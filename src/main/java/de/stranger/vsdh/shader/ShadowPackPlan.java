package de.stranger.vsdh.shader;

import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.model.TextureStage;
import dev.vitrail.pack.source.ShadowCasters;
import dev.vitrail.pack.source.IncludeExpander;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.DrawBuffers;
import dev.vitrail.pack.texture.PackTextures;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** The pack's actual shadow programs and output slots. */
public record ShadowPackPlan(Map<String, PackProgramSource.Prepared> programs, List<Integer> outputs,
                             PackProgramSource.Prepared entities, PackProgramSource.Prepared blocks, ShadowCasters casters) {
    public ShadowPackPlan { programs = Map.copyOf(programs); outputs = List.copyOf(outputs); }

    public static ShadowPackPlan prepare(OpenedPack pack, String prefix, int ceiling,
                                         Set<String> customUniforms, boolean distant, PackTextures textures,
                                         Consumer<String> warning) throws IOException {
        String path = prefix + "shadow";
        if (pack.source().file(path + ".vsh").isEmpty()) return null;
        var defines = pack.settings().globalDefines(pack.options());
        var alpha = pack.properties().alphaTests(defines);
        var terrain = PackProgramSource.prepareTerrain(pack, path, alpha.getOrDefault("shadow", AlphaTest.ONE_TENTH));
        var outputs = outputs(pack, path, ceiling);
        var programs = new LinkedHashMap<String, PackProgramSource.Prepared>();
        for (String layer : List.of("solid", "cutout_mipped", "cutout", "translucent", "tripwire")) programs.put(layer, terrain);
        var casters = pack.properties().shadowCasters(defines);
        PackProgramSource.Prepared entities = null, blocks = null;
        if (casters.entities() || casters.player())
            entities = caster(pack, prefix, "shadow_entities", ceiling, outputs, customUniforms, distant, alpha, textures, warning);
        if (casters.blockEntities() || casters.lightBlockEntities())
            blocks = caster(pack, prefix, "shadow_block", ceiling, outputs, customUniforms, distant, alpha, textures, warning);
        return new ShadowPackPlan(programs, outputs, entities, blocks, casters);
    }

    private static List<Integer> outputs(OpenedPack pack, String path, int ceiling) throws IOException {
        var fragment = pack.source().file(path + ".fsh").orElseThrow(() -> new IOException("Missing shadow fragment " + path));
        return DrawBuffers.shadowColours(DrawBuffers.parse(new IncludeExpander(pack.source(), pack.settings()).expand(fragment)), ceiling);
    }

    private static PackProgramSource.Prepared caster(OpenedPack pack, String prefix, String program, int ceiling,
            List<Integer> sharedOutputs, Set<String> customUniforms, boolean distant, Map<String, AlphaTest> alpha,
            PackTextures textures, Consumer<String> warning) {
        String name = pack.source().file(prefix + program + ".vsh").isPresent() ? program : "shadow";
        String path = prefix + name;
        try {
            var inputs = VertexInputs.VULKAN_ENTITY;
            var source = PackProgramSource.prepare(pack, path, inputs, inputs.elements(), alpha.getOrDefault(name, AlphaTest.ONE_TENTH));
            if (!outputs(pack, path, ceiling).equals(sharedOutputs))
                throw new IllegalArgumentException("Shadow output slots differ from the shared map");
            if (!source.compiled().stages().keySet().equals(Set.of(ProgramStage.VERTEX, ProgramStage.FRAGMENT))
                    || source.translated().stages().values().stream().anyMatch(stage -> !stage.notes().storageBlocks().isEmpty()))
                throw new IllegalArgumentException("Unsupported caster shader stages or storage");
            var missing = WorldUniforms.missing(source, customUniforms, distant);
            if (!missing.isEmpty()) throw new IllegalArgumentException("Missing caster uniforms: " + missing);
            var active = new java.util.HashSet<Integer>();
            source.compiled().stages().values().forEach(bytes -> active.addAll(SpirvInterface.bindings(bytes)));
            for (var sampler : source.translated().samplers()) {
                if (!active.contains(source.compiled().bindings().get(sampler.name()))) continue;
                boolean supplied = textures.resolve(TextureStage.GBUFFERS, sampler.name())
                        .filter(texture -> texture.raw().isEmpty()).isPresent();
                boolean builtin = Set.of("tex", "texture", "gtexture", "lightmap", "ofOverlay", "noisetex").contains(sampler.name());
                if (!sampler.type().equals("sampler2D") || sampler.declaration().contains("[") || !(supplied || builtin))
                    throw new IllegalArgumentException("Unsupported caster texture: " + sampler.declaration());
            }
            return source;
        } catch (IOException | IllegalArgumentException | VulkanProgramCompiler.CompilationException failure) {
            warning.accept("Skipping native shadow geometry for " + program + ": " + failure.getMessage());
            return null;
        }
    }
}
