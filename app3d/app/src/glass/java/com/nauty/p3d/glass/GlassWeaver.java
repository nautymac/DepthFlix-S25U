package com.nauty.p3d.glass;

import android.opengl.GLES20;
import android.util.Log;

import com.nauty.p3d.gl.Stereo3DView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 플레이어의 마지막 단계: Stereo3DView 가 만든 SBS 를 SpatialGlass 필름에 맞춰 위빙한다.
 *
 * 셰이더는 3D 맞추기 도구({@link StereoRenderer})와 같은 식이다 — 렌즈 간격·기울기·위상,
 * Glass 에서 측정한 채널별 섞기 곡선({@link WeaveTable}), 눈 위치에 따른 위상 이동.
 * 값은 도구와 같은 저장소({@link Settings}, "stereo")를 읽으므로 도구에서 맞춘 그대로 나온다.
 *
 * 다른 점은 그림이 이미 화면 크기의 SBS 라는 것뿐이다: 왼쪽 절반이 좌안, 오른쪽 절반이
 * 우안이고 각 절반이 화면 전체로 늘어난다 (레터박스·수렴·자막은 Stereo3DView 가 이미 했다).
 */
final class GlassWeaver implements Stereo3DView.Weaver {
    private static final String TAG = "GlassWeaver";
    /** 보정이 기준으로 삼는 눈 위치: 정면 400 mm (Glass 와 같다). */
    private static final float REFERENCE_DISTANCE_MM = 400f;

    private static final String VERTEX =
            "attribute vec2 aPos;\n"
            + "void main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n";

    /**
     * @param external true: the SBS picture is an external (SurfaceTexture) texture, as the
     *                 screen capture of another app is ({@link ScreenWeaveService}); its
     *                 coordinates go through the SurfaceTexture's transform (uTexM).
     */
    private static String fragment(boolean external) {
        return (external ? "#extension GL_OES_EGL_image_external : require\n" : "")
            + "precision highp float;\n"
            // SBS: 왼쪽 절반 좌안, 오른쪽 절반 우안
            + (external ? "uniform samplerExternalOES uTex;\nuniform mat4 uTexM;\n"
                        + "uniform vec4 uSrc;\nuniform vec4 uDst;\n"
                        // AI 3D: a 2D picture, each eye shifted by a depth map (255 = near).
                        // The same backward warp as the player's (assets/p3d_src.frag).
                        + "uniform int uAi;\nuniform sampler2D uDepth;\nuniform float uDepthAmt;\n"
                        + "float warp(vec2 p, float amt) {\n"
                        + "  float off = amt * (texture2D(uDepth, p).r - 0.4);\n"
                        + "  for (int i = 0; i < 4; i++) {\n"
                        + "    float d = texture2D(uDepth, vec2(p.x + off, p.y)).r;\n"
                        + "    off = 0.5 * (off + amt * (d - 0.4));\n"
                        + "  }\n"
                        + "  return off;\n"
                        + "}\n"
                        : "uniform sampler2D uTex;\n")
            + "uniform vec2 uSize;\n"         // 화면 픽셀
            + "uniform float uPitch;\n"
            + "uniform float uSlant;\n"
            + "uniform float uPhase;\n"
            + "uniform vec2 uEye;\n"
            + "uniform float uK;\n"
            + "uniform vec2 uRef;\n"
            + "uniform float uS;\n"        // stretch gap / eye distance
            + "uniform float uS0;\n"       // stretch gap / the distance the pitch is exact at
            + "uniform int uSwap;\n"
            + "uniform sampler2D uWeave;\n"
            + "uniform vec2 uOrigin;\n"       // 창의 왼쪽 아래가 패널에서 어디인지 (패널 화소)
            + "uniform vec2 uShift;\n"        // 그림을 패널 정중앙으로 옮기는 양 (창 화소)
            + "uniform float uBoost;\n"       // 밝기: 1 = 그대로
            + "uniform float uContrast;\n"    // 대비: 1 = 그대로
            + "\n"
            // 3D 는 각 눈에 화소의 일부만 가서 어둡다. 어두운 쪽·중간을 끌어올리고(흰색은 그대로)
            // 대비를 준다. 위빙 전에 그림에만 걸어 두 그림 사이의 틈(분리)은 건드리지 않는다.
            + "vec3 tone(vec3 c) {\n"
            + "  c = 1.0 - pow(1.0 - clamp(c, 0.0, 1.0), vec3(uBoost));\n"
            + "  return clamp((c - 0.5) * uContrast + 0.5, 0.0, 1.0);\n"
            + "}\n"
            + "\n"
            + "float phaseAt() {\n"
            + "  vec2 p = gl_FragCoord.xy + uOrigin;\n"   // 렌즈는 패널 위에 있다
            + "  vec2 q = p + (uEye - uRef) * uK + (uRef - p) * (uS - uS0);\n"
            + "  return fract((q.x + q.y * uSlant) / uPitch + uPhase);\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "  vec2 uv = (gl_FragCoord.xy - uShift) / uSize;\n"
            + "  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {\n"
            + "    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + (external
                // The SBS picture is the part uSrc of the capture (a 16:9 video on this panel
                // has black bars beside it, which must not be halved along with it); each
                // half goes to the part uDst of the panel (fit / fill / stretch).
                ? "  vec2 p = (uv - uDst.xy) / uDst.zw;\n"
                + "  if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) {\n"
                + "    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
                + "    return;\n"
                + "  }\n"
                + "  p.x = clamp(p.x, 0.002, 0.998);\n"   // keep each eye off the other's edge
                + "  vec2 tl;\n"
                + "  vec2 tr;\n"
                + "  if (uAi == 1) {\n"
                + "    tl = uSrc.xy + vec2(clamp(p.x + warp(p, -uDepthAmt), 0.0, 1.0), p.y) * uSrc.zw;\n"
                + "    tr = uSrc.xy + vec2(clamp(p.x + warp(p, uDepthAmt), 0.0, 1.0), p.y) * uSrc.zw;\n"
                + "  } else {\n"
                + "    tl = uSrc.xy + vec2(p.x * 0.5, p.y) * uSrc.zw;\n"
                + "    tr = uSrc.xy + vec2(0.5 + p.x * 0.5, p.y) * uSrc.zw;\n"
                + "  }\n"
                + "  vec3 l = tone(texture2D(uTex, (uTexM * vec4(tl, 0.0, 1.0)).xy).rgb);\n"
                + "  vec3 r = tone(texture2D(uTex, (uTexM * vec4(tr, 0.0, 1.0)).xy).rgb);\n"
                : "  vec3 l = tone(texture2D(uTex, vec2(uv.x * 0.5, uv.y)).rgb);\n"
                + "  vec3 r = tone(texture2D(uTex, vec2(0.5 + uv.x * 0.5, uv.y)).rgb);\n")
            + "  float ph = phaseAt();\n"
            + "  vec3 wl = texture2D(uWeave, vec2(ph, 0.25)).rgb;\n"
            + "  vec3 wr = texture2D(uWeave, vec2(ph, 0.75)).rgb;\n"
            + "  if (uSwap == 1) { vec3 t = wl; wl = wr; wr = t; }\n"
            + "  gl_FragColor = vec4(wl * l + wr * r, 1.0);\n"
            + "}\n";
    }

    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private final boolean external;
    private int uTexM;
    /** The SurfaceTexture's transform for the frame about to be drawn (external only). */
    private final float[] texMatrix = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private int uSrc;
    private int uDst;
    /** x, y, width, height as fractions: the SBS picture in the capture, and where it goes. */
    private final float[] srcRect = {0f, 0f, 1f, 1f};
    private final float[] dstRect = {0f, 0f, 1f, 1f};
    private int uAi;
    private int uDepth;
    private int uDepthAmt;
    /** AI 3D (external only): depth map texture, 0 = the picture is side-by-side. */
    private int depthTexture;
    private float depthAmount;

    private volatile Settings settings;
    private final float pixelMm;
    private final int panelW;
    private final int panelH;
    private volatile int viewLeft;
    private volatile int viewTop;
    private volatile EyeTracker.State eye;

    private int uOrigin;
    private int uShift;
    private int uBoost;
    private int uContrast;

    private int program;
    private int aPos;
    private int uTex;
    private int uSize;
    private int uPitch;
    private int uSlant;
    private int uPhase;
    private int uEye;
    private int uK;
    private int uRef;
    private int uS;
    private int uS0;
    private int uSwap;
    private int uWeave;
    private int weaveTexture;
    private FloatBuffer quad;

    /**
     * @param panelW 패널 가로 화소 (가로 방향 기준)
     * @param panelH 패널 세로 화소
     */
    GlassWeaver(Settings settings, float pixelMm, int panelW, int panelH) {
        this(settings, pixelMm, panelW, panelH, false);
    }

    GlassWeaver(Settings settings, float pixelMm, int panelW, int panelH, boolean external) {
        this.external = external;
        this.settings = settings;
        this.pixelMm = pixelMm;
        this.panelW = panelW;
        this.panelH = panelH;
    }

    /** 창(GL 뷰)의 왼쪽 위가 패널에서 어디인지. 카메라 구멍을 피해 창이 밀려 있을 수 있다. */
    void setPlacement(int left, int top) {
        viewLeft = left;
        viewTop = top;
    }

    /** 도구에서 값을 바꾸고 돌아왔을 때 새로 읽은 값으로 바꾼다. */
    void setSettings(Settings s) {
        settings = s;
    }

    /** GL thread, before draw(): the SurfaceTexture transform of the current frame. */
    void setTexMatrix(float[] m) {
        System.arraycopy(m, 0, texMatrix, 0, 16);
    }

    /**
     * GL thread, before draw() (external only). texture 0: the picture is side-by-side.
     * Otherwise it is a 2D picture and each eye is shifted by amount per unit of depth
     * (in picture widths; 0 = flat 2D while the depth is not ready).
     */
    void setAiDepth(int texture, float amount) {
        depthTexture = texture;
        depthAmount = amount;
    }

    /** GL thread, before draw() (external only): see srcRect / dstRect. */
    void setRegion(float[] src, float[] dst) {
        System.arraycopy(src, 0, srcRect, 0, 4);
        System.arraycopy(dst, 0, dstRect, 0, 4);
    }

    /** 추적기의 최신 눈 위치. 아무 스레드에서나. */
    void setEye(EyeTracker.State s) {
        eye = s;
    }

    @Override
    public void onSurfaceCreated() {
        program = buildProgram(VERTEX, fragment(external));
        uTexM = external ? GLES20.glGetUniformLocation(program, "uTexM") : -1;
        uSrc = external ? GLES20.glGetUniformLocation(program, "uSrc") : -1;
        uDst = external ? GLES20.glGetUniformLocation(program, "uDst") : -1;
        uAi = external ? GLES20.glGetUniformLocation(program, "uAi") : -1;
        uDepth = external ? GLES20.glGetUniformLocation(program, "uDepth") : -1;
        uDepthAmt = external ? GLES20.glGetUniformLocation(program, "uDepthAmt") : -1;
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        uSize = GLES20.glGetUniformLocation(program, "uSize");
        uPitch = GLES20.glGetUniformLocation(program, "uPitch");
        uSlant = GLES20.glGetUniformLocation(program, "uSlant");
        uPhase = GLES20.glGetUniformLocation(program, "uPhase");
        uEye = GLES20.glGetUniformLocation(program, "uEye");
        uK = GLES20.glGetUniformLocation(program, "uK");
        uRef = GLES20.glGetUniformLocation(program, "uRef");
        uS = GLES20.glGetUniformLocation(program, "uS");
        uS0 = GLES20.glGetUniformLocation(program, "uS0");
        uSwap = GLES20.glGetUniformLocation(program, "uSwap");
        uWeave = GLES20.glGetUniformLocation(program, "uWeave");
        uOrigin = GLES20.glGetUniformLocation(program, "uOrigin");
        uShift = GLES20.glGetUniformLocation(program, "uShift");
        uBoost = GLES20.glGetUniformLocation(program, "uBoost");
        uContrast = GLES20.glGetUniformLocation(program, "uContrast");

        float[] verts = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        quad = ByteBuffer.allocateDirect(verts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(verts).position(0);

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        weaveTexture = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, weaveTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, WeaveTable.SIZE, 2, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, WeaveTable.texture());
    }

    @Override
    public void draw(int sbsTexture, int width, int height) {
        Settings settings = this.settings;
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(external ? GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D, sbsTexture);
        GLES20.glUniform1i(uTex, 0);
        if (external) {
            GLES20.glUniformMatrix4fv(uTexM, 1, false, texMatrix, 0);
            GLES20.glUniform4fv(uSrc, 1, srcRect, 0);
            GLES20.glUniform4fv(uDst, 1, dstRect, 0);
            // The depth map has unit 2 to itself (unit 0 is the external picture, 1 the weave).
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture);
            GLES20.glUniform1i(uDepth, 2);
            GLES20.glUniform1i(uAi, depthTexture != 0 ? 1 : 0);
            GLES20.glUniform1f(uDepthAmt, depthAmount);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, weaveTexture);
        GLES20.glUniform1i(uWeave, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        // 렌즈와 눈은 패널 화소 기준 (3D 맞추기 도구와 같은 기준). 창이 카메라 구멍 옆에서
        // 시작하면 그만큼 원점을 더한다.
        int pw = panelW > 0 ? panelW : width;
        int ph = panelH > 0 ? panelH : height;
        float ox = viewLeft;
        float oy = ph - (viewTop + height);
        GLES20.glUniform2f(uOrigin, ox, oy);
        // 그림은 창 가운데에 들어 있다 — 패널 정중앙으로 옮겨 양쪽 여백을 같게 한다.
        float shiftX = (pw / 2f - viewLeft) - width / 2f;
        float shiftY = (ph / 2f - oy) - height / 2f;
        GLES20.glUniform2f(uShift, shiftX, shiftY);
        GLES20.glUniform1f(uBoost, settings.boost);
        GLES20.glUniform1f(uContrast, settings.contrast);

        GLES20.glUniform2f(uSize, width, height);
        GLES20.glUniform1f(uPitch, settings.pitch);
        GLES20.glUniform1f(uSlant, settings.slant);
        GLES20.glUniform1f(uPhase, settings.phase);
        float k = 0f;
        float st = 0f;
        float st0 = 0f;
        float ex = 0f;
        float ey = 0f;
        EyeTracker.State e = eye;
        if (settings.tracking && e != null && e.valid && e.zMm > 50f) {
            float mm = pixelMm;
            k = settings.gap / (e.zMm / mm);
            st = Settings.STRETCH_GAP / (e.zMm / mm);
            st0 = Settings.STRETCH_GAP / (Settings.STRETCH_REFERENCE_MM / mm);
            ex = pw / 2f + e.xMm / mm;
            ey = ph / 2f + e.yMm * settings.trackY / mm;
        }
        GLES20.glUniform2f(uEye, ex, ey);
        GLES20.glUniform1f(uK, k);
        GLES20.glUniform2f(uRef, pw / 2f, ph / 2f);
        GLES20.glUniform1f(uS, st);
        GLES20.glUniform1f(uS0, st0);
        GLES20.glUniform1i(uSwap, settings.swap ? 1 : 0);

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            Log.e(TAG, "link failed: " + log);
            throw new IllegalStateException("link failed: " + log);
        }
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            Log.e(TAG, "compile failed: " + log);
            throw new IllegalStateException("compile failed: " + log);
        }
        return s;
    }
}
