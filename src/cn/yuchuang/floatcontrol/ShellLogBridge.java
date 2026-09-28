package cn.yuchuang.floatcontrol;

import android.os.ParcelFileDescriptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ShellLogBridge extends ILogBridge.Stub {
    private Process logcat;
    private ParcelFileDescriptor writeEnd;

    public ShellLogBridge() {}

    @Override public synchronized ParcelFileDescriptor openLogcat(long sinceMillis) {
        stop();
        try {
            String since = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(sinceMillis));
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            try {
                logcat = new ProcessBuilder("/system/bin/logcat", "-v", "epoch",
                    "-b", "main", "-b", "system", "-b", "events", "-b", "crash",
                    "-T", since).redirectErrorStream(true).start();
            } catch (Exception e) {
                pipe[0].close();
                pipe[1].close();
                throw e;
            }
            writeEnd = pipe[1];
            Process current = logcat;
            ParcelFileDescriptor writer = writeEnd;
            new Thread(() -> {
                try (InputStream input = current.getInputStream();
                     OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(writer)) {
                    byte[] bytes = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(bytes)) >= 0) output.write(bytes, 0, count);
                } catch (Exception ignored) {
                } finally {
                    current.destroy();
                }
            }, "YuChuangShellLogcat").start();
            return pipe[0];
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start shell logcat", e);
        }
    }

    @Override public synchronized void stop() {
        if (logcat != null) {
            logcat.destroy();
            logcat = null;
        }
        if (writeEnd != null) {
            try { writeEnd.close(); } catch (Exception ignored) {}
            writeEnd = null;
        }
    }
}
