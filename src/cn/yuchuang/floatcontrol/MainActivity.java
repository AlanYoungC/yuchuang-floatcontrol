package cn.yuchuang.floatcontrol;

import android.app.Activity;
import android.app.Dialog;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.DashPathEffect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.FrameLayout;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

public class MainActivity extends Activity {
    private static final int EXPORT_LOGS = 1401;
    private static final int CANVAS = 0xfff7f7f8;
    private static final int SURFACE = 0xffffffff;
    private static final int INK = 0xff17191d;
    private static final int MUTED = 0xff676b75;
    private static final int SECTION = 0xff7c85a2;
    private static final int BLUE = 0xff347ff0;
    private static final int LEAD_HEIGHT_DP = 126;
    private SharedPreferences prefs;
    private LinearLayout body;
    private LinearLayout activePage;
    private LinearLayout[] pages;
    private LinearLayout mediaOrderRows, appOrderRows;
    private LinearLayout overviewContent;
    private ScrollView mainScroll;
    private TextView pageTitle, pageSubtitle;
    private GuardPreview guardPreview;
    private TextView permissions;
    private TextView diagnosticStatus;
    private TextView systemLogStatus;
    private TextView floatingSummaryState;
    private TextView floatingSummaryDetail;
    private TextView settingsSummaryState;
    private TextView settingsSummaryDetail;
    private LinearLayout diagnosticBlock;
    private TextView versionView;
    private int versionTapCount;
    private long lastVersionTapAt;
    private FrameMonitor frameMonitor;
    private ImageView[] navIcons;
    private TextView[] navLabels;
    private LinearLayout[] navItems;
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
        startRequested = getIntent().getBooleanExtra("startOverlay", false);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        getWindow().setStatusBarColor(CANVAS);
        getWindow().setNavigationBarColor(CANVAS);
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(CANVAS);
        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        mainScroll.setClipToPadding(false);
        mainScroll.setVerticalScrollBarEnabled(false);
        screen.addView(mainScroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        mainScroll.addView(root);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(24), dp(52), dp(24), dp(16));
        root.addView(header);
        pageTitle = textIn(header, "总览", 34);
        pageTitle.setTextColor(INK);
        pageTitle.setTypeface(null, 1);
        pageTitle.setPadding(0, dp(4), 0, dp(2));
        pageSubtitle = textIn(header, PAGE_SUBTITLES[0], 14);
        pageSubtitle.setTextColor(SECTION);
        pageSubtitle.setVisibility(View.VISIBLE);
        pages = new LinearLayout[4];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = new LinearLayout(this);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            pages[i].setPadding(dp(20), 0, dp(20), dp(148));
            pages[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            root.addView(pages[i]);
        }
        activePage = body = pages[0];
        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable glass = round(0xfffcfcfd, 40);
        glass.setStroke(dp(1), 0xffffffff);
        navigation.setBackground(glass);
        navigation.setElevation(dp(10));
        FrameLayout.LayoutParams navigationParams = new FrameLayout.LayoutParams(-1, dp(56), Gravity.BOTTOM);
        navigationParams.setMargins(dp(40), 0, dp(40), dp(60));
        screen.addView(navigation, navigationParams);
        View statusScrim = new View(this);
        statusScrim.setBackgroundColor(CANVAS);
        screen.addView(statusScrim, new FrameLayout.LayoutParams(-1, dp(28), Gravity.TOP));
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getInsets(WindowInsets.Type.statusBars()).top;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) statusScrim.getLayoutParams();
            if (params.height != top) {
                params.height = top;
                statusScrim.setLayoutParams(params);
            }
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
            item.setContentDescription(PAGE_NAMES[i]);
            item.setBackground(i == 0 ? selectedNavigationBackground() : null);
            navigation.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
            ImageView symbol = new ImageView(this);
            symbol.setImageResource(icons[i]);
            symbol.setImageTintList(ColorStateList.valueOf(i == 0 ? BLUE : INK));
            item.addView(symbol, new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView label = new TextView(this);
            label.setText(PAGE_NAMES[i]);
            label.setTextSize(11);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(i == 0 ? BLUE : INK);
            label.setTypeface(null, i == 0 ? 1 : 0);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, dp(17));
            labelParams.topMargin = dp(1);
            item.addView(label, labelParams);
            navIcons[i] = symbol;
            navLabels[i] = label;
            navItems[i] = item;
            item.setOnClickListener(v -> selectPage(page));
        }
        setContentView(screen);
        buildOverviewPage();
        buildFloatingPage(pages[1]);
        buildContentPage(pages[2]);
        buildOtherPage(pages[3]);
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
        for (int j = 0; j < pages.length; j++) {
            pages[j].setVisibility(j == page ? View.VISIBLE : View.GONE);
            navIcons[j].setImageTintList(ColorStateList.valueOf(j == page ? BLUE : INK));
            navLabels[j].setTextColor(j == page ? BLUE : INK);
            navLabels[j].setTypeface(null, j == page ? 1 : 0);
            navItems[j].setBackground(j == page ? selectedNavigationBackground() : null);
        }
        pageTitle.setText(PAGE_NAMES[page]);
        if (frameMonitor != null) frameMonitor.setContext(PAGE_NAMES[page]);
        pageSubtitle.setText(PAGE_SUBTITLES[page]);
        pageSubtitle.setVisibility(PAGE_SUBTITLES[page].isEmpty() ? View.GONE : View.VISIBLE);
        if (page == 0) updateOverview();
        if (page == 1 && guardPreview != null) guardPreview.invalidate();
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

    private void updatePageSummaries() {
        boolean running = OverlayService.isRunning();
        boolean permitted = Settings.canDrawOverlays(this);
        boolean media = mediaGranted();
        if (floatingSummaryState != null) {
            floatingSummaryState.setText(running ? "正在运行" : "尚未开启");
        }
        if (floatingSummaryDetail != null) {
            floatingSummaryDetail.setText(running ? OverlayService.currentStateLabel() :
                permitted ? "悬浮窗已就绪" : "需要授权后才能显示悬浮窗");
        }
        if (settingsSummaryState != null) {
            settingsSummaryState.setText(permitted && media ? "权限就绪" : "需要处理");
        }
        if (settingsSummaryDetail != null) {
            settingsSummaryDetail.setText("悬浮窗：" + (permitted ? "已授权" : "待授权") +
                "    ·    媒体：" + (media ? "已授权" : "待授权"));
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

        FrameLayout hero = new FrameLayout(this);
        hero.setPadding(dp(20), dp(18), dp(18), dp(16));
        hero.setBackground(round(0xff202b3d, 22));
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(-1, dp(LEAD_HEIGHT_DP));
        heroParams.bottomMargin = dp(10);
        overviewContent.addView(hero, heroParams);

        LinearLayout heroLabels = new LinearLayout(this);
        heroLabels.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams heroLabelParams = new FrameLayout.LayoutParams(
            -1, -2, Gravity.TOP | Gravity.LEFT);
        heroLabelParams.rightMargin = dp(94);
        hero.addView(heroLabels, heroLabelParams);

        TextView heroEyebrow = overviewText("悬浮窗状态", 12, 0xff91a3bd);
        heroEyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heroLabels.addView(heroEyebrow);
        TextView heroState = overviewText(running ? "正在运行" : "尚未开启", 27, Color.WHITE);
        heroState.setTypeface(null, 1);
        heroState.setPadding(0, dp(9), 0, dp(5));
        heroLabels.addView(heroState);
        TextView heroDetail = overviewText(running ? OverlayService.currentStateLabel() :
            permitted ? "悬浮窗已就绪，等待你的指令" : "需要授权后才能显示悬浮窗", 14, 0xffc7d2e2);
        heroDetail.setLineSpacing(dp(2), 1f);
        heroLabels.addView(heroDetail);

        TextView liveDot = overviewText(running ? "●  LIVE" : "○  OFF", 11,
            running ? 0xff9ee8bb : 0xffb7c0cf);
        liveDot.setGravity(Gravity.CENTER);
        liveDot.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        liveDot.setBackground(round(running ? 0x3329c76f : 0x263f5067, 18));
        FrameLayout.LayoutParams liveParams = new FrameLayout.LayoutParams(
            dp(76), dp(34), Gravity.TOP | Gravity.RIGHT);
        hero.addView(liveDot, liveParams);

        LinearLayout actions = new LinearLayout(this);
        overviewContent.addView(actions, overviewParams(0, 0, 0, 20));
        TextView heroAction = overviewText(running ? "关闭悬浮窗" :
            permitted ? "开启悬浮窗" : "授予权限并开启", 14, Color.WHITE);
        heroAction.setGravity(Gravity.CENTER);
        heroAction.setTypeface(null, 1);
        heroAction.setBackground(touchBackground(running ? 0xff315276 : BLUE, 18));
        heroAction.setMinHeight(dp(44));
        heroAction.setOnClickListener(v -> toggleOverlay());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        actionParams.rightMargin = dp(9);
        actions.addView(heroAction, actionParams);

        TextView heroShortcut = overviewText("调整浮窗", 13, 0xffc7d2e2);
        heroShortcut.setGravity(Gravity.CENTER);
        heroShortcut.setBackground(touchBackground(0x263f5067, 16));
        heroShortcut.setOnClickListener(v -> selectPage(1));
        actions.addView(heroShortcut, new LinearLayout.LayoutParams(dp(112), dp(44)));

        overviewSectionTitle("快捷应用与媒体控件", "编辑", () -> selectPage(2));
        String[] appOrder = OverlayOrder.get(prefs, OverlayOrder.APPS);
        String[] mediaOrder = OverlayOrder.get(prefs, OverlayOrder.MEDIA);
        LinearLayout orderCards = new LinearLayout(this);
        orderCards.setGravity(Gravity.CENTER);
        overviewContent.addView(orderCards, overviewParams(0, 0, 0, 16));
        overviewOrderCard(orderCards, "快捷应用", OverlayOrder.APPS, appOrder);
        overviewOrderCard(orderCards, "媒体控件", OverlayOrder.MEDIA, mediaOrder);

        overviewSectionTitle("运行状态", "打开设置", () -> selectPage(3));
        LinearLayout statusGroup = overviewGroup();
        overviewContent.addView(statusGroup, overviewParams(0, 0, 0, 30));
        overviewStatusRow(statusGroup, "悬浮窗权限",
            permitted ? "已授权" : "待授权", permitted, false);
        overviewStatusRow(statusGroup, "媒体通知使用权",
            media ? "已授权" : "待授权", media, false);
        overviewStatusRow(statusGroup, "日志锁存",
            locked ? "已开启" : "未开启", locked, false);
        overviewStatusRow(statusGroup, "系统日志",
            systemLogs ? "Shizuku 已启用" : "仅记录本应用", systemLogs, true);
        statusGroup.setOnClickListener(v -> selectPage(3));
    }

    private TextView overviewText(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
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
        group.setBackground(round(SURFACE, 22));
        return group;
    }

    private void overviewSectionTitle(String title, String action, Runnable onClick) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(8), dp(2), dp(10));
        overviewContent.addView(header, overviewParams(0, 0, 0, 0));
        TextView label = overviewText(title, 17, INK);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView link = overviewText(action + "  ›", 13, BLUE);
        link.setGravity(Gravity.CENTER);
        link.setPadding(dp(8), 0, dp(4), 0);
        link.setMinHeight(dp(36));
        link.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        link.setOnClickListener(v -> onClick.run());
        header.addView(link, new LinearLayout.LayoutParams(dp(90), dp(40)));
    }

    private void overviewOrderCard(LinearLayout parent, String title, String key, String[] items) {
        SquareStatusCard card = new SquareStatusCard();
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(11), dp(10), dp(10), dp(9));
        card.setBackground(touchBackground(SURFACE, 18));
        card.setContentDescription(title + "顺序，点击编辑");
        card.setOnClickListener(v -> selectPage(2));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, -2, 1);
        cardParams.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(card, cardParams);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header, new LinearLayout.LayoutParams(-1, dp(26)));
        TextView name = overviewText(title, 16, INK);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView count = overviewText("4 项", 12, MUTED);
        header.addView(count);

        for (int position = 0; position < 5; position++) {
            boolean switcher = position == 2;
            String item = switcher ? null : items[position < 2 ? position : position - 1];
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
            TextView number = overviewText(String.format(Locale.ROOT, "%02d", position + 1), 11, MUTED);
            row.addView(number, new LinearLayout.LayoutParams(dp(21), -2));
            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            if (switcher) {
                icon.setImageResource(R.drawable.swap_vertical);
                icon.setImageTintList(ColorStateList.valueOf(BLUE));
            } else {
                orderIcon(icon, key, item);
            }
            row.addView(icon, new LinearLayout.LayoutParams(dp(21), dp(21)));
            String label = switcher ? "切换页面" : orderLabel(key, item);
            if (!switcher && OverlayOrder.APPS.equals(key) && !"settings".equals(item)) {
                label = selectedName(item);
            }
            TextView value = overviewText(label, 13, switcher ? BLUE : INK);
            value.setSingleLine(true);
            value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            value.setPadding(dp(6), 0, 0, 0);
            row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    private void overviewStatusRow(LinearLayout parent, String title, String state,
                                   boolean okay, boolean last) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(2), dp(4), dp(2));
        parent.addView(row, new LinearLayout.LayoutParams(-1, dp(52)));
        TextView name = overviewText(title, 15, INK);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = overviewText(state, 13, okay ? 0xff1b9b5a : 0xffd18122);
        value.setGravity(Gravity.CENTER);
        value.setBackground(round(okay ? 0xffe8f8ef : 0xfffff3e3, 12));
        row.addView(value, new LinearLayout.LayoutParams(dp(118), dp(32)));
        if (!last) {
            View divider = new View(this);
            divider.setBackgroundColor(0xffeef0f3);
            parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private void buildFloatingPage(LinearLayout page) {
        LinearLayout old = body;
        LinearLayout oldPage = activePage;
        activePage = body = page;
        boolean running = OverlayService.isRunning();
        FrameLayout lead = pageLead(page, "浮窗控制",
            running ? "正在运行" : "尚未开启",
            running ? OverlayService.currentStateLabel() : "调整大小、位置和展开方式",
            0xff202b3d, Color.WHITE, 0xffc7d2e2);
        floatingSummaryState = lead.findViewWithTag("lead-title");
        floatingSummaryDetail = lead.findViewWithTag("lead-detail");
        heading("大小与位置");
        SeekBar size = new SeekBar(this);
        size.setMin(80);
        size.setMax(160);
        size.setProgress(Math.max(80, Math.min(160, prefs.getInt("sizePercent", 100))));
        TextView sizeValue = range("展开悬浮窗", "控件与快捷应用的整体大小", size);
        sizeValue.setText(size.getProgress() + "%");
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {
                sendBroadcast(new Intent("cn.yuchuang.floatcontrol.REFRESH").setPackage(getPackageName()));
            }
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                sizeValue.setText(progress + "%");
                if (fromUser) prefs.edit().putInt("sizePercent", progress).apply();
            }
        });
        SeekBar iconSize = new SeekBar(this);
        iconSize.setMin(70);
        iconSize.setMax(160);
        iconSize.setProgress(Math.max(70, Math.min(160, prefs.getInt("iconSizePercent", 100))));
        TextView iconSizeValue = range("收起图标", "独立调整，不影响展开尺寸", iconSize);
        iconSizeValue.setText(iconSize.getProgress() + "%");
        iconSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {
                sendBroadcast(new Intent("cn.yuchuang.floatcontrol.REFRESH").setPackage(getPackageName()));
            }
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                iconSizeValue.setText(progress + "%");
                if (fromUser) prefs.edit().putInt("iconSizePercent", progress).apply();
            }
        });
        choice("贴边位置", "拖动后吸附到允许的屏幕边缘",
            new String[]{"左右均可", "仅左侧", "仅右侧"},
            prefs.getInt("edgeMode", 0), position -> {
                if (prefs.getInt("edgeMode", 0) == position) return;
                prefs.edit().putInt("edgeMode", position).apply();
                sendBroadcast(new Intent("cn.yuchuang.floatcontrol.REFRESH").setPackage(getPackageName()));
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
        TextView duration = range("自动收起", "空闲后恢复为悬浮图标", seek);
        duration.setText((seek.getProgress() + 2) + " 秒");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                duration.setText((progress + 2) + " 秒");
                if (fromUser) prefs.edit().putInt("timeout", progress + 2).apply();
            }
        });
        resident.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("residentExpanded", checked).apply();
            ((View) seek.getParent()).setVisibility(checked ? View.GONE : View.VISIBLE);
            sendBroadcast(new Intent("cn.yuchuang.floatcontrol.REFRESH").setPackage(getPackageName()));
        });
        ((View) seek.getParent()).setVisibility(resident.isChecked() ? View.GONE : View.VISIBLE);
        heading("外观");
        SeekBar opacity = new SeekBar(this);
        opacity.setMax(80);
        opacity.setProgress(prefs.getInt("opacity", 24));
        TextView opacityValue = range("浮窗背景", "不透明度", opacity);
        opacityValue.setText(opacity.getProgress() + "%");
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {
                sendBroadcast(new Intent("cn.yuchuang.floatcontrol.REFRESH").setPackage(getPackageName()));
            }
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                opacityValue.setText(progress + "%");
                if (fromUser) {
                    prefs.edit().putInt("opacity", progress).apply();
                }
            }
        });
        heading("防误触保护");
        text("展开时，靠近浮窗的外侧点击不会触发收起。实际保护区完全透明。", 14);
        SeekBar protection = new SeekBar(this);
        protection.setMax(80);
        protection.setProgress(Math.max(0, Math.min(80, prefs.getInt("outsideProtectionDp", 24))));
        TextView protectionValue = range("保护范围", "贴边的一侧不会扩展", protection);
        protectionValue.setText(protection.getProgress() + " dp");
        guardPreview = new GuardPreview(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(146));
        previewParams.setMargins(dp(4), dp(6), dp(4), dp(12));
        body.addView(guardPreview, previewParams);
        protection.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                protectionValue.setText(progress + " dp");
                guardPreview.setProtection(progress);
                if (fromUser) prefs.edit().putInt("outsideProtectionDp", progress).apply();
            }
        });
        heading("使用体验");
        FlatSwitch vibration = new FlatSwitch();
        vibration.setText("切换时震动");
        vibration.setChecked(prefs.getBoolean("vibration", true));
        vibration.setOnCheckedChangeListener((v, checked) ->
            prefs.edit().putBoolean("vibration", checked).apply());
        switchRow(vibration, "切换页面时给出触觉反馈");
        FlatSwitch keepAwake = new FlatSwitch();
        keepAwake.setText("悬浮窗运行时保持屏幕常亮");
        keepAwake.setChecked(prefs.getBoolean("keepScreenOn", false));
        keepAwake.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("keepScreenOn", checked).apply();
            sendBroadcast(new Intent("cn.yuchuang.floatcontrol.REFRESH").setPackage(getPackageName()));
        });
        switchRow(keepAwake, "停止悬浮窗后恢复系统息屏设置");
        body = old;
        activePage = oldPage;
    }

    private void buildContentPage(LinearLayout page) {
        activePage = body = page;
        pageLead(page, "内容配置", "快捷应用",
            "选择快捷应用，并调整悬浮窗中的显示顺序",
            0xff202b3d, Color.WHITE, 0xffc7d2e2);
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
        for (int i = 0; i < KEYS.length; i++) {
            final int index = i;
            appChoiceRow(KEYS[i], LABELS[i], index);
        }
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
        text("拖动顺序会同步到悬浮窗。中间的切换按钮固定在第三个位置。", 14);
        mediaOrderRows = new LinearLayout(this);
        mediaOrderRows.setOrientation(LinearLayout.VERTICAL);
        body.addView(mediaOrderRows);
        drawOrderRows(OverlayOrder.MEDIA, mediaOrderRows);
        heading("快捷应用顺序");
        text("调整沟通、导航、音乐和设置在悬浮窗中的位置。", 14);
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
            row.setPadding(dp(4), 0, dp(2), 0);
            list.addView(row, new LinearLayout.LayoutParams(-1, dp(60)));
            TextView number = new TextView(this);
            number.setText(String.format(java.util.Locale.ROOT, "%02d", position + 1));
            number.setTextSize(12);
            number.setTextColor(MUTED);
            row.addView(number, new LinearLayout.LayoutParams(dp(30), -2));
            if (center) {
                ImageView switcher = new ImageView(this);
                switcher.setImageResource(R.drawable.swap_vertical);
                switcher.setImageTintList(ColorStateList.valueOf(BLUE));
                switcher.setPadding(dp(7), dp(7), dp(7), dp(7));
                switcher.setBackground(round(0xffe9f1ff, 8));
                row.addView(switcher, new LinearLayout.LayoutParams(dp(36), dp(36)));
                TextView label = new TextView(this);
                label.setText("切换页面");
                label.setTextSize(15);
                label.setTextColor(MUTED);
                label.setPadding(dp(12), 0, 0, 0);
                row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            } else {
                String item = items[index];
                ImageView icon = new ImageView(this);
                orderIcon(icon, key, item);
                row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
                TextView label = new TextView(this);
                label.setText(orderLabel(key, item));
                label.setTextSize(15);
                label.setTextColor(INK);
                label.setPadding(dp(12), 0, dp(3), 0);
                row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                orderArrow(row, true, index > 0, () -> moveOrder(key, index, index - 1));
                orderArrow(row, false, index < 3, () -> moveOrder(key, index, index + 1));
            }
            if (position < 4) {
                View divider = new View(this);
                divider.setBackgroundColor(0xffeef1f4);
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
        button.setImageTintList(ColorStateList.valueOf(enabled ? BLUE : 0xffc3c7cc));
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setContentDescription(up ? "上移" : "下移");
        button.setEnabled(enabled);
        button.setBackground(touchBackground(SURFACE, 10));
        button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(dp(44), dp(48)));
    }

    private void moveOrder(String key, int from, int to) {
        String[] items = OverlayOrder.get(prefs, key);
        String swap = items[from];
        items[from] = items[to];
        items[to] = swap;
        OverlayOrder.save(prefs, key, items);
        drawOrderRows(key, OverlayOrder.MEDIA.equals(key) ? mediaOrderRows : appOrderRows);
        sendBroadcast(new Intent("cn.yuchuang.floatcontrol.REFRESH").setPackage(getPackageName()));
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
            int drawable = "cover".equals(item) ? android.R.drawable.ic_menu_gallery :
                "play".equals(item) ? R.drawable.media_play : R.drawable.media_skip;
            icon.setImageResource(drawable);
            icon.setImageTintList(ColorStateList.valueOf(INK));
            icon.setPadding(dp(5), dp(5), dp(5), dp(5));
            if ("previous".equals(item)) icon.setScaleX(-1f);
            return;
        }
        if ("settings".equals(item)) {
            icon.setImageResource(android.R.drawable.ic_menu_preferences);
            icon.setImageTintList(ColorStateList.valueOf(INK));
            icon.setPadding(dp(5), dp(5), dp(5), dp(5));
            return;
        }
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
        boolean ready = Settings.canDrawOverlays(this) && mediaGranted();
        FrameLayout lead = pageLead(page, "设备与诊断", ready ? "权限就绪" : "需要处理",
            "", 0xff202b3d, Color.WHITE, 0xffc7d2e2);
        settingsSummaryState = lead.findViewWithTag("lead-title");
        settingsSummaryDetail = lead.findViewWithTag("lead-detail");
        permissions = settingsSummaryDetail;
        permissions.setText("悬浮窗：" + (Settings.canDrawOverlays(this) ? "已授权" : "待授权") +
            "    ·    媒体：" + (mediaGranted() ? "已授权" : "待授权"));
        heading("系统权限");
        button("授予悬浮窗权限", () -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
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
        systemLogStatus = text("检查 Shizuku 状态中", 14);
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
        diagnosticStatus = text("读取日志占用中", 14);
        button("导出日志 ZIP", "包含锁存记录与近期运行事件", () -> {
            Diagnostics.event("export", "requested", "");
            Intent document = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            document.addCategory(Intent.CATEGORY_OPENABLE);
            document.setType("application/zip");
            document.putExtra(Intent.EXTRA_TITLE,
                "驭窗浮控日志-" + System.currentTimeMillis() + ".zip");
            startActivityForResult(document, EXPORT_LOGS);
        });
        text("默认仅记录本应用。启用 Shizuku 后可能包含其他应用和系统日志。导出前请注意隐私，总占用上限 500 MB。", 14);
        button("隐藏日志配置", "关闭后再次点击版本号 5 次可重新打开", () ->
            setDiagnosticVisible(false));
        diagnosticBlock.setVisibility(
            prefs.getBoolean("diagnosticVisible", false) ? View.VISIBLE : View.GONE);
        body = old;
        activePage = oldPage;
        versionView = overviewText("版本 1.9.16", 13, MUTED);
        versionView.setGravity(Gravity.CENTER);
        versionView.setPadding(0, dp(16), 0, dp(18));
        versionView.setContentDescription("版本 1.9.16，连续点击五次打开日志配置");
        versionView.setOnClickListener(v -> handleVersionTap());
        page.addView(versionView, overviewParams(0, 0, 0, 0));
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
        if (diagnosticBlock != null) {
            diagnosticBlock.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (!visible) Toast.makeText(this, "日志配置已隐藏", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onResume() {
        super.onResume();
        frameMonitor = FrameMonitor.attach(getWindow(), "main");
        frameMonitor.setContext(pageTitle.getText().toString());
        registerReceiver(overlayStatus,
            new IntentFilter(OverlayService.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED);
        updateOverview();
        Diagnostics.event("activity", "resumed", "MainActivity");
        Diagnostics.status(this::showDiagnosticStatus);
        SystemLogCollector.setStatusListener(value -> {
            if (systemLogStatus != null) systemLogStatus.setText(value);
        });
        if (permissions != null) permissions.setText("悬浮窗：" + (Settings.canDrawOverlays(this) ? "已授权" : "未授权") +
            "    媒体：" + (mediaGranted() ? "已授权" : "未授权"));
        for (String key : KEYS) {
            TextView view = pages != null && pages.length > 2 ? pages[2].findViewWithTag(key) : null;
            if (view != null) view.setText(selectedName(key));
        }
        if (appOrderRows != null) drawOrderRows(OverlayOrder.APPS, appOrderRows);
        startRequestedOverlay();
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
            controller.setSystemBarsAppearance(lightBars, lightBars);
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

    private String selectedName(String key) {
        String value = prefs.getString(key, "");
        if (value.isEmpty()) return "未选择";
        try {
            ComponentName component = ComponentName.unflattenFromString(value);
            return getPackageManager().getActivityInfo(component, 0).loadLabel(getPackageManager()).toString();
        } catch (Exception e) { return "应用不可用，请重新选择"; }
    }

    private void pick(int slot) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = getPackageManager().queryIntentActivities(query, 0);
        Collections.sort(found, new ResolveInfo.DisplayNameComparator(getPackageManager()));
        List<ResolveInfo> apps = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ResolveInfo info : found) {
            if (info.activityInfo.packageName.equals(getPackageName())) continue;
            apps.add(info);
            names.add(info.loadLabel(getPackageManager()).toString());
        }
        Dialog dialog = new Dialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(10), dp(18), dp(18));
        sheet.setBackground(round(SURFACE, 24));
        View handle = new View(this);
        handle.setBackground(round(0xffd7d9de, 3));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(18);
        sheet.addView(handle, handleParams);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), 0, dp(6), dp(12));
        sheet.addView(header);
        TextView title = new TextView(this);
        title.setText("选择" + LABELS[slot]);
        title.setTextSize(20);
        title.setTypeface(null, 1);
        title.setTextColor(INK);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(15);
        cancel.setTextColor(BLUE);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(touchBackground(SURFACE, 10));
        header.addView(cancel, new LinearLayout.LayoutParams(dp(54), dp(44)));
        cancel.setOnClickListener(view -> dialog.dismiss());
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(INK);
        search.setHintTextColor(MUTED);
        search.setHint("搜索应用");
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setBackground(round(0xfff2f3f5, 12));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(48));
        searchParams.bottomMargin = dp(12);
        sheet.addView(search, searchParams);
        List<Integer> filtered = new ArrayList<>();
        for (int i = 0; i < apps.size(); i++) filtered.add(i);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return filtered.size(); }
            @Override public Object getItem(int position) { return apps.get(filtered.get(position)); }
            @Override public long getItemId(int position) { return filtered.get(position); }
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                LinearLayout row;
                if (convertView instanceof LinearLayout) {
                    row = (LinearLayout) convertView;
                } else {
                    row = new LinearLayout(MainActivity.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(12), dp(5), dp(12), dp(5));
                    row.setBackground(touchBackground(SURFACE, 12));
                    ImageView icon = new ImageView(MainActivity.this);
                    row.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
                    LinearLayout labels = new LinearLayout(MainActivity.this);
                    labels.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, -2, 1);
                    labelsParams.leftMargin = dp(14);
                    row.addView(labels, labelsParams);
                    TextView name = new TextView(MainActivity.this);
                    name.setTextSize(15);
                    name.setTextColor(INK);
                    name.setSingleLine(true);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    labels.addView(name);
                    TextView packageName = new TextView(MainActivity.this);
                    packageName.setTextSize(12);
                    packageName.setTextColor(MUTED);
                    packageName.setSingleLine(true);
                    packageName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    labels.addView(packageName);
                }
                int index = filtered.get(position);
                ResolveInfo info = apps.get(index);
                ((ImageView) row.getChildAt(0)).setImageDrawable(info.loadIcon(getPackageManager()));
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
        TextView empty = new TextView(this);
        empty.setText("没有找到应用");
        empty.setTextSize(15);
        empty.setTextColor(MUTED);
        empty.setGravity(Gravity.CENTER);
        results.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                String term = value.toString().trim().toLowerCase(Locale.ROOT);
                filtered.clear();
                for (int i = 0; i < apps.size(); i++) {
                    if (names.get(i).toLowerCase(Locale.ROOT).contains(term)
                        || apps.get(i).activityInfo.packageName.toLowerCase(Locale.ROOT).contains(term)) {
                        filtered.add(i);
                    }
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            ResolveInfo chosen = apps.get(filtered.get(position));
            String component = new ComponentName(chosen.activityInfo.packageName, chosen.activityInfo.name).flattenToString();
            prefs.edit().putString(KEYS[slot], component).apply();
            TextView label = pages != null && pages.length > 2 ? pages[2].findViewWithTag(KEYS[slot]) : null;
            if (label != null) label.setText(selectedName(KEYS[slot]));
            ImageView selectedIcon = pages != null && pages.length > 2
                ? pages[2].findViewWithTag("icon:" + KEYS[slot]) : null;
            if (selectedIcon != null) orderIcon(selectedIcon, OverlayOrder.APPS, KEYS[slot]);
            if (appOrderRows != null) drawOrderRows(OverlayOrder.APPS, appOrderRows);
            dialog.dismiss();
            sendBroadcast(new Intent("cn.yuchuang.floatcontrol.REFRESH").setPackage(getPackageName()));
        });
        showBottomSheet(dialog, sheet);
    }

    private FrameLayout pageLead(LinearLayout page, String eyebrow, String title,
                                 String detail, int background, int titleColor,
                                 int detailColor) {
        FrameLayout card = new FrameLayout(this);
        card.setPadding(dp(20), dp(18), dp(18), dp(17));
        card.setBackground(round(background, 22));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, dp(LEAD_HEIGHT_DP));
        cardParams.bottomMargin = dp(18);
        page.addView(card, cardParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        card.addView(labels, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.LEFT));

        TextView eyebrowView = overviewText(eyebrow, 12, detailColor);
        eyebrowView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labels.addView(eyebrowView);
        TextView titleView = overviewText(title, 27, titleColor);
        titleView.setTypeface(null, 1);
        titleView.setTag("lead-title");
        titleView.setPadding(0, dp(8), 0, dp(4));
        labels.addView(titleView);
        TextView detailView = overviewText(detail, 14, detailColor);
        detailView.setTag("lead-detail");
        detailView.setLineSpacing(dp(2), 1f);
        labels.addView(detailView);

        return card;
    }

    private void appChoiceRow(String key, String title, int slot) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(7), dp(5), dp(7));
        row.setMinimumHeight(dp(70));
        row.setBackground(touchBackground(SURFACE, 14));
        row.setContentDescription(title + "，" + selectedName(key));
        row.setOnClickListener(v -> pick(slot));

        ImageView icon = new ImageView(this);
        icon.setTag("icon:" + key);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setBackground(round(0xffedf4ff, 14));
        orderIcon(icon, OverlayOrder.APPS, key);
        row.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(13), 0, dp(6), 0);
        TextView name = overviewText(title, 16, INK);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labels.addView(name);
        TextView selected = overviewText(selectedName(key), 13, MUTED);
        selected.setTag(key);
        selected.setSingleLine(true);
        selected.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selected.setPadding(0, dp(3), 0, 0);
        labels.addView(selected);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.chevron_right);
        arrow.setImageTintList(ColorStateList.valueOf(0xff9aa2ad));
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(42)));
        body.addView(row, new LinearLayout.LayoutParams(-1, dp(70)));
    }

    private void heading(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(13);
        view.setTextColor(SECTION);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(dp(4), dp(26), 0, dp(14));
        activePage.addView(view, titleParams);
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(16), dp(7), dp(16), dp(7));
        group.setBackground(round(SURFACE, 22));
        activePage.addView(group, new LinearLayout.LayoutParams(-1, -2));
        body = group;
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(size <= 14 ? MUTED : INK);
        view.setTextSize(size);
        view.setPadding(dp(8), dp(12), dp(8), dp(12));
        view.setLineSpacing(dp(2), 1f);
        body.addView(view);
        return view;
    }
    private TextView textIn(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(MUTED);
        view.setTextSize(size);
        view.setPadding(0, dp(5), 0, dp(5));
        parent.addView(view);
        return view;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private GradientDrawable selectedNavigationBackground() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xfff2f3f6, 0xffe3e5e9, 0xffeff0f3});
        drawable.setCornerRadius(dp(32));
        drawable.setStroke(dp(1), 0xaaffffff);
        return drawable;
    }
    private RippleDrawable touchBackground(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x18347ff0),
            round(color, radius), round(SURFACE, radius));
    }
    private TextView range(String title, String subtitle, SeekBar bar) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(8), dp(14), dp(8), dp(2));
        body.addView(container, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        container.addView(header);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(INK);
        name.setTextSize(16);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labels.addView(name);
        TextView detail = new TextView(this);
        detail.setText(subtitle);
        detail.setTextColor(MUTED);
        detail.setTextSize(13);
        detail.setPadding(0, dp(3), 0, 0);
        labels.addView(detail);
        header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = new TextView(this);
        value.setTextColor(BLUE);
        value.setTextSize(14);
        value.setTypeface(null, 1);
        value.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(dp(66), -2);
        valueParams.leftMargin = dp(6);
        header.addView(value, valueParams);
        bar.setProgressTintList(ColorStateList.valueOf(BLUE));
        bar.setThumbTintList(ColorStateList.valueOf(BLUE));
        bar.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(44));
        barParams.topMargin = dp(4);
        container.addView(bar, barParams);
        return value;
    }
    private void choice(String title, String subtitle, String[] options,
                        int selected, IntConsumer onSelected) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(8), dp(12), dp(8), dp(5));
        body.addView(container, new LinearLayout.LayoutParams(-1, -2));
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(16);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setTextColor(INK);
        container.addView(name);
        TextView detail = new TextView(this);
        detail.setText(subtitle);
        detail.setTextSize(13);
        detail.setTextColor(MUTED);
        detail.setPadding(0, dp(3), 0, 0);
        container.addView(detail);
        int[] current = {Math.max(0, Math.min(options.length - 1, selected))};
        LinearLayout valueRow = new LinearLayout(this);
        valueRow.setGravity(Gravity.CENTER_VERTICAL);
        valueRow.setPadding(dp(12), 0, dp(5), 0);
        valueRow.setBackground(touchBackground(0xfff5f6f8, 12));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(48));
        rowParams.topMargin = dp(9);
        container.addView(valueRow, rowParams);
        TextView value = new TextView(this);
        value.setText(options[current[0]]);
        value.setTextSize(15);
        value.setTextColor(INK);
        valueRow.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.chevron_right);
        arrow.setRotation(90);
        valueRow.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(20)));
        valueRow.setContentDescription(title + "，" + options[current[0]]);
        valueRow.setOnClickListener(view -> showChoiceSheet(title, options, current[0], position -> {
            current[0] = position;
            value.setText(options[position]);
            valueRow.setContentDescription(title + "，" + options[position]);
            onSelected.accept(position);
        }));
    }
    private void showChoiceSheet(String title, String[] options, int selected, IntConsumer onSelected) {
        Dialog dialog = new Dialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(20), dp(20), dp(22));
        sheet.setBackground(round(SURFACE, 24));
        TextView heading = new TextView(this);
        heading.setText("选择" + title);
        heading.setTextSize(19);
        heading.setTypeface(null, 1);
        heading.setTextColor(INK);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.setMargins(dp(8), dp(2), 0, dp(14));
        sheet.addView(heading, headingParams);
        for (int i = 0; i < options.length; i++) {
            final int position = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setBackground(touchBackground(i == selected ? 0xffedf4ff : SURFACE, 12));
            sheet.addView(row, new LinearLayout.LayoutParams(-1, dp(58)));
            TextView label = new TextView(this);
            label.setText(options[i]);
            label.setTextColor(i == selected ? BLUE : INK);
            label.setTextSize(16);
            label.setTypeface(null, i == selected ? 1 : 0);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            if (i == selected) {
                ImageView check = new ImageView(this);
                check.setImageResource(R.drawable.choice_check);
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
            params.dimAmount = 0.32f;
            window.setAttributes(params);
        }
    }
    private void switchRow(FlatSwitch toggle) {
        switchRow(toggle, null);
    }
    private void switchRow(FlatSwitch toggle, String subtitle) {
        String title = toggle.getText().toString();
        toggle.setText(null);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(16);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setTextColor(INK);
        labels.addView(name);
        if (subtitle != null) {
            TextView detail = new TextView(this);
            detail.setText(subtitle);
            detail.setTextSize(13);
            detail.setTextColor(MUTED);
            detail.setPadding(0, dp(3), 0, 0);
            labels.addView(detail);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(toggle, new LinearLayout.LayoutParams(dp(56), dp(50)));
        row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    private class FlatSwitch extends CompoundButton {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        FlatSwitch() {
            super(MainActivity.this);
            setButtonDrawable((android.graphics.drawable.Drawable) null);
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
            paint.setColor(isChecked() ? BLUE : 0xffe2e3e5);
            canvas.drawRoundRect(left, top, left + trackWidth, top + trackHeight,
                trackHeight / 2, trackHeight / 2, paint);
            paint.setColor(SURFACE);
            float cx = isChecked() ? left + trackWidth - dp(13) : left + dp(13);
            canvas.drawCircle(cx, top + trackHeight / 2, dp(10), paint);
        }

        @Override public CharSequence getAccessibilityClassName() {
            return android.widget.Switch.class.getName();
        }
    }
    private class SquareStatusCard extends LinearLayout {
        SquareStatusCard() {
            super(MainActivity.this);
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            if (width <= 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                width = getMeasuredWidth();
            }
            if (width <= 0) return;
            // Weighted rows need the final square height during their first measure.
            int squareSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
            super.onMeasure(widthMeasureSpec, squareSpec);
            setMeasuredDimension(width, width);
        }
    }
    private class GuardPreview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float phase;
        private int protection = Math.max(0, Math.min(80, prefs.getInt("outsideProtectionDp", 24)));
        GuardPreview(android.content.Context context) {
            super(context);
        }
        void setProtection(int value) {
            protection = value;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cy = getHeight() / 2f;
            float width = dp(48), height = dp(76);
            float margin = dp(protection * .45f);
            float right = getWidth() - dp(18);
            float left = right - width;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xfff2f4f7);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(14), dp(14), paint);
            paint.setColor(0xffdceafa);
            canvas.drawRoundRect(left - margin, cy - height / 2 - margin,
                right, cy + height / 2 + margin, dp(14), dp(14), paint);
            paint.setColor(0xffffffff);
            canvas.drawRoundRect(left, cy - height / 2, right, cy + height / 2, dp(14), dp(14), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(BLUE);
            paint.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(4)}, 0));
            canvas.drawRoundRect(left - margin, cy - height / 2 - margin,
                right, cy + height / 2 + margin, dp(14), dp(14), paint);
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(BLUE);
            canvas.drawCircle(left + width / 2, cy, dp(5), paint);
            float t = (float)(.5 + .5 * Math.sin(phase));
            float touchX = left - dp(7) - t * (margin + dp(13));
            boolean ignored = touchX >= left - margin;
            paint.setColor(ignored ? BLUE : MUTED);
            canvas.drawCircle(touchX, cy, dp(5), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            canvas.drawCircle(touchX, cy, dp(8), paint);
            paint.setStyle(Paint.Style.FILL);
            phase += 0.045f;
            if (isShown()) postInvalidateDelayed(32);
        }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            postInvalidate();
        }
    }
    private void button(String name, Runnable action) {
        button(name, null, action);
    }
    private TextView button(String name, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(10), dp(8), dp(10));
        row.setContentDescription(name);
        row.setBackground(touchBackground(SURFACE, 12));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(16);
        label.setTypeface(null, 1);
        label.setTextColor(INK);
        label.setGravity(Gravity.CENTER_VERTICAL);
        labels.addView(label);
        TextView detail = new TextView(this);
        detail.setText(subtitle);
        detail.setTextSize(13);
        detail.setTextColor(MUTED);
        detail.setPadding(0, dp(3), 0, 0);
        detail.setVisibility(subtitle == null ? View.GONE : View.VISIBLE);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.chevron_right);
        arrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        arrowParams.leftMargin = dp(8);
        row.addView(arrow, arrowParams);
        row.setOnClickListener(v -> action.run());
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        row.setMinimumHeight(dp(58));
        return detail;
    }
    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
}
