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

    private static final String FRAGMENT =
            "precision highp float;\n"
            + "uniform sampler2D uTex;\n"     // SBS: 왼쪽 절반 좌안, 오른쪽 절반 우안
            + "uniform vec2 uSize;\n"         // 화면 픽셀
            + "uniform float uPitch;\n"
            + "uniform float uSlant;\n"
            + "uniform float uPhase;\n"
            + "uniform vec2 uEye;\n"
            + "uniform float uK;\n"
            + "uniform vec2 uRef;\n"
            + "uniform float uK0;\n"
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
            + "  vec2 q = p + uEye * uK - uRef * uK0 - p * (uK - uK0);\n"
            + "  return fract((q.x + q.y * uSlant) / uPitch + uPhase);\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "  vec2 uv = (gl_FragCoord.xy - uShift) / uSize;\n"
            + "  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {\n"
            + "    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  vec3 l = tone(texture2D(uTex, vec2(uv.x * 0.5, uv.y)).rgb);\n"
            + "  vec3 r = tone(texture2D(uTex, vec2(0.5 + uv.x * 0.5, uv.y)).rgb);\n"
            + "  float ph = phaseAt();\n"
            + "  vec3 wl = texture2D(uWeave, vec2(ph, 0.25)).rgb;\n"
            + "  vec3 wr = texture2D(uWeave, vec2(ph, 0.75)).rgb;\n"
            + "  if (uSwap == 1) { vec3 t = wl; wl = wr; wr = t; }\n"
            + "  gl_FragColor = vec4(wl * l + wr * r, 1.0);\n"
            + "}\n";

    private volatile Settings settings;
    private final float pixelMm;
    private final int panelW;
    private final int panelH;
    private volatile int viewLeft;
    private volatile int viewTop;
    private volatile EyeTracker.State eye;
    /** Learned phase correction by eye position; null = none. */
    private volatile PhaseLearner learner;

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
    private int uK0;
    private int uSwap;
    private int uWeave;
    private int weaveTexture;
    private FloatBuffer quad;

    /**
     * @param panelW 패널 가로 화소 (가로 방향 기준)
     * @param panelH 패널 세로 화소
     */
    GlassWeaver(Settings settings, float pixelMm, int panelW, int panelH) {
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

    void setLearner(PhaseLearner l) {
        learner = l;
    }

    /** 도구에서 값을 바꾸고 돌아왔을 때 새로 읽은 값으로 바꾼다. */
    void setSettings(Settings s) {
        settings = s;
    }

    /** 추적기의 최신 눈 위치. 아무 스레드에서나. */
    void setEye(EyeTracker.State s) {
        eye = s;
    }

    @Override
    public void onSurfaceCreated() {
        program = buildProgram(VERTEX, FRAGMENT);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        uSize = GLES20.glGetUniformLocation(program, "uSize");
        uPitch = GLES20.glGetUniformLocation(program, "uPitch");
        uSlant = GLES20.glGetUniformLocation(program, "uSlant");
        uPhase = GLES20.glGetUniformLocation(program, "uPhase");
        uEye = GLES20.glGetUniformLocation(program, "uEye");
        uK = GLES20.glGetUniformLocation(program, "uK");
        uRef = GLES20.glGetUniformLocation(program, "uRef");
        uK0 = GLES20.glGetUniformLocation(program, "uK0");
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
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sbsTexture);
        GLES20.glUniform1i(uTex, 0);
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
        // Base phase plus what the on-phone learner (PhaseLearner) has learned for where the
        // eyes are now. Zero until the viewer has adjusted something.
        float learned = 0f;
        EyeTracker.State le = eye;
        if (learner != null && settings.tracking && le != null && le.valid && le.zMm > 50f) {
            learned = learner.correction(le.xMm, le.yMm, le.zMm);
        }
        GLES20.glUniform1f(uPhase, settings.phase + learned);
        float k = 0f;
        float k0 = 0f;
        float ex = 0f;
        float ey = 0f;
        EyeTracker.State e = eye;
        if (settings.tracking && e != null && e.valid && e.zMm > 50f) {
            float mm = pixelMm;
            k = settings.gap / (e.zMm / mm);
            k0 = settings.gap / (REFERENCE_DISTANCE_MM / mm);
            ex = pw / 2f + e.xMm / mm;
            ey = ph / 2f + e.yMm * settings.trackY / mm;
        }
        GLES20.glUniform2f(uEye, ex, ey);
        GLES20.glUniform1f(uK, k);
        GLES20.glUniform2f(uRef, pw / 2f, ph / 2f);
        GLES20.glUniform1f(uK0, k0);
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
