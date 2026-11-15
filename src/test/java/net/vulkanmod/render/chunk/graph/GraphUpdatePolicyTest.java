package net.vulkanmod.render.chunk.graph;

import net.vulkanmod.render.chunk.frustum.VFrustum;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphUpdatePolicyTest {
    private static Matrix4f projection() {
        // FOV 70 at 2560x1440, as the game builds the culling projection.
        return new Matrix4f().perspective((float) Math.toRadians(70), 2560f / 1440f, 0.05f, 1024f);
    }

    private static VFrustum frustum(float yawDegrees) {
        VFrustum frustum = new VFrustum();
        frustum.calculateFrustum(new Matrix4f().rotationY((float) Math.toRadians(yawDegrees)), projection());
        frustum.setCamOffset(0, 0, 0);
        return frustum;
    }

    /** The reported fault: a section the turned camera sees is still culled by the frustum of the last walk. */
    @Test
    void aTurnBelowTheOldThresholdAlreadyShowsSectionsTheOldFrustumCulled() {
        VFrustum walked = frustum(0), turned = frustum(1.5f);
        int culledButVisible = 0;
        // Sections on a ring 96 to 160 blocks away, at eye level.
        for (int x = -160; x <= 160; x += 16) {
            for (int z = -160; z <= 160; z += 16) {
                double distance = Math.hypot(x + 8, z + 8);
                if (distance < 96 || distance > 160) continue;
                boolean before = walked.testFrustum(x, -8, z, x + 16, 8, z + 16);
                boolean now = turned.testFrustum(x, -8, z, x + 16, 8, z + 16);
                if (now && !before) culledButVisible++;
            }
        }
        assertTrue(culledButVisible > 0, "a 1.5 degree turn must bring sections into view");
        // The old rule kept the walked set for this turn, the default does not.
        assertFalse(GraphUpdatePolicy.cameraChanged(false, 0f, 1.5f, 0f, false, true));
        assertTrue(GraphUpdatePolicy.cameraChanged(true, 0f, 1.5f, 0f, false, true));
    }

    @Test
    void exactModeWalksOnAnyChangeAndRestsWhenNothingChanged() {
        assertFalse(GraphUpdatePolicy.cameraChanged(true, 0f, 0f, 0f, false, false));
        assertTrue(GraphUpdatePolicy.cameraChanged(true, 0.01f, 0f, 0f, false, false));
        assertTrue(GraphUpdatePolicy.cameraChanged(true, 0f, 0.01f, 0f, false, false));
        assertTrue(GraphUpdatePolicy.cameraChanged(true, 0f, 0f, 1.0e-6f, false, false));
        // Field of view alone (sprint, zoom): only the frustum matrix differs.
        assertTrue(GraphUpdatePolicy.cameraChanged(true, 0f, 0f, 0f, false, true));
        assertTrue(GraphUpdatePolicy.cameraChanged(true, 0f, 0f, 0f, true, false));
    }

    @Test
    void theSwitchRestoresTheOldThresholds() {
        assertFalse(GraphUpdatePolicy.cameraChanged(false, 2f, 2f, 0.24f, false, true));
        assertTrue(GraphUpdatePolicy.cameraChanged(false, 2.01f, 0f, 0f, false, false));
        assertTrue(GraphUpdatePolicy.cameraChanged(false, 0f, 2.01f, 0f, false, false));
        assertTrue(GraphUpdatePolicy.cameraChanged(false, 0f, 0f, 0.25f, false, false));
        assertTrue(GraphUpdatePolicy.cameraChanged(false, 0f, 0f, 0f, true, false));
    }

    @Test
    void theFrustumMatrixTellsAnUnchangedCameraFromATurnedOrZoomedOne() {
        Matrix4f last = new Matrix4f();
        VFrustum first = frustum(10);
        assertFalse(first.sameMatrix(last));
        first.copyMatrix(last);
        assertTrue(frustum(10).sameMatrix(last), "the same camera builds the same matrix every frame");
        assertFalse(frustum(10.05f).sameMatrix(last));
        VFrustum zoomed = new VFrustum();
        zoomed.calculateFrustum(new Matrix4f().rotationY((float) Math.toRadians(10)),
                new Matrix4f().perspective((float) Math.toRadians(80), 2560f / 1440f, 0.05f, 1024f));
        assertFalse(zoomed.sameMatrix(last));
    }
}
