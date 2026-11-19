package com.nauty.p3d.glass;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;

import com.nauty.p3d.gl.Stereo3DView;
import com.nauty.p3d.panel.PanelBackend;

import java.util.Locale;

/**
 * 갤럭시 S25 Ultra + SpatialGlass 필름.
 *
 * Leia 기기와 달리 패널이 스스로 3D 를 만들지 않는다 — 필름은 그냥 렌즈라서, 마지막
 * 위빙({@link GlassWeaver})과 눈 추적({@link EyeTracker}, MediaPipe 홍채)을 우리가 한다.
 * 렌즈 값은 "3D 맞추기" 도구({@link GlassToolActivity})에서 맞춘 것을 같이 쓴다.
 *
 * {@code Panel.create()} 가 이름으로 만든다 (glass 소스셋에만 있는 클래스라서).
 */
public final class GlassBackend implements PanelBackend {
    private static final String TAG = "GlassBackend";
    private static final int REQ_CAMERA = 7301;

    private Activity activity;
    private Stereo3DView gl;
    private GlassWeaver weaver;
    private volatile Settings settings;
    private GlassTuner tuner;
    private EyeTracker tracker;
    private float pixelMm;
    private boolean threeD = true;
    private boolean resumed;
    private boolean askedCamera;

    // ---- AI depth (2D -> 3D by a depth model on the NPU) ----
    private DepthEngine depth;
    /** AI depth on/off, one choice for every video and photo (default on). */
    private static final String AI_PREFS = "aidepth";
    private static final String AI_ON = "on";

    public GlassBackend() {
    }

    @Override
    public View outputView(Activity a) {
        return null;   // 우리 GLSurfaceView 가 그대로 화면이다
    }

    @Override
    public void whenReady(Runnable r) {
        r.run();
    }

    @Override
    public void attach(Activity a, Stereo3DView view) {
        activity = a;
        gl = view;

        // 창은 카메라 구멍 옆에서 시작할 수 있다. 위빙과 그림 위치는 패널 화소 기준으로
        // 맞추므로(GlassWeaver), 창이 패널 어디에 있는지 레이아웃이 바뀔 때마다 알려 준다.
        view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                int[] at = new int[2];
                v.getLocationOnScreen(at);
                if (weaver != null) weaver.setPlacement(at[0], at[1]);
            }
        });
        DisplayMetrics m = new DisplayMetrics();
        a.getWindowManager().getDefaultDisplay().getRealMetrics(m);
        pixelMm = 25.4f / m.xdpi;
        Log.i(TAG, String.format(Locale.US, "display %dx%d xdpi %.1f -> pixel %.4f mm",
                m.widthPixels, m.heightPixels, m.xdpi, pixelMm));
        settings = new Settings(a);
        weaver = new GlassWeaver(settings, pixelMm,
                Math.max(m.widthPixels, m.heightPixels), Math.min(m.widthPixels, m.heightPixels));
        view.setWeaver(weaver);

        // 보면서 바로 맞추는 위상·추적 세기 조절 (오른쪽 위 "3D 조절").
        tuner = new GlassTuner(a, settings.phase, settings.gap, settings.boost, settings.contrast,
                Settings.MIN_GAP, Settings.MAX_GAP,
                new GlassTuner.Listener() {
                    @Override public void onTune(float phase, float gap, float boost, float contrast) {
                        Settings s = settings;
                        s.phase = phase;
                        s.gap = gap;
                        s.boost = boost;
                        s.contrast = contrast;
                        s.save();
                        Stereo3DView v = gl;
                        if (v != null) v.requestRender();
                    }
                });
        setupAiDepth(a, view);
        // No line-up button on this bar: it made the bar too big over the picture. The
        // line-up lives in the 3D Fit tool (the "3D 맞추기" button on the list screen).
        // 화면에 붙이는 건 onResume 에서 한다: PlayerActivity 는 attach 뒤에
        // setContentView 를 하므로 지금 붙이면 그때 떨어져 나간다.
        tuner.setVisibility(threeD ? View.VISIBLE : View.GONE);
        tracker = new EyeTracker(a, settings, new EyeTracker.Listener() {
            @Override public void onUpdate(EyeTracker.State s) {
                weaver.setEye(s);
                Stereo3DView v = gl;
                if (v != null) v.requestRender();
            }
        });
    }

    @Override
    public void setThreeD(boolean on) {
        threeD = on;
        if (tuner != null) {
            tuner.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        updateTracking();
    }

    @Override
    public void setMonoSource(boolean mono) {
        // 2D 소스도 Stereo3DView 가 시어로 SBS 를 만들어 주므로 여기서 할 일이 없다.
    }

    @Override
    public void setConversionStrength(float v) {
        // 시어 세기는 Stereo3DView 가 직접 받는다.
    }

    @Override
    public void onResume() {
        resumed = true;
        if (activity != null && weaver != null) {
            // 도구에서 값을 바꾸고 돌아왔을 수 있다.
            settings = new Settings(activity);
            weaver.setSettings(settings);
            if (tuner != null) {
                tuner.setValues(settings.phase, settings.gap, settings.boost, settings.contrast);
                if (tuner.getParent() == null) {
                    activity.addContentView(tuner, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
            }
        }
        updateTracking();
    }

    /**
     * AI depth: 2D videos and photos get their 3D from Depth Anything V2 on the NPU, unless
     * the viewer switches it off (the AI depth button on the player's bar). Until the model is ready, or if it cannot
     * run, the picture uses the tilt ramp.
     */
    private void setupAiDepth(final Activity a, Stereo3DView view) {
        depth = new DepthEngine(a, new DepthEngine.Listener() {
            @Override public void onStatus(String text) {
                if (text != null) Log.i(TAG, "AI depth: " + text);
            }

            @Override public void onDepth() {
                Stereo3DView v = gl;
                if (v != null) v.requestRender();
            }
        });
        view.setDepthProvider(depth);
        depth.setModel(a.getSharedPreferences(AI_PREFS, Activity.MODE_PRIVATE).getBoolean(AI_ON, true)
                ? DepthEngine.Model.DA2 : null);
    }

    // The switch is the player's bar button (PanelBackend). Off: 2D pictures use the tilt.
    @Override
    public boolean hasAiDepth() {
        return depth != null;
    }

    @Override
    public boolean isAiDepthOn() {
        return depth != null && depth.model() != null;
    }

    @Override
    public void setAiDepthOn(boolean on) {
        if (depth == null || activity == null) return;
        activity.getSharedPreferences(AI_PREFS, Activity.MODE_PRIVATE).edit()
                .putBoolean(AI_ON, on).apply();
        depth.setModel(on ? DepthEngine.Model.DA2 : null);
        Stereo3DView v = gl;
        if (v != null) v.requestRender();
    }

    @Override
    public void onPause() {
        resumed = false;
        updateTracking();
    }

    @Override
    public void onDestroy() {
        resumed = false;
        updateTracking();
        if (gl != null) gl.setWeaver(null);
        if (gl != null) gl.setDepthProvider(null);
        if (depth != null) depth.close();
        depth = null;
        gl = null;
        activity = null;
    }

    /** 화면에 3D 가 나가는 동안만 카메라를 켠다. */
    private void updateTracking() {
        if (tracker == null || activity == null) return;
        if (!(resumed && threeD)) {
            tracker.stop();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // 권한 창이 닫히면 액티비티가 다시 onResume 되므로 그때 이어서 켜진다.
            if (!askedCamera) {
                askedCamera = true;
                activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
            return;
        }
        Display d = activity.getWindowManager().getDefaultDisplay();
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        int rot;
        switch (d.getRotation()) {
            case Surface.ROTATION_90: rot = 90; break;
            case Surface.ROTATION_180: rot = 180; break;
            case Surface.ROTATION_270: rot = 270; break;
            default: rot = 0; break;
        }
        tracker.start(rot, m.widthPixels * pixelMm, m.heightPixels * pixelMm);
    }
}
