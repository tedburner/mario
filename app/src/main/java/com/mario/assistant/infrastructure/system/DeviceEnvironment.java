package com.mario.assistant.infrastructure.system;

import com.mario.assistant.infrastructure.ui.Diag;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

/** 设备环境：前台包名、已安装应用、启动应用、屏幕尺寸、系统返回/主页。 */
public final class DeviceEnvironment {
    public static final String ALIPAY = "com.eg.android.AlipayGphone";
    public static final String QIDIAN = "com.qidian.QDReader";

    private final AccessibilityService service;
    private volatile String foregroundPkg;

    public DeviceEnvironment(AccessibilityService service) {
        this.service = service;
    }

    public void onWindowEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || pkg.length() == 0) return;
        String next = pkg.toString();
        if (isSystemOverlay(next)) {
            // 通知栏/音量面板等系统浮层不是广告落地页，不能覆盖真正的应用前台记录。
            Diag.i("MarioAdDiag", "overlay=" + next + " cls=" + event.getClassName());
            return;
        }
        if (!next.equals(foregroundPkg)) {
            Diag.i("MarioAdDiag", "foreground=" + next + " cls=" + event.getClassName());
        }
        foregroundPkg = next;
    }

    public String foreground() {
        return foregroundPkg == null ? "" : foregroundPkg;
    }

    public void resetForeground() {
        foregroundPkg = null;
    }

    public boolean isInstalled(String pkg) {
        try {
            service.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public boolean launch(String pkg, boolean fresh) {
        Intent intent = service.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) return false;
        try {
            int flags = Intent.FLAG_ACTIVITY_NEW_TASK;
            flags |= fresh ? Intent.FLAG_ACTIVITY_CLEAR_TASK : Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;
            intent.addFlags(flags);
            service.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 把已有任务栈唤到前台，不改动栈内页面（广告返回起点时使用）。 */
    public boolean reopen(String pkg) {
        Intent intent = service.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) return false;
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public boolean canDrawOverlays() {
        return android.provider.Settings.canDrawOverlays(service);
    }

    public int screenWidth() {
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        return metrics.widthPixels;
    }

    public int screenHeight() {
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        return metrics.heightPixels;
    }

    public boolean back() {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    public boolean home() {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
    }

    public static boolean isSystemOverlay(String pkg) {
        return "com.android.systemui".equals(pkg) || "miui.systemui.plugin".equals(pkg);
    }

    public static boolean isAppMarket(String pkg) {
        return "com.xiaomi.market".equals(pkg) || "com.android.vending".equals(pkg)
                || "com.huawei.appmarket".equals(pkg) || "com.oppo.market".equals(pkg)
                || "com.vivo.appstore".equals(pkg) || "com.tencent.android.qqdownloader".equals(pkg)
                || "com.qihoo.appstore".equals(pkg) || "com.baidu.appsearch".equals(pkg)
                || "com.wandoujia.phoenix2".equals(pkg);
    }
}
