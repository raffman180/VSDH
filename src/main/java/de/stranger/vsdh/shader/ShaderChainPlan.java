package de.stranger.vsdh.shader;

import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramNames;
import dev.vitrail.pack.model.TargetName;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSchedule;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** CPU preparation only. Nothing here retains archive paths or allocates Vulkan objects. */
public record ShaderChainPlan(TargetPlan targets, Map<Stage, List<Pass>> stages,
                              List<Integer> copyBack, Set<String> externalSamplers) {
    public enum Stage { BEGIN, PREPARE, DEFERRED, COMPOSITE, FINAL }
    public record Attachment(int target, TargetSchedule.Side side) {}
    public record Pass(String name, PackProgramSource.Prepared program, List<Attachment> outputs,
                       List<SamplerPlan.Binding> inputs, Set<Integer> mipmaps) {
        public Pass {
            outputs = List.copyOf(outputs);
            inputs = List.copyOf(inputs);
            mipmaps = Set.copyOf(mipmaps);
        }
    }

    public ShaderChainPlan {
        var immutable = new EnumMap<Stage, List<Pass>>(Stage.class);
        for (Stage stage : Stage.values()) immutable.put(stage, List.copyOf(stages.getOrDefault(stage, List.of())));
        stages = Map.copyOf(immutable);
        copyBack = List.copyOf(copyBack);
        externalSamplers = Set.copyOf(externalSamplers);
    }

    public static ShaderChainPlan prepare(OpenedPack pack, String dimension) throws IOException {
        TargetPlan targets = TargetPlan.build(pack.source(), pack.options(), pack.settings(), pack.properties(), dimension);
        if (!targets.unreadable().isEmpty()) throw new IOException("Unreadable shader programs: " + targets.unreadable());
        if (!targets.computes().isEmpty()) throw new IOException("Native compute passes are not implemented: " + targets.computes());
        if (!pack.properties().requiredFeatures().isEmpty())
            throw new IOException("Required native pack features need an implementation: " + pack.properties().requiredFeatures());
        var stages = new EnumMap<Stage, List<Pass>>(Stage.class);
        Set<String> external = new HashSet<>();
        var textures=dev.vitrail.pack.texture.PackTextures.read(pack.properties(),pack.settings().globalDefines(pack.options()),pack.source());
        for (Stage stage : Stage.values()) stages.put(stage, new ArrayList<>());
        for (String name : targets.running()) {
            Stage stage = stageOf(name);
            String path = targets.place().isEmpty() ? name : targets.place() + "/" + name;
            var program = PackProgramSource.prepare(pack, path, VertexInputs.FULLSCREEN);
            var step = targets.schedule().step(name).orElseThrow(() -> new IOException("Missing buffer schedule for " + name));
            List<Integer> writes = stage == Stage.FINAL ? List.of() : targets.writes(name);
            if (stage != Stage.FINAL && (writes.isEmpty() || writes.size() > 8 || new HashSet<>(writes).size() != writes.size()))
                throw new IOException("Invalid draw buffer list for " + name + ": " + writes);
            List<Attachment> outputs = writes.stream().map(index -> new Attachment(index, step.write(index))).toList();
            var active = new HashSet<Integer>();
            program.compiled().stages().values().forEach(bytes -> active.addAll(SpirvInterface.bindings(bytes)));
            var types = new LinkedHashMap<String, String>();
            for (var sampler : program.translated().samplers())
                if (active.contains(program.compiled().bindings().get(sampler.name()))) types.put(sampler.name(), sampler.type());
            var textureStage=dev.vitrail.pack.model.TextureStage.of(name).orElseThrow();
            var samplers = SamplerPlan.of(List.copyOf(types.keySet()), types, targets, name,
                    textures.suppliedTo(textureStage),textures.picturesTo(textureStage));
            for (var input : samplers.bindings()) {
                if (input.kind() == SamplerPlan.Kind.UNBINDABLE || input.kind() == SamplerPlan.Kind.UNSERVED
                        || input.kind() == SamplerPlan.Kind.CUSTOM_IMAGE || input.kind() == SamplerPlan.Kind.COLOUR_IMAGE)
                    throw new IOException("Unimplemented shader input " + name + "/" + input.sampler() + ": " + input.kind());
                if (input.kind() == SamplerPlan.Kind.COLORTEX) {
                    var format = targets.directives().format(input.index()).used();
                    String type = types.get(input.sampler());
                    if (!VulkanTargetFormat.acceptsSampler(VulkanTargetFormat.of(format), type))
                        throw new IOException("Incompatible shader input " + name + "/" + input.sampler() + ": "
                                + type + " cannot sample " + format);
                } else {
                    external.add(input.sampler());
                }
            }
            Set<Integer> mipmaps = targets.directives().mipmapRequests().getOrDefault(name, Set.of());
            stages.get(stage).add(new Pass(name, program, outputs, samplers.bindings(), mipmaps));
        }
        var copyBack = targets.schedule().flippedAtEnd().stream().filter(targets.persistent()::contains).sorted().toList();
        return new ShaderChainPlan(targets, stages, copyBack, external);
    }

    public static Stage stageOf(String name) {
        return switch (ProgramNames.familyOf(TargetName.bareName(name))) {
            case "begin" -> Stage.BEGIN;
            case "prepare" -> Stage.PREPARE;
            case "deferred" -> Stage.DEFERRED;
            case "composite" -> Stage.COMPOSITE;
            case "final" -> Stage.FINAL;
            default -> throw new IllegalArgumentException("Not a world fullscreen stage: " + name);
        };
    }
}
