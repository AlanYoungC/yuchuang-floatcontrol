package cn.yuchuang.floatcontrol;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.res.Configuration;

public class FloatApplication extends Application {
    private SharedPreferences.OnSharedPreferenceChangeListener changes;

    @Override public void onCreate() {
        super.onCreate();
        Diagnostics.init(this);
        SystemLogCollector.init(this);
        changes = (prefs, key) -> Diagnostics.event("settings", "changed", key);
        getSharedPreferences("settings", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changes);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Diagnostics.event("process", "trim_memory", "level=" + level);
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        Diagnostics.event("process", "low_memory", "");
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        Diagnostics.event("process", "configuration",
            "orientation=" + configuration.orientation + " uiMode=" + configuration.uiMode);
    }
}
