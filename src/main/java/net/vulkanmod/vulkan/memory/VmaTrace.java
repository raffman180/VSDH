package net.vulkanmod.vulkan.memory;

import org.lwjgl.util.vma.VmaAllocateDeviceMemoryFunction;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaDeviceMemoryCallbacks;
import org.lwjgl.util.vma.VmaFreeDeviceMemoryFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic only ({@code -Dvsdh.vmaTrace=true}): logs every vkAllocateMemory/vkFreeMemory the allocator makes, so stalls
 * between frames can be laid next to driver memory operations (Windows video memory management). Off by default; the
 * callbacks are never registered then.
 */
public final class VmaTrace {
    private static final Logger LOGGER = LoggerFactory.getLogger("VSDH-VmaTrace");
    public static final boolean ENABLED = Boolean.getBoolean("vsdh.vmaTrace");

    // Kept reachable for the allocator's lifetime: VMA stores the function pointers, not the Java objects.
    private static VmaAllocateDeviceMemoryFunction allocate;
    private static VmaFreeDeviceMemoryFunction free;
    private static VmaDeviceMemoryCallbacks callbacks;

    private VmaTrace() {}

    public static void install(VmaAllocatorCreateInfo info) {
        if (!ENABLED) return;
        allocate = VmaAllocateDeviceMemoryFunction.create((allocator, memoryType, memory, size, userData) ->
                LOGGER.info("VMA_ALLOC type={} sizeKiB={} thread={}", memoryType, size >> 10, Thread.currentThread().getName()));
        free = VmaFreeDeviceMemoryFunction.create((allocator, memoryType, memory, size, userData) ->
                LOGGER.info("VMA_FREE type={} sizeKiB={} thread={}", memoryType, size >> 10, Thread.currentThread().getName()));
        callbacks = VmaDeviceMemoryCallbacks.calloc().pfnAllocate(allocate).pfnFree(free);
        info.pDeviceMemoryCallbacks(callbacks);
        LOGGER.info("Device memory allocations are logged (vsdh.vmaTrace)");
    }
}
