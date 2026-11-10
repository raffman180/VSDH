#version 130
/* RENDERTARGETS: 1,10,11 */
/*
const int colortex10Format = RGBA8UI;
const int colortex11Format = RGBA8I;
*/
layout(location = 1) out uvec4 unsignedProbe;
layout(location = 2) out ivec4 signedProbe;
void main() {
    int checker = (int(gl_FragCoord.x) + int(gl_FragCoord.y)) & 1;
    gl_FragData[0] = vec4(0.25, 0.5, 0.5 + 0.5 * float(checker), 1.0);
    unsignedProbe = uvec4(7, 19, 33, 250);
    signedProbe = ivec4(-120, 60, -8, 127);
}
