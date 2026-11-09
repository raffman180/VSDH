#version 330 compatibility
uniform int renderStage;
in vec4 tint;
/* RENDERTARGETS: 0 */
void main() {
    if (renderStage != MC_RENDER_STAGE_SKY) discard;
    gl_FragData[0] = vec4(tint.r, 0.25, 0.75, 1.0);
}
