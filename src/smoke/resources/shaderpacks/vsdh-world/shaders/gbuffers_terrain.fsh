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
uniform int heldItemId;
uniform int heldItemId2;
uniform int heldBlockLightValue;
uniform int heldBlockLightValue2;
uniform ivec2 eyeBrightnessSmooth;
uniform float wetness;
uniform float darknessLightFactor;
uniform float qaFrame;
uniform float qaEffect;
uniform float qaEye;
uniform float qaRain;
uniform float qaDry;
uniform float qaRainy;
uniform float qaSnowy;
uniform vec3 qaVector;
uniform int qaLarge;
uniform bool qaEnabled;
// Deliberately unsupported and unused: active-member reflection must skip it.
uniform vec4 neverReadFromWorld[3];
in vec2 texcoord;
in vec2 lightcoord;
in vec4 tint;
in vec3 worldNormal;
/* RENDERTARGETS: 0,1,2,3,4,5,6,7 */
/*
const int colortex0Format = RGBA16F;
const int colortex1Format = RGBA16F;
const int colortex2Format = RGBA16F;
const int colortex3Format = RGBA16F;
const int colortex4Format = RGBA16F;
const int colortex5Format = RGBA16F;
const int colortex6Format = RGBA16F;
const int colortex7Format = RGBA16F;
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
    int slot = int(gl_FragCoord.x) & 7;
    vec3 measurement = vec3(0.125, 0.25, 0.5);
    if (slot == 0) measurement = vec3(float(heldItemId)/65536.0, float(heldItemId2)/65536.0, float(heldBlockLightValue)/15.0);
    if (slot == 1) measurement = vec3(float(heldBlockLightValue2)/15.0, qaFrame, qaEffect);
    if (slot == 2) measurement = vec3(qaEye, qaRain, wetness);
    if (slot == 3) measurement = vec3(qaDry, qaRainy, qaSnowy);
    if (slot == 4) measurement = vec3(darknessLightFactor, vec2(eyeBrightnessSmooth)/240.0);
    if (slot == 5) measurement = qaVector;
    if (slot == 6) measurement = vec3(float(qaLarge & 255)/255.0, qaEnabled ? 1.0 : 0.0, 0.25);
    gl_FragData[7] = vec4(measurement, 1.0);
}
