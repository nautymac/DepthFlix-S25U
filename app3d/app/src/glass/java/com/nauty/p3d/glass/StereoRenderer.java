package com.nauty.p3d.glass;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws a side-by-side image through the film: every physical pixel picks the
 * left or right eye from where it sits under the lens.
 *
 * The lens is modelled as stripes with a period of {@code pitch} pixels,
 * shifted {@code slant} pixels per row, and offset by {@code phase} of a
 * period. A pixel in the first half of a period shows the left eye, the second
 * half the right eye. The weave works in screen pixels (gl_FragCoord), after the
 * image has been scaled to the screen, so it stays pixel-exact at any image size.
 *
 * Eye tracking: the lens sits a gap G above the pixels, so the eye at E, a
 * distance Z away, sees through each lens the pixel at
 * {@code p = c (1 + G/Z) - E G/Z}. Weaving in the coordinate
 * {@code q = (p + E k) / (1 + k)}, with {@code k = G/Z}, keeps every eye on the
 * same stripe phase wherever the head is.
 */
final class StereoRenderer implements GLSurfaceView.Renderer {
    interface SizeListener {
        void onSizes(int viewW, int viewH, int imageW, int imageH);
    }

    private static final String TAG = "StereoRenderer";
    /** Eye position the calibration stands for: straight ahead, 400 mm (as Glass asks). */
    private static final float REFERENCE_DISTANCE_MM = 400f;

    private static final String VERTEX =
            "attribute vec2 aPos;\n"
            + "void main() { gl_Position = vec4(aPos, 0.0, 1.0); }\n";

    private static final String FRAGMENT =
            "precision highp float;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec4 uContent;\n"   // x, y, w, h in pixels, bottom-left origin
            + "uniform float uPitch;\n"
            + "uniform float uSlant;\n"
            + "uniform float uPhase;\n"
            + "uniform vec2 uEye;\n"       // eye position on the screen plane, pixels
            + "uniform float uK;\n"        // gap / eye distance; 0 = no tracking
            + "uniform vec2 uRef;\n"       // reference eye position (screen centre), pixels
            + "uniform float uK0;\n"       // gap / reference distance
            + "uniform int uMode;\n"       // 0 = 3D, 1 = original, 2 = red/blue test, 3 = red/black calibration
            + "uniform int uSwap;\n"
            + "uniform int uHasImage;\n"
            + "uniform sampler2D uWeave;\n" // WeaveTable: row 0 left weights (rgb), row 1 right
            + "uniform float uShift;\n"    // depth: views pushed apart, fraction of the eye width
            + "uniform float uBoost;\n"    // 3D brightness lift, 1 = none (see GlassWeaver.tone)
            + "uniform float uContrast;\n" // 3D contrast, 1 = none
            + "\n"
            + "vec3 tone(vec3 c) {\n"
            + "  c = 1.0 - pow(1.0 - clamp(c, 0.0, 1.0), vec3(uBoost));\n"
            + "  return clamp((c - 0.5) * uContrast + 0.5, 0.0, 1.0);\n"
            + "}\n"
            + "\n"
            + "vec3 sampleImage(vec2 t) { return texture2D(uTex, vec2(t.x, 1.0 - t.y)).rgb; }\n"
            + "\n"
            // Same lookup for full and half SBS: only the on-screen aspect differs.
            // Like Glass's depth setting, the left view moves left and the right view right.
            + "vec3 sampleEye(vec2 uv, float eye) {\n"
            + "  float x = clamp(uv.x + (eye < 0.5 ? uShift : -uShift), 0.0, 1.0);\n"
            + "  return sampleImage(vec2((x + eye) * 0.5, uv.y));\n"
            + "}\n"
            + "\n"
            // Lens phase of the (sub)pixel. The calibration holds for the reference eye
            // (straight ahead at the reference distance); tracking only adds the change
            // from there: q = p + (E - p) k - (C - p) k0.
            + "float phaseAt(float dx) {\n"
            + "  vec2 p = gl_FragCoord.xy + vec2(dx, 0.0);\n"
            + "  vec2 q = p + uEye * uK - uRef * uK0 - p * (uK - uK0);\n"
            + "  return fract((q.x + q.y * uSlant) / uPitch + uPhase);\n"
            + "}\n"
            + "\n"
            // Spatial Glass's own weave, measured per channel (see WeaveTable): the pixel's
            // lens phase picks how much left and right each of R, G, B shows.
            + "vec3 weave(vec3 l, vec3 r) {\n"
            + "  float ph = phaseAt(0.0);\n"
            + "  vec3 wl = texture2D(uWeave, vec2(ph, 0.25)).rgb;\n"
            + "  vec3 wr = texture2D(uWeave, vec2(ph, 0.75)).rgb;\n"
            + "  if (uSwap == 1) { vec3 t = wl; wl = wr; wr = t; }\n"
            + "  return wl * l + wr * r;\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "  if (uMode == 2) {\n"
            + "    gl_FragColor = vec4(weave(vec3(1.0, 0.0, 0.0), vec3(0.0, 0.35, 1.0)), 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  if (uMode == 3) {\n"
            + "    gl_FragColor = vec4(weave(vec3(1.0, 0.0, 0.0), vec3(0.0)), 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  vec2 uv = (gl_FragCoord.xy - uContent.xy) / uContent.zw;\n"
            + "  if (uHasImage == 0 || uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {\n"
            + "    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  if (uMode == 1) {\n"
            + "    gl_FragColor = vec4(sampleImage(uv), 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  gl_FragColor = vec4(weave(tone(sampleEye(uv, 0.0)), tone(sampleEye(uv, 1.0))), 1.0);\n"
            + "}\n";

    private final Settings settings;
    private GLSurfaceView view;
    private SizeListener sizeListener;

    private volatile Bitmap pending;
    private volatile EyeTracker.State eye;
    /** Learned phase correction by eye position (PhaseLearner); null = none. */
    private volatile PhaseLearner learner;

    void setLearner(PhaseLearner l) {
        learner = l;
    }
    private volatile float pixelMm = 0.05f;
    private int program;
    private int texture;
    private FloatBuffer quad;
    private int imageW;
    private int imageH;
    private boolean hasImage;
    private int viewW;
    private int viewH;

    private int aPos;
    private int uTex;
    private int uContent;
    private int uPitch;
    private int uSlant;
    private int uPhase;
    private int uEye;
    private int uK;
    private int uRef;
    private int uK0;
    private int uMode;
    private int uSwap;
    private int uHasImage;
    private int uWeave;
    private int uShift;
    private int uBoost;
    private int uContrast;
    private int weaveTexture;

    /**
     * Glass's "Medium depth": each view moved 7.5 px (of a 1560 px eye) away from the
     * other, measured on the same game picture.
     */
    private static final float DEPTH_SHIFT = 7.5f / 1560f;

    StereoRenderer(Settings settings) {
        this.settings = settings;
    }

    void attach(GLSurfaceView view, SizeListener listener) {
        this.view = view;
        this.sizeListener = listener;
    }

    /** Shows {@code bitmap}; it is uploaded on the GL thread at the next frame. */
    void setImage(Bitmap bitmap) {
        pending = bitmap;
        requestRender();
    }

    /** Latest eye position from the tracker; any thread. */
    void setEye(EyeTracker.State state) {
        eye = state;
        requestRender();
    }

    /** Physical size of one screen pixel, in mm. */
    void setPixelMm(float mm) {
        pixelMm = mm;
    }

    void requestRender() {
        if (view != null) {
            view.requestRender();
        }
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        program = buildProgram(VERTEX, FRAGMENT);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        uContent = GLES20.glGetUniformLocation(program, "uContent");
        uPitch = GLES20.glGetUniformLocation(program, "uPitch");
        uSlant = GLES20.glGetUniformLocation(program, "uSlant");
        uPhase = GLES20.glGetUniformLocation(program, "uPhase");
        uEye = GLES20.glGetUniformLocation(program, "uEye");
        uK = GLES20.glGetUniformLocation(program, "uK");
        uRef = GLES20.glGetUniformLocation(program, "uRef");
        uK0 = GLES20.glGetUniformLocation(program, "uK0");
        uMode = GLES20.glGetUniformLocation(program, "uMode");
        uSwap = GLES20.glGetUniformLocation(program, "uSwap");
        uHasImage = GLES20.glGetUniformLocation(program, "uHasImage");
        uWeave = GLES20.glGetUniformLocation(program, "uWeave");
        uShift = GLES20.glGetUniformLocation(program, "uShift");
        uBoost = GLES20.glGetUniformLocation(program, "uBoost");
        uContrast = GLES20.glGetUniformLocation(program, "uContrast");

        float[] verts = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        quad = ByteBuffer.allocateDirect(verts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(verts).position(0);

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texture = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        hasImage = false;

        // Weave table: power-of-two width so the phase can wrap with GL_REPEAT.
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
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        viewW = width;
        viewH = height;
        GLES20.glViewport(0, 0, width, height);
        reportSizes();
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        Bitmap b = pending;
        if (b != null) {
            pending = null;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            imageW = b.getWidth();
            imageH = b.getHeight();
            hasImage = true;
            reportSizes();
        }

        // Calibration steps show the red/black pattern deliberately detuned in the
        // OTHER value, so stripes are always visible and the user only has to judge
        // their direction:
        // Stripe width matches Spatial Glass: thick bars for horizontal, thin lines for vertical.
        //  - step 0 (horizontal): slant pushed off by pitch/480 px per row, giving bars
        //    ~480 px apart. They run exactly horizontal when, and only when, the pitch
        //    is right.
        //  - step 1 (vertical): pitch pushed off by pitch²/120, giving lines ~120 px
        //    apart. They run exactly vertical when, and only when, the slant is right
        //    (slant is scaled with the detuned pitch so the answer is not biased).
        int mode = settings.mode;
        float pitch = settings.pitch;
        float slant = settings.slant;
        int cal = settings.calStep;
        if (cal == 0) {
            mode = Settings.MODE_CAL;
            slant = slant + pitch / 480f;
        } else if (cal == 1) {
            mode = Settings.MODE_CAL;
            float detuned = pitch + pitch * pitch / 120f;
            slant = slant * detuned / pitch;
            pitch = detuned;
        } else if (cal == 2) {
            mode = Settings.MODE_3D;
        }

        float aspect = 1f;
        if (hasImage) {
            // Full SBS (two whole frames side by side) is much wider than one picture;
            // half SBS keeps one picture's shape.
            float a = (float) imageW / imageH;
            boolean fullSbs = mode == Settings.MODE_3D && a > 2.2f;
            aspect = fullSbs ? a * 0.5f : a;
        }
        // 3D fills the screen like Glass does (scale to cover, crop the overflow);
        // the original view shows the whole picture.
        boolean cover = mode == Settings.MODE_3D;
        float cw;
        float ch;
        if ((aspect > (float) viewW / viewH) != cover) {
            cw = viewW;
            ch = viewW / aspect;
        } else {
            ch = viewH;
            cw = viewH * aspect;
        }

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniform4f(uContent, (viewW - cw) / 2f, (viewH - ch) / 2f, cw, ch);
        GLES20.glUniform1f(uPitch, pitch);
        GLES20.glUniform1f(uSlant, slant);
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
            ex = viewW / 2f + e.xMm / mm;
            ey = viewH / 2f + e.yMm * settings.trackY / mm;
        }
        GLES20.glUniform2f(uEye, ex, ey);
        GLES20.glUniform1f(uK, k);
        GLES20.glUniform2f(uRef, viewW / 2f, viewH / 2f);
        GLES20.glUniform1f(uK0, k0);
        GLES20.glUniform1i(uMode, mode);
        GLES20.glUniform1i(uSwap, settings.swap ? 1 : 0);
        GLES20.glUniform1i(uHasImage, hasImage ? 1 : 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, weaveTexture);
        GLES20.glUniform1i(uWeave, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1f(uShift, mode == Settings.MODE_3D ? DEPTH_SHIFT : 0f);
        GLES20.glUniform1f(uBoost, settings.boost);
        GLES20.glUniform1f(uContrast, settings.contrast);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
    }

    private void reportSizes() {
        if (sizeListener != null) {
            sizeListener.onSizes(viewW, viewH, imageW, imageH);
        }
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
