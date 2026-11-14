package de.stranger.vsdh.shader;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * View bobbing and the hurt tilt belong in the model view while a pack draws, not in the projection.
 * <p>
 * Minecraft multiplies them into the projection matrix. Packs rebuild view and world positions from the screen with
 * {@code gbufferProjectionInverse} and treat that matrix as a pure perspective (Eclipse's {@code projMAD} reads only
 * its diagonal and last column), so every step shifted the rebuilt positions by the bob: shadows, reflections and
 * water slid along while walking and stood still while only looking around or flying ("the water and the shadows
 * slide when I move", 15.11.2026; the walk test showed water toggling between two looks frame by frame under VSDH and
 * not under Iris). Iris moves the same transform into the model view (MixinModelViewBobbing); so does
 * WorldShaderGameMixin while a pack host exists. The picture is the same product either way:
 * {@code (P * B) * V == P * (B * V)}.
 * <p>
 * {@code -Dvsdh.modelViewBobbing=false} leaves the bob in the projection as before.
 */
public final class CameraBobbing {
    private CameraBobbing() {}

    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("vsdh.modelViewBobbing"));

    /** The view a pack frame draws with: the bob applied after the camera rotation. */
    public static Matrix4f intoView(Matrix4fc bobbing, Matrix4fc view, Matrix4f destination) {
        return destination.set(bobbing).mul(view);
    }
}
