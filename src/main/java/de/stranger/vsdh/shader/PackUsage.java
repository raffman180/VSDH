package de.stranger.vsdh.shader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What the prepared programs of a plan really read, from their optimised SPIR-V bindings. */
final class PackUsage {
    private PackUsage() {}

    static List<PackProgramSource.Prepared> programs(WorldPackPlan plan) {
        var programs = new ArrayList<PackProgramSource.Prepared>(plan.terrain().values());
        if (plan.distant() != null) programs.addAll(plan.distant().programs());
        programs.addAll(plan.skyPrograms().programs().values());
        programs.addAll(plan.particles().programs().values());
        if (plan.entities() != null) programs.addAll(plan.entities().programs());
        for (var stage : plan.chain().stages().values()) for (var pass : stage) {
            if (pass.draws()) programs.add(pass.program());
            pass.computes().forEach(c -> programs.add(c.program().asProgram()));
        }
        plan.chain().setup().forEach(c -> programs.add(c.program().asProgram()));
        plan.chain().shadowComputes().forEach(c -> programs.add(c.program().asProgram()));
        if (plan.shadow() != null) {
            programs.addAll(plan.shadow().programs().values());
            if (plan.shadow().entities() != null) programs.add(plan.shadow().entities());
            if (plan.shadow().blocks() != null) programs.add(plan.shadow().blocks());
        }
        return programs;
    }

    /** Sampler and image names some program of the plan actually reads (unused declarations excluded). */
    static Set<String> activeSamplers(WorldPackPlan plan) {
        return activeSamplers(programs(plan));
    }

    static Set<String> activeSamplers(List<PackProgramSource.Prepared> programs) {
        var names = new HashSet<String>();
        for (var source : programs) {
            var active = new HashSet<Integer>();
            source.compiled().stages().values().forEach(bytes -> active.addAll(SpirvInterface.bindings(bytes)));
            for (var sampler : source.translated().samplers())
                if (active.contains(source.compiled().bindings().get(sampler.name()))) names.add(sampler.name());
        }
        return names;
    }
}
