package cn.yuchuang.floatcontrol;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import rikka.shizuku.Shizuku;

final class SystemLogCollector {
    private static final String KEY = "systemLoggingEnabled";
    private static final int PERMISSION_REQUEST = 914;
    private static SystemLogCollector instance;

    static synchronized void init(Context context) {
        if (instance != null) return;
        instance = new SystemLogCollector(context.getApplicationContext());
        instance.register();
    }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean(KEY, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        init(context);
        instance.prefs.edit().putBoolean(KEY, enabled).apply();
        if (enabled) instance.connect(true);
        else instance.disconnect("已关闭", true);
    }

    static void setStatusListener(Consumer<String> listener) {
        if (instance == null) return;
        instance.statusListener = listener;
        if (listener != null) listener.accept(instance.status);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Shizuku.UserServiceArgs args;
    private volatile ParcelFileDescriptor stream;
    private volatile ILogBridge bridge;
    private boolean bound;
    private int generation;
    private String status = "未启用";
    private Consumer<String> statusListener;

    private final Shizuku.OnBinderReceivedListener received;
    private final Shizuku.OnBinderDeadListener dead;
    private final Shizuku.OnRequestPermissionResultListener permission;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            main.post(() -> {
                if (!isEnabled(context) || !bound) return;
                bridge = ILogBridge.Stub.asInterface(binder);
                startReading();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            main.post(() -> {
                disconnect("采集中断，尝试重连", false);
                int token = generation;
                main.postDelayed(() -> {
                    if (token == generation && isEnabled(context)) connect(false);
                }, 1500);
            });
        }
    };

    private SystemLogCollector(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        args = new Shizuku.UserServiceArgs(new ComponentName(context, ShellLogBridge.class))
            .daemon(false).processNameSuffix("log_bridge").tag("log_bridge")
            .version(1).debuggable(false);
        received = () -> main.post(() -> {
            if (isEnabled(this.context)) connect(false);
        });
        dead = () -> main.post(() -> disconnect("Shizuku 已断开，等待重连", false));
        permission = (request, result) -> {
            if (request != PERMISSION_REQUEST) return;
            main.post(() -> {
                if (result == PackageManager.PERMISSION_GRANTED) connect(false);
                else update("Shizuku 授权被拒");
            });
        };
    }

    private void register() {
        try {
            Shizuku.addBinderReceivedListenerSticky(received);
            Shizuku.addBinderDeadListener(dead);
            Shizuku.addRequestPermissionResultListener(permission);
            if (isEnabled(context)) connect(false);
        } catch (Throwable error) {
            Diagnostics.error("shizuku", "registration_failed", error);
            update("Shizuku API 不可用");
        }
    }

    private void connect(boolean requestPermission) {
        if (!isEnabled(context) || bound) return;
        try {
            if (!Shizuku.pingBinder()) {
                update("请先启动 Shizuku，连接后自动采集");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                update("等待 Shizuku 授权");
                if (requestPermission) Shizuku.requestPermission(PERMISSION_REQUEST);
                return;
            }
            bound = true;
            update("正在连接 Shizuku");
            Shizuku.bindUserService(args, connection);
        } catch (Throwable error) {
            bound = false;
            Diagnostics.error("shizuku", "bind_failed", error);
            update("Shizuku 连接失败");
        }
    }

    private void startReading() {
        int token = ++generation;
        ILogBridge current = bridge;
        update("正在回溯系统日志");
        new Thread(() -> {
            try (ParcelFileDescriptor pipe =
                     current.openLogcat(System.currentTimeMillis() - 15 * 60 * 1000L);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                     new ParcelFileDescriptor.AutoCloseInputStream(pipe), StandardCharsets.UTF_8))) {
                if (token != generation) return;
                stream = pipe;
                main.post(() -> { if (token == generation) update("正在采集系统日志"); });
                String line;
                while (token == generation && (line = reader.readLine()) != null)
                    Diagnostics.systemLine(line);
                if (token == generation) main.post(() -> update("logcat 已停止，请重新开启系统日志"));
            } catch (Exception error) {
                if (token == generation) {
                    Diagnostics.error("shizuku", "logcat_failed", error);
                    main.post(() -> update("系统日志采集失败，请检查 Shizuku"));
                }
            } finally {
                if (token == generation) stream = null;
            }
        }, "YuChuangSystemLogReader").start();
    }

    private void disconnect(String reason, boolean unbind) {
        generation++;
        ParcelFileDescriptor oldStream = stream;
        stream = null;
        if (oldStream != null) try { oldStream.close(); } catch (Exception ignored) {}
        ILogBridge oldBridge = bridge;
        bridge = null;
        if (oldBridge != null) new Thread(() -> {
            try { oldBridge.stop(); } catch (Exception ignored) {}
        }, "YuChuangLogStop").start();
        if (bound) {
            bound = false;
            try { Shizuku.unbindUserService(args, connection, unbind); }
            catch (Throwable error) { Diagnostics.error("shizuku", "unbind_failed", error); }
        }
        update(reason);
    }

    private void update(String value) {
        status = value;
        Diagnostics.event("shizuku", "status", value);
        if (statusListener != null) statusListener.accept(value);
    }
}
