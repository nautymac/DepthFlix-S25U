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
        DisplayMetrics m = new DisplayMetrics();
        a.getWindowManager().getDefaultDisplay().getRealMetrics(m);
        pixelMm = 25.4f / m.xdpi;
        Log.i(TAG, String.format(Locale.US, "display %dx%d xdpi %.1f -> pixel %.4f mm",
                m.widthPixels, m.heightPixels, m.xdpi, pixelMm));
        settings = new Settings(a);
        weaver = new GlassWeaver(settings, pixelMm);
        view.setWeaver(weaver);

        // 보면서 바로 맞추는 위상·추적 세기 조절 (오른쪽 위 "3D 조절").
        tuner = new GlassTuner(a, settings.phase, settings.gap, Settings.MIN_GAP, Settings.MAX_GAP,
                new GlassTuner.Listener() {
                    @Override public void onTune(float phase, float gap) {
                        Settings s = settings;
                        s.phase = phase;
                        s.gap = gap;
                        s.save();
                        Stereo3DView v = gl;
                        if (v != null) v.requestRender();
                    }
                });
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
                tuner.setValues(settings.phase, settings.gap);
                if (tuner.getParent() == null) {
                    activity.addContentView(tuner, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
            }
        }
        updateTracking();
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
