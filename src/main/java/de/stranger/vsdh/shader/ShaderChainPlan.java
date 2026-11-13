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
                              List<Integer> copyBack, Set<String> externalSamplers, List<Compute> setup,
                              dev.vitrail.pack.model.BufferObject.Reading buffers,
                              dev.vitrail.pack.model.ImageInformation.Reading images,
                              List<Compute> shadowComputes) {
    public enum Stage { BEGIN, PREPARE, DEFERRED, COMPOSITE, FINAL }
    public record Attachment(int target, TargetSchedule.Side side) {}
    /**
     * One {@code .csh} of a pass. It samples {@code colortexN} and stores into {@code colorimgN} on
     * the same half, the one the pass it hangs off reads (Iris CompositeRenderer binds both from
     * one snapshot), and runs before that pass draws.
     */
    /** {@code indirect}: Iris's indirect.<name> pointer, or null for a dispatch sized by the directives. */
    public record Compute(String name, PackProgramSource.PreparedCompute program, List<SamplerPlan.Binding> inputs,
                          Map<String, Attachment> images, Map<String, Integer> shadowImages,
                          dev.vitrail.pack.source.ShaderProperties.IndirectPointer indirect) {
        public Compute {
            inputs = List.copyOf(inputs);
            images = Map.copyOf(images);
            shadowImages = Map.copyOf(shadowImages);
        }
        public Compute(String name, PackProgramSource.PreparedCompute program, List<SamplerPlan.Binding> inputs,
                       Map<String, Attachment> images, Map<String, Integer> shadowImages) {
            this(name, program, inputs, images, shadowImages, null);
        }
        public Compute(String name, PackProgramSource.PreparedCompute program, List<SamplerPlan.Binding> inputs,
                       Map<String, Attachment> images) {
            this(name, program, inputs, images, Map.of(), null);
        }
    }

    /** Shadow colour targets some compute stores into as shadowcolorimgN (they need storage usage). */
    public java.util.Set<Integer> shadowStorage() {
        var indices = new java.util.TreeSet<Integer>();
        shadowComputes.forEach(compute -> indices.addAll(compute.shadowImages().values()));
        stages.values().forEach(passes -> passes.forEach(pass -> pass.computes()
                .forEach(compute -> indices.addAll(compute.shadowImages().values()))));
        return indices;
    }

    /** A drawn pass, or with a null program a moment that only runs computes (Iris "passing" programs). */
    public record Pass(String name, PackProgramSource.Prepared program, List<Attachment> outputs,
                       List<SamplerPlan.Binding> inputs, Set<Integer> mipmaps, List<Compute> computes,
                       Map<String, Attachment> images) {
        public Pass {
            outputs = List.copyOf(outputs);
            inputs = List.copyOf(inputs);
            mipmaps = Set.copyOf(mipmaps);
            computes = List.copyOf(computes);
            images = Map.copyOf(images);
        }
        public Pass(String name, PackProgramSource.Prepared program, List<Attachment> outputs,
                    List<SamplerPlan.Binding> inputs, Set<Integer> mipmaps) {
            this(name, program, outputs, inputs, mipmaps, List.of(), Map.of());
        }
        public boolean draws() { return program != null; }
    }

    public ShaderChainPlan {
        var immutable = new EnumMap<Stage, List<Pass>>(Stage.class);
        for (Stage stage : Stage.values()) immutable.put(stage, List.copyOf(stages.getOrDefault(stage, List.of())));
        stages = Map.copyOf(immutable);
        copyBack = List.copyOf(copyBack);
        externalSamplers = Set.copyOf(externalSamplers);
        setup = List.copyOf(setup);
        shadowComputes = List.copyOf(shadowComputes);
    }

    public ShaderChainPlan(TargetPlan targets, Map<Stage, List<Pass>> stages, List<Integer> copyBack, Set<String> externalSamplers) {
        this(targets, stages, copyBack, externalSamplers, List.of(), dev.vitrail.pack.model.BufferObject.Reading.empty(),
                dev.vitrail.pack.model.ImageInformation.Reading.empty(), List.of());
    }

    public static ShaderChainPlan prepare(OpenedPack pack, String dimension) throws IOException {
        TargetPlan targets = TargetPlan.build(pack.source(), pack.options(), pack.settings(), pack.properties(), dimension);
        if (!targets.unreadable().isEmpty()) throw new IOException("Unreadable shader programs: " + targets.unreadable());
        var computeNames = new java.util.TreeMap<String, List<String>>();
        for (String compute : targets.computes()) {
            String host = hostOf(compute);
            String family = ProgramNames.familyOf(TargetName.bareName(host));
            if (!List.of("setup", "shadowcomp", "begin", "prepare", "deferred", "composite", "final").contains(family))
                throw new IOException("Native " + family + " compute passes are not implemented: " + compute);
            computeNames.computeIfAbsent(host, ignored -> new ArrayList<>()).add(compute);
        }
        var unsupported = pack.properties().requiredFeatures().stream()
                .filter(feature -> !dev.vitrail.pack.option.EngineDefines.FEATURES.contains(feature.toUpperCase(java.util.Locale.ROOT))).toList();
        if (!unsupported.isEmpty())
            throw new IOException("Required native pack features need an implementation: " + unsupported);
        var stages = new EnumMap<Stage, List<Pass>>(Stage.class);
        Set<String> external = new HashSet<>();
        var textures=dev.vitrail.pack.texture.PackTextures.read(pack.properties(),pack.settings().globalDefines(pack.options()),pack.source());
        for (Stage stage : Stage.values()) stages.put(stage, new ArrayList<>());
        var order = new ArrayList<String>(targets.running());
        for (String passing : targets.passing()) if (!order.contains(passing)) order.add(passing);
        order.removeIf(name -> List.of("setup", "shadowcomp").contains(ProgramNames.familyOf(TargetName.bareName(name))));
        order.sort(ProgramNames.frameOrder());
        var drawn = Set.copyOf(targets.running());
        for (String name : order) {
            Stage stage = stageOf(name);
            var computes = computes(pack, targets, textures, computeNames.getOrDefault(TargetName.bareName(name), List.of()), name);
            if (!drawn.contains(name)) {
                if (computes.isEmpty()) throw new IOException("Passing program without computes: " + name);
                stages.get(stage).add(new Pass(name, null, List.of(), List.of(), Set.of(), computes, Map.of()));
                continue;
            }
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
            var supplied = new HashSet<>(textures.suppliedTo(textureStage));
            types.forEach((sampler, type) -> {
                if (type.equals("sampler2D") && PackTexturePlan.hasMaterialDefault(sampler)) supplied.add(sampler);
            });
            var samplers = SamplerPlan.of(List.copyOf(types.keySet()), types, targets, name,
                    supplied,textures.picturesTo(textureStage));
            var colourImages = new LinkedHashMap<String, Attachment>();
            for (var input : samplers.bindings()) {
                if (input.kind() == SamplerPlan.Kind.CUSTOM_IMAGE) continue; // bound from the graph's custom images
                if (input.kind() == SamplerPlan.Kind.COLOUR_IMAGE) {
                    // A fragment stage storing into colorimgN does so on the half the pass reads (Iris).
                    int index = Integer.parseInt(input.sampler().substring("colorimg".length()));
                    if (outputs.stream().anyMatch(output -> output.target() == index && output.side() == step.read(index)))
                        throw new IOException("Pass stores into the colour target it draws: " + name + "/" + input.sampler());
                    colourImages.put(input.sampler(), new Attachment(index, step.read(index)));
                    continue;
                }
                if (input.kind() == SamplerPlan.Kind.UNBINDABLE || input.kind() == SamplerPlan.Kind.UNSERVED)
                    throw new IOException("Unimplemented shader input " + name + "/" + input.sampler() + ": " + input.kind());
                if (input.kind() == SamplerPlan.Kind.COLORTEX) {
                    var format = targets.directives().format(input.index()).used();
                    String type = types.get(input.sampler());
                    if (!VulkanTargetFormat.acceptsSampler(VulkanTargetFormat.of(format), type))
                        throw new IOException("Incompatible shader input " + name + "/" + input.sampler() + ": "
                                + type + " cannot sample " + format);
                } else if (external(input)) {
                    external.add(input.sampler());
                }
            }
            Set<Integer> mipmaps = targets.directives().mipmapRequests().getOrDefault(name, Set.of());
            stages.get(stage).add(new Pass(name, program, outputs, samplers.bindings(), mipmaps, computes, colourImages));
        }
        for (var passes : stages.values()) for (var pass : passes) for (var compute : pass.computes())
            for (var input : compute.inputs()) if (external(input)) external.add(input.sampler());
        // Iris runs setup once when the pipeline is built, before any frame: every target on MAIN.
        var setup = computes(pack, targets, textures, computeNames.getOrDefault("setup", List.of()), "setup",
                java.util.Optional.of(new TargetSchedule.Bound("setup", List.of(), true, Set.of(), Set.of())));
        for (var compute : setup)
            for (var input : compute.inputs()) if (external(input)) external.add(input.sampler());
        var copyBack = targets.schedule().flippedAtEnd().stream().filter(targets.persistent()::contains).sorted().toList();
        var buffers = pack.properties().bufferObjects(pack.settings().globalDefines(pack.options()));
        for (String dropped : buffers.dropped())
            org.slf4j.LoggerFactory.getLogger("VSDH").warn("Unreadable storage buffer directive: {}", dropped);
        var images = pack.properties().imageDirectives(pack.settings().globalDefines(pack.options()));
        for (String dropped : images.dropped())
            org.slf4j.LoggerFactory.getLogger("VSDH").warn("Unreadable image directive: {}", dropped);
        // Iris runs shadowcomp right after the shadow pass, before prepare; they read shadow
        // textures and the pack's images, and no colour target half has been chosen yet.
        var shadowHosts = new ArrayList<String>();
        for (String host : computeNames.keySet())
            if (ProgramNames.familyOf(TargetName.bareName(host)).equals("shadowcomp")) shadowHosts.add(host);
        shadowHosts.sort(ProgramNames.frameOrder());
        var shadowComputes = new ArrayList<Compute>();
        for (String host : shadowHosts)
            shadowComputes.addAll(computes(pack, targets, textures, computeNames.get(host), host,
                    java.util.Optional.of(new TargetSchedule.Bound(host, List.of(), true, Set.of(), Set.of()))));
        for (var compute : shadowComputes)
            for (var input : compute.inputs()) if (external(input)) external.add(input.sampler());
        return new ShaderChainPlan(targets, stages, copyBack, external, setup, buffers, images, shadowComputes);
    }

    /** Inputs resolved outside the chain at draw time; colour targets and custom images are not. */
    public static boolean external(SamplerPlan.Binding input) {
        return input.kind() != SamplerPlan.Kind.COLORTEX && input.kind() != SamplerPlan.Kind.CUSTOM_IMAGE
                && input.kind() != SamplerPlan.Kind.COLOUR_IMAGE;
    }

    /** {@code deferred4_a} hangs off {@code deferred4}; {@code deferred4} (a .csh) off itself. */
    static String hostOf(String compute) {
        return compute.matches(".*_[a-z]") ? compute.substring(0, compute.length() - 2) : compute;
    }

    private static final java.util.regex.Pattern COLOUR_IMAGE = java.util.regex.Pattern.compile("colorimg(\\d+)");
    /** Iris: shadowcolorimgN is shadow colour target N as a storage image (Rethinking Voxels' interactive water). */
    private static final java.util.regex.Pattern SHADOW_COLOUR_IMAGE = java.util.regex.Pattern.compile("shadowcolorimg(\\d+)");

    private static List<Compute> computes(OpenedPack pack, TargetPlan targets, dev.vitrail.pack.texture.PackTextures textures,
                                          List<String> names, String host) throws IOException {
        return computes(pack, targets, textures, names, host, targets.schedule().step(host).or(() -> targets.schedule().passing(host)));
    }

    private static List<Compute> computes(OpenedPack pack, TargetPlan targets, dev.vitrail.pack.texture.PackTextures textures,
                                          List<String> names, String host, java.util.Optional<TargetSchedule.Bound> bound) throws IOException {
        if (names.isEmpty()) return List.of();
        var step = bound.orElseThrow(() -> new IOException("Missing buffer schedule for computes of " + host));
        var sorted = new ArrayList<>(names);
        // Iris: <name>.csh first, then <name>_a ... <name>_z.
        sorted.sort(java.util.Comparator.naturalOrder());
        var result = new ArrayList<Compute>();
        var defines = pack.settings().globalDefines(pack.options());
        var indirect = pack.properties().indirectDispatches(defines);
        var declared = pack.properties().bufferObjects(defines);
        for (String name : sorted) {
            String path = targets.place().isEmpty() ? name : targets.place() + "/" + name;
            var program = PackProgramSource.prepareCompute(pack, path);
            var active = new HashSet<Integer>();
            program.compiled().stages().values().forEach(bytes -> active.addAll(SpirvInterface.bindings(bytes)));
            var types = new LinkedHashMap<String, String>();
            var images = new LinkedHashMap<String, Attachment>();
            var shadowImages = new LinkedHashMap<String, Integer>();
            for (var sampler : program.translated().samplers()) {
                if (!active.contains(program.compiled().bindings().get(sampler.name()))) continue;
                if (sampler.type().matches("[iu]?image\\w+")) {
                    var shadowColour = SHADOW_COLOUR_IMAGE.matcher(sampler.name());
                    if (shadowColour.matches()) {
                        if (!sampler.type().matches("[iu]?image2D") || sampler.declaration().contains("["))
                            throw new IOException("Unsupported shadow colour image " + name + "/" + sampler.declaration());
                        shadowImages.put(sampler.name(), Integer.parseInt(shadowColour.group(1)));
                        continue;
                    }
                    var colour = COLOUR_IMAGE.matcher(sampler.name());
                    if (!colour.matches()) {
                        if (!dev.vitrail.pack.texture.CustomImages.named(sampler.name()))
                            throw new IOException("Image without an image directive: " + name + "/" + sampler.name());
                        continue; // bound from the graph's custom images
                    }
                    if (!sampler.type().matches("[iu]?image2D") || sampler.declaration().contains("["))
                        throw new IOException("Unsupported colour image " + name + "/" + sampler.declaration());
                    int index = Integer.parseInt(colour.group(1));
                    images.put(sampler.name(), new Attachment(index, step.read(index)));
                } else {
                    types.put(sampler.name(), sampler.type());
                }
            }
            var textureStage = dev.vitrail.pack.model.TextureStage.of(host).orElseThrow();
            var samplers = SamplerPlan.of(List.copyOf(types.keySet()), types, targets, java.util.Optional.of(step),
                    textures.suppliedTo(textureStage));
            for (var input : samplers.bindings()) {
                if (input.kind() == SamplerPlan.Kind.CUSTOM_IMAGE) continue;
                if (input.kind() == SamplerPlan.Kind.UNBINDABLE || input.kind() == SamplerPlan.Kind.UNSERVED
                        || input.kind() == SamplerPlan.Kind.COLOUR_IMAGE)
                    throw new IOException("Unimplemented compute input " + name + "/" + input.sampler() + ": " + input.kind());
                if (input.kind() == SamplerPlan.Kind.COLORTEX) {
                    var format = targets.directives().format(input.index()).used();
                    if (!VulkanTargetFormat.acceptsSampler(VulkanTargetFormat.of(format), types.get(input.sampler())))
                        throw new IOException("Incompatible compute input " + name + "/" + input.sampler() + ": "
                                + types.get(input.sampler()) + " cannot sample " + format);
                }
            }
            var pointer = indirect.get(name);
            if (pointer != null && !declared.hasIndex(pointer.buffer()))
                throw new IOException("indirect." + name + " names bufferObject." + pointer.buffer() + ", which the pack does not declare");
            result.add(new Compute(name, program, samplers.bindings(), images, shadowImages, pointer));
        }
        return result;
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
