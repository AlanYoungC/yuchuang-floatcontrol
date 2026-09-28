package cn.yuchuang.floatcontrol;

import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
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
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class OverlayService extends Service {
    private static final String TAG = "YuChuangOverlay";
    public static final String ACTION_STATUS = "cn.yuchuang.floatcontrol.STATUS";
    private static volatile boolean running;
    private static volatile int visibleState = -1;
    private static final int EDGE_LEFT = 0, EDGE_RIGHT = 1;
    private static final int COLLAPSED = 0, CONTROLS = 1, APPS = 2;

    // Geometry at 100% size, in dp.
    private static final int ICON_DP = 62;
    private static final int PANEL_WIDTH_DP = 68;
    private static final int PANEL_PADDING_DP = 5;
    private static final int SLOT_DP = 66;
    private static final int FACE_DP = 56;
    private static final int SWITCH_DP = 58;
    private static final int PANEL_HEIGHT_DP = PANEL_PADDING_DP * 2 + SLOT_DP * 5;
    private static final int PANEL_RADIUS_DP = 24;
    private static final int FACE_RADIUS_DP = 16;
    private static final int BLUR_RADIUS_DP = 28;

    private WindowManager manager;
    private SharedPreferences prefs;
    private Vibrator vibrator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private HandlerThread mediaThread;
    private Handler mediaHandler;
    private final Rect area = new Rect();

    private FrameLayout iconView;
    private WindowManager.LayoutParams iconParams;
    private View guardView;
    private WindowManager.LayoutParams guardParams;
    private PanelDialog panelDialog;
    private FrameLayout panelRoot;
    private LinearLayout mediaColumn, appsColumn;
    private FrameMonitor panelFrames;
    private Ui.Overlay palette;
    private boolean blurEnabled;
    private final Consumer<Boolean> blurListener = enabled -> {
        if (enabled == blurEnabled) return;
        blurEnabled = enabled;
        Diagnostics.event("window", "blur_availability", "enabled=" + enabled);
        if (panelDialog != null && palette != null && palette.glass) rebuildPanel();
    };

    private int sizePercent = 100;
    private int iconSizePercent = 100;
    private int state = COLLAPSED;
    private int lastState = CONTROLS;
    private int edgeSide, anchorY;
    private boolean panelDragging;
    private float downX, downY;
    private int initialX, initialY;
    private boolean moved, iconMultiTouch;
    private long lastIconTapMs = 0;
    private int lastNightMode = -1;
    private SpringAnimation settleXAnimation;
    private SpringAnimation settleYAnimation;
    private int settleGeneration;

    private ImageView coverFace, playFace;
    private final List<ImageView> skipFaces = new ArrayList<>();
    private final Map<String, Drawable> appIcons = new HashMap<>();
    private boolean mediaAvailable;
    private boolean mediaPlaying;
    private Bitmap mediaArt;
    private String mediaPackage = "";

    private android.animation.ValueAnimator liquidGlassAnimator;
    private float liquidGlassPhase = 0f;

    // Media thread only.
    private MediaSessionManager sessions;
    private final List<MediaController> watched = new ArrayList<>();
    private boolean sessionsListening;
    private String lastMediaPackage = "";
    private int lastMediaState = -1;
    private String lastArtKey = "";
    private Bitmap lastScaledArt;
    private volatile int artTargetPx;

    private final Runnable collapseTask = () -> setState(COLLAPSED);
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState playback) { scheduleMediaRefresh(0); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { scheduleMediaRefresh(0); }
        @Override public void onSessionDestroyed() { scheduleMediaRefresh(0); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = controllers -> {
        watchControllers(controllers);
        scheduleMediaRefresh(0);
    };
    private final Runnable refreshMedia = new Runnable() {
        @Override public void run() {
            Bitmap art = null;
            String artKey = "";
            boolean playing = false;
            boolean available = false;
            String packageName = "";
            try {
                MediaController controller = selectedController();
                if (controller != null) {
                    available = true;
                    packageName = controller.getPackageName();
                    MediaMetadata metadata = controller.getMetadata();
                    if (metadata != null) {
                        art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
                        if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                        if (art != null) {
                            artKey = packageName + "|" + metadata.getString(MediaMetadata.METADATA_KEY_TITLE) +
                                "|" + metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) +
                                "|" + art.getWidth() + "x" + art.getHeight();
                        }
                    }
                    PlaybackState playback = controller.getPlaybackState();
                    int playbackState = playback == null ? -1 : playback.getState();
                    playing = playbackState == PlaybackState.STATE_PLAYING;
                    if (!packageName.equals(lastMediaPackage) || playbackState != lastMediaState) {
                        lastMediaPackage = packageName;
                        lastMediaState = playbackState;
                        Diagnostics.event("media", "session", packageName + " state=" + playbackState);
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
            final Bitmap nextArt = scaledArt(art, artKey);
            final boolean nextPlaying = playing;
            final boolean nextAvailable = available;
            final String nextPackage = packageName;
            handler.post(() -> applyMedia(nextAvailable, nextPlaying, nextArt, nextPackage));
        }
    };
    private final BroadcastReceiver refresh = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { applySettings(); }
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
        vibrator = getSystemService(Vibrator.class);
        sessions = getSystemService(MediaSessionManager.class);
        mediaThread = new HandlerThread("YuChuangMedia");
        mediaThread.start();
        mediaHandler = new Handler(mediaThread.getLooper());
        manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        blurEnabled = manager.isCrossWindowBlurEnabled();
        manager.addCrossWindowBlurEnabledListener(getMainExecutor(), blurListener);
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
            .setSmallIcon(R.drawable.icon)
            .setContentTitle("驭窗浮控正在运行")
            .setContentIntent(settings)
            .addAction(new Notification.Action.Builder(null, "重置悬浮窗", recover).build())
            .addAction(new Notification.Action.Builder(null, "停止", stop).build())
            .build();
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
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
        if (intent != null && "recover".equals(intent.getAction()) && iconView != null) {
            Log.i(TAG, "Manual window recovery, state=" + state);
            Diagnostics.event("window", "manual_recovery", "state=" + state);
            destroyWindows();
            setupWindows();
            return START_STICKY;
        }
        if (iconView == null) {
            updateArea();
            loadPosition();
            setupWindows();
            startMediaWatch();
        }
        if (iconView != null) {
            running = true;
            refreshTile();
            notifyStatus();
        }
        Log.i(TAG, "Service started, window=" + (iconView != null) + ", state=" + state);
        return START_STICKY;
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (iconView == null) return;
        float fraction = anchorFraction();
        updateArea();
        anchorY = clampAnchorY(Math.round(area.top + fraction * area.height()));
        int newNightMode = configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean nightChanged = lastNightMode != -1 && newNightMode != lastNightMode;
        lastNightMode = newNightMode;
        Diagnostics.event("window", "configuration_changed", "orientation=" +
            configuration.orientation + " night=" + newNightMode + " area=" + area.toShortString());
        applySettings();
    }

    private void setupWindows() {
        try {
            iconSizePercent = configuredIconSizePercent();
            ensureIcon();
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to create icon window", e);
            Diagnostics.error("window", "icon_create_failed", e);
            iconView = null;
            stopSelf();
            return;
        }
        showState();
    }

    private void destroyWindows() {
        cancelSettleAnimation();
        handler.removeCallbacks(collapseTask);
        if (panelFrames != null) {
            panelFrames.stop();
            panelFrames = null;
        }
        if (panelDialog != null) {
            try { panelDialog.dismiss(); } catch (RuntimeException ignored) {}
            panelDialog = null;
        }
        panelRoot = null;
        mediaColumn = appsColumn = null;
        coverFace = playFace = null;
        skipFaces.clear();
        removeWindow(guardView);
        guardView = null;
        removeWindow(iconView);
        iconView = null;
    }

    private void removeWindow(View view) {
        if (view == null) return;
        try { manager.removeView(view); }
        catch (RuntimeException e) {
            Log.w(TAG, "Window removal failed", e);
            Diagnostics.error("window", "remove_failed", e);
        }
    }

    private void applySettings() {
        if (iconView == null) return;
        Trace.beginSection("YuChuang.overlayApplySettings");
        try {
            iconSizePercent = configuredIconSizePercent();
            sizePercent = effectiveSizePercent();
            edgeSide = allowedSide(edgeSide);
            anchorY = clampAnchorY(anchorY);
            appIcons.clear();
            if (panelDialog != null) rebuildPanel();
            showState();
        } finally {
            Trace.endSection();
        }
    }

    private void showState() {
        if (iconView == null) return;
        Trace.beginSection("YuChuang.overlayShow");
        try {
            cancelSettleAnimation();
            if (state == COLLAPSED) {
                hideGuard();
                if (panelDialog != null) panelDialog.hide();
                layoutIcon();
                iconView.setVisibility(View.VISIBLE);
            } else {
                ensurePanel();
                showColumn();
                positionPanel();
                panelDialog.show();
                syncGuard();
                iconView.setVisibility(View.GONE);
                resetTimer();
                if (state == CONTROLS) startMediaWatch();
            }
            visibleState = state;
            Diagnostics.event("window", "rendered", "state=" + state + " edge=" + edgeSide +
                " anchorY=" + anchorY + " size=" + sizePercent + " area=" + area.toShortString());
            if (running) notifyStatus();
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to show state " + state, e);
            Diagnostics.error("window", state == COLLAPSED ? "icon_show_failed" : "panel_show_failed", e);
            if (state != COLLAPSED) {
                destroyPanel();
                state = COLLAPSED;
                showState();
            } else {
                stopSelf();
            }
        } finally {
            Trace.endSection();
        }
    }

    private void ensureIcon() {
        if (iconView != null) return;
        iconView = new FrameLayout(this);
        iconView.setContentDescription("展开驭窗浮控");
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.floating_icon);
        icon.setScaleType(ImageView.ScaleType.FIT_XY);
        iconView.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        attachDrag(iconView);
        iconParams = new WindowManager.LayoutParams(iconDp(ICON_DP), iconDp(ICON_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        iconParams.gravity = Gravity.TOP | Gravity.LEFT;
        iconParams.x = edgeX(iconParams.width);
        iconParams.y = yFor(iconParams.height);
        manager.addView(iconView, iconParams);
    }

    private void layoutIcon() {
        iconParams.width = iconDp(ICON_DP);
        iconParams.height = iconDp(ICON_DP);
        iconParams.x = edgeX(iconParams.width);
        iconParams.y = yFor(iconParams.height);
        iconParams.flags = keepScreenFlag(iconParams.flags);
        manager.updateViewLayout(iconView, iconParams);
    }

    private void ensureGuard() {
        if (guardView != null) return;
        guardView = new View(this);
        guardView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
                Diagnostics.event("touch", "guard_down", "");
            return true;
        });
        guardView.setVisibility(View.GONE);
        guardParams = new WindowManager.LayoutParams(1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        guardParams.gravity = Gravity.TOP | Gravity.LEFT;
        manager.addView(guardView, guardParams);
    }

    private void syncGuard() {
        if (guardView == null || panelDialog == null) return;
        int guard = state == COLLAPSED || panelDragging ? 0 : protectionPx();
        if (guard == 0) {
            hideGuard();
            return;
        }
        WindowManager.LayoutParams panel = panelDialog.getWindow().getAttributes();
        guardParams.width = panel.width + guard;
        guardParams.height = panel.height + guard * 2;
        guardParams.x = edgeSide == EDGE_LEFT ? panel.x : panel.x - guard;
        guardParams.y = panel.y - guard;
        guardView.setVisibility(View.VISIBLE);
        manager.updateViewLayout(guardView, guardParams);
    }

    private void hideGuard() {
        if (guardView != null && guardView.getVisibility() != View.GONE)
            guardView.setVisibility(View.GONE);
    }

    private void ensurePanel() {
        if (panelDialog != null) return;
        // The guard window has to be added first so it stays below the panel.
        ensureGuard();
        panelDialog = new PanelDialog();
        panelDialog.setCanceledOnTouchOutside(false);
        Window window = panelDialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        window.setGravity(Gravity.TOP | Gravity.LEFT);
        window.setWindowAnimations(0);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        panelRoot = new FrameLayout(this);
        panelDialog.setContentView(panelRoot);
        rebuildPanel();
        positionPanel();
        panelDialog.show();
        panelFrames = FrameMonitor.attach(window, "expanded_overlay");
    }

    private void destroyPanel() {
        if (panelFrames != null) {
            panelFrames.stop();
            panelFrames = null;
        }
        if (panelDialog != null) {
            try { panelDialog.dismiss(); } catch (RuntimeException ignored) {}
        }
        panelDialog = null;
        panelRoot = null;
        mediaColumn = appsColumn = null;
        coverFace = playFace = null;
        skipFaces.clear();
        hideGuard();
    }

    private void rebuildPanel() {
        Trace.beginSection("YuChuang.overlayBuildPanel");
        try {
            sizePercent = effectiveSizePercent();
            palette = Ui.overlay(this, Ui.overlayStyle(this), opacity(), blurEnabled);
            artTargetPx = dp(FACE_DP);
            lastArtKey = "";
            lastScaledArt = null;
            coverFace = null;
            playFace = null;
            skipFaces.clear();
            mediaColumn = column();
            String[] media = OverlayOrder.get(prefs, OverlayOrder.MEDIA);
            for (int i = 0; i < media.length; i++) {
                if (i == 2) switchSlot(mediaColumn);
                mediaItem(mediaColumn, media[i]);
            }
            appsColumn = column();
            String[] apps = OverlayOrder.get(prefs, OverlayOrder.APPS);
            for (int i = 0; i < apps.length; i++) {
                if (i == 2) switchSlot(appsColumn);
                appItem(appsColumn, apps[i]);
            }
            panelRoot.removeAllViews();
            panelRoot.addView(mediaColumn, new FrameLayout.LayoutParams(-1, -1));
            panelRoot.addView(appsColumn, new FrameLayout.LayoutParams(-1, -1));
            bindMediaViews();
            showColumn();
            Window window = panelDialog.getWindow();
            window.setBackgroundDrawable(panelBackground());
            window.setBackgroundBlurRadius(palette.blurred
                ? Math.round(BLUR_RADIUS_DP * getResources().getDisplayMetrics().density) : 0);
            window.setElevation(palette.elevationDp * getResources().getDisplayMetrics().density);
            Diagnostics.event("window", "panel_built", "style=" + palette.style +
                " blur=" + palette.blurred + " size=" + sizePercent);
        } finally {
            Trace.endSection();
        }
    }

    private void positionPanel() {
        Window window = panelDialog.getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.width = dp(PANEL_WIDTH_DP);
        attributes.height = dp(PANEL_HEIGHT_DP);
        attributes.x = edgeX(attributes.width);
        attributes.y = yFor(attributes.height);
        attributes.flags = keepScreenFlag(attributes.flags);
        window.setAttributes(attributes);
    }

    private void showColumn() {
        if (mediaColumn == null || appsColumn == null) return;
        int shown = state == COLLAPSED ? lastState : state;
        mediaColumn.setVisibility(shown == CONTROLS ? View.VISIBLE : View.GONE);
        appsColumn.setVisibility(shown == APPS ? View.VISIBLE : View.GONE);
        if (panelFrames != null) panelFrames.setContext(shown == CONTROLS ? "media" : "apps");
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(0, dp(PANEL_PADDING_DP), 0, dp(PANEL_PADDING_DP));
        return column;
    }

    private Drawable panelBackground() {
        if (palette.blurred) {
            startLiquidGlassAnimation();
            return createLiquidGlassDrawable();
        } else {
            stopLiquidGlassAnimation();
            int topColor = palette.panel;
            int alpha = Color.alpha(topColor);
            int r = Color.red(topColor);
            int g = Color.green(topColor);
            int b = Color.blue(topColor);
            int bottomColor = Color.argb(Math.max(0, alpha - 10),
                Math.max(0, r - 5), Math.max(0, g - 5), Math.max(0, b - 5));
            GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{topColor, bottomColor});
            gradient.setCornerRadius(dp(PANEL_RADIUS_DP));
            gradient.setStroke(Math.max(1, dp(1)), palette.panelStroke);
            if (palette.highlight == 0) return gradient;
            GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{palette.highlight, palette.highlight & 0x00ffffff, 0});
            sheen.setCornerRadius(dp(PANEL_RADIUS_DP));
            return new LayerDrawable(new Drawable[]{gradient, sheen});
        }
    }

    private Drawable createLiquidGlassDrawable() {
        GradientDrawable base = rounded(palette.panel, PANEL_RADIUS_DP);
        base.setStroke(Math.max(1, dp(1)), palette.panelStroke);

        float phase = liquidGlassPhase;
        int highlightAlpha = (int)(20 + 15 * Math.sin(phase * Math.PI * 2));
        int edgeAlpha = (int)(40 + 20 * Math.sin((phase + 0.3f) * Math.PI * 2));

        GradientDrawable flowingGradient = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[]{
                Color.argb(highlightAlpha, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Color.argb(highlightAlpha / 2, 255, 255, 255)
            });
        flowingGradient.setCornerRadius(dp(PANEL_RADIUS_DP));

        GradientDrawable edgeShimmer = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{
                Color.argb(0, 255, 255, 255),
                Color.argb(edgeAlpha, 255, 255, 255),
                Color.argb(0, 255, 255, 255)
            });
        edgeShimmer.setCornerRadius(dp(PANEL_RADIUS_DP));

        if (palette.highlight == 0) {
            return new LayerDrawable(new Drawable[]{base, flowingGradient, edgeShimmer});
        }

        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{palette.highlight, palette.highlight & 0x00ffffff, 0});
        sheen.setCornerRadius(dp(PANEL_RADIUS_DP));

        return new LayerDrawable(new Drawable[]{base, flowingGradient, edgeShimmer, sheen});
    }

    private void startLiquidGlassAnimation() {
        if (liquidGlassAnimator != null && liquidGlassAnimator.isRunning()) return;

        liquidGlassAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        liquidGlassAnimator.setDuration(8000);
        liquidGlassAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        liquidGlassAnimator.setRepeatMode(android.animation.ValueAnimator.RESTART);
        liquidGlassAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        liquidGlassAnimator.addUpdateListener(animation -> {
            liquidGlassPhase = (float) animation.getAnimatedValue();
            if (panelDialog != null && panelDialog.getWindow() != null) {
                panelDialog.getWindow().setBackgroundDrawable(createLiquidGlassDrawable());
            }
        });
        liquidGlassAnimator.start();
    }

    private void stopLiquidGlassAnimation() {
        if (liquidGlassAnimator != null) {
            liquidGlassAnimator.cancel();
            liquidGlassAnimator = null;
        }
    }

    /** Full-width touch slot; its inner face shows the ripple and a quick press scale. */
    private final class Slot extends FrameLayout {
        final ImageView face;

        Slot(LinearLayout column, int faceDp, String description) {
            super(OverlayService.this);
            face = new ImageView(OverlayService.this);
            addView(face, new FrameLayout.LayoutParams(dp(faceDp), dp(faceDp), Gravity.CENTER));
            setContentDescription(description);
            column.addView(this, new LinearLayout.LayoutParams(-1, dp(SLOT_DP)));
        }
    }

    private void styleFace(ImageView face, int surface, int stroke, int radiusDp, int ripple) {
        GradientDrawable background = rounded(surface, radiusDp);
        if (stroke != 0) background.setStroke(Math.max(1, dp(1)), stroke);
        face.setBackground(background);
        face.setForeground(new RippleDrawable(ColorStateList.valueOf(ripple), null,
            rounded(0xffffffff, radiusDp)));
        final float radius = dp(radiusDp);
        face.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        face.setClipToOutline(true);
    }

    private ImageView glyphFace(Slot slot, int drawable, int paddingDp) {
        ImageView face = slot.face;
        styleFace(face, palette.button, palette.buttonStroke, FACE_RADIUS_DP, palette.ripple);
        face.setImageResource(drawable);
        face.setImageTintList(ColorStateList.valueOf(palette.ink));
        face.setScaleType(ImageView.ScaleType.FIT_CENTER);
        face.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp));
        return face;
    }

    private void mediaItem(LinearLayout column, String item) {
        switch (item) {
            case "cover": {
                Slot slot = new Slot(column, FACE_DP, "打开正在播放的应用");
                styleFace(slot.face, palette.button, palette.buttonStroke, FACE_RADIUS_DP, palette.ripple);
                coverFace = slot.face;
                slot.setOnClickListener(v -> {
                    haptic(false);
                    openMediaApp();
                });
                break;
            }
            case "play": {
                Slot slot = new Slot(column, FACE_DP, "播放");
                playFace = glyphFace(slot, R.drawable.media_play, 12);
                slot.setOnClickListener(v -> {
                    haptic(false);
                    resetTimer();
                    togglePlayback();
                });
                break;
            }
            case "next":
            case "previous": {
                boolean previous = "previous".equals(item);
                Slot slot = new Slot(column, FACE_DP, previous ? "上一首" : "下一首");
                ImageView skipFace = glyphFace(slot, previous ? R.drawable.media_previous : R.drawable.media_skip, 13);
                skipFaces.add(skipFace);
                slot.setOnClickListener(v -> {
                    haptic(false);
                    resetTimer();
                    media(!previous);
                });
                break;
            }
            default:
                break;
        }
    }

    private void appItem(LinearLayout column, String item) {
        if (!"settings".equals(item)) {
            appButton(column, item, "message".equals(item) ? "沟通" :
                "navigation".equals(item) ? "导航" : "音乐");
            return;
        }
        Slot slot = new Slot(column, FACE_DP, "设置");
        glyphFace(slot, R.drawable.nav_settings, 15);
        slot.setOnClickListener(v -> {
            haptic(false);
            collapse();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
    }

    private void switchSlot(LinearLayout column) {
        Slot slot = new Slot(column, SWITCH_DP, "切换控件与应用");
        ImageView face = slot.face;
        styleFace(face, palette.accent, 0, SWITCH_DP / 2, 0x40ffffff);
        face.setImageResource(R.drawable.swap_vertical);
        face.setImageTintList(ColorStateList.valueOf(palette.onAccent));
        face.setScaleType(ImageView.ScaleType.FIT_CENTER);
        face.setPadding(dp(15), dp(15), dp(15), dp(15));
        slot.setOnClickListener(v -> {
            haptic(true);
            setState(state == CONTROLS ? APPS : CONTROLS);
        });
    }

    private void appButton(LinearLayout column, String key, String description) {
        ComponentName component = ComponentName.unflattenFromString(prefs.getString(key, ""));
        Slot slot = new Slot(column, FACE_DP, description);
        ImageView face = slot.face;
        face.setBackground(null);
        face.setForeground(new RippleDrawable(ColorStateList.valueOf(palette.ripple), null, null));
        face.setScaleType(ImageView.ScaleType.FIT_CENTER);
        face.setImageDrawable(appIcon(component));
        slot.setOnClickListener(v -> {
            haptic(false);
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
            } catch (Exception e) {
                Diagnostics.error("app", "launch_failed", e);
                Toast.makeText(this, "主实例启动失败，请尝试关闭主实例选项", Toast.LENGTH_LONG).show();
            }
        });
    }

    private Drawable appIcon(ComponentName component) {
        if (component == null) return getDrawable(R.drawable.icon);
        String key = component.flattenToString();
        Drawable cached = appIcons.get(key);
        if (cached != null) return cached;
        Drawable icon;
        try {
            icon = getPackageManager().getActivityInfo(component, 0).loadIcon(getPackageManager());
        } catch (Exception ignored) {
            icon = getDrawable(R.drawable.icon);
        }
        appIcons.put(key, icon);
        return icon;
    }

    private void haptic(boolean strong) {
        if (!prefs.getBoolean("vibration", true) || vibrator == null || !vibrator.hasVibrator()) return;
        try {
            vibrator.vibrate(VibrationEffect.createPredefined(strong
                ? VibrationEffect.EFFECT_HEAVY_CLICK : VibrationEffect.EFFECT_CLICK));
        } catch (RuntimeException e) {
            Diagnostics.error("haptic", "vibrate_failed", e);
        }
    }

    private void startMediaWatch() {
        mediaHandler.post(() -> {
            if (!sessionsListening) {
                try {
                    ComponentName listener = new ComponentName(this, MediaListener.class);
                    sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, mediaHandler);
                    sessionsListening = true;
                    watchControllers(sessions.getActiveSessions(listener));
                    Diagnostics.event("media", "watch_started", "sessions=" + watched.size());
                } catch (SecurityException e) {
                    Diagnostics.event("media", "watch_denied", "notification access missing");
                } catch (RuntimeException e) {
                    Diagnostics.error("media", "watch_failed", e);
                }
            }
            refreshMedia.run();
        });
    }

    private void stopMediaWatch() {
        mediaHandler.post(() -> {
            if (sessionsListening) {
                try { sessions.removeOnActiveSessionsChangedListener(sessionsChanged); }
                catch (RuntimeException ignored) {}
                sessionsListening = false;
            }
            watchControllers(null);
        });
    }

    private void watchControllers(List<MediaController> controllers) {
        for (MediaController controller : watched) {
            try { controller.unregisterCallback(mediaCallback); }
            catch (RuntimeException ignored) {}
        }
        watched.clear();
        if (controllers == null) return;
        for (MediaController controller : controllers) {
            try {
                controller.registerCallback(mediaCallback, mediaHandler);
                watched.add(controller);
            } catch (RuntimeException e) {
                Diagnostics.error("media", "callback_failed", e);
            }
        }
    }

    private void scheduleMediaRefresh(long delayMs) {
        mediaHandler.removeCallbacks(refreshMedia);
        mediaHandler.postDelayed(refreshMedia, delayMs);
    }

    private Bitmap scaledArt(Bitmap source, String key) {
        if (source == null) {
            lastArtKey = "";
            lastScaledArt = null;
            return null;
        }
        if (key.equals(lastArtKey) && lastScaledArt != null) return lastScaledArt;
        int target = Math.max(1, artTargetPx);
        int shortSide = Math.min(source.getWidth(), source.getHeight());
        Bitmap result = source;
        if (shortSide > target * 3 / 2) {
            float scale = target / (float) shortSide;
            result = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)),
                Math.max(1, Math.round(source.getHeight() * scale)), true);
        }
        lastArtKey = key;
        lastScaledArt = result;
        return result;
    }

    private void applyMedia(boolean available, boolean playing, Bitmap art, String packageName) {
        mediaAvailable = available;
        mediaPlaying = playing;
        mediaArt = art;
        mediaPackage = packageName;
        bindMediaViews();
    }

    private void bindMediaViews() {
        if (coverFace != null) {
            View slot = (View) coverFace.getParent();
            if (mediaArt != null) {
                coverFace.setImageTintList(null);
                coverFace.setScaleType(ImageView.ScaleType.CENTER_CROP);
                coverFace.setPadding(0, 0, 0, 0);
                coverFace.setImageBitmap(mediaArt);
                if (mediaPlaying) {
                    GradientDrawable glow = new GradientDrawable();
                    glow.setShape(GradientDrawable.RECTANGLE);
                    glow.setCornerRadius(dp(FACE_RADIUS_DP));
                    glow.setColor(0x33347ff0);
                    GradientDrawable surface = rounded(palette.button, FACE_RADIUS_DP);
                    surface.setStroke(Math.max(1, dp(1)), 0x44ffffff);
                    coverFace.setBackground(new LayerDrawable(new Drawable[]{glow, surface}));
                } else {
                    styleFace(coverFace, palette.button, palette.buttonStroke, FACE_RADIUS_DP, palette.ripple);
                }
            } else {
                coverFace.setImageResource(mediaAvailable ? R.drawable.media_cover : R.drawable.media_off);
                coverFace.setImageTintList(ColorStateList.valueOf(palette.ink));
                coverFace.setScaleType(ImageView.ScaleType.FIT_CENTER);
                coverFace.setPadding(dp(15), dp(15), dp(15), dp(15));
                styleFace(coverFace, palette.button, palette.buttonStroke, FACE_RADIUS_DP, palette.ripple);
            }
            slot.setContentDescription(mediaAvailable ? "专辑封面，打开正在播放的应用" :
                "无媒体会话，打开音乐应用");
        }
        if (playFace != null) {
            playFace.animate().cancel();
            playFace.setImageResource(mediaPlaying ? R.drawable.media_pause : R.drawable.media_play);
            playFace.setAlpha(mediaAvailable ? 1f : .4f);
            if (mediaPlaying && mediaAvailable) {
                playFace.animate().alpha(0.85f).setDuration(1200)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (playFace != null && mediaPlaying) {
                                playFace.animate().alpha(1f).setDuration(1200)
                                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                                    .withEndAction(this).start();
                            }
                        }
                    }).start();
            }
            ((View) playFace.getParent()).setContentDescription(
                (mediaPlaying ? "暂停" : "播放") + (mediaAvailable ? "" : "，当前无媒体会话"));
        }
        for (ImageView face : skipFaces) face.setAlpha(mediaAvailable ? 1f : .4f);
    }

    private MediaController selectedController() {
        try {
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
        if (mediaAvailable) {
            mediaPlaying = !mediaPlaying;
            bindMediaViews();
        }
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
        // Fallback in case the player never reports the new state.
        scheduleMediaRefresh(600);
    }

    private void openMediaApp() {
        Intent launch = null;
        String source = "session";
        if (!mediaPackage.isEmpty()) launch = getPackageManager().getLaunchIntentForPackage(mediaPackage);
        if (launch == null) {
            source = "configured";
            ComponentName music = ComponentName.unflattenFromString(prefs.getString("music", ""));
            if (music != null)
                launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(music);
        }
        if (launch == null) {
            Toast.makeText(this, "请先在设置中选择音乐应用", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            collapse();
            return;
        }
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Diagnostics.event("media", "open_app", "source=" + source + " package=" + mediaPackage);
            collapse();
        } catch (RuntimeException e) {
            Diagnostics.error("media", "open_app_failed", e);
            Toast.makeText(this, "无法打开音乐应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void mediaMessage(String message) {
        handler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private final class PanelDialog extends Dialog {
        private final int slop = dragSlop();
        private float startX, startY;
        private int startWindowX, startWindowY;
        private boolean singleTouch;

        PanelDialog() {
            super(OverlayService.this, R.style.FloatingOverlayTheme);
        }

        private boolean handleOutside(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_OUTSIDE) return false;
            boolean shouldCollapse = state != COLLAPSED &&
                outsideProtectionExceeded(event, getWindow().getDecorView());
            Diagnostics.event("touch", "outside_dialog", "collapse=" + shouldCollapse);
            if (shouldCollapse) collapse();
            return true;
        }

        private void cancelChildren(MotionEvent event) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (handleOutside(event)) return true;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelSettleAnimation();
                    Diagnostics.event("touch", "expanded_down", "x=" + Math.round(event.getRawX()) +
                        " y=" + Math.round(event.getRawY()));
                    startX = event.getRawX();
                    startY = event.getRawY();
                    WindowManager.LayoutParams attributes = getWindow().getAttributes();
                    startWindowX = attributes.x;
                    startWindowY = attributes.y;
                    panelDragging = false;
                    singleTouch = true;
                    syncGuard();
                    resetTimer();
                    return super.dispatchTouchEvent(event);
                case MotionEvent.ACTION_POINTER_DOWN:
                    Diagnostics.event("touch", "expanded_multitouch", "");
                    singleTouch = false;
                    if (!panelDragging) cancelChildren(event);
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!singleTouch) return true;
                    float dx = event.getRawX() - startX;
                    float dy = event.getRawY() - startY;
                    if (!panelDragging && dx * dx + dy * dy > slop * slop) {
                        cancelChildren(event);
                        panelDragging = true;
                        hideGuard();
                        handler.removeCallbacks(collapseTask);
                        panelRoot.animate().scaleX(1.045f).scaleY(1.045f).alpha(0.93f)
                            .setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                        Diagnostics.event("touch", "expanded_drag_start",
                            "distance=" + Math.round(Math.hypot(dx, dy)));
                    }
                    if (panelDragging) {
                        WindowManager.LayoutParams moving = getWindow().getAttributes();
                        moving.x = clamp(startWindowX + Math.round(dx), area.left, area.right - moving.width);
                        moving.y = clamp(startWindowY + Math.round(dy), area.top, area.bottom - moving.height);
                        getWindow().setAttributes(moving);
                        return true;
                    }
                    return super.dispatchTouchEvent(event);
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    Diagnostics.event("touch", event.getActionMasked() == MotionEvent.ACTION_UP
                        ? "expanded_up" : "expanded_cancel",
                        "dragging=" + panelDragging + " multi=" + !singleTouch);
                    if (panelDragging) {
                        panelDragging = false;
                        panelRoot.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(220).setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
                        WindowManager.LayoutParams end = getWindow().getAttributes();
                        settlePosition(end.x + end.width / 2, end.y + end.height / 2);
                        animatePosition(true, end.x, end.y, edgeX(end.width), yFor(end.height), () -> {
                            syncGuard();
                            resetTimer();
                        });
                        Diagnostics.event("touch", "expanded_drag_end",
                            "edge=" + edgeSide + " anchorY=" + anchorY);
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
    }

    private void attachDrag(View view) {
        final int slop = dragSlop();
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelSettleAnimation();
                    Diagnostics.event("touch", "icon_down", "x=" + Math.round(event.getRawX()) +
                        " y=" + Math.round(event.getRawY()));
                    downX = event.getRawX();
                    downY = event.getRawY();
                    initialX = iconParams.x;
                    initialY = iconParams.y;
                    moved = false;
                    iconMultiTouch = false;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    iconMultiTouch = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (iconMultiTouch) return true;
                    int dx = (int) (event.getRawX() - downX);
                    int dy = (int) (event.getRawY() - downY);
                    if (!moved && dx * dx + dy * dy > slop * slop) {
                        moved = true;
                        Diagnostics.event("touch", "icon_drag_start", "dx=" + dx + " dy=" + dy);
                    }
                    if (moved) {
                        iconParams.x = clamp(initialX + dx, area.left, area.right - iconParams.width);
                        iconParams.y = clamp(initialY + dy, area.top, area.bottom - iconParams.height);
                        manager.updateViewLayout(iconView, iconParams);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean up = event.getActionMasked() == MotionEvent.ACTION_UP;
                    Diagnostics.event("touch", up ? "icon_up" : "icon_cancel",
                        "moved=" + moved + " multi=" + iconMultiTouch);
                    if (moved) {
                        settlePosition(iconParams.x + iconParams.width / 2, iconParams.y + iconParams.height / 2);
                        animatePosition(false, iconParams.x, iconParams.y,
                            edgeX(iconParams.width), yFor(iconParams.height), null);
                        Diagnostics.event("touch", "icon_drag_end", "edge=" + edgeSide + " anchorY=" + anchorY);
                    } else if (up && !iconMultiTouch) {
                        long nowMs = SystemClock.uptimeMillis();
                        boolean isDoubleTap = nowMs - lastIconTapMs < 380;
                        lastIconTapMs = isDoubleTap ? 0 : nowMs;
                        if (isDoubleTap && state != COLLAPSED) {
                            haptic(true);
                            setState(state == CONTROLS ? APPS : CONTROLS);
                        } else {
                            haptic(false);
                            open();
                        }
                    }
                    return true;
                default:
                    return true;
            }
        });
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
            showState();
        } finally {
            Trace.endSection();
        }
    }
    private void resetTimer() {
        handler.removeCallbacks(collapseTask);
        if (state != COLLAPSED && !prefs.getBoolean("residentExpanded", false))
            handler.postDelayed(collapseTask, Math.max(2, prefs.getInt("timeout", 12)) * 1000L);
    }
    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }
    private int keepScreenFlag(int flags) {
        return prefs.getBoolean("keepScreenOn", false)
            ? flags | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            : flags & ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
    }
    private int opacity() {
        return Math.max(0, Math.min(80, prefs.getInt("opacity", 24)));
    }
    private int configuredSizePercent() {
        return Math.max(80, Math.min(160, prefs.getInt("sizePercent", 100)));
    }
    /** Shrinks the panel when the configured size does not fit, e.g. in landscape. */
    private int effectiveSizePercent() {
        float density = getResources().getDisplayMetrics().density;
        int fit = (int) (area.height() * 100f / (PANEL_HEIGHT_DP * density));
        return Math.max(50, Math.min(configuredSizePercent(), fit));
    }
    private int configuredIconSizePercent() {
        return Math.max(70, Math.min(160, prefs.getInt("iconSizePercent", 100)));
    }
    private void updateArea() {
        WindowMetrics metrics = manager.getMaximumWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        area.set(bounds.left + insets.left, bounds.top + insets.top,
            bounds.right - insets.right, bounds.bottom - insets.bottom);
        if (area.width() <= 0 || area.height() <= 0) area.set(bounds);
    }
    private void loadPosition() {
        if (prefs.contains("anchorFraction")) {
            edgeSide = prefs.getInt("edgeSide", EDGE_RIGHT);
            anchorY = Math.round(area.top + prefs.getFloat("anchorFraction", .5f) * area.height());
        } else {
            int width = getResources().getDisplayMetrics().widthPixels;
            int height = getResources().getDisplayMetrics().heightPixels;
            int oldSize = prefs.getInt("positionIconSizePercent", prefs.getInt("positionSizePercent", 100));
            int oldDiameter = Math.round(ICON_DP * getResources().getDisplayMetrics().density * oldSize / 100f);
            edgeSide = prefs.getInt("edgeSide", prefs.getInt("x", width - oldDiameter) + oldDiameter / 2
                < width / 2 ? EDGE_LEFT : EDGE_RIGHT);
            anchorY = prefs.getInt("anchorY", prefs.getInt("y", height / 2 - oldDiameter / 2) + oldDiameter / 2);
        }
        edgeSide = allowedSide(edgeSide);
        sizePercent = effectiveSizePercent();
        iconSizePercent = configuredIconSizePercent();
        anchorY = clampAnchorY(anchorY);
    }
    private float anchorFraction() {
        return area.height() > 0 ? (anchorY - area.top) / (float) area.height() : .5f;
    }
    private int allowedSide(int requested) {
        int mode = prefs.getInt("edgeMode", 0);
        return mode == 1 ? EDGE_LEFT : mode == 2 ? EDGE_RIGHT : requested;
    }
    private int edgeX(int width) {
        return edgeSide == EDGE_LEFT ? area.left : Math.max(area.left, area.right - width);
    }
    private int yFor(int height) {
        return clamp(anchorY - height / 2, area.top, area.bottom - height);
    }
    private int clampAnchorY(int center) {
        int half = Math.min(area.height() / 2, Math.max(iconDp(ICON_DP), dp(PANEL_HEIGHT_DP)) / 2);
        return clamp(center, area.top + half, area.bottom - half);
    }
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, Math.max(min, max)));
    }
    private void settlePosition(int centerX, int centerY) {
        edgeSide = allowedSide(centerX < area.centerX() ? EDGE_LEFT : EDGE_RIGHT);
        anchorY = clampAnchorY(centerY);
        prefs.edit().putInt("edgeSide", edgeSide).putInt("anchorY", anchorY)
            .putFloat("anchorFraction", anchorFraction()).apply();
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
                if (panelDialog == null) return;
                WindowManager.LayoutParams moving = panelDialog.getWindow().getAttributes();
                moving.x = clamp(x, area.left, area.right - moving.width);
                moving.y = clamp(y, area.top, area.bottom - moving.height);
                panelDialog.getWindow().setAttributes(moving);
            } else if (iconView != null) {
                iconParams.x = clamp(x, area.left, area.right - iconParams.width);
                iconParams.y = clamp(y, area.top, area.bottom - iconParams.height);
                manager.updateViewLayout(iconView, iconParams);
            }
        } catch (RuntimeException error) {
            Diagnostics.error("window", "settle_update_failed", error);
            cancelSettleAnimation();
        }
    }
    private int iconDp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density * iconSizePercent / 100f + .5f);
    }
    private int dragSlop() {
        int minimum = (int) (12 * getResources().getDisplayMetrics().density + .5f);
        return Math.max(ViewConfiguration.get(this).getScaledTouchSlop(), minimum);
    }
    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density * sizePercent / 100f + .5f);
    }
    @Override public void onDestroy() {
        Log.i(TAG, "Service destroyed, state=" + state);
        Diagnostics.event("service", "destroyed", "state=" + state);
        running = false;
        visibleState = -1;
        refreshTile();
        notifyStatus();
        handler.removeCallbacksAndMessages(null);
        mediaHandler.removeCallbacksAndMessages(null);
        stopMediaWatch();
        mediaThread.quitSafely();
        unregisterReceiver(refresh);
        manager.removeCrossWindowBlurEnabledListener(blurListener);
        destroyWindows();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
