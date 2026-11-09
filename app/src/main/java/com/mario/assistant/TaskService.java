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
import android.provider.Settings;
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
    private long returnConfirmDeadline; // 跨应用切回起点的前台确认截止时间；0 表示不校验
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
    private boolean scrollReversed;
    private int claimedFrames;
    private long readingUnlockDeadline;
    private int unlockAttempts;
    private boolean signinTask;
    private int signinFrames;
    private long signinTapAt;
    private boolean signinClaimSeen;
    private int signinIdleFrames;
    private static final int STAGE_SIGNIN = 6; // 签到落地页（携程）：点“立即签到”后切回起点
    private String currentTaskTitle;
    private boolean currentSignin;
    private int returnedFrames;
    private int carouselFrames;
    private int carouselSwipes;
    private long carouselSwipeAt;
    private int dialogFrames;
    private long dialogTapAt;
    private long adEntryAt;
    private int adEntryRetries;
    private int readingPhase; // stage 5 内：0=找书架第一本书，1=书中阅读
    private long readingStartedAt;
    private ReadingTaskLocator.Box priorBookTarget;
    private int bookScans;
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
                // 前台包名以当前活动窗口为准：第三方页面上层弹出时仍可能收到起点的窗口事件，
                // 只用事件记录会把真实前台（如携程签到页）误判成起点（真机实测）。
                AccessibilityNodeInfo active = getRootInActiveWindow();
                CharSequence activePkg = active == null ? null : active.getPackageName();
                pkg = activePkg != null ? activePkg.toString()
                        : (lastForegroundPkg == null ? "" : lastForegroundPkg);
                root = null;
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
                            returnConfirmDeadline = 0;
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
                                returnConfirmDeadline = now + 5000;
                                save("已请求切回起点，等待前台窗口切换。");
                            } catch (RuntimeException e) { stopReading("无法自动切回起点，请手动返回。"); }
                        }
                    }
                }
            } else if (readingStage == 4) {
                boolean backInReading = "com.qidian.QDReader".equals(lastForegroundPkg)
                        || "com.qidian.QDReader".equals(pkg);
                if (returnConfirmDeadline > 0 && !backInReading && now >= returnConfirmDeadline) {
                    stopReading(Settings.canDrawOverlays(TaskService.this)
                            ? "系统拦截了后台启动起点，请检查 MIUI「后台弹出界面」权限后重试。"
                            : "系统拦截了后台启动起点：请授予「后台弹出界面」和「显示悬浮窗」后重试。");
                } else if (now >= adTransitionDeadline) {
                    stopReading("未能确认返回后的奖励，请手动检查。");
                } else if (!readingBusy && (root != null || "com.qidian.QDReader".equals(lastForegroundPkg))) {
                    // 返回起点后的奖励/轮播提示只能靠 OCR 判定，必须在此持续触发识别。
                    inspectReadingScreen();
                }
            } else if (readingStage == 5) {
                // 阅读解锁两阶段：找到书架第一本书 → 书中阅读 1 分钟 → 返回并重走导航
                if (now >= readingUnlockDeadline) {
                    stopReading("阅读解锁未完成，请手动检查限时任务状态。");
                } else if (readingPhase == 1) {
                    if (now - readingStartedAt >= 120000 && !readingBusy) {
                        if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                            readingStage = 0;
                            readingPhase = 0;
                            // 返回键落到书架页：必须重置导航标记，才能重新走 我→福利中心 链路。
                            readingNavigationAttempted = false;
                            emptyScans = 0;
                            scrollAttempts = 0;
                            save("阅读时间已满，已退出书籍，将重新进入 我-福利中心 继续任务。");
                        } else {
                            stopReading("无法执行返回操作，请手动返回福利中心。");
                        }
                    }
                } else if (!readingBusy && root != null && "com.qidian.QDReader".equals(pkg)) {
                    inspectReadingScreen(); // OCR 驱动：任务行可见性 / 书架第一本书定位
                }
            } else if (readingStage == STAGE_SIGNIN) {
                // 签到页可能在携程应用内，也可能内嵌在起点；统一靠 OCR 找“立即签到”。
                if (now >= readingDeadline) {
                    stopReading(readingTimeoutMessage());
                } else if (signinTapAt > 0 && now - signinTapAt >= 5000) {
                    returnAfterSignin(now);
                } else if (!readingBusy) {
                    inspectReadingScreen();
                }
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
        // 只停森林流程；前台服务必须保持（识别流程自身的冻结豁免依赖它）
        armed = false;
        handler.removeCallbacks(scan);
        save("准备识别起点福利中心。");
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
            scrollReversed = false;
            claimedFrames = 0;
            unlockAttempts = 0;
            signinTask = false;
            signinFrames = 0;
            signinTapAt = 0;
            signinClaimSeen = false;
            signinIdleFrames = 0;
            currentTaskTitle = null;
            currentSignin = false;
            returnedFrames = 0;
            carouselFrames = 0;
            carouselSwipes = 0;
            carouselSwipeAt = 0;
            dialogFrames = 0;
            dialogTapAt = 0;
            adEntryAt = 0;
            adEntryRetries = 0;
            readingPhase = 0;
            priorBookTarget = null;
            bookScans = 0;
            readingArmed = true;
            readingBusy = false;
            lastForegroundPkg = null;
            readingDeadline = SystemClock.elapsedRealtime() + 300000;
            returnConfirmDeadline = 0;
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
    /** 主界面 HUD：是否有试运行在进行。 */
    boolean busy() { return armed || readingArmed; }
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
            case 5: return "阅读解锁未完成，已超时停止。";
            case STAGE_SIGNIN: return "未在签到页找到「立即签到」，已超时停止。";
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
        List<ReadingTaskLocator.Box> boxes = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect rect = line.getBoundingBox();
                if (rect != null) boxes.add(new ReadingTaskLocator.Box(line.getText().replace(" ", ""), rect.left, rect.top, rect.right, rect.bottom));
            }
        }
        if (readingStage == STAGE_SIGNIN) {
            // 签到页属于携程应用，必须在起点的包名过滤之前处理。
            handleSigninOcr(boxes);
            return;
        }
        AccessibilityNodeInfo current = getRootInActiveWindow();
        CharSequence currentPkg = current == null ? null : current.getPackageName();
        boolean qidian = currentPkg != null
                ? "com.qidian.QDReader".contentEquals(currentPkg)
                : "com.qidian.QDReader".equals(lastForegroundPkg);
        if (!qidian) return;
        if (!AdCarouselPolicy.isContinuePrompt(boxes)) carouselFrames = 0;
        for (ReadingTaskLocator.Box box : boxes) {
            if (box == null || box.text == null || !box.text.contains("恭喜")) continue;
            Log.i("MarioAdDiag", "ocr congrats: " + allText(boxes));
            break;
        }
        for (ReadingTaskLocator.Box box : boxes) {
            if (box == null || box.text == null || !box.text.contains("知道")) continue;
            Log.i("MarioAdDiag", "ocr know: " + allText(boxes));
            break;
        }
        if (readingStage == 1) {
            inspectAdPrompt(boxes, image, capturedAt);
            return;
        }
        if (readingStage == 2) {
            handleStageTwoOcr(boxes, image);
            return;
        }
        if (readingStage == 5) {
            handleReadingUnlockOcr(boxes, image, capturedAt);
            return;
        }
        if (readingStage == 4) {
            Log.i("MarioAdDiag", "stage4 boxes=" + allText(boxes));
            if (!signinTask && AdCarouselPolicy.isContinuePrompt(boxes)) {
                handleCarouselPrompt(image);
                return;
            }
            if (dismissKnownDialog(boxes)) return;
            if (signinTask) {
                // 签到任务：返回起点且连续两帧无奖励弹窗时视为完成；携程为最后一个任务，完成后收尾。
                if (AdPromptMatcher.hasReward(boxes)) {
                    signinFrames = 0;
                    return;
                }
                if (++signinFrames >= 2) {
                    if (currentSignin) {
                        readingArmed = false;
                        scanHandler.removeCallbacks(readingScan);
                        stopRunForeground();
                        save("「去携程签到领积分」已完成，全部任务结束；请在福利中心核对各项奖励。");
                    } else {
                        nextTask("「" + currentTaskTitle + "」已返回起点；");
                    }
                }
                return;
            }
            // 广告任务：返回福利中心连续两帧即视为本轮流程结束，继续下一个任务。
            if (ReadingTaskLocator.find(boxes, image.getWidth()) != null) {
                if (++returnedFrames >= 2) {
                    nextTask("「" + currentTaskTitle + "」已返回福利中心；");
                }
                return;
            }
            returnedFrames = 0;
            inspectReward(boxes, current, image, capturedAt);
            return;
        }
        if (readingStage != 0) return;
        if (AdCarouselPolicy.isContinuePrompt(boxes)) { // 上次运行中断在广告轮播页时，先左滑继续
            handleCarouselPrompt(image);
            return;
        }
        if (dismissKnownDialog(boxes)) return;
        ReadingTaskLocator.Row row = ReadingTaskLocator.find(boxes, image.getWidth());
        if (row == null) {
            priorReadingTarget = null;
            Log.i("MarioAdDiag", ReadingTaskLocator.describe(boxes, image.getHeight()));
            logSigninGeometry(boxes);
            if (ReadingTaskLocator.claimedSignin(boxes, image.getWidth())) {
                // 携程行已是“已领取”，其余任务行也不可点：本轮任务全部完成，不必再滑动到超时。
                if (++claimedFrames >= 2) {
                    readingArmed = false;
                    scanHandler.removeCallbacks(readingScan);
                    stopRunForeground();
                    save("「去携程签到领积分」已领取，本轮任务全部完成；请在福利中心核对各项奖励。");
                }
                return;
            }
            claimedFrames = 0;
            boolean onWelfarePage = readingNavigationAttempted || ReadingTaskLocator.isWelfareCenter(boxes);
            if (onWelfarePage) {
                emptyScans++;
                if (emptyScans >= 2 && scrollAttempts < 6) {
                    emptyScans = 0;
                    scrollAttempts++;
                    swipe(image.getWidth(), image.getHeight(), !scrollReversed);
                } else if (emptyScans >= 2 && scrollAttempts >= 6 && !scrollReversed) {
                    // 起点可能停在福利中心底部：继续往下滑不会动，反向回扫找回任务行。
                    emptyScans = 0;
                    scrollAttempts = 0;
                    scrollReversed = true;
                    swipe(image.getWidth(), image.getHeight(), false);
                }
            } else {
                emptyScans = 0;
                attemptTabNavigation(boxes, image, capturedAt);
            }
            return;
        }
        emptyScans = 0;
        claimedFrames = 0;
        if (priorWidth != image.getWidth() || priorHeight != image.getHeight()
                || !VisualTargetGuard.stable(priorReadingTarget, row.button, image.getWidth(), image.getHeight())
                || SystemClock.elapsedRealtime() - capturedAt > 5000) {
            priorReadingTarget = row.button;
            priorWidth = image.getWidth(); priorHeight = image.getHeight();
            return;
        }
        priorReadingTarget = null;
        if (row.signin) {
            // 签到类任务：点「去完成」后进入携程签到页，由 STAGE_SIGNIN 点「立即签到」再切回。
            signinTask = true;
            signinFrames = 0;
            signinTapAt = 0;
            signinClaimSeen = false;
            signinIdleFrames = 0;
            currentTaskTitle = row.title;
            currentSignin = true;
            readingStage = STAGE_SIGNIN;
            readingDeadline = SystemClock.elapsedRealtime() + 45000;
            tap(row.button.left, row.button.top, row.button.right, row.button.bottom,
                    "已点击「" + row.title + "」，等待签到页面。", "签到入口点击被中断。");
            return;
        }
        signinTask = false;
        currentSignin = false;
        if (row.goRead) {
            currentTaskTitle = "限时广告加点";
            if (unlockAttempts >= 2) {
                readingArmed = false;
                save("阅读解锁未生效（已尝试 " + unlockAttempts + " 次），请手动完成后重试。");
                return;
            }
            unlockAttempts++;
            readingStage = 5;
            readingPhase = 0;
            priorBookTarget = null;
            bookScans = 0;
            readingUnlockDeadline = SystemClock.elapsedRealtime() + 200000;
            tap(row.button.left, row.button.top, row.button.right, row.button.bottom,
                    "已点击「阅读1分钟」，将打开书架第一本书阅读 2 分钟。", "阅读入口点击被中断。");
            return;
        }
        readingStage = 1;
        adEntryAt = SystemClock.elapsedRealtime();
        currentTaskTitle = row.title;
        tap(row.button.left, row.button.top, row.button.right, row.button.bottom,
                "已点击「" + row.title + "」任务入口，等待广告页面。", "任务入口点击被中断。");
    }
    /** 携程行定位失败时的原始坐标诊断（输出标题整条带内的所有 OCR 框）。 */
    private static void logSigninGeometry(List<ReadingTaskLocator.Box> boxes) {
        ReadingTaskLocator.Box anchor = null;
        for (ReadingTaskLocator.Box box : boxes) {
            if (box != null && box.text != null && box.text.contains("携程") && box.text.contains("签到")) { anchor = box; break; }
        }
        if (anchor == null) return;
        StringBuilder sb = new StringBuilder("signin-geom");
        for (ReadingTaskLocator.Box box : boxes) {
            if (box == null || box.text == null) continue;
            if (Math.abs(box.top - anchor.top) > 120) continue;
            sb.append(' ').append(box.text).append('@').append(box.left).append(',').append(box.top)
                    .append('-').append(box.right).append(',').append(box.bottom);
        }
        Log.i("MarioAdDiag", sb.toString());
    }
    /** 单个任务流程结束：回到 stage 0 继续下一个任务；滚动预算与各阶段状态重置，截止时间顺延。 */
    private void nextTask(String reason) {
        readingStage = 0;
        signinTask = false;
        signinFrames = 0;
        signinTapAt = 0;
        signinClaimSeen = false;
        signinIdleFrames = 0;
        returnedFrames = 0;
        adSheetFrames = 0;
        unlockAttempts = 0;
        readingPhase = 0;
        priorReadingTarget = null;
        priorAdTarget = null;
        priorAdAnchor = null;
        priorTabTarget = null;
        scrollAttempts = 0;
        scrollReversed = false;
        claimedFrames = 0;
        emptyScans = 0;
        carouselFrames = 0;
        carouselSwipes = 0;
        carouselSwipeAt = 0;
        dialogFrames = 0;
        dialogTapAt = 0;
        adEntryAt = 0;
        adEntryRetries = 0;
        returnConfirmDeadline = 0;
        if (adFlow != null) adFlow.reset();
        readingDeadline = SystemClock.elapsedRealtime() + 300000;
        save(reason + "继续查找下一个任务。");
    }
    /** 福利中心之外且尚未进入任务页时，点击底部唯一的“我的”标签实现全自动进入我的页面。 */
    private void attemptTabNavigation(List<ReadingTaskLocator.Box> boxes, Bitmap image, long capturedAt) {
        int width = image.getWidth(), height = image.getHeight();
        if (width <= 0 || height <= 0 || readingNavigationAttempted) return;
        boolean markerVisible = false;
        int candidates = 0;
        ReadingTaskLocator.Box tab = null;
        for (ReadingTaskLocator.Box box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            if (text.equals("我的账户") || text.equals("福利中心")) markerVisible = true;
            if (!"我".equals(box.text)) continue;
            if (box.left < 0 || box.right > width || box.top < height * 88 / 100 || box.top >= box.bottom) continue;
            candidates++;
            tab = box;
        }
        boolean stableNow = tab != null && VisualTargetGuard.stable(priorTabTarget, tab, width, height);
        Log.i("MarioAdDiag", "tabnav attempted=" + readingNavigationAttempted + " marker=" + markerVisible
                + " candidates=" + candidates + " tab=" + boxSummary(tab) + " prior=" + boxSummary(priorTabTarget)
                + " stable=" + stableNow);
        if (markerVisible || candidates != 1) { priorTabTarget = null; return; }
        if (!ReadingNavigationPolicy.mayTapMyTab(1, true)) return;
        if (!stableNow || SystemClock.elapsedRealtime() - capturedAt > 5000) {
            priorTabTarget = tab;
            return;
        }
        priorTabTarget = null;
        tap(tab.left, tab.top, tab.right, tab.bottom,
                "已点击「我的」标签，前往我的页面。", "「我的」标签点击被中断。");
    }
    /** 福利中心页内找不到任务行时滑动，暴露屏外任务行；每次试运行每方向最多 6 次。 */
    private void swipe(int width, int height, boolean revealBelow) {
        Path path = new Path();
        if (revealBelow) {
            path.moveTo(width / 2f, height * 3f / 4f);
            path.lineTo(width / 2f, height / 4f);
        } else {
            path.moveTo(width / 2f, height / 4f);
            path.lineTo(width / 2f, height * 3f / 4f);
        }
        dispatchSwipe(path, revealBelow ? "swipe" : "swipe back",
                revealBelow ? "已向上滑动福利中心，寻找下方任务行。" : "已向下滑动福利中心，回扫上方任务行。");
    }
    /** 广告轮播左滑：手指从右向左，取视频区域中部，避开顶部轮播面板与底部广告卡片。 */
    private void swipeLeft(int width, int height) {
        if (width <= 0 || height <= 0) return;
        Path path = new Path();
        path.moveTo(width * 85f / 100f, height * 45f / 100f);
        path.lineTo(width * 15f / 100f, height * 45f / 100f);
        dispatchSwipe(path, "left swipe", "广告轮播已完成一个任务，已左滑继续下一个任务。");
    }
    private void dispatchSwipe(Path path, String tag, String success) {
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 300)).build();
        Thread dispatch = new Thread(() -> {
            boolean accepted;
            try {
                accepted = dispatchGesture(gesture, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) {
                        Log.i("MarioAdDiag", tag + " completed");
                        scanHandler.post(() -> save(success));
                    }
                    @Override public void onCancelled(GestureDescription g) {
                        Log.i("MarioAdDiag", tag + " cancelled");
                    }
                }, scanHandler);
            } catch (RuntimeException e) {
                accepted = false;
            }
            boolean result = accepted;
            scanHandler.post(() -> { if (!result) Log.i("MarioAdDiag", tag + " rejected"); });
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
    /** stage 5 phase 0：任务行可见=去阅读没生效；否则在书架页定位第一本书（双帧稳定后点击）。 */
    private void handleReadingUnlockOcr(List<ReadingTaskLocator.Box> boxes, Bitmap image, long capturedAt) {
        long now = SystemClock.elapsedRealtime();
        long sinceEntry = now - (readingUnlockDeadline - 150000);
        if (readingPhase == 1) return;
        if (sinceEntry > 10000 && ReadingTaskLocator.find(boxes, image.getWidth()) != null) {
            readingStage = 0;
            readingPhase = 0;
            save("阅读页未打开，重新查找任务行。");
            return;
        }
        if (sinceEntry < 5000) return;
        ReadingTaskLocator.Box book = ReadingTaskLocator.firstBook(boxes, image.getHeight());
        StringBuilder content = new StringBuilder();
        for (ReadingTaskLocator.Box box : boxes) {
            if (box == null || box.text == null || box.text.length() < 2) continue;
            int h = image.getHeight();
            if (box.top < h * 12 / 100 || box.bottom > h * 85 / 100) continue;
            if (content.length() > 0) content.append(' ');
            content.append(box.text).append('@').append(box.top).append('-').append(box.bottom);
        }
        Log.i("MarioAdDiag", "bookshelf book=" + (book == null ? "null" : boxSummary(book))
                + " content=[" + content + "]");
        if (book == null) {
            priorBookTarget = null;
            if (++bookScans >= 2 && hasOcrText(boxes, "书架")) {
                // 书架内容识别不到但标签可见：点“书架”标签回到书架页
                ReadingTaskLocator.Box tab = null;
                for (ReadingTaskLocator.Box box : boxes) {
                    if (box == null || !"书架".equals(box.text) || box.top < image.getHeight() * 88 / 100) continue;
                    if (tab != null) { tab = null; break; }
                    tab = box;
                }
                if (tab != null && VisualTargetGuard.stable(priorBookTarget, tab, image.getWidth(), image.getHeight())) {
                    priorBookTarget = null;
                    tap(tab.left, tab.top, tab.right, tab.bottom,
                            "已点击「书架」标签，查找第一本书。", "书架标签点击被中断。");
                } else if (tab != null) {
                    priorBookTarget = tab;
                }
            }
            return;
        }
        bookScans = 0;
        if (!VisualTargetGuard.stable(priorBookTarget, book, image.getWidth(), image.getHeight())
                || now - capturedAt > 5000) {
            priorBookTarget = book;
            return;
        }
        priorBookTarget = null;
        readingPhase = 1;
        readingStartedAt = now;
        tap(book.left, book.top, book.right, book.bottom,
                "已打开书架第一本书，开始阅读 1 分钟。", "打开书籍被中断。");
    }
    /** 签到页：连续两帧确认「立即签到」再点；只认这一个文案，推广按钮一概不碰。 */
    private void handleSigninOcr(List<ReadingTaskLocator.Box> boxes) {
        if (signinTapAt > 0) return;
        // 签到已完成后，起点把该行按钮换成“领奖励”，点击后直接弹“恭喜获得 +1点币 / 知道了”。
        if (dismissKnownDialog(boxes)) { signinClaimSeen = true; signinIdleFrames = 0; return; }
        ReadingTaskLocator.Box button = SigninPolicy.findSigninButton(boxes);
        if (button != null) {
            if (++signinFrames < 2) return;
            signinFrames = 0;
            signinTapAt = SystemClock.elapsedRealtime();
            tap(button.left, button.top, button.right, button.bottom,
                    "已点击携程「立即签到」，稍后返回起点。", "携程签到点击被中断。");
            return;
        }
        signinFrames = 0;
        if (signinClaimSeen && ++signinIdleFrames >= 2) {
            readingArmed = false;
            scanHandler.removeCallbacks(readingScan);
            stopRunForeground();
            save("「去携程签到领积分」奖励已领取，全部任务结束；请在福利中心核对各项奖励。");
        }
    }
    /** 签到点击完成后切回起点，由 stage 4 的签到分支核对奖励并收尾。 */
    private void returnAfterSignin(long now) {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.qidian.QDReader");
        if (intent == null) { stopReading("无法返回起点，请手动返回。"); return; }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            readingStage = 4;
            signinFrames = 0;
            adTransitionDeadline = now + 30000;
            returnConfirmDeadline = now + 5000;
            save("携程签到已点击，正在切回起点核对任务状态。");
        } catch (RuntimeException e) { stopReading("无法自动切回起点，请手动返回。"); }
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
        if (dismissKnownDialog(boxes)) {
            // 福利中心奖励弹窗会吞掉任务入口点击；当前仍在福利中心，退回找任务阶段重新定位。
            readingNavigationAttempted = true;
            readingStage = 0;
            return;
        }
        if (AdCarouselPolicy.isContinuePrompt(boxes)) {
            handleCarouselPrompt(image);
            return;
        }
        if (AdPromptMatcher.hasReward(boxes)) {
            stopReading("广告显示已获得奖励，请手动关闭并核对任务状态。");
            return;
        }
        int width = image.getWidth(), height = image.getHeight();
        ReadingTaskLocator.Box anchor = AdPromptMatcher.findStabilityAnchor(boxes, width, height);
        ReadingTaskLocator.Box target = AdPromptMatcher.findDetails(boxes, width, height);
        Log.i("MarioAdDiag", "target=" + (target != null) + " anchor=" + (anchor != null)
                + " boxes=" + boxes.size() + " age=" + (SystemClock.elapsedRealtime() - capturedAt)
                + " prior=" + (priorAdTarget != null)
                + " stable=" + VisualTargetGuard.stableDetails(priorAdTarget, target, priorAdAnchor, anchor, width, height));
        if (target == null || anchor == null) {
            priorAdTarget = null;
            priorAdAnchor = null;
            Log.i("MarioAdDiag", AdPromptMatcher.describe(boxes, width, height));
            if (ReadingTaskLocator.find(boxes, width) != null
                    && SystemClock.elapsedRealtime() - adEntryAt > 12000) {
                // 入口点击未生效（弹窗吞点击/按钮无响应）：回到找任务阶段重新定位，最多重试 2 次。
                if (++adEntryRetries >= 3) {
                    stopReading("点击任务入口后未进入广告页，已停止自动操作。");
                } else {
                    readingNavigationAttempted = true;
                    readingStage = 0;
                }
            }
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
    private static String allText(List<ReadingTaskLocator.Box> boxes) {
        StringBuilder sb = new StringBuilder();
        for (ReadingTaskLocator.Box box : boxes) {
            if (box == null || box.text == null) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(box.text).append('@').append(box.top);
        }
        return sb.toString();
    }
    /** 轮播页“滑动继续完成”：连续两帧确认后左滑到下一支广告，并按新广告页重新识别浏览入口。 */
    private void handleCarouselPrompt(Bitmap image) {
        long now = SystemClock.elapsedRealtime();
        if (now - carouselSwipeAt < 6000) { carouselFrames = 0; return; } // 刚滑过，等下一支广告加载
        if (++carouselFrames < 2) return;
        carouselFrames = 0;
        if (carouselSwipes >= 3) {
            stopReading("广告轮播已连续左滑 3 次仍未切换到下一任务，请手动检查。");
            return;
        }
        carouselSwipes++;
        carouselSwipeAt = now;
        priorAdTarget = null;
        priorAdAnchor = null;
        adSheetFrames = 0;
        if (adFlow != null) adFlow.reset();
        readingStage = 1;
        adEntryAt = now;
        readingDeadline = now + 300000;
        swipeLeft(image.getWidth(), image.getHeight());
    }
    /** 轮播完成后的奖励弹窗：唯一“知道了”按钮，连续两帧确认后点击关闭。 */
    private boolean dismissKnownDialog(List<ReadingTaskLocator.Box> boxes) {
        ReadingTaskLocator.Box target = AdCarouselPolicy.findDismissButton(boxes);
        if (target == null) { dialogFrames = 0; return false; }
        long now = SystemClock.elapsedRealtime();
        if (dialogTapAt > 0 && now - dialogTapAt < 5000) return true; // 刚点过，等弹窗消失
        if (++dialogFrames < 2) return true;
        dialogFrames = 0;
        dialogTapAt = now;
        tap(target.left, target.top, target.right, target.bottom,
                "已关闭奖励弹窗。", "关闭奖励弹窗被中断。");
        return true;
    }
    private void inspectReward(List<ReadingTaskLocator.Box> boxes, AccessibilityNodeInfo root, Bitmap image, long capturedAt) {
        if (root == null) { rewardFrames = 0; return; }
        boolean carouselDone = AdCarouselPolicy.isCompletion(boxes);
        if (!adFlow.confirmReward() || (!AdPromptMatcher.hasReward(boxes) && !carouselDone)
                || SystemClock.elapsedRealtime() - capturedAt > 5000) { rewardFrames = 0; return; }
        if (++rewardFrames < 2) return;
        List<AccessibilityNodeInfo> close = findExact(root, "点击退出关闭视频");
        if (close.size() != 1 || !close.get(0).isVisibleToUser()) return;
        Rect rect = new Rect(); close.get(0).getBoundsInScreen(rect);
        if (rect.isEmpty() || rect.left < image.getWidth() * 3 / 4 || rect.bottom > image.getHeight() / 4) return;
        String reason = carouselDone ? "广告轮播已完成，已关闭广告；" : "广告显示已获得奖励，已尝试关闭；";
        nextTask(reason);
        tap(rect.left, rect.top, rect.right, rect.bottom,
                reason + "继续下一个任务。", "关闭广告被中断，请手动处理。");
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
            if (isSystemOverlay(next)) {
                // 通知栏/音量面板等系统浮层不是广告落地页，不能覆盖真正的应用前台记录。
                Log.i("MarioAdDiag", "overlay=" + next + " cls=" + event.getClassName());
                return;
            }
            if (!next.equals(lastForegroundPkg))
                Log.i("MarioAdDiag", "foreground=" + next + " cls=" + event.getClassName());
            lastForegroundPkg = next;
        }
    }
    private static boolean isSystemOverlay(String pkg) {
        return "com.android.systemui".equals(pkg) || "miui.systemui.plugin".equals(pkg);
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
