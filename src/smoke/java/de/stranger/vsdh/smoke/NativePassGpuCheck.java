package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.NativeFullscreenPass;
import de.stranger.vsdh.shader.PackProgramSource;
import de.stranger.vsdh.shader.Std140Layout;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.source.OpenedPack;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

/** Tests real include expansion -> translation -> shaderc -> native Vulkan -> readback. */
final class NativePassGpuCheck {
    private static CompletableFuture<Prepared> pending;
    private record Prepared(PackProgramSource.Prepared generate, PackProgramSource.Prepared sample,
                            PackProgramSource.Prepared complementary) {}

    static void prepare() {
        if (pending != null) return;
        pending = CompletableFuture.supplyAsync(() -> {
            try {
                Path path = Path.of(NativePassGpuCheck.class.getResource("/shaderpacks/vsdh-diagnostic").toURI());
                PackProgramSource.Prepared generate, sample, complementary = null;
                try (OpenedPack pack = OpenedPack.open(path, Map.of(), "")) {
                    generate = PackProgramSource.prepare(pack, "prepare", VertexInputs.FULLSCREEN);
                    sample = PackProgramSource.prepare(pack, "final", VertexInputs.FULLSCREEN);
                }
                String external = System.getProperty("vsdh.smoke.pack");
                if (external != null) {
                    try (OpenedPack pack = OpenedPack.open(Path.of(external), Map.of(), "MEDIUM")) {
                        complementary = PackProgramSource.prepare(pack, "world0/final", VertexInputs.FULLSCREEN);
                    }
                }
                return new Prepared(generate, sample, complementary);
            } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        });
    }

    static boolean ready() { return pending != null && pending.isDone(); }

    static void run() {
        Prepared prepared = pending.join(); // Called only after ready(); no file I/O or waiting on the render thread.
        var source = Framebuffer.builder(13, 9, 2, false)
                .setColorFormats(VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT).build();
        var destination = Framebuffer.builder(13, 9, 1, false).build();
        RenderPass write = RenderPass.builder(source).build();
        RenderPass read = RenderPass.builder(destination).build();
        ByteBuffer rgba = MemoryUtil.memAlloc(13 * 9 * 4);
        ByteBuffer half = MemoryUtil.memAlloc(13 * 9 * 8);
        try (var scope = Renderer.getInstance().temporaryRenderTarget();
             var generate = new NativeFullscreenPass(prepared.generate.path(), prepared.generate.translated(), prepared.generate.compiled());
             var sample = new NativeFullscreenPass(prepared.sample.path(), prepared.sample.translated(), prepared.sample.compiled())) {
            initializeQuad(generate);
            initializeQuad(sample);
            generate.layout().putFloats(generate.uniforms(), "gain", 0, 0.75f);
            generate.render(source, write);
            ImageUtil.downloadTexture(source.getColorAttachment(0), MemoryUtil.memAddress(half));
            for (int y = 0; y < 9; y++) for (int x = 0; x < 13; x++) {
                int offset = (y * 13 + x) * 8;
                assertNear((x + 0.5f) / 13, halfFloat(half.getShort(offset)), 0.001f, "UV x");
                assertNear((y + 0.5f) / 9, halfFloat(half.getShort(offset + 2)), 0.001f, "UV y");
                assertNear(0.75f, halfFloat(half.getShort(offset + 4)), 0.001f, "uniform gain");
            }
            if (!sample.requiredSamplers().equals(java.util.Set.of("colortex1")))
                throw new AssertionError("Unused shadow sampler was not eliminated: " + sample.requiredSamplers());
            sample.bindImage("colortex1", source.getColorAttachment(1));
            sample.layout().putFloats(sample.uniforms(), "tint", 0, 1, 0.5f, 0.25f);
            sample.layout().putFloats(sample.uniforms(), "gain", 0, 0.5f);
            sample.render(destination, read);
            ImageUtil.downloadTexture(destination.getColorAttachment(0), MemoryUtil.memAddress(rgba));
            int[] expected = {32, 32, 24, 255};
            for (int pixel = 0; pixel < 13 * 9; pixel++) for (int channel = 0; channel < 4; channel++)
                assertNear(expected[channel], Byte.toUnsignedInt(rgba.get(pixel * 4 + channel)), 1, "sampled output");
            // Flip/read a different input with the SAME pipeline/descriptor pool: catches stale bindings.
            sample.bindImage("colortex1", source.getColorAttachment(0));
            sample.render(destination, read);
            ImageUtil.downloadTexture(destination.getColorAttachment(0), MemoryUtil.memAddress(rgba));
            int center = (4 * 13 + 6) * 4;
            assertNear(64, Byte.toUnsignedInt(rgba.get(center)), 1, "rebound red");
            assertNear(32, Byte.toUnsignedInt(rgba.get(center + 1)), 1, "rebound green");
            assertNear(24, Byte.toUnsignedInt(rgba.get(center + 2)), 1, "rebound blue");
            if (prepared.complementary != null) {
                var actual = prepared.complementary;
                try (var complementary = new NativeFullscreenPass(actual.path(), actual.translated(), actual.compiled())) {
                    initializeQuad(complementary);
                    if (!complementary.requiredSamplers().equals(java.util.Set.of("colortex3", "noisetex")))
                        throw new AssertionError("Complementary final inputs changed: " + complementary.requiredSamplers());
                    complementary.bindImage("colortex3", source.getColorAttachment(1));
                    complementary.bindImage("noisetex", source.getColorAttachment(1));
                    complementary.render(destination, read);
                    ImageUtil.downloadTexture(destination.getColorAttachment(0), MemoryUtil.memAddress(rgba));
                    // Uniform source makes sharpening neutral; at the center vignette is neutral.
                    // The real final shader adds (noise.b - .25) / 128 before its UNORM output.
                    int[] actualExpected = {65, 128, 192, 255};
                    for (int channel = 0; channel < 4; channel++)
                        assertNear(actualExpected[channel], Byte.toUnsignedInt(rgba.get(center + channel)), 1, "Complementary final center");
                    LoggerFactory.getLogger("VSDH-Smoke").info("COMPLEMENTARY FINAL GPU PASS: real world0/final with controlled HDR/noise inputs; not a complete world shader test");
                }
            }
        } finally {
            MemoryUtil.memFree(half);
            MemoryUtil.memFree(rgba);
            write.cleanUp(); read.cleanUp(); source.cleanUp(); destination.cleanUp();
        }
        LoggerFactory.getLogger("VSDH-Smoke").info("PACK GPU PASS: legacy GLSL/includes, std140, RGBA16F MRT, texture sampling and rebinding, all pixels verified");
    }

    static void initializeQuad(NativeFullscreenPass pass) { initializeQuad(pass, 13, 9); }
    static void initializeQuad(NativeFullscreenPass pass, int width, int height) {
        float[] quad = {2,0,0,0, 0,2,0,0, 0,0,0,0, -1,-1,0,1};
        float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        for (Std140Layout.Member member : pass.layout().members()) {
            if (member.name().equals("of_ModelViewProjectionMatrix")) pass.layout().putFloats(pass.uniforms(), member.name(), 0, quad);
            if (member.name().equals("of_TextureMatrix"))
                for (int i = 0; i < member.arrayLength(); i++) pass.layout().putFloats(pass.uniforms(), member.name(), i, identity);
            if (member.name().equals("viewWidth")) pass.layout().putFloats(pass.uniforms(), member.name(), 0, width);
            if (member.name().equals("viewHeight")) pass.layout().putFloats(pass.uniforms(), member.name(), 0, height);
            if (member.name().equals("aspectRatio")) pass.layout().putFloats(pass.uniforms(), member.name(), 0, (float) width / height);
        }
    }
    private static void assertNear(float expected, float actual, float tolerance, String what) {
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > tolerance)
            throw new AssertionError(what + ": expected=" + expected + " actual=" + actual);
    }
    static float halfFloat(short encoded) {
        int bits = Short.toUnsignedInt(encoded);
        int exponent = (bits >>> 10) & 31;
        float value = exponent == 0 ? Math.scalb((float) (bits & 1023), -24)
                : exponent == 31 ? Float.NaN : Math.scalb(1f + (bits & 1023) / 1024f, exponent - 15);
        return (bits & 0x8000) == 0 ? value : -value;
    }
}
