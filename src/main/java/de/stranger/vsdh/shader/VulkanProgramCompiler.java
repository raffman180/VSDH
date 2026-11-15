package de.stranger.vsdh.shader;

import dev.vitrail.glsl.ProgramTranslator.TranslatedProgram;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.glsl.VertexInputs;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** CPU-only compilation. No Vulkan device, Minecraft instance or render-thread state is used. */
public final class VulkanProgramCompiler {
    private VulkanProgramCompiler() {}

    public record Compiled(Map<ProgramStage, byte[]> stages, Map<String, Integer> bindings,
                           Map<ProgramStage, String> sources) {
        public Compiled {
            stages = Map.copyOf(stages);
            bindings = Map.copyOf(bindings);
            sources = Map.copyOf(sources);
        }
    }

    /** Bindings are shared across stages, including uniforms a particular stage does not read. */
    public static Compiled compile(String name, TranslatedProgram translated) {
        Map<String, Integer> bindings = bindings(translated);
        // The same program compiled before (also in an earlier session) is read back instead of compiled again.
        // A shader dump wants the compile step itself, so it goes around the cache.
        String key = null;
        if (DUMP == null && SpirvCache.active()) {
            long hashing = PrepareClock.start();
            var texts = new java.util.EnumMap<ProgramStage, String>(ProgramStage.class);
            translated.stages().forEach((stage, unit) -> texts.put(stage, unit.text()));
            key = SpirvCache.keyFor(name, translated.inputs() == VertexInputs.FULLSCREEN, bindings, texts);
            var kept = SpirvCache.lookup(key);
            PrepareClock.add("leaf.spirvCacheRead", hashing);
            if (kept != null) return new Compiled(kept.stages(), bindings, kept.sources());
        }
        if (key == null) return build(name, translated, bindings);
        // Two requests with one key (the same text under another alpha test or vertex format) compile once: the
        // second worker waits for the first instead of compiling the same program beside it.
        var mine = new java.util.concurrent.CompletableFuture<Compiled>();
        var other = BUILDING.putIfAbsent(key, mine);
        if (other != null) {
            try {
                Compiled same = other.join();
                PrepareClock.count("leaf.sharedCompile");
                return new Compiled(same.stages(), bindings, same.sources());
            } catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException failed) {
                return build(name, translated, bindings); // meets the same failure and reports it as its own
            }
        }
        try {
            Compiled built = build(name, translated, bindings);
            long storing = PrepareClock.start();
            SpirvCache.store(key, new SpirvCache.Entry(built.stages(), built.sources()));
            PrepareClock.add("leaf.spirvCacheWrite", storing);
            mine.complete(built);
            return built;
        } catch (RuntimeException | Error failure) {
            mine.completeExceptionally(failure);
            throw failure;
        } finally {
            BUILDING.remove(key, mine);
        }
    }

    /** Compilations under way, by cache key. */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Compiled>> BUILDING =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The descriptor binding of every resource of the program, shared by its stages. Stages are walked in the
     * enum's order: the translated stage map has no order of its own, and the numbers must not depend on it.
     */
    static Map<String, Integer> bindings(TranslatedProgram translated) {
        Map<String, Integer> bindings = new LinkedHashMap<>();
        if (!translated.uniforms().isEmpty()) bindings.put("OfGlobals", bindings.size());
        if (translated.stages().values().stream().anyMatch(unit ->
                unit.text().contains("uniform DynamicTransforms {")))
            bindings.put("DynamicTransforms", bindings.size());
        translated.samplers().forEach(sampler -> bindings.putIfAbsent(sampler.name(), bindings.size()));
        // Storage blocks keep their pack index (bufferObject.N) under a key no GLSL name can take;
        // the descriptor binding is ours, appended after the samplers like every other resource.
        for (ProgramStage stage : ProgramStage.values()) {
            var unit = translated.stages().get(stage);
            if (unit != null && unit.notes() != null) for (var block : unit.notes().storageBlocks())
                bindings.putIfAbsent(storageKey(Math.max(0, block.binding())), bindings.size());
        }
        return bindings;
    }

    /** One compiler and one options object per call: shaderc needs no synchronisation between different objects. */
    private static Compiled build(String name, TranslatedProgram translated, Map<String, Integer> bindings) {
        long clock = PrepareClock.start();
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0) throw new IllegalStateException("shaderc compiler initialization failed");
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            if (options == 0) throw new IllegalStateException("shaderc options initialization failed");
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                    Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
            // Measured on Bliss (11.11.2026): size and performance give the same GPU time; the driver
            // optimises again. Unoptimised SPIR-V is not supported (unused inputs keep locations busy).
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
            Map<ProgramStage, String> texts = new LinkedHashMap<>();
            for (ProgramStage stage : ProgramStage.values()) {
                var unit = translated.stages().get(stage);
                if (unit == null) continue;
                String source = bind(unit.text(), bindings);
                if (stage == ProgramStage.VERTEX && translated.inputs() == VertexInputs.FULLSCREEN) {
                    source = source.replace("in vec3 Position;", "#define Position vec3(UV0, 0.0)")
                            // Iris's full-screen QUAD (0..1, two triangles), not one oversized triangle: packs
                            // shrink gl_Position to draw a part of the screen (Bliss's half-resolution fog), and a
                            // shrunk triangle covers twice the area of a shrunk quad and overwrites the rest.
                            .replace("in vec2 UV0;", "#define of_QuadCorner (gl_VertexIndex < 3 ? gl_VertexIndex : (gl_VertexIndex == 3 ? 0 : gl_VertexIndex - 2))\n"
                                    + "#define UV0 vec2(float((of_QuadCorner + 1) / 2 == 1), float(of_QuadCorner >= 2))");
                }
                if (stage == ProgramStage.TESSELLATION_CONTROL) source = sizeControlOutputs(source);
                // The evaluation stage reads corner arrays and writes plain outputs of the same names.
                if (stage == ProgramStage.TESSELLATION_EVALUATION) source = SpirvInterface.renameGeometryOutputs(source);
                texts.put(stage, renameReservedMacros(source));
            }
            // A geometry stage that only hands each corner on is folded into the fragment stage
            // (dev.vitrail.glsl.GeometryFold): the same picture without a geometry pipeline. Any
            // other geometry stage is refused with the fold's reason.
            if (texts.containsKey(ProgramStage.GEOMETRY)) {
                if (!texts.containsKey(ProgramStage.FRAGMENT) || !texts.containsKey(ProgramStage.VERTEX))
                    throw new IllegalStateException("Geometry stage without vertex and fragment stages: " + name);
                String geometry = preprocess(compiler, options, texts.get(ProgramStage.GEOMETRY), name);
                var folded = dev.vitrail.glsl.GeometryFold.fold(geometry, texts.get(ProgramStage.FRAGMENT));
                if (folded.folded()) {
                    texts.remove(ProgramStage.GEOMETRY);
                    texts.put(ProgramStage.FRAGMENT, folded.fragment());
                } else {
                    // A real geometry stage: flattened in/out blocks of one name collide, so the
                    // outputs take a prefix the linker strips again when it meets the fragment stage.
                    texts.put(ProgramStage.GEOMETRY, SpirvInterface.renameGeometryOutputs(
                            forwardEngineVaryings(texts.get(ProgramStage.GEOMETRY))));
                }
            }
            dumpSources(name, texts);
            PrepareClock.add("leaf.bind", clock);
            boolean linkGraphics = texts.size() == 2
                    && texts.containsKey(ProgramStage.VERTEX)
                    && texts.containsKey(ProgramStage.FRAGMENT);
            // Any longer graphics chain (tessellation and/or geometry) is linked pair by pair.
            var chain = new java.util.ArrayList<ProgramStage>();
            for (ProgramStage stage : java.util.List.of(ProgramStage.VERTEX, ProgramStage.TESSELLATION_CONTROL,
                    ProgramStage.TESSELLATION_EVALUATION, ProgramStage.GEOMETRY, ProgramStage.FRAGMENT))
                if (texts.containsKey(stage)) chain.add(stage);
            boolean linkChain = !linkGraphics && chain.size() > 2 && chain.size() == texts.size()
                    && chain.getFirst() == ProgramStage.VERTEX && chain.getLast() == ProgramStage.FRAGMENT;
            if (linkGraphics || linkChain) Shaderc.shaderc_compile_options_set_generate_debug_info(options);
            Map<ProgramStage, byte[]> stages = new LinkedHashMap<>();
            Map<ProgramStage, String> sources = new LinkedHashMap<>();
            for (var entry : texts.entrySet()) {
                ProgramStage stage = entry.getKey();
                String source = entry.getValue();
                sources.put(stage, source);
                // The CharSequence overload copies the entire shader to LWJGL's 64 KiB stack.
                // Expanded shaderpacks routinely exceed that size; own this temporary allocation.
                ByteBuffer text = MemoryUtil.memUTF8(source, false);
                ByteBuffer filename = MemoryUtil.memUTF8(name + "." + stage.extension());
                ByteBuffer entrypoint = MemoryUtil.memUTF8("main");
                long result;
                long shaderc = PrepareClock.start();
                try {
                    result = Shaderc.shaderc_compile_into_spv(compiler, text, kind(stage), filename, entrypoint, options);
                } finally {
                    PrepareClock.add("leaf.shaderc", shaderc);
                    MemoryUtil.memFree(entrypoint);
                    MemoryUtil.memFree(filename);
                    MemoryUtil.memFree(text);
                }
                if (result == 0) throw new IllegalStateException("shaderc returned no result: " + name);
                try {
                    if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success)
                        throw new CompilationException(name, stage, source, Shaderc.shaderc_result_get_error_message(result));
                    ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                    if (bytes == null) throw new IllegalStateException("shaderc returned no bytecode: " + name);
                    byte[] owned = new byte[bytes.remaining()];
                    bytes.get(owned);
                    stages.put(stage, owned);
                } finally {
                    Shaderc.shaderc_result_release(result);
                }
            }
            clock = PrepareClock.start();
            if (linkGraphics) {
                SpirvInterface.linkGraphics(stages.get(ProgramStage.VERTEX), stages.get(ProgramStage.FRAGMENT),
                        sources.get(ProgramStage.FRAGMENT));
                stages.replaceAll((stage, bytes) -> SpirvInterface.withoutDebugSource(bytes));
            }
            if (linkChain) {
                for (int i = 1; i < chain.size(); i++)
                    SpirvInterface.linkStages(stages.get(chain.get(i - 1)), stages.get(chain.get(i)), sources.get(chain.get(i)));
                stages.replaceAll((stage, bytes) -> SpirvInterface.withoutDebugSource(bytes));
            }
            PrepareClock.add("leaf.link", clock);
            return new Compiled(stages, bindings, sources);
        } finally {
            if (options != 0) Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static final Pattern RESERVED_MACRO = Pattern.compile("(?m)^\\s*#\\s*define\\s+(GL_\\w+)");

    /**
     * glslang refuses to #define a name beginning with GL_, which GL drivers accept; Rethinking
     * Voxels defines GL_CAVE_FACTOR. Every such macro the source defines is renamed consistently.
     */
    static String renameReservedMacros(String source) {
        var matcher = RESERVED_MACRO.matcher(source);
        var names = new java.util.LinkedHashSet<String>();
        while (matcher.find()) names.add(matcher.group(1));
        for (String name : names)
            source = source.replaceAll("\\b" + Pattern.quote(name) + "\\b", "of" + name);
        return source;
    }

    private static final Pattern CONTROL_VERTICES = Pattern.compile("layout\\s*\\(\\s*vertices\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s*;");

    /**
     * The flattened output arrays of a tessellation control stage are lifted above the pack's
     * {@code layout(vertices = N) out;}, and glslang sizes an unsized output array only from a
     * layout it has already seen. They are given the size explicitly.
     */
    static String sizeControlOutputs(String source) {
        source = renameControlOutputs(source);
        var vertices = CONTROL_VERTICES.matcher(source);
        if (!vertices.find()) return source;
        return source.replaceAll("\\b(" + Pattern.quote(dev.vitrail.glsl.GlslTranslator.TESSELLATION_OUTPUT) + "\\w+)\\s*\\[\\s*\\]",
                "$1[" + vertices.group(1) + "]");
    }

    private static final Pattern CONTROL_INPUT = Pattern.compile("\\bin\\s+\\w+\\s+(of_\\w+)\\s*\\[");
    private static final Pattern CONTROL_OUTPUT = Pattern.compile("\\bout\\s+\\w+\\s+(of_\\w+)\\s*\\[");

    /**
     * A control stage's flattened input and output arrays share one name. The output takes the
     * {@code ofTcOut_} prefix (stripped again by the linker) in its declaration and wherever it is
     * written: an element, optionally swizzled, followed by an assignment. Packs write their outputs
     * at {@code gl_InvocationID} and read their inputs, which is what tells the two apart.
     */
    static String renameControlOutputs(String source) {
        var inputs = new java.util.HashSet<String>();
        var in = CONTROL_INPUT.matcher(source);
        while (in.find()) inputs.add(in.group(1));
        var out = CONTROL_OUTPUT.matcher(source);
        var clashing = new java.util.LinkedHashSet<String>();
        while (out.find()) if (inputs.contains(out.group(1))) clashing.add(out.group(1));
        for (String name : clashing) {
            String renamed = dev.vitrail.glsl.GlslTranslator.TESSELLATION_OUTPUT + name.substring("of_".length());
            String quoted = Pattern.quote(name);
            source = source.replaceAll("\\bout(\\s+\\w+\\s+)" + quoted + "(\\s*\\[)", "out$1" + renamed + "$2");
            source = source.replaceAll("\\b" + quoted + "(\\s*\\[[^\\[\\]]*\\](?:\\s*\\.\\s*\\w+)?\\s*(?:=(?!=)|[-+*/]=))",
                    renamed + "$1");
        }
        return source;
    }

    /** The stage as the compiler's own preprocessor leaves it: every directive settled. */
    private static String preprocess(long compiler, long options, String source, String name) {
        ByteBuffer text = MemoryUtil.memUTF8(source, false);
        ByteBuffer filename = MemoryUtil.memUTF8(name + ".gsh");
        ByteBuffer entrypoint = MemoryUtil.memUTF8("main");
        long result;
        try {
            result = Shaderc.shaderc_compile_into_preprocessed_text(compiler, text, Shaderc.shaderc_glsl_geometry_shader,
                    filename, entrypoint, options);
        } finally {
            MemoryUtil.memFree(entrypoint);
            MemoryUtil.memFree(filename);
            MemoryUtil.memFree(text);
        }
        if (result == 0) throw new IllegalStateException("shaderc returned no preprocessing result: " + name);
        try {
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success)
                throw new CompilationException(name, ProgramStage.GEOMETRY, source, Shaderc.shaderc_result_get_error_message(result));
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            byte[] owned = new byte[bytes.remaining()];
            bytes.get(owned);
            return new String(owned, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            Shaderc.shaderc_result_release(result);
        }
    }

    /**
     * The per-vertex values the engine makes for entity programs (entityColor, entityId,
     * blockEntityId, currentRenderedItemId) are declared by the translator as plain inputs in every
     * stage after the vertex stage. A geometry stage needs them as arrays and must hand them on to
     * the fragment stage itself, which Iris does as well (EntityPatcher). Here each becomes an
     * array input read through its first corner (the values are flat per draw), and a wrapped
     * EmitVertex() writes it to an output the stage linker pairs with the fragment input.
     */
    private static final Pattern ENGINE_VARYING = Pattern.compile(
            "(?m)^(flat\\s+)?in\\s+(vec4|int)\\s+(entityColor|entityId|blockEntityId|currentRenderedItemId)\\s*;\\s*$");

    static String forwardEngineVaryings(String geometry) {
        var matcher = ENGINE_VARYING.matcher(geometry);
        var forwarded = new java.util.ArrayList<String>();
        var text = new StringBuilder();
        while (matcher.find()) {
            String flat = matcher.group(1) != null || matcher.group(2).equals("int") ? "flat " : "";
            String type = matcher.group(2), name = matcher.group(3);
            forwarded.add(name);
            matcher.appendReplacement(text, java.util.regex.Matcher.quoteReplacement(
                    flat + "in " + type + " of_EngineIn_" + name + "[];\n"
                            + flat + "out " + type + " " + SpirvInterface.GEOMETRY_OUTPUT + name + ";\n"
                            + "#define " + name + " of_EngineIn_" + name + "[0]"));
        }
        if (forwarded.isEmpty()) return geometry;
        matcher.appendTail(text);
        var emit = new StringBuilder("#define EmitVertex() { ");
        for (String name : forwarded) emit.append(SpirvInterface.GEOMETRY_OUTPUT).append(name).append(" = ").append(name).append("; ");
        emit.append("EmitVertex(); }\n");
        // After the declarations, before the first function that may emit.
        String result = text.toString();
        int at = result.indexOf("#define " + forwarded.get(forwarded.size() - 1) + " of_EngineIn_");
        at = result.indexOf('\n', at) + 1;
        return result.substring(0, at) + emit + result.substring(at);
    }

    /** -Dvsdh.dumpShaders=<name part>: write the final GLSL of matching programs to vsdh-shader-dump/. */
    private static final String DUMP = System.getProperty("vsdh.dumpShaders");
    private static void dumpSources(String name, Map<ProgramStage, String> texts) {
        if (DUMP == null || !name.contains(DUMP)) return;
        try {
            var directory = java.nio.file.Path.of("vsdh-shader-dump");
            java.nio.file.Files.createDirectories(directory);
            for (var entry : texts.entrySet())
                java.nio.file.Files.writeString(directory.resolve(name.replaceAll("[^A-Za-z0-9._-]", "_") + "."
                        + entry.getKey().name().toLowerCase(java.util.Locale.ROOT) + ".glsl"), entry.getValue());
        } catch (java.io.IOException failure) {
            org.slf4j.LoggerFactory.getLogger("VSDH-Shaders").warn("Shader dump failed for {}", name, failure);
        }
    }

    /** The bindings key of storage block {@code bufferObject.index}. */
    public static String storageKey(int index) { return "ssbo#" + index; }

    /** The pack's bufferObject index behind a storage key, or -1 for any other binding name. */
    public static int storageIndex(String key) {
        return key.startsWith("ssbo#") ? Integer.parseInt(key.substring(5)) : -1;
    }

    private static final Pattern STORAGE_BLOCK = Pattern.compile(
            "(?m)layout\\s*\\(([^)]*)\\)(\\s*(?:(?:coherent|volatile|restrict|readonly|writeonly)\\s+)*buffer\\s+\\w+\\s*\\{)");
    private static final Pattern BINDING_ARGUMENT = Pattern.compile("\\s*,?\\s*\\bbinding\\s*=\\s*(\\d+)\\s*");

    static String bind(String source, Map<String, Integer> bindings) {
        var blocks = STORAGE_BLOCK.matcher(source);
        StringBuilder rebound = new StringBuilder();
        while (blocks.find()) {
            String arguments = blocks.group(1);
            var packBinding = BINDING_ARGUMENT.matcher(arguments);
            int index = 0;
            if (packBinding.find()) {
                index = Integer.parseInt(packBinding.group(1));
                arguments = (arguments.substring(0, packBinding.start()) + " " + arguments.substring(packBinding.end())).trim();
                if (arguments.startsWith(",")) arguments = arguments.substring(1).trim();
            }
            Integer ours = bindings.get(storageKey(index));
            if (ours == null) {
                // Only blocks in live branches are recorded; one in a dead #if is left exactly as written
                // for the compiler's own preprocessor to drop.
                blocks.appendReplacement(rebound, java.util.regex.Matcher.quoteReplacement(blocks.group()));
                continue;
            }
            String layout = (arguments.isEmpty() ? "" : arguments + ", ") + "set = 0, binding = " + ours;
            blocks.appendReplacement(rebound, java.util.regex.Matcher.quoteReplacement("layout(" + layout + ")" + blocks.group(2)));
        }
        blocks.appendTail(rebound);
        source = rebound.toString();
        for (var binding : bindings.entrySet()) {
            if (storageIndex(binding.getKey()) >= 0) continue;
            String name = Pattern.quote(binding.getKey());
            if (binding.getKey().equals("OfGlobals") || binding.getKey().equals("DynamicTransforms")) {
                source = source.replaceAll("layout\\(std140\\) uniform " + name + "\\s*\\{",
                        "layout(std140, set = 0, binding = " + binding.getValue() + ") uniform " + binding.getKey() + " {");
            } else {
                // Opaque declarations have been lifted to one declaration per line by the translator.
                // Images may carry a format layout and memory qualifiers ahead of "uniform"; the
                // binding is merged into that layout rather than written as a second one.
                var matcher = Pattern.compile("(?m)^(?:layout\\(([^)]*)\\)\\s*)?((?:(?:coherent|volatile|restrict|readonly|writeonly)\\s+)*)"
                        + "(uniform\\s+\\w+\\s+" + name + "\\s*(?:\\[[^]]*])?\\s*;)").matcher(source);
                StringBuilder out = new StringBuilder();
                while (matcher.find()) {
                    // The pack's own binding/set give way to ours (layout(binding = 0, rgba8) image2D).
                    String qualifiers = "";
                    if (matcher.group(1) != null) {
                        var kept = new java.util.ArrayList<String>();
                        for (String part : matcher.group(1).split(","))
                            if (!part.isBlank() && !part.trim().matches("(?:binding|set)\\s*=.*")) kept.add(part.trim());
                        qualifiers = kept.isEmpty() ? "" : String.join(", ", kept) + ", ";
                    }
                    matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("layout(" + qualifiers
                            + "set = 0, binding = " + binding.getValue() + ") " + matcher.group(2) + matcher.group(3)));
                }
                matcher.appendTail(out);
                source = out.toString();
            }
        }
        return source;
    }

    private static int kind(ProgramStage stage) {
        return switch (stage) {
            case VERTEX -> Shaderc.shaderc_glsl_vertex_shader;
            case FRAGMENT -> Shaderc.shaderc_glsl_fragment_shader;
            case GEOMETRY -> Shaderc.shaderc_glsl_geometry_shader;
            case COMPUTE -> Shaderc.shaderc_glsl_compute_shader;
            case TESSELLATION_CONTROL -> Shaderc.shaderc_glsl_tess_control_shader;
            case TESSELLATION_EVALUATION -> Shaderc.shaderc_glsl_tess_evaluation_shader;
        };
    }

    public static final class CompilationException extends RuntimeException {
        public final ProgramStage stage;
        public final String source;
        CompilationException(String name, ProgramStage stage, String source, String diagnostic) {
            super(name + "." + stage.extension() + ": " + diagnostic);
            this.stage = stage;
            this.source = source;
        }
    }
}
