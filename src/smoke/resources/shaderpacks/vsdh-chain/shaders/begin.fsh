#version 130
/*
const int colortex0Format = RGBA16F;
const int colortex1Format = RGBA16F;
const int colortex2Format = RGBA16F;
const int colortex4Format = RGBA16F;
const bool colortex2Clear = false;
const vec4 colortex2ClearColor = vec4(0.0);
*/
uniform float seedValue;
/* DRAWBUFFERS:0 */
void main() { gl_FragData[0] = vec4(seedValue, 0.0, 0.0, 1.0); }
