#version 130
uniform int entityId;
uniform int blockEntityId;
uniform int currentRenderedItemId;
varying vec4 lightAndMid;
varying vec4 normalAndSign;
varying vec2 spriteUV;
varying vec4 color;
bool nearValue(vec4 actual, vec4 expected) {
    return all(lessThan(abs(actual - expected), vec4(.001)));
}
void main() {
    bool mirrored = color.r < .5;
    bool ids = entityId == (mirrored ? 601 : 65535) && blockEntityId == 0 && currentRenderedItemId == 0;
    bool geometry = nearValue(normalAndSign, vec4(0, 1, 1, mirrored ? -1 : 1));
    bool light = nearValue(lightAndMid, vec4(128.0 / 255.0, 240.0 / 255.0, .375, .625));
    bool sprite = all(greaterThan(spriteUV, vec2(.24, .37))) && all(lessThan(spriteUV, vec2(.51, .88)))
            && abs(color.g - 128.0 / 255.0) < .001;
    gl_FragColor = vec4(float(ids), float(geometry), float(light), float(sprite));
}
