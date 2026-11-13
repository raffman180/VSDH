package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.vitrail.pack.model.BufferObject;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The pack's {@code bufferObject.N} storage buffers for one shader graph. Like Iris they start
 * zeroed, persist across frames and are shared by every program that declares the block; they
 * are recreated with the graph (resize, dimension change). Render thread only.
 */
public final class NativeStorageBuffers implements AutoCloseable {
    private static NativeStorageBuffers current;

    private record Allocation(long buffer, long memory, long size) {}

    private final Map<Integer, Allocation> buffers = new HashMap<>();
    private boolean zeroed, closed;

    public NativeStorageBuffers(BufferObject.Reading reading, int width, int height) {
        RenderSystem.assertOnRenderThread();
        long limit = DeviceManager.deviceProperties.limits().maxStorageBufferRange() & 0xffffffffL;
        try {
            for (var object : reading.buffers()) {
                long size = object.relative()
                        ? Math.multiplyExact(object.size(), Math.multiplyExact((long) Math.ceil(width * object.scaleX()), (long) Math.ceil(height * object.scaleY())))
                        : object.size();
                if (size <= 0) continue;
                size = (size + 3) & ~3L;
                if (size > limit) throw new IllegalArgumentException("bufferObject." + object.index() + " exceeds the device storage limit: " + size);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    LongBuffer buffer = stack.mallocLong(1);
                    PointerBuffer memory = stack.pointers(VK_NULL_HANDLE);
                    MemoryManager.getInstance().createBuffer(size,
                            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                                    | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, // indirect.<pass> reads its counts here
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, buffer, memory);
                    buffers.put(object.index(), new Allocation(buffer.get(0), memory.get(0), size));
                }
            }
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    /** The graph whose programs bind these buffers; set before its programs are created. */
    static void install(NativeStorageBuffers buffers) { RenderSystem.assertOnRenderThread(); current = buffers; }
    static NativeStorageBuffers current() { return current; }

    public boolean has(int index) { return buffers.containsKey(index); }

    /** The VkBuffer of bufferObject.{@code index}, or VK_NULL_HANDLE once closed or never declared. */
    public long buffer(int index) {
        var allocation = closed ? null : buffers.get(index);
        return allocation == null ? VK_NULL_HANDLE : allocation.buffer();
    }

    /** Zero every buffer once, on the first frame, before any program reads it. */
    void prepareFrame() {
        RenderSystem.assertOnRenderThread();
        if (zeroed || closed || buffers.isEmpty()) return;
        Renderer.getInstance().endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var command = Renderer.getCommandBuffer();
            for (var allocation : buffers.values()) vkCmdFillBuffer(command, allocation.buffer(), 0, VK_WHOLE_SIZE, 0);
            var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, barrier, null, null);
        }
        zeroed = true;
    }

    /**
     * Makes shader writes to buffers and images visible to every later shader read, which is what
     * Iris gets from glMemoryBarrier between its passes. Must be recorded outside a render pass.
     */
    static void shaderWriteBarrier() {
        Renderer.getInstance().endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
            int stages = VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            // DRAW_INDIRECT: an indirect.<pass> dispatch may read counts a graphics pass just wrote.
            vkCmdPipelineBarrier(Renderer.getCommandBuffer(), stages, stages | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, 0, barrier, null, null);
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        if (current == this) current = null;
        for (var allocation : buffers.values())
            MemoryManager.getInstance().addFrameOp(() -> MemoryManager.freeBuffer(allocation.buffer(), allocation.memory()));
        buffers.clear();
    }
}
