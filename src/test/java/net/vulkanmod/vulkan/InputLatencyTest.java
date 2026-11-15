package net.vulkanmod.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputLatencyTest {
    @Test
    void presentIdsOnlyGrowAcrossSwapchains() {
        // VUID-VkPresentIdKHR-presentIds-04999: greater than every id submitted before for that swapchain.
        var ids = new InputLatency.PresentIds();
        long first = ids.next(0x10);
        long second = ids.next(0x10);
        long other = ids.next(0x20);
        long back = ids.next(0x10);
        assertTrue(first > 0, "zero means: no id");
        assertTrue(second > first);
        assertTrue(other > second);
        assertTrue(back > other);
    }

    @Test
    void onlyThePresentOfTheSameSwapchainIsWaitedFor() {
        var ids = new InputLatency.PresentIds();
        assertEquals(0, ids.pending(0x10), "nothing presented yet");
        long id = ids.next(0x10);
        assertEquals(id, ids.pending(0x10));
        assertEquals(0, ids.pending(0x20), "an id of another swapchain would only time out");
        long next = ids.next(0x20);
        assertEquals(0, ids.pending(0x10));
        assertEquals(next, ids.pending(0x20));
    }

    @Test
    void aRecreatedSwapchainWithAReusedHandleStartsWithoutAnId() {
        var ids = new InputLatency.PresentIds();
        long old = ids.next(0x10);
        ids.forget();
        assertEquals(0, ids.pending(0x10));
        assertTrue(ids.next(0x10) > old, "the counter is not reset with the swapchain");
    }

    @Test
    void lowLatencyNeedsTheDeviceFeatureAndTheVulkanSwapchain() {
        assertFalse(InputLatency.lowLatency("true", true, false, false), "no VK_KHR_present_wait");
        assertFalse(InputLatency.lowLatency("true", true, true, true), "DXGI presents without a Vulkan swapchain");
        assertTrue(InputLatency.lowLatency("true", false, true, false));
    }

    @Test
    void onlyThePropertyTurnsTheModeOn() {
        assertFalse(InputLatency.lowLatency(null, false, true, false), "off by default");
        // A settings file that still says lowLatency=true (the switch existed for a few hours on 15.11.2026) must
        // not serialise CPU and GPU again: 40-70 FPS with every pack at 180 Hz was the report.
        assertFalse(InputLatency.lowLatency(null, true, true, false), "the saved setting alone is ignored");
        assertFalse(InputLatency.lowLatency("", true, true, false));
        assertFalse(InputLatency.lowLatency("false", true, true, false));
        assertTrue(InputLatency.lowLatency("TRUE", false, true, false));
        assertTrue(InputLatency.lowLatency("true", true, true, false));
    }

    @Test
    void thePresentExtensionsAreOnlyAskedForWithTheMode() {
        // Every player's device used to be created with VK_KHR_present_id/present_wait; now only the opt-in mode's.
        assertFalse(InputLatency.presentWaitWanted(null, null), "default: device without the extensions");
        assertFalse(InputLatency.presentWaitWanted(null, "true"));
        assertFalse(InputLatency.presentWaitWanted("false", null));
        assertTrue(InputLatency.presentWaitWanted("true", null));
        assertTrue(InputLatency.presentWaitWanted("TRUE", ""));
        assertFalse(InputLatency.presentWaitWanted("true", "false"), "-Dvsdh.presentWait=false still wins");
    }
}
