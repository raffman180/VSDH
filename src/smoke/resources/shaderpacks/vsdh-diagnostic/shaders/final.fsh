#version 120
#include "/lib/tint.glsl"
varying vec2 coord;
uniform sampler2D colortex1;
uniform sampler2DShadow unusedShadow;
/* DRAWBUFFERS:0 */
void main() {
    gl_FragColor = vec4(texture2D(colortex1, coord).rgb * tint * gain, 1.0);
}
