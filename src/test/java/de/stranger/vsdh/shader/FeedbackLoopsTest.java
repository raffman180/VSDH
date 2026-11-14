package de.stranger.vsdh.shader;

import dev.vitrail.pack.source.ShaderProperties.FlipDirective;
import dev.vitrail.pack.target.TargetSchedule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackLoopsTest {
    /** Eclipse's shape: water writes colortex0/7/11 and samples 7 and 11, a composite flips colortex0. */
    private static TargetSchedule schedule() {
        return TargetSchedule.of(List.of(
                new TargetSchedule.Step("gbuffers_terrain", List.of(0, 7), false),
                new TargetSchedule.Step("gbuffers_water", List.of(0, 7, 11), false),
                new TargetSchedule.Step("deferred", List.of(0), true),
                new TargetSchedule.Step("composite", List.of(0), true),
                new TargetSchedule.Step("final", List.of(), true)), List.<FlipDirective>of());
    }

    @Test void geometryLoopsAreExactlyTheSampledDrawBuffers() {
        var schedule = schedule();
        var water = schedule.step("gbuffers_water").orElseThrow();
        assertEquals(Set.of(7, 11), FeedbackLoops.loops(water, water.writes(), Set.of(1, 7, 11)));
        // Reads of targets the program does not draw into, and draw buffers it does not sample, are no loops.
        assertEquals(Set.of(), FeedbackLoops.loops(water, water.writes(), Set.of(1, 2)));
        var terrain = schedule.step("gbuffers_terrain").orElseThrow();
        assertEquals(Set.of(), FeedbackLoops.loops(terrain, terrain.writes(), Set.of()));
    }

    @Test void theSameProgramAfterDeferredStillLoopsOnTheHalfItWrites() {
        var schedule = schedule();
        var late = schedule.stepAfterDeferred("gbuffers_water").orElseThrow();
        for (int index : late.writes()) assertEquals(late.read(index), late.write(index));
        assertEquals(Set.of(0, 11), FeedbackLoops.loops(late, late.writes(), Set.of(0, 11)));
    }

    @Test void fullscreenPassesNeverLoop() {
        var schedule = schedule();
        var composite = schedule.step("composite").orElseThrow();
        assertNotEquals(composite.read(0), composite.write(0));
        assertEquals(Set.of(), FeedbackLoops.loops(composite, composite.writes(), Set.of(0)));
    }

    @Test void samplerNamesMapToTargetsIncludingLegacyAliases() {
        assertEquals(Set.of(0, 7, 11), FeedbackLoops.colourTargets(List.of("colortex11", "gaux4", "gcolor", "depthtex0", "dhDepthTex", "noisetex")));
        assertTrue(FeedbackLoops.distantDepthLoop(Set.of("dhDepthTex0")));
        assertTrue(FeedbackLoops.distantDepthLoop(Set.of("dhDepthTex", "colortex0")));
        assertFalse(FeedbackLoops.distantDepthLoop(Set.of("dhDepthTex1", "depthtex0")));
    }

    /** The DH depth loop races with dh_water's depth writes (distant flicker): opt-in only. */
    @Test void theDistantDepthLoopIsOptIn() {
        assertFalse(FeedbackLoops.distantDepthRequested(null));
        assertFalse(FeedbackLoops.distantDepthRequested(""));
        assertFalse(FeedbackLoops.distantDepthRequested("false"));
        assertFalse(FeedbackLoops.distantDepthRequested("verify"));
        assertTrue(FeedbackLoops.distantDepthRequested("true"));
        assertTrue(FeedbackLoops.distantDepthRequested("TRUE"));
        // Without the property the opaque copy is used; the device is not even asked.
        if (System.getProperty("vsdh.dhDepthFeedbackLoop") == null) {
            assertFalse(FeedbackLoops.DISTANT_DEPTH_REQUESTED);
            assertFalse(FeedbackLoops.distantDepthEnabled());
        }
    }
}
