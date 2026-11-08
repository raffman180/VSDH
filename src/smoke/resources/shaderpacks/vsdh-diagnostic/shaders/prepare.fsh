#version 120
varying vec2 coord;
uniform float gain;
/* DRAWBUFFERS:01 */
void main() {
    gl_FragData[0] = vec4(coord, gain, 1.0);
    gl_FragData[1] = vec4(0.25, 0.5, 0.75, 1.0);
}
