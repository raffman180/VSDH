#version 130
attribute vec2 mc_midTexCoord;
attribute vec4 at_tangent;
varying vec4 lightAndMid;
varying vec4 normalAndSign;
varying vec2 spriteUV;
varying vec4 color;
void main() {
    gl_Position = ftransform();
    lightAndMid = vec4(gl_MultiTexCoord1.xy / 255.0, mc_midTexCoord);
    normalAndSign = vec4(gl_Normal.yz, at_tangent.xw);
    spriteUV = gl_MultiTexCoord0.xy;
    color = gl_Color;
}
