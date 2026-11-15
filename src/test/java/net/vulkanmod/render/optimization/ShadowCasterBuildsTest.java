package net.vulkanmod.render.optimization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowCasterBuildsTest {
    private static final int QUEUE = ChunkSchedulingPolicy.MAX_QUEUED_BUILD_TASKS;

    @Test
    void cameraSectionsComeFirst() {
        assertEquals(4, ShadowCasterBuilds.budget(true, 4, 0, 0, 0));
        // A visible section still waits: nothing for the light this frame.
        assertEquals(0, ShadowCasterBuilds.budget(true, 4, 1, 0, 0));
        assertEquals(0, ShadowCasterBuilds.budget(false, 4, 0, 0, 0));
    }

    @Test
    void sharesTheNormalScheduleTimeAndNeverExtendsIt() {
        // The 3 ms catch-up schedule budget cost 1% lows (AGENTS.md); the light gets the rest of the normal 1 ms.
        assertEquals(1_000_000L, ShadowCasterBuilds.TIME_BUDGET_NANOS);
        assertEquals(4, ShadowCasterBuilds.budget(true, 4, 0, 999_999L, 0));
        assertEquals(0, ShadowCasterBuilds.budget(true, 4, 0, 1_000_000L, 0));
        assertEquals(0, ShadowCasterBuilds.budget(true, 4, 0, 5_000_000L, 0));
        assertEquals(400_000L, ShadowCasterBuilds.timeLeft(600_000L));
        assertEquals(1_000_000L, ShadowCasterBuilds.timeLeft(-5L));
    }

    @Test
    void leavesWorkerSlotsToTheCamera() {
        int usable = QUEUE - ShadowCasterBuilds.QUEUE_RESERVE;
        assertTrue(ShadowCasterBuilds.QUEUE_RESERVE >= 16 && usable >= ShadowCasterBuilds.PER_FRAME);
        assertEquals(4, ShadowCasterBuilds.budget(true, 4, 0, 0, usable - 4));
        assertEquals(2, ShadowCasterBuilds.budget(true, 4, 0, 0, usable - 2));
        assertEquals(0, ShadowCasterBuilds.budget(true, 4, 0, 0, usable));
        assertEquals(0, ShadowCasterBuilds.budget(true, 4, 0, 0, QUEUE));
    }

    @Test
    void keepsTheNearestCandidatesNearestFirst() {
        var picks = new ShadowCasterBuilds.Picks<String>(3);
        picks.offer("far", 900);
        picks.offer("near", 10);
        picks.offer("middle", 100);
        picks.offer("farther", 2000); // full and not nearer than the last one
        picks.offer("nearest", 1);    // pushes "far" out
        assertEquals(3, picks.size());
        assertEquals("nearest", picks.get(0));
        assertEquals("near", picks.get(1));
        assertEquals("middle", picks.get(2));
        assertThrows(IndexOutOfBoundsException.class, () -> picks.get(3));
    }

    @Test
    void equalDistancesKeepScanOrderAndClearEmptiesTheList() {
        var picks = new ShadowCasterBuilds.Picks<String>(2);
        picks.offer("first", 5);
        picks.offer("second", 5);
        picks.offer("third", 5);
        assertEquals("first", picks.get(0));
        assertEquals("second", picks.get(1));
        picks.clear();
        assertEquals(0, picks.size());
        picks.offer("again", 7);
        assertEquals("again", picks.get(0));
        assertEquals(1, picks.size());
    }

    @Test
    void reachIsTestedOnTheWholeSectionBox() {
        // Jurgens HIGH: shadowDistance 192.
        assertFalse(ShadowCasterBuilds.outOfReach(192, -8, -8, -8));
        assertFalse(ShadowCasterBuilds.outOfReach(192, 192, 0, 0));     // touches the cube
        assertFalse(ShadowCasterBuilds.outOfReach(192, -207.5f, 0, 0)); // reaches in by half a block
        assertTrue(ShadowCasterBuilds.outOfReach(192, 192.5f, 0, 0));
        assertTrue(ShadowCasterBuilds.outOfReach(192, 0, -208.5f, 0));
        assertTrue(ShadowCasterBuilds.outOfReach(192, 0, 0, 300));
        assertFalse(ShadowCasterBuilds.outOfReach(Float.POSITIVE_INFINITY, 1e6f, 0, 0));
    }
}
