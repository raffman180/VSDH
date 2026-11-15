package de.stranger.vsdh.shader;

import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.ProgramFallbacks;
import dev.vitrail.pack.model.RenderStage;
import dev.vitrail.pack.source.OpenedPack;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Four Minecraft 1.21.1 vertex layouts used by the vanilla sky draws, plus the vanilla cloud mesh. */
public record SkyPackPlan(Map<Layout, PackProgramSource.Prepared> programs) {
    enum Layout {
        DISC("gbuffers_skybasic", List.of("Position")),
        SUNSET("gbuffers_skybasic", List.of("Position", "Color")),
        CELESTIAL("gbuffers_skytextured", List.of("Position", "UV0")),
        END("gbuffers_skytextured", List.of("Position", "UV0", "Color")),
        /** LevelRenderer.renderClouds: drawn after translucent terrain, so after deferred like Iris. */
        CLOUDS("gbuffers_clouds", List.of("Position", "UV0", "Color", "Normal"));

        final String program;
        final List<String> elements;

        boolean afterDeferred() { return this == CLOUDS; }

        Layout(String program, List<String> elements) {
            this.program = program;
            this.elements = elements;
        }

        static Layout forStage(RenderStage stage) {
            return switch (stage) {
                case SKY, STARS, VOID -> DISC;
                case SUNSET -> SUNSET;
                case SUN, MOON -> CELESTIAL;
                case CUSTOM_SKY -> END;
                case CLOUDS -> CLOUDS;
                default -> throw new IllegalArgumentException("Not a sky stage: " + stage);
            };
        }
    }

    public SkyPackPlan { programs = Map.copyOf(programs); }

    static SkyPackPlan empty() { return new SkyPackPlan(Map.of()); }

    /** Read-ahead of the programs {@link #prepare} will ask for (same candidates, same arguments). */
    static void prefetch(OpenedPack pack, String prefix, dev.vitrail.pack.target.TargetPlan targets) {
        var alphas = pack.properties().alphaTests(pack.settings().globalDefines(pack.options()));
        for (Layout layout : Layout.values()) {
            for (String candidate : ProgramFallbacks.chain(layout.program)) {
                if (pack.source().file(prefix + candidate + ".vsh").isEmpty()
                        || pack.source().file(prefix + candidate + ".fsh").isEmpty()
                        || targets.schedule().step(candidate).isEmpty()) continue;
                PackProgramSource.prefetch(pack, prefix + candidate, VertexInputs.SKY,
                        layout.elements, alphas.getOrDefault(candidate, AlphaTest.OFF));
                break;
            }
        }
    }

    static SkyPackPlan prepare(OpenedPack pack, String prefix, ShaderChainPlan chain) throws IOException {
        var alphas = pack.properties().alphaTests(pack.settings().globalDefines(pack.options()));
        var programs = new EnumMap<Layout, PackProgramSource.Prepared>(Layout.class);
        for (Layout layout : Layout.values()) {
            for (String candidate : ProgramFallbacks.chain(layout.program)) {
                if (pack.source().file(prefix + candidate + ".vsh").isEmpty()
                        || pack.source().file(prefix + candidate + ".fsh").isEmpty()
                        || chain.targets().schedule().step(candidate).isEmpty()) continue;
                programs.put(layout, PackProgramSource.prepare(pack, prefix + candidate, VertexInputs.SKY,
                        layout.elements, alphas.getOrDefault(candidate, AlphaTest.OFF)));
                break;
            }
        }
        return new SkyPackPlan(programs);
    }
}
