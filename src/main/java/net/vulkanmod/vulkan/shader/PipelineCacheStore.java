package net.vulkanmod.vulkan.shader;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The one VkPipelineCache every graphics and compute pipeline of the renderer is created with
 * ({@link GraphicsPipeline}, {@link ComputePipeline}), loaded from and written back to
 * {@code config/vulkanmod_pipeline_cache.bin}.
 * <p>
 * Threading. The cache is created without VK_PIPELINE_CACHE_CREATE_EXTERNALLY_SYNCHRONIZED_BIT, so the
 * implementation synchronizes it itself: vkCreateGraphicsPipelines asks for external synchronization of
 * pipelineCache only when that bit was set (VUID-vkCreateGraphicsPipelines-pipelineCache-02876), and
 * vkGetPipelineCacheData lists no externally synchronized parameter. Only vkDestroyPipelineCache needs it. All
 * Vulkan calls here still run on the render thread (project rule, and the counters are plain fields); the file
 * write alone runs on a worker with a copied byte array, and {@link #destroy()} waits for that worker.
 * <p>
 * {@code -Dvsdh.pipelineCacheSave=false} keeps only the save at exit.
 */
public final class PipelineCacheStore {
    private static final Path PATH = Path.of("config", "vulkanmod_pipeline_cache.bin");
    private static final boolean SAVE = !"false".equals(System.getProperty("vsdh.pipelineCacheSave"));
    /** No pipeline for this long: the burst is over, snapshot it. */
    private static final long QUIET_NANOS = 3_000_000_000L;
    /** A burst that never pauses is snapshotted this long after its first pipeline. */
    private static final long LATEST_NANOS = 20_000_000_000L;

    private static long handle;
    private static long loadedBytes;
    private static long lastSavedSize = -1;
    private static long created;
    private static long createdNanos;
    private static long unsaved;
    private static long unsavedNanos;
    private static long lastCreated;
    private static long firstUnsaved;
    private static volatile boolean writing;
    private static volatile boolean writeFailed;
    private static Thread writer;

    private PipelineCacheStore() {
    }

    static long create() {
        byte[] bytes = load();
        handle = create(bytes);
        if (handle == VK_NULL_HANDLE && bytes != null) {
            Initializer.LOGGER.warn("Pipeline cache: the driver refused {}; starting empty", PATH);
            bytes = null;
            handle = create(null);
        }
        if (handle == VK_NULL_HANDLE) throw new RuntimeException("Failed to create pipeline cache");
        loadedBytes = bytes == null ? 0 : bytes.length;
        lastSavedSize = bytes == null ? -1 : bytes.length;
        Initializer.LOGGER.info("Pipeline cache: {} KiB loaded from {}", loadedBytes >> 10, PATH);
        return handle;
    }

    private static byte[] load() {
        try {
            if (!Files.isRegularFile(PATH)) return null;
            long size = Files.size(PATH);
            if (size > PipelineCacheFile.MAX_FILE_BYTES) {
                Initializer.LOGGER.warn("Pipeline cache: {} ignored ({} MiB)", PATH, size >> 20);
                return null;
            }
            byte[] bytes = Files.readAllBytes(PATH);
            var properties = DeviceManager.deviceProperties;
            byte[] uuid = new byte[PipelineCacheFile.UUID_SIZE];
            properties.pipelineCacheUUID().get(0, uuid);
            String rejection = PipelineCacheFile.rejection(bytes, properties.vendorID(), properties.deviceID(), uuid);
            if (rejection != null) {
                Initializer.LOGGER.info("Pipeline cache: {} not used ({}); starting empty", PATH, rejection);
                return null;
            }
            return bytes;
        } catch (Exception | OutOfMemoryError failure) {
            Initializer.LOGGER.warn("Pipeline cache: cannot read {}: {}", PATH, failure.toString());
            return null;
        }
    }

    private static long create(byte[] initial) {
        ByteBuffer data = null;
        try (MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            if (initial != null) {
                data = MemoryUtil.memAlloc(initial.length);
                data.put(0, initial);
                info.pInitialData(data);
            }
            LongBuffer result = stack.mallocLong(1);
            return vkCreatePipelineCache(DeviceManager.vkDevice, info, null, result) == VK_SUCCESS
                    ? result.get(0) : VK_NULL_HANDLE;
        } finally {
            if (data != null) MemoryUtil.memFree(data);
        }
    }

    /** One vkCreate*Pipelines call that took {@code nanos}. */
    static void created(long nanos) {
        long now = System.nanoTime();
        created++;
        createdNanos += nanos;
        if (unsaved++ == 0) firstUnsaved = now;
        unsavedNanos += nanos;
        lastCreated = now;
    }

    /** Pipelines created since start; with {@link #createdNanos()} for "N created in X ms" reports. */
    public static long createdCount() { return created; }
    /** Time spent inside vkCreate*Pipelines since start. */
    public static long createdNanos() { return createdNanos; }
    /** Size of the cache file accepted at start (0: started empty). */
    public static long loadedBytes() { return loadedBytes; }

    /** No pipeline was created during the quiet interval: a burst of creations has ended. */
    public static boolean quiet() {
        return System.nanoTime() - lastCreated >= QUIET_NANOS;
    }

    /** Render thread, once per frame: one compare unless pipelines were created since the last snapshot. */
    public static void frame() {
        if (unsaved == 0 || !SAVE) return;
        if (!PipelineCacheFile.due(true, unsaved, writing, System.nanoTime(), lastCreated, firstUnsaved,
                QUIET_NANOS, LATEST_NANOS)) return;
        long count = unsaved, nanos = unsavedNanos;
        byte[] data = snapshot();
        unsaved = 0;
        unsavedNanos = 0;
        if (data == null) return;
        long size = data.length;
        writing = true;
        writer = new Thread(() -> {
            try {
                PipelineCacheFile.writeAtomically(PATH, data);
                Initializer.LOGGER.info("Pipeline cache saved: {} KiB ({} pipelines created in {} ms since the last save)",
                        size >> 10, count, nanos / 1_000_000);
            } catch (Exception | OutOfMemoryError failure) {
                writeFailed = true;
                Initializer.LOGGER.warn("Pipeline cache: cannot write {}: {}", PATH, failure.toString());
            } finally {
                writing = false;
            }
        }, "VSDH pipeline cache writer");
        writer.setDaemon(true);
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.start();
    }

    /** The cache contents when their size differs from the last save, else {@code null}. Render thread. */
    private static byte[] snapshot() {
        if (writeFailed) {
            writeFailed = false;
            lastSavedSize = -1;
        }
        ByteBuffer buffer = null;
        try (MemoryStack stack = stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            if (vkGetPipelineCacheData(DeviceManager.vkDevice, handle, size, null) != VK_SUCCESS) return null;
            long bytes = size.get(0);
            if (bytes > Integer.MAX_VALUE - 16 || !PipelineCacheFile.worthWriting(bytes, lastSavedSize)) return null;
            buffer = MemoryUtil.memAlloc((int) bytes);
            // VK_INCOMPLETE would mean the cache grew between the two calls; a later snapshot takes it.
            if (vkGetPipelineCacheData(DeviceManager.vkDevice, handle, size, buffer) != VK_SUCCESS) return null;
            byte[] data = new byte[(int) size.get(0)];
            buffer.get(0, data);
            lastSavedSize = data.length;
            return data;
        } catch (OutOfMemoryError failure) {
            return null;
        } finally {
            if (buffer != null) MemoryUtil.memFree(buffer);
        }
    }

    /** Clean shutdown: waits for a running write, saves what is new, destroys the cache. */
    static void destroy() {
        try {
            Thread running = writer;
            if (running != null) running.join(10_000);
            if (running == null || !running.isAlive()) { // never two writers on the temporary file
                byte[] data = snapshot();
                if (data != null) PipelineCacheFile.writeAtomically(PATH, data);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            Initializer.LOGGER.warn("Pipeline cache: cannot write {}: {}", PATH, failure.toString());
        }
        vkDestroyPipelineCache(DeviceManager.vkDevice, handle, null);
        handle = VK_NULL_HANDLE;
    }
}
