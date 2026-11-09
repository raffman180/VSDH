#version 130
varying vec2 coord;
uniform sampler2D colortex0;
uniform sampler2D colortex2;
/*
const bool colortex0MipmapEnabled = true;
*/
/* DRAWBUFFERS:02 */
void main() {
    float history = texture(colortex2, coord).r;
    gl_FragData[0] = vec4(textureLod(colortex0, coord, 2.0).rgb + vec3(history, 0.0, 0.0), 1.0);
    gl_FragData[1] = vec4(history + 0.125, 0.0, 0.0, 1.0);
}
