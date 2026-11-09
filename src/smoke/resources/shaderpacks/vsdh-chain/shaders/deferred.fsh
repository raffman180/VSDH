#version 130
varying vec2 coord;
uniform sampler2D colortex0;
uniform sampler2D colortex1;
/*
const bool colortex1MipmapEnabled = true;
*/
/* DRAWBUFFERS:0 */
void main() { gl_FragData[0] = vec4(texture(colortex0, coord).rgb + textureLod(colortex1, coord, 1.0).rgb * 0.5, 1.0); }
