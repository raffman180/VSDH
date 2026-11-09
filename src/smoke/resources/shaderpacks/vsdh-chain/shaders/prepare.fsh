#version 130
/* DRAWBUFFERS:1 */
void main() {
    int checker = (int(gl_FragCoord.x) + int(gl_FragCoord.y)) & 1;
    gl_FragData[0] = vec4(0.25, 0.5, 0.5 + 0.5 * float(checker), 1.0);
}
