package cn.yuchuang.floatcontrol;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

public class OverlayTileService extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        updateState();
    }

    @Override public void onClick() {
        super.onClick();
        if (OverlayService.isRunning()) {
            stopService(new Intent(this, OverlayService.class));
            updateState(false);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Intent settings = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("startOverlay", true);
            PendingIntent pending = PendingIntent.getActivity(this, 2, settings,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pending);
            updateState();
            return;
        }
        try {
            startForegroundService(new Intent(this, OverlayService.class).setAction("start"));
            updateState(true);
        } catch (RuntimeException e) {
            Log.e("YuChuangOverlay", "Quick Settings could not start overlay", e);
            Intent settings = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("startOverlay", true);
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, settings,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
    }

    private void updateState() {
        updateState(OverlayService.isRunning());
    }

    private void updateState(boolean running) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setIcon(Icon.createWithResource(this, R.drawable.qs_icon));
        tile.setLabel("驭窗浮控");
        tile.setState(running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setStateDescription(running ? "悬浮窗已开启" : "悬浮窗已关闭");
        tile.updateTile();
    }
}
