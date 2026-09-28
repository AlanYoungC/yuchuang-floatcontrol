package cn.yuchuang.floatcontrol;

import android.os.SystemClock;
import android.view.Window;
import androidx.metrics.performance.JankStats;

final class FrameMonitor {
    private final String windowName;
    private final JankStats stats;
    private String context = "";
    private long lastReport = SystemClock.elapsedRealtime();
    private int frames;
    private int jankyFrames;
    private long worstFrameMs;

    static FrameMonitor attach(Window window, String name) {
        return new FrameMonitor(window, name);
    }

    private FrameMonitor(Window window, String name) {
        windowName = name;
        stats = JankStats.createAndTrack(window, frame -> {
            frames++;
            if (frame.isJank()) {
                jankyFrames++;
                worstFrameMs = Math.max(worstFrameMs,
                    frame.getFrameDurationUiNanos() / 1_000_000);
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastReport >= 10_000) report(now);
        });
    }

    void setContext(String value) {
        if (context.equals(value)) return;
        report(SystemClock.elapsedRealtime());
        context = value;
    }

    private void report(long now) {
        if (jankyFrames > 0) {
            Diagnostics.event("jank", "frame_summary",
                "window=" + windowName + " context=" + context + " frames=" + frames +
                " janky=" + jankyFrames + " worstUiMs=" + worstFrameMs);
        }
        frames = 0;
        jankyFrames = 0;
        worstFrameMs = 0;
        lastReport = now;
    }

    void stop() {
        stats.setTrackingEnabled(false);
        report(SystemClock.elapsedRealtime());
    }
}
