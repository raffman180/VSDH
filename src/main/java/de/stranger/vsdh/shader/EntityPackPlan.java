package de.stranger.vsdh.shader;

import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.model.TextureStage;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.PropertiesFile;
import dev.vitrail.pack.texture.PackTextures;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Prepared programs for supported entity mesh layers. */
public record EntityPackPlan(PackProgramSource.Prepared solid, PackProgramSource.Prepared cutout,
                             Map<String, Integer> materials, BlockPrograms blocks,
                             PackProgramSource.Prepared translucent, boolean lateTransparency) {
    private static final String NAME = "gbuffers_entities";
    private static final String BLOCK = "gbuffers_block";
    private static final String TRANSLUCENT = "gbuffers_entities_translucent";
    private static final Pattern ENTRY = Pattern.compile("^\\s*entity\\.(-?\\d+)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Set<String> DRAW_IMAGES = Set.of("tex", "texture", "gtexture", "lightmap", "ofOverlay", "noisetex");
    private static final Set<String> SHADOW_DEPTH = Set.of("shadow", "watershadow", "shadowtex0", "shadowtex1");
    private static final Set<String> SHADOW_COLOR = Set.of("shadowcolor", "shadowcolor0", "shadowcolor1");

    public EntityPackPlan { materials = Map.copyOf(materials); }

    public record BlockPrograms(PackProgramSource.Prepared solid, PackProgramSource.Prepared cutout) {}

    static EntityPackPlan prepare(OpenedPack pack, String prefix, ShaderChainPlan chain,
                                  Set<String> customUniforms, boolean shadows, boolean distant, Consumer<String> warning) {
        if (chain.targets().schedule().step(NAME).isEmpty()
                || pack.source().file(prefix + NAME + ".vsh").isEmpty()
                || pack.source().file(prefix + NAME + ".fsh").isEmpty()) return null;
        try {
            var defines = pack.settings().globalDefines(pack.options());
            var alphas = pack.properties().alphaTests(defines);
            var inputs = VertexInputs.VULKAN_ENTITY;
            var solid = PackProgramSource.prepare(pack, prefix + NAME, inputs, inputs.elements(),
                    alphas.getOrDefault(NAME, AlphaTest.OFF));
            var cutout = PackProgramSource.prepare(pack, prefix + NAME, inputs, inputs.elements(),
                    alphas.getOrDefault(NAME, AlphaTest.ONE_TENTH));
            var textures = PackTextures.read(pack.properties(), defines, pack.source());
            for (var source : List.of(solid, cutout)) {
                validate(source, customUniforms, shadows, distant, textures);
            }
            var ids = new HashMap<String, Integer>();
            PropertiesFile.read(pack.source(), "entity.properties").walk(defines, line -> {
                var entry = ENTRY.matcher(line);
                if (!entry.matches()) return;
                int id = Integer.parseInt(entry.group(1));
                for (String token : entry.group(2).split("\\s+")) {
                    if (token.isEmpty()) continue;
                    String name = token.contains(":") ? token : "minecraft:" + token;
                    if (RESOURCE_ID.matcher(name).matches()) ids.putIfAbsent(name, id);
                }
            });
            BlockPrograms blocks = null;
            if (chain.targets().schedule().step(BLOCK).isPresent()
                    && pack.source().file(prefix + BLOCK + ".vsh").isPresent()
                    && pack.source().file(prefix + BLOCK + ".fsh").isPresent()) {
                try {
                    var blockSolid = PackProgramSource.prepare(pack, prefix + BLOCK, inputs, inputs.elements(),
                            alphas.getOrDefault(BLOCK, AlphaTest.OFF));
                    var blockCutout = PackProgramSource.prepare(pack, prefix + BLOCK, inputs, inputs.elements(),
                            alphas.getOrDefault(BLOCK, AlphaTest.ONE_TENTH));
                    validate(blockSolid, customUniforms, shadows, distant, textures);
                    validate(blockCutout, customUniforms, shadows, distant, textures);
                    blocks = new BlockPrograms(blockSolid, blockCutout);
                } catch (IOException | IllegalArgumentException | VulkanProgramCompiler.CompilationException failure) {
                    warning.accept("Keeping standard block entity rendering for " + prefix + BLOCK + ": " + failure.getMessage());
                }
            }
            PackProgramSource.Prepared translucent = null;
            if (chain.targets().schedule().step(TRANSLUCENT).isPresent()
                    && pack.source().file(prefix + TRANSLUCENT + ".vsh").isPresent()
                    && pack.source().file(prefix + TRANSLUCENT + ".fsh").isPresent()) {
                try {
                    var source = PackProgramSource.prepare(pack, prefix + TRANSLUCENT, inputs, inputs.elements(),
                            alphas.getOrDefault(TRANSLUCENT, AlphaTest.NON_ZERO));
                    validate(source, customUniforms, shadows, distant, textures);
                    translucent = source;
                } catch (IOException | IllegalArgumentException | VulkanProgramCompiler.CompilationException failure) {
                    warning.accept("Keeping standard translucent entity rendering for " + prefix + TRANSLUCENT + ": " + failure.getMessage());
                }
            }
            return new EntityPackPlan(solid, cutout, ids, blocks, translucent, pack.properties().separateEntityDraws(defines));
        } catch (IOException | IllegalArgumentException | VulkanProgramCompiler.CompilationException failure) {
            warning.accept("Keeping standard opaque entity rendering for " + prefix + NAME + ": " + failure.getMessage());
            return null;
        }
    }

    private static void validate(PackProgramSource.Prepared source, Set<String> customUniforms, boolean shadows,
                                 boolean distant, PackTextures textures) {
        if (!source.compiled().stages().keySet().equals(Set.of(ProgramStage.VERTEX, ProgramStage.FRAGMENT))
                || source.translated().stages().values().stream().anyMatch(stage -> !stage.notes().storageBlocks().isEmpty()))
            throw new IllegalArgumentException("Unsupported entity shader stages or storage");
        var missing = WorldUniforms.missing(source, customUniforms, distant);
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing entity uniforms: " + missing);
        var active = new HashSet<Integer>();
        source.compiled().stages().values().forEach(bytes -> active.addAll(SpirvInterface.bindings(bytes)));
        for (var sampler : source.translated().samplers()) {
            String name = sampler.name();
            if (!active.contains(source.compiled().bindings().get(name))) continue;
            boolean supported = switch (sampler.type()) {
                case "sampler2D" -> DRAW_IMAGES.contains(name)
                        || shadows && (SHADOW_DEPTH.contains(name) || SHADOW_COLOR.contains(name));
                case "sampler2DShadow" -> shadows && SHADOW_DEPTH.contains(name);
                default -> false;
            };
            if (!supported || sampler.declaration().contains("[")
                    || textures.resolve(TextureStage.GBUFFERS, name).isPresent())
                throw new IllegalArgumentException("Unsupported entity texture: " + sampler.declaration());
        }
    }

    List<PackProgramSource.Prepared> programs() {
        var programs = new java.util.ArrayList<>(List.of(solid, cutout));
        if (blocks != null) {
            programs.add(blocks.solid());
            programs.add(blocks.cutout());
        }
        if (translucent != null) programs.add(translucent);
        return List.copyOf(programs);
    }
}
