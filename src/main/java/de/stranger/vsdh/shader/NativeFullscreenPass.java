package de.stranger.vsdh.shader;

import dev.vitrail.glsl.ProgramTranslator.TranslatedProgram;
import dev.vitrail.glsl.VertexInputs;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.PipelineState;

import static org.lwjgl.vulkan.VK10.*;

/** Fullscreen triangle over the shared native shader resource/descriptor implementation. */
public final class NativeFullscreenPass extends NativeGraphicsProgram {
    public NativeFullscreenPass(String name, TranslatedProgram translated, VulkanProgramCompiler.Compiled compiled) {
        super(name, requireFullscreen(translated), compiled, CustomVertexFormat.NONE);
    }
    private static TranslatedProgram requireFullscreen(TranslatedProgram translated) {
        if (translated.inputs() != VertexInputs.FULLSCREEN) throw new IllegalArgumentException("Expected fullscreen input contract");
        return translated;
    }
    public void prepare(RenderPass pass) { super.prepare(state(pass)); }
    public void render(Framebuffer target, RenderPass pass) {
        renderDraw(target, pass, state(pass), command -> vkCmdDraw(command, 3, 1, 0, 0));
    }
    private static PipelineState state(RenderPass pass) {
        int assembly = PipelineState.AssemblyRasterState.encode(false, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL);
        return new PipelineState(assembly, 0, 0, 0, 0, 15, pass);
    }
}
