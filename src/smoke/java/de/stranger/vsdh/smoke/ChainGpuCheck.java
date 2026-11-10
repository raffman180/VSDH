package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.NativeImageOps;
import de.stranger.vsdh.shader.NativeShaderChain;
import de.stranger.vsdh.shader.NativeWorldDepth;
import de.stranger.vsdh.shader.ShaderChainPlan;
import dev.vitrail.pack.target.TargetDirectives.Colour;
import dev.vitrail.pack.target.TargetSchedule.Side;
import dev.vitrail.pack.source.OpenedPack;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A whole parsed shader chain, checked against a known closed-form result over multiple frames. */
final class ChainGpuCheck {
    private record Prepared(ShaderChainPlan normal, ShaderChainPlan noFinal, ShaderChainPlan complementary) {}
    private static CompletableFuture<Prepared> pending;

    static void prepare() {
        if (pending != null) return;
        pending = CompletableFuture.supplyAsync(() -> {
            try {
                var path = Path.of(ChainGpuCheck.class.getResource("/shaderpacks/vsdh-chain").toURI());
                ShaderChainPlan normal;
                ShaderChainPlan complementary = null;
                String external = System.getProperty("vsdh.smoke.pack");
                if (external != null) {
                    try (var pack = OpenedPack.open(Path.of(external), Map.of(), "MEDIUM")) {
                        complementary = ShaderChainPlan.prepare(pack, "world0");
                    }
                }
                Path noFinal = Files.createTempFile("vsdh-chain-no-final-", ".zip");
                try {
                    try (var pack = OpenedPack.open(path, Map.of(), "");
                         var zip = new ZipOutputStream(Files.newOutputStream(noFinal))) {
                        normal = ShaderChainPlan.prepare(pack, "");
                        Path shaderRoot = path.resolve("shaders");
                        try (var files = Files.walk(shaderRoot)) {
                            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                                String relative = shaderRoot.relativize(file).toString().replace('\\', '/');
                                if (relative.equals("final.vsh") || relative.equals("final.fsh")) continue;
                                zip.putNextEntry(new ZipEntry("shaders/" + relative));
                                zip.write(Files.readAllBytes(file));
                                zip.closeEntry();
                            }
                        }
                    }
                    try (var pack = OpenedPack.open(noFinal, Map.of(), "")) {
                        return new Prepared(normal, ShaderChainPlan.prepare(pack, ""), complementary);
                    }
                } finally { Files.deleteIfExists(noFinal); }
            } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        });
    }
    static boolean ready() { return pending != null && pending.isDone(); }
    static void run() {
        var prepared = pending.join();
        check(prepared.normal, 13, 9, org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT);
        check(prepared.normal, 17, 11, org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT);
        check(prepared.normal, 13, 9, org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT);
        check(prepared.noFinal, 13, 9, org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT);
        if (prepared.complementary != null) prepareComplementary(prepared.complementary);
        LoggerFactory.getLogger("VSDH-Smoke").info("CHAIN GPU PASS: stages, fullscreen blending, scaled HDR/mips, four-frame history, rebuild/no-final, D32/D24S8/D32S8 snapshots and stencil copies");
    }

    private static void check(ShaderChainPlan plan, int width, int height, int depthFormat) {
        var output = Framebuffer.builder(width, height, 1, false).build();
        var worldDepth = VulkanImage.createDepthImage(depthFormat, width, height,
                org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT
                        | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT, false, true);
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 8);
        try (var scope = Renderer.getInstance().temporaryRenderTarget();
             var chain = new NativeShaderChain(plan, width, height, 8L << 20);
             var depth = new NativeWorldDepth(worldDepth, true, true)) {
            if (chain.targets().image(0, Side.MAIN) == chain.targets().image(0, Side.ALT))
                throw new AssertionError("Fullscreen target has no independent write half");
            if (chain.targets().image(1, Side.MAIN).width != (int) (width * 0.5f))
                throw new AssertionError("Relative buffer size ignored");
            if (chain.targets().image(0, Side.MAIN).mipLevels < 3 || chain.targets().image(1, Side.MAIN).mipLevels < 3)
                throw new AssertionError("Diagnostic mip directives were not applied");
            var fog = new Colour(0.03125f, 0.0625f, 0.125f, 1);
            for (int frame = 0; frame < 4; frame++) {
                chain.beginFrame(fog);
                depth.beginFrame();
                expectThrows(() -> chain.beginFrame(fog));
                expectThrows(() -> chain.render(ShaderChainPlan.Stage.COMPOSITE, null, null, null));
                hdr(chain.targets().image(0, Side.MAIN), pixels, fog.r(), fog.g(), fog.b());
                hdr(chain.targets().image(0, Side.ALT), pixels, fog.r(), fog.g(), fog.b());
                if (frame == 0) checkMipRenderPassFinalLayout(chain, pixels, width, height, fog);
                var writer = (NativeShaderChain.UniformWriter) (source, pass) -> {
                    NativePassGpuCheck.initializeQuad(pass, width, height);
                    if (source.name().equals("begin")) pass.layout().putFloats(pass.uniforms(), "seedValue", 0, 0.25f);
                };
                var external = (NativeShaderChain.ExternalImages) (program, binding) -> {
                    if (binding.kind() == dev.vitrail.pack.target.SamplerPlan.Kind.DEPTH) return depth.image(binding.sampler());
                    throw new AssertionError("Unexpected external input " + program + "/" + binding.sampler());
                };
                chain.render(ShaderChainPlan.Stage.BEGIN, null, writer, external);
                hdr(chain.targets().image(0, Side.ALT), pixels, 0.28125f, fog.g(), fog.b());
                chain.render(ShaderChainPlan.Stage.PREPARE, null, writer, external);
                var integer = chain.targets().image(10, Side.ALT);
                ImageUtil.downloadTextureRegion(integer, MemoryUtil.memAddress(pixels), 0, 0, 1, 1, integer.aspect, 4);
                if (integer.format != org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UINT
                        || Byte.toUnsignedInt(pixels.get(0)) != 7 || Byte.toUnsignedInt(pixels.get(1)) != 19
                        || Byte.toUnsignedInt(pixels.get(2)) != 33 || Byte.toUnsignedInt(pixels.get(3)) != 250)
                    throw new AssertionError("Fullscreen blend must bypass integer outputs");
                var geometry = chain.geometry("gbuffers_terrain", false, null);
                if (geometry.framebuffer().getColorAttachment() != chain.targets().image(0, Side.ALT))
                    throw new AssertionError("Geometry writes to wrong half after prepare");
                // Synthetic geometry input isolates buffer scheduling from the unfinished terrain shader hook.
                NativeImageOps.clear(geometry.framebuffer().getColorAttachment(), dev.vitrail.pack.model.TargetFormat.RGBA16_FLOAT,
                        new Colour(0.25f, 0.5f, 0.75f, 1));
                NativeImageOps.clearDepth(worldDepth, 0.25f, 42);
                depth.capturePreHand();
                NativeImageOps.clearDepth(worldDepth, 0.5f, 59);
                depth.capturePreTranslucent();
                chain.render(ShaderChainPlan.Stage.DEFERRED, null, writer, external);
                // The checkerboard's mip 2 must be the average .75, not a clamped base-level lookup.
                hdr(chain.targets().image(0, Side.MAIN), pixels, 0.375f, 0.75f, 1.125f);
                NativeImageOps.clearDepth(worldDepth, 0.75f, 76);
                chain.render(ShaderChainPlan.Stage.COMPOSITE, null, writer, external);
                hdr(chain.targets().image(4, Side.ALT), pixels, 0.75f, 0.5f, 0.25f);
                if (VulkanImage.hasStencilComponent(depthFormat)) {
                    ImageUtil.downloadTextureRegion(depth.image("depthtex2"), MemoryUtil.memAddress(pixels), 0, 0, 1, 1,
                            org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT, 1);
                    near(42, Byte.toUnsignedInt(pixels.get(0)), 0, "pre-hand stencil");
                    ImageUtil.downloadTextureRegion(depth.image("depthtex1"), MemoryUtil.memAddress(pixels), 0, 0, 1, 1,
                            org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT, 1);
                    near(59, Byte.toUnsignedInt(pixels.get(0)), 0, "pre-translucent stencil");
                }
                chain.render(ShaderChainPlan.Stage.FINAL, output.getColorAttachment(), writer, external);
                depth.endFrame();
                ImageUtil.downloadTexture(output.getColorAttachment(), MemoryUtil.memAddress(pixels));
                int[] expected = {Math.round((0.375f + frame * 0.125f) * 255), 191, 255, 255};
                for (int pixel = 0; pixel < width * height; pixel++) for (int channel = 0; channel < 4; channel++)
                    near(expected[channel], Byte.toUnsignedInt(pixels.get(pixel * 4 + channel)), 1, "final channel " + channel);
                hdr(chain.targets().image(2, Side.MAIN), pixels, (frame + 1) * 0.125f, 0, 0);
                hdr(chain.targets().image(2, Side.ALT), pixels, (frame + 1) * 0.125f, 0, 0);
            }
            if (chain.framesCompleted() != 4) throw new AssertionError("Wrong completed frame count");
        } finally { MemoryUtil.memFree(pixels); output.cleanUp(); worldDepth.free(); }
    }
    private static void prepareComplementary(ShaderChainPlan source) {
        var output = Framebuffer.builder(64, 48, 1, false).build();
        try (var chain = new NativeShaderChain(source, 64, 48, 8L << 20)) {
            chain.prepareOutput(output.getColorAttachment());
            long passes = source.stages().values().stream().mapToLong(java.util.List::size).sum();
            if (passes != 9) throw new AssertionError("Complementary chain changed: " + passes);
            LoggerFactory.getLogger("VSDH-Smoke").info("COMPLEMENTARY CHAIN PREPARE PASS: {} native Vulkan pipelines and {} targets constructed; world draw is not enabled", passes, chain.targets().layout().targets().size());
        } finally { output.cleanUp(); }
    }

    private static void checkMipRenderPassFinalLayout(NativeShaderChain chain, ByteBuffer pixels, int width, int height, Colour fog) {
        var image = chain.targets().image(0, Side.MAIN);
        var framebuffer = Framebuffer.builder(new VulkanImage[]{image}, null).ownsAttachments(false).build();
        var builder = net.vulkanmod.vulkan.framebuffer.RenderPass.builder(framebuffer);
        builder.getColorAttachmentInfo().setFinalLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        var pass = builder.build();
        var begin = chain.plan().stages().get(ShaderChainPlan.Stage.BEGIN).getFirst().program();
        try (var shader = new de.stranger.vsdh.shader.NativeFullscreenPass(begin.path(), begin.translated(), begin.compiled())) {
            NativePassGpuCheck.initializeQuad(shader, width, height);
            shader.layout().putFloats(shader.uniforms(), "seedValue", 0, 0.25f);
            shader.render(framebuffer, pass);
            NativeImageOps.mipmaps(image, true);
            ImageUtil.downloadTextureGl(image, 2, MemoryUtil.memAddress(pixels));
            for (int i = 0; i < Math.max(1, width >> 2) * Math.max(1, height >> 2); i++) {
                near(0.25f, NativePassGpuCheck.halfFloat(pixels.getShort(i * 8)), 0.001f, "final-layout mip red");
                near(0, NativePassGpuCheck.halfFloat(pixels.getShort(i * 8 + 2)), 0.001f, "final-layout mip green");
            }
        } finally { pass.cleanUp(); framebuffer.cleanUp(false); }
        NativeImageOps.clear(image, dev.vitrail.pack.model.TargetFormat.RGBA16_FLOAT, fog);
    }
    private static void hdr(VulkanImage image, ByteBuffer pixels, float r, float g, float b) {
        ImageUtil.downloadTexture(image, MemoryUtil.memAddress(pixels));
        for (int pixel = 0; pixel < image.width * image.height; pixel++) {
            near(r, NativePassGpuCheck.halfFloat(pixels.getShort(pixel * 8)), 0.001f, "HDR red");
            near(g, NativePassGpuCheck.halfFloat(pixels.getShort(pixel * 8 + 2)), 0.001f, "HDR green");
            near(b, NativePassGpuCheck.halfFloat(pixels.getShort(pixel * 8 + 4)), 0.001f, "HDR blue");
        }
    }
    private static void expectThrows(Runnable call) {
        try { call.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Invalid frame/stage transition was accepted");
    }
    private static void near(float expected, float actual, float tolerance, String what) {
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > tolerance)
            throw new AssertionError(what + ": expected=" + expected + " actual=" + actual);
    }
}
