package de.stranger.vsdh.shader;

import dev.vitrail.pack.model.BlendMode;
import dev.vitrail.pack.target.TargetPlan;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.PipelineState.BlendState;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/** Converts pack blend factors in output-location order, inheriting unspecified attachments. */
public final class NativeBlend {
    private static final int INHERIT = -1;
    private final List<Integer> states;

    private NativeBlend(List<Integer> states) { this.states = List.copyOf(states); }

    public static NativeBlend forProgram(TargetPlan targets, String program, int attachments) {
        var result = new ArrayList<Integer>(attachments);
        for (int rank = 0; rank < attachments; rank++)
            result.add(targets.blend(program, rank).map(NativeBlend::encode).orElse(INHERIT));
        return new NativeBlend(result);
    }

    public PipelineState apply(PipelineState baseline) {
        if (states.stream().allMatch(state -> state == INHERIT)) return baseline;
        var effective = new ArrayList<Integer>(states.size());
        for (int rank = 0; rank < states.size(); rank++) {
            int state = states.get(rank);
            effective.add(state == INHERIT ? baseline.blendState(rank) : state);
        }
        return baseline.withAttachmentBlends(effective);
    }

    public static int encode(BlendMode mode) {
        if (mode.off()) return 0;
        return BlendState.ENABLE_BIT
                | BlendState.encode(factor(mode.srcRgb()), BlendState.SRC_RGB_OFFSET, BlendState.FACTOR_MASK)
                | BlendState.encode(factor(mode.dstRgb()), BlendState.DST_RGB_OFFSET, BlendState.FACTOR_MASK)
                | BlendState.encode(factor(mode.srcAlpha()), BlendState.SRC_A_OFFSET, BlendState.FACTOR_MASK)
                | BlendState.encode(factor(mode.dstAlpha()), BlendState.DST_A_OFFSET, BlendState.FACTOR_MASK);
    }

    private static int factor(String name) {
        return switch (name) {
            case "ZERO" -> VK_BLEND_FACTOR_ZERO;
            case "ONE" -> VK_BLEND_FACTOR_ONE;
            case "SRC_COLOR" -> VK_BLEND_FACTOR_SRC_COLOR;
            case "ONE_MINUS_SRC_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case "DST_COLOR" -> VK_BLEND_FACTOR_DST_COLOR;
            case "ONE_MINUS_DST_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case "SRC_ALPHA" -> VK_BLEND_FACTOR_SRC_ALPHA;
            case "ONE_MINUS_SRC_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case "DST_ALPHA" -> VK_BLEND_FACTOR_DST_ALPHA;
            case "ONE_MINUS_DST_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case "CONSTANT_COLOR" -> VK_BLEND_FACTOR_CONSTANT_COLOR;
            case "ONE_MINUS_CONSTANT_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case "CONSTANT_ALPHA" -> VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case "ONE_MINUS_CONSTANT_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            case "SRC_ALPHA_SATURATE" -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            default -> throw new IllegalArgumentException("Unsupported shaderpack blend factor: " + name);
        };
    }
}
