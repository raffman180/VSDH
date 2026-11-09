#version 330 compatibility
uniform sampler2D gtexture;
uniform sampler2D lightmap;
uniform ivec2 eyeBrightness;
uniform ivec2 atlasSize;
uniform int isEyeInWater;
uniform float screenBrightness;
uniform float sunAngle;
uniform int moonPhase;
uniform float nightVision;
uniform float blindness;
uniform float darknessFactor;
uniform vec3 cameraPositionFract;
uniform ivec3 cameraPositionInt;
uniform vec3 relativeEyePosition;
uniform float cloudHeight;
// Deliberately unsupported and unused: active-member reflection must skip it.
uniform vec4 neverReadFromWorld[3];
in vec2 texcoord;
in vec2 lightcoord;
in vec4 tint;
in vec3 worldNormal;
/* RENDERTARGETS: 0,1,2,3,4,5,6 */
/*
const int colortex0Format = RGBA16F;
const int colortex1Format = RGBA16F;
const int colortex2Format = RGBA16F;
const int colortex3Format = RGBA16F;
const int colortex4Format = RGBA16F;
const int colortex5Format = RGBA16F;
const int colortex6Format = RGBA16F;
*/
void main() {
    vec4 color = texture2D(gtexture, texcoord) * tint;
    color.rgb *= texture2D(lightmap, lightcoord).rgb;
    gl_FragData[0] = color;
    gl_FragData[1] = vec4(normalize(worldNormal)*0.5+0.5, 1.0);
    // All diagnostic alpha values must stay one: water blending also applies to MRTs.
    bool oddColumn = (int(gl_FragCoord.x) & 1) != 0;
    gl_FragData[2] = vec4(vec2(eyeBrightness)/240.0, float(isEyeInWater)/3.0, 1.0);
    gl_FragData[3] = vec4(vec2(atlasSize)/4096.0, screenBrightness, 1.0);
    gl_FragData[4] = vec4(nightVision, blindness, darknessFactor, 1.0);
    gl_FragData[5] = vec4(oddColumn ? relativeEyePosition*0.01+0.5 : cameraPositionFract, 1.0);
    gl_FragData[6] = vec4(oddColumn
        ? vec3(gl_Fog.density, float(cameraPositionInt.y)/512.0, float(cameraPositionInt.x-cameraPositionInt.z)/512.0)
        : vec3(cloudHeight/1024.0, sunAngle, float(moonPhase)/7.0), 1.0);
}
