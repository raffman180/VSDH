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

    static ParticlePackPlan prepare(OpenedPack pack, String prefix, ShaderChainPlan chain) throws IOException {
        var defines = pack.settings().globalDefines(pack.options());
        var weather = pack.properties().weather(defines);
        var alphas = pack.properties().alphaTests(defines);
        var defaultOrder = chain.stages().get(ShaderChainPlan.Stage.DEFERRED).isEmpty() ? Order.MIXED : Order.AFTER;
        var order = switch (pack.properties().particleOrdering(defines).orElse("")) {
            case "before" -> Order.BEFORE;
            case "mixed" -> Order.MIXED;
            case "after" -> Order.AFTER;
            default -> defaultOrder;
        };
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
