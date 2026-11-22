package com.nauty.p3d.glass;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.nauty.p3d.R;

/**
 * Quick Settings tile for Screen 3D ({@link ScreenWeaveService}): starts it from the
 * pull-down panel without opening DepthFlix, and ends it when it is running.
 *
 * Android does not let an app put its tile in the panel by itself. The user adds it from
 * the panel's edit screen, or answers the one-time question {@link ScreenWeaveActivity#offerTile}
 * asks.
 */
public final class ScreenTileService extends TileService {

    @Override
    public void onStartListening() {
        show(ScreenWeaveService.running);
    }

    @Override
    public void onClick() {
        if (ScreenWeaveService.running) {
            startService(new Intent(this, ScreenWeaveService.class)
                    .setAction(ScreenWeaveService.ACTION_STOP));
            show(false);
            return;
        }
        Intent i = new Intent(this, ScreenWeaveActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }

    private void show(boolean on) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(getString(R.string.screen3d_tile));
        t.updateTile();
    }

    /** Any thread: Screen 3D started or ended; the tile should show it. */
    static void refresh(Context c) {
        try {
            requestListeningState(c, new ComponentName(c, ScreenTileService.class));
        } catch (RuntimeException ignored) { }
    }
}
