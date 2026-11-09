package de.stranger.vsdh.shader;

import dev.vitrail.glsl.ProgramTranslator.TranslatedProgram;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.memory.IndexBuffer;
import net.vulkanmod.vulkan.memory.VertexBuffer;
import net.vulkanmod.vulkan.shader.PipelineState;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.vulkan.VK10.*;

/** Native shader terrain draw. Mesh buffers are borrowed and owned by the renderer. */
public final class NativeTerrainPass extends NativeGraphicsProgram {
    public NativeTerrainPass(String name, TranslatedProgram translated, VulkanProgramCompiler.Compiled compiled) {
        super(name, requireTerrain(translated), compiled, CustomVertexFormat.SHADER_TERRAIN);
    }
    private static TranslatedProgram requireTerrain(TranslatedProgram translated) {
        if (!translated.inputs().vulkanTerrain()) throw new IllegalArgumentException("Expected VSDH terrain input contract");
        return translated;
    }
    public void prepare(RenderPass pass) { super.prepare(opaqueState(pass)); }

    /** Draw the renderer's actual area buffers; the callback only binds buffers/push constants and issues draws. */
    public void renderBatches(Framebuffer target, RenderPass pass, PipelineState state,
                              java.util.function.Consumer<net.vulkanmod.vulkan.shader.GraphicsPipeline> batches) {
        renderDraw(target,pass,state,command -> batches.accept(pipeline));
    }

    public void render(Framebuffer target, RenderPass pass, VertexBuffer vertices, IndexBuffer indices,
                       int indexCount, int firstIndex, int vertexOffset, int section,
                       float chunkX, float chunkY, float chunkZ) {
        if (indexCount < 0 || firstIndex < 0 || section < 0) throw new IllegalArgumentException("Negative terrain draw range");
        renderDraw(target, pass, opaqueState(pass), command -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindVertexBuffers(command, 0, stack.longs(vertices.getId()), stack.longs(vertices.getOffset()));
                vkCmdBindIndexBuffer(command, indices.getId(), indices.getOffset(), indices.indexType.type);
                vkCmdPushConstants(command, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0,
                        stack.floats(chunkX, chunkY, chunkZ));
                vkCmdDrawIndexed(command, indexCount, 1, firstIndex, vertexOffset, section);
            }
        });
    }
    private static PipelineState opaqueState(RenderPass pass) {
        int assembly = PipelineState.AssemblyRasterState.encode(false, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL);
        int depth = pass.getFramebuffer().getDepthAttachment() == null ? 0
                : PipelineState.DepthState.DEPTH_TEST_BIT | PipelineState.DepthState.DEPTH_MASK_BIT
                | PipelineState.DepthState.encodeDepthFun(515);
        return new PipelineState(assembly, 0, depth, 0, 0, 15, pass);
    }
}
