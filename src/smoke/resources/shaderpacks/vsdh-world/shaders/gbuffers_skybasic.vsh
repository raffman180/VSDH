#version 330 compatibility
out vec4 tint;
void main() {
    gl_Position = ftransform();
    tint = gl_Color;
}
