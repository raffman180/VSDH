package net.vulkanmod.gl;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

public final class GlEmulationLog {
    private static final int DIAGNOSTIC_KEY_LIMIT = 256;
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Set<String> CONTRACT_FAMILIES = Set.of(
            "provider",
            "state_query",
            "object_lifetime",
            "texture_image",
            "framebuffer_readback",
            "shader_conversion",
            "draw_path",
            "performance");

    private static final class Log {
        static final org.apache.logging.log4j.Logger IMPL =
                org.apache.logging.log4j.LogManager.getLogger("VulkanMod-GlEmulation");
    }

    private GlEmulationLog() {
    }

    public static void warnContractGap(String family, String operation, String message, Object... args) {
        warnOnce(contractGapKey(family, operation), message, args);
    }

    private static String contractGapKey(String family, String operation) {
        if (family == null || !CONTRACT_FAMILIES.contains(family)) {
            throw new IllegalArgumentException("Unknown GL contract family: " + family);
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("GL contract operation must not be blank");
        }
        return family + "." + operation;
    }

    static void warnOnce(String key, String message, Object... args) {
        if (REPORTED.contains(key) || REPORTED.size() >= DIAGNOSTIC_KEY_LIMIT || !REPORTED.add(key)) {
            return;
        }

        try {
            Log.IMPL.warn(message, args);
        } catch (Throwable ignored) {
        }
    }

    private static final int UNIMPLEMENTED_KEY_LIMIT = 128;
    private static final Set<String> UNIMPLEMENTED = ConcurrentHashMap.newKeySet();

    /**
     * A GL entry point that is accepted but does nothing here although its caller expects pixels, geometry or
     * data from it. Logged once per function so that a mod that silently shows nothing leaves a trace. The
     * repeat path is one set lookup and allocates nothing.
     */
    public static void unimplemented(String function) {
        if (UNIMPLEMENTED.contains(function) || UNIMPLEMENTED.size() >= UNIMPLEMENTED_KEY_LIMIT || !UNIMPLEMENTED.add(function)) {
            return;
        }

        try {
            Log.IMPL.warn("{} is not emulated by the Vulkan renderer; the call is ignored (a mod using it may draw nothing or wrong)", function);
        } catch (Throwable ignored) {
        }
    }

    /** As {@link #unimplemented(String)}, for a call that is only ignored with these arguments. */
    public static void unimplemented(String function, String detail) {
        if (UNIMPLEMENTED.contains(function) || UNIMPLEMENTED.size() >= UNIMPLEMENTED_KEY_LIMIT || !UNIMPLEMENTED.add(function)) {
            return;
        }

        try {
            Log.IMPL.warn("{}: {}; the call is ignored (a mod using it may draw nothing or wrong)", function, detail);
        } catch (Throwable ignored) {
        }
    }

    /** Whether an ignored call of this name loses output a mod would notice (as opposed to hints, debug and legacy state). */
    public static boolean losesOutput(String function) {
        return !function.contains("Invalidate") && IMPACT.matcher(function).find();
    }

    private static final java.util.regex.Pattern IMPACT = java.util.regex.Pattern.compile(
            "Tex(ture)?SubImage|TexImage|CopyTex|CopyImageSubData|TexStorage|TextureStorage|TextureView|TexBuffer|TextureBuffer"
                    + "|DrawArrays|DrawElements|DrawRangeElements|MultiDraw|DrawTransformFeedback|DispatchCompute"
                    + "|ReadnPixels|GetTextureImage|GetTextureSubImage|GetnTexImage|GetCompressedTex|GetnCompressedTex"
                    + "|ClearBuffer|ClearTex|ClearNamedFramebuffer|ClearNamedBuffer|FramebufferTexture|FramebufferDrawBuffer|FramebufferReadBuffer"
                    + "|ColorMaski|Enablei|Disablei|BlendEquationi|BlendEquationSeparatei|BlendFunci|BlendFuncSeparatei"
                    + "|^glUniform|ProgramUniform|UniformBlockBinding|ShaderStorageBlockBinding|UniformSubroutines"
                    + "|BindImageTexture|BindTextures|BindBuffersBase|BindBuffersRange|BindVertexBuffer|BindSamplers"
                    + "|VertexAttribBinding|VertexAttribFormat|VertexAttribIFormat|VertexAttribLFormat|VertexAttribLPointer|VertexBindingDivisor"
                    + "|^glVertexAttrib[IL]?[1-4]|VertexArray(Attrib|Binding|Element|Vertex)|EnableVertexArrayAttrib"
                    + "|ScissorIndexed|ScissorArray|ViewportIndexed|ViewportArray|DepthRangeIndexed|DepthRangeArray"
                    + "|BindProgramPipeline|UseProgramStages|ActiveShaderProgram|ProgramBinary|ShaderBinary"
                    + "|BeginTransformFeedback|TransformFeedbackVaryings|TransformFeedbackBuffer|BeginConditionalRender|BindFragDataLocation"
                    + "|ClipControl|GetQueryObject|GetQueryBufferObject|QueryIndexed|PatchParameter");

    public static Set<String> reportedContractGaps() {
        return Set.copyOf(new TreeSet<>(REPORTED));
    }
}
