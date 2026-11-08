package de.stranger.vsdh.compat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorShutdownTest {
    @Test void resourceClosureWaitsForAnInterruptedWorkerToActuallyFinish() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var resourceClosed = new AtomicBoolean();
        var usedAfterClosure = new AtomicBoolean();
        executor.execute(() -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException ignored) {
                cancelled.countDown();
                try { release.await(); } catch (InterruptedException failure) { throw new AssertionError(failure); }
            }
            usedAfterClosure.set(resourceClosed.get());
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        executor.shutdownNow();
        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        Thread closer = Thread.ofPlatform().start(() -> {
            ExecutorShutdown.awaitStopped(executor, Duration.ofSeconds(2));
            resourceClosed.set(true);
        });
        try {
            assertFalse(resourceClosed.get());
            release.countDown();
            closer.join(2500);
            assertFalse(closer.isAlive());
            assertTrue(resourceClosed.get());
            assertFalse(usedAfterClosure.get(), "resource closed while worker was still using it");
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void refusesResourceClosureWhenWorkerDoesNotStop() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        executor.execute(() -> {
            entered.countDown();
            boolean finished = false;
            while (!finished) {
                try { release.await(); finished = true; } catch (InterruptedException ignored) {}
            }
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            executor.shutdownNow();
            assertThrows(IllegalStateException.class, () -> ExecutorShutdown.awaitStopped(executor, Duration.ofMillis(10)));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
