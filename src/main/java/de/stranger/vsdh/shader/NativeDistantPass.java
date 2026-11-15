package de.stranger.vsdh.shader;

import com.braffolk.dhvulkan.compat.Compat;
import com.braffolk.dhvulkan.core.VulkanRenderContext;
import dev.vitrail.glsl.VertexInputs;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.PipelineState;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;

/** A pack pass over the original DH 16-byte vertex data and UINT32 index buffer. */
final class NativeDistantPass extends NativeGraphicsProgram {
    private final NativeBlend blend;
    private final boolean translucent;

    /**
     * DH's translucent pass blends with SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA and
     * Iris keeps that state for dh_water unless the pack overrides it (blend.dh_water...). Mellow and
     * Solas rely on it: drawn opaque, their far water lost the sea floor behind it and came out light.
     */
    private static final int DH_TRANSLUCENT_BLEND = PipelineState.BlendState.ENABLE_BIT
            | PipelineState.BlendState.encode(VK_BLEND_FACTOR_SRC_ALPHA, PipelineState.BlendState.SRC_RGB_OFFSET, PipelineState.BlendState.FACTOR_MASK)
            | PipelineState.BlendState.encode(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, PipelineState.BlendState.DST_RGB_OFFSET, PipelineState.BlendState.FACTOR_MASK)
            | PipelineState.BlendState.encode(VK_BLEND_FACTOR_ONE, PipelineState.BlendState.SRC_A_OFFSET, PipelineState.BlendState.FACTOR_MASK)
            | PipelineState.BlendState.encode(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, PipelineState.BlendState.DST_A_OFFSET, PipelineState.BlendState.FACTOR_MASK);

    NativeDistantPass(PackProgramSource.Prepared source, NativeBlend blend) {
        this(source, blend, false);
    }

    NativeDistantPass(PackProgramSource.Prepared source, NativeBlend blend, boolean translucent) {
        super(source.path(), source.translated(), source.compiled(), VulkanRenderContext.packTerrainFormat());
        if (source.translated().inputs() != VertexInputs.DISTANT) throw new IllegalArgumentException("Expected DH program");
        this.blend = blend;
        this.translucent = translucent;
    }

    void render(Framebuffer framebuffer, RenderPass pass, Consumer<VkCommandBuffer> draws) {
        // DH draws its LODs with back-face culling and counter-clockwise front faces, in its own GL
        // renderer and in the plain Vulkan path alike. Without it, the downward faces of LOD water
        // above kelp or seagrass reach the pack, which shades them as water seen from below.
        // The game's projection with renderDraw's unflipped viewport stores rows bottom-up (GL
        // orientation), which mirrors the winding in framebuffer space: DH's CCW is CW here.
        int raster = PipelineState.AssemblyRasterState.encode(VK_CULL_MODE_BACK_BIT, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
                VK_POLYGON_MODE_FILL, VK_FRONT_FACE_CLOCKWISE); // DH's CCW, seen in a bottom-up target
        int depth = PipelineState.DepthState.DEPTH_TEST_BIT | PipelineState.DepthState.DEPTH_MASK_BIT
                | PipelineState.DepthState.encodeDepthFun(515);
        var baseline = new PipelineState(raster, translucent ? DH_TRANSLUCENT_BLEND : 0, depth, 0, 0, 15, pass);
        if (translucent) {
            // OpenGL bypasses blending on unnormalized integer destinations.
            int count = framebuffer.getColorAttachmentCount();
            var states = new java.util.ArrayList<Integer>(count);
            for (int index = 0; index < count; index++)
                states.add(VulkanTargetFormat.isInteger(framebuffer.getColorAttachment(index).format) ? 0 : DH_TRANSLUCENT_BLEND);
            baseline = baseline.withAttachmentBlends(states);
        }
        renderDraw(framebuffer, pass, blend.apply(baseline), draws);
    }

    void draw(VkCommandBuffer command, ByteBuffer offset, Object vertices, Object indices, int count,
              float x, float y, float z) {
        offset.putFloat(0, x).putFloat(4, y).putFloat(8, z);
        vkCmdPushConstants(command, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, offset);
        Compat.drawIndexed(vertices, indices, count);
    }
}
