package de.stranger.vsdh.shader;

import de.stranger.vsdh.shader.PersistentSwap.Access;
import dev.vitrail.pack.target.TargetSchedule.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistentSwapTest {
    private static final Access UNTOUCHED = new Access(false, null, false);
    private static final Access GEOMETRY_MAIN = new Access(false, Side.MAIN, false);
    private static final Access GEOMETRY_ALT = new Access(true, Side.ALT, false);
    private static final Access FULL_TO_ALT = new Access(false, Side.ALT, true);
    private static final Access FULL_TO_MAIN = new Access(true, Side.MAIN, true);

    /** Complementary colortex4: gbuffers draw MAIN, deferred1 replaces ALT, later geometry draws into ALT. */
    @Test void geometryBeforeAndAfterTheReplacingPassMaySwap() {
        assertTrue(PersistentSwap.alternateReplacedFirst(List.of(GEOMETRY_MAIN, GEOMETRY_MAIN, FULL_TO_ALT, GEOMETRY_ALT, UNTOUCHED)));
    }

    /** Eclipse colortex7: geometry (blended) MAIN, composite4 ALT, composite11 MAIN, composite12 ALT. */
    @Test void laterFlipsDoNotMatter() {
        assertTrue(PersistentSwap.alternateReplacedFirst(List.of(GEOMETRY_MAIN, UNTOUCHED, FULL_TO_ALT, FULL_TO_MAIN, FULL_TO_ALT)));
    }

    @Test void partialFirstWriteToTheAlternateHalfKeepsTheCopy() {
        // geometry draws into ALT before anything replaced it (an explicit flip put it there)
        assertFalse(PersistentSwap.alternateReplacedFirst(List.of(new Access(false, Side.ALT, false), FULL_TO_ALT)));
        // a blended fullscreen pass mixes with what the half held
        assertFalse(PersistentSwap.alternateReplacedFirst(List.of(GEOMETRY_MAIN, new Access(false, Side.ALT, false))));
    }

    @Test void aReadOfTheAlternateHalfBeforeItsReplacementKeepsTheCopy() {
        assertFalse(PersistentSwap.alternateReplacedFirst(List.of(new Access(true, null, false), FULL_TO_ALT)));
        // read and partial write in one step (geometry on a flipped target)
        assertFalse(PersistentSwap.alternateReplacedFirst(List.of(GEOMETRY_ALT, FULL_TO_MAIN)));
    }

    @Test void aTargetNothingReplacesKeepsTheCopy() {
        assertFalse(PersistentSwap.alternateReplacedFirst(List.of()));
        assertFalse(PersistentSwap.alternateReplacedFirst(List.of(GEOMETRY_MAIN, UNTOUCHED)));
    }

    @Test void onlyAnExplicitTrueOptsIn() {
        assertFalse(PersistentSwap.requested(null));
        assertFalse(PersistentSwap.requested(""));
        assertFalse(PersistentSwap.requested("false"));
        assertTrue(PersistentSwap.requested("true"));
        assertTrue(PersistentSwap.requested("TRUE"));
    }
}
