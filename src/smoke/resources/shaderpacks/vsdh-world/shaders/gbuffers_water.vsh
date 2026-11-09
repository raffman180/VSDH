#version 330 compatibility
out vec2 texcoord;
out vec2 lightcoord;
out vec4 tint;
out vec3 worldNormal;
void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.xy;
    lightcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    tint = gl_Color;
    worldNormal = gl_Normal;
}
