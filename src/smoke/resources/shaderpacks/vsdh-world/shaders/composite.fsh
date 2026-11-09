#version 330 compatibility
uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform mat4 gbufferProjectionInverse;
uniform vec3 fogColor;
in vec2 texcoord;
/* RENDERTARGETS: 0 */
void main() {
    vec4 color=texture2D(colortex0,texcoord);
    float depth=texture2D(depthtex0,texcoord).r;
    vec4 position=gbufferProjectionInverse*vec4(texcoord*2.0-1.0,depth*2.0-1.0,1.0);
    float distanceToCamera=length(position.xyz/position.w);
    float fog=depth<0.99999?clamp((distanceToCamera-24.0)/180.0,0.0,0.55):0.0;
    gl_FragData[0]=vec4(mix(color.rgb,fogColor,fog),color.a);
}
