#version 130
varying vec2 coord;
uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform usampler2D colortex10;
uniform isampler2D colortex11;
/*
const bool colortex1MipmapEnabled = true;
*/
/* DRAWBUFFERS:0 */
void main() {
    bool matched = texture(colortex10, coord) == uvec4(7, 19, 33, 250)
            && texelFetch(colortex11, ivec2(0), 0) == ivec4(-120, 60, -8, 127);
    vec3 color = texture(colortex0, coord).rgb + textureLod(colortex1, coord, 1.0).rgb * 0.5;
    gl_FragData[0] = vec4(matched ? color : vec3(-1.0), 1.0);
}
