package de.stranger.vsdh.shader;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The DH projection a pack draws its far terrain and water with, made from the camera's own projection.
 * <p>
 * Distant Horizons hands over a plain perspective of its own: no view bobbing, no hurt or nausea tilt, and a field of
 * view that eases towards the game's when walking starts. The near terrain is drawn with the game's projection, which
 * carries all of that. While walking the two pictures slid against each other every step and the far water showed
 * through the near water in changing places ("the water moves when I move, not when I look around", 14.11.2026; the
 * walk test with view bobbing logged a DH projection without any of the camera's off-diagonal terms). Iris keeps the
 * two together the same way: the camera's rows for x, y and w, and DH's own depth mapping.
 * <p>
 * For a GL projection {@code P} with depth row {@code (0, 0, A, B)} and w row {@code (0, 0, -1, 0)}, the depth row
 * equals {@code -A * w + (0, 0, 0, B)}. Any camera transform {@code T} multiplied in keeps that relation:
 * {@code (P*T).depth = -A * (P*T).w + (0, 0, 0, B)}. So the camera's matrix with its depth row rebuilt from DH's
 * {@code A} and {@code B} is exactly {@code P_dh * T}.
 */
final class DistantProjection {
    private DistantProjection() {}

    /** {@code -Dvsdh.dhFollowCamera=false}: draw DH with its own projection as before. */
    static final boolean FOLLOW_CAMERA = !"false".equalsIgnoreCase(System.getProperty("vsdh.dhFollowCamera"));

    /**
     * @param distant DH's GL projection, replaced by the result
     * @param camera  the camera's GL projection as the pack receives it (view bobbing included)
     */
    static void followCamera(Matrix4f distant, Matrix4fc camera) {
        float a = distant.m22(), b = distant.m32();
        distant.set(camera);
        distant.m02(-a * camera.m03());
        distant.m12(-a * camera.m13());
        distant.m22(-a * camera.m23());
        distant.m32(-a * camera.m33() + b);
    }
}
