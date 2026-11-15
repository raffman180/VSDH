package de.stranger.vsdh.shader;

import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.model.TextureStage;
import dev.vitrail.pack.source.ShadowCasters;
import dev.vitrail.pack.source.ShadowCullState;
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

/**
 * The pack's actual shadow programs and output slots, plus how the light pass may cull terrain.
 * {@code voxelizes} mirrors Iris: a geometry stage on the shadow program means voxelisation.
 */
public record ShadowPackPlan(Map<String, PackProgramSource.Prepared> programs, List<Integer> outputs,
                             PackProgramSource.Prepared entities, PackProgramSource.Prepared blocks, ShadowCasters casters,
                             ShadowCullState cull, boolean voxelizes) {
    public ShadowPackPlan {
        programs = Map.copyOf(programs); outputs = List.copyOf(outputs);
        java.util.Objects.requireNonNull(cull, "cull");
    }

    /** Read-ahead of the programs {@link #prepare} will ask for (same names, same arguments). */
    static void prefetch(OpenedPack pack, String prefix) {
        String path = prefix + "shadow";
        if (pack.source().file(path + ".vsh").isEmpty()) return;
        var defines = pack.settings().globalDefines(pack.options());
        var alpha = pack.properties().alphaTests(defines);
        PackProgramSource.prefetch(pack, path, PackProgramSource.terrainInputs(pack), PackProgramSource.terrainInputs(pack).elements(),
                alpha.getOrDefault("shadow", AlphaTest.ONE_TENTH));
        var casters = pack.properties().shadowCasters(defines);
        var programs = new java.util.ArrayList<String>();
        if (casters.entities() || casters.player()) programs.add("shadow_entities");
        if (casters.blockEntities() || casters.lightBlockEntities()) programs.add("shadow_block");
        for (String program : programs) {
            String name = pack.source().file(prefix + program + ".vsh").isPresent() ? program : "shadow";
            PackProgramSource.prefetch(pack, prefix + name, VertexInputs.VULKAN_ENTITY, VertexInputs.VULKAN_ENTITY.elements(),
                    alpha.getOrDefault(name, AlphaTest.ONE_TENTH));
        }
    }

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
        var cull = pack.properties().shadowCull(defines);
        boolean voxelizes = pack.source().file(path + ".gsh").isPresent();
        return new ShadowPackPlan(programs, outputs, entities, blocks, casters, cull, voxelizes);
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
            if (!PackProgramSource.graphicsStages(source.compiled().stages().keySet()))
                throw new IllegalArgumentException("Unsupported caster shader stages");
            // Unprovided uniforms read as zero, as WorldUniforms writes them for every program of the plan.
            var active = new java.util.HashSet<Integer>();
            source.compiled().stages().values().forEach(bytes -> active.addAll(SpirvInterface.bindings(bytes)));
            for (var sampler : source.translated().samplers()) {
                if (!active.contains(source.compiled().bindings().get(sampler.name()))) continue;
                boolean supplied = textures.resolve(TextureStage.GBUFFERS, sampler.name())
                        .filter(texture -> texture.raw().isEmpty()).isPresent();
                boolean builtin = Set.of("tex", "texture", "ofTexture", "gtexture", "lightmap", "ofOverlay", "noisetex").contains(sampler.name())
                        || PackTexturePlan.hasMaterialDefault(sampler.name())
                        // A shadow target this pass does not write (NativeWorldShadows.readableColour).
                        || sampler.name().matches("shadowcolor[0-7]?") && !sharedOutputs.contains(
                                sampler.name().equals("shadowcolor") ? 0 : Integer.parseInt(sampler.name().substring(11)));
                if (dev.vitrail.pack.texture.CustomImages.named(sampler.name())) continue; // bound from the graph's images
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
