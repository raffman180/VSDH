#version 330 compatibility
out vec2 texcoord;
out vec4 tint;
out vec3 normal;
void main() {
    gl_Position=ftransform();
    texcoord=gl_MultiTexCoord0.xy;
    tint=gl_Color;
    normal=gl_Normal;
}
