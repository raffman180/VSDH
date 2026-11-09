#version 330 compatibility
uniform sampler2D gtexture;
uniform sampler2D lightmap;
in vec2 texcoord;
in vec2 lightcoord;
in vec4 tint;
in vec3 worldNormal;
/* RENDERTARGETS: 0,1 */
/*
const int colortex0Format = RGBA16F;
const int colortex1Format = RGBA16F;
*/
void main() {
    vec4 color = texture2D(gtexture, texcoord) * tint;
    color.rgb *= texture2D(lightmap, lightcoord).rgb;
    gl_FragData[0] = color;
    gl_FragData[1] = vec4(normalize(worldNormal)*0.5+0.5, 1.0);
}
