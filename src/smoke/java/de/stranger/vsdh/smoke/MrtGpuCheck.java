package de.stranger.vsdh.smoke;

import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/** GPU assertions in the development client only. No controls or user worlds are touched. */
final class MrtGpuCheck {
    private static final String VERTEX = """
            #version 450
            void main() {
                vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.375, 1.0);
            }
            """;
    private static final int[][] COLORS = {{255, 0, 0, 255}, {0, 255, 0, 255},
            {0, 0, 255, 255}, {255, 255, 0, 255}};

    static void run() {
        Renderer renderer = Renderer.getInstance();
        if (!Renderer.isRecording()) throw new IllegalStateException("GPU check requires an active frame");
        try (var scope = renderer.temporaryRenderTarget()) {
            checkColors(13, 9);
            checkColors(7, 5);
            checkDepthOnly();
            if (net.vulkanmod.vulkan.device.DeviceManager.supportsIndependentBlend()) checkIndependentBlend();
        }
        LoggerFactory.getLogger("VSDH-Smoke").info("MRT GPU PASS: all pixels in four independent color outputs, two sizes, depth-only output");
    }

    private static void checkIndependentBlend() {
        var framebuffer = Framebuffer.builder(5, 3, 2, false).build();
        var pass = RenderPass.builder(framebuffer).setLoadOp(VK_ATTACHMENT_LOAD_OP_LOAD).build();
        var pipeline = pipeline("""
                #version 450
                layout(location=0) out vec4 first;
                layout(location=1) out vec4 second;
                void main() { first=second=vec4(1,0,0,0.25); }
                """);
        var bytes = MemoryUtil.memAlloc(4);
        try {
            var mode = dev.vitrail.pack.model.BlendMode.parse("SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA").orElseThrow();
            int blend = de.stranger.vsdh.shader.NativeBlend.encode(mode);
            for (int blended = 0; blended < 2; blended++) {
                for (int i = 0; i < 2; i++)
                    de.stranger.vsdh.shader.NativeImageOps.clear(framebuffer.getColorAttachment(i),
                            dev.vitrail.pack.model.TargetFormat.RGBA8_UNORM,
                            new dev.vitrail.pack.target.TargetDirectives.Colour(0, 0, 1, 1));
                draw(framebuffer, pass, pipeline, false, blended == 0 ? List.of(blend, 0) : List.of(0, blend));
                for (int i = 0; i < 2; i++) {
                    ImageUtil.downloadTextureRegion(framebuffer.getColorAttachment(i), MemoryUtil.memAddress(bytes),
                            2, 1, 1, 1, VK_IMAGE_ASPECT_COLOR_BIT, 4);
                    int[] expected = i == blended ? new int[]{64, 0, 191, 255} : new int[]{255, 0, 0, 64};
                    for (int c = 0; c < 4; c++)
                        if (Math.abs(Byte.toUnsignedInt(bytes.get(c)) - expected[c]) > 1)
                            throw new AssertionError("Independent blend attachment=" + i + " channel=" + c
                                    + " expected=" + expected[c] + " actual=" + Byte.toUnsignedInt(bytes.get(c)));
                }
            }
            LoggerFactory.getLogger("VSDH-Smoke").info("MRT INDEPENDENT BLEND PASS: alpha blended and direct attachments in both orders");
        } finally {
            MemoryUtil.memFree(bytes);
            pipeline.scheduleCleanUp();
            pass.cleanUp();
            framebuffer.cleanUp();
        }
    }

    private static void checkColors(int width, int height) {
        Framebuffer framebuffer = Framebuffer.builder(width, height, 4, true).build();
        RenderPass pass = RenderPass.builder(framebuffer).build();
        String fragment = """
                #version 450
                layout(location=0) out vec4 c0;
                layout(location=1) out vec4 c1;
                layout(location=2) out vec4 c2;
                layout(location=3) out vec4 c3;
                void main() { c0=vec4(1,0,0,1); c1=vec4(0,1,0,1);
                              c2=vec4(0,0,1,1); c3=vec4(1,1,0,1); }
                """;
        GraphicsPipeline pipeline = pipeline(fragment);
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            draw(framebuffer, pass, pipeline, false);
            for (int i = 0; i < 4; i++) {
                ImageUtil.downloadTexture(framebuffer.getColorAttachment(i), MemoryUtil.memAddress(pixels));
                for (int pixel = 0; pixel < width * height; pixel++)
                    for (int channel = 0; channel < 4; channel++) {
                        int actual = Byte.toUnsignedInt(pixels.get(pixel * 4 + channel));
                        if (actual != COLORS[i][channel])
                            throw new AssertionError("MRT " + width + "x" + height + " attachment=" + i
                                    + " pixel=" + pixel + " channel=" + channel + " expected=" + COLORS[i][channel] + " actual=" + actual);
                    }
            }
        } finally {
            MemoryUtil.memFree(pixels);
            pipeline.scheduleCleanUp();
            pass.cleanUp();
            framebuffer.cleanUp();
        }
    }

    private static void checkDepthOnly() {
        VulkanImage depth = VulkanImage.createDepthImage(VK_FORMAT_D32_SFLOAT, 7, 5,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, false, true);
        Framebuffer framebuffer = Framebuffer.builder(new VulkanImage[0], depth).build();
        var builder = RenderPass.builder(framebuffer);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        RenderPass pass = builder.build();
        GraphicsPipeline pipeline = pipeline("#version 450\nvoid main() {}\n");
        ByteBuffer pixels = MemoryUtil.memAlloc(7 * 5 * 4);
        try {
            draw(framebuffer, pass, pipeline, true);
            ImageUtil.downloadTexture(depth, MemoryUtil.memAddress(pixels));
            for (int i = 0; i < 35; i++)
                if (Math.abs(pixels.getFloat(i * 4) - 0.375f) > 0.00001f)
                    throw new AssertionError("Depth-only pass pixel " + i + ": " + pixels.getFloat(i * 4));
        } finally {
            MemoryUtil.memFree(pixels);
            pipeline.scheduleCleanUp();
            pass.cleanUp();
            framebuffer.cleanUp();
        }
    }

    private static GraphicsPipeline pipeline(String fragment) {
        var builder = new Pipeline.Builder(CustomVertexFormat.NONE, "vsdh_mrt_gpu_check");
        try (var vertex = SPIRVUtils.compileShader("vsdh_mrt_check.vsh", VERTEX, SPIRVUtils.ShaderKind.VERTEX_SHADER);
             var pixel = SPIRVUtils.compileShader("vsdh_mrt_check.fsh", fragment, SPIRVUtils.ShaderKind.FRAGMENT_SHADER)) {
            builder.setSPIRVs(vertex, pixel);
            builder.setUniforms(List.of(), List.of());
            return builder.createGraphicsPipeline();
        }
    }

    private static void draw(Framebuffer framebuffer, RenderPass pass, GraphicsPipeline pipeline, boolean depth) {
        draw(framebuffer, pass, pipeline, depth, List.of());
    }

    private static void draw(Framebuffer framebuffer, RenderPass pass, GraphicsPipeline pipeline, boolean depth,
                             List<Integer> attachmentBlends) {
        Renderer renderer = Renderer.getInstance();
        renderer.endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var command = Renderer.getCommandBuffer();
            framebuffer.beginRenderPass(command, pass, stack);
            int assembly = PipelineState.AssemblyRasterState.encode(false, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL);
            int depthState = depth ? PipelineState.DepthState.DEPTH_TEST_BIT | PipelineState.DepthState.DEPTH_MASK_BIT
                    | PipelineState.DepthState.encodeDepthFun(519) : 0; // GL_ALWAYS
            var state = new PipelineState(assembly, 0, depthState, 0, 0, 15, pass);
            if (!attachmentBlends.isEmpty()) state = state.withAttachmentBlends(attachmentBlends);
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.getHandle(state));
            vkCmdSetViewport(command, 0, VkViewport.calloc(1, stack).x(0).y(0)
                    .width(framebuffer.getWidth()).height(framebuffer.getHeight()).minDepth(0).maxDepth(1));
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(framebuffer.getWidth(), framebuffer.getHeight());
            vkCmdSetScissor(command, 0, scissor);
            vkCmdSetDepthBias(command, 0, 0, 0);
            vkCmdSetBlendConstants(command, stack.floats(0, 0, 0, 0));
            vkCmdDraw(command, 3, 1, 0, 0);
            renderer.endRenderPass();
            // Direct Vulkan binding must invalidate the renderer's cached pipeline handle.
            renderer.restoreGraphicsPipeline(null);
        }
    }
}
