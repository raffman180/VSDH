package de.stranger.vsdh.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineState;

import java.util.function.Consumer;

/** Draws an existing Minecraft mesh with a pack program and its targets. */
final class NativeGeometryPass extends NativeGraphicsProgram {
    private final NativeShaderChain.View target;
    private final NativeBlend blend;

    NativeGeometryPass(PackProgramSource.Prepared source, VertexFormat format, NativeShaderChain.View target, NativeBlend blend) {
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
        };
    }

    void render(Consumer<GraphicsPipeline> geometry) {
        var state = blend.apply(PipelineState.getCurrentPipelineState(target.pass()));
        renderDraw(target.framebuffer(), target.pass(), state, command -> geometry.accept(pipeline));
    }
}
