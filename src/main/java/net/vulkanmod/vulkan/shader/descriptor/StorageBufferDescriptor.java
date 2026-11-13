package net.vulkanmod.vulkan.shader.descriptor;

import java.util.function.LongSupplier;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A shader storage buffer bound whole at one binding. The buffer is borrowed: the supplier is
 * asked at every descriptor update, so a replaced buffer is picked up and nothing here frees it.
 */
public final class StorageBufferDescriptor implements Descriptor {
    private final int binding;
    private final int stages;
    private final LongSupplier buffer;

    public StorageBufferDescriptor(int binding, int stages, LongSupplier buffer) {
        this.binding = binding;
        this.stages = stages;
        this.buffer = buffer;
    }

    @Override public int getBinding() { return binding; }
    @Override public int getType() { return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; }
    @Override public int getStages() { return stages; }

    /** The VkBuffer to bind now; never VK_NULL_HANDLE. */
    public long buffer() {
        long handle = buffer.getAsLong();
        if (handle == VK_NULL_HANDLE) throw new IllegalStateException("Storage buffer at binding " + binding + " is not allocated");
        return handle;
    }
}
