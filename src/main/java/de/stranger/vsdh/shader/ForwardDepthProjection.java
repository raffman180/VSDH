package de.stranger.vsdh.shader;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Reforged's JOML mixin produces forward Vulkan 0..w. Packs must receive the equivalent -w..w. */
public final class ForwardDepthProjection {
    private ForwardDepthProjection() {}

    /** Scale legacy clip Z while keeping this matrix in forward Vulkan clip space. */
    public static Matrix4f hand(Matrix4fc projection) {
        float scale = 0.125f, shift = (1 - scale) * 0.5f;
        return new Matrix4f(projection)
                .m02(scale * projection.m02() + shift * projection.m03())
                .m12(scale * projection.m12() + shift * projection.m13())
                .m22(scale * projection.m22() + shift * projection.m23())
                .m32(scale * projection.m32() + shift * projection.m33());
    }
    public static Matrix4f toLegacy(Matrix4fc rendered,Matrix4f destination) {
        float a=2*rendered.m02()-rendered.m03(),b=2*rendered.m12()-rendered.m13();
        float c=2*rendered.m22()-rendered.m23(),d=2*rendered.m32()-rendered.m33();
        destination.set(rendered);destination.m02(a).m12(b).m22(c).m32(d);return destination;
    }
}
