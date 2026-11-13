package de.stranger.vsdh.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineState;

import java.util.function.Consumer;

/** Draws an existing Minecraft mesh with a pack program and its targets. */
final class NativeGeometryPass extends NativeGraphicsProgram {
    /** Looked up per draw: the chain's target halves swap roles between frames. */
    private final java.util.function.Supplier<NativeShaderChain.View> target;
    private final NativeBlend blend;

    NativeGeometryPass(PackProgramSource.Prepared source, VertexFormat format, NativeShaderChain.View target, NativeBlend blend) {
        this(source, format, () -> target, blend);
    }

    NativeGeometryPass(PackProgramSource.Prepared source, VertexFormat format,
                       java.util.function.Supplier<NativeShaderChain.View> target, NativeBlend blend) {
        super(source.path(), source.translated(), source.compiled(), format);
        this.target = target;
        this.blend = blend;
    }

    static VertexFormat skyFormat(SkyPackPlan.Layout layout) {
        return switch (layout) {
            case DISC -> DefaultVertexFormat.POSITION;
            case SUNSET -> DefaultVertexFormat.POSITION_COLOR;
            case CELESTIAL -> DefaultVertexFormat.POSITION_TEX;
            case END -> DefaultVertexFormat.POSITION_TEX_COLOR;
            case CLOUDS -> DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL;
        };
    }

    net.vulkanmod.vulkan.texture.VulkanImage colorAttachment() { return target.get().framebuffer().getColorAttachment(); }

    void render(Consumer<GraphicsPipeline> geometry) {
        var view = target.get();
        var state = blend.apply(PipelineState.getCurrentPipelineState(view.pass(), true));
        renderDraw(view.framebuffer(), view.pass(), state, command -> geometry.accept(pipeline));
    }
}
