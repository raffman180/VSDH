/* Adapted from Braffolk's DH Vulkan extension, LGPL-3.0. See NOTICE. */
package com.braffolk.dhvulkan.core.data;

import java.nio.ByteBuffer;

/**
 * Owns a staged CPU upload. Draw/upload/close synchronize on this object, so a
 * DH worker cannot free native memory while the render thread is copying it.
 * GPU resources are owned separately by the render-thread cache.
 */
public final class VkVertexData {
    public final int id;
    public ByteBuffer vertexBuffer;
    public int byteSize;
    public int handleIdentity;
    private Runnable release;
    private boolean closed;

    public VkVertexData(int id) { this.id = id; }

    /** Takes ownership, including when a late upload races with close(). */
    public synchronized boolean replace(ByteBuffer buffer, Runnable releaseBuffer) {
        if (closed) {
            releaseBuffer.run();
            return false;
        }
        clearData();
        vertexBuffer = buffer;
        byteSize = buffer.remaining();
        handleIdentity++;
        release = releaseBuffer;
        return true;
    }

    /** Releases staging memory after upload; the GPU cache stays valid. */
    public synchronized void clearData() {
        vertexBuffer = null;
        byteSize = 0;
        Runnable oldRelease = release;
        release = null;
        if (oldRelease != null) oldRelease.run();
    }

    public synchronized void close() {
        closed = true;
        clearData();
    }
    public synchronized boolean isClosed() { return closed; }
}
