package de.stranger.vsdh.shader;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraBobbingTest {
    @Test
    void pictureIsUnchangedAndTheProjectionStaysPure() {
        var projection = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, .05f, 160f);
        var bobbing = new Matrix4f().translate(.03f, -.08f, 0).rotateZ(.012f).rotateX(.02f);
        var view = new Matrix4f().rotateX(.5f).rotateY(2.1f);
        var vanilla = new Matrix4f(projection).mul(bobbing).mul(view);
        var moved = new Matrix4f(projection).mul(CameraBobbing.intoView(bobbing, view, new Matrix4f()));
        assertTrue(moved.equals(vanilla, 1e-5f), moved + " vs " + vanilla);
        // What packs rely on: no off-diagonal terms in the x and y rows of the projection.
        assertEquals(0f, projection.m10(), 0f);
        assertEquals(0f, projection.m01(), 0f);
        assertEquals(0f, projection.m30(), 0f);
        assertEquals(0f, projection.m31(), 0f);
    }
}
