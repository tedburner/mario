package com.mario.assistant;

import com.mario.assistant.application.RunCoordinator;
import com.mario.assistant.application.RunHost;
import com.mario.assistant.application.checkin.DailyCheckinRunner;
import com.mario.assistant.application.forest.ForestEnergyRunner;
import com.mario.assistant.application.forest.ForestTaskRunner;
import com.mario.assistant.application.welfare.WelfareTaskRunner;

import com.mario.assistant.infrastructure.a11y.A11yScreenshot;
import com.mario.assistant.infrastructure.a11y.Gestures;
import com.mario.assistant.infrastructure.a11y.UiActions;
import com.mario.assistant.infrastructure.a11y.UiTreeSource;
import com.mario.assistant.infrastructure.ocr.MlKitTextReader;
import com.mario.assistant.infrastructure.system.DeviceEnvironment;
import com.mario.assistant.infrastructure.ui.RunLog;
import com.mario.assistant.infrastructure.ui.StatusNotifier;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.content.pm.ServiceInfo;
import android.view.Display;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 组合根：把无障碍/截图/手势/OCR 能力装配给任务运行器，并保证同一时刻只跑一个任务。
 * 所有流程都跑在独立工作线程 scanThread——实测（HyperOS/Android 16）主线程会在
 * 跨应用手势切换后静默停摆，流程逻辑全部迁出主线程后不再受其影响。
 */
public final class TaskService extends AccessibilityService implements RunHost {
    private static volatile TaskService instance;
    private HandlerThread scanThread;
    private Handler scanHandler;
    private UiTreeSource trees;
    private UiActions uiActions;
    private Gestures gestures;
    private MlKitTextReader ocr;
    private DeviceEnvironment env;
    private RunLog runLog;
    private StatusNotifier notifier;
    private RunCoordinator coordinator;
    private volatile boolean foregroundActive;

    @Override protected void onServiceConnected() {
        Log.i("MarioAdDiag", "onServiceConnected");
        if (scanThread == null) {
            scanThread = new HandlerThread("mario-scan");
            scanThread.start();
            scanHandler = new Handler(scanThread.getLooper());
        }
        instance = this;
        env = new DeviceEnvironment(this);
        notifier = new StatusNotifier(this);
        runLog = new RunLog(this, notifier, () -> foregroundActive);
        trees = new UiTreeSource(this);
        uiActions = new UiActions(this);
        gestures = new Gestures((gesture, callback, handler) ->
                dispatchGesture(gesture, callback, handler), scanHandler);
        ocr = new MlKitTextReader(new A11yScreenshot(
                (executor, callback) -> takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback),
                scanHandler), scanHandler);
        coordinator = new RunCoordinator(this);
    }
    static TaskService connected() { return instance; }
    void arm() {
        if (scanHandler == null || coordinator == null) { save("识别线程未就绪，请稍后重试。"); return; }
        scanHandler.post(() -> coordinator.start(new ForestTaskRunner(ForestTaskRunner.Mode.NORMAL)));
    }
    /** 森林能量：收自己树上/边上的气泡，再逐个好友「一键收」。 */
    void armEnergy() {
        if (scanHandler == null || coordinator == null) { save("识别线程未就绪，请稍后重试。"); return; }
        scanHandler.post(() -> coordinator.start(new ForestEnergyRunner()));
    }
    void inspectReading() {
        if (scanHandler == null || coordinator == null) { save("识别线程未就绪，请稍后重试。"); return; }
        save("准备识别起点福利中心。");
        scanHandler.post(() -> {
            // 只停森林流程；前台服务保持（识别流程自身的冻结豁免依赖它）
            coordinator.detach();
            coordinator.start(new WelfareTaskRunner());
        });
    }
    /** 每日签到：按名单逐个 APP 自动签到，单个失败跳过继续。 */
    void armCheckin() {
        if (scanHandler == null || coordinator == null) { save("识别线程未就绪，请稍后重试。"); return; }
        scanHandler.post(() -> coordinator.start(new DailyCheckinRunner()));
    }
    /** 一键自动化：每日签到 → 起点任务 → 森林任务 → 森林能量，前一个收尾后自动接续。 */
    void armAutomation() {
        if (scanHandler == null || coordinator == null) { save("识别线程未就绪，请稍后重试。"); return; }
        save("一键自动化：每日签到 → 起点任务 → 森林任务 → 森林能量。");
        scanHandler.post(() -> coordinator.startChain(
                new DailyCheckinRunner(),
                new WelfareTaskRunner(),
                new ForestTaskRunner(ForestTaskRunner.Mode.NORMAL),
                new ForestEnergyRunner()));
    }
    void cancel() {
        if (scanHandler != null) scanHandler.post(() -> coordinator.stop("已停止。"));
        else save("已停止。");
    }
    /** 主界面 HUD：是否有试运行在进行。 */
    boolean busy() { return coordinator != null && coordinator.busy(); }
    /**
     * 实测（HyperOS/Android 16）：点击广告跳转第三方应用后，本进程会被 MIUI Daemon
     * 直接通过 cgroup v2 冻结（/sys/fs/cgroup/.../cgroup.freeze=1，AOSP 的
     * dumpsys isFrozen 仍显示 false），扫描循环、回调、落盘全部停摆。
     * 用户启动试运行时升级为前台服务（带实时状态通知），使进程获得冻结豁免。
     */
    private void startRunForeground() {
        notifier.ensureChannel();
        Notification notification = notifier.build("任务试运行进行中");
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(StatusNotifier.NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(StatusNotifier.NOTIFICATION_ID, notification);
            }
            foregroundActive = true;
            Log.i("MarioAdDiag", "startForeground ok");
        } catch (RuntimeException e) {
            Log.i("MarioAdDiag", "startForeground failed: " + e.getMessage());
        }
    }
    private void stopRunForeground() {
        if (!foregroundActive) return;
        foregroundActive = false;
        stopForeground(true);
        Log.i("MarioAdDiag", "stopForeground");
    }
    /** 仅调试桥使用：直接进入恢复流程，验证从支付宝内页/背包回到奖励页。 */
    boolean debugForestRecover() {
        if (scanHandler == null || coordinator == null) return false;
        scanHandler.post(() -> coordinator.start(new ForestTaskRunner(ForestTaskRunner.Mode.DEBUG_RECOVER)));
        return true;
    }
    /** 仅调试桥使用：模拟进入「找能量」页后的浏览阶段，验证「一键收」识别。 */
    boolean debugForestCollect() {
        if (scanHandler == null || coordinator == null) return false;
        scanHandler.post(() -> coordinator.start(new ForestTaskRunner(ForestTaskRunner.Mode.DEBUG_COLLECT)));
        return true;
    }
    @Override public long now() { return SystemClock.elapsedRealtime(); }
    @Override public Handler handler() { return scanHandler; }
    @Override public DeviceEnvironment env() { return env; }
    @Override public UiTreeSource trees() { return trees; }
    @Override public UiActions uiActions() { return uiActions; }
    @Override public Gestures gestures() { return gestures; }
    @Override public MlKitTextReader ocr() { return ocr; }
    @Override public RunLog log() { return runLog; }
    @Override public void startForeground() { startRunForeground(); }
    @Override public void stopForeground() { stopRunForeground(); }
    private void save(String result) {
        runLog.save(result);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // 服务不按包名过滤事件：只记录前台窗口切换的包名（不读取任何窗口内容），
        // 供广告详情/第三方应用阶段判断前台，无需同步遍历陌生应用的窗口树。
        env.onWindowEvent(event);
    }
    @Override public void onInterrupt() {
        // 该回调可能在手势跨应用窗口切换时由系统触发且不代表能力被收回；
        // 只记录不停机，真实能力丧失由后续截图/手势失败的兜底路径如实停止。
        Log.i("MarioAdDiag", "onInterrupt");
    }
    @Override public void onDestroy() {
        Log.i("MarioAdDiag", "onDestroy");
        if (scanHandler != null) {
            if (coordinator != null) coordinator.detach();
        }
        if (ocr != null) ocr.close();
        if (scanThread != null) scanThread.quitSafely();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
