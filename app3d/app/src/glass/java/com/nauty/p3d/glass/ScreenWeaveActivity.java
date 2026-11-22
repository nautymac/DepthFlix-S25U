package com.nauty.p3d.glass;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import com.nauty.p3d.R;

/**
 * Collects what Screen 3D ({@link ScreenWeaveService}) needs and starts it: drawing over
 * other apps, the camera (eye tracking) and the system's screen-capture consent, where
 * "a single app" should be chosen so that our own picture is not captured again.
 * Opening it while Screen 3D is running ends it.
 */
public final class ScreenWeaveActivity extends Activity {
    private static final int REQ_CAMERA = 7321;
    private static final int REQ_CAPTURE = 7322;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (ScreenWeaveService.running) {
            startService(new Intent(this, ScreenWeaveService.class)
                    .setAction(ScreenWeaveService.ACTION_STOP));
            Toast.makeText(this, R.string.screen3d_stopped, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (saved == null) next();
    }

    private void next() {
        if (!ScreenWeaveService.nativeResolution(this)) {
            Toast.makeText(this, R.string.screen3d_native_only, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.screen3d_need_overlay, Toast.LENGTH_LONG).show();
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            finish();   // press the button again once it is allowed
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Toast.makeText(this, R.string.screen3d_pick_app, Toast.LENGTH_LONG).show();
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] permissions, int[] results) {
        if (req != REQ_CAMERA) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            next();
        } else {
            finish();
        }
    }

    /**
     * Asks once whether to put the Screen 3D tile in the Quick Settings panel (Android 13
     * and later; an app cannot add its tile without this question). Called by the list
     * screen, by name, when it is in front. Not asked again once the user has answered.
     */
    public static void offerTile(final Activity a) {
        if (Build.VERSION.SDK_INT < 33) return;
        final android.content.SharedPreferences p = a.getSharedPreferences("screen3d", MODE_PRIVATE);
        if (p.getBoolean("tile_offered", false)) return;
        android.app.StatusBarManager sbm = a.getSystemService(android.app.StatusBarManager.class);
        if (sbm == null) return;
        try {
            sbm.requestAddTileService(
                    new android.content.ComponentName(a, ScreenTileService.class),
                    a.getString(R.string.screen3d_tile),
                    android.graphics.drawable.Icon.createWithResource(a, R.drawable.ic_screen3d),
                    a.getMainExecutor(),
                    new java.util.function.Consumer<Integer>() {
                        @Override public void accept(Integer result) {
                            android.util.Log.i("Screen3D", "tile request result " + result);
                            // Below 1000: the user answered (or it is there already).
                            if (result != null && result < 1000) {
                                p.edit().putBoolean("tile_offered", true).apply();
                            }
                        }
                    });
        } catch (RuntimeException e) {
            android.util.Log.w("Screen3D", "tile request failed: " + e);
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req != REQ_CAPTURE) return;
        if (result == RESULT_OK && data != null) {
            Intent i = new Intent(this, ScreenWeaveService.class)
                    .putExtra(ScreenWeaveService.EXTRA_CODE, result)
                    .putExtra(ScreenWeaveService.EXTRA_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            Toast.makeText(this, R.string.screen3d_started, Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
