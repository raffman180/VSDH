package net.vulkanmod.vulkan.framebuffer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwapChainSlotsTest {
    @Test
    void noImageIsHandedOutWhileTheSwapchainHasNone() {
        // Minimised window: the Vulkan swapchain and its images are destroyed, the client tick still asks.
        assertEquals(-1, SwapChainSlots.slot(0, 0));
        assertEquals(-1, SwapChainSlots.slot(2, 0));
    }

    @Test
    void anIndexLeftOverFromALargerImageSetStaysInRange() {
        assertEquals(1, SwapChainSlots.slot(1, 3));
        assertEquals(2, SwapChainSlots.slot(2, 3));
        assertEquals(0, SwapChainSlots.slot(3, 3));
        assertEquals(0, SwapChainSlots.slot(-1, 3));
    }

    @Test
    void aLostSwapchainIsRetriedOnlyWhileTheWindowHasASizeAndNotEveryFrame() {
        long interval = SwapChainSlots.REVIVE_INTERVAL_NANOS;
        assertTrue(SwapChainSlots.shouldRevive(0, 0, 1280, 720, interval, 0));
        assertFalse(SwapChainSlots.shouldRevive(0, 0, 1280, 720, interval - 1, 0));
        assertFalse(SwapChainSlots.shouldRevive(0, 0, 0, 0, 10 * interval, 0)); // really minimised
        assertFalse(SwapChainSlots.shouldRevive(1280, 720, 1280, 720, 10 * interval, 0)); // has a swapchain
        // System.nanoTime() may be negative and wrap: only the difference counts.
        assertTrue(SwapChainSlots.shouldRevive(0, 0, 1280, 720, Long.MIN_VALUE + interval, Long.MIN_VALUE));
    }
}
