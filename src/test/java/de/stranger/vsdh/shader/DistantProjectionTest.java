package de.stranger.vsdh.shader;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DistantProjectionTest {
    private static final float FOV = (float) Math.toRadians(70), ASPECT = 16f / 9f;

    @Test
    void unbobbedCameraGivesDhsOwnProjection() {
        var camera = new Matrix4f().perspective(FOV, ASPECT, .05f, 160f);
        var expected = new Matrix4f().perspective(FOV, ASPECT, 16f, 4096f);
        var distant = new Matrix4f(expected);
        DistantProjection.followCamera(distant, camera);
        assertTrue(distant.equals(expected, 1e-5f), distant + " vs " + expected);
    }

    @Test
    void cameraTransformsAreCarriedOver() {
        // A view-bobbing style transform: small roll, pitch and a translation, multiplied into the projection.
        var bob = new Matrix4f().translate(.03f, -.08f, 0).rotateZ(.012f).rotateX(.02f);
        var camera = new Matrix4f().perspective(FOV, ASPECT, .05f, 160f).mul(bob);
        var dh = new Matrix4f().perspective((float) Math.toRadians(62), ASPECT, 16f, 4096f); // DH's lagging FOV
        var expected = new Matrix4f().perspective(FOV, ASPECT, 16f, 4096f).mul(bob);
        DistantProjection.followCamera(dh, camera);
        assertTrue(dh.equals(expected, 1e-4f), dh + " vs " + expected);
    }
}
