package cn.yuchuang.floatcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherApps;
import android.content.res.ColorStateList;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.session.MediaController;
import android.media.MediaMetadata;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class OverlayService extends Service {
    private static volatile boolean running;
    private static final int OUTLINE = 0x66ffffff;
    private static final int ICON_INK = 0xff263238;
    private static final int BUTTON_SURFACE = 0xeef8fafb;
    private WindowManager manager;
    private WindowManager.LayoutParams params;
    private View window;
    private Dialog expandedDialog;
    private int expandedOpacity = -1;
    private int expandedSizePercent = -1;
    private int sizePercent = 100;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int COLLAPSED = 0, CONTROLS = 1, APPS = 2;
    private int state = COLLAPSED;
    private int lastState = CONTROLS;
    private float downX, downY;
    private int initialX, initialY;
    private int collapsedX, collapsedY;
    private boolean moved;
    private ImageView cover;
    private ImageView playButton;
    private final Runnable collapseTask = () -> setState(COLLAPSED);
    private final Runnable refreshMedia = new Runnable() {
        @Override public void run() {
            if (state == CONTROLS && cover != null && playButton != null) {
                MediaController controller = selectedController();
                if (controller != null) {
                    MediaMetadata metadata = controller.getMetadata();
                    Bitmap art = metadata == null ? null : metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    if (art == null && metadata != null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (art != null) {
                        cover.setImageBitmap(art);
                        cover.setBackground(background(BUTTON_SURFACE, 10, 0x44ffffff));
                    } else cover.setImageResource(R.drawable.icon);
                    PlaybackState playback = controller.getPlaybackState();
                    playButton.setImageResource(playback != null && playback.getState() == PlaybackState.STATE_PLAYING
                        ? R.drawable.media_pause : R.drawable.media_play);
                } else {
                    cover.setImageResource(R.drawable.icon);
                    playButton.setImageResource(R.drawable.media_play);
                }
                handler.postDelayed(this, 1800);
            }
        }
    };
    private final BroadcastReceiver refresh = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { render(); }
    };

    public static boolean isRunning() { return running; }
    private void refreshTile() {
        TileService.requestListeningState(this, new ComponentName(this, OverlayTileService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        sizePercent = configuredSizePercent();
        manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("floating", "悬浮快捷栏", NotificationManager.IMPORTANCE_LOW));
        PendingIntent settings = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, OverlayService.class).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, "floating")
            .setSmallIcon(cn.yuchuang.floatcontrol.R.drawable.icon)
            .setContentTitle("驭窗浮控正在运行")
            .setContentIntent(settings)
            .addAction(new Notification.Action.Builder(null, "停止", stop).build())
            .build();
        startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        registerReceiver(refresh, new IntentFilter("cn.yuchuang.floatcontrol.REFRESH"), Context.RECEIVER_NOT_EXPORTED);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        if (params == null) {
            params = new WindowManager.LayoutParams(dp(62), dp(62),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                android.graphics.PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            collapsedX = prefs.getInt("x", getResources().getDisplayMetrics().widthPixels - dp(76));
            collapsedY = prefs.getInt("y", getResources().getDisplayMetrics().heightPixels / 2);
            if (prefs.contains("x") && prefs.contains("y"))
                adjustCollapsedPosition(prefs.getInt("positionSizePercent", 100), sizePercent);
            render();
        }
        if (window != null) {
            running = true;
            refreshTile();
        }
        return START_STICKY;
    }

    private void render() {
        if (params == null) return;
        int requestedSizePercent = configuredSizePercent();
        if (requestedSizePercent != sizePercent) {
            adjustCollapsedPosition(sizePercent, requestedSizePercent);
            sizePercent = requestedSizePercent;
        }
        handler.removeCallbacks(refreshMedia);
        cover = null;
        playButton = null;
        boolean reuseExpanded = expandedDialog != null && state != COLLAPSED;
        if (expandedDialog != null && !reuseExpanded) {
            expandedDialog.dismiss();
            expandedDialog = null;
            expandedOpacity = -1;
            expandedSizePercent = -1;
        } else if (expandedDialog == null && window != null) {
            try { manager.removeView(window); } catch (Exception ignored) {}
        }
        window = null;
        final boolean compact = state == COLLAPSED;
        int width = dp(compact ? 62 : 60);
        int height = dp(compact ? 62 : 308);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        // Both expanded layouts put the switch at their geometric center (30, 154 dp).
        // Keep the collapsed icon's center (31, 31 dp) as the stable anchor.
        int targetX = compact ? collapsedX : collapsedX + dp(31) - width / 2;
        int targetY = compact ? collapsedY : collapsedY + dp(31) - height / 2;
        params.x = Math.max(0, Math.min(targetX, screenWidth - width));
        params.y = Math.max(0, Math.min(targetY, screenHeight - height));
        params.width = width;
        params.height = height;
        if (prefs.getBoolean("keepScreenOn", false))
            params.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        else params.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        LinearLayout column = new LinearLayout(this) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    if (state != COLLAPSED) collapse();
                    return true;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setPadding(compact ? 0 : dp(4), compact ? 0 : dp(4),
            compact ? 0 : dp(4), compact ? 0 : dp(4));
        column.setElevation(compact ? 0 : dp(8));
        if (compact) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.floating_icon);
            column.addView(icon, new LinearLayout.LayoutParams(-1, -1));
            attachDrag(column, true);
        } else if (state == CONTROLS) {
            FrameLayout coverSlot = new FrameLayout(this);
            column.addView(coverSlot, new LinearLayout.LayoutParams(-1, dp(60)));
            cover = new ImageView(this);
            cover.setContentDescription("专辑封面");
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setImageResource(R.drawable.icon);
            cover.setBackground(background(BUTTON_SURFACE, 10, 0x44ffffff));
            cover.setClipToOutline(true);
            FrameLayout.LayoutParams artLayout = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER);
            coverSlot.addView(cover, artLayout);
            mediaSkipButton(column, false);
            switchButton(column);
            FrameLayout playSlot = new FrameLayout(this);
            column.addView(playSlot, new LinearLayout.LayoutParams(-1, dp(60)));
            playSlot.setContentDescription("播放或暂停");
            playButton = new ImageView(this);
            playButton.setImageResource(R.drawable.media_play);
            playButton.setImageTintList(ColorStateList.valueOf(ICON_INK));
            playButton.setPadding(dp(9), dp(9), dp(9), dp(9));
            playButton.setBackground(background(BUTTON_SURFACE, 12, 0x44ffffff));
            playSlot.addView(playButton, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
            playSlot.setOnClickListener(v -> { resetTimer(); togglePlayback(); });
            mediaSkipButton(column, true);
            handler.post(refreshMedia);
        } else {
            appButton(column, "message", "沟通");
            appButton(column, "navigation", "导航");
            switchButton(column);
            appButton(column, "music", "音乐");
            FrameLayout settingsSlot = new FrameLayout(this);
            column.addView(settingsSlot, new LinearLayout.LayoutParams(-1, dp(60)));
            ImageView settings = new ImageView(this);
            settings.setImageResource(android.R.drawable.ic_menu_preferences);
            settings.setImageTintList(ColorStateList.valueOf(ICON_INK));
            settings.setPadding(dp(11), dp(11), dp(11), dp(11));
            settings.setBackground(background(BUTTON_SURFACE, 12, 0x44ffffff));
            settingsSlot.addView(settings, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
            settingsSlot.setContentDescription("设置");
            settingsSlot.setOnClickListener(v -> {
                collapse();
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });
        }
        window = column;
        try {
            if (compact) manager.addView(window, params);
            else {
                final int opacity = Math.max(0, Math.min(80, prefs.getInt("opacity", 24)));
                if (!reuseExpanded) {
                    expandedDialog = new Dialog(this, R.style.FloatingOverlayTheme) {
                        @Override public boolean dispatchTouchEvent(MotionEvent event) {
                            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                                collapse();
                                return true;
                            }
                            return super.dispatchTouchEvent(event);
                        }
                    };
                    expandedDialog.setCanceledOnTouchOutside(false);
                }
                android.view.Window overlayWindow = expandedDialog.getWindow();
                if (!reuseExpanded) {
                    overlayWindow.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                    overlayWindow.setGravity(Gravity.TOP | Gravity.LEFT);
                    overlayWindow.setWindowAnimations(0);
                }
                if (expandedOpacity != opacity || expandedSizePercent != sizePercent)
                    overlayWindow.setBackgroundDrawable(background((opacity * 255 / 100) << 24, 19, OUTLINE));
                expandedDialog.setContentView(column);
                if (!reuseExpanded || expandedSizePercent != sizePercent)
                    overlayWindow.setBackgroundBlurRadius(dp(18));
                WindowManager.LayoutParams dialogParams = overlayWindow.getAttributes();
                dialogParams.width = width;
                dialogParams.height = height;
                dialogParams.x = params.x;
                dialogParams.y = params.y;
                dialogParams.flags = (dialogParams.flags & ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) | params.flags;
                overlayWindow.setAttributes(dialogParams);
                if (!reuseExpanded) expandedDialog.show();
                expandedOpacity = opacity;
                expandedSizePercent = sizePercent;
            }
        }
        catch (Exception e) {
            Log.e("YuChuangOverlay", "Failed to create " + (compact ? "icon" : "expanded") + " window", e);
            if (!compact) {
                state = COLLAPSED;
                render();
            } else {
                window = null;
                stopSelf();
            }
            return;
        }
        if (!compact) resetTimer();
    }

    private void attachDrag(View view, boolean openOnClick) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX(); downY = event.getRawY();
                    initialX = params.x; initialY = params.y; moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int)(event.getRawX() - downX);
                    int dy = (int)(event.getRawY() - downY);
                    if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) moved = true;
                    if (moved) {
                        params.x = Math.max(0, initialX + dx);
                        params.y = Math.max(0, initialY + dy);
                        manager.updateViewLayout(window, params);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved) {
                        collapsedX = params.x;
                        collapsedY = params.y;
                        prefs.edit().putInt("x", collapsedX).putInt("y", collapsedY)
                            .putInt("positionSizePercent", sizePercent).apply();
                    }
                    else if (openOnClick) open();
                    return true;
                default: return true;
            }
        });
    }

    private void mediaSkipButton(LinearLayout column, boolean previous) {
        FrameLayout slot = new FrameLayout(this);
        column.addView(slot, new LinearLayout.LayoutParams(-1, dp(60)));
        ImageView button = new ImageView(this);
        button.setImageResource(R.drawable.media_skip);
        button.setImageTintList(ColorStateList.valueOf(ICON_INK));
        button.setScaleX(previous ? -1f : 1f);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        button.setBackground(background(BUTTON_SURFACE, 12, 0x44ffffff));
        slot.addView(button, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
        slot.setContentDescription(previous ? "上一首" : "下一首");
        slot.setOnClickListener(v -> { resetTimer(); media(!previous); });
    }

    private void switchButton(LinearLayout column) {
        TextView switcher = new TextView(this);
        switcher.setText("⇅");
        switcher.setTextSize(30 * sizePercent / 100f);
        switcher.setTextColor(Color.WHITE);
        switcher.setGravity(Gravity.CENTER);
        switcher.setContentDescription("切换控件与应用");
        switcher.setBackground(background(0xff0967e9, 27, 0xff438ff5));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(dp(52), dp(54));
        layout.gravity = Gravity.CENTER;
        layout.setMargins(0, dp(3), 0, dp(3));
        column.addView(switcher, layout);
        switcher.setOnClickListener(v -> {
            if (prefs.getBoolean("vibration", true)) {
                Vibrator vibrator = getSystemService(Vibrator.class);
                if (vibrator != null && vibrator.hasVibrator())
                    vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE));
            }
            setState(state == CONTROLS ? APPS : CONTROLS);
        });
    }

    private void appButton(LinearLayout column, String key, String description) {
        String saved = prefs.getString(key, "");
        ComponentName component = ComponentName.unflattenFromString(saved);
        FrameLayout slot = new FrameLayout(this);
        column.addView(slot, new LinearLayout.LayoutParams(-1, dp(60)));
        slot.setContentDescription(description);
        ImageView button = new ImageView(this);
        if (component != null) {
            try {
                ActivityInfo info = getPackageManager().getActivityInfo(component, 0);
                button.setImageDrawable(info.loadIcon(getPackageManager()));
            } catch (Exception ignored) { button.setImageResource(R.drawable.icon); }
        } else button.setImageResource(R.drawable.icon);
        slot.addView(button, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
        slot.setOnClickListener(v -> {
            resetTimer();
            if (component == null) {
                Toast.makeText(this, "请先在设置中选择" + description + "应用", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                collapse();
                return;
            }
            try {
                if ("message".equals(key) && prefs.getBoolean("primaryMessage", true)) {
                    LauncherApps launcher = getSystemService(LauncherApps.class);
                    launcher.startMainActivity(component, Process.myUserHandle(), null, null);
                } else {
                    Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                }
                collapse();
            } catch (Exception e) { Toast.makeText(this, "主实例启动失败，请尝试关闭主实例选项", Toast.LENGTH_LONG).show(); }
        });
    }

    private MediaController selectedController() {
        try {
            MediaSessionManager sessions = getSystemService(MediaSessionManager.class);
            List<MediaController> active = sessions.getActiveSessions(new ComponentName(this, MediaListener.class));
            if (active.isEmpty()) return null;
            String preferred = "";
            ComponentName music = ComponentName.unflattenFromString(prefs.getString("music", ""));
            if (music != null) preferred = music.getPackageName();
            for (MediaController controller : active) {
                PlaybackState playback = controller.getPlaybackState();
                if (playback != null && playback.getState() == PlaybackState.STATE_PLAYING &&
                    controller.getPackageName().equals(preferred)) return controller;
            }
            for (MediaController controller : active) {
                PlaybackState playback = controller.getPlaybackState();
                if (playback != null && playback.getState() == PlaybackState.STATE_PLAYING) return controller;
            }
            for (MediaController controller : active)
                if (controller.getPackageName().equals(preferred)) return controller;
            return active.get(0);
        } catch (Exception e) { return null; }
    }

    private void media(boolean next) {
        try {
            MediaController target = selectedController();
            if (target == null) {
                Toast.makeText(this, "无媒体会话，请检查通知使用权", Toast.LENGTH_SHORT).show();
                return;
            }
            if (next) target.getTransportControls().skipToNext();
            else target.getTransportControls().skipToPrevious();
        } catch (Exception e) {
            Toast.makeText(this, "媒体控制暂不可用", Toast.LENGTH_SHORT).show();
        }
    }
    private void togglePlayback() {
        MediaController target = selectedController();
        if (target == null) {
            Toast.makeText(this, "无媒体会话，请检查通知使用权", Toast.LENGTH_SHORT).show();
            return;
        }
        PlaybackState playback = target.getPlaybackState();
        if (playback != null && playback.getState() == PlaybackState.STATE_PLAYING)
            target.getTransportControls().pause();
        else target.getTransportControls().play();
        handler.removeCallbacks(refreshMedia);
        handler.postDelayed(refreshMedia, 220);
    }

    private void open() {
        int mode = prefs.getInt("openMode", 0);
        setState(mode == 0 ? CONTROLS : mode == 1 ? APPS : lastState);
    }
    private void collapse() { setState(COLLAPSED); }
    private void setState(int next) {
        handler.removeCallbacks(collapseTask);
        if (state != COLLAPSED) lastState = state;
        state = next;
        render();
    }
    private void resetTimer() {
        handler.removeCallbacks(collapseTask);
        if (state != COLLAPSED) handler.postDelayed(collapseTask, Math.max(2, prefs.getInt("timeout", 12)) * 1000L);
    }
    private GradientDrawable background(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }
    private int configuredSizePercent() {
        return Math.max(80, Math.min(160, prefs.getInt("sizePercent", 100)));
    }
    private void adjustCollapsedPosition(int oldSize, int newSize) {
        if (oldSize == newSize) return;
        float density = getResources().getDisplayMetrics().density;
        int oldDiameter = Math.round(62 * density * oldSize / 100f);
        int newDiameter = Math.round(62 * density * newSize / 100f);
        collapsedX = Math.max(0, Math.min(collapsedX + (oldDiameter - newDiameter) / 2,
            getResources().getDisplayMetrics().widthPixels - newDiameter));
        collapsedY = Math.max(0, Math.min(collapsedY + (oldDiameter - newDiameter) / 2,
            getResources().getDisplayMetrics().heightPixels - newDiameter));
        prefs.edit().putInt("x", collapsedX).putInt("y", collapsedY)
            .putInt("positionSizePercent", newSize).apply();
    }
    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density * sizePercent / 100f + .5f);
    }
    @Override public void onDestroy() {
        running = false;
        refreshTile();
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(refresh);
        if (expandedDialog != null) expandedDialog.dismiss();
        else if (window != null) try { manager.removeView(window); } catch (Exception ignored) {}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
