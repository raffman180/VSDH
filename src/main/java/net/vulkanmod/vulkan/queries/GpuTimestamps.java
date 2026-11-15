package net.vulkanmod.vulkan.queries;

import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Opt-in GPU pass timing ({@code -Dvsdh.gpuProfile=true}). Each frame slot owns one timestamp
 * pool; a labelled mark measures the GPU work recorded since the previous mark. Results are read
 * without waiting, after the frame's own fence, and averaged over a reporting window.
 * <p>
 * Render thread only. Disabled builds pay a single static boolean test per mark.
 */
public final class GpuTimestamps {
    public static final boolean ENABLED = Boolean.getBoolean("vsdh.gpuProfile");
    private static final Logger LOGGER = LoggerFactory.getLogger("VSDH-GpuProfile");
    private static final int SLOTS = 8, MARKS = 128;
    private static final long REPORT_NANOS = 5_000_000_000L;

    private static long[] pools;
    private static final String[][] labels = new String[SLOTS][MARKS];
    private static final int[] counts = new int[SLOTS];
    private static final boolean[] pending = new boolean[SLOTS];
    private static boolean unsupported;
    private static double nanosPerTick;
    private static VkCommandBuffer recording;
    private static int recordingSlot = -1;

    private static final Map<String, double[]> window = new LinkedHashMap<>();
    private static int windowFrames, idleFrames;
    private static double idleSum;
    private static long lastFrameEnd;
    private static boolean continuous;
    private static long windowStart;
    private static volatile Map<String, Double> lastAverages = Map.of();
    private static volatile int lastFrames;

    private GpuTimestamps() {}

    /**
     * Called once per frame right after its command buffer begins, outside any render pass and
     * after the slot's fence was waited on.
     */
    public static void beginFrame(VkCommandBuffer command, int frame) {
        recording = null;
        if (!ENABLED || unsupported || frame < 0 || frame >= SLOTS || !ensurePools()) return;
        if (pending[frame]) collect(frame);
        else continuous = false;
        pending[frame] = false;
        vkCmdResetQueryPool(command, pools[frame], 0, MARKS);
        counts[frame] = 0;
        recording = command;
        recordingSlot = frame;
        // TOP_OF_PIPE is not held by the swapchain-image wait (COLOR_ATTACHMENT_OUTPUT); the
        // following BOTTOM_OF_PIPE mark is, so "imageWait" is the GPU time spent waiting for it.
        vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, pools[frame], 0);
        labels[frame][0] = "start";
        counts[frame] = 1;
        mark("imageWait");
    }

    private static long batchPool;
    private static final boolean[] batchWritten = new boolean[SLOTS];
    private static double batchSum, gapBeforeBatchSum, gapAfterBatchSum;
    private static int batchFrames;

    /** The frame's upload batch (submitted before the frame) begins recording in {@code command}. */
    public static void beginBatch(VkCommandBuffer command, int frame) {
        if (!ENABLED || unsupported || frame < 0 || frame >= SLOTS || !ensurePools()) return;
        if (batchPool == 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var info = VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(SLOTS * 2);
                LongBuffer handle = stack.mallocLong(1);
                if (vkCreateQueryPool(DeviceManager.vkDevice, info, null, handle) != VK_SUCCESS) return;
                batchPool = handle.get(0);
            }
        }
        // The slot's fence passed: the previous batch of this slot is complete.
        batchValid[frame] = false;
        if (batchWritten[frame]) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer values = stack.mallocLong(2);
                if (vkGetQueryPoolResults(DeviceManager.vkDevice, batchPool, frame * 2, 2, values, 8, VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
                    batchTimes[frame * 2] = values.get(0);
                    batchTimes[frame * 2 + 1] = values.get(1);
                    batchValid[frame] = true;
                }
            }
        }
        vkCmdResetQueryPool(command, batchPool, frame * 2, 2);
        vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, batchPool, frame * 2);
        batchWritten[frame] = false;
    }

    private static final long[] batchTimes = new long[SLOTS * 2];
    private static final boolean[] batchValid = new boolean[SLOTS];

    public static void endBatch(VkCommandBuffer command, int frame) {
        if (!ENABLED || batchPool == 0 || frame < 0 || frame >= SLOTS) return;
        vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, batchPool, frame * 2 + 1);
        batchWritten[frame] = true;
    }

    /** The frame continues in another command buffer of the same slot (Renderer.submitPartial). */
    public static void continueIn(VkCommandBuffer command) {
        if (recording != null) recording = command;
    }

    /**
     * Late swapchain wait (Renderer.submitBeforeSwapchain): the frame's earlier commands were submitted without
     * the swapchain image wait and the frame continues in {@code command}, whose submission carries that wait.
     * "lateSubmitGap" is the time between the early part's last mark and the start of this buffer (the GPU ran
     * out of work before the rest was submitted); "lateImageWait" is what is left of the image wait after that.
     * On the Vulkan swapchain the wait holds COLOR_ATTACHMENT_OUTPUT only, so the TOP_OF_PIPE stamp is not held by
     * it; on the DXGI path the wait holds every stage and both labels together are the wait.
     */
    public static void continueAfterEarlySubmit(VkCommandBuffer command) {
        if (!ENABLED || recording == null) return;
        recording = command;
        int slot = recordingSlot, index = counts[slot];
        if (index >= MARKS) return;
        vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, pools[slot], index);
        labels[slot][index] = "lateSubmitGap";
        counts[slot] = index + 1;
        mark("lateImageWait");
    }

    /** Attributes the GPU work recorded since the previous mark to {@code label}. */
    public static void mark(String label) {
        if (!ENABLED || recording == null) return;
        int slot = recordingSlot, index = counts[slot];
        if (index >= MARKS) return;
        vkCmdWriteTimestamp(recording, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pools[slot], index);
        labels[slot][index] = label;
        counts[slot] = index + 1;
    }

    /** Last mark of the frame, recorded before the main pass closes its command buffer. */
    public static void endFrame() {
        if (!ENABLED) return;
        mark("gui");
        recording = null;
    }

    /** The frame's command buffer was submitted; its results may be read once its fence passed. */
    public static void submitted(int frame) {
        if (!ENABLED || frame < 0 || frame >= SLOTS) return;
        pending[frame] = counts[frame] >= 2;
    }

    /** Average milliseconds per frame for each label in the last completed window. */
    public static Map<String, Double> lastAverages() { return lastAverages; }
    public static int lastWindowFrames() { return lastFrames; }

    private static boolean ensurePools() {
        if (pools != null) return true;
        var properties = DeviceManager.deviceProperties;
        if (properties == null || !properties.limits().timestampComputeAndGraphics()) {
            unsupported = true;
            LOGGER.warn("GPU timestamps are not supported on the graphics queue; profiling disabled");
            return false;
        }
        nanosPerTick = properties.limits().timestampPeriod();
        pools = new long[SLOTS];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(MARKS);
            LongBuffer handle = stack.mallocLong(1);
            for (int i = 0; i < SLOTS; i++) {
                int result = vkCreateQueryPool(DeviceManager.vkDevice, info, null, handle);
                if (result != VK_SUCCESS) {
                    for (int j = 0; j < i; j++) vkDestroyQueryPool(DeviceManager.vkDevice, pools[j], null);
                    pools = null;
                    unsupported = true;
                    LOGGER.warn("Cannot create GPU timestamp pool: {}", result);
                    return false;
                }
                pools[i] = handle.get(0);
            }
        }
        windowStart = System.nanoTime();
        LOGGER.info("GPU pass profiling enabled: {} ns per tick", nanosPerTick);
        return true;
    }

    private static void collect(int slot) {
        int count = counts[slot];
        if (count < 2) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer values = stack.mallocLong(count);
            int result = vkGetQueryPoolResults(DeviceManager.vkDevice, pools[slot], 0, count, values, 8, VK_QUERY_RESULT_64_BIT);
            if (result != VK_SUCCESS) { continuous = false; return; }
            long previous = values.get(0);
            // Collection follows submission order, so the gap to the previous frame's last mark is
            // the time the GPU sat idle waiting for this frame's commands.
            if (continuous && previous >= lastFrameEnd) {
                double idle = (previous - lastFrameEnd) * nanosPerTick / 1_000_000.0;
                if (idle < 100) { idleSum += idle; idleFrames++; }
                long bStart = batchTimes[slot * 2], bEnd = batchTimes[slot * 2 + 1];
                if (SPIKES && idle >= 5 && idle < 1000) {
                    // The GPU sat idle between two frames: who held it, the upload batch or the time before it?
                    boolean batch = batchValid[slot] && bStart >= lastFrameEnd && bEnd <= previous && bEnd >= bStart;
                    LOGGER.info(String.format(java.util.Locale.ROOT, "GPU_IDLE gapMs=%.2f beforeBatchMs=%s batchMs=%s afterBatchMs=%s",
                            idle, batch ? String.format(java.util.Locale.ROOT, "%.2f", (bStart - lastFrameEnd) * nanosPerTick / 1_000_000.0) : "-",
                            batch ? String.format(java.util.Locale.ROOT, "%.2f", (bEnd - bStart) * nanosPerTick / 1_000_000.0) : "-",
                            batch ? String.format(java.util.Locale.ROOT, "%.2f", (previous - bEnd) * nanosPerTick / 1_000_000.0) : "-"));
                }
                if (batchValid[slot] && bStart >= lastFrameEnd && bEnd <= previous && bEnd >= bStart) {
                    gapBeforeBatchSum += (bStart - lastFrameEnd) * nanosPerTick / 1_000_000.0;
                    batchSum += (bEnd - bStart) * nanosPerTick / 1_000_000.0;
                    gapAfterBatchSum += (previous - bEnd) * nanosPerTick / 1_000_000.0;
                    batchFrames++;
                }
            }
            lastFrameEnd = values.get(count - 1);
            continuous = true;
            double frameMs = (values.get(count - 1) - values.get(0)) * nanosPerTick / 1_000_000.0;
            boolean spike = SPIKES && typicalFrameMs > 0 && frameMs > Math.max(1.6 * typicalFrameMs, typicalFrameMs + 2.5);
            typicalFrameMs = typicalFrameMs == 0 ? frameMs : typicalFrameMs + 0.02 * (Math.min(frameMs, 3 * typicalFrameMs) - typicalFrameMs);
            StringBuilder spikeLine = spike ? new StringBuilder() : null;
            double[] passMs = spike ? new double[count] : null;
            for (int i = 1; i < count; i++) {
                long current = values.get(i);
                double millis = Math.max(0, current - previous) * nanosPerTick / 1_000_000.0;
                window.computeIfAbsent(labels[slot][i], ignored -> new double[1])[0] += millis;
                if (spike) passMs[i] = millis;
                previous = current;
            }
            if (spike) {
                // -Dvsdh.gpuSpikes=true: the passes that made one GPU frame much longer than usual.
                Integer[] order = new Integer[count - 1];
                for (int i = 1; i < count; i++) order[i - 1] = i;
                java.util.Arrays.sort(order, (x, y) -> Double.compare(passMs[y], passMs[x]));
                for (int i = 0; i < Math.min(6, order.length); i++)
                    spikeLine.append(' ').append(labels[slot][order[i]]).append('=')
                            .append(String.format(java.util.Locale.ROOT, "%.2f", passMs[order[i]]));
                LOGGER.info(String.format(java.util.Locale.ROOT, "GPU_SPIKE frameMs=%.2f typicalMs=%.2f marks=%d top:%s",
                        frameMs, typicalFrameMs, count, spikeLine));
            }
        }
        windowFrames++;
        long now = System.nanoTime();
        if (now - windowStart >= REPORT_NANOS) report(now);
    }

    private static final boolean SPIKES = Boolean.getBoolean("vsdh.gpuSpikes");
    private static double typicalFrameMs;

    private static void report(long now) {
        var averages = new LinkedHashMap<String, Double>();
        double total = 0;
        var line = new StringBuilder();
        for (var entry : window.entrySet()) {
            double average = entry.getValue()[0] / windowFrames;
            averages.put(entry.getKey(), average);
            total += average;
            line.append(' ').append(entry.getKey()).append('=').append(String.format(java.util.Locale.ROOT, "%.3f", average));
        }
        double idle = idleFrames == 0 ? 0 : idleSum / idleFrames;
        averages.put("gpuIdleBetweenFrames", idle);
        lastAverages = java.util.Collections.unmodifiableMap(averages);
        lastFrames = windowFrames;
        LOGGER.info("GPU_PROFILE frames={} totalMs={} idleBetweenFramesMs={}{}", windowFrames,
                String.format(java.util.Locale.ROOT, "%.3f", total), String.format(java.util.Locale.ROOT, "%.3f", idle), line);
        if (batchFrames > 0) LOGGER.info(String.format(java.util.Locale.ROOT,
                "GPU_GAP frames=%d beforeUploadBatchMs=%.3f uploadBatchMs=%.3f afterUploadBatchMs=%.3f",
                batchFrames, gapBeforeBatchSum / batchFrames, batchSum / batchFrames, gapAfterBatchSum / batchFrames));
        batchFrames = 0;
        batchSum = gapBeforeBatchSum = gapAfterBatchSum = 0;
        window.clear();
        windowFrames = 0;
        idleSum = 0;
        idleFrames = 0;
        windowStart = now;
    }
}
