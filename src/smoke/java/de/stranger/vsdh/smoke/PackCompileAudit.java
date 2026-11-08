package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.VulkanProgramCompiler;
import de.stranger.vsdh.shader.Std140Layout;
import dev.vitrail.glsl.ProgramTranslator;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.program.ProgramSet;
import dev.vitrail.pack.source.DimensionSet;
import dev.vitrail.pack.source.IncludeExpander;
import dev.vitrail.pack.source.OpenedPack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runs without a GPU. Pack source and translated source remain outside distributable artifacts. */
public final class PackCompileAudit {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Expected pack ZIP and report directory, optionally profile");
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        String profile = args.length > 2 ? args[2] : "MEDIUM";
        // Audit the intended DH contract. This is not a runtime capability announcement.
        EngineDefines.machine(new EngineDefines.Environment(12101, EngineDefines.Os.WINDOWS,
                "", "", 4, true, Map.of(), java.util.List.of()));
        int compiled = 0, failed = 0, disabled = 0;
        StringBuilder report = new StringBuilder("Vulkan compilation audit; profile=" + profile + "\n");
        try (OpenedPack pack = OpenedPack.open(Path.of(args[0]), Map.of(), profile)) {
            var dimensions = DimensionSet.discover(pack.source());
            var set = ProgramSet.enumerate(pack.source(), dimensions);
            var switchedOff = pack.properties().switchedOff(pack.settings().globalDefines(pack.options()), pack.options());
            Map<String, EnumMap<ProgramStage, String>> programs = new LinkedHashMap<>();
            for (var key : set.keys()) {
                String path = key.file().substring(0, key.file().lastIndexOf('.'));
                if (switchedOff.contains(path)) { disabled++; continue; }
                programs.computeIfAbsent(path, ignored -> new EnumMap<>(ProgramStage.class)).put(key.stage(), key.file());
            }
            var expander = new IncludeExpander(pack.source(), pack.settings());
            for (var program : programs.entrySet()) {
                String name = program.getKey();
                String bare = name.substring(name.lastIndexOf('/') + 1);
                boolean fullscreen = bare.matches("(?:begin|prepare|deferred|composite|shadowcomp)\\d*|final");
                try {
                    Map<ProgramStage, IncludeExpander.ExpandedUnit> units = new EnumMap<>(ProgramStage.class);
                    for (var entry : program.getValue().entrySet())
                        units.put(entry.getKey(), expander.expand(pack.source().file(entry.getValue()).orElseThrow()));
                    var inputs = bare.equals("gbuffers_line") ? VertexInputs.LINES
                            : fullscreen ? VertexInputs.FULLSCREEN : VertexInputs.WORLD;
                    var translated = ProgramTranslator.translate(units, inputs, bare);
                    var layout = new Std140Layout(translated.uniforms());
                    var result = VulkanProgramCompiler.compile(name, translated);
                    for (var stage : result.sources().entrySet()) {
                        Path source = output.resolve(name + "." + stage.getKey().extension());
                        Files.createDirectories(source.getParent());
                        Files.writeString(source, stage.getValue());
                        Files.write(output.resolve(name + "." + stage.getKey().extension() + ".spv"), result.stages().get(stage.getKey()));
                    }
                    report.append("PASS ").append(name).append(" stages=").append(result.stages().size())
                            .append(" uniformBytes=").append(layout.size())
                            .append(" storageBlocks=").append(translated.stages().values().stream()
                                    .mapToInt(unit -> unit.notes().storageBlocks().size()).sum()).append('\n');
                    compiled++;
                } catch (Exception failure) {
                    report.append("FAIL ").append(name).append(": ").append(failure.getMessage()).append('\n');
                    if (failure instanceof VulkanProgramCompiler.CompilationException compilation) {
                        Path source = output.resolve(name + ".failed." + compilation.stage.extension());
                        Files.createDirectories(source.getParent());
                        Files.writeString(source, compilation.source);
                    }
                    failed++;
                }
            }
        }
        report.append("SUMMARY programs=").append(compiled + failed).append(" compiled=").append(compiled)
                .append(" failed=").append(failed).append(" disabledStages=").append(disabled).append('\n');
        Files.writeString(output.resolve("report.txt"), report);
        System.out.println(report);
        if (failed != 0) throw new IllegalStateException(failed + " shader programs did not compile; see " + output);
    }
}
