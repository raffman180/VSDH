#version 330 compatibility
uniform sampler2D gtexture;
uniform sampler2D lightmap;
in vec2 texcoord;
in vec2 lightcoord;
in vec4 tint;
in vec3 worldNormal;
/* RENDERTARGETS: 0 */
/*
const int colortex0Format = RGBA16F;
const int colortex1Format = RGBA16F;
*/
void main() {
    vec4 color = texture2D(gtexture, texcoord) * tint;
    color.rgb *= texture2D(lightmap, lightcoord).rgb;
    gl_FragData[0] = color;

}
