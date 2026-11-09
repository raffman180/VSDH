package de.stranger.vsdh.shader;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Reforged's JOML mixin produces forward Vulkan 0..w. Packs must receive the equivalent -w..w. */
public final class ForwardDepthProjection {
    private ForwardDepthProjection() {}
    public static Matrix4f toLegacy(Matrix4fc rendered,Matrix4f destination) {
        float a=2*rendered.m02()-rendered.m03(),b=2*rendered.m12()-rendered.m13();
        float c=2*rendered.m22()-rendered.m23(),d=2*rendered.m32()-rendered.m33();
        destination.set(rendered);destination.m02(a).m12(b).m22(c).m32(d);return destination;
    }
}
