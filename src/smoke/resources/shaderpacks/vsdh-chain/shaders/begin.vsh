#version 130
varying vec2 coord;
void main() {
    gl_Position = ftransform();
    coord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
}
