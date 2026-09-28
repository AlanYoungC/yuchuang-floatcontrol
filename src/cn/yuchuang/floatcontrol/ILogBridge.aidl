package cn.yuchuang.floatcontrol;
import android.os.ParcelFileDescriptor;
interface ILogBridge {
    ParcelFileDescriptor openLogcat(long sinceMillis);
    void stop();
}
