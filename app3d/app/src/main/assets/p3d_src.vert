uniform mat4 uSTMatrix;
attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vTex;
varying vec2 vUv;      // crop coordinates before the SurfaceTexture transform (depth map space)
void main() {
    gl_Position = aPosition;
    vTex = (uSTMatrix * aTexCoord).xy;
    vUv = aTexCoord.xy;
}
