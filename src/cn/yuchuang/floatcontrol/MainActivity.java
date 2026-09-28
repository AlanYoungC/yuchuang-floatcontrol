package cn.yuchuang.floatcontrol;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

public class MainActivity extends Activity {
    private static final int EXPORT_LOGS = 1401;
    private static final String REFRESH = "cn.yuchuang.floatcontrol.REFRESH";
    private Ui.Palette c;
    private SharedPreferences prefs;
    private LinearLayout body;
    private LinearLayout activePage;
    private LinearLayout[] pages;
    private LinearLayout mediaOrderRows, appOrderRows;
    private LinearLayout overviewContent;
    private ScrollView mainScroll;
    private LinearLayout header;
    private LinearLayout navigation;
    private TextView pageTitle, pageSubtitle;
    private GuardPreview guardPreview;
    private TextView diagnosticStatus;
    private TextView systemLogStatus;
    private TextView[] floatingSummary;
    private TextView[] contentSummary;
    private TextView[] settingsSummary;
    private TextView styleSubtitle;
    private TextView opacitySubtitle;
    private SeekBar opacityBar;
    private LinearLayout diagnosticBlock;
    private int versionTapCount;
    private long lastVersionTapAt;
    private int currentPage;
    private FrameMonitor frameMonitor;
    private ImageView[] navIcons;
    private TextView[] navLabels;
    private LinearLayout[] navItems;
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Map<String, Drawable> pickerIcons = new HashMap<>();
    private final Set<String> pickerLoading = new HashSet<>();
    private static final String[] PAGE_NAMES = {"总览", "浮窗", "内容", "设置"};
    private static final String[] PAGE_SUBTITLES = {
        "浮窗状态、常用应用与媒体控件",
        "位置、外观与展开行为",
        "快捷应用与媒体控件",
        "权限、控制中心与诊断"
    };
    private final BroadcastReceiver overlayStatus = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateOverview();
        }
    };
    private boolean startRequested;
    private static final String[] KEYS = {"message", "navigation", "music"};
    private static final String[] LABELS = {"沟通应用", "导航应用", "音乐应用"};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Diagnostics.event("activity", "created", "MainActivity");
        c = Ui.app(this);
        startRequested = getIntent().getBooleanExtra("startOverlay", false);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(c.canvas);
        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        mainScroll.setClipToPadding(false);
        mainScroll.setVerticalScrollBarEnabled(false);
        screen.addView(mainScroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        mainScroll.addView(root);
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(24), dp(52), dp(24), dp(16));
        root.addView(header);
        pageTitle = label(PAGE_NAMES[0], Ui.TEXT_DISPLAY, c.ink, Ui.BOLD);
        pageTitle.setPadding(0, dp(4), 0, dp(4));
        header.addView(pageTitle);
        pageSubtitle = label(PAGE_SUBTITLES[0], Ui.TEXT_SECONDARY, c.section, null);
        header.addView(pageSubtitle);
        pages = new LinearLayout[4];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = new LinearLayout(this);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            pages[i].setPadding(dp(20), 0, dp(20), dp(148));
            pages[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            root.addView(pages[i]);
        }
        activePage = body = pages[0];
        navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(4), dp(4), dp(4), dp(4));
        navigation.setMinimumHeight(dp(60));
        GradientDrawable bar = round(c.surface, 32);
        bar.setStroke(dp(1), c.divider);
        navigation.setBackground(bar);
        navigation.setElevation(dp(6));
        FrameLayout.LayoutParams navigationParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        navigationParams.setMargins(dp(40), 0, dp(40), dp(24));
        screen.addView(navigation, navigationParams);
        View statusScrim = new View(this);
        statusScrim.setBackgroundColor(c.canvas);
        screen.addView(statusScrim, new FrameLayout.LayoutParams(-1, 0, Gravity.TOP));
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            FrameLayout.LayoutParams scrim = (FrameLayout.LayoutParams) statusScrim.getLayoutParams();
            if (scrim.height != bars.top) {
                scrim.height = bars.top;
                statusScrim.setLayoutParams(scrim);
            }
            header.setPadding(dp(24) + bars.left, bars.top + dp(20), dp(24) + bars.right, dp(16));
            FrameLayout.LayoutParams nav = (FrameLayout.LayoutParams) navigation.getLayoutParams();
            int navBottom = bars.bottom + dp(12);
            if (nav.bottomMargin != navBottom) {
                nav.bottomMargin = navBottom;
                navigation.setLayoutParams(nav);
            }
            for (LinearLayout page : pages)
                page.setPadding(dp(20) + bars.left, 0, dp(20) + bars.right, bars.bottom + dp(112));
            return insets;
        });
        int[] icons = {R.drawable.nav_home, R.drawable.nav_layout,
            R.drawable.nav_apps, R.drawable.nav_settings};
        navIcons = new ImageView[4];
        navLabels = new TextView[4];
        navItems = new LinearLayout[4];
        for (int i = 0; i < PAGE_NAMES.length; i++) {
            final int page = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(6), 0, dp(6));
            item.setMinimumHeight(dp(52));
            item.setContentDescription(PAGE_NAMES[i]);
            navigation.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
            ImageView symbol = new ImageView(this);
            symbol.setImageResource(icons[i]);
            item.addView(symbol, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView name = label(PAGE_NAMES[i], Ui.TEXT_CAPTION, c.muted, null);
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
            labelParams.topMargin = dp(2);
            item.addView(name, labelParams);
            navIcons[i] = symbol;
            navLabels[i] = name;
            navItems[i] = item;
            item.setOnClickListener(v -> selectPage(page));
        }
        styleNavigation(0);
        setContentView(screen);
        buildOverviewPage();
        buildFloatingPage(pages[1]);
        buildContentPage(pages[2]);
        buildOtherPage(pages[3]);
        updatePageSummaries();
    }

    private void styleNavigation(int selected) {
        for (int j = 0; j < navItems.length; j++) {
            boolean active = j == selected;
            navIcons[j].setImageTintList(ColorStateList.valueOf(active ? c.brand : c.muted));
            navLabels[j].setTextColor(active ? c.brand : c.muted);
            navLabels[j].setTypeface(active ? Ui.BOLD : Typeface.DEFAULT);
            navItems[j].setBackground(active ? round(c.brandTint, 26) : touchBackground(c.surface, 26));
            navItems[j].setSelected(active);
        }
    }

    private void buildOverviewPage() {
        LinearLayout page = pages[0];
        overviewContent = new LinearLayout(this);
        overviewContent.setOrientation(LinearLayout.VERTICAL);
        page.addView(overviewContent, new LinearLayout.LayoutParams(-1, -2));
        renderOverview();
    }

    private void selectPage(int page) {
        if (page < 0 || page >= pages.length) return;
        Diagnostics.event("activity", "page_selected", PAGE_NAMES[page]);
        currentPage = page;
        for (int j = 0; j < pages.length; j++)
            pages[j].setVisibility(j == page ? View.VISIBLE : View.GONE);
        styleNavigation(page);
        pageTitle.setText(PAGE_NAMES[page]);
        if (frameMonitor != null) frameMonitor.setContext(PAGE_NAMES[page]);
        pageSubtitle.setText(PAGE_SUBTITLES[page]);
        if (page == 0) updateOverview();
        if (page == 1 && guardPreview != null) guardPreview.play();
        mainScroll.scrollTo(0, 0);
    }

    private void toggleOverlay() {
        Trace.beginSection("YuChuang.toggleOverlay");
        try {
            if (OverlayService.isRunning()) {
                Diagnostics.event("service", "stop_requested", "overview");
                stopService(new Intent(this, OverlayService.class));
            } else if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            } else {
                Diagnostics.event("service", "start_requested", "overview");
                startForegroundService(new Intent(this, OverlayService.class).setAction("start"));
            }
            updateOverview();
        } catch (RuntimeException error) {
            Diagnostics.error("service", "toggle_failed", error);
            Toast.makeText(this, "无法切换悬浮窗，请检查权限", Toast.LENGTH_LONG).show();
        } finally {
            Trace.endSection();
        }
    }

    private void updateOverview() {
        updatePageSummaries();
        renderOverview();
    }

    private String permissionSummary(boolean overlay, boolean media) {
        return "悬浮窗" + (overlay ? "已授权" : "待授权") + " · 媒体" + (media ? "已授权" : "待授权");
    }

    private int configuredApps() {
        int count = 0;
        for (String key : KEYS) if (appAvailable(key)) count++;
        return count;
    }

    private String floatingDetail(boolean running) {
        int style = Ui.overlayStyle(this);
        String detail = Ui.OVERLAY_STYLES[style] + " · 展开 " +
            Math.max(80, Math.min(160, prefs.getInt("sizePercent", 100))) + "% · 图标 " +
            Math.max(70, Math.min(160, prefs.getInt("iconSizePercent", 100))) + "%";
        return running ? OverlayService.currentStateLabel() + " · " + detail : detail;
    }

    private void updatePageSummaries() {
        boolean running = OverlayService.isRunning();
        boolean permitted = Settings.canDrawOverlays(this);
        boolean media = mediaGranted();
        if (floatingSummary != null) {
            floatingSummary[0].setText(running ? "正在运行" : permitted ? "尚未开启" : "需要悬浮窗权限");
            floatingSummary[1].setText(floatingDetail(running));
            setPill(floatingSummary[2], running ? "运行中" : "已关闭", running ? PILL_OK : PILL_NEUTRAL);
        }
        if (contentSummary != null) {
            int apps = configuredApps();
            boolean ready = apps == KEYS.length && media;
            contentSummary[0].setText("已设置 " + apps + "/" + KEYS.length + " 个快捷应用");
            contentSummary[1].setText(media ? "媒体控制可用" : "媒体控制需要通知使用权");
            setPill(contentSummary[2], ready ? "就绪" : "待完善", ready ? PILL_OK : PILL_WARN);
        }
        if (settingsSummary != null) {
            boolean ready = permitted && media;
            settingsSummary[0].setText(ready ? "权限就绪" : "需要处理");
            settingsSummary[1].setText(permissionSummary(permitted, media));
            setPill(settingsSummary[2], ready ? "就绪" : "待处理", ready ? PILL_OK : PILL_WARN);
        }
    }

    private void renderOverview() {
        if (overviewContent == null) return;
        overviewContent.removeAllViews();

        boolean permitted = Settings.canDrawOverlays(this);
        boolean running = OverlayService.isRunning();
        boolean media = mediaGranted();
        boolean locked = Diagnostics.isLocked(this);
        boolean systemLogs = SystemLogCollector.isEnabled(this);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(20), dp(18), dp(18), dp(18));
        hero.setBackground(round(c.surface, Ui.RADIUS_L));
        overviewContent.addView(hero, overviewParams(0, 0, 0, 20));

        LinearLayout top = new LinearLayout(this);
        hero.addView(top, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout heroLabels = new LinearLayout(this);
        heroLabels.setOrientation(LinearLayout.VERTICAL);
        top.addView(heroLabels, new LinearLayout.LayoutParams(0, -2, 1));
        heroLabels.addView(label("悬浮窗", Ui.TEXT_CAPTION, c.muted, Ui.MEDIUM));
        TextView heroState = label(running ? "正在运行" : "尚未开启", Ui.TEXT_HEADLINE, c.ink, Ui.BOLD);
        heroState.setPadding(0, dp(6), 0, dp(4));
        heroLabels.addView(heroState);
        TextView heroDetail = label(running ? OverlayService.currentStateLabel() :
            permitted ? "悬浮窗已就绪，等待你的指令" : "需要授权后才能显示悬浮窗", Ui.TEXT_SECONDARY, c.muted, null);
        heroDetail.setLineSpacing(dp(2), 1f);
        heroLabels.addView(heroDetail);
        TextView live = pill();
        setPill(live, running ? "运行中" : "已关闭", running ? PILL_OK : PILL_NEUTRAL);
        LinearLayout.LayoutParams liveParams = new LinearLayout.LayoutParams(-2, -2);
        liveParams.leftMargin = dp(8);
        top.addView(live, liveParams);

        LinearLayout actions = new LinearLayout(this);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(16);
        hero.addView(actions, actionsParams);
        TextView heroAction = actionButton(running ? "关闭悬浮窗" :
            permitted ? "开启悬浮窗" : "授予权限并开启",
            running ? c.surfaceMuted : c.brandFill, running ? c.ink : c.onBrand, this::toggleOverlay);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, -2, 1);
        actionParams.rightMargin = dp(10);
        actions.addView(heroAction, actionParams);
        TextView heroShortcut = actionButton("调整浮窗", c.brandTint, c.brand, () -> selectPage(1));
        heroShortcut.setMinWidth(dp(112));
        actions.addView(heroShortcut, new LinearLayout.LayoutParams(-2, -2));

        overviewSectionTitle("快捷应用与媒体控件", "编辑", () -> selectPage(2));
        String[] appOrder = OverlayOrder.get(prefs, OverlayOrder.APPS);
        String[] mediaOrder = OverlayOrder.get(prefs, OverlayOrder.MEDIA);
        LinearLayout orderCards = new LinearLayout(this);
        overviewContent.addView(orderCards, overviewParams(0, 0, 0, 16));
        overviewOrderCard(orderCards, "快捷应用", OverlayOrder.APPS, appOrder, true);
        overviewOrderCard(orderCards, "媒体控件", OverlayOrder.MEDIA, mediaOrder, false);

        overviewSectionTitle("运行状态", "打开设置", () -> selectPage(3));
        LinearLayout statusGroup = overviewGroup();
        statusGroup.setBackground(touchBackground(c.surface, Ui.RADIUS_L));
        overviewContent.addView(statusGroup, overviewParams(0, 0, 0, 30));
        overviewStatusRow(statusGroup, "悬浮窗权限",
            permitted ? "已授权" : "待授权", permitted ? PILL_OK : PILL_WARN, false);
        overviewStatusRow(statusGroup, "媒体通知使用权",
            media ? "已授权" : "待授权", media ? PILL_OK : PILL_WARN, false);
        overviewStatusRow(statusGroup, "日志锁存",
            locked ? "已开启" : "未开启", locked ? PILL_OK : PILL_NEUTRAL, false);
        overviewStatusRow(statusGroup, "系统日志",
            systemLogs ? "Shizuku 已启用" : "仅记录本应用", systemLogs ? PILL_OK : PILL_NEUTRAL, true);
        statusGroup.setOnClickListener(v -> selectPage(3));
    }

    private TextView label(String value, int size, int color, Typeface typeface) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (typeface != null) view.setTypeface(typeface);
        return view;
    }

    private TextView actionButton(String text, int background, int foreground, Runnable action) {
        TextView button = label(text, Ui.TEXT_BODY, foreground, Ui.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(Ui.TOUCH_MIN));
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        button.setBackground(touchBackground(background, Ui.RADIUS_M));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private static final int PILL_OK = 0, PILL_WARN = 1, PILL_NEUTRAL = 2;

    private TextView pill() {
        TextView view = label("", Ui.TEXT_SECONDARY, c.muted, Ui.MEDIUM);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setMinHeight(dp(30));
        view.setPadding(dp(12), dp(4), dp(12), dp(4));
        return view;
    }

    private void setPill(TextView view, String text, int tone) {
        int foreground = tone == PILL_OK ? c.success : tone == PILL_WARN ? c.warning : c.muted;
        int background = tone == PILL_OK ? c.successTint : tone == PILL_WARN ? c.warningTint : c.surfaceMuted;
        String mark = tone == PILL_OK ? "● " : tone == PILL_WARN ? "! " : "○ ";
        view.setText(mark + text);
        view.setTextColor(foreground);
        view.setBackground(round(background, 15));
    }

    private LinearLayout.LayoutParams overviewParams(int top, int left, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout overviewGroup() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(12), dp(6), dp(12), dp(6));
        group.setBackground(round(c.surface, Ui.RADIUS_L));
        return group;
    }

    private void overviewSectionTitle(String title, String action, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(4), 0, dp(6));
        overviewContent.addView(row, overviewParams(0, 0, 0, 0));
        row.addView(label(title, Ui.TEXT_SUBHEAD, c.ink, Ui.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        TextView link = label(action + "  ›", Ui.TEXT_SECONDARY, c.brand, Ui.MEDIUM);
        link.setGravity(Gravity.CENTER);
        link.setPadding(dp(10), 0, dp(6), 0);
        link.setMinHeight(dp(Ui.TOUCH_MIN));
        link.setBackground(touchBackground(c.canvas, Ui.RADIUS_S));
        link.setOnClickListener(v -> onClick.run());
        row.addView(link, new LinearLayout.LayoutParams(-2, -2));
    }

    private void overviewOrderCard(LinearLayout parent, String title, String key, String[] items, boolean first) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(12), dp(12));
        card.setBackground(touchBackground(c.surface, Ui.RADIUS_L));
        card.setContentDescription(title + "顺序，点击编辑");
        card.setOnClickListener(v -> selectPage(2));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, -2, 1);
        if (first) cardParams.rightMargin = dp(8);
        parent.addView(card, cardParams);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topParams = new LinearLayout.LayoutParams(-1, -2);
        topParams.bottomMargin = dp(6);
        card.addView(top, topParams);
        top.addView(label(title, Ui.TEXT_BODY, c.ink, Ui.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(label("4 项", Ui.TEXT_CAPTION, c.muted, null));

        for (int position = 0; position < 5; position++) {
            boolean switcher = position == 2;
            String item = switcher ? null : items[position < 2 ? position : position - 1];
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(30));
            card.addView(row, new LinearLayout.LayoutParams(-1, -2));
            row.addView(label(String.format(Locale.ROOT, "%02d", position + 1), Ui.TEXT_CAPTION, c.muted, null),
                new LinearLayout.LayoutParams(dp(22), -2));
            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            if (switcher) {
                icon.setImageResource(R.drawable.swap_vertical);
                icon.setImageTintList(ColorStateList.valueOf(c.brand));
            } else {
                orderIcon(icon, key, item);
            }
            row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
            String text = switcher ? "切换页面" : orderLabel(key, item);
            if (!switcher && OverlayOrder.APPS.equals(key) && !"settings".equals(item)) text = selectedName(item);
            TextView value = label(text, Ui.TEXT_SECONDARY, switcher ? c.brand : c.ink, null);
            value.setSingleLine(true);
            value.setEllipsize(TextUtils.TruncateAt.END);
            value.setPadding(dp(8), 0, 0, 0);
            row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    private void overviewStatusRow(LinearLayout parent, String title, String state, int tone, boolean last) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(8), dp(4), dp(8));
        row.setMinimumHeight(dp(52));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        row.addView(label(title, Ui.TEXT_BODY, c.ink, Ui.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = pill();
        setPill(value, state, tone);
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        if (!last) {
            View divider = new View(this);
            divider.setBackgroundColor(c.divider);
            parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private TextView[] statusStrip(LinearLayout page) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(14), dp(14), dp(14));
        card.setMinimumHeight(dp(72));
        card.setBackground(round(c.surface, Ui.RADIUS_L));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(6);
        page.addView(card, cardParams);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = label("", Ui.TEXT_SUBHEAD, c.ink, Ui.BOLD);
        labels.addView(title);
        TextView detail = label("", Ui.TEXT_SECONDARY, c.muted, null);
        detail.setPadding(0, dp(3), 0, 0);
        detail.setLineSpacing(dp(2), 1f);
        labels.addView(detail);
        TextView status = pill();
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
        statusParams.leftMargin = dp(8);
        card.addView(status, statusParams);
        return new TextView[]{title, detail, status};
    }

    private void broadcastRefresh() {
        sendBroadcast(new Intent(REFRESH).setPackage(getPackageName()));
    }

    /**
     * Updates the value label live, but persists only when the thumb is released
     * (or on keyboard / accessibility changes, which have no tracking phase).
     */
    private void bindSlider(SeekBar bar, TextView value, IntFunction<String> format,
                            IntConsumer live, IntConsumer commit) {
        value.setText(format.apply(bar.getProgress()));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean tracking;
            @Override public void onStartTrackingTouch(SeekBar seekBar) { tracking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                tracking = false;
                commit.accept(seekBar.getProgress());
            }
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(format.apply(progress));
                if (live != null) live.accept(progress);
                if (fromUser && !tracking) commit.accept(progress);
            }
        });
    }

    private void buildFloatingPage(LinearLayout page) {
        LinearLayout old = body;
        LinearLayout oldPage = activePage;
        activePage = body = page;
        floatingSummary = statusStrip(page);
        heading("大小与位置");
        SeekBar size = new SeekBar(this);
        size.setMin(80);
        size.setMax(160);
        size.setProgress(Math.max(80, Math.min(160, prefs.getInt("sizePercent", 100))));
        bindSlider(size, range("展开悬浮窗", "控件与快捷应用的整体大小", size), p -> p + "%", null, p -> {
            prefs.edit().putInt("sizePercent", p).apply();
            broadcastRefresh();
            updatePageSummaries();
        });
        SeekBar iconSize = new SeekBar(this);
        iconSize.setMin(70);
        iconSize.setMax(160);
        iconSize.setProgress(Math.max(70, Math.min(160, prefs.getInt("iconSizePercent", 100))));
        bindSlider(iconSize, range("收起图标", "独立调整，不影响展开尺寸", iconSize), p -> p + "%", null, p -> {
            prefs.edit().putInt("iconSizePercent", p).apply();
            broadcastRefresh();
            updatePageSummaries();
        });
        choice("贴边位置", "拖动后吸附到允许的屏幕边缘",
            new String[]{"左右均可", "仅左侧", "仅右侧"},
            prefs.getInt("edgeMode", 0), position -> {
                if (prefs.getInt("edgeMode", 0) == position) return;
                prefs.edit().putInt("edgeMode", position).apply();
                broadcastRefresh();
            });
        heading("展开与收起");
        choice("展开后显示", "从图标打开时呈现的页面",
            new String[]{"控件", "APP", "记忆收起前状态"},
            prefs.getInt("openMode", 0), position ->
                prefs.edit().putInt("openMode", position).apply());
        FlatSwitch resident = new FlatSwitch();
        resident.setText("常驻展开");
        resident.setChecked(prefs.getBoolean("residentExpanded", false));
        switchRow(resident, "仅在点击浮窗外时收起");
        SeekBar seek = new SeekBar(this);
        seek.setMax(58);
        seek.setProgress(Math.max(0, Math.min(58, prefs.getInt("timeout", 12) - 2)));
        bindSlider(seek, range("自动收起", "空闲后恢复为悬浮图标", seek), p -> (p + 2) + " 秒", null,
            p -> prefs.edit().putInt("timeout", p + 2).apply());
        resident.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("residentExpanded", checked).apply();
            ((View) seek.getParent()).setVisibility(checked ? View.GONE : View.VISIBLE);
            broadcastRefresh();
        });
        ((View) seek.getParent()).setVisibility(resident.isChecked() ? View.GONE : View.VISIBLE);
        heading("外观");
        styleSubtitle = choice("悬浮窗样式", "", Ui.OVERLAY_STYLES, Ui.overlayStyle(this), position -> {
            if (Ui.overlayStyle(this) == position) return;
            prefs.edit().putInt("overlayStyle", position).apply();
            updateStyleHints();
            updatePageSummaries();
            broadcastRefresh();
        });
        opacityBar = new SeekBar(this);
        opacityBar.setMax(80);
        opacityBar.setProgress(Math.max(0, Math.min(80, prefs.getInt("opacity", 24))));
        TextView opacityValue = range("浮窗背景", "", opacityBar);
        opacitySubtitle = ((View) opacityBar.getParent()).findViewWithTag("range-detail");
        bindSlider(opacityBar, opacityValue, p -> p + "%", null, p -> {
            prefs.edit().putInt("opacity", p).apply();
            broadcastRefresh();
        });
        updateStyleHints();
        heading("防误触保护");
        text("展开时，靠近浮窗的外侧点击不会触发收起。实际保护区完全透明。");
        SeekBar protection = new SeekBar(this);
        protection.setMax(80);
        protection.setProgress(Math.max(0, Math.min(80, prefs.getInt("outsideProtectionDp", 24))));
        TextView protectionValue = range("保护范围", "贴边的一侧不会扩展", protection);
        guardPreview = new GuardPreview(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(146));
        previewParams.setMargins(dp(4), dp(6), dp(4), dp(12));
        body.addView(guardPreview, previewParams);
        bindSlider(protection, protectionValue, p -> p + " dp", guardPreview::setProtection, p -> {
            prefs.edit().putInt("outsideProtectionDp", p).apply();
            broadcastRefresh();
        });
        heading("使用体验");
        FlatSwitch vibration = new FlatSwitch();
        vibration.setText("按键触感");
        vibration.setChecked(prefs.getBoolean("vibration", true));
        vibration.setOnCheckedChangeListener((v, checked) ->
            prefs.edit().putBoolean("vibration", checked).apply());
        switchRow(vibration, "点击悬浮窗按键时轻震确认");
        FlatSwitch keepAwake = new FlatSwitch();
        keepAwake.setText("悬浮窗运行时保持屏幕常亮");
        keepAwake.setChecked(prefs.getBoolean("keepScreenOn", false));
        keepAwake.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("keepScreenOn", checked).apply();
            broadcastRefresh();
        });
        switchRow(keepAwake, "停止悬浮窗后恢复系统息屏设置");
        body = old;
        activePage = oldPage;
    }

    private void updateStyleHints() {
        int style = Ui.overlayStyle(this);
        if (styleSubtitle != null) {
            String hint;
            switch (style) {
                case Ui.STYLE_LIGHT: hint = "浅色按键，适合白天的浅色地图"; break;
                case Ui.STYLE_DARK: hint = "深色按键，夜间不刺眼"; break;
                case Ui.STYLE_SUNLIGHT: hint = "纯黑底、白色按键，强光直射下最易辨认"; break;
                case Ui.STYLE_GLASS:
                    WindowManager windows = getSystemService(WindowManager.class);
                    hint = windows != null && windows.isCrossWindowBlurEnabled()
                        ? "实时模糊背后的画面，随昼夜切换明暗"
                        : "系统当前关闭了窗口模糊（如省电模式），将以半透明底色代替";
                    break;
                default: hint = "白天浅色，夜间自动切换为深色";
            }
            styleSubtitle.setText(hint);
        }
        if (opacityBar != null) {
            boolean fixed = style == Ui.STYLE_SUNLIGHT;
            opacityBar.setEnabled(!fixed);
            ((View) opacityBar.getParent()).setAlpha(fixed ? .45f : 1f);
            if (opacitySubtitle != null) {
                opacitySubtitle.setText(fixed ? "强光高对比样式固定为不透明" :
                    style == Ui.STYLE_GLASS ? "玻璃着色浓度" : "背景不透明度");
            }
        }
    }

    private void buildContentPage(LinearLayout page) {
        activePage = body = page;
        contentSummary = statusStrip(page);
        buildAppsPage(page);
        buildLayoutPage(page);
        body = null;
        activePage = null;
    }

    private void buildAppsPage(LinearLayout page) {
        LinearLayout old = body;
        LinearLayout oldPage = activePage;
        activePage = body = page;
        heading("快捷应用");
        for (int i = 0; i < KEYS.length; i++) appChoiceRow(KEYS[i], LABELS[i], i);
        heading("沟通应用");
        FlatSwitch primary = new FlatSwitch();
        primary.setText("使用主实例");
        primary.setChecked(prefs.getBoolean("primaryMessage", true));
        primary.setOnCheckedChangeListener((v, checked) ->
            prefs.edit().putBoolean("primaryMessage", checked).apply());
        switchRow(primary, "避免启动沟通应用时弹出双开选择");
        body = old;
        activePage = oldPage;
    }

    private void buildLayoutPage(LinearLayout page) {
        LinearLayout old = body;
        LinearLayout oldPage = activePage;
        activePage = body = page;
        heading("媒体控件顺序");
        text("调整顺序会同步到悬浮窗。中间的切换按钮固定在第三个位置。");
        mediaOrderRows = new LinearLayout(this);
        mediaOrderRows.setOrientation(LinearLayout.VERTICAL);
        body.addView(mediaOrderRows);
        drawOrderRows(OverlayOrder.MEDIA, mediaOrderRows);
        heading("快捷应用顺序");
        text("调整沟通、导航、音乐和设置在悬浮窗中的位置。");
        appOrderRows = new LinearLayout(this);
        appOrderRows.setOrientation(LinearLayout.VERTICAL);
        body.addView(appOrderRows);
        drawOrderRows(OverlayOrder.APPS, appOrderRows);
        body = old;
        activePage = oldPage;
    }

    private void drawOrderRows(String key, LinearLayout list) {
        list.removeAllViews();
        String[] items = OverlayOrder.get(prefs, key);
        for (int position = 0; position < 5; position++) {
            final int index = position < 2 ? position : position - 1;
            boolean center = position == 2;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(4), dp(2), dp(4));
            row.setMinimumHeight(dp(60));
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
            row.addView(label(String.format(Locale.ROOT, "%02d", position + 1), Ui.TEXT_CAPTION, c.muted, null),
                new LinearLayout.LayoutParams(dp(30), -2));
            if (center) {
                ImageView switcher = new ImageView(this);
                switcher.setImageResource(R.drawable.swap_vertical);
                switcher.setImageTintList(ColorStateList.valueOf(c.brand));
                switcher.setPadding(dp(7), dp(7), dp(7), dp(7));
                switcher.setBackground(round(c.brandTint, Ui.RADIUS_S));
                row.addView(switcher, new LinearLayout.LayoutParams(dp(36), dp(36)));
                TextView name = label("切换页面", Ui.TEXT_BODY, c.muted, null);
                name.setPadding(dp(12), 0, 0, 0);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            } else {
                String item = items[index];
                ImageView icon = new ImageView(this);
                orderIcon(icon, key, item);
                row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
                TextView name = label(orderLabel(key, item), Ui.TEXT_BODY, c.ink, null);
                name.setPadding(dp(12), 0, dp(3), 0);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                orderArrow(row, true, index > 0, () -> moveOrder(key, index, index - 1));
                orderArrow(row, false, index < 3, () -> moveOrder(key, index, index + 1));
            }
            if (position < 4) {
                View divider = new View(this);
                divider.setBackgroundColor(c.divider);
                LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1));
                line.leftMargin = dp(34);
                list.addView(divider, line);
            }
        }
    }

    private void orderArrow(LinearLayout row, boolean up, boolean enabled, Runnable action) {
        ImageView button = new ImageView(this);
        button.setImageResource(R.drawable.chevron_right);
        button.setRotation(up ? -90 : 90);
        button.setImageTintList(ColorStateList.valueOf(enabled ? c.brand : c.disabled));
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setContentDescription(up ? "上移" : "下移");
        button.setEnabled(enabled);
        button.setBackground(touchBackground(c.surface, Ui.RADIUS_S));
        button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(dp(Ui.TOUCH_MIN), dp(Ui.TOUCH_MIN)));
    }

    private void moveOrder(String key, int from, int to) {
        String[] items = OverlayOrder.get(prefs, key);
        String swap = items[from];
        items[from] = items[to];
        items[to] = swap;
        OverlayOrder.save(prefs, key, items);
        drawOrderRows(key, OverlayOrder.MEDIA.equals(key) ? mediaOrderRows : appOrderRows);
        broadcastRefresh();
    }

    private String orderLabel(String key, String item) {
        if (OverlayOrder.MEDIA.equals(key)) {
            switch (item) {
                case "cover": return "专辑封面";
                case "next": return "下一首";
                case "play": return "播放／暂停";
                default: return "上一首";
            }
        }
        switch (item) {
            case "message": return "沟通应用";
            case "navigation": return "导航应用";
            case "music": return "音乐应用";
            default: return "设置";
        }
    }

    private void orderIcon(ImageView icon, String key, String item) {
        if (OverlayOrder.MEDIA.equals(key)) {
            int drawable = "cover".equals(item) ? R.drawable.media_cover :
                "play".equals(item) ? R.drawable.media_play :
                "previous".equals(item) ? R.drawable.media_previous : R.drawable.media_skip;
            icon.setImageResource(drawable);
            icon.setImageTintList(ColorStateList.valueOf(c.ink));
            icon.setPadding(dp(5), dp(5), dp(5), dp(5));
            return;
        }
        if ("settings".equals(item)) {
            icon.setImageResource(R.drawable.nav_settings);
            icon.setImageTintList(ColorStateList.valueOf(c.ink));
            icon.setPadding(dp(6), dp(6), dp(6), dp(6));
            return;
        }
        icon.setImageTintList(null);
        ComponentName component = ComponentName.unflattenFromString(prefs.getString(item, ""));
        if (component != null) {
            try {
                icon.setImageDrawable(getPackageManager().getActivityInfo(component, 0).loadIcon(getPackageManager()));
                return;
            } catch (Exception ignored) {}
        }
        icon.setImageResource(R.drawable.icon);
    }

    private void buildOtherPage(LinearLayout page) {
        LinearLayout old = body;
        LinearLayout oldPage = activePage;
        activePage = body = page;
        settingsSummary = statusStrip(page);
        heading("系统权限");
        button("授予悬浮窗权限", () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName()))));
        button("授予通知使用权（媒体切歌）", () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        heading("控制中心");
        button("添加到控制中心", () -> {
            StatusBarManager statusBar = getSystemService(StatusBarManager.class);
            if (statusBar == null) {
                Toast.makeText(this, "请在控制中心编辑页手动添加驭窗浮控", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                statusBar.requestAddTileService(
                    new ComponentName(this, OverlayTileService.class),
                    "驭窗浮控", Icon.createWithResource(this, R.drawable.qs_icon),
                    getMainExecutor(), result -> {
                        if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED)
                            Toast.makeText(this, "可在控制中心编辑页手动添加驭窗浮控", Toast.LENGTH_LONG).show();
                    });
            } catch (RuntimeException e) {
                Toast.makeText(this, "请在控制中心编辑页手动添加驭窗浮控", Toast.LENGTH_LONG).show();
            }
        });
        diagnosticBlock = new LinearLayout(this);
        diagnosticBlock.setOrientation(LinearLayout.VERTICAL);
        page.addView(diagnosticBlock, overviewParams(0, 0, 0, 0));
        activePage = diagnosticBlock;
        body = diagnosticBlock;
        heading("日志配置");
        FlatSwitch capture = new FlatSwitch();
        capture.setText("锁存诊断日志");
        capture.setChecked(Diagnostics.isLocked(this));
        capture.setOnCheckedChangeListener((view, checked) -> {
            Diagnostics.setLocked(this, checked);
            Diagnostics.status(this::showDiagnosticStatus);
        });
        switchRow(capture, "开启即保存此前15分钟，关闭后保留记录");
        FlatSwitch systemLog = new FlatSwitch();
        systemLog.setText("系统日志（Shizuku）");
        systemLog.setChecked(SystemLogCollector.isEnabled(this));
        systemLog.setOnCheckedChangeListener((view, checked) ->
            SystemLogCollector.setEnabled(this, checked));
        switchRow(systemLog, "可包含其他应用信息，需 Shizuku 授权");
        systemLogStatus = text("检查 Shizuku 状态中");
        button("打开 Shizuku", () -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch == null) {
                Toast.makeText(this, "请先安装 Shizuku", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                startActivity(launch);
            } catch (RuntimeException error) {
                Diagnostics.error("shizuku", "open_manager_failed", error);
                Toast.makeText(this, "无法打开 Shizuku", Toast.LENGTH_LONG).show();
            }
        });
        diagnosticStatus = text("读取日志占用中");
        button("导出日志 ZIP", "包含锁存记录与近期运行事件", () -> {
            Diagnostics.event("export", "requested", "");
            Intent document = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            document.addCategory(Intent.CATEGORY_OPENABLE);
            document.setType("application/zip");
            document.putExtra(Intent.EXTRA_TITLE,
                "驭窗浮控日志-" + System.currentTimeMillis() + ".zip");
            startActivityForResult(document, EXPORT_LOGS);
        });
        text("默认仅记录本应用。启用 Shizuku 后可能包含其他应用和系统日志。导出前请注意隐私，总占用上限 500 MB。");
        button("隐藏日志配置", "关闭后再次点击版本号 5 次可重新打开", () ->
            setDiagnosticVisible(false));
        diagnosticBlock.setVisibility(
            prefs.getBoolean("diagnosticVisible", false) ? View.VISIBLE : View.GONE);
        body = old;
        activePage = oldPage;
        String version = "版本 " + versionName();
        TextView versionView = label(version, Ui.TEXT_SECONDARY, c.muted, null);
        versionView.setGravity(Gravity.CENTER);
        versionView.setPadding(0, dp(16), 0, dp(18));
        versionView.setMinHeight(dp(Ui.TOUCH_MIN));
        versionView.setContentDescription(version + "，连续点击五次打开日志配置");
        versionView.setOnClickListener(v -> handleVersionTap());
        page.addView(versionView, overviewParams(0, 0, 0, 0));
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void handleVersionTap() {
        long now = SystemClock.uptimeMillis();
        if (now - lastVersionTapAt > 1800) versionTapCount = 0;
        lastVersionTapAt = now;
        versionTapCount++;
        if (versionTapCount < 5) return;
        versionTapCount = 0;
        setDiagnosticVisible(true);
        Toast.makeText(this, "日志配置已显示", Toast.LENGTH_SHORT).show();
    }

    private void setDiagnosticVisible(boolean visible) {
        prefs.edit().putBoolean("diagnosticVisible", visible).apply();
        if (diagnosticBlock != null) diagnosticBlock.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) Toast.makeText(this, "日志配置已隐藏", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onResume() {
        super.onResume();
        frameMonitor = FrameMonitor.attach(getWindow(), "main");
        frameMonitor.setContext(PAGE_NAMES[currentPage]);
        registerReceiver(overlayStatus,
            new IntentFilter(OverlayService.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED);
        Diagnostics.event("activity", "resumed", "MainActivity");
        Diagnostics.status(this::showDiagnosticStatus);
        SystemLogCollector.setStatusListener(value -> {
            if (systemLogStatus != null) systemLogStatus.setText(value);
        });
        refreshAppChoices();
        updateStyleHints();
        updateOverview();
        if (currentPage == 1 && guardPreview != null) guardPreview.play();
        startRequestedOverlay();
    }

    private void refreshAppChoices() {
        if (pages == null) return;
        for (String key : KEYS) {
            TextView view = pages[2].findViewWithTag(key);
            if (view != null) view.setText(selectedName(key));
            ImageView icon = pages[2].findViewWithTag("icon:" + key);
            if (icon != null) orderIcon(icon, OverlayOrder.APPS, key);
        }
        if (appOrderRows != null) drawOrderRows(OverlayOrder.APPS, appOrderRows);
    }

    @Override protected void onPause() {
        Diagnostics.event("activity", "paused", "MainActivity");
        if (frameMonitor != null) {
            frameMonitor.stop();
            frameMonitor = null;
        }
        unregisterReceiver(overlayStatus);
        SystemLogCollector.setStatusListener(null);
        super.onPause();
    }

    @Override protected void onDestroy() {
        Diagnostics.event("activity", "destroyed", "MainActivity");
        background.shutdownNow();
        pickerIcons.clear();
        super.onDestroy();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_LOGS || resultCode != RESULT_OK ||
            data == null || data.getData() == null) return;
        Diagnostics.export(this, data.getData(), error -> {
            Toast.makeText(this, error == null ? "日志已导出" : "日志导出失败",
                Toast.LENGTH_SHORT).show();
            Diagnostics.status(this::showDiagnosticStatus);
        });
    }

    private void showDiagnosticStatus(Diagnostics.Status status) {
        if (diagnosticStatus == null || isFinishing() || isDestroyed()) return;
        diagnosticStatus.setText(String.format(Locale.ROOT, "已用 %.1f / 500 MB · %d 份锁存记录",
            status.bytes / (1024d * 1024d), status.captures));
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
        if (controller != null) {
            int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(c.night ? 0 : lightBars, lightBars);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        startRequested = intent.getBooleanExtra("startOverlay", false);
        startRequestedOverlay();
    }

    private void startRequestedOverlay() {
        if (!startRequested) return;
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限，返回后将自动开启", Toast.LENGTH_LONG).show();
            return;
        }
        startRequested = false;
        startForegroundService(new Intent(this, OverlayService.class).setAction("start"));
    }

    private boolean mediaGranted() {
        String listeners = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return listeners != null && listeners.contains(getPackageName());
    }

    private boolean appAvailable(String key) {
        ComponentName component = ComponentName.unflattenFromString(prefs.getString(key, ""));
        if (component == null) return false;
        try {
            getPackageManager().getActivityInfo(component, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String selectedName(String key) {
        String value = prefs.getString(key, "");
        if (value.isEmpty()) return "未选择";
        try {
            ComponentName component = ComponentName.unflattenFromString(value);
            return getPackageManager().getActivityInfo(component, 0).loadLabel(getPackageManager()).toString();
        } catch (Exception e) { return "应用不可用，请重新选择"; }
    }

    private void pick(int slot) {
        List<ResolveInfo> apps = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Integer> filtered = new ArrayList<>();
        Dialog dialog = new Dialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(10), dp(18), dp(18));
        sheet.setBackground(round(c.surface, Ui.RADIUS_L));
        View handle = new View(this);
        handle.setBackground(round(c.divider, 2));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(18);
        sheet.addView(handle, handleParams);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), 0, dp(6), dp(12));
        sheet.addView(top);
        top.addView(label("选择" + LABELS[slot], Ui.TEXT_SUBHEAD, c.ink, Ui.BOLD),
            new LinearLayout.LayoutParams(0, -2, 1));
        TextView cancel = label("取消", Ui.TEXT_BODY, c.brand, null);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(12), 0, dp(12), 0);
        cancel.setMinHeight(dp(Ui.TOUCH_MIN));
        cancel.setBackground(touchBackground(c.surface, Ui.RADIUS_S));
        top.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
        cancel.setOnClickListener(view -> dialog.dismiss());
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(Ui.TEXT_BODY);
        search.setTextColor(c.ink);
        search.setHintTextColor(c.muted);
        search.setHint("搜索应用");
        search.setPadding(dp(16), dp(10), dp(16), dp(10));
        search.setMinHeight(dp(Ui.TOUCH_MIN));
        search.setBackground(round(c.surfaceMuted, Ui.RADIUS_S));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2);
        searchParams.bottomMargin = dp(12);
        sheet.addView(search, searchParams);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        PackageManager packages = getPackageManager();
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return filtered.size(); }
            @Override public Object getItem(int position) { return apps.get(filtered.get(position)); }
            @Override public long getItemId(int position) { return filtered.get(position); }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                if (convertView instanceof LinearLayout) {
                    row = (LinearLayout) convertView;
                } else {
                    row = new LinearLayout(MainActivity.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(12), dp(6), dp(12), dp(6));
                    row.setMinimumHeight(dp(56));
                    row.setBackground(touchBackground(c.surface, Ui.RADIUS_S));
                    ImageView icon = new ImageView(MainActivity.this);
                    row.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
                    LinearLayout labels = new LinearLayout(MainActivity.this);
                    labels.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, -2, 1);
                    labelsParams.leftMargin = dp(14);
                    row.addView(labels, labelsParams);
                    TextView name = label("", Ui.TEXT_BODY, c.ink, null);
                    name.setSingleLine(true);
                    name.setEllipsize(TextUtils.TruncateAt.END);
                    labels.addView(name);
                    TextView packageName = label("", Ui.TEXT_CAPTION, c.muted, null);
                    packageName.setSingleLine(true);
                    packageName.setEllipsize(TextUtils.TruncateAt.END);
                    labels.addView(packageName);
                }
                int index = filtered.get(position);
                ResolveInfo info = apps.get(index);
                ImageView icon = (ImageView) row.getChildAt(0);
                String key = info.activityInfo.packageName + "/" + info.activityInfo.name;
                icon.setTag(key);
                Drawable cached = pickerIcons.get(key);
                icon.setImageDrawable(cached);
                if (cached == null && pickerLoading.add(key)) {
                    background.execute(() -> {
                        Drawable loaded = info.loadIcon(packages);
                        runOnUiThread(() -> {
                            pickerLoading.remove(key);
                            pickerIcons.put(key, loaded);
                            for (int i = 0; i < list.getChildCount(); i++) {
                                View child = list.getChildAt(i);
                                if (!(child instanceof LinearLayout)) continue;
                                ImageView target = (ImageView) ((LinearLayout) child).getChildAt(0);
                                if (key.equals(target.getTag())) target.setImageDrawable(loaded);
                            }
                        });
                    });
                }
                LinearLayout labels = (LinearLayout) row.getChildAt(1);
                ((TextView) labels.getChildAt(0)).setText(names.get(index));
                ((TextView) labels.getChildAt(1)).setText(info.activityInfo.packageName);
                return row;
            }
        };
        list.setAdapter(adapter);
        FrameLayout results = new FrameLayout(this);
        LinearLayout.LayoutParams resultsParams = new LinearLayout.LayoutParams(-1,
            Math.min(dp(400), getResources().getDisplayMetrics().heightPixels / 2));
        sheet.addView(results, resultsParams);
        results.addView(list, new FrameLayout.LayoutParams(-1, -1));
        TextView empty = label("正在读取应用…", Ui.TEXT_BODY, c.muted, null);
        empty.setGravity(Gravity.CENTER);
        results.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        Runnable applyFilter = () -> {
            String term = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            filtered.clear();
            for (int i = 0; i < apps.size(); i++) {
                if (names.get(i).toLowerCase(Locale.ROOT).contains(term)
                    || apps.get(i).activityInfo.packageName.toLowerCase(Locale.ROOT).contains(term)) {
                    filtered.add(i);
                }
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                applyFilter.run();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            ResolveInfo chosen = apps.get(filtered.get(position));
            String component = new ComponentName(chosen.activityInfo.packageName, chosen.activityInfo.name).flattenToString();
            prefs.edit().putString(KEYS[slot], component).apply();
            refreshAppChoices();
            updatePageSummaries();
            dialog.dismiss();
            broadcastRefresh();
        });
        showBottomSheet(dialog, sheet);
        background.execute(() -> {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> found = packages.queryIntentActivities(query, 0);
            List<ResolveInfo> loadedApps = new ArrayList<>();
            List<String> loadedNames = new ArrayList<>();
            for (ResolveInfo info : found) {
                if (info.activityInfo.packageName.equals(getPackageName())) continue;
                loadedApps.add(info);
                loadedNames.add(info.loadLabel(packages).toString());
            }
            Integer[] order = new Integer[loadedApps.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Collator collator = Collator.getInstance();
            java.util.Arrays.sort(order, (a, b) -> collator.compare(loadedNames.get(a), loadedNames.get(b)));
            List<ResolveInfo> sortedApps = new ArrayList<>(order.length);
            List<String> sortedNames = new ArrayList<>(order.length);
            for (Integer i : order) {
                sortedApps.add(loadedApps.get(i));
                sortedNames.add(loadedNames.get(i));
            }
            runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                apps.addAll(sortedApps);
                names.addAll(sortedNames);
                empty.setText("没有找到应用");
                applyFilter.run();
            });
        });
    }

    private void appChoiceRow(String key, String title, int slot) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(8), dp(5), dp(8));
        row.setMinimumHeight(dp(70));
        row.setBackground(touchBackground(c.surface, Ui.RADIUS_M));
        row.setContentDescription(title + "，" + selectedName(key));
        row.setOnClickListener(v -> pick(slot));

        ImageView icon = new ImageView(this);
        icon.setTag("icon:" + key);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setBackground(round(c.brandTint, Ui.RADIUS_M));
        orderIcon(icon, OverlayOrder.APPS, key);
        row.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(13), 0, dp(6), 0);
        labels.addView(label(title, Ui.TEXT_BODY, c.ink, Ui.MEDIUM));
        TextView selected = label(selectedName(key), Ui.TEXT_SECONDARY, c.muted, null);
        selected.setTag(key);
        selected.setSingleLine(true);
        selected.setEllipsize(TextUtils.TruncateAt.END);
        selected.setPadding(0, dp(3), 0, 0);
        labels.addView(selected);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.chevron_right);
        arrow.setImageTintList(ColorStateList.valueOf(c.chevron));
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(42)));
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void heading(String value) {
        TextView view = label(value, Ui.TEXT_SECONDARY, c.section, Ui.MEDIUM);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(dp(4), dp(26), 0, dp(12));
        activePage.addView(view, titleParams);
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(16), dp(7), dp(16), dp(7));
        group.setBackground(round(c.surface, Ui.RADIUS_L));
        activePage.addView(group, new LinearLayout.LayoutParams(-1, -2));
        body = group;
    }
    private TextView text(String value) {
        TextView view = label(value, Ui.TEXT_SECONDARY, c.muted, null);
        view.setPadding(dp(8), dp(12), dp(8), dp(12));
        view.setLineSpacing(dp(2), 1f);
        body.addView(view);
        return view;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private RippleDrawable touchBackground(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(c.ripple),
            round(color, radius), round(0xffffffff, radius));
    }
    private TextView range(String title, String subtitle, SeekBar bar) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(8), dp(14), dp(8), dp(2));
        body.addView(container, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        container.addView(top);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(label(title, Ui.TEXT_BODY, c.ink, Ui.MEDIUM));
        TextView detail = label(subtitle, Ui.TEXT_SECONDARY, c.muted, null);
        detail.setTag("range-detail");
        detail.setPadding(0, dp(3), 0, 0);
        labels.addView(detail);
        top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = label("", Ui.TEXT_BODY, c.brand, Ui.BOLD);
        value.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        value.setMinWidth(dp(64));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-2, -2);
        valueParams.leftMargin = dp(6);
        top.addView(value, valueParams);
        bar.setProgressTintList(ColorStateList.valueOf(c.brand));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(c.switchTrack));
        // Custom thumb: brand oval with white stroke
        android.graphics.drawable.GradientDrawable thumbShape = new android.graphics.drawable.GradientDrawable();
        thumbShape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumbShape.setColor(c.brand);
        thumbShape.setStroke(dp(2), 0xffffffff);
        thumbShape.setSize(dp(22), dp(22));
        bar.setThumb(thumbShape);
        bar.setThumbOffset(0);
        bar.setPadding(0, 0, 0, 0);
        bar.setContentDescription(title);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(44));
        barParams.topMargin = dp(4);
        container.addView(bar, barParams);
        return value;
    }
    private TextView choice(String title, String subtitle, String[] options,
                            int selected, IntConsumer onSelected) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(8), dp(12), dp(8), dp(5));
        body.addView(container, new LinearLayout.LayoutParams(-1, -2));
        container.addView(label(title, Ui.TEXT_BODY, c.ink, Ui.MEDIUM));
        TextView detail = label(subtitle, Ui.TEXT_SECONDARY, c.muted, null);
        detail.setPadding(0, dp(3), 0, 0);
        container.addView(detail);
        int[] current = {Math.max(0, Math.min(options.length - 1, selected))};
        LinearLayout valueRow = new LinearLayout(this);
        valueRow.setGravity(Gravity.CENTER_VERTICAL);
        valueRow.setPadding(dp(12), dp(8), dp(8), dp(8));
        valueRow.setMinimumHeight(dp(Ui.TOUCH_MIN));
        valueRow.setBackground(touchBackground(c.surfaceMuted, Ui.RADIUS_S));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dp(9);
        container.addView(valueRow, rowParams);
        TextView value = label(options[current[0]], Ui.TEXT_BODY, c.ink, null);
        valueRow.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.chevron_right);
        arrow.setImageTintList(ColorStateList.valueOf(c.chevron));
        arrow.setRotation(90);
        valueRow.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(20)));
        valueRow.setContentDescription(title + "，" + options[current[0]]);
        valueRow.setOnClickListener(view -> showChoiceSheet(title, options, current[0], position -> {
            current[0] = position;
            value.setText(options[position]);
            valueRow.setContentDescription(title + "，" + options[position]);
            onSelected.accept(position);
        }));
        return detail;
    }
    private void showChoiceSheet(String title, String[] options, int selected, IntConsumer onSelected) {
        Dialog dialog = new Dialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(20), dp(20), dp(22));
        sheet.setBackground(round(c.surface, Ui.RADIUS_L));
        TextView sheetTitle = label("选择" + title, Ui.TEXT_SUBHEAD, c.ink, Ui.BOLD);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.setMargins(dp(8), dp(2), 0, dp(14));
        sheet.addView(sheetTitle, headingParams);
        for (int i = 0; i < options.length; i++) {
            final int position = i;
            boolean active = i == selected;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(8), dp(16), dp(8));
            row.setMinimumHeight(dp(56));
            row.setBackground(touchBackground(active ? c.brandTint : c.surface, Ui.RADIUS_S));
            sheet.addView(row, new LinearLayout.LayoutParams(-1, -2));
            TextView name = label(options[i], Ui.TEXT_BODY, active ? c.brand : c.ink, active ? Ui.BOLD : null);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            if (active) {
                ImageView check = new ImageView(this);
                check.setImageResource(R.drawable.choice_check);
                check.setImageTintList(ColorStateList.valueOf(c.brand));
                row.addView(check, new LinearLayout.LayoutParams(dp(22), dp(22)));
            }
            row.setOnClickListener(view -> {
                onSelected.accept(position);
                dialog.dismiss();
            });
        }
        showBottomSheet(dialog, sheet);
    }
    private void showBottomSheet(Dialog dialog, LinearLayout sheet) {
        dialog.setContentView(sheet);
        dialog.setCanceledOnTouchOutside(true);
        sheet.setFocusableInTouchMode(true);
        sheet.requestFocus();
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = getResources().getDisplayMetrics().widthPixels - dp(24);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.BOTTOM;
            params.y = dp(16);
            params.dimAmount = c.night ? 0.55f : 0.32f;
            window.setAttributes(params);
        }
    }
    private void switchRow(FlatSwitch toggle, String subtitle) {
        String title = toggle.getText().toString();
        toggle.setText(null);
        toggle.setContentDescription(title);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setMinimumHeight(dp(56));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(label(title, Ui.TEXT_BODY, c.ink, Ui.MEDIUM));
        if (subtitle != null) {
            TextView detail = label(subtitle, Ui.TEXT_SECONDARY, c.muted, null);
            detail.setPadding(0, dp(3), 0, 0);
            labels.addView(detail);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(toggle, new LinearLayout.LayoutParams(dp(56), dp(Ui.TOUCH_MIN)));
        row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    private class FlatSwitch extends CompoundButton {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        FlatSwitch() {
            super(MainActivity.this);
            setButtonDrawable((Drawable) null);
            setClickable(true);
            setFocusable(true);
        }

        @Override public void setChecked(boolean checked) {
            boolean changed = isChecked() != checked;
            super.setChecked(checked);
            if (changed) invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float trackWidth = dp(46);
            float trackHeight = dp(26);
            float left = (getWidth() - trackWidth) / 2f;
            float top = (getHeight() - trackHeight) / 2f;
            paint.setColor(isChecked() ? c.brandFill : c.switchTrack);
            canvas.drawRoundRect(left, top, left + trackWidth, top + trackHeight,
                trackHeight / 2, trackHeight / 2, paint);
            paint.setColor(0xffffffff);
            float cx = isChecked() ? left + trackWidth - dp(13) : left + dp(13);
            canvas.drawCircle(cx, top + trackHeight / 2, dp(10), paint);
        }

        @Override public CharSequence getAccessibilityClassName() {
            return android.widget.Switch.class.getName();
        }
    }
    /** Illustrates the guard ring; animates briefly after a change instead of looping forever. */
    private class GuardPreview extends View {
        private static final long PLAY_MS = 2800;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final DashPathEffect dash = new DashPathEffect(new float[]{dp(5), dp(4)}, 0);
        private int protection = Math.max(0, Math.min(80, prefs.getInt("outsideProtectionDp", 24)));
        private long animationStart;
        private long animateUntil;

        GuardPreview(Context context) {
            super(context);
            setContentDescription("防误触保护范围示意");
        }
        void setProtection(int value) {
            protection = value;
            play();
        }
        void play() {
            long now = SystemClock.uptimeMillis();
            if (now >= animateUntil) animationStart = now;
            animateUntil = now + PLAY_MS;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            long now = SystemClock.uptimeMillis();
            boolean animating = ValueAnimator.areAnimatorsEnabled() && now < animateUntil;
            float t = animating
                ? (float) (.5 + .5 * Math.sin((now - animationStart) / 1000.0 * 2.8))
                : .3f;
            float cy = getHeight() / 2f;
            float width = dp(48), height = dp(76);
            float margin = dp(protection * .45f);
            float right = getWidth() - dp(18);
            float left = right - width;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(c.surfaceMuted);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(Ui.RADIUS_M), dp(Ui.RADIUS_M), paint);
            paint.setColor(c.brandTint);
            canvas.drawRoundRect(left - margin, cy - height / 2 - margin,
                right, cy + height / 2 + margin, dp(Ui.RADIUS_M), dp(Ui.RADIUS_M), paint);
            paint.setColor(c.surface);
            canvas.drawRoundRect(left, cy - height / 2, right, cy + height / 2,
                dp(Ui.RADIUS_M), dp(Ui.RADIUS_M), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(c.brand);
            paint.setPathEffect(dash);
            canvas.drawRoundRect(left - margin, cy - height / 2 - margin,
                right, cy + height / 2 + margin, dp(Ui.RADIUS_M), dp(Ui.RADIUS_M), paint);
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(left + width / 2, cy, dp(5), paint);
            float touchX = left - dp(7) - t * (margin + dp(13));
            boolean ignored = touchX >= left - margin;
            paint.setColor(ignored ? c.brand : c.muted);
            canvas.drawCircle(touchX, cy, dp(5), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            canvas.drawCircle(touchX, cy, dp(8), paint);
            paint.setStyle(Paint.Style.FILL);
            if (animating && isShown()) postInvalidateOnAnimation();
        }
    }
    private void button(String name, Runnable action) {
        button(name, null, action);
    }
    private TextView button(String name, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(10), dp(8), dp(10));
        row.setMinimumHeight(dp(58));
        row.setContentDescription(name);
        row.setBackground(touchBackground(c.surface, Ui.RADIUS_S));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = label(name, Ui.TEXT_BODY, c.ink, Ui.MEDIUM);
        title.setGravity(Gravity.CENTER_VERTICAL);
        labels.addView(title);
        TextView detail = label(subtitle, Ui.TEXT_SECONDARY, c.muted, null);
        detail.setPadding(0, dp(3), 0, 0);
        detail.setVisibility(subtitle == null ? View.GONE : View.VISIBLE);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.chevron_right);
        arrow.setImageTintList(ColorStateList.valueOf(c.chevron));
        arrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        arrowParams.leftMargin = dp(8);
        row.addView(arrow, arrowParams);
        row.setOnClickListener(v -> action.run());
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return detail;
    }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
}
