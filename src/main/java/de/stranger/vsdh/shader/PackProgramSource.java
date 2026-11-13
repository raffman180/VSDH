package de.stranger.vsdh.shader;

import dev.vitrail.glsl.ProgramTranslator;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.IncludeExpander;
import dev.vitrail.pack.source.OpenedPack;

import java.io.IOException;
import java.util.EnumMap;

/** Resource-independent prepared source: no zip filesystem handles escape the caller's OpenedPack. */
public final class PackProgramSource {
    private PackProgramSource() {}
    public record Prepared(String path, ProgramTranslator.TranslatedProgram translated,
                           VulkanProgramCompiler.Compiled compiled) {}

    /** Vertex and fragment, with optional paired tessellation stages and an optional geometry stage. */
    public static boolean graphicsStages(java.util.Set<ProgramStage> stages) {
        return stages.contains(ProgramStage.VERTEX) && stages.contains(ProgramStage.FRAGMENT)
                && stages.contains(ProgramStage.TESSELLATION_CONTROL) == stages.contains(ProgramStage.TESSELLATION_EVALUATION)
                && !stages.contains(ProgramStage.COMPUTE);
    }

    /** One {@code .csh} entry point with the directives its dispatch is sized from. */
    public record PreparedCompute(String path, ProgramTranslator.TranslatedProgram translated,
                                  VulkanProgramCompiler.Compiled compiled, dev.vitrail.glsl.PackProgram.Dispatch dispatch) {
        /** The same program for code that reads only path, translation and bytecode (textures, uniforms). */
        public Prepared asProgram() { return new Prepared(path, translated, compiled); }

        /** Iris ComputeProgram.getWorkGroups: a fixed count, else the (scaled) render size over the local size. */
        public int[] groups(int width, int height) {
            if (dispatch.groupsX() >= 0) return new int[]{dispatch.groupsX(), dispatch.groupsY(), dispatch.groupsZ()};
            if (dispatch.localX() <= 0 || dispatch.localY() <= 0)
                throw new IllegalStateException("Compute " + path + " has no readable local_size for a derived dispatch");
            float scaleX = dispatch.renderX() >= 0 ? dispatch.renderX() : 1f, scaleY = dispatch.renderY() >= 0 ? dispatch.renderY() : 1f;
            return new int[]{(int) Math.ceil(width * scaleX / dispatch.localX()), (int) Math.ceil(height * scaleY / dispatch.localY()), 1};
        }
    }

    public static PreparedCompute prepareCompute(OpenedPack pack, String path) throws IOException {
        var file = pack.source().file(path + "." + ProgramStage.COMPUTE.extension())
                .orElseThrow(() -> new IOException("Missing compute program: " + path));
        var unit = new IncludeExpander(pack.source(), pack.settings()).expand(file);
        var units = new EnumMap<ProgramStage, IncludeExpander.ExpandedUnit>(ProgramStage.class);
        units.put(ProgramStage.COMPUTE, unit);
        var dispatch = dev.vitrail.glsl.PackProgram.dispatchOf(unit);
        if (dispatch.groupsX() < 0 && (dispatch.localX() <= 0 || dispatch.localY() <= 0))
            throw new IOException("Compute " + path + " has no readable work group size: " + dispatch.unresolved());
        String bare = path.substring(path.lastIndexOf('/') + 1);
        var translated = ProgramTranslator.translate(units, VertexInputs.FULLSCREEN, VertexInputs.FULLSCREEN.elements(),
                dev.vitrail.pack.model.AlphaTest.OFF, false, bare, volumes(pack));
        return new PreparedCompute(path, translated, VulkanProgramCompiler.compile(path, translated), dispatch);
    }

    public static Prepared prepareTerrain(OpenedPack pack, String path) throws IOException {
        return prepareTerrain(pack, path, dev.vitrail.pack.model.AlphaTest.OFF);
    }

    public static Prepared prepareTerrain(OpenedPack pack, String path, dev.vitrail.pack.model.AlphaTest alpha) throws IOException {
        var defines = pack.settings().globalDefines(pack.options());
        boolean separateAo = pack.properties().separateAo(defines), oldLighting = pack.properties().oldLighting(defines);
        var inputs = oldLighting ? (separateAo ? VertexInputs.VULKAN_TERRAIN_OLD_LIGHTING_SEPARATE_AO : VertexInputs.VULKAN_TERRAIN_OLD_LIGHTING)
                : separateAo ? VertexInputs.VULKAN_TERRAIN_SEPARATE_AO : VertexInputs.VULKAN_TERRAIN;
        return prepare(pack, path, inputs, alpha);
    }

    public static Prepared prepare(OpenedPack pack, String path, VertexInputs inputs) throws IOException {
        return prepare(pack, path, inputs, dev.vitrail.pack.model.AlphaTest.OFF);
    }

    private static Prepared prepare(OpenedPack pack, String path, VertexInputs inputs, dev.vitrail.pack.model.AlphaTest alpha) throws IOException {
        return prepare(pack, path, inputs, inputs.elements(), alpha);
    }

    static Prepared prepare(OpenedPack pack, String path, VertexInputs inputs, java.util.List<String> elements,
                            dev.vitrail.pack.model.AlphaTest alpha) throws IOException {
        var expander = new IncludeExpander(pack.source(), pack.settings());
        var units = new EnumMap<ProgramStage, IncludeExpander.ExpandedUnit>(ProgramStage.class);
        for (ProgramStage stage : ProgramStage.values()) {
            // A compute of the same name is its own program (deferred1.csh beside deferred1.fsh).
            if (stage == ProgramStage.COMPUTE) continue;
            var file = pack.source().file(path + "." + stage.extension());
            if (file.isPresent()) units.put(stage, expander.expand(file.get()));
        }
        if (!units.containsKey(ProgramStage.VERTEX) || !units.containsKey(ProgramStage.FRAGMENT))
            throw new IOException("Incomplete shader program: " + path);
        String bare = path.substring(path.lastIndexOf('/') + 1);
        var translated = ProgramTranslator.translate(units, inputs, elements, alpha, false, bare, volumes(pack));
        return new Prepared(path, translated, VulkanProgramCompiler.compile(path, translated));
    }

    /**
     * Volumes the pack ships, laid out flat. Every program declaring one is rewritten onto the same
     * atlas, which PackTexturePlan builds from the same first declaration.
     */
    public static java.util.Map<String, dev.vitrail.pack.texture.VolumeAtlas> volumes(OpenedPack pack) throws IOException {
        var defines = pack.settings().globalDefines(pack.options());
        return pack.source().derived(java.util.List.of(dev.vitrail.pack.texture.PackTextures.class, defines),
                () -> dev.vitrail.pack.texture.PackTextures.read(pack.properties(), defines, pack.source())).volumes();
    }
}
