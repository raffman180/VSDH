package com.braffolk.dhvulkan.core.data;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VkVertexDataTest {
    @Test void uploadAndRepeatedCloseReleaseEachAllocationOnce() {
        VkVertexData data = new VkVertexData(1);
        AtomicInteger frees = new AtomicInteger();
        data.replace(ByteBuffer.allocate(32), frees::incrementAndGet);
        data.clearData();
        data.close();
        data.close();
        assertEquals(1, frees.get());
    }

    @Test void replacementInvalidatesTheGpuGenerationAndReleasesOldStaging() {
        VkVertexData data = new VkVertexData(2);
        AtomicInteger frees = new AtomicInteger();
        data.replace(ByteBuffer.allocate(16), frees::incrementAndGet);
        int oldGeneration = data.handleIdentity;
        data.replace(ByteBuffer.allocate(64), frees::incrementAndGet);
        assertNotEquals(oldGeneration, data.handleIdentity);
        assertEquals(1, frees.get());
        assertEquals(64, data.byteSize);
        data.close();
        assertEquals(2, frees.get());
    }

    @Test void lateWorkerUploadAfterCloseIsReleasedWithoutResurrectingTheBuffer() {
        VkVertexData data = new VkVertexData(3);
        data.close();
        AtomicInteger frees = new AtomicInteger();
        assertFalse(data.replace(ByteBuffer.allocate(16), frees::incrementAndGet));
        assertEquals(1, frees.get());
        assertNull(data.vertexBuffer);
        assertTrue(data.isClosed());
    }

    @Test void workerCannotFreeStagingWhileRenderThreadOwnsUpload() throws Exception {
        VkVertexData data = new VkVertexData(4);
        AtomicInteger frees = new AtomicInteger();
        data.replace(ByteBuffer.allocate(16), frees::incrementAndGet);
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        Thread worker;
        synchronized (data) {
            worker = Thread.ofPlatform().start(() -> {
                closing.countDown();
                data.close();
                closed.countDown();
            });
            assertTrue(closing.await(2, TimeUnit.SECONDS));
            assertFalse(closed.await(30, TimeUnit.MILLISECONDS));
            assertEquals(0, frees.get());
            assertNotNull(data.vertexBuffer);
        }
        worker.join(2000);
        assertFalse(worker.isAlive());
        assertEquals(1, frees.get());
    }
}
