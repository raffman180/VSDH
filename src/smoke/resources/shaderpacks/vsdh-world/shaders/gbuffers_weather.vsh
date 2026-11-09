#version 330 compatibility
out vec4 tint;
out vec2 uv;
void main() {
    gl_Position = ftransform();
    tint = gl_Color;
    uv = gl_MultiTexCoord0.xy;
}
