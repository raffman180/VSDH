#version 130
varying vec2 coord;
uniform sampler2D depthtex0;
uniform sampler2D depthtex1;
uniform sampler2D depthtex2;
/* DRAWBUFFERS:4 */
void main() { gl_FragData[0] = vec4(texture(depthtex0, coord).r, texture(depthtex1, coord).r, texture(depthtex2, coord).r, 1.0); }
