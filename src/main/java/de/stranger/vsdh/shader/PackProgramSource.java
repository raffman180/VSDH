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

    public static Prepared prepare(OpenedPack pack, String path, VertexInputs inputs) throws IOException {
        var expander = new IncludeExpander(pack.source(), pack.settings());
        var units = new EnumMap<ProgramStage, IncludeExpander.ExpandedUnit>(ProgramStage.class);
        for (ProgramStage stage : ProgramStage.values()) {
            var file = pack.source().file(path + "." + stage.extension());
            if (file.isPresent()) units.put(stage, expander.expand(file.get()));
        }
        if (!units.containsKey(ProgramStage.VERTEX) || !units.containsKey(ProgramStage.FRAGMENT))
            throw new IOException("Incomplete shader program: " + path);
        String bare = path.substring(path.lastIndexOf('/') + 1);
        var translated = ProgramTranslator.translate(units, inputs, bare);
        return new Prepared(path, translated, VulkanProgramCompiler.compile(path, translated));
    }
}
