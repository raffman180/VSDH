package de.stranger.vsdh.shader;

import dev.vitrail.pack.model.TargetName;
import dev.vitrail.pack.target.TargetSchedule;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which geometry programs sample a colour target they also draw into (OpenGL binds the attachment itself,
 * and so does Iris). Where the device has VK_EXT_attachment_feedback_loop_layout the pass binds the live
 * attachment; elsewhere, or with {@code -Dvsdh.feedbackLoops=false}, it samples a snapshot copy made before
 * the draw ({@link NativeColorTargets#feedbackImage}). Decided from the immutable plan, per program and image.
 * <p>
 * No barrier is recorded between the draws of such a pass, so a loop is only as defined as it is in OpenGL:
 * a fragment that samples a texel an earlier fragment of the same pass wrote may see either value. That is
 * accepted for colour targets (packs read their own pixel there); it is not for the DH depth, see
 * {@link #DISTANT_DEPTH_REQUESTED}.
 */
final class FeedbackLoops {
    private FeedbackLoops() {}

    /**
     * Opt-in since 14.11.2026 ({@code -Dvsdh.feedbackLoops=true}): in a player's pack the water "moved along" with the
     * camera while gbuffers_water and dh_water read colortex7/11 live. Overlapping water fragments of one pass read
     * what earlier ones wrote without a barrier, which Vulkan leaves undefined. Default: the snapshot copies.
     */
    static final boolean REQUESTED = "true".equalsIgnoreCase(System.getProperty("vsdh.feedbackLoops"));

    static boolean enabled() {
        return REQUESTED && net.vulkanmod.vulkan.device.DeviceManager.isAttachmentFeedbackLoopEnabled();
    }

    /** Colour targets named by the samplers, e.g. {@code colortex7} and {@code gaux4} both as 7. */
    static Set<Integer> colourTargets(Iterable<String> samplers) {
        var result = new TreeSet<Integer>();
        for (String sampler : samplers) TargetName.index(sampler).ifPresent(result::add);
        return result;
    }

    /**
     * Colour targets every geometry program of the plan really samples, by bare program name. Two prepared
     * variants of one program (terrain and entity builds of gbuffers_water) are merged.
     */
    static Map<String, Set<Integer>> colourReads(List<PackProgramSource.Prepared> programs) {
        var result = new HashMap<String, Set<Integer>>();
        for (var source : programs) {
            var reads = colourTargets(PackUsage.activeSamplers(List.of(source)));
            if (!reads.isEmpty()) result.computeIfAbsent(bare(source.path()), ignored -> new TreeSet<>()).addAll(reads);
        }
        return result;
    }

    /**
     * The targets of one geometry step that are read from the very half the step draws into.
     *
     * @param writes the program's draw buffers
     * @param reads  the colour targets the program samples
     */
    static Set<Integer> loops(TargetSchedule.Bound step, List<Integer> writes, Set<Integer> reads) {
        var result = new HashSet<Integer>();
        if (step.fullscreen()) return result; // a fullscreen pass reads one half and writes the other
        for (int index : writes) if (reads.contains(index) && step.read(index) == step.write(index)) result.add(index);
        return result;
    }

    /**
     * {@code -Dvsdh.dhDepthFeedbackLoop=true}: DH programs sample the live full DH depth while they draw into it.
     * Off by default. dh_water writes depth, and packs march rays through dhDepthTex at other pixels (Eclipse's
     * water reflections): texels written earlier in the same pass are then read without a barrier between the
     * draws, which Vulkan leaves undefined even in the feedback-loop layout, and distant water flickered. The
     * default is the opaque copy (dhDepthTex1's image), complete and unchanged for the whole water pass.
     */
    static final boolean DISTANT_DEPTH_REQUESTED = distantDepthRequested(System.getProperty("vsdh.dhDepthFeedbackLoop"));

    /** Opt-in only: anything but an explicit {@code true} keeps the opaque copy. */
    static boolean distantDepthRequested(String property) { return "true".equalsIgnoreCase(property); }

    static boolean distantDepthEnabled() { return DISTANT_DEPTH_REQUESTED && enabled(); }

    /** A DH program that samples the full DH depth, which it also draws into. */
    static boolean distantDepthLoop(Set<String> activeSamplers) {
        return activeSamplers.contains("dhDepthTex") || activeSamplers.contains("dhDepthTex0");
    }

    static String bare(String path) { return path.substring(path.lastIndexOf('/') + 1); }
}
