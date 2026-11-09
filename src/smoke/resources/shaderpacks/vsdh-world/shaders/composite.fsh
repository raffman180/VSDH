#version 330 compatibility
uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform mat4 gbufferProjectionInverse;
uniform vec3 fogColor;
uniform mat4 gbufferModelViewInverse;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
uniform sampler2D shadowtex0;
in vec2 texcoord;
/* RENDERTARGETS: 0 */
void main() {
    vec4 color=texture2D(colortex0,texcoord);
    float depth=texture2D(depthtex0,texcoord).r;
    vec4 position=gbufferProjectionInverse*vec4(texcoord*2.0-1.0,depth*2.0-1.0,1.0);
    float distanceToCamera=length(position.xyz/position.w);
    vec4 relativeWorld=gbufferModelViewInverse*vec4(position.xyz/position.w,1.0);
    vec4 light=shadowProjection*shadowModelView*relativeWorld;
    vec3 shadowUv=light.xyz/light.w*0.5+0.5;
    if(depth<0.99999 && all(greaterThanEqual(shadowUv,vec3(0.0))) && all(lessThanEqual(shadowUv,vec3(1.0)))) {
        float stored=texture2D(shadowtex0,shadowUv.xy).r;
        color.rgb*=shadowUv.z>stored+0.002?0.65:1.0;
    }
    float fog=depth<0.99999?clamp((distanceToCamera-24.0)/180.0,0.0,0.55):0.0;
    gl_FragData[0]=vec4(mix(color.rgb,fogColor,fog),color.a);
}
