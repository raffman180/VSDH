#version 330 compatibility
uniform sampler2D gtexture;
uniform int renderStage;
in vec4 tint;
in vec2 uv;
/* RENDERTARGETS: 9 */
/*
const int colortex9Format = RGBA16F;
*/
void main() {
    if (renderStage != MC_RENDER_STAGE_RAIN_SNOW || texture2D(gtexture, uv).a * tint.a < 0.02) discard;
    gl_FragData[0] = vec4(0.125, 0.5, 0.875, 1.0);
}
