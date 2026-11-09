#version 330 compatibility
in ivec3 mc_Entity;
in vec2 mc_midTexCoord;
in vec4 at_tangent;
in vec4 at_midBlock;
out vec3 testNormal;
out vec4 testTangent;
out vec4 testColor;
out vec4 testMaterialUv;
out vec4 testMidBlock;
out vec2 testLight;
flat out int testLowByte;
void main() {
    gl_Position = vec4(gl_Vertex.xy * 2.0 - 1.0, 0.0, 1.0);
    testNormal = gl_Normal;
    testTangent = at_tangent;
    testColor = gl_Color;
    testMaterialUv = vec4(vec2(mc_Entity.xy), mc_midTexCoord);
    testLowByte = mc_Entity.x & 255;
    testMidBlock = at_midBlock;
    testLight = gl_MultiTexCoord1.xy;
}
