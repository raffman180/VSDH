package de.stranger.vsdh.shader;

import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.pack.target.TargetSchedule.Side;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wider rule for swapping the halves of a persistent target at the end of a frame instead of copying the
 * alternate half back ({@link NativeColorTargets#copyBack}). Opt-in: {@code -Dvsdh.swapAfterGeometry=true}.
 * <p>
 * After a swap the main half holds what the copy would have put there; only the alternate half differs
 * (an older picture instead of an equal one). That is invisible as long as the first thing a frame does with
 * the alternate half is to replace all of it: an unblended fullscreen pass. Geometry may then draw into the
 * main half before, and into either half after that pass (Complementary colortex4, Eclipse colortex7/8,
 * Bliss colortex7: one full-image copy per frame each under the default rule, which keeps the copy for
 * every target any geometry writes).
 * <p>
 * Not the default: a fullscreen pass that shrinks gl_Position or discards leaves texels of the alternate
 * half untouched, and those would show this frame's earlier contents of the target instead of last frame's
 * final ones. The default rule has the same gap only for targets no geometry writes.
 */
final class PersistentSwap {
    private PersistentSwap() {}

    static final boolean AFTER_GEOMETRY = requested(System.getProperty("vsdh.swapAfterGeometry"));

    /** Opt-in only: anything but an explicit {@code true} keeps the default rule. */
    static boolean requested(String property) { return "true".equalsIgnoreCase(property); }

    /**
     * What one program does with one target, in frame order.
     *
     * @param readsAlt the program's inputs see the alternate half of the target at that moment
     * @param write    the half it draws into, or null if the target is not among its draw buffers
     * @param replaces an unblended fullscreen draw (every texel of the half is written)
     */
    record Access(boolean readsAlt, Side write, boolean replaces) {}

    /**
     * Whether the alternate half's contents from earlier frames can never be seen: nothing reads that half and
     * nothing draws part of it before a pass replaces it, and some pass does replace it.
     */
    static boolean alternateReplacedFirst(List<Access> frame) {
        for (Access access : frame) {
            if (access.readsAlt()) return false;
            if (access.write() == Side.ALT) return access.replaces();
        }
        return false;
    }

    /**
     * The persistent targets of {@code plan} that may swap under the wider rule. Frame order: BEGIN and PREPARE
     * passes, geometry, DEFERRED passes, geometry again, COMPOSITE and FINAL passes. A target any image store
     * touches is left out, as is one a fullscreen step outside those stages writes.
     */
    static Set<Integer> afterGeometry(ShaderChainPlan plan) {
        var targets = plan.targets();
        var schedule = targets.schedule();
        var stored = new HashSet<Integer>();
        var staged = new HashSet<String>();
        for (var passes : plan.stages().values()) for (var pass : passes) {
            staged.add(pass.name());
            pass.images().values().forEach(attachment -> stored.add(attachment.target()));
            pass.computes().forEach(compute -> compute.images().values().forEach(attachment -> stored.add(attachment.target())));
        }
        plan.setup().forEach(compute -> compute.images().values().forEach(attachment -> stored.add(attachment.target())));
        var geometry = new ArrayList<TargetSchedule.Bound>();
        for (var step : schedule.steps()) {
            if (!step.fullscreen()) geometry.add(step);
            else if (!staged.contains(step.program())) stored.addAll(targets.writes(step.program()));
        }
        var result = new HashSet<Integer>();
        for (int index : plan.copyBack()) {
            if (stored.contains(index)) continue;
            var frame = new ArrayList<Access>();
            for (var stage : ShaderChainPlan.Stage.values()) {
                if (stage == ShaderChainPlan.Stage.DEFERRED)
                    for (var step : geometry) frame.add(access(plan, step, step.program(), index));
                if (stage == ShaderChainPlan.Stage.COMPOSITE)
                    for (var step : geometry) {
                        var late = schedule.stepAfterDeferred(step.program());
                        if (late.isPresent()) frame.add(access(plan, late.get(), step.program(), index));
                    }
                for (var pass : plan.stages().get(stage)) {
                    if (!pass.draws()) continue; // compute only: its image stores are in "stored"
                    var step = schedule.step(pass.name());
                    if (step.isEmpty()) { frame.add(new Access(true, null, false)); continue; } // unknown: keep the copy
                    frame.add(access(plan, step.get(), pass.name(), index));
                }
            }
            if (alternateReplacedFirst(frame)) result.add(index);
        }
        return Set.copyOf(result);
    }

    private static Access access(ShaderChainPlan plan, TargetSchedule.Bound step, String program, int index) {
        var writes = plan.targets().writes(program);
        int rank = writes.indexOf(index);
        if (rank < 0) return new Access(step.read(index) == Side.ALT, null, false);
        boolean blended = plan.targets().blend(program, rank).filter(mode -> !mode.off()).isPresent();
        return new Access(step.read(index) == Side.ALT, step.write(index), step.fullscreen() && !blended);
    }
}
