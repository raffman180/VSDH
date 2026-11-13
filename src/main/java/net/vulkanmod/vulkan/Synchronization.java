package net.vulkanmod.vulkan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class Synchronization {
    private static final int ALLOCATION_SIZE = 50;

    public static final Synchronization INSTANCE = new Synchronization(ALLOCATION_SIZE);

    private final LongBuffer fences;
    private int idx = 0;

    private ObjectArrayList<CommandPool.CommandBuffer> commandBuffers = new ObjectArrayList<>();

    Synchronization(int allocSize) {
        this.fences = MemoryUtil.memAllocLong(allocSize);
    }

    public synchronized void addCommandBuffer(CommandPool.CommandBuffer commandBuffer) {
        this.addFence(commandBuffer.getFence());
        this.commandBuffers.add(commandBuffer);
    }

    /** Opt-in ({@code -Dvsdh.syncTrace=true}): who makes the next frame submission wait on the CPU. */
    private static final boolean TRACE = Boolean.getBoolean("vsdh.syncTrace");
    private final java.util.Map<String, int[]> traced = new java.util.HashMap<>();
    private long traceStart = System.nanoTime();

    private void trace() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        StringBuilder key = new StringBuilder();
        for (int i = 2, used = 0; i < stack.length && used < 7; i++) {
            String owner = stack[i].getClassName();
            if (owner.startsWith("net.vulkanmod.vulkan.Synchronization")) continue;
            key.append(owner.substring(owner.lastIndexOf('.') + 1)).append('.').append(stack[i].getMethodName()).append(" < ");
            used++;
        }
        traced.computeIfAbsent(key.toString(), ignored -> new int[1])[0]++;
        long now = System.nanoTime();
        if (now - traceStart > 5_000_000_000L) {
            traced.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(8)
                    .forEach(entry -> net.vulkanmod.Initializer.LOGGER.info("SYNC_TRACE {} per 5s: {}", entry.getValue()[0], entry.getKey()));
            traced.clear();
            traceStart = now;
        }
    }

    public synchronized void addFence(long fence) {
        if (TRACE) trace();
        if (idx == ALLOCATION_SIZE)
            waitFences();

        fences.put(idx, fence);
        idx++;
    }

    public synchronized void waitFences() {
        if (idx == 0)
            return;

        VkDevice device = Vulkan.getVkDevice();

        fences.limit(idx);

        vkWaitForFences(device, fences, true, VUtil.UINT64_MAX);

        this.commandBuffers.forEach(CommandPool.CommandBuffer::reset);
        this.commandBuffers.clear();

        fences.limit(ALLOCATION_SIZE);
        idx = 0;
    }

    public static void waitFence(long fence) {
        VkDevice device = Vulkan.getVkDevice();

        vkWaitForFences(device, fence, true, VUtil.UINT64_MAX);
    }

    public static boolean checkFenceStatus(long fence) {
        VkDevice device = Vulkan.getVkDevice();
        return vkGetFenceStatus(device, fence) == VK_SUCCESS;
    }

}
