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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.session.MediaController;
import android.media.MediaMetadata;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.Trace;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import java.util.List;

public class OverlayService extends Service {
    private static final String TAG = "YuChuangOverlay";
    public static final String ACTION_STATUS = "cn.yuchuang.floatcontrol.STATUS";
    private static volatile boolean running;
    private static volatile int visibleState = -1;
    private static final int OUTLINE = 0x66ffffff;
    private static final int ICON_INK = 0xff263238;
    private static final int BUTTON_SURFACE = 0xeef8fafb;
    private static final int EDGE_LEFT = 0, EDGE_RIGHT = 1;
    private WindowManager manager;
    private WindowManager.LayoutParams params;
    private View window;
    private Dialog expandedDialog;
    private FrameMonitor expandedFrames;
    private int expandedOpacity = -1;
    private int expandedSizePercent = -1;
    private int expandedContentLeft;
    private int expandedContentTop;
    private int expandedContentWidth;
    private int expandedContentHeight;
    private int sizePercent = 100;
    private int iconSizePercent = 100;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private HandlerThread mediaThread;
    private Handler mediaHandler;
    private volatile int mediaGeneration;
    private Bitmap displayedArt;
    private static final int COLLAPSED = 0, CONTROLS = 1, APPS = 2;
    private int state = COLLAPSED;
    private int lastState = CONTROLS;
    private float downX, downY;
    private int initialX, initialY;
    private int edgeSide, anchorY;
    private boolean moved, iconMultiTouch;
    private SpringAnimation settleXAnimation;
    private SpringAnimation settleYAnimation;
    private int settleGeneration;
    private ImageView cover;
    private ImageView playButton;
    private String lastMediaPackage = "";
    private int lastMediaState = -1;
    private final Runnable collapseTask = () -> setState(COLLAPSED);
    private final Runnable refreshMedia = new Runnable() {
        @Override public void run() {
            final int generation = mediaGeneration;
            Bitmap art = null;
            boolean playing = false;
            try {
                MediaController controller = selectedController();
                if (controller != null) {
                    MediaMetadata metadata = controller.getMetadata();
                    if (metadata != null) {
                        art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
                        if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    }
                    PlaybackState playback = controller.getPlaybackState();
                    playing = playback != null && playback.getState() == PlaybackState.STATE_PLAYING;
                    int playbackState = playback == null ? -1 : playback.getState();
                    if (!controller.getPackageName().equals(lastMediaPackage) ||
                        playbackState != lastMediaState) {
                        lastMediaPackage = controller.getPackageName();
                        lastMediaState = playbackState;
                        Diagnostics.event("media", "session", lastMediaPackage +
                            " state=" + playbackState);
                    }
                } else if (!lastMediaPackage.isEmpty()) {
                    lastMediaPackage = "";
                    lastMediaState = -1;
                    Diagnostics.event("media", "session_lost", "");
                }
            } catch (Exception e) {
                Log.w(TAG, "Media refresh failed", e);
                Diagnostics.error("media", "refresh_failed", e);
            }
            final Bitmap nextArt = art;
            final boolean nextPlaying = playing;
            handler.post(() -> {
                if (generation != mediaGeneration || state != CONTROLS ||
                    cover == null || playButton == null) return;
                if (displayedArt != nextArt) {
                    displayedArt = nextArt;
                    if (nextArt != null) {
                        cover.setImageBitmap(nextArt);
                        cover.setBackground(background(BUTTON_SURFACE, 10, 0x44ffffff));
                    } else cover.setImageResource(R.drawable.icon);
                }
                playButton.setImageResource(nextPlaying ? R.drawable.media_pause : R.drawable.media_play);
            });
            if (generation == mediaGeneration) mediaHandler.postDelayed(this, 1800);
        }
    };
    private final BroadcastReceiver refresh = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { render(); }
    };

    public static boolean isRunning() { return running; }
    public static String currentStateLabel() {
        return visibleState == 0 ? "已收起为悬浮图标" :
            visibleState == 1 ? "媒体控件已展开" :
            visibleState == 2 ? "快捷应用已展开" : "正在准备悬浮窗";
    }
    private void notifyStatus() {
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
    }
    private void refreshTile() {
        TileService.requestListeningState(this, new ComponentName(this, OverlayTileService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        Diagnostics.event("service", "created", "OverlayService");
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        mediaThread = new HandlerThread("YuChuangMedia");
        mediaThread.start();
        mediaHandler = new Handler(mediaThread.getLooper());
        sizePercent = configuredSizePercent();
        iconSizePercent = configuredIconSizePercent();
        manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("floating", "悬浮快捷栏", NotificationManager.IMPORTANCE_LOW));
        PendingIntent settings = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, OverlayService.class).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent recover = PendingIntent.getService(this, 2,
            new Intent(this, OverlayService.class).setAction("recover"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, "floating")
            .setSmallIcon(cn.yuchuang.floatcontrol.R.drawable.icon)
            .setContentTitle("驭窗浮控正在运行")
            .setContentIntent(settings)
            .addAction(new Notification.Action.Builder(null, "重置悬浮窗", recover).build())
            .addAction(new Notification.Action.Builder(null, "停止", stop).build())
            .build();
        startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        registerReceiver(refresh, new IntentFilter("cn.yuchuang.floatcontrol.REFRESH"), Context.RECEIVER_NOT_EXPORTED);
        Log.i(TAG, "Service created");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Diagnostics.event("service", "start_command", "action=" +
            (intent == null ? "null" : intent.getAction()) + " startId=" + startId);
        if (intent != null && "stop".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            Diagnostics.event("service", "overlay_permission_missing", "");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && "recover".equals(intent.getAction()) && params != null) {
            Log.i(TAG, "Manual window recovery, state=" + state);
            Diagnostics.event("window", "manual_recovery", "state=" + state);
            render();
            return START_STICKY;
        }
        if (params == null) {
            params = new WindowManager.LayoutParams(iconDp(62), iconDp(62),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                android.graphics.PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            int oldSize = prefs.getInt("positionIconSizePercent",
                prefs.getInt("positionSizePercent", 100));
            int oldDiameter = Math.round(62 * getResources().getDisplayMetrics().density * oldSize / 100f);
            edgeSide = prefs.getInt("edgeSide", prefs.getInt("x",
                getResources().getDisplayMetrics().widthPixels - oldDiameter) + oldDiameter / 2
                < getResources().getDisplayMetrics().widthPixels / 2 ? EDGE_LEFT : EDGE_RIGHT);
            anchorY = prefs.getInt("anchorY",
                prefs.getInt("y", getResources().getDisplayMetrics().heightPixels / 2 - oldDiameter / 2)
                    + oldDiameter / 2);
            render();
        }
        if (window != null) {
            running = true;
            refreshTile();
            notifyStatus();
        }
        Log.i(TAG, "Service started, window=" + (window != null) + ", state=" + state);
        return START_STICKY;
    }

    private void render() {
        if (params == null) return;
        cancelSettleAnimation();
        Trace.beginSection("YuChuang.overlayRender");
        try {
        Diagnostics.event("window", "render_begin", "state=" + state +
            " dialog=" + (expandedDialog != null) + " view=" + (window != null));
        sizePercent = configuredSizePercent();
        iconSizePercent = configuredIconSizePercent();
        mediaGeneration++;
        mediaHandler.removeCallbacks(refreshMedia);
        cover = null;
        playButton = null;
        displayedArt = null;
        boolean reuseExpanded = expandedDialog != null && state != COLLAPSED;
        if (expandedDialog != null && !reuseExpanded) {
            if (expandedFrames != null) {
                expandedFrames.stop();
                expandedFrames = null;
            }
            expandedDialog.dismiss();
            expandedDialog = null;
            expandedOpacity = -1;
            expandedSizePercent = -1;
        } else if (expandedDialog == null && window != null) {
            try { manager.removeView(window); }
            catch (Exception e) {
                Log.w(TAG, "Old window removal failed", e);
                Diagnostics.error("window", "remove_failed", e);
            }
        }
        window = null;
        final boolean compact = state == COLLAPSED;
        int width = compact ? iconDp(62) : dp(60);
        int height = compact ? iconDp(62) : dp(308);
        final int guard = compact ? 0 : protectionPx();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        edgeSide = allowedSide(edgeSide);
        anchorY = clampAnchorY(anchorY);
        params.x = edgeSide == EDGE_LEFT ? 0 : Math.max(0, screenWidth - width);
        params.y = Math.max(0, Math.min(anchorY - height / 2, screenHeight - height));
        params.width = width;
        params.height = height;
        if (prefs.getBoolean("keepScreenOn", false))
            params.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        else params.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        LinearLayout column = new LinearLayout(this) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    boolean collapse = state != COLLAPSED && outsideProtectionExceeded(event, this);
                    Diagnostics.event("touch", "outside", "collapse=" + collapse);
                    if (collapse) collapse();
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
            String[] order = OverlayOrder.get(prefs, OverlayOrder.MEDIA);
            for (int i = 0; i < order.length; i++) {
                if (i == 2) switchButton(column);
                mediaItem(column, order[i]);
            }
            mediaHandler.post(refreshMedia);
        } else {
            String[] order = OverlayOrder.get(prefs, OverlayOrder.APPS);
            for (int i = 0; i < order.length; i++) {
                if (i == 2) switchButton(column);
                appItem(column, order[i]);
            }
        }
        window = column;
        try {
            if (compact) manager.addView(window, params);
            else {
                final int opacity = Math.max(0, Math.min(80, prefs.getInt("opacity", 24)));
                final int dialogWidth = width + guard;
                final int dialogHeight = height + guard * 2;
                final int dialogX = edgeSide == EDGE_LEFT ? 0 : Math.max(0, screenWidth - dialogWidth);
                final int dialogY = Math.max(0, Math.min(params.y - guard, screenHeight - dialogHeight));
                expandedContentLeft = params.x - dialogX;
                expandedContentTop = params.y - dialogY;
                expandedContentWidth = width;
                expandedContentHeight = height;
                if (!reuseExpanded) {
                    expandedDialog = new Dialog(this, R.style.FloatingOverlayTheme) {
                        private final int slop = dragSlop();
                        private float startX, startY;
                        private int startWindowX, startWindowY;
                        private boolean dragging, singleTouch, guardTouch;

                        private boolean isInsideContent(MotionEvent event) {
                            float x = event.getX();
                            float y = event.getY();
                            return x >= expandedContentLeft
                                && x < expandedContentLeft + expandedContentWidth
                                && y >= expandedContentTop
                                && y < expandedContentTop + expandedContentHeight;
                        }

                        private boolean handleOutside(MotionEvent event) {
                            if (event.getActionMasked() != MotionEvent.ACTION_OUTSIDE) return false;
                            boolean shouldCollapse = state != COLLAPSED &&
                                outsideProtectionExceeded(event, getWindow().getDecorView());
                            Diagnostics.event("touch", "outside_dialog", "collapse=" + shouldCollapse);
                            if (shouldCollapse)
                                collapse();
                            return true;
                        }

                        @Override public boolean dispatchTouchEvent(MotionEvent event) {
                            if (handleOutside(event)) return true;
                            switch (event.getActionMasked()) {
                                case MotionEvent.ACTION_DOWN:
                                    cancelSettleAnimation();
                                    guardTouch = !isInsideContent(event);
                                    Diagnostics.event("touch", "expanded_down", "guard=" + guardTouch +
                                        " x=" + Math.round(event.getRawX()) +
                                        " y=" + Math.round(event.getRawY()));
                                    if (guardTouch) return true;
                                    startX = event.getRawX();
                                    startY = event.getRawY();
                                    WindowManager.LayoutParams attributes = getWindow().getAttributes();
                                    startWindowX = attributes.x;
                                    startWindowY = attributes.y;
                                    dragging = false;
                                    singleTouch = true;
                                    resetTimer();
                                    return super.dispatchTouchEvent(event);
                                case MotionEvent.ACTION_POINTER_DOWN:
                                    if (guardTouch) return true;
                                    Diagnostics.event("touch", "expanded_multitouch", "");
                                    singleTouch = false;
                                    if (!dragging) {
                                        MotionEvent cancel = MotionEvent.obtain(event);
                                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                                        super.dispatchTouchEvent(cancel);
                                        cancel.recycle();
                                    }
                                    return true;
                                case MotionEvent.ACTION_POINTER_UP:
                                    if (guardTouch) return true;
                                    return true;
                                case MotionEvent.ACTION_MOVE:
                                    if (guardTouch) return true;
                                    if (!singleTouch) return true;
                                    float dx = event.getRawX() - startX;
                                    float dy = event.getRawY() - startY;
                                    if (!dragging && dx * dx + dy * dy > slop * slop) {
                                        MotionEvent cancel = MotionEvent.obtain(event);
                                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                                        super.dispatchTouchEvent(cancel);
                                        cancel.recycle();
                                        dragging = true;
                                        Diagnostics.event("touch", "expanded_drag_start",
                                            "distance=" + Math.round(Math.hypot(dx, dy)));
                                        handler.removeCallbacks(collapseTask);
                                    }
                                    if (dragging) {
                                        WindowManager.LayoutParams moving = getWindow().getAttributes();
                                        moving.x = Math.max(0, Math.min(startWindowX + Math.round(dx),
                                            screenWidth() - moving.width));
                                        moving.y = Math.max(0, Math.min(startWindowY + Math.round(dy),
                                            screenHeight() - moving.height));
                                        getWindow().setAttributes(moving);
                                        return true;
                                    }
                                    return super.dispatchTouchEvent(event);
                                case MotionEvent.ACTION_UP:
                                case MotionEvent.ACTION_CANCEL:
                                    Diagnostics.event("touch", event.getActionMasked() == MotionEvent.ACTION_UP
                                        ? "expanded_up" : "expanded_cancel",
                                        "guard=" + guardTouch + " dragging=" + dragging +
                                        " multi=" + !singleTouch);
                                    if (guardTouch) {
                                        guardTouch = false;
                                        return true;
                                    }
                                    if (dragging) {
                                        WindowManager.LayoutParams end = getWindow().getAttributes();
                                        settlePosition(end.x + end.width / 2, end.y + end.height / 2);
                                        int targetX = edgeSide == EDGE_LEFT ? 0 :
                                            Math.max(0, screenWidth() - end.width);
                                        int targetY = Math.max(0, Math.min(anchorY - end.height / 2,
                                            screenHeight() - end.height));
                                        animatePosition(true, end.x, end.y, targetX, targetY,
                                            OverlayService.this::resetTimer);
                                        Diagnostics.event("touch", "expanded_drag_end",
                                            "edge=" + edgeSide + " anchorY=" + anchorY);
                                        dragging = false;
                                        return true;
                                    }
                                    if (!singleTouch) return true;
                                    return super.dispatchTouchEvent(event);
                                default:
                                    return super.dispatchTouchEvent(event);
                            }
                        }

                        @Override public boolean onTouchEvent(MotionEvent event) {
                            if (handleOutside(event)) return true;
                            return super.onTouchEvent(event);
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
                column.setBackground(background((opacity * 255 / 100) << 24, 19, OUTLINE));
                overlayWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                FrameLayout touchSurface = new FrameLayout(this);
                touchSurface.setClipChildren(false);
                FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(width, height);
                contentParams.leftMargin = expandedContentLeft;
                contentParams.topMargin = expandedContentTop;
                touchSurface.addView(column, contentParams);
                expandedDialog.setContentView(touchSurface);
                overlayWindow.setBackgroundBlurRadius(0);
                WindowManager.LayoutParams dialogParams = overlayWindow.getAttributes();
                dialogParams.width = dialogWidth;
                dialogParams.height = dialogHeight;
                dialogParams.x = dialogX;
                dialogParams.y = dialogY;
                dialogParams.flags = (dialogParams.flags & ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) | params.flags;
                overlayWindow.setAttributes(dialogParams);
                if (!reuseExpanded) {
                    expandedDialog.show();
                    expandedFrames = FrameMonitor.attach(overlayWindow, "expanded_overlay");
                }
                if (expandedFrames != null)
                    expandedFrames.setContext(state == CONTROLS ? "media" : "apps");
                expandedOpacity = opacity;
                expandedSizePercent = sizePercent;
            }
            Log.i(TAG, "Window rendered, state=" + state + ", edge=" + edgeSide +
                ", x=" + params.x + ", y=" + params.y + ", size=" + width + "x" + height);
            Diagnostics.event("window", "rendered", "state=" + state + " edge=" + edgeSide +
                " x=" + params.x + " y=" + params.y + " size=" + width + "x" + height +
                " guard=" + guard + " reused=" + reuseExpanded);
            visibleState = state;
            if (running) notifyStatus();
        }
        catch (Exception e) {
            Log.e(TAG, "Failed to create " + (compact ? "icon" : "expanded") + " window", e);
            Diagnostics.error("window", compact ? "icon_create_failed" : "dialog_create_failed", e);
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
        } finally {
            Trace.endSection();
        }
    }

    private void attachDrag(View view, boolean openOnClick) {
        final int slop = dragSlop();
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelSettleAnimation();
                    Log.i(TAG, "Icon DOWN x=" + event.getRawX() + " y=" + event.getRawY());
                    Diagnostics.event("touch", "icon_down", "x=" + Math.round(event.getRawX()) +
                        " y=" + Math.round(event.getRawY()));
                    downX = event.getRawX(); downY = event.getRawY();
                    initialX = params.x; initialY = params.y; moved = false; iconMultiTouch = false;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    iconMultiTouch = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (iconMultiTouch) return true;
                    int dx = (int)(event.getRawX() - downX);
                    int dy = (int)(event.getRawY() - downY);
                    if (!moved && dx * dx + dy * dy > slop * slop) {
                        moved = true;
                        Diagnostics.event("touch", "icon_drag_start", "dx=" + dx + " dy=" + dy);
                    }
                    if (moved) {
                        params.x = Math.max(0, Math.min(initialX + dx,
                            screenWidth() - params.width));
                        params.y = Math.max(0, Math.min(initialY + dy,
                            screenHeight() - params.height));
                        manager.updateViewLayout(window, params);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    return true;
                case MotionEvent.ACTION_UP:
                    Log.i(TAG, "Icon UP moved=" + moved + " multi=" + iconMultiTouch);
                    Diagnostics.event("touch", "icon_up",
                        "moved=" + moved + " multi=" + iconMultiTouch);
                    if (moved) {
                        settlePosition(params.x + params.width / 2, params.y + params.height / 2);
                        int targetX = edgeSide == EDGE_LEFT ? 0 :
                            Math.max(0, screenWidth() - params.width);
                        int targetY = Math.max(0, Math.min(anchorY - params.height / 2,
                            screenHeight() - params.height));
                        animatePosition(false, params.x, params.y, targetX, targetY, null);
                        Diagnostics.event("touch", "icon_drag_end",
                            "edge=" + edgeSide + " anchorY=" + anchorY);
                    }
                    else if (!iconMultiTouch && openOnClick) open();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    Log.i(TAG, "Icon CANCEL moved=" + moved);
                    Diagnostics.event("touch", "icon_cancel", "moved=" + moved);
                    if (moved) {
                        settlePosition(params.x + params.width / 2, params.y + params.height / 2);
                        int targetX = edgeSide == EDGE_LEFT ? 0 :
                            Math.max(0, screenWidth() - params.width);
                        int targetY = Math.max(0, Math.min(anchorY - params.height / 2,
                            screenHeight() - params.height));
                        animatePosition(false, params.x, params.y, targetX, targetY, null);
                    }
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

    private void mediaItem(LinearLayout column, String item) {
        if ("next".equals(item) || "previous".equals(item)) {
            mediaSkipButton(column, "previous".equals(item));
        } else if ("cover".equals(item)) {
            FrameLayout slot = new FrameLayout(this);
            column.addView(slot, new LinearLayout.LayoutParams(-1, dp(60)));
            cover = new ImageView(this);
            cover.setContentDescription("专辑封面");
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setImageResource(R.drawable.icon);
            cover.setBackground(background(BUTTON_SURFACE, 10, 0x44ffffff));
            cover.setClipToOutline(true);
            slot.addView(cover, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
        } else if ("play".equals(item)) {
            FrameLayout slot = new FrameLayout(this);
            column.addView(slot, new LinearLayout.LayoutParams(-1, dp(60)));
            slot.setContentDescription("播放或暂停");
            playButton = new ImageView(this);
            playButton.setImageResource(R.drawable.media_play);
            playButton.setImageTintList(ColorStateList.valueOf(ICON_INK));
            playButton.setPadding(dp(9), dp(9), dp(9), dp(9));
            playButton.setBackground(background(BUTTON_SURFACE, 12, 0x44ffffff));
            slot.addView(playButton, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
            slot.setOnClickListener(v -> { resetTimer(); togglePlayback(); });
        }
    }

    private void appItem(LinearLayout column, String item) {
        if (!"settings".equals(item)) {
            appButton(column, item, "message".equals(item) ? "沟通" :
                "navigation".equals(item) ? "导航" : "音乐");
            return;
        }
        FrameLayout slot = new FrameLayout(this);
        column.addView(slot, new LinearLayout.LayoutParams(-1, dp(60)));
        ImageView settings = new ImageView(this);
        settings.setImageResource(android.R.drawable.ic_menu_preferences);
        settings.setImageTintList(ColorStateList.valueOf(ICON_INK));
        settings.setPadding(dp(11), dp(11), dp(11), dp(11));
        settings.setBackground(background(BUTTON_SURFACE, 12, 0x44ffffff));
        slot.addView(settings, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
        slot.setContentDescription("设置");
        slot.setOnClickListener(v -> {
            collapse();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
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
            Diagnostics.event("app", "shortcut_tapped", "slot=" + key +
                " component=" + (component == null ? "unset" : component.flattenToShortString()));
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
                Diagnostics.event("app", "launch_requested", "slot=" + key +
                    " component=" + component.flattenToShortString());
                collapse();
            } catch (Exception e) {
                Diagnostics.error("app", "launch_failed", e);
                Toast.makeText(this, "主实例启动失败，请尝试关闭主实例选项", Toast.LENGTH_LONG).show();
            }
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
        } catch (Exception e) {
            Diagnostics.error("media", "session_query_failed", e);
            return null;
        }
    }

    private void media(boolean next) {
        mediaHandler.post(() -> {
            Trace.beginSection(next ? "YuChuang.mediaNext" : "YuChuang.mediaPrevious");
            try {
                MediaController target = selectedController();
                if (target == null) {
                    mediaMessage("无媒体会话，请检查通知使用权");
                    return;
                }
                if (next) target.getTransportControls().skipToNext();
                else target.getTransportControls().skipToPrevious();
                Diagnostics.event("media", next ? "next" : "previous", target.getPackageName());
            } catch (Exception e) {
                Log.w(TAG, "Media skip failed", e);
                Diagnostics.error("media", "skip_failed", e);
                mediaMessage("媒体控制暂不可用");
            } finally {
                Trace.endSection();
            }
        });
    }
    private void togglePlayback() {
        mediaHandler.post(() -> {
            Trace.beginSection("YuChuang.mediaToggle");
            try {
                MediaController target = selectedController();
                if (target == null) {
                    mediaMessage("无媒体会话，请检查通知使用权");
                    return;
                }
                PlaybackState playback = target.getPlaybackState();
                if (playback != null && playback.getState() == PlaybackState.STATE_PLAYING)
                    target.getTransportControls().pause();
                else target.getTransportControls().play();
                Diagnostics.event("media", "playback_toggled", target.getPackageName());
            } catch (Exception e) {
                Log.w(TAG, "Playback toggle failed", e);
                Diagnostics.error("media", "playback_toggle_failed", e);
                mediaMessage("媒体控制暂不可用");
            } finally {
                Trace.endSection();
            }
        });
        mediaHandler.removeCallbacks(refreshMedia);
        mediaHandler.postDelayed(refreshMedia, 220);
    }
    private void mediaMessage(String message) {
        handler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void open() {
        int mode = prefs.getInt("openMode", 0);
        Log.i(TAG, "Opening icon, mode=" + mode);
        setState(mode == 0 ? CONTROLS : mode == 1 ? APPS : lastState);
    }
    private void collapse() { setState(COLLAPSED); }
    private boolean outsideProtectionExceeded(MotionEvent event, View overlay) {
        int margin = Math.max(0, Math.min(80, prefs.getInt("outsideProtectionDp", 24)));
        if (margin == 0) return true;
        int[] location = new int[2];
        overlay.getLocationOnScreen(location);
        float x = event.getRawX(), y = event.getRawY();
        float dx = Math.max(Math.max(location[0] - x, x - location[0] - overlay.getWidth()), 0);
        float dy = Math.max(Math.max(location[1] - y, y - location[1] - overlay.getHeight()), 0);
        return dx * dx + dy * dy > dp(margin) * dp(margin);
    }
    private int protectionPx() {
        return dp(Math.max(0, Math.min(80, prefs.getInt("outsideProtectionDp", 24))));
    }
    private void setState(int next) {
        Trace.beginSection("YuChuang.overlaySetState");
        try {
        Diagnostics.event("window", "state_changed", "from=" + state + " to=" + next);
        handler.removeCallbacks(collapseTask);
        if (state != COLLAPSED) lastState = state;
        state = next;
        render();
        } finally {
            Trace.endSection();
        }
    }
    private void resetTimer() {
        handler.removeCallbacks(collapseTask);
        if (state != COLLAPSED && !prefs.getBoolean("residentExpanded", false))
            handler.postDelayed(collapseTask, Math.max(2, prefs.getInt("timeout", 12)) * 1000L);
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
    private int configuredIconSizePercent() {
        return Math.max(70, Math.min(160, prefs.getInt("iconSizePercent", 100)));
    }
    private int screenWidth() { return getResources().getDisplayMetrics().widthPixels; }
    private int screenHeight() { return getResources().getDisplayMetrics().heightPixels; }
    private int allowedSide(int requested) {
        int mode = prefs.getInt("edgeMode", 0);
        return mode == 1 ? EDGE_LEFT : mode == 2 ? EDGE_RIGHT : requested;
    }
    private int clampAnchorY(int center) {
        int half = Math.min(screenHeight() / 2, Math.max(iconDp(62), dp(308)) / 2);
        return Math.max(half, Math.min(center, screenHeight() - half));
    }
    private void settlePosition(int centerX, int centerY) {
        edgeSide = allowedSide(centerX < screenWidth() / 2 ? EDGE_LEFT : EDGE_RIGHT);
        anchorY = clampAnchorY(centerY);
        prefs.edit().putInt("edgeSide", edgeSide).putInt("anchorY", anchorY).apply();
    }
    private void cancelSettleAnimation() {
        settleGeneration++;
        if (settleXAnimation != null) {
            settleXAnimation.cancel();
            settleXAnimation = null;
        }
        if (settleYAnimation != null) {
            settleYAnimation.cancel();
            settleYAnimation = null;
        }
    }
    private void animatePosition(boolean expanded, int startX, int startY,
                                 int targetX, int targetY, Runnable onEnd) {
        cancelSettleAnimation();
        if (startX == targetX && startY == targetY) {
            if (onEnd != null) onEnd.run();
            return;
        }
        final int generation = settleGeneration;
        final float[] position = {startX, startY};
        final int[] remaining = {2};
        settleXAnimation = new SpringAnimation(new FloatValueHolder(startX))
            .setSpring(new SpringForce(targetX).setDampingRatio(0.92f)
                .setStiffness(SpringForce.STIFFNESS_MEDIUM));
        settleYAnimation = new SpringAnimation(new FloatValueHolder(startY))
            .setSpring(new SpringForce(targetY).setDampingRatio(0.92f)
                .setStiffness(SpringForce.STIFFNESS_MEDIUM));
        settleXAnimation.addUpdateListener((animation, value, velocity) -> {
            if (generation != settleGeneration) return;
            position[0] = value;
            applyAnimatedPosition(expanded, Math.round(position[0]), Math.round(position[1]));
        });
        settleYAnimation.addUpdateListener((animation, value, velocity) -> {
            if (generation != settleGeneration) return;
            position[1] = value;
            applyAnimatedPosition(expanded, Math.round(position[0]), Math.round(position[1]));
        });
        DynamicAnimation.OnAnimationEndListener end = (animation, canceled, value, velocity) -> {
            if (generation != settleGeneration || canceled || --remaining[0] != 0) return;
            applyAnimatedPosition(expanded, targetX, targetY);
            settleXAnimation = null;
            settleYAnimation = null;
            if (onEnd != null) onEnd.run();
        };
        settleXAnimation.addEndListener(end);
        settleYAnimation.addEndListener(end);
        Diagnostics.event("touch", "settle_animation",
            "expanded=" + expanded + " from=" + startX + "," + startY +
                " to=" + targetX + "," + targetY);
        settleXAnimation.start();
        settleYAnimation.start();
    }
    private void applyAnimatedPosition(boolean expanded, int x, int y) {
        try {
            if (expanded) {
                if (expandedDialog == null) return;
                WindowManager.LayoutParams moving = expandedDialog.getWindow().getAttributes();
                moving.x = Math.max(0, Math.min(x, screenWidth() - moving.width));
                moving.y = Math.max(0, Math.min(y, screenHeight() - moving.height));
                expandedDialog.getWindow().setAttributes(moving);
            } else if (window != null && params != null) {
                params.x = Math.max(0, Math.min(x, screenWidth() - params.width));
                params.y = Math.max(0, Math.min(y, screenHeight() - params.height));
                manager.updateViewLayout(window, params);
            }
        } catch (RuntimeException error) {
            Diagnostics.error("window", "settle_update_failed", error);
            cancelSettleAnimation();
        }
    }
    private int iconDp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density * iconSizePercent / 100f + .5f);
    }
    private int dragSlop() {
        int minimum = (int)(12 * getResources().getDisplayMetrics().density + .5f);
        return Math.max(ViewConfiguration.get(this).getScaledTouchSlop(), minimum);
    }
    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density * sizePercent / 100f + .5f);
    }
    @Override public void onDestroy() {
        cancelSettleAnimation();
        Log.i(TAG, "Service destroyed, state=" + state);
        Diagnostics.event("service", "destroyed", "state=" + state);
        running = false;
        visibleState = -1;
        refreshTile();
        notifyStatus();
        handler.removeCallbacksAndMessages(null);
        mediaGeneration++;
        mediaHandler.removeCallbacksAndMessages(null);
        mediaThread.quitSafely();
        unregisterReceiver(refresh);
        if (expandedFrames != null) {
            expandedFrames.stop();
            expandedFrames = null;
        }
        if (expandedDialog != null) expandedDialog.dismiss();
        else if (window != null) try { manager.removeView(window); } catch (Exception ignored) {}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
