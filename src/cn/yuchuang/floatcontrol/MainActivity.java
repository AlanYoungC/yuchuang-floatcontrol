package cn.yuchuang.floatcontrol;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Switch;
import android.widget.SeekBar;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private LinearLayout body;
    private TextView permissions;
    private boolean startRequested;
    private static final String[] KEYS = {"message", "navigation", "music"};
    private static final String[] LABELS = {"沟通应用", "导航应用", "音乐应用"};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startRequested = getIntent().getBooleanExtra("startOverlay", false);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xfff6f9f9);
        body = new LinearLayout(this);
        body.setPadding(dp(22), dp(25), dp(22), dp(35));
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        setContentView(scroll);
        title("驭窗浮控", 26);
        text("独立悬浮快捷栏", 14);
        heading("权限");
        permissions = text("", 15);
        button("授予悬浮窗权限", () -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        button("授予通知使用权（媒体切歌）", () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        heading("悬浮窗");
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
        button("启动悬浮窗", () -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                return;
            }
            startForegroundService(new Intent(this, OverlayService.class).setAction("start"));
            Toast.makeText(this, "悬浮窗已启动", Toast.LENGTH_SHORT).show();
        });
        button("停止悬浮窗", () -> stopService(new Intent(this, OverlayService.class)));
        heading("悬浮窗大小");
        TextView sizeValue = text("", 15);
        SeekBar size = new SeekBar(this);
        size.setMin(80);
        size.setMax(160);
        size.setProgress(Math.max(80, Math.min(160, prefs.getInt("sizePercent", 100))));
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
        body.addView(size);
        heading("展开后显示");
        Spinner start = new Spinner(this);
        start.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"控件", "APP", "记忆收起前状态"}));
        start.setSelection(prefs.getInt("openMode", 0));
        start.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putInt("openMode", position).apply();
            }
        });
        body.addView(start);
        heading("自动收起时间");
        TextView duration = text("", 15);
        SeekBar seek = new SeekBar(this);
        seek.setMax(58);
        seek.setProgress(Math.max(0, Math.min(58, prefs.getInt("timeout", 12) - 2)));
        duration.setText((seek.getProgress() + 2) + " 秒");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                duration.setText((progress + 2) + " 秒");
                if (fromUser) prefs.edit().putInt("timeout", progress + 2).apply();
            }
        });
        body.addView(seek);
        Switch vibration = new Switch(this);
        vibration.setText("切换时震动");
        vibration.setTextSize(16);
        vibration.setChecked(prefs.getBoolean("vibration", true));
        vibration.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("vibration", checked).apply());
        body.addView(vibration);
        Switch keepAwake = new Switch(this);
        keepAwake.setText("悬浮窗运行时保持屏幕常亮");
        keepAwake.setTextSize(16);
        keepAwake.setChecked(prefs.getBoolean("keepScreenOn", false));
        keepAwake.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("keepScreenOn", checked).apply();
            sendBroadcast(new Intent("cn.yuchuang.floatcontrol.REFRESH").setPackage(getPackageName()));
        });
        body.addView(keepAwake);
        heading("浮窗背景不透明度");
        TextView opacityValue = text("", 15);
        SeekBar opacity = new SeekBar(this);
        opacity.setMax(80);
        opacity.setProgress(prefs.getInt("opacity", 24));
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
        body.addView(opacity);
        heading("快捷应用");
        for (int i = 0; i < KEYS.length; i++) {
            final int index = i;
            button(LABELS[i] + "：选择应用", () -> pick(index));
            TextView chosen = text(selectedName(KEYS[i]), 14);
            chosen.setTag(KEYS[i]);
        }
        Switch primary = new Switch(this);
        primary.setText("沟通应用使用主实例（不弹双开选择）");
        primary.setTextSize(16);
        primary.setChecked(prefs.getBoolean("primaryMessage", true));
        primary.setOnCheckedChangeListener((v, checked) ->
            prefs.edit().putBoolean("primaryMessage", checked).apply());
        body.addView(primary);
        text("拖动悬浮图标调整位置；展开后点击中间按钮切换状态。媒体封面来自当前播放应用。", 13);
    }

    @Override protected void onResume() {
        super.onResume();
        if (permissions != null) permissions.setText("悬浮窗：" + (Settings.canDrawOverlays(this) ? "已授权" : "未授权") +
            "    媒体：" + (mediaGranted() ? "已授权" : "未授权"));
        for (String key : KEYS) {
            TextView view = body.findViewWithTag(key);
            if (view != null) view.setText(selectedName(key));
        }
        startRequestedOverlay();
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
        android.widget.ListView list = new android.widget.ListView(this);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        list.setAdapter(adapter);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).setTitle(LABELS[slot]).setView(list)
            .setNegativeButton("取消", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            ResolveInfo chosen = apps.get(position);
            String component = new ComponentName(chosen.activityInfo.packageName, chosen.activityInfo.name).flattenToString();
            prefs.edit().putString(KEYS[slot], component).apply();
            TextView label = body.findViewWithTag(KEYS[slot]);
            if (label != null) label.setText(selectedName(KEYS[slot]));
            dialog.dismiss();
            sendBroadcast(new Intent("cn.yuchuang.floatcontrol.REFRESH").setPackage(getPackageName()));
        });
        dialog.show();
    }

    private void heading(String value) {
        TextView view = title(value, 18);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.topMargin = dp(25);
        view.setLayoutParams(params);
    }
    private TextView title(String value, int size) {
        TextView view = text(value, size);
        view.setTextColor(0xff17323a);
        view.setTypeface(null, 1);
        return view;
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(0xff567078);
        view.setTextSize(size);
        view.setPadding(0, dp(6), 0, dp(6));
        body.addView(view);
        return view;
    }
    private void button(String name, Runnable action) {
        Button button = new Button(this);
        button.setText(name);
        button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        body.addView(button, new LinearLayout.LayoutParams(-1, dp(52)));
    }
    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
}
