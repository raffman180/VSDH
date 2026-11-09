#version 330 compatibility
uniform sampler2D colortex0;
uniform float viewWidth;
uniform float viewHeight;
in vec2 texcoord;
void main() {
    gl_FragColor = texcoord.x<2.0/viewWidth && texcoord.y<2.0/viewHeight
        ? vec4(0.25,0.5,0.75,1.0) : texture2D(colortex0,texcoord);
}
