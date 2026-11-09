#version 330 compatibility
uniform sampler2D colortex0;
in vec2 texcoord;
/* RENDERTARGETS: 0 */
void main() {gl_FragData[0]=texture2D(colortex0,texcoord);}
