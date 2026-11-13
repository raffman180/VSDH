package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

/**
 * Closed sky geometry around the camera for pack sky programs.
 * <p>
 * Vanilla only draws the upper sky disc (and the dark disc while the camera is below the horizon),
 * so a downward view from above sea level reaches no {@code gbuffers_skybasic} fragment at all and
 * the clear colour shows through. Shaderpack loaders close that gap with extra horizon geometry, and
 * packs such as AstraLex and Insanity rely on it: their sky colour is the colour their fog fades to.
 * This draws an octagonal wall at the render distance between the two sky heights plus a floor and
 * a ceiling square, camera-relative, with the sky disc's matrices and shader, before the disc.
 */
public final class SkyHorizon {
    private static final float LOW = -16, HIGH = 16;
    private static VertexBuffer buffer;
    private static int builtRadius = -1;

    private SkyHorizon() {}

    static void draw(Matrix4f view, Matrix4f projection, ShaderInstance shader) {
        RenderSystem.assertOnRenderThread();
        int radius = Math.max(32, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16);
        if (buffer == null || builtRadius != radius) rebuild(radius);
        // Loaders draw this before the sky disc and tint it with the fog colour, which packs read as
        // gl_Color; the disc then covers it above the horizon with the sky colour.
        float[] colour = RenderSystem.getShaderColor().clone();
        float[] fog = RenderSystem.getShaderFogColor();
        RenderSystem.setShaderColor(fog[0], fog[1], fog[2], 1);
        boolean cull = net.vulkanmod.vulkan.VRenderSystem.cull;
        net.vulkanmod.vulkan.VRenderSystem.cull = false; // the camera sits inside the geometry
        try {
            buffer.bind();
            buffer.drawWithShader(view, projection, shader);
            VertexBuffer.unbind();
        } finally {
            net.vulkanmod.vulkan.VRenderSystem.cull = cull;
            RenderSystem.setShaderColor(colour[0], colour[1], colour[2], colour[3]);
        }
    }

    private static void rebuild(int radius) {
        if (buffer != null) buffer.close();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        // Octagon whose flat sides lie at the render distance.
        double corner = radius / Math.cos(Math.PI / 8);
        for (int side = 0; side < 8; side++) {
            double a0 = Math.PI / 8 + side * Math.PI / 4, a1 = a0 + Math.PI / 4;
            float x0 = (float) (Math.cos(a0) * corner), z0 = (float) (Math.sin(a0) * corner);
            float x1 = (float) (Math.cos(a1) * corner), z1 = (float) (Math.sin(a1) * corner);
            builder.addVertex(x0, LOW, z0); builder.addVertex(x0, HIGH, z0);
            builder.addVertex(x1, HIGH, z1); builder.addVertex(x1, LOW, z1);
        }
        float half = (float) corner;
        for (float y : new float[]{LOW, HIGH}) {
            builder.addVertex(-half, y, -half); builder.addVertex(-half, y, half);
            builder.addVertex(half, y, half); builder.addVertex(half, y, -half);
        }
        buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(builder.buildOrThrow());
        VertexBuffer.unbind();
        builtRadius = radius;
    }
}
