/* Adapted from Braffolk's DH Vulkan extension, LGPL-3.0. See NOTICE. */
package com.braffolk.dhvulkan.api;

import com.braffolk.dhvulkan.core.VulkanBackend;
import com.braffolk.dhvulkan.core.data.VkVertexData;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/** Copies DH's temporary upload data; Vulkan work only happens during draw. */
public final class VkVertexBufferWrapper implements IVertexBufferWrapper {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private final VulkanBackend backend;
    private final VkVertexData data = new VkVertexData(NEXT_ID.getAndIncrement());
    private volatile int vertexCount;

    public VkVertexBufferWrapper(VulkanBackend backend) { this.backend = backend; }

    @Override public void uploadVertexBuffer(ByteBuffer source, int count) {
        if (source.remaining() % LodQuadBuilder.BYTES_PER_QUAD != 0) {
            throw new IllegalArgumentException("DH terrain upload must contain complete quads");
        }
        ByteBuffer copy = MemoryUtil.memAlloc(source.remaining());
        copy.put(source.duplicate()).flip();
        synchronized (data) {
            if (data.replace(copy, () -> MemoryUtil.memFree(copy))) {
                vertexCount = copy.remaining() / LodQuadBuilder.BYTES_PER_VERTEX;
            }
        }
    }

    @Override public void uploadIndexBuffer(ByteBuffer buffer, int count) {
        // useSingleIbo() is true: VulkanBackend generates shared quad indices.
    }
    @Override public int getVertexCount() { return vertexCount; }

    @Override public void close() {
        synchronized (data) {
            if (data.isClosed()) return;
            data.close();
            vertexCount = 0;
            backend.queueDataFree(data);
        }
    }
    VkVertexData getVertexData() { return data; }
    int getIndexCount() { return vertexCount; }
}
