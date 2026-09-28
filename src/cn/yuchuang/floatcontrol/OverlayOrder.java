package cn.yuchuang.floatcontrol;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;

final class OverlayOrder {
    static final String MEDIA = "mediaOrder";
    static final String APPS = "appsOrder";
    private static final String[] MEDIA_DEFAULT = {"cover", "next", "play", "previous"};
    private static final String[] APPS_DEFAULT = {"message", "navigation", "music", "settings"};

    private OverlayOrder() {}

    static String[] get(SharedPreferences prefs, String key) {
        String[] defaults = MEDIA.equals(key) ? MEDIA_DEFAULT : APPS_DEFAULT;
        String saved = prefs.getString(key, "");
        try {
            JSONArray array = new JSONArray(saved);
            if (array.length() != defaults.length) return defaults.clone();
            String[] result = new String[defaults.length];
            for (int i = 0; i < result.length; i++) {
                String item = array.getString(i);
                boolean known = false;
                for (String valid : defaults) if (valid.equals(item)) known = true;
                if (!known) return defaults.clone();
                for (int j = 0; j < i; j++) if (item.equals(result[j])) return defaults.clone();
                result[i] = item;
            }
            return result;
        } catch (JSONException e) {
            return defaults.clone();
        }
    }

    static void save(SharedPreferences prefs, String key, String[] items) {
        JSONArray array = new JSONArray();
        for (String item : items) array.put(item);
        prefs.edit().putString(key, array.toString()).apply();
    }
}
