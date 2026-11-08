package com.mario.assistant;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.accessibilityservice.GestureDescription;
import android.hardware.HardwareBuffer;
import android.view.Display;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * A single, narrowly scoped, user-started claim; no generic button clicking.
 * 森林流程运行在主线程；起点识别流程运行在独立工作线程 scanThread——
 * 实测（HyperOS/Android 16）主线程会在跨应用手势切换后静默停摆（三次复测均复现，
 * 停摆点无法归因到任何应用侧调用），流程逻辑全部迁出主线程后不再受其影响。
 */
public final class TaskService extends AccessibilityService {
    private static final String ALIPAY = "com.eg.android.AlipayGphone";
    private static final String[] TASKS = {"逛支付宝会员中心", "逛一逛芝麻攒粒攻略"};
    private static volatile TaskService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private HandlerThread scanThread;
    private Handler scanHandler;
    private boolean armed;
    private boolean readingArmed;
    private boolean readingBusy;
    private long readingDeadline;
    private long adTransitionDeadline;
    private volatile long lastScanAt;
    private int readingStage; // 0: task entry, 1: ad, 2: waiting for detail, 3: viewing, 4: return
    private AdFlow adFlow;
    private ReadingTaskLocator.Box priorAdTarget;
    private ReadingTaskLocator.Box priorAdAnchor;
    private int rewardFrames;
    private boolean readingNavigationAttempted;
    private ReadingTaskLocator.Box priorReadingTarget;
    private volatile String lastForegroundPkg;
    private int adSheetFrames;
    private ReadingTaskLocator.Box priorTabTarget;
    private int emptyScans;
    private int scrollAttempts;
    private int priorWidth, priorHeight;
    private TextRecognizer recognizer;
    private static final String CHANNEL_STATUS = "run_status";
    private static final int NOTIFICATION_ID = 42;
    private volatile boolean foregroundActive;
    private final Runnable readingScan = new Runnable() {
        @Override public void run() {
            if (!readingArmed) return;
            lastScanAt = SystemClock.elapsedRealtime();
            if (lastScanAt >= readingDeadline) {
                readingArmed = false;
                save(readingTimeoutMessage());
                return;
            }
            AccessibilityNodeInfo root;
            String pkg;
            if (readingStage == 2 || readingStage == 3) {
                // 详情页/第三方应用阶段只需要前台包名；避免对陌生窗口做 getRootInActiveWindow
                // 的同步调用，改用窗口切换事件记录。
                root = null;
                pkg = lastForegroundPkg == null ? "" : lastForegroundPkg;
            } else {
                root = getRootInActiveWindow();
                pkg = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
            }
            Log.i("MarioAdDiag", "scan stage=" + readingStage + " busy=" + readingBusy + " pkg=" + pkg);
            long now = SystemClock.elapsedRealtime();
            if (readingStage == 2) {
                if (adFlow.observeDetail(pkg, now)) {
                    readingStage = 3;
                    save("已进入广告详情页，等待至少 15 秒。");
                } else if ("com.qidian.QDReader".equals(pkg) && !readingBusy) {
                    // 疑似起点内落地页：用 OCR 确认广告底部操作区是否已消失（见 handleOcrText）。
                    inspectReadingScreen();
                } else if (!pkg.isEmpty()) {
                    stopReading("广告进入未适配的应用，已停止自动操作。");
                } else if (now >= adTransitionDeadline) {
                    stopReading("广告点击后未确认详情页打开（前台记录："
                            + (lastForegroundPkg == null ? "无，若持续如此请关闭再开启无障碍服务" : lastForegroundPkg)
                            + "），已超时停止。");
                }
            } else if (readingStage == 3) {
                if (!pkg.isEmpty() && !adFlow.isDestination(pkg)) {
                    stopReading("已离开广告详情页，已停止自动返回。");
                } else if (adFlow.shouldReturn(pkg, now)) {
                    if (adFlow.isInApp()) {
                        if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                            adFlow.returnRequested();
                            readingStage = 4;
                            adTransitionDeadline = now + 30000;
                            save("已在起点内停留 15 秒，已按返回键退出详情页，等待奖励提示。");
                        } else {
                            stopReading("无法执行返回操作，请手动返回。");
                        }
                    } else {
                        Intent intent = getPackageManager().getLaunchIntentForPackage("com.qidian.QDReader");
                        if (intent == null) { stopReading("无法返回起点，请手动切回。"); }
                        else {
                            try {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                                adFlow.returnRequested();
                                readingStage = 4;
                                adTransitionDeadline = now + 30000;
                                save("已尝试切回起点，等待奖励提示。");
                            } catch (RuntimeException e) { stopReading("无法自动切回起点，请手动返回。"); }
                        }
                    }
                }
            } else if (readingStage == 4 && now >= adTransitionDeadline) {
                stopReading("未能确认返回后的奖励，请手动检查。");
            } else if (!readingBusy && root != null && "com.qidian.QDReader".equals(pkg)) {
                if (readingStage != 0 || !openReadingCenterIfNeeded(root)) inspectReadingScreen();
            }
            if (readingArmed) scanHandler.postDelayed(this, 4000);
        }
    };
    private long deadline;
    private final Runnable scan = new Runnable() {
        @Override public void run() {
            if (!armed) return;
            if (SystemClock.elapsedRealtime() >= deadline) {
                finish("未找到指定任务的领取按钮，已超时停止。");
                return;
            }
            inspect();
            if (armed) handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onServiceConnected() {
        Log.i("MarioAdDiag", "onServiceConnected");
        if (scanThread == null) {
            scanThread = new HandlerThread("mario-scan");
            scanThread.start();
            scanHandler = new Handler(scanThread.getLooper());
        }
        instance = this;
    }
    static TaskService connected() { return instance; }
    void arm() {
        startRunForeground();
        handler.removeCallbacks(scan);
        armed = true;
        deadline = SystemClock.elapsedRealtime() + 90000;
        save("等待森林奖励页：仅处理会员中心或芝麻攻略的立即领取。");
        if (scanHandler != null) scanHandler.post(() -> {
            readingArmed = false;
            scanHandler.removeCallbacks(readingScan);
            if (adFlow != null) adFlow.cancel();
        });
        handler.postDelayed(scan, 800);
    }
    void inspectReading() {
        startRunForeground();
        finish("准备识别起点福利中心。");
        if (scanHandler == null) { save("识别线程未就绪，请稍后重试。"); return; }
        scanHandler.post(() -> {
            readingNavigationAttempted = false;
            readingStage = 0;
            adFlow = new AdFlow();
            priorAdTarget = null;
            priorAdAnchor = null;
            rewardFrames = 0;
            adSheetFrames = 0;
            priorReadingTarget = null;
            priorTabTarget = null;
            emptyScans = 0;
            scrollAttempts = 0;
            readingArmed = true;
            readingBusy = false;
            lastForegroundPkg = null;
            readingDeadline = SystemClock.elapsedRealtime() + 300000;
            save("等待起点福利中心，将打开可执行的广告任务行。");
            scanHandler.removeCallbacks(readingScan);
            scanHandler.postDelayed(readingScan, 1000);
        });
    }
    void cancel() {
        armed = false;
        handler.removeCallbacks(scan);
        if (scanHandler != null) scanHandler.post(() -> stopReading("已停止。"));
        else save("已停止。");
    }
    private void stopReading(String result) {
        readingArmed = false;
        scanHandler.removeCallbacks(readingScan);
        if (adFlow != null) adFlow.cancel();
        stopRunForeground();
        save(result);
    }
    /**
     * 实测（HyperOS/Android 16）：点击广告跳转第三方应用后，本进程会被 MIUI Daemon
     * 直接通过 cgroup v2 冻结（/sys/fs/cgroup/.../cgroup.freeze=1，AOSP 的
     * dumpsys isFrozen 仍显示 false），扫描循环、回调、落盘全部停摆。
     * 用户启动试运行时升级为前台服务（带实时状态通知），使进程获得冻结豁免。
     */
    private void startRunForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_STATUS) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_STATUS, "试运行状态", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("任务试运行期间的实时状态");
            nm.createNotificationChannel(channel);
        }
        Notification notification = buildStatusNotification("任务试运行进行中");
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
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
    private Notification buildStatusNotification(String text) {
        return new Notification.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Mario 任务助手")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }
    private String readingTimeoutMessage() {
        switch (readingStage) {
            case 1: return "已进入广告页，但未确认可点击的浏览入口，已超时停止。";
            case 2: return "广告点击后未确认详情页打开，已超时停止。";
            case 3: return "广告停留期间未能确认返回时机，已超时停止。";
            case 4: return "返回起点后未确认奖励提示，已超时停止。";
            default: return "未找到可执行的广告任务入口，已超时停止。";
        }
    }
    private boolean openReadingCenterIfNeeded(AccessibilityNodeInfo root) {
        if (readingNavigationAttempted) return false;
        List<AccessibilityNodeInfo> entries = findExact(root, "福利中心");
        if (entries.size() != 1) return false;
        AccessibilityNodeInfo label = entries.get(0);
        AccessibilityNodeInfo item = label.getParent();
        Rect bounds = new Rect();
        if (item != null) item.getBoundsInScreen(bounds);
        boolean eligible = item != null && label.isVisibleToUser() && item.isVisibleToUser()
                && item.isEnabled() && item.isClickable() && !bounds.isEmpty()
                && "com.qidian.QDReader:id/layoutItem".equals(item.getViewIdResourceName());
        if (!ReadingNavigationPolicy.mayOpen(String.valueOf(root.getPackageName()),
                hasText(root, "我的账户"), hasText(root, "活动中心"), entries.size(), eligible)) return false;
        readingNavigationAttempted = true;
        if (!item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            readingArmed = false;
            save("无法打开起点福利中心，请手动进入后重试。");
        } else {
            save("已尝试进入起点福利中心，等待任务页面。");
        }
        return true;
    }
    private void inspectReadingScreen() {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            readingArmed = false; save("系统不支持屏幕识别。"); return;
        }
        readingBusy = true;
        java.util.concurrent.Executor scanExecutor = scanHandler::post;
        takeScreenshot(Display.DEFAULT_DISPLAY, scanExecutor, new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap bitmap = null;
                try {
                    Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware != null) bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                } finally { buffer.close(); }
                if (bitmap == null) { readingBusy = false; return; }
                final Bitmap image = bitmap;
                final long capturedAt = SystemClock.elapsedRealtime();
                if (recognizer == null) recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                recognizer.process(InputImage.fromBitmap(image, 0))
                    .addOnSuccessListener(text -> scanHandler.post(() -> handleOcrText(text, image, capturedAt)))
                    .addOnFailureListener(error -> scanHandler.post(() -> {
                        if (readingArmed) { readingArmed = false; save("起点页面识别失败，本次未操作。"); }
                    }))
                    .addOnCompleteListener(task -> scanHandler.post(() -> { image.recycle(); readingBusy = false; }));
            }
            @Override public void onFailure(int errorCode) {
                readingBusy = false;
                readingArmed = false;
                save("无法读取起点页面，截图错误码：" + errorCode + "；本次未操作。");
            }
        });
    }
    /** OCR 结果的业务判定；固定在 scan 线程执行（识别回调线程与流程线程解耦）。 */
    private void handleOcrText(Text text, Bitmap image, long capturedAt) {
        if (!readingArmed) return;
        AccessibilityNodeInfo current = getRootInActiveWindow();
        if (current == null || !"com.qidian.QDReader".contentEquals(current.getPackageName())) return;
        List<ReadingTaskLocator.Box> boxes = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect rect = line.getBoundingBox();
                if (rect != null) boxes.add(new ReadingTaskLocator.Box(line.getText().replace(" ", ""), rect.left, rect.top, rect.right, rect.bottom));
            }
        }
        if (readingStage == 1) {
            inspectAdPrompt(boxes, image, capturedAt);
            return;
        }
        if (readingStage == 2) {
            handleStageTwoOcr(boxes, image);
            return;
        }
        if (readingStage == 4) {
            inspectReward(boxes, current, image, capturedAt);
            return;
        }
        if (readingStage != 0) return;
        ReadingTaskLocator.Row row = ReadingTaskLocator.find(boxes, image.getWidth());
        if (row == null) {
            priorReadingTarget = null;
            Log.i("MarioAdDiag", ReadingTaskLocator.describe(boxes));
            boolean onWelfarePage = hasOcrText(boxes, "福利中心");
            if (onWelfarePage) {
                emptyScans++;
                if (emptyScans >= 2 && scrollAttempts < 6) {
                    emptyScans = 0;
                    scrollAttempts++;
                    swipe(image.getWidth(), image.getHeight());
                }
            } else {
                emptyScans = 0;
                attemptTabNavigation(boxes, image, capturedAt);
            }
            return;
        }
        emptyScans = 0;
        if (priorWidth != image.getWidth() || priorHeight != image.getHeight()
                || !VisualTargetGuard.stable(priorReadingTarget, row.button, image.getWidth(), image.getHeight())
                || SystemClock.elapsedRealtime() - capturedAt > 5000) {
            priorReadingTarget = row.button;
            priorWidth = image.getWidth(); priorHeight = image.getHeight();
            return;
        }
        readingStage = 1;
        priorReadingTarget = null;
        tap(row.button.left, row.button.top, row.button.right, row.button.bottom,
                "已点击「" + row.title + "」任务入口，等待广告页面。", "任务入口点击被中断。");
    }
    /** 福利中心之外且尚未进入任务页时，点击底部唯一的“我的”标签实现全自动进入我的页面。 */
    private void attemptTabNavigation(List<ReadingTaskLocator.Box> boxes, Bitmap image, long capturedAt) {
        int width = image.getWidth(), height = image.getHeight();
        if (width <= 0 || height <= 0 || readingNavigationAttempted) return;
        for (ReadingTaskLocator.Box box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            if (text.equals("我的账户") || text.equals("福利中心")) return;
        }
        ReadingTaskLocator.Box tab = null;
        for (ReadingTaskLocator.Box box : boxes) {
            if (box == null || !"我的".equals(box.text)) continue;
            if (box.left < 0 || box.right > width || box.top < height * 88 / 100 || box.top >= box.bottom) continue;
            if (tab != null) return;
            tab = box;
        }
        if (tab == null) { priorTabTarget = null; return; }
        if (!ReadingNavigationPolicy.mayTapMyTab(1, true)) return;
        if (!VisualTargetGuard.stable(priorTabTarget, tab, width, height)
                || SystemClock.elapsedRealtime() - capturedAt > 5000) {
            priorTabTarget = tab;
            return;
        }
        priorTabTarget = null;
        tap(tab.left, tab.top, tab.right, tab.bottom,
                "已点击「我的」标签，前往我的页面。", "「我的」标签点击被中断。");
    }
    /** 福利中心页内找不到任务行时向上滑动，暴露下方的章节卡等任务行；每次试运行最多 6 次。 */
    private void swipe(int width, int height) {
        Path path = new Path();
        path.moveTo(width / 2f, height * 3f / 4f);
        path.lineTo(width / 2f, height / 4f);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 300)).build();
        Thread dispatch = new Thread(() -> {
            boolean accepted;
            try {
                accepted = dispatchGesture(gesture, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) {
                        Log.i("MarioAdDiag", "swipe completed");
                        scanHandler.post(() -> save("已向上滑动福利中心，寻找下方任务行。"));
                    }
                    @Override public void onCancelled(GestureDescription g) {
                        Log.i("MarioAdDiag", "swipe cancelled");
                    }
                }, scanHandler);
            } catch (RuntimeException e) {
                accepted = false;
            }
            boolean result = accepted;
            scanHandler.post(() -> { if (!result) Log.i("MarioAdDiag", "swipe rejected"); });
        }, "mario-gesture");
        dispatch.setDaemon(true);
        dispatch.start();
    }
    private static boolean hasOcrText(List<ReadingTaskLocator.Box> boxes, String expected) {
        for (ReadingTaskLocator.Box box : boxes) {
            if (box != null && expected.equals(box.text)) return true;
        }
        return false;
    }
    /** stage 2 且前台仍在起点：广告底部操作区连续两帧消失即确认起点内落地页；若已回到福利中心则如实停止。 */
    private void handleStageTwoOcr(List<ReadingTaskLocator.Box> boxes, Bitmap image) {
        boolean sheetPresent = AdPromptMatcher.findDisclosure(boxes) != null
                || AdPromptMatcher.findDetails(boxes, image.getWidth(), image.getHeight()) != null;
        if (sheetPresent) { adSheetFrames = 0; return; }
        if (ReadingTaskLocator.find(boxes, image.getWidth()) != null) {
            stopReading("广告点击后回到了福利中心，未打开详情页；请手动核对任务状态。");
            return;
        }
        if (++adSheetFrames >= 2 && adFlow.confirmInAppDetail()) {
            adFlow.observeDetailInApp(SystemClock.elapsedRealtime());
            readingStage = 3;
            save("已在起点内打开广告详情页，停留 15 秒后将自动返回。");
        }
    }
    private void inspectAdPrompt(List<ReadingTaskLocator.Box> boxes, Bitmap image, long capturedAt) {
        if (AdPromptMatcher.hasReward(boxes)) {
            stopReading("广告显示已获得奖励，请手动关闭并核对任务状态。");
            return;
        }
        int width = image.getWidth(), height = image.getHeight();
        ReadingTaskLocator.Box anchor = AdPromptMatcher.findDisclosure(boxes);
        ReadingTaskLocator.Box target = AdPromptMatcher.findDetails(boxes, width, height);
        Log.i("MarioAdDiag", "target=" + (target != null) + " anchor=" + (anchor != null)
                + " boxes=" + boxes.size() + " age=" + (SystemClock.elapsedRealtime() - capturedAt)
                + " prior=" + (priorAdTarget != null)
                + " stable=" + VisualTargetGuard.stableDetails(priorAdTarget, target, priorAdAnchor, anchor, width, height));
        if (target == null || anchor == null) {
            priorAdTarget = null;
            priorAdAnchor = null;
            Log.i("MarioAdDiag", AdPromptMatcher.describe(boxes, width, height));
            return;
        }
        if (priorWidth != width || priorHeight != height
                || !VisualTargetGuard.stableDetails(priorAdTarget, target, priorAdAnchor, anchor, width, height)
                || SystemClock.elapsedRealtime() - capturedAt > 5000) {
            Log.i("MarioAdDiag", "stable-wait prior=" + boxSummary(priorAdTarget) + " target=" + boxSummary(target)
                    + " anchor=" + boxSummary(anchor));
            priorAdTarget = target;
            priorAdAnchor = anchor;
            priorWidth = width;
            priorHeight = height;
            return;
        }
        adFlow.detailsRequested();
        Log.i("MarioAdDiag", "dispatch details");
        readingStage = 2;
        adSheetFrames = 0;
        adTransitionDeadline = SystemClock.elapsedRealtime() + 30000;
        tap(target.left, target.top, target.right, target.bottom,
                "已点击浏览入口，等待进入广告详情页。", "广告详情点击被中断。");
    }
    private static String boxSummary(ReadingTaskLocator.Box box) {
        return box == null ? "null" : box.text + "@" + box.left + "," + box.top + "-" + box.right + "," + box.bottom;
    }
    private void inspectReward(List<ReadingTaskLocator.Box> boxes, AccessibilityNodeInfo root, Bitmap image, long capturedAt) {
        if (!adFlow.confirmReward() || !AdPromptMatcher.hasReward(boxes)
                || SystemClock.elapsedRealtime() - capturedAt > 5000) { rewardFrames = 0; return; }
        if (++rewardFrames < 2) return;
        List<AccessibilityNodeInfo> close = findExact(root, "点击退出关闭视频");
        if (close.size() != 1 || !close.get(0).isVisibleToUser()) return;
        Rect rect = new Rect(); close.get(0).getBoundsInScreen(rect);
        if (rect.isEmpty() || rect.left < image.getWidth() * 3 / 4 || rect.bottom > image.getHeight() / 4) return;
        readingArmed = false;
        scanHandler.removeCallbacks(readingScan);
        tap(rect.left, rect.top, rect.right, rect.bottom,
                "广告显示已获得奖励，已尝试关闭；请在福利中心核对。", "关闭广告被中断，请手动处理。");
    }
    private void tap(int left, int top, int right, int bottom, String success, String failure) {
        Path path = new Path(); path.moveTo((left + right) / 2f, (top + bottom) / 2f);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build();
        // dispatchGesture 在后台线程调用（曾在主线程卡死且回调不返回）；回调经 scanHandler
        // 回流到流程线程。看门狗在派发前先排定，派发线程即使卡住也不影响记录。
        java.util.concurrent.atomic.AtomicBoolean gestureDone = new java.util.concurrent.atomic.AtomicBoolean();
        long dispatchedAt = SystemClock.elapsedRealtime();
        scanHandler.postDelayed(() -> {
            if (readingArmed && !gestureDone.get())
                Log.i("MarioAdDiag", "gesture callback pending " + (SystemClock.elapsedRealtime() - dispatchedAt) + "ms; flow continues");
        }, 3000);
        Thread dispatch = new Thread(() -> {
            boolean accepted;
            try {
                accepted = dispatchGesture(gesture, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) {
                        Log.i("MarioAdDiag", "gesture completed");
                        gestureDone.set(true);
                        scanHandler.post(() -> save(success));
                    }
                    @Override public void onCancelled(GestureDescription g) {
                        Log.i("MarioAdDiag", "gesture cancelled");
                        gestureDone.set(true);
                        scanHandler.post(() -> stopReading(failure));
                    }
                }, scanHandler);
            } catch (RuntimeException e) {
                accepted = false;
            }
            boolean result = accepted;
            scanHandler.post(() -> {
                if (!result) { Log.i("MarioAdDiag", "gesture rejected"); stopReading(failure); }
            });
        }, "mario-gesture");
        dispatch.setDaemon(true);
        dispatch.start();
    }
    private void finish(String result) {
        armed = false;
        handler.removeCallbacks(scan);
        stopRunForeground();
        save(result);
    }
    private void save(String result) {
        Log.i("MarioAdDiag", "save: " + result);
        getSharedPreferences("mario", MODE_PRIVATE).edit().putString("last_result", result).apply();
        if (foregroundActive) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildStatusNotification(result));
        }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // 服务不按包名过滤事件：只记录前台窗口切换的包名（不读取任何窗口内容），
        // 供广告详情/第三方应用阶段判断前台，无需同步遍历陌生应用的窗口树。
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null && pkg.length() > 0) {
            String next = pkg.toString();
            if (!next.equals(lastForegroundPkg))
                Log.i("MarioAdDiag", "foreground=" + next + " cls=" + event.getClassName());
            lastForegroundPkg = next;
        }
    }
    @Override public void onInterrupt() {
        // 该回调可能在手势跨应用窗口切换时由系统触发且不代表能力被收回；
        // 只记录不停机，真实能力丧失由后续截图/手势失败的兜底路径如实停止。
        Log.i("MarioAdDiag", "onInterrupt");
    }
    @Override public void onDestroy() {
        Log.i("MarioAdDiag", "onDestroy");
        armed = false;
        readingArmed = false;
        handler.removeCallbacks(scan);
        if (scanHandler != null) scanHandler.removeCallbacks(readingScan);
        if (recognizer != null) recognizer.close();
        if (scanThread != null) scanThread.quitSafely();
        if (instance == this) instance = null;
        super.onDestroy();
    }
    private void inspect() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (!ALIPAY.contentEquals(root.getPackageName())) return;
        if (!hasText(root, "蚂蚁森林")) return;
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        if (screen.height() <= 0) return;
        List<AccessibilityNodeInfo> matches = new ArrayList<>();
        List<AccessibilityNodeInfo> buttons = findExact(root, "立即领取");
        for (AccessibilityNodeInfo button : buttons) {
            if (!exactText(button, "立即领取") || !button.isVisibleToUser()
                    || !button.isEnabled() || !button.isClickable()) continue;
            Rect box = new Rect();
            button.getBoundsInScreen(box);
            if (box.isEmpty()) continue;
            AccessibilityNodeInfo parent = button;
            for (int i = 0; i < 7 && parent != null; i++, parent = parent.getParent()) {
                Rect bounds = new Rect();
                parent.getBoundsInScreen(bounds);
                boolean hasTask = hasSupportedTask(parent);
                if (bounds.height() > 0 && bounds.height() <= screen.height() / 6
                        && hasTask) {
                    matches.add(button);
                    break;
                }
            }
        }
        if (matches.size() != 1) return;
        AccessibilityNodeInfo button = matches.get(0);
        if (!ExecutionPolicy.mayClick(ALIPAY, ALIPAY, "立即领取", true, !armed)) return;
        boolean dispatched = button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        finish(dispatched ? "已点击立即领取；请查看是否进入抽奖或其他后续任务，奖励尚未确认。" : "按钮未响应，未确认领取。");
    }
    private final TreeSearch.Access<AccessibilityNodeInfo> nodeAccess = new TreeSearch.Access<AccessibilityNodeInfo>() {
        @Override public boolean matches(AccessibilityNodeInfo node, String label) {
            return exactText(node, label);
        }
        @Override public List<AccessibilityNodeInfo> children(AccessibilityNodeInfo node) {
            List<AccessibilityNodeInfo> children = new ArrayList<>();
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) children.add(child);
            }
            return children;
        }
    };
    private List<AccessibilityNodeInfo> findExact(AccessibilityNodeInfo root, String label) {
        return TreeSearch.find(root, label, nodeAccess, 1000);
    }
    private boolean hasSupportedTask(AccessibilityNodeInfo root) {
        for (String task : TASKS) if (hasText(root, task)) return true;
        return false;
    }
    private boolean hasText(AccessibilityNodeInfo root, String expected) {
        for (AccessibilityNodeInfo node : findExact(root, expected)) {
            if (exactText(node, expected)) return true;
        }
        return false;
    }
    private boolean exactText(AccessibilityNodeInfo node, String expected) {
        return expected.contentEquals(node.getText() == null ? "" : node.getText())
                || expected.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription());
    }
}
