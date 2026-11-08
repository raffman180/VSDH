package de.stranger.vsdh.compat;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Finish already-cancelled workers before a caller releases resources they may still use. */
public final class ExecutorShutdown {
    private ExecutorShutdown() {}

    public static void awaitStopped(ExecutorService executor, Duration timeout) {
        awaitStopped(executor, timeout, null);
    }

    public static void awaitStopped(ExecutorService executor, Duration timeout, Runnable cancellation) {
        if (executor == null || executor.isTerminated()) return;
        if (!executor.isShutdown()) throw new IllegalStateException("Worker must be shut down before draining");
        long remaining = timeout.toNanos();
        long started = System.nanoTime();
        boolean interrupted = false;
        try {
            while (!executor.isTerminated()) {
                if (remaining <= 0) throw new IllegalStateException("DH worker did not stop within " + timeout
                        + "; refusing to close its database while it is still in use");
                try {
                    if (cancellation != null) cancellation.run();
                    long wait = cancellation == null ? remaining : Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25));
                    if (executor.awaitTermination(wait, TimeUnit.NANOSECONDS)) return;
                } catch (InterruptedException interruptedWait) {
                    // Closing a live SQL statement is unsafe even when the shutdown caller is interrupted.
                    // Preserve the signal after the bounded drain rather than losing it.
                    interrupted = true;
                }
                remaining = timeout.toNanos() - (System.nanoTime() - started);
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
