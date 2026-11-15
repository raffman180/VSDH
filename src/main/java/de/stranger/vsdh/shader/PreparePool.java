package de.stranger.vsdh.shader;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The workers that translate and compile the programs of a pack side by side. The thread that reads the pack (the
 * pack source is single-threaded) hands each program over as soon as its files are flattened and takes the results
 * back in the order the plan asks for them, so the outcome does not depend on which worker finished first.
 * <p>
 * CPU only: no worker touches Vulkan, Minecraft or the pack archive. shaderc is used with one compiler and one
 * options object per task (shaderc.h: concurrent calls on different objects need no synchronisation).
 * <p>
 * {@code -Dvsdh.prepareThreads=N}: the number of workers; 0 restores the sequential preparation on the calling thread
 * (no read-ahead, no shared results). Default: processors - 2, at least 1, at most 16. Packs prepared ahead
 * ({@link #background}) use at most a quarter of that at the lowest priority.
 */
public final class PreparePool {
    private PreparePool() {}

    static final int THREADS = threads(System.getProperty("vsdh.prepareThreads"), Runtime.getRuntime().availableProcessors());

    /** Workers for a pack the player asked for and for one prepared ahead. */
    private static final ThreadPoolExecutor REQUESTED = executor("VSDH shader compile", THREADS, Thread.NORM_PRIORITY - 1);
    private static final ThreadPoolExecutor AHEAD = executor("VSDH shader warm-up compile", aheadThreads(THREADS), Thread.MIN_PRIORITY);

    private static final ThreadLocal<Boolean> BACKGROUND = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** The worker count for a configured value (null or unreadable: the default for this many processors). */
    static int threads(String configured, int processors) {
        int fallback = Math.max(1, Math.min(16, processors - 2));
        if (configured == null || configured.isBlank()) return fallback;
        try {
            return Math.max(0, Math.min(64, Integer.parseInt(configured.strip())));
        } catch (NumberFormatException unreadable) {
            return fallback;
        }
    }

    /** A pack prepared ahead takes a quarter of the workers, at least one; none when preparation is sequential. */
    static int aheadThreads(int threads) {
        return threads == 0 ? 0 : Math.max(1, threads / 4);
    }

    private static ThreadPoolExecutor executor(String name, int threads, int priority) {
        if (threads == 0) return null;
        var number = new AtomicInteger();
        var executor = new ThreadPoolExecutor(threads, threads, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            var thread = new Thread(task, name + " " + number.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        });
        executor.allowCoreThreadTimeOut(true); // no idle threads between two pack changes
        return executor;
    }

    /** False with {@code -Dvsdh.prepareThreads=0}: every program is then prepared where it is asked for. */
    public static boolean parallel() { return THREADS > 0; }

    /** Runs {@code work} with its programs on the few lowest-priority workers (packs prepared ahead). */
    public static <T> T background(Callable<T> work) throws Exception {
        boolean before = BACKGROUND.get();
        BACKGROUND.set(Boolean.TRUE);
        try {
            return work.call();
        } finally {
            BACKGROUND.set(before);
        }
    }

    /**
     * While the player waits for a pack, a preparation that began as a warm-up gets all workers instead of its
     * quarter (the request may be waiting for exactly that pack, or behind it for its own turn).
     */
    public static void urgent(boolean on) {
        if (AHEAD == null) return;
        int size = on ? THREADS : aheadThreads(THREADS);
        if (size > AHEAD.getMaximumPoolSize()) {
            AHEAD.setMaximumPoolSize(size);
            AHEAD.setCorePoolSize(size);
        } else {
            AHEAD.setCorePoolSize(size);
            AHEAD.setMaximumPoolSize(size);
        }
    }

    /** Queues {@code work}; sequential mode runs it here and hands back the finished result or failure. */
    static <T> Future<T> submit(Callable<T> work) {
        if (!parallel()) {
            var done = new CompletableFuture<T>();
            try {
                done.complete(work.call());
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
            return done;
        }
        return (BACKGROUND.get() ? AHEAD : REQUESTED).submit(work);
    }
}
