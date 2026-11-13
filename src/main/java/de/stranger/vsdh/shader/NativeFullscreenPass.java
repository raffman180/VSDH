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
    private final int blendState;
    public NativeFullscreenPass(String name, TranslatedProgram translated, VulkanProgramCompiler.Compiled compiled) {
        this(name, translated, compiled, 0);
    }

    NativeFullscreenPass(String name, TranslatedProgram translated, VulkanProgramCompiler.Compiled compiled, int blendState) {
        super(name, requireFullscreen(translated), compiled, CustomVertexFormat.NONE);
        this.blendState = blendState;
    }
    private static TranslatedProgram requireFullscreen(TranslatedProgram translated) {
        if (translated.inputs() != VertexInputs.FULLSCREEN) throw new IllegalArgumentException("Expected fullscreen input contract");
        return translated;
    }
    public void prepare(RenderPass pass) { super.prepare(state(pass)); }
    public void render(Framebuffer target, RenderPass pass) {
        renderDraw(target, pass, state(pass), command -> vkCmdDraw(command, 6, 1, 0, 0));
    }
    private PipelineState state(RenderPass pass) {
        int assembly = PipelineState.AssemblyRasterState.encode(false, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL);
        var state = new PipelineState(assembly, blendState, 0, 0, 0, 15, pass);
        if (blendState == 0) return state;
        var framebuffer = pass.getFramebuffer();
        int count = framebuffer.getColorAttachmentCount();
        var attachmentStates = new java.util.ArrayList<Integer>(count);
        for (int index = 0; index < count; index++) {
            // OpenGL bypasses blending on unnormalized integer destinations.
            attachmentStates.add(VulkanTargetFormat.isInteger(framebuffer.getColorAttachment(index).format) ? 0 : blendState);
        }
        return state.withAttachmentBlends(attachmentStates);
    }
}
