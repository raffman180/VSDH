#version 130
varying vec2 coord;
uniform sampler2D colortex0;
void main() { gl_FragColor = texture(colortex0, coord); }
