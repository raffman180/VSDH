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
        Map<String, Integer> bindings = new LinkedHashMap<>();
        if (!translated.uniforms().isEmpty()) bindings.put("OfGlobals", bindings.size());
        if (translated.stages().values().stream().anyMatch(unit ->
                unit.text().contains("uniform DynamicTransforms {")))
            bindings.put("DynamicTransforms", bindings.size());
        translated.samplers().forEach(sampler -> bindings.putIfAbsent(sampler.name(), bindings.size()));

        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0) throw new IllegalStateException("shaderc compiler initialization failed");
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            if (options == 0) throw new IllegalStateException("shaderc options initialization failed");
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                    Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
            Map<ProgramStage, byte[]> stages = new LinkedHashMap<>();
            Map<ProgramStage, String> sources = new LinkedHashMap<>();
            for (ProgramStage stage : ProgramStage.values()) {
                var unit = translated.stages().get(stage);
                if (unit == null) continue;
                String source = bind(unit.text(), bindings);
                if (stage == ProgramStage.VERTEX && translated.inputs() == VertexInputs.FULLSCREEN) {
                    source = source.replace("in vec3 Position;", "#define Position vec3(UV0, 0.0)")
                            .replace("in vec2 UV0;", "#define UV0 vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2))");
                }
                sources.put(stage, source);
                // The CharSequence overload copies the entire shader to LWJGL's 64 KiB stack.
                // Expanded shaderpacks routinely exceed that size; own this temporary allocation.
                ByteBuffer text = MemoryUtil.memUTF8(source, false);
                ByteBuffer filename = MemoryUtil.memUTF8(name + "." + stage.extension());
                ByteBuffer entrypoint = MemoryUtil.memUTF8("main");
                long result;
                try {
                    result = Shaderc.shaderc_compile_into_spv(compiler, text, kind(stage), filename, entrypoint, options);
                } finally {
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
            return new Compiled(stages, bindings, sources);
        } finally {
            if (options != 0) Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    static String bind(String source, Map<String, Integer> bindings) {
        for (var binding : bindings.entrySet()) {
            String name = Pattern.quote(binding.getKey());
            String layout = "layout(set = 0, binding = " + binding.getValue() + ") ";
            if (binding.getKey().equals("OfGlobals") || binding.getKey().equals("DynamicTransforms")) {
                source = source.replaceAll("layout\\(std140\\) uniform " + name + "\\s*\\{",
                        "layout(std140, set = 0, binding = " + binding.getValue() + ") uniform " + binding.getKey() + " {");
            } else {
                // Opaque declarations have been lifted to one declaration per line by the translator.
                source = source.replaceAll("(?m)^(uniform\\s+\\w+\\s+" + name + "\\s*(?:\\[[^]]*])?\\s*;)", layout + "$1");
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
