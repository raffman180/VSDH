package net.vulkanmod.vulkan;

/**
 * Late swapchain wait for shader-pack frames ({@code -Dvsdh.lateAcquire=true}, off by default until an in-game A/B).
 * <p>
 * A frame used to go to the GPU in one vkQueueSubmit that waits for the acquired swapchain image, so the GPU could
 * not start the world and pack passes before the presentation engine handed the image back (about 0.45 ms per frame,
 * 13-25 ms in Distant Horizons loading stalls, docs/PERFORMANCE.md). A pack frame does not need that image before
 * FINAL: everything earlier draws into pack targets. With the switch on, such a frame
 * <ol>
 * <li>leaves the default main pass unbegun ({@link #defersMainPass}), so nothing records a use of the image,</li>
 * <li>hands the upload batch and everything up to the COMPOSITE stage to the queue without the image wait and
 * without a fence ({@link Renderer#submitBeforeSwapchain}, decided by {@link #splits}),</li>
 * <li>records FINAL, hand and GUI into a second command buffer whose submission carries the image wait, the
 * render-finished signal and the frame fence, exactly as the single submission did.</li>
 * </ol>
 * Ordering rests on the Vulkan specification: a semaphore wait covers only its own batch and later commands in
 * submission order (Command Buffers, "Queue Submission", vkQueueSubmit), so the early batch does not wait; a fence
 * signal operation defined by vkQueueSubmit includes in its first synchronization scope "all commands that occur
 * earlier in submission order" (Synchronization, "Fences", fence signaling), and so do the semaphore signal
 * operations of that submission, so the frame fence and the render-finished semaphore of the second submission still
 * cover the first. Resources of the slot are therefore free again exactly when they were before.
 * <p>
 * Render thread only; the counters exist for the diagnostics line.
 */
public final class LateAcquire {
    public static final boolean ENABLED = Boolean.getBoolean("vsdh.lateAcquire");
    /** Layout value standing for "the swapchain has no image" (window without size). */
    public static final int NO_IMAGE = -1;

    private static final long REPORT_NANOS = 5_000_000_000L;
    private static int deferredFrames, splitFrames, refusedFrames;
    private static long reportStart;

    private LateAcquire() {}

    /**
     * Whether a frame with a pack host leaves the default main pass unbegun. Only frames that can become a pack
     * frame qualify: a loaded level and a graph that has not failed. Every other frame keeps the usual begin, and a
     * frame that qualifies but draws no pack world binds the main target at its first use instead.
     */
    public static boolean defersMainPass(boolean enabled, boolean levelLoaded, boolean graphUsable) {
        return enabled && levelLoaded && graphUsable;
    }

    /**
     * Whether the frame's commands so far may be submitted ahead of the swapchain image wait.
     *
     * @param recording          a frame command buffer is open and the frame is not skipped
     * @param swapChainUpdate    a recreate is pending; such a frame is not submitted at all
     * @param acquireWaitPending no earlier submission of this frame (readback flush) consumed the image wait
     * @param targetUnbound      the main pass is still unbegun and no render pass is open
     * @param batchDetachable    the upload batch is closed or at its outermost level, so it can go first
     * @param startLayout        layout of the frame's swapchain image when the frame began
     * @param layout             its layout now; any use of the image has to change it first
     * @param freeBuffers        spare command buffers left in this frame slot
     */
    public static boolean splits(boolean enabled, boolean recording, boolean swapChainUpdate, boolean acquireWaitPending,
                                 boolean targetUnbound, boolean batchDetachable, int startLayout, int layout,
                                 int freeBuffers) {
        return enabled && recording && !swapChainUpdate && acquireWaitPending && targetUnbound && batchDetachable
                && startLayout != NO_IMAGE && layout == startLayout && freeBuffers > 0;
    }

    static void deferred() {
        deferredFrames++;
    }

    /** Counts one split decision and, under the profiling switches, logs the totals every five seconds. */
    static void decided(boolean split, boolean diagnostics) {
        if (split) splitFrames++;
        else refusedFrames++;
        if (!diagnostics) return;
        long now = System.nanoTime();
        if (reportStart == 0) reportStart = now;
        if (now - reportStart < REPORT_NANOS) return;
        net.vulkanmod.Initializer.LOGGER.info("LATE_ACQUIRE deferredFrames={} earlySubmits={} refused={}",
                deferredFrames, splitFrames, refusedFrames);
        deferredFrames = splitFrames = refusedFrames = 0;
        reportStart = now;
    }
}
