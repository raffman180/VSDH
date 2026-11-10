#version 130
/* RENDERTARGETS: 1,10 */
/*
const int colortex10Format = RGBA8UI;
*/
layout(location = 1) out uvec4 integerProbe;
void main() {
    int checker = (int(gl_FragCoord.x) + int(gl_FragCoord.y)) & 1;
    gl_FragData[0] = vec4(0.25, 0.5, 0.5 + 0.5 * float(checker), 1.0);
    integerProbe = uvec4(7, 19, 33, 250);
}
