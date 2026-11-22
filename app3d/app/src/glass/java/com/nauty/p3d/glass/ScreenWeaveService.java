package com.nauty.p3d.glass;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.nauty.p3d.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Screen 3D: any app that shows a side-by-side picture, seen in 3D.
 *
 * The app on screen is captured (MediaProjection, "share one app" so that what we draw is
 * not captured again), its left and right halves are woven for the film exactly as the
 * player does ({@link GlassWeaver}, {@link EyeTracker}) and drawn in a full-screen window
 * over it. Where the picture is in the capture and how it is fitted to the panel is
 * {@link ScreenRegion}.
 *
 * A window from another app cannot pass touches through once it is opaque, so this is a
 * viewing mode: the small "3D" button turns it on; a right-to-left swipe then brings a menu
 * in from the right edge (picture area, fit / fill, shape, 3D off). Gamepads and keyboards
 * still reach the app underneath.
 *
 * Started by {@link ScreenWeaveActivity}, which collects the permissions.
 */
public final class ScreenWeaveService extends Service {
    private static final String TAG = "Screen3D";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static final String ACTION_STOP = "com.nauty.p3d.glass.SCREEN3D_STOP";
    private static final String CHANNEL = "screen3d";
    private static final int NOTE_ID = 7311;
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final String PREFS = "screen3d";
    /** The menu goes away this long after it opened untouched, or after a setting was made. */
    private static final long BAR_HIDE_MS = 5000;
    private static final long BAR_AFTER_SET_MS = 1500;
    /** Size of the small copy of the capture in which the picture is looked for. */
    private static final int DETECT_W = 390;
    private static final int DETECT_H = 180;
    private static final long DETECT_EVERY_MS = 500;
    static final int MODE_SBS = 0;
    static final int MODE_AI = 1;
    /** AI 3D: each eye's shift per unit of depth, in picture widths (the player's default). */
    private static final float AI_AMOUNT = 0.03f * 0.5f;
    private static final int SLIDER_MAX = 1000;
    /**
     * The "aspect" button goes through the same choices as the player's (PlayerActivity
     * ASPECTS): automatic, zoom to fill (shape kept, the overflow cut off), then fixed
     * shapes. The player's last one, stretching to the whole screen, is left out: zoom to
     * fill already fills the screen without distorting the picture.
     */
    private static final float[] ASPECTS = {
            0f, com.nauty.p3d.gl.Stereo3DView.ASPECT_ZOOM, 16f / 9f, 2.40f, 1.85f, 4f / 3f
    };
    private static final String[] ASPECT_NAMES = {null, null, "16:9", "2.40:1", "1.85:1", "4:3"};

    /** True while the service is up (the button in the tool stops it instead of starting it). */
    static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private DisplayManager dm;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private Settings settings;
    private GlassWeaver weaver;
    private EyeTracker tracker;
    private SharedPreferences prefs;
    private float pixelMm;
    private int densityDpi;

    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private int bubbleSize;
    private float bubbleFx;
    private float bubbleFy;
    private boolean bubbleHeld;
    private GLSurfaceView gl;
    private View glHolder;
    private LinearLayout bar;
    private Button aspectButton;
    private Button srcButton;
    private Button modeButton;
    private TextView boostLabel;
    private TextView phaseLabel;
    private TextView contrastLabel;
    /** AI 3D: the depth model (made the first time AI 3D is chosen). */
    private volatile DepthEngine depthEngine;
    private volatile int mode;
    private SurfaceTexture captureTexture;
    private Surface captureSurface;
    /** Size of what is captured, as the system last reported it (0 = not reported). */
    private volatile int contentW;
    private volatile int contentH;
    /** Size the capture buffers have now. */
    private volatile int captureW;
    private volatile int captureH;

    // Picture placement (set on the main thread, read on the GL thread).
    private final ScreenRegion region = new ScreenRegion();
    private volatile int srcMode;
    private volatile int aspect;

    // Statistics for the log (GL thread unless noted).
    private volatile int framesIn;          // set from the capture's callback thread
    /** Frames captured so far (never reset): tells the depth model there is something new. */
    private volatile int captureFrames;
    private int draws;
    private long drawNanos;
    private int timedDraws;
    private long statsAt;

    private final Runnable hideBar = new Runnable() {
        @Override public void run() { showBar(false); }
    };

    private final Runnable refreshBar = new Runnable() {
        @Override public void run() { updateBarLabels(); }
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            Log.i(TAG, "projection stopped by the system or the user");
            stopSelf();
        }

        @Override public void onCapturedContentResize(int width, int height) {
            Log.i(TAG, "captured content is " + width + "x" + height);
            contentW = width;
            contentH = height;
            // Screen 3D works on a picture that is the whole panel, pixel for pixel
            // (3120 x 1440 on this phone) and nothing else: an app shown smaller (split
            // screen, a pop-up window) is not woven. A turn of the phone also changes the
            // size, but that is handled by the rotation itself, without this message.
            if (gl != null && rotation() == Surface.ROTATION_90) {
                DisplayMetrics m = realMetrics();
                if (width != m.widthPixels || height != m.heightPixels) {
                    Log.w(TAG, "captured content " + width + "x" + height + " is not the panel "
                            + m.widthPixels + "x" + m.heightPixels + ": 3D off");
                    Toast.makeText(ScreenWeaveService.this,
                            getString(R.string.screen3d_size_only, m.widthPixels, m.heightPixels),
                            Toast.LENGTH_LONG).show();
                    setWeave(false);
                    return;
                }
            }
            resizeCapture(width, height);
        }

        @Override public void onCapturedContentVisibilityChanged(boolean visible) {
            Log.i(TAG, "captured content visible: " + visible);
            if (!visible) setWeave(false);
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayRemoved(int id) { }
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) placeBubble(true);
            if (id == Display.DEFAULT_DISPLAY && gl != null && rotation() != Surface.ROTATION_90) {
                Log.i(TAG, "rotated away from camera-left: 3D off");
                setWeave(false);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection != null) {
            return START_NOT_STICKY;   // already up
        }
        startAsForeground();

        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // Which part of the captured screen is the picture: found automatically, 16:9, or
        // all of it. (The button was taken out for a moment on 2026-11-22 and put back the
        // same morning: without it the picture broke for the user.)
        srcMode = prefs.getInt("src", ScreenRegion.SRC_AUTO);
        if (srcMode < 0 || srcMode >= ScreenRegion.SRC_COUNT) srcMode = ScreenRegion.SRC_AUTO;
        aspect = prefs.getInt("aspect", 0);
        if (aspect < 0 || aspect >= ASPECTS.length) aspect = 0;
        mode = prefs.getInt("mode", MODE_SBS);
        DisplayMetrics m = realMetrics();
        pixelMm = 25.4f / m.xdpi;
        densityDpi = m.densityDpi;
        settings = new Settings(this);
        weaver = new GlassWeaver(settings, pixelMm,
                Math.max(m.widthPixels, m.heightPixels), Math.min(m.widthPixels, m.heightPixels), true);
        tracker = new EyeTracker(this, settings, new EyeTracker.Listener() {
            @Override public void onUpdate(EyeTracker.State s) {
                weaver.setEye(s);
                GLSurfaceView v = gl;
                if (v != null) v.requestRender();
            }
        });

        int code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (RuntimeException e) {
            Log.e(TAG, "no projection", e);
        }
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(projectionCallback, main);
        dm.registerDisplayListener(displayListener, main);
        running = true;
        ScreenTileService.refresh(this);
        addBubble();
        Log.i(TAG, String.format(Locale.US, "up: panel %dx%d, pixel %.4f mm, sdk %d",
                m.widthPixels, m.heightPixels, pixelMm, Build.VERSION.SDK_INT));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        ScreenTileService.refresh(this);
        setWeave(false);
        if (bubble != null) {
            try { wm.removeView(bubble); } catch (RuntimeException ignored) { }
            bubble = null;
        }
        if (dm != null) dm.unregisterDisplayListener(displayListener);
        DepthEngine de = depthEngine;
        depthEngine = null;
        if (de != null) de.close();
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            projection.unregisterCallback(projectionCallback);
            projection.stop();
            projection = null;
        }
        Log.i(TAG, "down");
        super.onDestroy();
    }

    // ------------------------------------------------------------ foreground notice

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.screen3d_title), NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, ScreenWeaveService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(getString(R.string.screen3d_title))
                .setContentText(getString(R.string.screen3d_note_text))
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.screen3d_stop), stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTE_ID, n);
        }
    }

    // ------------------------------------------------------------ windows

    private static int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /** The small button that turns 3D on. A long press ends Screen 3D. */
    private void addBubble() {
        float d = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText("3D");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xB0206CD8);
        t.setBackground(bg);
        final int size = (int) (48 * d);
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp = lp;
        bubbleSize = size;
        bubbleFx = prefs.getFloat("bubble_x", 0.96f);
        bubbleFy = prefs.getFloat("bubble_y", 0.5f);
        placeBubble(false);

        // A tap turns 3D on, a long press ends Screen 3D, a drag moves the button (its place
        // is kept as a fraction of the screen, so it survives turning the phone).
        final float slop = 10 * d;
        final Runnable longPress = new Runnable() {
            @Override public void run() {
                bubbleHeld = true;
                stopSelf();
            }
        };
        t.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startX;
            private int startY;
            private boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        bubbleHeld = false;
                        main.postDelayed(longPress, 700);
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            moved = true;
                            main.removeCallbacks(longPress);
                        }
                        if (moved) {
                            DisplayMetrics m = realMetrics();
                            lp.x = Math.max(0, Math.min(m.widthPixels - size, startX + Math.round(dx)));
                            lp.y = Math.max(0, Math.min(m.heightPixels - size, startY + Math.round(dy)));
                            try { wm.updateViewLayout(v, lp); } catch (RuntimeException ignored) { }
                        }
                        break;
                    }
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(longPress);
                        if (moved) {
                            DisplayMetrics m = realMetrics();
                            bubbleFx = (lp.x + size / 2f) / m.widthPixels;
                            bubbleFy = (lp.y + size / 2f) / m.heightPixels;
                            prefs.edit().putFloat("bubble_x", bubbleFx).putFloat("bubble_y", bubbleFy).apply();
                        } else if (!bubbleHeld) {
                            setWeave(true);
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress);
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
        wm.addView(t, lp);
        bubble = t;
    }

    /** Main thread: puts the 3D button where it was left, for the screen as it is turned now. */
    private void placeBubble(boolean update) {
        if (bubbleLp == null) return;
        DisplayMetrics m = realMetrics();
        bubbleLp.x = Math.max(0, Math.min(m.widthPixels - bubbleSize,
                Math.round(bubbleFx * m.widthPixels - bubbleSize / 2f)));
        bubbleLp.y = Math.max(0, Math.min(m.heightPixels - bubbleSize,
                Math.round(bubbleFy * m.heightPixels - bubbleSize / 2f)));
        if (update && bubble != null) {
            try { wm.updateViewLayout(bubble, bubbleLp); } catch (RuntimeException ignored) { }
        }
    }

    /**
     * Main thread: the capture shows our own window, so the whole screen is being shared,
     * not one app, and the picture would feed on itself (thin lines all over; 2026-11-22).
     * Ends this capture and asks again.
     */
    private final Runnable ownPictureCaptured = new Runnable() {
        @Override public void run() {
            Log.w(TAG, "the capture contains our own window: the whole screen is shared, not one app");
            Toast.makeText(ScreenWeaveService.this, R.string.screen3d_whole_screen, Toast.LENGTH_LONG).show();
            final Context app = getApplicationContext();
            stopSelf();
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        app.startActivity(new Intent(app, ScreenWeaveActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException e) {
                        Log.w(TAG, "cannot ask again: " + e);
                    }
                }
            }, 500);
        }
    };

    /**
     * The weave is made for the panel's own pixels, one to one. With the screen resolution
     * set lower in the phone's settings (FHD+ / HD+ on this phone) the system scales what we
     * draw and the lens pattern is lost, so Screen 3D runs at the panel's resolution only;
     * other resolutions are not supported.
     *
     * @return true when the screen is at the panel's own resolution
     */
    static boolean nativeResolution(Context c) {
        WindowManager w = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        Display d = w.getDefaultDisplay();
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        int now = Math.min(m.widthPixels, m.heightPixels);
        int panel = 0;
        if (Build.VERSION.SDK_INT >= 23) {
            for (Display.Mode mode : d.getSupportedModes()) {
                panel = Math.max(panel, Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight()));
            }
        }
        Log.i(TAG, "resolution: now " + m.widthPixels + "x" + m.heightPixels + ", panel short side " + panel);
        return panel == 0 || now >= panel;
    }

    /** Main thread. On: the woven picture covers the screen. Off: the app underneath again. */
    private void setWeave(boolean on) {
        if (on == (gl != null)) return;
        if (on) {
            if (!nativeResolution(this)) {
                Toast.makeText(this, R.string.screen3d_native_only, Toast.LENGTH_LONG).show();
                return;
            }
            if (rotation() != Surface.ROTATION_90) {
                Toast.makeText(this, R.string.screen3d_rotate, Toast.LENGTH_LONG).show();
                return;
            }
            DisplayMetrics m = realMetrics();
            if (virtualDisplay == null) {
                captureW = contentW > 0 ? contentW : m.widthPixels;
                captureH = contentH > 0 ? contentH : m.heightPixels;
            }
            settings = new Settings(this);   // the tool may have changed the values
            weaver.setSettings(settings);
            if (mode == MODE_AI) ensureDepthEngine();

            final GLSurfaceView v = new GLSurfaceView(this);
            v.setEGLContextClientVersion(2);
            v.setRenderer(new Renderer(v));
            v.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            // The menu comes in from the right edge (the side away from the camera) with a
            // right-to-left swipe and goes with the opposite swipe or a tap beside it.
            // A plain tap on the picture does nothing, so a stray touch changes nothing.
            final float swipe = 48 * getResources().getDisplayMetrics().density;
            v.setOnTouchListener(new View.OnTouchListener() {
                private float downX;
                private float downY;

                @Override public boolean onTouch(View view, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getX();
                            downY = e.getY();
                            break;
                        case MotionEvent.ACTION_UP: {
                            float dx = e.getX() - downX;
                            float dy = e.getY() - downY;
                            if (Math.abs(dx) >= swipe && Math.abs(dx) > 1.5f * Math.abs(dy)) {
                                showBar(dx < 0);
                            } else if (bar != null && Math.abs(dx) < swipe && Math.abs(dy) < swipe) {
                                showBar(false);
                            }
                            break;
                        }
                        default:
                            break;
                    }
                    return true;
                }
            });
            v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View view, int l, int t, int r, int b,
                                                     int ol, int ot, int or, int ob) {
                    int[] at = new int[2];
                    view.getLocationOnScreen(at);
                    weaver.setPlacement(at[0], at[1]);
                    Log.i(TAG, "window at " + at[0] + "," + at[1] + " size " + (r - l) + "x" + (b - t));
                }
            });
            // Exactly the panel, camera hole and bar areas included: the weave is per panel pixel.
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    m.widthPixels, m.heightPixels, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= 30) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                lp.setFitInsetsTypes(0);
            } else if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            // The GL view sits in a holder: as the window's own root, a SurfaceView crashed
            // when the window was removed right after a draw (NullPointerException in
            // SurfaceView.performDrawFinished, its parent already gone; 2026-11-22).
            android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
            holder.addView(v, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            try {
                wm.addView(holder, lp);
            } catch (RuntimeException e) {
                Log.e(TAG, "cannot add the window", e);
                return;
            }
            glHolder = holder;
            gl = v;
            if (bubble != null) bubble.setVisibility(View.GONE);
            tracker.start(90, m.widthPixels * pixelMm, m.heightPixels * pixelMm);
            Log.i(TAG, "3D on, capture " + captureW + "x" + captureH);
        } else {
            showBar(false);
            View holder = glHolder;
            glHolder = null;
            gl = null;
            tracker.stop();
            if (virtualDisplay != null) virtualDisplay.setSurface(null);
            try { wm.removeView(holder); } catch (RuntimeException ignored) { }   // ends the GL thread
            if (captureSurface != null) captureSurface.release();
            if (captureTexture != null) captureTexture.release();
            captureSurface = null;
            captureTexture = null;
            if (bubble != null) bubble.setVisibility(View.VISIBLE);
            Log.i(TAG, "3D off");
        }
    }

    // ------------------------------------------------------------ the bar

    /**
     * Main thread. The menu over the woven picture: picture area, fit, shape, 3D off.
     * A column on the right edge (camera on the left), sliding in from that edge; it goes
     * away by itself shortly after a setting is made.
     */
    private void showBar(boolean show) {
        main.removeCallbacks(hideBar);
        if (!show) {
            final LinearLayout old = bar;
            bar = null;
            if (old != null) {
                old.animate().translationX(old.getWidth()).setDuration(150).withEndAction(new Runnable() {
                    @Override public void run() {
                        try { wm.removeView(old); } catch (RuntimeException ignored) { }
                    }
                }).start();
            }
            return;
        }
        if (gl == null) return;
        if (bar == null) {
            float d = getResources().getDisplayMetrics().density;
            final LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (6 * d);
            col.setPadding(pad, pad, pad, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE0181818);
            float r = 12 * d;
            bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});   // round on the inner side
            col.setBackground(bg);

            modeButton = barButton(col, 1f, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    mode = mode == MODE_SBS ? MODE_AI : MODE_SBS;
                    if (mode == MODE_AI) ensureDepthEngine();
                    changed();
                }
            });
            srcButton = barButton(col, 1f, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    srcMode = (srcMode + 1) % ScreenRegion.SRC_COUNT;
                    changed();
                }
            });
            // Aspect: each press goes to the next choice, as in the player.
            aspectButton = barButton(col, 1f, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    aspect = (aspect + 1) % ASPECTS.length;
                    changed();
                }
            });
            // Brightness, phase and contrast are the lens values the player and the 3D Fit
            // tool use: changed here, they are saved for all three (as the player's 3D tune
            // bar does).
            boostLabel = barSlider(col, GlassTuner.MIN_BOOST, GlassTuner.MAX_BOOST, settings.boost,
                    new SliderListener() {
                        @Override public void onValue(float v) { settings.boost = v; }
                    });
            contrastLabel = barSlider(col, GlassTuner.MIN_CONTRAST, GlassTuner.MAX_CONTRAST, settings.contrast,
                    new SliderListener() {
                        @Override public void onValue(float v) { settings.contrast = v; }
                    });
            phaseLabel = barSlider(col, 0f, 1f, settings.phase,
                    new SliderListener() {
                        @Override public void onValue(float v) { settings.phase = v; }
                    });
            barButton(col, 1f, new View.OnClickListener() {
                @Override public void onClick(View v) { setWeave(false); }
            }).setText(R.string.screen3d_off);

            final int width = (int) (236 * d);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    width, WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            try {
                wm.addView(col, lp);
            } catch (RuntimeException e) {
                Log.e(TAG, "cannot add the menu", e);
                return;
            }
            col.setTranslationX(width);
            col.animate().translationX(0f).setDuration(180).start();
            bar = col;
        }
        updateBarLabels();
        main.postDelayed(hideBar, BAR_HIDE_MS);
    }

    private LinearLayout barRow(LinearLayout col) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private interface SliderListener {
        void onValue(float v);
    }

    /**
     * A menu row with a value label and a slider. The picture follows the finger; the value
     * is saved, and the menu's leaving timed, when the finger lifts.
     *
     * @return the label (its text is set in updateBarLabels)
     */
    private TextView barSlider(LinearLayout col, final float min, final float max, float value,
                               final SliderListener l) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout row = barRow(col);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight((int) (40 * d));
        TextView label = new TextView(this);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(13);
        label.setPadding((int) (6 * d), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams((int) (78 * d),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(SLIDER_MAX);
        bar.setProgress(Math.round((Math.max(min, Math.min(max, value)) - min) / (max - min) * SLIDER_MAX));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                l.onValue(min + (max - min) * progress / SLIDER_MAX);
                updateBarLabels();
                GLSurfaceView v = gl;
                if (v != null) v.requestRender();
            }

            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {
                main.removeCallbacks(hideBar);
            }

            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {
                settings.save();
                Log.i(TAG, String.format(Locale.US, "menu: boost %.2f, phase %.2f, contrast %.2f",
                        settings.boost, settings.phase, settings.contrast));
                main.removeCallbacks(hideBar);
                main.postDelayed(hideBar, BAR_AFTER_SET_MS);
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return label;
    }

    /** Main thread: the depth model for AI 3D, loaded once. */
    private void ensureDepthEngine() {
        if (depthEngine != null) return;
        DepthEngine e = new DepthEngine(this, new DepthEngine.Listener() {
            @Override public void onStatus(String text) {
                if (text != null) Log.i(TAG, "AI depth: " + text);
                main.post(refreshBar);
            }

            @Override public void onDepth() {
                GLSurfaceView v = gl;
                if (v != null) v.requestRender();
            }
        });
        e.setModel(DepthEngine.Model.DA2);
        depthEngine = e;
    }

    private Button barButton(LinearLayout into, float weight, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(13);
        float d = getResources().getDisplayMetrics().density;
        b.setMinHeight(0);
        b.setMinimumHeight((int) (40 * d));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding((int) (4 * d), 0, (int) (4 * d), 0);
        if (l != null) b.setOnClickListener(l);
        else b.setClickable(false);
        boolean row = into.getOrientation() == LinearLayout.HORIZONTAL;
        into.addView(b, row
                ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                : new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return b;
    }

    /** Main thread: a menu button changed something. The menu leaves soon after. */
    private void changed() {
        prefs.edit().putInt("src", srcMode).putInt("aspect", aspect)
                .putInt("mode", mode).apply();
        updateBarLabels();
        GLSurfaceView v = gl;
        if (v != null) v.requestRender();
        main.removeCallbacks(hideBar);
        main.postDelayed(hideBar, BAR_AFTER_SET_MS);
        Log.i(TAG, String.format(Locale.US, "menu: mode %d, source %d, aspect %d", mode, srcMode, aspect));
    }

    private void updateBarLabels() {
        if (bar == null) return;
        int[] size = new int[2];
        region.source(srcMode, captureW, captureH, size);
        String found = size[0] + "×" + size[1];
        srcButton.setText(getString(srcMode == ScreenRegion.SRC_AUTO ? R.string.screen3d_src_auto
                : srcMode == ScreenRegion.SRC_16_9 ? R.string.screen3d_src_169
                : R.string.screen3d_src_full, found));
        aspectButton.setText(getString(R.string.btn_aspect,
                aspect == 0 ? getString(R.string.aspect_auto)
                        : ASPECT_NAMES[aspect] == null ? getString(R.string.aspect_zoom)
                        : ASPECT_NAMES[aspect]));
        DepthEngine e = depthEngine;
        modeButton.setText(mode == MODE_SBS ? R.string.screen3d_mode_sbs
                : e != null && e.active() ? R.string.screen3d_mode_ai : R.string.screen3d_mode_ai_loading);
        boostLabel.setText(getString(R.string.screen3d_boost, settings.boost));
        phaseLabel.setText(getString(R.string.screen3d_phase, settings.phase));
        contrastLabel.setText(getString(R.string.screen3d_contrast, settings.contrast));
    }

    // ------------------------------------------------------------ capture

    /** Main thread: the GL side has a surface for the captured picture. */
    private void attachCapture(GLSurfaceView from, SurfaceTexture texture, Surface surface) {
        if (from != gl || projection == null) {   // turned off meanwhile
            surface.release();
            texture.release();
            return;
        }
        captureTexture = texture;
        captureSurface = surface;
        try {
            if (virtualDisplay == null) {
                virtualDisplay = projection.createVirtualDisplay("screen3d", captureW, captureH,
                        densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, main);
            } else {
                virtualDisplay.setSurface(surface);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "cannot start the capture", e);
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    /** Main thread: the captured app's size changed; capture it one to one. */
    private void resizeCapture(int w, int h) {
        if (w <= 0 || h <= 0 || (w == captureW && h == captureH)) return;
        captureW = w;
        captureH = h;
        region.reset();
        if (captureTexture != null) captureTexture.setDefaultBufferSize(w, h);
        if (virtualDisplay != null) virtualDisplay.resize(w, h, densityDpi);
    }

    private DisplayMetrics realMetrics() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return m;
    }

    private int rotation() {
        return wm.getDefaultDisplay().getRotation();
    }

    // ------------------------------------------------------------ drawing

    private final class Renderer implements GLSurfaceView.Renderer {
        private final GLSurfaceView view;
        private int texture;
        private SurfaceTexture surfaceTexture;
        private final float[] matrix = new float[16];
        private final float[] src = new float[4];
        private final float[] dst = new float[4];
        private int width;
        private int height;

        // The small copy in which the picture is looked for (ScreenRegion.detect).
        private int copyProgram;
        private int copyPos;
        private int copyTex;
        private int copyMatrix;
        private int copyFbo;
        private FloatBuffer quad;
        private ByteBuffer pixels;
        private long detectAt;
        private int copyRect;

        // AI 3D: the picture goes to the depth model, its depth map comes back as a texture.
        private int depthTex;
        private int depthTexW;
        private int depthTexH;
        private boolean depthValid;
        private int depthInFbo;
        private int depthInW;
        private int depthInH;
        private ByteBuffer depthInBuf;
        private int framesAtSubmit = -1;

        Renderer(GLSurfaceView view) {
            this.view = view;
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            weaver.onSurfaceCreated();
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            texture = t[0];
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
            // Linear: the captured app is not always one pixel per panel pixel.
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            final SurfaceTexture st = new SurfaceTexture(texture);
            st.setDefaultBufferSize(captureW, captureH);
            st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override public void onFrameAvailable(SurfaceTexture s) {
                    framesIn++;
                    captureFrames++;
                    view.requestRender();
                }
            });
            surfaceTexture = st;
            final Surface surface = new Surface(st);
            main.post(new Runnable() {
                @Override public void run() { attachCapture(view, st, surface); }
            });
            setUpCopy();
            statsAt = SystemClock.elapsedRealtime();
        }

        private void setUpCopy() {
            String vs = "attribute vec2 aPos;\n"
                    + "uniform mat4 uM;\n"
                    + "uniform vec4 uRect;\n"      // the part of the capture to copy
                    + "varying vec2 vT;\n"
                    + "void main() {\n"
                    + "  vT = (uM * vec4(uRect.xy + (aPos * 0.5 + 0.5) * uRect.zw, 0.0, 1.0)).xy;\n"
                    + "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
                    + "}\n";
            String fs = "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "uniform samplerExternalOES uTex;\n"
                    + "varying vec2 vT;\n"
                    + "void main() { gl_FragColor = texture2D(uTex, vT); }\n";
            int v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
            GLES20.glShaderSource(v, vs);
            GLES20.glCompileShader(v);
            int f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
            GLES20.glShaderSource(f, fs);
            GLES20.glCompileShader(f);
            copyProgram = GLES20.glCreateProgram();
            GLES20.glAttachShader(copyProgram, v);
            GLES20.glAttachShader(copyProgram, f);
            GLES20.glLinkProgram(copyProgram);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(copyProgram, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                Log.e(TAG, "copy program: " + GLES20.glGetProgramInfoLog(copyProgram));
                copyProgram = 0;
                return;
            }
            copyPos = GLES20.glGetAttribLocation(copyProgram, "aPos");
            copyTex = GLES20.glGetUniformLocation(copyProgram, "uTex");
            copyMatrix = GLES20.glGetUniformLocation(copyProgram, "uM");
            copyRect = GLES20.glGetUniformLocation(copyProgram, "uRect");

            // AI 3D depth map; one flat pixel until the first real one arrives.
            int[] dt = new int[1];
            GLES20.glGenTextures(1, dt, 0);
            depthTex = dt[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, 1, 0,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
                    ByteBuffer.allocateDirect(1).put(0, (byte) 102));
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
            depthTexW = 1;
            depthTexH = 1;

            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, DETECT_W, DETECT_H, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            int[] fb = new int[1];
            GLES20.glGenFramebuffers(1, fb, 0);
            copyFbo = fb[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, copyFbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, t[0], 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "copy framebuffer incomplete");
                copyProgram = 0;
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            float[] verts = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
            quad = ByteBuffer.allocateDirect(verts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(verts).position(0);
            pixels = ByteBuffer.allocateDirect(DETECT_W * DETECT_H * 4).order(ByteOrder.nativeOrder());
        }

        /** Looks for the picture in the capture (black bars around it). */
        private void detect() {
            if (copyProgram == 0) return;
            readSmall();
            if (region.detect(pixels, DETECT_W, DETECT_H, captureW, captureH)) {
                Log.i(TAG, "picture found: " + region.autoW + "x" + region.autoH
                        + " in " + captureW + "x" + captureH);
                main.post(refreshBar);
            }
            long now = SystemClock.elapsedRealtime();
            if (now - dumpAt >= 3000) {
                dumpAt = now;
                Log.i(TAG, "looking: " + region.last);
            }
        }

        private long dumpAt;

        /** The whole capture, small (DETECT_W x DETECT_H), into {@code pixels}. */
        private void readSmall() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, copyFbo);
            GLES20.glViewport(0, 0, DETECT_W, DETECT_H);
            GLES20.glUseProgram(copyProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glUniform1i(copyTex, 0);
            GLES20.glUniformMatrix4fv(copyMatrix, 1, false, matrix, 0);
            GLES20.glUniform4f(copyRect, 0f, 0f, 1f, 1f);
            GLES20.glEnableVertexAttribArray(copyPos);
            GLES20.glVertexAttribPointer(copyPos, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(copyPos);
            pixels.position(0);
            GLES20.glReadPixels(0, 0, DETECT_W, DETECT_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        // Is our own window in the capture? For the first moments a magenta square is drawn
        // in a corner of our window; if the capture shows it, the whole screen is shared.
        private static final int MARK_PX = 120;
        private static final long MARK_MS = 700;        // after the capture's first frame
        private static final long MARK_WAIT_MS = 2000;  // no frame at all by then: a still app
        private boolean markDone;
        private long markFirstDraw;
        private long markCaptureAt;
        private int markFramesAtStart = -1;

        /** After the picture is drawn: the square, and a look for it in the capture. */
        private void ownWindowCheck(long now) {
            if (markDone || copyProgram == 0) return;
            if (markFirstDraw == 0) {
                markFirstDraw = now;
                markFramesAtStart = captureFrames;
            }
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(0, 0, MARK_PX, MARK_PX);
            GLES20.glClearColor(1f, 0f, 1f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            if (markCaptureAt == 0 && captureFrames != markFramesAtStart) markCaptureAt = now;
            if (markCaptureAt == 0) {
                if (now - markFirstDraw > MARK_WAIT_MS) markDone = true;
                return;
            }
            readSmall();
            GLES20.glViewport(0, 0, width, height);
            if (seesMark()) {
                markDone = true;
                main.post(ownPictureCaptured);
            } else if (now - markCaptureAt > MARK_MS) {
                markDone = true;
                Log.i(TAG, "our window is not in the capture: one app is shared");
            }
        }

        /** The magenta square in any corner of the small copy (whichever way up it is). */
        private boolean seesMark() {
            int cw = Math.max(4, MARK_PX * DETECT_W / Math.max(1, captureW));
            int ch = Math.max(4, MARK_PX * DETECT_H / Math.max(1, captureH));
            int mx = Math.max(1, cw / 4);
            int my = Math.max(1, ch / 4);
            for (int corner = 0; corner < 4; corner++) {
                int x0 = (corner & 1) == 0 ? mx : DETECT_W - cw + mx;
                int y0 = (corner & 2) == 0 ? my : DETECT_H - ch + my;
                int n = 0;
                int hit = 0;
                for (int y = y0; y < y0 + ch - 2 * my; y++) {
                    for (int x = x0; x < x0 + cw - 2 * mx; x++) {
                        int at = (y * DETECT_W + x) * 4;
                        int r = pixels.get(at) & 0xff;
                        int g = pixels.get(at + 1) & 0xff;
                        int b = pixels.get(at + 2) & 0xff;
                        n++;
                        if (r > 190 && b > 190 && g < 70) hit++;
                    }
                }
                if (n > 0 && hit * 10 >= n * 7) return true;
            }
            return false;
        }

        /**
         * AI 3D: hands the picture (the part src of the capture) to the depth model when it
         * is free and something new has been captured, and takes its newest depth map.
         */
        private void aiDepth(DepthEngine e) {
            if (copyProgram == 0) return;
            if (e.wantsFrame() && captureFrames != framesAtSubmit) {
                int iw = e.inputWidth();
                int ih = e.inputHeight();
                if (depthInFbo == 0 || depthInW != iw || depthInH != ih) {
                    int[] t = new int[1];
                    GLES20.glGenTextures(1, t, 0);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, iw, ih, 0,
                            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                    int[] fb = new int[1];
                    GLES20.glGenFramebuffers(1, fb, 0);
                    depthInFbo = fb[0];
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, depthInFbo);
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                            GLES20.GL_TEXTURE_2D, t[0], 0);
                    depthInW = iw;
                    depthInH = ih;
                    depthInBuf = ByteBuffer.allocateDirect(iw * ih * 4).order(ByteOrder.nativeOrder());
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, depthInFbo);
                GLES20.glViewport(0, 0, iw, ih);
                GLES20.glUseProgram(copyProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
                GLES20.glUniform1i(copyTex, 0);
                GLES20.glUniformMatrix4fv(copyMatrix, 1, false, matrix, 0);
                GLES20.glUniform4f(copyRect, src[0], src[1], src[2], src[3]);
                GLES20.glEnableVertexAttribArray(copyPos);
                GLES20.glVertexAttribPointer(copyPos, 2, GLES20.GL_FLOAT, false, 0, quad);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GLES20.glDisableVertexAttribArray(copyPos);
                depthInBuf.position(0);
                GLES20.glReadPixels(0, 0, iw, ih, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, depthInBuf);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                depthInBuf.position(0);
                e.submit(depthInBuf);
                framesAtSubmit = captureFrames;
            }
            ByteBuffer d = e.takeDepth();
            if (d != null) {
                int dw = e.depthWidth();
                int dh = e.depthHeight();
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex);
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
                d.position(0);
                if (depthTexW != dw || depthTexH != dh) {
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, dw, dh, 0,
                            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, d);
                    depthTexW = dw;
                    depthTexH = dh;
                } else {
                    GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, dw, dh,
                            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, d);
                }
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
                depthValid = true;
            }
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int w, int h) {
            width = w;
            height = h;
            Log.i(TAG, "surface " + w + "x" + h);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            try {
                surfaceTexture.updateTexImage();
            } catch (RuntimeException e) {
                return;   // released while turning off
            }
            surfaceTexture.getTransformMatrix(matrix);
            long now = SystemClock.elapsedRealtime();
            if (srcMode == ScreenRegion.SRC_AUTO && now - detectAt >= DETECT_EVERY_MS) {
                detectAt = now;
                detect();
            }
            DepthEngine engine = mode == MODE_AI ? depthEngine : null;
            boolean ai = engine != null;
            float shown = ASPECTS[aspect];
            region.compute(srcMode,
                    shown == com.nauty.p3d.gl.Stereo3DView.ASPECT_ZOOM ? ScreenRegion.FILL : ScreenRegion.FIT,
                    shown > 0f ? shown : 0f, ai, captureW, captureH, width, height, src, dst);
            if (ai && engine.active()) aiDepth(engine);
            weaver.setTexMatrix(matrix);
            weaver.setRegion(src, dst);
            // AI 3D before its first depth map: the picture flat, as it is.
            weaver.setAiDepth(ai ? depthTex : 0, ai && depthValid ? AI_AMOUNT : 0f);
            boolean timed = (draws % 30) == 0;
            long t0 = timed ? System.nanoTime() : 0;
            weaver.draw(texture, width, height);
            ownWindowCheck(now);
            if (timed) {
                GLES20.glFinish();
                drawNanos += System.nanoTime() - t0;
                timedDraws++;
            }
            draws++;
            if (now - statsAt >= 2000) {
                float sec = (now - statsAt) / 1000f;
                Log.i(TAG, String.format(Locale.US,
                        "capture %.1f fps, drawn %.1f fps, draw %.2f ms, capture %dx%d, window %dx%d, "
                                + "picture %.0fx%.0f -> %.0fx%.0f",
                        framesIn / sec, draws / sec,
                        timedDraws > 0 ? drawNanos / 1e6f / timedDraws : 0f,
                        captureW, captureH, width, height,
                        src[2] * captureW, src[3] * captureH, dst[2] * width, dst[3] * height));
                framesIn = 0;
                draws = 0;
                drawNanos = 0;
                timedDraws = 0;
                statsAt = now;
            }
        }
    }
}
