package net.vulkanmod.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LateAcquireTest {
    private static final int PRESENT_SRC = 1000001002, COLOR_ATTACHMENT = 2, UNDEFINED = 0;

    @Test
    void onlyFramesThatCanBecomePackFramesLeaveTheMainPassUnbegun() {
        assertTrue(LateAcquire.defersMainPass(true, true, true));
        assertFalse(LateAcquire.defersMainPass(false, true, true), "switch off: every frame begins as before");
        assertFalse(LateAcquire.defersMainPass(true, false, true), "title screen and menus without a level");
        assertFalse(LateAcquire.defersMainPass(true, true, false), "failed graph: standard renderer");
    }

    @Test
    void anUntouchedPackFrameSplits() {
        assertTrue(LateAcquire.splits(true, true, false, true, true, true, PRESENT_SRC, PRESENT_SRC, 3));
        // A fresh swapchain image starts undefined and is just as unused.
        assertTrue(LateAcquire.splits(true, true, false, true, true, true, UNDEFINED, UNDEFINED, 1));
    }

    @Test
    void everySingleObstacleKeepsTheFrameInOneSubmission() {
        assertFalse(LateAcquire.splits(false, true, false, true, true, true, PRESENT_SRC, PRESENT_SRC, 3), "switch off");
        assertFalse(LateAcquire.splits(true, false, false, true, true, true, PRESENT_SRC, PRESENT_SRC, 3), "no frame");
        assertFalse(LateAcquire.splits(true, true, true, true, true, true, PRESENT_SRC, PRESENT_SRC, 3), "recreate pending");
        assertFalse(LateAcquire.splits(true, true, false, false, true, true, PRESENT_SRC, PRESENT_SRC, 3),
                "a readback flush already submitted with the image wait");
        assertFalse(LateAcquire.splits(true, true, false, true, false, true, PRESENT_SRC, PRESENT_SRC, 3),
                "main target bound or a pass open");
        assertFalse(LateAcquire.splits(true, true, false, true, true, false, PRESENT_SRC, PRESENT_SRC, 3),
                "upload batch nested: it could not go first");
        assertFalse(LateAcquire.splits(true, true, false, true, true, true, PRESENT_SRC, COLOR_ATTACHMENT, 3),
                "some command moved the swapchain image out of its present layout");
        assertFalse(LateAcquire.splits(true, true, false, true, true, true, LateAcquire.NO_IMAGE, LateAcquire.NO_IMAGE, 3),
                "window without size: no image");
        assertFalse(LateAcquire.splits(true, true, false, true, true, true, PRESENT_SRC, PRESENT_SRC, 0),
                "no spare command buffer");
    }
}
