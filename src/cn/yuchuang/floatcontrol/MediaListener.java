package cn.yuchuang.floatcontrol;

import android.service.notification.NotificationListenerService;

public class MediaListener extends NotificationListenerService {
    @Override public void onListenerConnected() {
        super.onListenerConnected();
        Diagnostics.event("media", "listener_connected", "");
    }

    @Override public void onListenerDisconnected() {
        Diagnostics.event("media", "listener_disconnected", "");
        super.onListenerDisconnected();
    }
}
