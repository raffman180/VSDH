package dev.vitrail.uniform;

/** Type vocabulary retained from Vitrail; std140 writing remains VSDH's responsibility. */
public enum UniformShape {
    FLOAT(1), INT(1), VEC2(2), VEC3(3), VEC4(4), IVEC2(2), IVEC3(3), IVEC4(4), MAT3(9), MAT4(16), FOG(8);
    private final int rank;
    UniformShape(int rank) { this.rank = rank; }
    public int rank() { return rank; }
    public static UniformShape of(String type) {
        return switch (type) {
            case "float" -> FLOAT;
            case "bool", "int" -> INT;
            case "vec2" -> VEC2; case "vec3" -> VEC3; case "vec4" -> VEC4;
            case "ivec2" -> IVEC2; case "ivec3" -> IVEC3; case "ivec4" -> IVEC4;
            case "mat3" -> MAT3; case "mat4" -> MAT4; case "OfFog" -> FOG;
            default -> null;
        };
    }
}
