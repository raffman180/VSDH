package de.stranger.vsdh.shader;

import com.braffolk.dhvulkan.compat.Compat;
import com.braffolk.dhvulkan.core.VulkanRenderContext;
import dev.vitrail.glsl.VertexInputs;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.PipelineState;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;

/** A pack pass over the original DH 16-byte vertex data and UINT32 index buffer. */
final class NativeDistantPass extends NativeGraphicsProgram {
    private final NativeBlend blend;

    NativeDistantPass(PackProgramSource.Prepared source, NativeBlend blend) {
        super(source.path(), source.translated(), source.compiled(), VulkanRenderContext.packTerrainFormat());
        if (source.translated().inputs() != VertexInputs.DISTANT) throw new IllegalArgumentException("Expected DH program");
        this.blend = blend;
    }

    void render(Framebuffer framebuffer, RenderPass pass, Consumer<VkCommandBuffer> draws) {
        int raster = PipelineState.AssemblyRasterState.encode(false, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL);
        int depth = PipelineState.DepthState.DEPTH_TEST_BIT | PipelineState.DepthState.DEPTH_MASK_BIT
                | PipelineState.DepthState.encodeDepthFun(515);
        renderDraw(framebuffer, pass, blend.apply(new PipelineState(raster, 0, depth, 0, 0, 15, pass)), draws);
    }

    void draw(VkCommandBuffer command, ByteBuffer offset, Object vertices, Object indices, int count,
              float x, float y, float z) {
        offset.putFloat(0, x).putFloat(4, y).putFloat(8, z);
        vkCmdPushConstants(command, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, offset);
        Compat.drawIndexed(vertices, indices, count);
    }
}
