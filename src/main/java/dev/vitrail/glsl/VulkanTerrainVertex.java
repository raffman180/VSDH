package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Decoder for VSDH's Reforged terrain format, independent of Sodium's mesh layout. */
public final class VulkanTerrainVertex {
    private VulkanTerrainVertex() {}
    public static final String PROLOGUE = "ofVulkanTerrainVertex";
    public static final List<String> ATTRIBUTES = List.of("vs_Position", "vs_Color", "vs_UV0",
            "vs_Normal", "vs_Tangent", "vs_Material", "vs_Fluid", "vs_MidUV", "vs_MidBlock", "vs_TintAO");
    private static final List<String> TYPES = List.of("ivec4", "vec4", "uvec2", "int", "int",
            "int", "int", "uvec2", "int", "vec4");

    public static List<String> prologue(List<String> carried, Set<String> used,
                                        Map<String, String> synthesized, boolean separateAo, boolean oldLighting) {
        if (!carried.equals(ATTRIBUTES)) throw new IllegalArgumentException("VSDH terrain format mismatch");
        List<String> lines = new ArrayList<>();
        // Explicit locations survive removal of unused attributes by the SPIR-V optimiser.
        for (int i = 0; i < ATTRIBUTES.size(); i++)
            lines.add("layout(location = " + i + ") in " + TYPES.get(i) + " " + ATTRIBUTES.get(i) + ";");
        lines.add("layout(push_constant) uniform VsdhChunk { vec3 vs_ChunkOffset; };");
        lines.add("vec4 of_Vertex, of_Color, of_MultiTexCoord0, of_MultiTexCoord1;");
        lines.add("vec3 of_Normal;");
        lines.add("#define of_MultiTexCoord2 of_MultiTexCoord1");
        for (int unit = 3; unit <= 7; unit++)
            lines.add("#define of_MultiTexCoord" + unit + (unit == 3
                    ? " vec4(vec2(vs_MidUV) / 32768.0, 0.0, 1.0)"
                    : " vec4(0.0, 0.0, 0.0, 1.0)"));
        Map<String, String> globals = VertexPrologue.globals(used, synthesized, Map.of());
        globals.forEach((name, type) -> lines.add(answer(name) == null
                ? VertexPrologue.declaration(name, type) : type + " " + name + ";"));
        lines.add("vec4 vs_Unpack(int v) { return vec4(bitfieldExtract(v,0,8), bitfieldExtract(v,8,8),"
                + " bitfieldExtract(v,16,8), bitfieldExtract(v,24,8)) / 127.0; }");
        lines.add("void " + PROLOGUE + "() {");
        lines.add("vec3 section = vec3(bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0,16,8),0,8));");
        // ChunkOffset includes +4 to undo the encoder's -4 bias, just as Reforged's own shader.
        lines.add("of_Vertex = vec4(vec3(vs_Position.xyz) / 2048.0 + vs_ChunkOffset + section, 1.0);");
        String tint = "vs_TintAO.rgb" + (oldLighting ? " * (float(uint(vs_Normal) >> 24u) / 255.0)" : "");
        lines.add("of_Color = " + (separateAo ? "vec4(" + tint + ", vs_TintAO.a)"
                : oldLighting ? "vs_Color" : "vec4(vs_TintAO.rgb * vs_TintAO.a, vs_Color.a)") + ";");
        lines.add("of_MultiTexCoord0 = vec4(vec2(vs_UV0) / 32768.0, 0.0, 1.0);");
        lines.add("uint lm = uint(vs_Position.w) & 65535u;");
        lines.add("of_MultiTexCoord1 = vec4(float(lm & 255u), float(lm >> 8u), 0.0, 1.0);");
        lines.add("of_Normal = normalize(vs_Unpack(vs_Normal).xyz);");
        globals.forEach((name, type) -> {
            String expression = answer(name);
            if (expression != null) lines.add(name + " = " + (name.equals("mc_Entity")
                    && integerEntity(type) != null ? integerEntity(type) : cast(type, expression)) + ";");
        });
        lines.add("}");
        return List.copyOf(lines);
    }

    private static String answer(String name) {
        return switch (name) {
            case "mc_Entity" -> "vec4(float(vs_Material), float(vs_Fluid), 0.0, 1.0)";
            case "mc_midTexCoord" -> "vec4(vec2(vs_MidUV) / 32768.0, 0.0, 1.0)";
            case "at_tangent" -> "vec4(normalize(vs_Unpack(vs_Tangent).xyz), vs_Unpack(vs_Tangent).w)";
            case "at_midBlock" -> "vec4(bitfieldExtract(vs_MidBlock,0,8), bitfieldExtract(vs_MidBlock,8,8),"
                    + " bitfieldExtract(vs_MidBlock,16,8), float(uint(vs_MidBlock) >> 24u))";
            case "vaPosition" -> "of_Vertex";
            case "vaNormal" -> "vec4(of_Normal, 0.0)";
            case "vaColor" -> "of_Color";
            case "vaUV0" -> "of_MultiTexCoord0";
            case "vaUV1" -> "vec4(0.0)";
            case "vaUV2" -> "of_MultiTexCoord1";
            default -> null;
        };
    }

    private static String cast(String type, String expression) {
        String swizzle = switch (type) {
            case "float", "int", "uint", "bool" -> ".x";
            case "vec2", "ivec2", "uvec2", "bvec2" -> ".xy";
            case "vec3", "ivec3", "uvec3", "bvec3" -> ".xyz";
            default -> "";
        };
        return type + "((" + expression + ")" + swizzle + ")";
    }

    // Preserve all 32 bits for packs declaring an integer material input; a float round trip
    // would silently change e.g. 33554559 into 33554560 before the pack's own bit operations.
    private static String integerEntity(String type) {
        return switch (type) {
            case "int" -> "vs_Material";
            case "uint" -> "uint(vs_Material)";
            case "ivec2", "uvec2" -> type + "(vs_Material, vs_Fluid)";
            case "ivec3", "uvec3" -> type + "(vs_Material, vs_Fluid, 0)";
            case "ivec4", "uvec4" -> type + "(vs_Material, vs_Fluid, 0, 1)";
            default -> null;
        };
    }
}
