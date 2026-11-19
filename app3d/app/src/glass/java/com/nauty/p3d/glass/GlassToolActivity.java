package com.nauty.p3d.glass;

import com.nauty.p3d.R;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;

/**
 * SBS photo viewer for the 3D film, with eye tracking and a Spatial Glass style
 * manual calibration.
 *
 * Viewer: tap switches 3D / original, long press shows a small menu.
 * Calibration, three steps with one value each:
 *   1 가로 — red stripes parallel to the horizontal red line   (sets the lens pitch)
 *   2 세로 — red stripes parallel to the vertical red line     (sets the lens slant)
 *   3 이미지 — the 3D photo looks right                         (sets the lens phase)
 */
public class GlassToolActivity extends Activity {
    private static final String TAG = "StereoMain";
    private static final int REQ_PICK = 1;
    private static final int REQ_CAMERA = 2;
    private static final int MAX_IMAGE_WIDTH = 8192;

    private static final int[] STEP_TABS = {R.string.glass_step_1, R.string.glass_step_2, R.string.glass_step_3};
    private static final int[] STEP_HINTS = {R.string.glass_hint_1, R.string.glass_hint_2, R.string.glass_hint_3};
    /** Slider range and fine −/+ step for each calibration step. */
    private static final float[][] STEP_RANGE = {
            {1.30f, 1.75f, 0.0001f},   // pitch, px (Glass: 1.51217)
            {-0.40f, 0.02f, 0.0001f},  // slant, px per row (Glass: -0.18973)
            {0.0f, 1.0f, 0.01f},       // phase
    };
    private static final int SEEK_MAX = 10000;

    private Settings settings;
    private GLSurfaceView glView;
    private StereoRenderer renderer;
    private EyeTracker tracker;
    private TrackView trackView;
    private float pixelMm;
    private boolean askedCamera;
    private final Handler repeat = new Handler(Looper.getMainLooper());

    private View menu;
    private Button trackButton;
    /** The hidden tuning tools (see buildMenu). */
    private LinearLayout devRow;
    private boolean devMode = false;
    private volatile EyeTracker.State lastEye;
    private int samples;
    /** This 3D 맞추기 session's taps: x, y, z (mm), phase, gap, trackY. */
    private final java.util.ArrayList<float[]> fitSamples = new java.util.ArrayList<>();
    private TextView phaseBadge;
    private LinearLayout guidePanel;
    /** The same "3D tune" bar as the player (phase, strength, brightness, contrast). */
    private GlassTuner tuner;
    private final Runnable hideBadge = () -> phaseBadge.setVisibility(View.GONE);
    private final java.util.List<File> photos = new java.util.ArrayList<>();
    private int photoIndex;
    private Button prevButton;
    private Button nextButton;
    private float phaseAtDown;
    private float gapAtDown;

    private View calib;
    private View calTabRow;
    private View calMinus;
    private View calPlus;
    private View calBottom;
    private Button recExit;
    private final Button[] tabs = new Button[3];
    private TextView eyeView;
    private TextView hintView;
    private TextView valueView;
    private SeekBar seek;
    private View hLine;
    private View vLine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        w.setAttributes(lp);
        w.setDecorFitsSystemWindows(false);

        DisplayMetrics m = new DisplayMetrics();
        getDisplay().getRealMetrics(m);
        pixelMm = 25.4f / m.xdpi;
        Log.i(TAG, String.format(Locale.US, "display %dx%d xdpi %.1f -> pixel %.4f mm",
                m.widthPixels, m.heightPixels, m.xdpi, pixelMm));

        settings = new Settings(this);
        // Opens in 2D with a short guide on top; one tap switches to 3D (and hides the guide).
        settings.mode = Settings.MODE_2D;
        renderer = new StereoRenderer(settings);
        renderer.setPixelMm(pixelMm);
        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        renderer.attach(glView, null);

        trackView = new TrackView(this);
        trackView.setPixelMm(pixelMm);
        tracker = new EyeTracker(this, settings, s -> {
            lastEye = s;
            renderer.setEye(s);
            trackView.setState(s);
            if (settings.calStep >= 0) {
                runOnUiThread(() -> showEye(s));
            }
        });

        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (settings.calStep < 0) {
                    toggle3D();
                } else if (settings.calStep == 2) {
                    recordSample();
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (settings.calStep < 0) {
                    menu.setVisibility(menu.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                }
            }

            // While watching: drag left/right to nudge the lens phase (one screen width is a
            // whole period, ~30 px is 0.01), up/down to change the tracking strength (one
            // screen height is 4, so 0.1 is ~36 px; 0 switches the correction off).
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (settings.calStep >= 0 && settings.calStep < 2) {
                    return false;
                }
                if (e2.getPointerCount() >= 2) {
                    // Pitch and slant are what the line-up sets; a stray two-finger drag
                    // (trying to zoom, say) must not undo it. Only in the developer
                    // recording view (step 3) do two fingers fine-tune them.
                    if (settings.calStep != 2) {
                        return true;
                    }
                    // Two fingers, while looking at a real picture: left/right fine-tunes the
                    // lens period (whole width = 0.001 px), up/down the slant (whole height =
                    // 0.002). Same effect as calibration steps 1 and 2, but judged on content.
                    if (Math.abs(dx) >= Math.abs(dy)) {
                        settings.pitch = Math.max(1.3f, Math.min(1.75f,
                                settings.pitch - dx / glView.getWidth() * 0.001f));
                    } else {
                        settings.slant = Math.max(-0.4f, Math.min(0.02f,
                                settings.slant + dy / glView.getHeight() * 0.002f));
                    }
                    settings.save();
                    renderer.requestRender();
                    showLensBadge();
                    return true;
                }
                if (Math.abs(dx) >= Math.abs(dy)) {
                    float p = (settings.phase - dx / glView.getWidth()) % 1f;
                    settings.phase = p < 0 ? p + 1f : p;
                    if (settings.calStep == 2) {
                        syncSeek();
                    }
                } else {
                    // Strength stays in the range found to work on this film (whole height = 1.0).
                    settings.gap = Math.max(Settings.MIN_GAP, Math.min(Settings.MAX_GAP,
                            settings.gap + dy / glView.getHeight()));
                }
                settings.save();
                renderer.requestRender();
                showPhaseBadge();
                return true;
            }
        });
        glView.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                phaseAtDown = settings.phase;
                gapAtDown = settings.gap;
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                // A second finger means a two-finger pitch/slant adjustment: whatever the first
                // finger dragged before it landed was not meant as a phase or strength change.
                settings.phase = phaseAtDown;
                settings.gap = gapAtDown;
                settings.save();
                renderer.requestRender();
            }
            return gestures.onTouchEvent(e);
        });

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(glView, match());
        root.addView(trackView, match());
        menu = buildMenu();
        menu.setVisibility(View.GONE);
        root.addView(menu, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        calib = buildCalibration();
        calib.setVisibility(View.GONE);
        root.addView(calib, match());
        phaseBadge = label("", 12, true);
        phaseBadge.setBackgroundColor(0xE6181818);
        phaseBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        phaseBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = dp(8);
        root.addView(phaseBadge, bp);
        // How-to shown over the 2D picture. Not clickable, so a tap still reaches the
        // picture and switches to 3D. Text in 3D would split between the eyes, hence 2D only.
        // The panel itself is not clickable, so a tap on its text still reaches the picture
        // and switches to 3D; only the big button below takes its own taps.
        guidePanel = new LinearLayout(this);
        guidePanel.setOrientation(LinearLayout.VERTICAL);
        guidePanel.setBackgroundColor(0xE6181818);
        guidePanel.setPadding(dp(18), dp(14), dp(18), dp(14));
        TextView guideView = label(getString(R.string.glass_guide), 14, false);
        guideView.setLineSpacing(0f, 1.25f);
        guidePanel.addView(guideView);
        // The one thing a new user has to do: line up the lens (steps 1 and 2).
        Button lineCal = button(getString(R.string.glass_btn_line_cal), v -> startCalibration(0));
        lineCal.setTextSize(17);
        lineCal.setBackgroundColor(0xFF1E9E52);
        LinearLayout.LayoutParams lcp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lcp.topMargin = dp(12);
        guidePanel.addView(lineCal, lcp);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(dp(580),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(guidePanel, gp);
        // Top right, as in the player; shown only on the 3D picture (see updateGuide).
        tuner = new GlassTuner(this, settings.phase, settings.gap, settings.boost, settings.contrast,
                Settings.MIN_GAP, Settings.MAX_GAP, (phase, gap, boost, contrast) -> {
                    settings.phase = phase;
                    settings.gap = gap;
                    settings.boost = boost;
                    settings.contrast = contrast;
                    settings.save();
                    renderer.requestRender();
                });
        tuner.setVisibility(View.GONE);
        root.addView(tuner, match());
        setContentView(root);

        renderer.setImage(SampleImage.create());
        seedDefaultPhotos();
        photos.addAll(storedPhotos());
        refreshMenu();
        if (!openFromIntent(getIntent())) {
            if (!photos.isEmpty()) {
                // Pick up where the user left off; no need to choose photos again.
                photoIndex = Math.max(0, Math.min(photos.size() - 1,
                        getSharedPreferences("stereo", MODE_PRIVATE).getInt("photoIndex", 0)));
                loadPhoto(Uri.fromFile(photos.get(photoIndex)));
            }
            // A first-time user sees the 2D guide with its big line-up button rather than
            // being dropped straight into calibration.
        }
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_LINE_CAL, false)) {
            startCalibration(0);
        }
    }

    /** Opened from DepthFlix ("3D 맞추기" button or the tuning bar): go straight to the line-up. */
    static final String EXTRA_LINE_CAL = "line_cal";

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openFromIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_LINE_CAL, false)) {
            startCalibration(0);
        }
    }

    /** Opens the photo a gallery "open with" or "share" handed us. */
    private boolean openFromIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            java.util.ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            if (list == null || list.isEmpty()) {
                return false;
            }
            showPhotos(list);
            return true;
        }
        if (uri == null) {
            return false;
        }
        showPhotos(java.util.Collections.singletonList(uri));
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        startTracking();
    }

    @Override
    protected void onPause() {
        tracker.stop();
        glView.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    // ---- eye tracking ----

    private void startTracking() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!askedCamera) {
                askedCamera = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
            return;
        }
        DisplayMetrics m = new DisplayMetrics();
        getDisplay().getRealMetrics(m);
        int rot;
        switch (getDisplay().getRotation()) {
            case Surface.ROTATION_90: rot = 90; break;
            case Surface.ROTATION_180: rot = 180; break;
            case Surface.ROTATION_270: rot = 270; break;
            default: rot = 0; break;
        }
        tracker.start(rot, m.widthPixels * pixelMm, m.heightPixels * pixelMm);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_CAMERA && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startTracking();
        }
    }

    // ---- viewer ----

    /** Shows pitch and slant for a moment while they are being dragged with two fingers. */
    private void showLensBadge() {
        phaseBadge.setText(fmt(R.string.glass_lens_badge, settings.pitch, settings.slant));
        phaseBadge.setVisibility(View.VISIBLE);
        repeat.removeCallbacks(hideBadge);
        repeat.postDelayed(hideBadge, 1500);
    }

    /** Shows the phase at the bottom for a moment while it is being dragged. */
    private void showPhaseBadge() {
        syncTuner();
        phaseBadge.setText(fmt(R.string.glass_phase_badge, settings.phase, settings.gap));
        phaseBadge.setVisibility(View.VISIBLE);
        repeat.removeCallbacks(hideBadge);
        repeat.postDelayed(hideBadge, 1500);
    }

    private void toggle3D() {
        settings.mode = settings.mode == Settings.MODE_3D ? Settings.MODE_2D : Settings.MODE_3D;
        renderer.requestRender();
        Toast.makeText(this, settings.mode == Settings.MODE_3D ? "3D" : getString(R.string.glass_mode_original), Toast.LENGTH_SHORT).show();
        updateGuide();
    }

    /**
     * The long-press menu. Only what a user needs is on show; the tuning tools (position
     * recording with auto fit, sample picture, tracking overlay) sit in a second row that
     * appears only after holding "Close" for 3 seconds, so nobody stumbles into them.
     */
    private View buildMenu() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xE6202020);
        box.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout r = row();
        box.addView(r);
        devRow = row();
        devRow.setVisibility(View.GONE);
        box.addView(devRow);

        r.addView(button(getString(R.string.glass_btn_add), v -> pickPhoto()));
        prevButton = button(getString(R.string.glass_btn_prev), v -> stepPhoto(-1));
        r.addView(prevButton);
        nextButton = button(getString(R.string.glass_btn_next), v -> stepPhoto(1));
        r.addView(nextButton);
        r.addView(button(getString(R.string.glass_btn_line_cal), v -> startCalibration(0)));
        r.addView(button(getString(R.string.glass_btn_restore), v -> {
            // Back to the values recorded in CALIBRATION.md (the Settings defaults).
            settings.resetCalibration();
            settings.save();
            syncTuner();
            renderer.requestRender();
            Toast.makeText(this, fmt(R.string.glass_restored,
                    settings.phase, settings.gap, settings.pitch, settings.slant), Toast.LENGTH_LONG).show();
        }));
        Button close = button(getString(R.string.glass_btn_close), v -> menu.setVisibility(View.GONE));
        r.addView(close);
        // Holding Close for 3 s shows the developer row (a normal tap still just closes).
        final Runnable showDev = () -> {
            devMode = true;
            devRow.setVisibility(View.VISIBLE);
            if (tabs[2] != null) tabs[2].setVisibility(View.VISIBLE);
            Toast.makeText(this, R.string.glass_dev_on, Toast.LENGTH_SHORT).show();
        };
        close.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                repeat.postDelayed(showDev, 3000);
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                repeat.removeCallbacks(showDev);
            }
            return false;   // let the click through
        });

        // Developer row.
        devRow.addView(button(getString(R.string.glass_btn_fit), v -> startCalibration(2)));
        devRow.addView(button(getString(R.string.glass_btn_sample), v -> {
            renderer.setImage(SampleImage.create());
            settings.mode = Settings.MODE_3D;
            updateGuide();
            renderer.requestRender();
        }));
        trackButton = button("", v -> {
            settings.showTrack = !settings.showTrack;
            settings.save();
            refreshMenu();
        });
        devRow.addView(trackButton);
        return box;
    }

    private void refreshMenu() {
        int nav = photos.size() > 1 ? View.VISIBLE : View.GONE;
        prevButton.setVisibility(nav);
        nextButton.setVisibility(nav);
        trackButton.setText(getString(settings.showTrack ? R.string.glass_track_on : R.string.glass_track_off));
        trackView.setVisibility(settings.showTrack && settings.calStep < 0 ? View.VISIBLE : View.GONE);
    }

    // ---- calibration (Spatial Glass style) ----

    private void startCalibration() {
        startCalibration(0);
    }

    /** Opens calibration at {@code step}, keeping the photo on screen for the image step. */
    private void startCalibration(int step) {
        menu.setVisibility(View.GONE);
        calib.setVisibility(View.VISIBLE);
        trackView.setVisibility(View.GONE);
        settings.mode = Settings.MODE_3D;
        fitSamples.clear();
        samples = 0;
        showStep(step);
    }

    private void showStep(int step) {
        settings.calStep = step;
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setBackgroundColor(i == step ? 0xFF3D7BFF : 0xFF3A3A3A);
        }
        hintView.setText(STEP_HINTS[step]);
        hLine.setVisibility(step == 0 ? View.VISIBLE : View.GONE);
        vLine.setVisibility(step == 1 ? View.VISIBLE : View.GONE);
        // Step 3 is for watching the picture: keep only one small line at the top
        // (eye position, sample count, "끝"). Phase is dragged left/right, a tap records.
        boolean compact = step == 2;
        int full = compact ? View.GONE : View.VISIBLE;
        calTabRow.setVisibility(full);
        hintView.setVisibility(full);
        calMinus.setVisibility(full);
        calPlus.setVisibility(full);
        calBottom.setVisibility(full);
        recExit.setVisibility(compact ? View.VISIBLE : View.GONE);
        eyeView.setTextSize(compact ? 11 : 13);
        syncSeek();
        updateGuide();
        renderer.requestRender();
    }

    private void save() {
        settings.calibrated = true;
        settings.save();
        int step = settings.calStep;
        if (step == 1 && !devMode) {
            // Both lines are done: that is all a user needs. Straight to the 3D picture.
            exitCalibration();
            Toast.makeText(this, R.string.glass_cal_done, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, getString(R.string.glass_saved, getString(STEP_TABS[step])), Toast.LENGTH_SHORT).show();
        if (step < 2) {
            showStep(step + 1);
        }
    }

    private void exitCalibration() {
        if (settings.calStep == 2) {
            fitTracking();
        }
        settings.save();
        settings.calStep = -1;
        settings.mode = Settings.MODE_3D;
        calib.setVisibility(View.GONE);
        refreshMenu();
        updateGuide();
        renderer.requestRender();
    }

    /** The how-to shows only on the plain 2D picture, never over 3D or calibration. */
    private void updateGuide() {
        if (guidePanel == null) return;
        boolean show = settings.mode == Settings.MODE_2D && settings.calStep < 0;
        guidePanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (tuner != null) {
            boolean tune = settings.mode == Settings.MODE_3D && settings.calStep < 0;
            if (!tune) tuner.setOpen(false);
            else syncTuner();
            tuner.setVisibility(tune ? View.VISIBLE : View.GONE);
        }
    }

    /** The bar shows what a drag, the line-up or a reset has just set. */
    private void syncTuner() {
        if (tuner != null) {
            tuner.setValues(settings.phase, settings.gap, settings.boost, settings.contrast);
        }
    }

    private float value() {
        switch (settings.calStep) {
            case 0: return settings.pitch;
            case 1: return settings.slant;
            default: return settings.phase;
        }
    }

    private void setValue(float v) {
        float[] r = STEP_RANGE[settings.calStep];
        switch (settings.calStep) {
            case 0:
                settings.pitch = Math.max(r[0], Math.min(r[1], v));
                break;
            case 1:
                settings.slant = Math.max(r[0], Math.min(r[1], v));
                break;
            default:
                float p = v % 1f;
                settings.phase = p < 0 ? p + 1f : p;
                break;
        }
        renderer.requestRender();
    }

    private void nudge(int sign, int multiplier) {
        setValue(value() + sign * multiplier * STEP_RANGE[settings.calStep][2]);
        syncSeek();
    }

    private void syncSeek() {
        float[] r = STEP_RANGE[settings.calStep];
        seek.setProgress(Math.round((value() - r[0]) / (r[1] - r[0]) * SEEK_MAX));
        showValue();
    }

    private void showValue() {
        int[] ids = {R.string.glass_val_pitch, R.string.glass_val_slant, R.string.glass_val_phase};
        valueView.setText(fmt(ids[settings.calStep], value()));
    }

    private void showEye(EyeTracker.State s) {
        String rec = settings.calStep == 2
                ? fmt(R.string.glass_rec_line, settings.phase, samples)
                : "";
        if (s.error != null) {
            eyeView.setText(getString(R.string.glass_eye_error) + rec);
        } else if (s.found && settings.calStep == 2) {
            eyeView.setText(fmt(R.string.glass_eye_pos, s.xMm, s.yMm, s.zMm, rec));
        } else if (s.found) {
            eyeView.setText(fmt(R.string.glass_eye_dist, s.zMm));
        } else {
            eyeView.setText(getString(R.string.glass_eye_none) + rec);
        }
    }

    /**
     * Step 3: the user taps when the picture looks right. Each tap appends the eye
     * position and the weave settings to samples.csv (app files dir) and to the log,
     * so tracking strength, direction and phase can be fitted from several positions.
     */
    private void recordSample() {
        EyeTracker.State s = lastEye;
        if (s == null || !s.found) {
            Toast.makeText(this, R.string.glass_no_face, Toast.LENGTH_SHORT).show();
            return;
        }
        samples++;
        String line = String.format(Locale.US, "%d,%.1f,%.1f,%.1f,%.4f,%.3f,%.5f,%.5f,%b,%.3f",
                System.currentTimeMillis(), s.xMm, s.yMm, s.zMm, settings.phase, settings.gap,
                settings.pitch, settings.slant, settings.tracking, settings.trackY);
        Log.i("StereoSample", line);
        try (java.io.FileWriter fw = new java.io.FileWriter(
                new java.io.File(getExternalFilesDir(null), "samples.csv"), true)) {
            fw.write(line + "\n");
        } catch (java.io.IOException e) {
            Log.w(TAG, "sample write failed", e);
        }
        fitSamples.add(new float[] {s.xMm, s.yMm, s.zMm, settings.phase, settings.gap, settings.trackY});
        Toast.makeText(this, fmt(R.string.glass_recorded,
                samples, s.xMm, s.yMm, s.zMm, settings.phase), Toast.LENGTH_SHORT).show();
        showEye(s);
    }

    /**
     * Fits the tracking strength from this session's taps, each made after dragging the
     * phase until the picture looked right at that head position.
     *
     * At screen centre the shader's lens phase is phase + gap*(x + slant*trackY*y)/(z*pitch)
     * (see StereoRenderer.phaseAt). If the true strength is G, a good-looking tap means
     * phase_i + gap_i*w_i = c + G*w_i with w_i = (x_i + slant*trackY*y_i)/(z_i*pitch).
     * G and c are found by a circular least-squares search (phases wrap at 1), and become
     * the new strength and the phase for a head straight in front.
     */
    private void fitTracking() {
        int n = fitSamples.size();
        if (n == 0) {
            return;
        }
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        for (float[] f : fitSamples) {
            minX = Math.min(minX, f[0]);
            maxX = Math.max(maxX, f[0]);
        }
        if (n < 2 || maxX - minX < 30f) {
            Toast.makeText(this, R.string.glass_fit_need, Toast.LENGTH_LONG).show();
            return;
        }
        boolean phaseMoved = false;
        for (float[] f : fitSamples) {
            float d = Math.abs(f[3] - fitSamples.get(0)[3]);
            phaseMoved |= Math.min(d, 1f - d) > 0.005f || Math.abs(f[4] - fitSamples.get(0)[4]) > 0.01f;
        }
        if (!phaseMoved) {
            // Every tap kept the same setting: the current values already look right at all
            // of these positions, so there is nothing to change (and nothing to fit).
            Toast.makeText(this, fmt(R.string.glass_fit_same, n, settings.phase, settings.gap), Toast.LENGTH_LONG).show();
            return;
        }
        double[] w = new double[n];
        double[] obs = new double[n];
        for (int i = 0; i < n; i++) {
            float[] f = fitSamples.get(i);
            w[i] = (f[0] + settings.slant * f[5] * f[1]) / (f[2] * settings.pitch);
            obs[i] = f[3] + f[4] * w[i];
        }
        double bestG = settings.gap;
        double bestC = settings.phase;
        double bestCost = Double.MAX_VALUE;
        int steps = Math.round((Settings.MAX_GAP - Settings.MIN_GAP) * 100f);
        for (int step = 0; step <= steps; step++) {
            double g = Settings.MIN_GAP + step * 0.01;
            double cs = 0;
            double sn = 0;
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * (obs[i] - g * w[i]);
                cs += Math.cos(a);
                sn += Math.sin(a);
            }
            double c = Math.atan2(sn, cs) / (2 * Math.PI);
            double cost = 0;
            for (int i = 0; i < n; i++) {
                cost += 1 - Math.cos(2 * Math.PI * (obs[i] - g * w[i] - c));
            }
            if (cost < bestCost - 1e-9) {
                bestCost = cost;
                bestG = g;
                bestC = c;
            }
        }
        // Typical leftover error of a tap, in lens periods.
        double rms = Math.sqrt(bestCost / n / (2 * Math.PI * Math.PI));
        float oldGap = settings.gap;
        float oldPhase = settings.phase;
        float c = (float) (bestC % 1.0);
        settings.gap = (float) bestG;
        settings.phase = c < 0 ? c + 1f : c;
        String msg = fmt(R.string.glass_fit_result, n, oldGap, settings.gap, oldPhase, settings.phase, rms);
        Log.i("StereoSample", msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private View buildCalibration() {
        FrameLayout f = new FrameLayout(this);

        // Reference lines. Ordinary views, not woven, so both eyes see them whole.
        hLine = new View(this);
        hLine.setBackgroundColor(0xFFFF2020);
        f.addView(hLine, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6), Gravity.CENTER));
        vLine = new View(this);
        vLine.setBackgroundColor(0xFFFF2020);
        f.addView(vLine, new FrameLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // Top: step tabs, save, exit; eye distance; instructions. Dark backing so the text reads.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout tabRow = row();
        calTabRow = tabRow;
        tabRow.setBackgroundColor(0xE6181818);
        tabRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        for (int i = 0; i < 3; i++) {
            final int step = i;
            tabs[i] = button(getString(STEP_TABS[i]), v -> showStep(step));
            tabs[i].setTextSize(15);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(120), dp(44));
            p.setMargins(dp(4), 0, dp(4), 0);
            tabRow.addView(tabs[i], p);
        }
        // Step 3 (position recording) is a tuning tool: only with the developer menu on.
        tabs[2].setVisibility(devMode ? View.VISIBLE : View.GONE);
        View gap = new View(this);
        tabRow.addView(gap, new LinearLayout.LayoutParams(dp(40), 1));
        Button saveButton = button(getString(R.string.glass_btn_save), v -> save());
        saveButton.setTextSize(15);
        saveButton.setBackgroundColor(0xFF1E9E52);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(100), dp(44));
        sp.setMargins(dp(4), 0, dp(4), 0);
        tabRow.addView(saveButton, sp);
        Button exitButton = button(getString(R.string.glass_btn_exit), v -> exitCalibration());
        exitButton.setTextSize(15);
        exitButton.setBackgroundColor(0xFF3A3A3A);
        tabRow.addView(exitButton, new LinearLayout.LayoutParams(dp(100), dp(44)));
        top.addView(tabRow);

        // Eye readout, plus a small exit used in the compact recording view (step 3).
        LinearLayout eyeRow = row();
        eyeRow.setBackgroundColor(0xE6181818);
        eyeView = label(getString(R.string.glass_eye_none), 13, true);
        eyeView.setPadding(dp(10), dp(2), dp(10), dp(2));
        eyeRow.addView(eyeView);
        recExit = button(getString(R.string.glass_btn_done), v -> exitCalibration());
        recExit.setTextSize(11);
        recExit.setMinimumHeight(dp(28));
        eyeRow.addView(recExit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        top.addView(eyeRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        hintView = label("", 13, false);
        hintView.setGravity(Gravity.CENTER);
        hintView.setBackgroundColor(0xE6181818);
        hintView.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(620), ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(6);
        top.addView(hintView, hp);
        f.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        // Big −/+ at the screen edges, as in Spatial Glass. Hold to keep going; it speeds up.
        Button minus = holdButton("−", m -> nudge(-1, m));
        Button plus = holdButton("+", m -> nudge(1, m));
        calMinus = minus;
        calPlus = plus;
        f.addView(minus, new FrameLayout.LayoutParams(dp(90), dp(110), Gravity.START | Gravity.CENTER_VERTICAL));
        f.addView(plus, new FrameLayout.LayoutParams(dp(90), dp(110), Gravity.END | Gravity.CENTER_VERTICAL));

        // Bottom: value and slider for big moves.
        LinearLayout bottom = row();
        calBottom = bottom;
        bottom.setBackgroundColor(0xE6181818);
        bottom.setPadding(dp(12), dp(4), dp(12), dp(4));
        valueView = label("", 15, true);
        valueView.setMinWidth(dp(130));
        bottom.addView(valueView);
        seek = new SeekBar(this);
        seek.setMax(SEEK_MAX);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser && settings.calStep >= 0) {
                    float[] r = STEP_RANGE[settings.calStep];
                    setValue(r[0] + (r[1] - r[0]) * progress / SEEK_MAX);
                    showValue();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                settings.save();
            }
        });
        bottom.addView(seek, new LinearLayout.LayoutParams(dp(520), dp(44)));
        f.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        return f;
    }

    /** Acts once on press, then repeats while held, ten times bigger after a second. */
    @SuppressLint("ClickableViewAccessibility")
    private Button holdButton(String text, java.util.function.IntConsumer action) {
        Button b = button(text, null);
        b.setTextSize(40);
        b.setBackgroundColor(0xCC303030);
        int[] count = new int[1];
        Runnable[] again = new Runnable[1];
        again[0] = () -> {
            count[0]++;
            action.accept(count[0] > 15 ? 10 : 1);
            repeat.postDelayed(again[0], 70);
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    count[0] = 0;
                    action.accept(1);
                    repeat.postDelayed(again[0], 450);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    repeat.removeCallbacks(again[0]);
                    settings.save();
                    break;
                default:
                    break;
            }
            return true;
        });
        return b;
    }

    // ---- photo loading ----

    /** Lets the user pick one or many photos; with several, ◀ / ▶ in the menu steps through them. */
    private void pickPhoto() {
        Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES);
        i.setType("image/*");
        i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, Math.min(50, MediaStore.getPickImagesMaxLimit()));
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) {
            return;
        }
        java.util.ArrayList<Uri> list = new java.util.ArrayList<>();
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int k = 0; k < clip.getItemCount(); k++) {
                list.add(clip.getItemAt(k).getUri());
            }
        } else if (data.getData() != null) {
            list.add(data.getData());
        }
        showPhotos(list);
    }

    /**
     * Copies picked or shared photos into the app's own folder, so they stay available
     * (picker and share permissions are temporary) and need not be chosen again.
     */
    private void showPhotos(java.util.List<Uri> list) {
        if (list.isEmpty()) {
            return;
        }
        new Thread(() -> {
            java.util.List<File> added = new java.util.ArrayList<>();
            long stamp = System.currentTimeMillis();
            for (int k = 0; k < list.size(); k++) {
                File f = copyIn(list.get(k), stamp, k);
                if (f != null) {
                    added.add(f);
                }
            }
            runOnUiThread(() -> {
                if (added.isEmpty()) {
                    Toast.makeText(this, R.string.glass_photo_import_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                photos.clear();
                photos.addAll(storedPhotos());
                photoIndex = Math.max(0, photos.indexOf(added.get(0)));
                rememberIndex();
                loadPhoto(Uri.fromFile(photos.get(photoIndex)));
                refreshMenu();
                Toast.makeText(this, getString(R.string.glass_photos_added, added.size(), photos.size()), Toast.LENGTH_LONG).show();
            });
        }, "import").start();
    }

    private File photoDir() {
        File d = new File(getFilesDir(), "photos");
        if (!d.isDirectory() && !d.mkdirs()) {
            Log.w(TAG, "cannot create " + d);
        }
        return d;
    }

    /**
     * First run only: copies the pictures bundled under assets/default_photos/ into the
     * photo store, so the tool opens on a real 3D picture without anyone adding one. The
     * first file is the one it opens on (Stellar Blade). Only when the store is empty, so
     * nothing is duplicated; without the folder the built-in sample is shown.
     */
    private void seedDefaultPhotos() {
        android.content.SharedPreferences sp = getSharedPreferences("stereo", MODE_PRIVATE);
        if (sp.getBoolean("defaultPhotosSeeded", false)) {
            return;
        }
        sp.edit().putBoolean("defaultPhotosSeeded", true).apply();
        File[] have = photoDir().listFiles();
        if (have != null && have.length > 0) {
            return;   // the user already has pictures here; do not add copies
        }
        String[] names;
        try {
            names = getAssets().list("default_photos");
        } catch (java.io.IOException e) {
            return;
        }
        if (names == null || names.length == 0) {
            return;
        }
        java.util.Arrays.sort(names);
        for (int k = 0; k < names.length; k++) {
            // Stamp 0 sorts them ahead of anything the user adds later.
            File out = new File(photoDir(), String.format(Locale.US, "%013d_%02d_%s", 0L, k, names[k]));
            try (java.io.InputStream in = getAssets().open("default_photos/" + names[k]);
                 java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            } catch (java.io.IOException e) {
                Log.w(TAG, "default photo " + names[k], e);
            }
        }
        sp.edit().putInt("photoIndex", 0).apply();
    }

    private java.util.List<File> storedPhotos() {
        File[] files = photoDir().listFiles();
        java.util.List<File> out = new java.util.ArrayList<>();
        if (files != null) {
            java.util.Collections.addAll(out, files);
            out.sort((a, b) -> a.getName().compareTo(b.getName()));
        }
        return out;
    }

    private File copyIn(Uri uri, long stamp, int k) {
        String name = "photo.jpg";
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) {
                name = c.getString(0).replaceAll("[^A-Za-z0-9._-]", "_");
            }
        } catch (Exception ignored) {
            // keep the generic name
        }
        File out = new File(photoDir(), String.format(Locale.US, "%d_%02d_%s", stamp, k, name));
        try (InputStream in = getContentResolver().openInputStream(uri);
             java.io.OutputStream os = new java.io.FileOutputStream(out)) {
            if (in == null) {
                return null;
            }
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "copy failed: " + uri, e);
            if (!out.delete()) {
                Log.w(TAG, "could not remove partial " + out);
            }
            return null;
        }
    }

    private void rememberIndex() {
        getSharedPreferences("stereo", MODE_PRIVATE).edit().putInt("photoIndex", photoIndex).apply();
    }

    private void stepPhoto(int d) {
        if (photos.size() < 2) {
            return;
        }
        photoIndex = (photoIndex + d + photos.size()) % photos.size();
        rememberIndex();
        loadPhoto(Uri.fromFile(photos.get(photoIndex)));
        Toast.makeText(this, (photoIndex + 1) + " / " + photos.size(), Toast.LENGTH_SHORT).show();
    }

    private void loadPhoto(Uri uri) {
        new Thread(() -> {
            Bitmap bmp = decode(uri);
            if (bmp != null) {
                bmp = trimBlackBorders(bmp);
            }
            Bitmap shown = bmp;
            runOnUiThread(() -> {
                if (shown == null) {
                    Toast.makeText(this, R.string.glass_photo_open_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                Log.i(TAG, "photo " + shown.getWidth() + "x" + shown.getHeight());
                renderer.setImage(shown);
                renderer.requestRender();
            });
        }, "decode").start();
    }

    /**
     * Cuts off black bars at the top and bottom, e.g. the letterbox of a screenshot, so
     * the SBS layout is judged from the picture itself and not from the screen it was
     * captured on. Never trims the sides: a dark edge on one eye's picture would move the
     * split between the two eyes off the middle.
     */
    private static Bitmap trimBlackBorders(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        int top = 0;
        while (top < h / 2 && isDarkRow(b, top)) {
            top++;
        }
        int bottom = h - 1;
        while (bottom > h / 2 && isDarkRow(b, bottom)) {
            bottom--;
        }
        int nh = bottom - top + 1;
        // Only a real letterbox: a clear band, not a dark top row of the picture itself.
        if (nh == h || nh < h / 5 || (h - nh) < h / 20) {
            return b;
        }
        return Bitmap.createBitmap(b, 0, top, w, nh);
    }

    private static boolean isDarkRow(Bitmap b, int y) {
        for (int x = 0; x < b.getWidth(); x += 4) {
            if (luma(b.getPixel(x, y)) > 18) {
                return false;
            }
        }
        return true;
    }

    private static int luma(int c) {
        return (((c >> 16) & 0xff) * 3 + ((c >> 8) & 0xff) * 6 + (c & 0xff)) / 10;
    }

    private Bitmap decode(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (o.outWidth / sample > MAX_IMAGE_WIDTH) {
                sample *= 2;
            }
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, d);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ---- small helpers ----

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    /** A string resource formatted with dots for decimals, whatever the phone's language. */
    private String fmt(int id, Object... args) {
        return String.format(Locale.US, getString(id), args);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(dp(40));
        b.setPadding(dp(8), 0, dp(8), 0);
        if (l != null) {
            b.setOnClickListener(l);
        }
        return b;
    }

    private TextView label(String text, float sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        if (bold) {
            v.setTypeface(v.getTypeface(), Typeface.BOLD);
        }
        return v;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
