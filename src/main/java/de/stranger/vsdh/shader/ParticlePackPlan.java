package de.stranger.vsdh.shader;

import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.ProgramFallbacks;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.ShaderProperties;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/** Programs that share Minecraft's PARTICLE mesh, resolved for their actual timing. */
public record ParticlePackPlan(Map<Pass, PackProgramSource.Prepared> programs, Order order,
                               ShaderProperties.Weather weather, boolean rainDepth) {
    enum Pass { BEFORE, AFTER, WEATHER }
    enum Order { BEFORE, MIXED, AFTER }

    public ParticlePackPlan { programs = Map.copyOf(programs); }

    static ParticlePackPlan empty() {
        return new ParticlePackPlan(Map.of(), Order.MIXED, new ShaderProperties.Weather(true, true), false);
    }

    /** When particles are drawn; {@code deferredEmpty}: the chain has no deferred pass. */
    private static Order order(OpenedPack pack, boolean deferredEmpty) {
        var defines = pack.settings().globalDefines(pack.options());
        var defaultOrder = pack.properties().separateEntityDraws(defines) || deferredEmpty ? Order.MIXED : Order.AFTER;
        return switch (pack.properties().particleOrdering(defines).orElse("")) {
            case "before" -> Order.BEFORE;
            case "mixed" -> Order.MIXED;
            case "after" -> Order.AFTER;
            default -> defaultOrder;
        };
    }

    /** Read-ahead of the programs {@link #prepare} will ask for (same candidates, same arguments). */
    static void prefetch(OpenedPack pack, String prefix, dev.vitrail.pack.target.TargetPlan targets) {
        var defines = pack.settings().globalDefines(pack.options());
        var alphas = pack.properties().alphaTests(defines);
        boolean deferredEmpty = ShaderChainPlan.frameOrder(targets).stream()
                .noneMatch(name -> ShaderChainPlan.stageOf(name) == ShaderChainPlan.Stage.DEFERRED);
        var order = order(pack, deferredEmpty);
        var names = new java.util.ArrayList<String>();
        if (order != Order.AFTER) names.add("gbuffers_particles");
        if (order != Order.BEFORE) names.add(order == Order.MIXED ? "gbuffers_particles_translucent" : "gbuffers_particles");
        if (pack.properties().weather(defines).drawn()) names.add("gbuffers_weather");
        for (String name : names) {
            for (String candidate : ProgramFallbacks.chain(name)) {
                if (targets.schedule().step(candidate).isEmpty()
                        || pack.source().file(prefix + candidate + ".vsh").isEmpty()
                        || pack.source().file(prefix + candidate + ".fsh").isEmpty()) continue;
                PackProgramSource.prefetch(pack, prefix + candidate, VertexInputs.PARTICLE,
                        VertexInputs.PARTICLE.elements(), alphas.getOrDefault(candidate, AlphaTest.OFF));
                break;
            }
        }
    }

    static ParticlePackPlan prepare(OpenedPack pack, String prefix, ShaderChainPlan chain) throws IOException {
        var defines = pack.settings().globalDefines(pack.options());
        var weather = pack.properties().weather(defines);
        var alphas = pack.properties().alphaTests(defines);
        var order = order(pack, chain.stages().get(ShaderChainPlan.Stage.DEFERRED).isEmpty());
        var programs = new EnumMap<Pass, PackProgramSource.Prepared>(Pass.class);
        if (order != Order.AFTER) {
            prepare(programs, Pass.BEFORE, "gbuffers_particles", pack, prefix, chain, alphas);
        }
        if (order != Order.BEFORE) {
            prepare(programs, Pass.AFTER, order == Order.MIXED ? "gbuffers_particles_translucent" : "gbuffers_particles",
                    pack, prefix, chain, alphas);
        }
        if (weather.drawn()) {
            prepare(programs, Pass.WEATHER, "gbuffers_weather", pack, prefix, chain, alphas);
        }
        return new ParticlePackPlan(programs, order, weather, pack.properties().rainDepth(defines));
    }

    private static void prepare(Map<Pass, PackProgramSource.Prepared> programs, Pass pass, String name,
                                OpenedPack pack, String prefix, ShaderChainPlan chain, Map<String, AlphaTest> alphas) throws IOException {
        for (String candidate : ProgramFallbacks.chain(name)) {
            if (chain.targets().schedule().step(candidate).isEmpty()
                    || pack.source().file(prefix + candidate + ".vsh").isEmpty()
                    || pack.source().file(prefix + candidate + ".fsh").isEmpty()) continue;
            programs.put(pass, PackProgramSource.prepare(pack, prefix + candidate, VertexInputs.PARTICLE,
                    VertexInputs.PARTICLE.elements(), alphas.getOrDefault(candidate, AlphaTest.OFF)));
            return;
        }
    }
}
