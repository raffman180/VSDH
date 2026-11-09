#version 330 compatibility
uniform sampler2D tex;
uniform int entityId;
in vec2 texcoord;
in vec4 tint;
in vec3 normal;
const int shadowMapResolution=512;
const float shadowDistance=128.0;
const float shadowIntervalSize=2.0;
/* RENDERTARGETS: 0,1 */
/*
const int shadowcolor0Format = RGBA8;
const int shadowcolor1Format = RGBA8;
*/
void main() {
    if(entityId!=0)discard;
    gl_FragData[0]=texture2D(tex,texcoord)*tint;
    gl_FragData[1]=vec4(normalize(normal)*0.5+0.5,1.0);
}
