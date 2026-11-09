#version 120
#include "/lib/tint.glsl"
varying vec2 coord;
uniform sampler2DShadow unusedShadow;
uniform sampler2D colortex1;
// The translator sees this use; SPIR-V optimization removes the uncalled function.
// Its binding must remain a hole rather than shifting the later sampler's binding.
float neverCalled() { return texture2D(unusedShadow, vec3(0.5)); }
/* DRAWBUFFERS:0 */
void main() {
    gl_FragColor = vec4(texture2D(colortex1, coord).rgb * tint * gain, 1.0);
}
