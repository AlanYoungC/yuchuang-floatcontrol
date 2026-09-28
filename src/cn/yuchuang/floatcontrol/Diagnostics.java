package cn.yuchuang.floatcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONException;
import org.json.JSONObject;

final class Diagnostics {
    private static final String TAG = "YuChuangDiagnostics";
    private static final String LOCK_KEY = "diagnosticsLocked";
    private static final long WINDOW_MS = 15 * 60 * 1000L;
    private static final long MAX_TOTAL = 500L * 1024 * 1024;
    private static final long MAX_RECENT = 120L * 1024 * 1024;
    private static final long MAX_CAPTURE = 320L * 1024 * 1024;
    private static final long MAX_SEGMENT = 2L * 1024 * 1024;
    private static final int MAX_LINE = 8192;
    private static volatile Diagnostics instance;

    interface Result<T> { void accept(T value); }

    static final class Status {
        final long bytes;
        final int captures;
        final boolean locked;
        Status(long bytes, int captures, boolean locked) {
            this.bytes = bytes;
            this.captures = captures;
            this.locked = locked;
        }
    }

    private interface Job { void run() throws Exception; }

    static synchronized void init(Context context) {
        if (instance != null) return;
        Diagnostics diagnostics = new Diagnostics(context.getApplicationContext());
        instance = diagnostics;
        diagnostics.worker.start();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            diagnostics.writeCrash(thread, error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
        event("process", "created", "SDK=" + Build.VERSION.SDK_INT + " device=" +
            Build.MANUFACTURER + "/" + Build.MODEL + " pid=" + Process.myPid());
    }

    static void event(String category, String action, String detail) {
        Diagnostics diagnostics = instance;
        if (diagnostics != null) {
            String value = line(category, action, detail, null);
            diagnostics.enqueue(() -> diagnostics.record(value));
        }
    }

    static void error(String category, String action, Throwable throwable) {
        Diagnostics diagnostics = instance;
        if (diagnostics != null) {
            String value = line(category, action, "", throwable);
            diagnostics.enqueue(() -> diagnostics.record(value));
        }
    }

    static void systemLine(String raw) {
        Diagnostics diagnostics = instance;
        if (diagnostics == null || raw == null) return;
        String valueWithTimestamp = raw.trim();
        int space = valueWithTimestamp.indexOf(' ');
        if (space < 10 || space > 20) return;
        long timestamp;
        try {
            timestamp = (long) (Double.parseDouble(
                valueWithTimestamp.substring(0, space)) * 1000);
        } catch (NumberFormatException ignored) {
            return;
        }
        long now = System.currentTimeMillis();
        if (timestamp < now - WINDOW_MS || timestamp > now + 60_000) return;
        String value = lineAt(timestamp, "logcat", "line", raw, null);
        diagnostics.enqueue(() -> diagnostics.record(value));
    }

    static boolean isLocked(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean(LOCK_KEY, false);
    }

    static void setLocked(Context context, boolean enabled) {
        init(context);
        Diagnostics diagnostics = instance;
        diagnostics.prefs.edit().putBoolean(LOCK_KEY, enabled).apply();
        long clickedAt = System.currentTimeMillis();
        diagnostics.enqueue(() -> {
            if (enabled && !diagnostics.locked) diagnostics.startCapture(clickedAt);
            else if (!enabled && diagnostics.locked) {
                diagnostics.record(line("capture", "stopped", "user", null));
                diagnostics.locked = false;
                diagnostics.activeCapture = null;
            }
        });
    }

    static void status(Result<Status> callback) {
        Diagnostics diagnostics = instance;
        if (diagnostics == null) return;
        diagnostics.enqueue(() -> {
            File[] files = diagnostics.files();
            long bytes = 0;
            int captures = 0;
            for (File file : files) {
                bytes += file.length();
                if (file.getName().startsWith("capture-")) captures++;
            }
            Status result = new Status(bytes, captures, diagnostics.locked);
            diagnostics.main.post(() -> callback.accept(result));
        });
    }

    static void export(Context context, Uri uri, Result<Exception> callback) {
        init(context);
        Diagnostics diagnostics = instance;
        Thread thread = new Thread(() -> {
            Exception failure = null;
            try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IOException("Cannot open export destination");
                try (ZipOutputStream zip = new ZipOutputStream(output)) {
                    zip.putNextEntry(new ZipEntry("README.txt"));
                    zip.write(("YuChuang floating control diagnostics\n" +
                        "App events are always recorded. When enabled and authorized, Shizuku logcat may include other apps and system data.\n" +
                        "recent- files contain up to 15 minutes of rolling events; capture- files are locked sessions.\n" +
                        "Storage limit: 500 MiB. Oldest captures can be evicted when full.\n")
                        .getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                    for (File file : diagnostics.files()) {
                        if (!file.isFile()) continue;
                        long remaining = file.length();
                        try (FileInputStream input = new FileInputStream(file)) {
                            zip.putNextEntry(new ZipEntry(file.getName()));
                            byte[] buffer = new byte[64 * 1024];
                            while (remaining > 0) {
                                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                                if (count < 0) break;
                                zip.write(buffer, 0, count);
                                remaining -= count;
                            }
                            zip.closeEntry();
                        } catch (IOException e) {
                            Log.w(TAG, "Skipped disappearing log: " + file.getName(), e);
                        }
                    }
                }
            } catch (Exception e) {
                failure = e;
                error("export", "failed", e);
            }
            Exception result = failure;
            diagnostics.main.post(() -> callback.accept(result));
        }, "YuChuangExport");
        thread.start();
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayBlockingQueue<Job> queue = new ArrayBlockingQueue<>(4096);
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicLong mainAck = new AtomicLong(SystemClock.elapsedRealtime());
    private final Thread worker;
    private File recent;
    private File activeCapture;
    private long recentMinute = -1;
    private long lastPrune;
    private long lastMemory;
    private long heartbeatSent;
    private boolean stalled;
    private boolean locked;
    private boolean captureFull;

    private Diagnostics(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        dir = new File(context.getFilesDir(), "diagnostics");
        worker = new Thread(this::loop, "YuChuangLogWriter");
    }

    private void enqueue(Job job) {
        if (!queue.offer(job)) dropped.incrementAndGet();
    }

    private void loop() {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Cannot create diagnostics directory");
            return;
        }
        try {
            prune();
            if (prefs.getBoolean(LOCK_KEY, false)) startCapture(System.currentTimeMillis());
            File crash = new File(dir, "fatal.log");
            if (crash.exists()) {
                byte[] data = new byte[(int) Math.min(crash.length(), 64 * 1024)];
                try (FileInputStream input = new FileInputStream(crash)) {
                    int count = input.read(data);
                    if (count > 0) record(line("process", "previous_crash",
                        new String(data, 0, count, StandardCharsets.UTF_8), null));
                }
                if (!crash.delete()) Log.w(TAG, "Could not clear imported fatal log");
            }
            while (true) {
                Job job = queue.poll(2, TimeUnit.SECONDS);
                if (job != null) {
                    try { job.run(); }
                    catch (Exception e) { Log.e(TAG, "Diagnostics write failed", e); }
                }
                int lost = dropped.getAndSet(0);
                if (lost > 0) record(line("logger", "queue_overflow", "dropped=" + lost, null));
                long now = SystemClock.elapsedRealtime();
                if (now - lastMemory >= 60_000) {
                    Runtime runtime = Runtime.getRuntime();
                    record(line("health", "memory", "heapUsed=" +
                        (runtime.totalMemory() - runtime.freeMemory()) +
                        " heapMax=" + runtime.maxMemory() +
                        " threads=" + Thread.getAllStackTraces().size(), null));
                    lastMemory = now;
                }
                if (heartbeatSent != 0 && mainAck.get() < heartbeatSent) {
                    if (now - heartbeatSent > 5_000 && !stalled) {
                        record(line("health", "main_thread_delayed",
                            "delayMs=" + (now - heartbeatSent), null));
                        stalled = true;
                    }
                } else {
                    if (stalled) record(line("health", "main_thread_resumed",
                        "delayMs=" + (mainAck.get() - heartbeatSent), null));
                    stalled = false;
                    if (now - heartbeatSent >= 10_000) {
                        heartbeatSent = now;
                        main.post(() -> mainAck.set(SystemClock.elapsedRealtime()));
                    }
                }
                if (now - lastPrune >= 30_000) prune();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Diagnostics worker stopped", e);
        }
    }

    private void record(String line) throws IOException {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (recent == null || minute != recentMinute || recent.length() >= MAX_SEGMENT) {
            recent = new File(dir, "recent-" + now + "-" + SystemClock.elapsedRealtime() + ".jsonl");
            recentMinute = minute;
        }
        append(recent, line);
        if (locked && activeCapture != null && !captureFull) {
            long length = activeCapture.length();
            if (length + line.getBytes(StandardCharsets.UTF_8).length <= MAX_CAPTURE - MAX_LINE) {
                append(activeCapture, line);
            } else {
                append(activeCapture, line("capture", "size_limit",
                    "Session reached 320 MiB; recent events still rotate.", null));
                captureFull = true;
            }
        }
        if (recent.length() >= MAX_SEGMENT || now - lastPrune >= 30_000) prune();
    }

    private void startCapture(long clickedAt) throws IOException {
        locked = true;
        captureFull = false;
        activeCapture = new File(dir, "capture-" + clickedAt + "-" +
            SystemClock.elapsedRealtime() + ".jsonl");
        append(activeCapture, line("capture", "started",
            "Lookback=15min; only events recorded while this app was running.", null));
        long cutoff = clickedAt - WINDOW_MS;
        for (File file : matching("recent-")) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String value;
                while ((value = reader.readLine()) != null) {
                    try {
                        long timestamp = new JSONObject(value).getLong("time");
                        if (timestamp >= cutoff && timestamp <= clickedAt &&
                            activeCapture.length() + value.getBytes(StandardCharsets.UTF_8).length
                                < MAX_CAPTURE - MAX_LINE)
                            append(activeCapture, value);
                    } catch (JSONException ignored) {}
                }
            }
        }
        record(line("capture", "lookback_complete", "since=" + cutoff, null));
        prune();
    }

    private void prune() {
        lastPrune = SystemClock.elapsedRealtime();
        long cutoff = System.currentTimeMillis() - WINDOW_MS - 60_000;
        List<File> recentFiles = matching("recent-");
        long recentBytes = 0;
        for (File file : recentFiles) {
            if (!file.equals(recent) && file.lastModified() < cutoff) {
                if (!file.delete()) Log.w(TAG, "Could not prune " + file.getName());
            } else recentBytes += file.length();
        }
        for (File file : matching("recent-")) {
            if (recentBytes <= MAX_RECENT) break;
            if (!file.equals(recent)) {
                long length = file.length();
                if (file.delete()) recentBytes -= length;
            }
        }
        List<File> captures = matching("capture-");
        long total = 0;
        for (File file : files()) total += file.length();
        for (File file : captures) {
            if (total <= MAX_TOTAL) break;
            if (!file.equals(activeCapture)) {
                long length = file.length();
                if (file.delete()) total -= length;
            }
        }
    }

    private List<File> matching(String prefix) {
        List<File> result = new ArrayList<>();
        for (File file : files()) if (file.getName().startsWith(prefix)) result.add(file);
        result.sort(Comparator.comparing(File::getName));
        return result;
    }

    private File[] files() {
        File[] files = dir.listFiles();
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    private static void append(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write((value + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String line(String category, String action, String detail, Throwable error) {
        return lineAt(System.currentTimeMillis(), category, action, detail, error);
    }

    private static String lineAt(long timestamp, String category, String action, String detail, Throwable error) {
        try {
            JSONObject record = new JSONObject();
            record.put("time", timestamp);
            record.put("elapsed", SystemClock.elapsedRealtime());
            record.put("pid", Process.myPid());
            record.put("thread", Thread.currentThread().getName());
            record.put("category", category);
            record.put("action", action);
            if (detail != null && !detail.isEmpty())
                record.put("detail", truncate(detail, 4000));
            if (error != null) record.put("error", truncate(Log.getStackTraceString(error), 4000));
            return record.toString();
        } catch (JSONException e) {
            return "{\"category\":\"logger\",\"action\":\"encoding_error\"}";
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private void writeCrash(Thread thread, Throwable error) {
        try {
            if (!dir.exists()) dir.mkdirs();
            File crash = new File(dir, "fatal.log");
            if (crash.length() > 256 * 1024 && !crash.delete()) return;
            append(crash, line("process", "uncaught_exception",
                "thread=" + thread.getName(), error));
        } catch (Exception e) {
            Log.e(TAG, "Could not preserve crash", e);
        }
    }
}
