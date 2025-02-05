package top.shixinzhang.bitmapmonitor;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.bytedance.shadowhook.ShadowHook;

import java.util.LinkedList;
import java.util.List;

import androidx.annotation.Keep;
import androidx.annotation.WorkerThread;

import top.shixinzhang.bitmapmonitor.ui.FloatWindow;

/**
 * Description:
 * <br>
 *
 * <br> Created by shixinzhang on 2022/5/8.
 *
 * <br> Email: shixinzhang2016@gmail.com
 *
 * <br> https://about.me/shixinzhang
 */
@Keep
public class BitmapMonitor {
    static {
        System.loadLibrary("bitmapmonitor");
    }

    public interface BitmapInfoListener {
        void onBitmapInfoChanged(BitmapMonitorData data);
    }

    public interface CurrentSceneProvider {
        String getCurrentScene();
    }

    private final static String TAG = "BitmapMonitor";
    private static List<BitmapInfoListener> sListener = new LinkedList<>();
    private static Config sConfig;
    private static CurrentSceneProvider sCurrentSceneProvider;

    public static boolean init(Config config) {
        if (config == null) {
            return false;
        }
        sConfig = config;

        int ret = ShadowHook.init();
        ShadowHook.setDebuggable(config.isDebug);

        if (isDebug()) {
            log("init called, ret:" + ret + config);
        }

        return ret == 0;
    }

    /**
     * Start hook bitmap
     * @return
     */
    public static boolean start() {
        return start(null);
    }

    public static boolean start(CurrentSceneProvider provider) {
        if (sConfig == null) {
            return false;
        }

        sCurrentSceneProvider = provider;

        int ret = hookBitmapNative(sConfig.checkRecycleInterval, sConfig.getStackThreshold,
                sConfig.restoreImageThreshold, sConfig.restoreImageDirectory);

        if (sConfig.showFloatWindow) {
            toggleFloatWindowVisibility(true);
        }
        return ret == 0;
    }

    /**
     * 停止 hook
     */
    public static void stop() {
        stopHookBitmapNative();

        sCurrentSceneProvider = null;
    }

    public static void toggleFloatWindowVisibility(boolean show) {
        if (sConfig == null || sConfig.context == null) {
            return;
        }
        if (show) {
            Context context = sConfig.context;
            FloatWindow.show(context);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "需要给悬浮窗权限才能实时查看图片内存数据", Toast.LENGTH_SHORT).show();
            }
        } else {
            FloatWindow.hide(sConfig.context);
        }
    }

    /**
     * 获取这段期间 hook 的 bitmap 数据 （包括总数和具体各个图片的信息）
     */
    @WorkerThread
    public static BitmapMonitorData dumpBitmapInfo() {
        return dumpBitmapInfoNative(false);
    }

    @WorkerThread
    public static BitmapMonitorData dumpBitmapInfo(boolean ensureRestoreImage) {
        return dumpBitmapInfoNative(true);
    }

    /**
     * 获取这段期间 hook 的 bitmap 数量（只有总数，没有具体的图片信息）
     */
    public static BitmapMonitorData dumpBitmapCount() {
        return dumpBitmapCountNative();
    }

    public static void addListener(BitmapInfoListener listener) {
        if (!sListener.contains(listener)) {
            sListener.add(listener);
        }
    }

    public static void removeListener(BitmapInfoListener listener) {
        sListener.remove(listener);
    }

    /**
     * Will be called from native
     * @return
     */
    @Keep
    public static void reportBitmapInfo(BitmapMonitorData info) {
        for (BitmapInfoListener listener : sListener) {
            listener.onBitmapInfoChanged(info);
        }
    }

    /**
     * Will be called from native
     * @return
     */
    @Keep
    public static String dumpJavaStack() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        sb.setLength(0);

        sb.append(Thread.currentThread().getName()).append("\n");

        boolean beginBusinessCode = false;

        for (StackTraceElement s : st) {
            String fileName = s.getFileName();
            String className = s.getClassName();
            boolean isInternalLogic = className != null && className.contains("top.shixinzhang.bitmapmonitor.ui");
//            log("dumpJavaStack, isInternalLogic: " + isInternalLogic + ", " + className);
            if (isInternalLogic) {
                //BitmapMonitor 展示图片数据时也会触发 bitmap create，需要过滤这部分调试数据
                return null;
            }
            if (beginBusinessCode) {
                sb.append("\tat ").append(className).append(".")
                        .append(s.getMethodName())
                        .append("(").append(fileName)
                        .append(":").append(s.getLineNumber())
                        .append(")\n");
            } else {
                beginBusinessCode = fileName != null && fileName.contains("BitmapMonitor.java");
            }
        }

        if (isDebug()) {
            log("dumpJavaStack: " + Thread.currentThread().getName());
            log("dumpJavaStack: " + sb.toString());
        }
        return sb.toString();
    }

    /**
     * Will be called from native
     * @return
     */
    @Keep
    public static String getCurrentScene() {
        if (sCurrentSceneProvider != null) {
            return sCurrentSceneProvider.getCurrentScene();
        }
        return null;
    }

    public static boolean isDebug() {
        return sConfig != null && sConfig.isDebug;
    }

    private static void log(String msg) {
        if (!isDebug()) {
            return;
        }
        Log.d(TAG, msg);
    }

    public static Config getConfig() {
        return sConfig;
    }

    /**
     * @param getStackThreshold 超过这个阈值后打印堆栈
     * @param copyLocalThreshold  超过这个阈值后，保存本地数据，以便查看
     * @return 0 if success
     */
    @Keep
    private static native int hookBitmapNative(long checkRecycleInterval, long getStackThreshold, long copyLocalThreshold, String copyDir);

    @Keep
    private static native void stopHookBitmapNative();


    /**
     * 仅仅获取数量
     *
     * @return
     */
    @Keep
    private static native BitmapMonitorData dumpBitmapCountNative();

    /**
     * Get all bitmap info
     * @param ensureRestoreImage whether need check and restore again
     * @return
     */
    @Keep
    private static native BitmapMonitorData dumpBitmapInfoNative(boolean ensureRestoreImage);

    public static class Config {
        //检查 Bitmap 是否回收的间隔，单位：秒
        long checkRecycleInterval;
        //超过这个阈值后获取堆栈，单位 byte
        long getStackThreshold;
        //超过这个阈值后，保存像素数据为图片，以便分析内容，单位 byte
        long restoreImageThreshold;
        //图片还原保存路径
        String restoreImageDirectory;
        //是否展示悬浮窗，开启后可以实时查看数据
        boolean showFloatWindow;
        boolean isDebug;
        //图片数据是否持久化到磁盘
        boolean persistDataInDisk;

        //建议用 application context
        Context context;

        private Config(Builder builder) {
            checkRecycleInterval = builder.checkRecycleInterval;
            getStackThreshold = builder.getStackThreshold;
            restoreImageThreshold = builder.restoreImageThreshold;
            restoreImageDirectory = builder.restoreImageDirectory;
            showFloatWindow = builder.showFloatWindow;
            isDebug = builder.isDebug;
            persistDataInDisk = builder.persistDataInDisk;
            context = builder.context;
        }


        public static final class Builder {
            private long checkRecycleInterval;
            private long getStackThreshold;
            private long restoreImageThreshold;
            private String restoreImageDirectory;
            private boolean showFloatWindow;
            private boolean isDebug;
            private boolean persistDataInDisk;
            private Context context;

            public Builder() {
            }

            public Builder checkRecycleInterval(long val) {
                checkRecycleInterval = val;
                return this;
            }

            public Builder getStackThreshold(long val) {
                getStackThreshold = val;
                return this;
            }

            public Builder restoreImageThreshold(long val) {
                restoreImageThreshold = val;
                return this;
            }

            public Builder restoreImageDirectory(String val) {
                restoreImageDirectory = val;
                return this;
            }

            public Builder showFloatWindow(boolean val) {
                showFloatWindow = val;
                return this;
            }

            public Builder isDebug(boolean val) {
                isDebug = val;
                return this;
            }

            public Builder context(Context val) {
                context = val;
                return this;
            }

            public Config build() {
                return new Config(this);
            }
        }

        @Override
        public String toString() {
            return "Config:\n" +
                    "checkRecycleInterval=" + checkRecycleInterval +
                    ", \ngetStackThreshold=" + getStackThreshold +
                    ", \nrestoreImageThreshold=" + restoreImageThreshold +
                    ", \nrestoreImageDirectory='" + restoreImageDirectory + '\'' +
                    ", \nshowFloatWindow=" + showFloatWindow +
                    ", \nisDebug=" + isDebug;
        }
    }
}
