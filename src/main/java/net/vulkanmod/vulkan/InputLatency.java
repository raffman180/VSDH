package net.vulkanmod.vulkan;

/**
 * Where the render thread blocks relative to the moment Minecraft reads the mouse.
 * <p>
 * Minecraft (1.21.1, {@code Minecraft.runTick}) reads input events in {@code Window.updateDisplay} ->
 * {@code RenderSystem.flipFrame} (and in the frame limiter's {@code glfwWaitEventsTimeout}) at the END of a frame,
 * and applies the accumulated mouse movement to the camera in {@code MouseHandler.handleAccumulatedMovement} of the
 * next frame, right after {@code RenderSystem.clear}. Under OpenGL the blocking call (the buffer swap) sits inside
 * {@code flipFrame} BEFORE its last event poll, so everything the driver makes the game wait is over when the mouse
 * is read. This renderer used to block AFTER that poll: on the frame slot's fence at the head of {@code runTick}
 * ({@code Renderer.preInitFrame}) and on the swapchain image at the clear ({@code Renderer.beginFrame}). Mouse
 * movement made during those waits was not in the frame that was then drawn; the camera followed the hand late by
 * the length of the wait (up to a whole refresh interval with VSync, the GPU's lead otherwise).
 * <p>
 * {@link #EARLY_WAIT}: the same two waits run at the end of {@code Renderer.endFrame}, before the event poll. Nothing
 * else moves; the later calls find the fence signalled and the image acquired.
 * <p>
 * Low latency mode ({@code VK_KHR_present_id} + {@code VK_KHR_present_wait}, opt-in): additionally waits there until
 * the frame presented last is on screen, so no finished frame queues in front of the one about to read the mouse.
 * It costs frames whenever a frame takes longer than one refresh interval with VSync (the next one starts a whole
 * interval later), which is why it is off by default.
 */
public final class InputLatency {
    private InputLatency() {}

    /** {@code -Dvsdh.earlyFrameWait=false}: fence and swapchain waits after the event poll, as before. */
    public static final boolean EARLY_WAIT = !"false".equalsIgnoreCase(System.getProperty("vsdh.earlyFrameWait"));
    /** {@code -Dvsdh.earlyAcquire=false}: only the fence wait moves; the swapchain image is acquired at the clear. */
    public static final boolean EARLY_ACQUIRE = EARLY_WAIT && !"false".equalsIgnoreCase(System.getProperty("vsdh.earlyAcquire"));
    /** {@code -Dvsdh.presentWait=false}: do not enable the two present extensions on the device at all. */
    public static final boolean PRESENT_WAIT_ALLOWED = !"false".equalsIgnoreCase(System.getProperty("vsdh.presentWait"));
    /** {@code -Dvsdh.inputTrace=true}: logs poll-to-present times every five seconds. */
    public static final boolean TRACE = Boolean.getBoolean("vsdh.inputTrace");
    /** A present that never reaches the screen (covered window, lost surface) must not hold the game. */
    public static final long PRESENT_WAIT_TIMEOUT_NANOS = 100_000_000L;

    private static final String LOW_LATENCY_PROPERTY = System.getProperty("vsdh.lowLatency");

    /** Whether frames carry a present id and wait for the previous one: needs the device feature and the Vulkan swapchain. */
    public static boolean lowLatency(boolean configured, boolean deviceSupport, boolean dxgi) {
        return lowLatency(LOW_LATENCY_PROPERTY, configured, deviceSupport, dxgi);
    }

    /** {@code -Dvsdh.lowLatency=true|false} overrides the setting; anything else leaves the setting in charge. */
    static boolean lowLatency(String property, boolean configured, boolean deviceSupport, boolean dxgi) {
        if (!deviceSupport || dxgi) return false;
        if ("true".equalsIgnoreCase(property)) return true;
        if ("false".equalsIgnoreCase(property)) return false;
        return configured;
    }

    /**
     * Present ids of one swapchain at a time. The specification wants every non-zero id greater than all earlier ones
     * of that swapchain (VUID-VkPresentIdKHR-presentIds-04999); one counter for the session satisfies it for all. An
     * id is only waited for on the swapchain it was presented to: a new swapchain starts without one.
     */
    public static final class PresentIds {
        private long swapchain;
        private long last;
        private long counter;

        /** The id for the next present on {@code swapchain}. */
        public long next(long swapchain) {
            if (this.swapchain != swapchain) {
                this.swapchain = swapchain;
                this.last = 0;
            }
            return this.last = ++this.counter;
        }

        /** The id presented last on {@code swapchain}, or 0 when there is nothing to wait for. */
        public long pending(long swapchain) {
            return this.swapchain == swapchain ? this.last : 0;
        }

        /** After a failed present or a swapchain recreate (handles may be reused): nothing to wait for. */
        public void forget() {
            this.swapchain = 0;
            this.last = 0;
        }
    }

    /** Poll-to-present bookkeeping for {@link #TRACE}; plain longs, render thread only. */
    public static final class Trace {
        private long polledAt, frames, sum, max, presentWaitSum, presentWaits, presentTimeouts, lastLog;

        /** The frame's waits are over; the game polls input next. */
        public void polled(long now) { polledAt = now; }

        public void presentWaited(long nanos, boolean timedOut) {
            presentWaitSum += nanos; presentWaits++;
            if (timedOut) presentTimeouts++;
        }

        /** vkQueuePresentKHR (or the DXGI present) has returned. */
        public void presented(long now) {
            if (polledAt != 0) {
                long took = now - polledAt;
                sum += took; frames++;
                if (took > max) max = took;
            }
            if (lastLog == 0) lastLog = now;
            if (now - lastLog < 5_000_000_000L || frames == 0) return;
            org.slf4j.LoggerFactory.getLogger("VulkanMod").info(
                    "INPUT_TRACE frames={} pollToPresentAvgMs={} pollToPresentMaxMs={} presentWaitAvgMs={} presentWaitTimeouts={} earlyWait={} earlyAcquire={}",
                    frames, String.format(java.util.Locale.ROOT, "%.2f", sum / 1e6 / frames),
                    String.format(java.util.Locale.ROOT, "%.2f", max / 1e6),
                    String.format(java.util.Locale.ROOT, "%.2f", presentWaits == 0 ? 0 : presentWaitSum / 1e6 / presentWaits),
                    presentTimeouts, EARLY_WAIT, EARLY_ACQUIRE);
            frames = sum = max = presentWaitSum = presentWaits = presentTimeouts = 0;
            lastLog = now;
        }
    }
}
