#version 330 compatibility
uniform int testMode;
in vec3 testNormal;
in vec4 testTangent;
in vec4 testColor;
in vec4 testMaterialUv;
in vec4 testMidBlock;
in vec4 testLight;
flat in int testLowByte;
/* RENDERTARGETS: 0,1,2,3 */
void main() {
    gl_FragData[0] = testMode == 0 ? vec4(testNormal * 0.5 + 0.5, testColor.a) : testColor;
    gl_FragData[1] = testMode == 0 ? testTangent * 0.5 + 0.5 : testLight / 255.0;
    gl_FragData[2] = vec4(testMode == 0 ? testMaterialUv.x / 67108864.0 : float(testLowByte) / 255.0,
                        testMaterialUv.y, testMaterialUv.zw);
    gl_FragData[3] = vec4(testMidBlock.xyz / 128.0 + 0.5, testMidBlock.w / 15.0);
}
