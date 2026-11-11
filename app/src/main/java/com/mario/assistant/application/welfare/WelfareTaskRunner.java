package com.mario.assistant.application.welfare;

import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import com.mario.assistant.application.ActionGate;
import com.mario.assistant.application.RunHost;
import com.mario.assistant.application.RunToken;
import com.mario.assistant.application.TaskRunner;
import com.mario.assistant.domain.model.Bounds;
import com.mario.assistant.domain.model.TextBlock;
import com.mario.assistant.domain.safety.ActionGuard;
import com.mario.assistant.domain.welfare.AdCarouselPolicy;
import com.mario.assistant.domain.welfare.AdFlow;
import com.mario.assistant.domain.welfare.AdPromptMatcher;
import com.mario.assistant.domain.welfare.DialogDismissPolicy;
import com.mario.assistant.domain.welfare.ReadingNavigationPolicy;
import com.mario.assistant.domain.welfare.SigninPolicy;
import com.mario.assistant.domain.welfare.TaskEntryBudget;
import com.mario.assistant.domain.welfare.VisualTargetGuard;
import com.mario.assistant.domain.welfare.WelfareTaskLocator;
import com.mario.assistant.infrastructure.a11y.Gestures;
import com.mario.assistant.infrastructure.a11y.UiTree;
import com.mario.assistant.infrastructure.system.DeviceEnvironment;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 起点福利中心任务：广告浏览、携程签到、阅读解锁，逐行走完并自动返回福利中心。 */
public final class WelfareTaskRunner implements TaskRunner {
    private static final String TAG = "MarioAdDiag";
    private static final String QIDIAN = DeviceEnvironment.QIDIAN;
    private static final int STAGE_SIGNIN = 6;
    /** 奖励弹窗唯一关闭按钮的节点原文；节点点击不依赖 OCR 坐标与合并后的文案。 */
    private static final String DISMISS_LABEL = "知道了";

    private RunHost host;
    private RunToken token;
    private ActionGate gate;
    private boolean finished;

    private long deadline;
    private int stage; // 0: task entry, 1: ad, 2: waiting for detail, 3: viewing, 4: return
    private AdFlow adFlow;
    private TextBlock priorAdTarget;
    private TextBlock priorAdAnchor;
    private int rewardFrames;
    private boolean navigationAttempted;
    private TextBlock priorReadingTarget;
    private int adSheetFrames;
    private TextBlock priorTabTarget;
    private int emptyScans;
    private int scrollAttempts;
    private boolean scrollReversed;
    private int claimedFrames;
    private long unlockDeadline;
    private int unlockAttempts;
    private boolean signinTask;
    private int signinFrames;
    private long signinTapAt;
    private boolean signinClaimSeen;
    private int signinIdleFrames;
    /** 携程签到行「领奖励」的点击次数与连续帧：签到完成后起点还要再点一次才真正到账。 */
    private int signinClaimTaps;
    private int signinClaimFrames;
    private static final int MAX_SIGNIN_CLAIMS = 2;
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
    /** 进入 stage 5（点击阅读入口）的时刻；阅读解锁的各阶段时限都以它为锚点。 */
    private long readingEntryAt;
    private TextBlock priorBookTarget;
    private int bookScans;
    private int priorWidth, priorHeight;
    private long adTransitionDeadline;
    private long returnConfirmDeadline;
    /** 同一条任务行在一次试运行内的进入预算，防止「行内状态未刷新」导致整轮反复。 */
    private final TaskEntryBudget entryBudget = new TaskEntryBudget();
    /** 本轮是广告任务行：只有它会向预算回报「是否真的走到了广告页」。 */
    private boolean adRoundTracked;
    /** 本轮是否确认进入了广告详情页（stage 2→3）；累计型任务靠它区分「有进展」和「空进」。 */
    private boolean adRoundProgressed;
    /** 起点停在阅读页/内页（没有标签栏）时按返回键的次数与连续帧计数。 */
    private int tabBacks;
    private int tabStuckFrames;
    private static final int MAX_TAB_BACKS = 2;

    @Override public String id() { return "welfare"; }

    @Override public long tickIntervalMs() { return 4000; }

    @Override public long initialDelayMs() { return 1000; }

    @Override public boolean isFinished() { return finished; }

    @Override public void onStart(RunHost host, RunToken token) {
        this.host = host;
        this.token = token;
        this.gate = new ActionGate(host.log(), token);
        adFlow = new AdFlow();
        // 串联运行时上一个任务可能把前台留在其他应用：不在起点就自行唤起
        // （单独运行时主界面已启动起点，这里只是把已有任务栈唤到前台，不改页面）。
        if (!QIDIAN.equals(host.env().foreground()) && !host.env().launch(QIDIAN, false)) {
            finish("未找到起点读书，已跳过起点任务。");
            return;
        }
        host.env().resetForeground();
        deadline = host.now() + 300000;
        log("等待起点福利中心，将打开可执行的广告任务行。");
    }

    @Override public void onTick(long now) {
        if (finished || !token.isCurrent()) return;
        if (now >= deadline) {
            stop(timeoutMessage());
            return;
        }
        String pkg;
        AccessibilityNodeInfo root = null;
        UiTree tree = null;
        if (stage == 2 || stage == 3) {
            // 前台包名以当前活动窗口为准：第三方页面上层弹出时仍可能收到起点的窗口事件，
            // 只用事件记录会把真实前台（如携程签到页）误判成起点（真机实测）。
            pkg = host.trees().activePackage();
            if (pkg.isEmpty()) pkg = host.env().foreground();
        } else {
            tree = host.trees().capture();
            root = tree.root;
            pkg = tree.packageName;
        }
        Log.i(TAG, "scan stage=" + stage + " busy=" + host.ocr().busy() + " pkg=" + pkg);
        if (stage == 2) {
            if (adFlow.observeDetail(pkg, now)) {
                stage = 3;
                adRoundProgressed = true;
                log("已进入广告详情页，等待至少 15 秒。");
            } else if (QIDIAN.equals(pkg) && !host.ocr().busy()) {
                // 疑似起点内落地页：用 OCR 确认广告底部操作区是否已消失。
                inspectScreen();
            } else if (!pkg.isEmpty()) {
                stop("广告进入未适配的应用，已停止自动操作。");
            } else if (now >= adTransitionDeadline) {
                stop("广告点击后未确认详情页打开（前台记录："
                        + (host.env().foreground().isEmpty() ? "无，若持续如此请关闭再开启无障碍服务" : host.env().foreground())
                        + "），已超时停止。");
            }
        } else if (stage == 3) {
            if (!pkg.isEmpty() && !adFlow.isDestination(pkg)) {
                stop("已离开广告详情页，已停止自动返回。");
            } else if (adFlow.shouldReturn(pkg, now)) {
                if (adFlow.isInApp()) {
                    if (host.env().back()) {
                        adFlow.returnRequested();
                        stage = 4;
                        adTransitionDeadline = now + 30000;
                        returnConfirmDeadline = 0;
                        log("已在起点内停留 15 秒，已按返回键退出详情页，等待奖励提示。");
                    } else {
                        stop("无法执行返回操作，请手动返回。");
                    }
                } else if (host.env().reopen(QIDIAN)) {
                    adFlow.returnRequested();
                    stage = 4;
                    adTransitionDeadline = now + 30000;
                    returnConfirmDeadline = now + 5000;
                    log("已请求切回起点，等待前台窗口切换。");
                } else {
                    stop("无法返回起点，请手动切回。");
                }
            }
        } else if (stage == 4) {
            boolean backInReading = QIDIAN.equals(host.env().foreground()) || QIDIAN.equals(pkg);
            if (returnConfirmDeadline > 0 && !backInReading && now >= returnConfirmDeadline) {
                stop(host.env().canDrawOverlays()
                        ? "系统拦截了后台启动起点，请检查 MIUI「后台弹出界面」权限后重试。"
                        : "系统拦截了后台启动起点：请授予「后台弹出界面」和「显示悬浮窗」后重试。");
            } else if (now >= adTransitionDeadline) {
                stop("未能确认返回后的奖励，请手动检查。");
            } else if (!host.ocr().busy() && (root != null || QIDIAN.equals(host.env().foreground()))) {
                // 返回起点后的奖励/轮播提示只能靠 OCR 判定，必须在此持续触发识别。
                inspectScreen();
            }
        } else if (stage == 5) {
            // 阅读解锁两阶段：找到书架第一本书 → 书中阅读 1 分钟 → 返回并重走导航
            if (now >= unlockDeadline) {
                stop("阅读解锁未完成，请手动检查限时任务状态。");
            } else if (readingPhase == 1) {
                if (now - readingStartedAt >= 120000 && !host.ocr().busy()) {
                    if (host.env().back()) {
                        stage = 0;
                        readingPhase = 0;
                        // 返回键落到书架页：必须重置导航标记，才能重新走 我→福利中心 链路。
                        navigationAttempted = false;
                        emptyScans = 0;
                        scrollAttempts = 0;
                        log("阅读时间已满，已退出书籍，将重新进入 我-福利中心 继续任务。");
                    } else {
                        stop("无法执行返回操作，请手动返回福利中心。");
                    }
                }
            } else if (!host.ocr().busy() && root != null && QIDIAN.equals(pkg)) {
                inspectScreen(); // OCR 驱动：任务行可见性 / 书架第一本书定位
            }
        } else if (stage == STAGE_SIGNIN) {
            // 签到页可能在携程应用内，也可能内嵌在起点；统一靠 OCR 找“立即签到”。
            if (signinTapAt > 0 && now - signinTapAt >= 5000) {
                returnAfterSignin(now);
            } else if (!host.ocr().busy()) {
                inspectScreen();
            }
        } else if (!host.ocr().busy() && root != null && QIDIAN.equals(pkg)) {
            if (stage != 0 || !openReadingCenterIfNeeded(tree)) inspectScreen();
        }
    }

    @Override public void onStop() {
        finished = true;
        if (adFlow != null) adFlow.cancel();
    }

    private void inspectScreen() {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            stop("系统不支持屏幕识别。");
            return;
        }
        final long capturedAt = host.now();
        final int width = host.env().screenWidth();
        final int height = host.env().screenHeight();
        host.ocr().recognize(boxes -> {
            if (!token.isCurrent() || finished) return;
            if (boxes == null) {
                stop("起点页面识别失败，本次未操作。");
                return;
            }
            handleOcrText(boxes, width, height, capturedAt);
        }, errorCode -> {
            if (!token.isCurrent() || finished) return;
            stop("无法读取起点页面，截图错误码：" + errorCode + "；本次未操作。");
        });
    }

    /** OCR 结果的业务判定；固定在流程线程执行。 */
    private void handleOcrText(List<TextBlock> boxes, int imageWidth, int imageHeight, long capturedAt) {
        if (finished) return;
        UiTree tree = host.trees().capture();
        if (stage == STAGE_SIGNIN) {
            // 签到页属于携程应用，必须在起点的包名过滤之前处理。
            handleSigninOcr(boxes, tree, imageWidth, imageHeight);
            return;
        }
        String currentPkg = tree.root == null ? null : tree.packageName;
        boolean qidian = currentPkg != null
                ? QIDIAN.equals(currentPkg)
                : QIDIAN.equals(host.env().foreground());
        if (!qidian) return;
        if (!AdCarouselPolicy.isContinuePrompt(boxes)) carouselFrames = 0;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null || !box.text.contains("恭喜")) continue;
            Log.i(TAG, "ocr congrats: " + allText(boxes));
            break;
        }
        for (TextBlock box : boxes) {
            if (box == null || box.text == null || !box.text.contains("知道")) continue;
            Log.i(TAG, "ocr know: " + allText(boxes));
            break;
        }
        if (stage == 1) {
            inspectAdPrompt(tree, boxes, imageWidth, imageHeight, capturedAt);
            return;
        }
        if (stage == 2) {
            handleStageTwoOcr(boxes, imageWidth, imageHeight);
            return;
        }
        if (stage == 5) {
            handleReadingUnlockOcr(boxes, imageWidth, imageHeight, capturedAt);
            return;
        }
        if (stage == 4) {
            Log.i(TAG, "stage4 boxes=" + allText(boxes));
            if (!signinTask && AdCarouselPolicy.isContinuePrompt(boxes)) {
                handleCarouselPrompt(imageWidth, imageHeight);
                return;
            }
            if (dismissKnownDialog(tree, boxes, imageWidth, imageHeight)) return;
            if (signinTask) {
                // 签到任务：签到页只负责点「立即签到」，真正的到账还要在福利中心点一次「领奖励」
                // （真机 2026-11-10 14:01 回到福利中心时该行按钮正是「领奖励」+1点币，此前直接收尾）。
                if (AdPromptMatcher.hasReward(boxes)) {
                    signinFrames = 0;
                    return;
                }
                if (currentSignin) {
                    TextBlock claim = WelfareTaskLocator.signinClaimButton(boxes, imageWidth);
                    if (claim == null) {
                        signinClaimFrames = 0;
                    } else if (++signinClaimFrames >= 2) {
                        signinClaimFrames = 0;
                        if (signinClaimTaps >= MAX_SIGNIN_CLAIMS) {
                            stop("携程签到行的「领奖励」已点击 " + signinClaimTaps
                                    + " 次仍未变成已领取，已停止；请手动确认。");
                            return;
                        }
                        if (!allow(ActionGuard.Surface.CLAIM, claim.text)) {
                            stop("签到奖励按钮「" + claim.text + "」被安全策略拒绝，已停止。");
                            return;
                        }
                        signinClaimTaps++;
                        tap(claim.left, claim.top, claim.right, claim.bottom,
                                "已点击携程签到行的「领奖励」，等待奖励弹窗。", "领取签到奖励被中断。");
                        return;
                    } else {
                        return; // 等下一帧确认按钮位置，避免误点
                    }
                }
                if (++signinFrames >= 2) {
                    if (currentSignin) {
                        finish(signinClaimTaps > 0
                                ? "「去携程签到领积分」奖励已领取，全部任务结束；请在福利中心核对。"
                                : "「去携程签到领积分」已完成，但页面上没有出现「领奖励」，请在福利中心手动确认。");
                    } else {
                        nextTask("「" + currentTaskTitle + "」已返回起点；");
                    }
                }
                return;
            }
            // 广告任务：返回福利中心连续两帧即视为本轮流程结束，继续下一个任务。
            if (WelfareTaskLocator.find(boxes, imageWidth) != null) {
                if (++returnedFrames >= 2) {
                    nextTask("「" + currentTaskTitle + "」已返回福利中心；");
                }
                return;
            }
            returnedFrames = 0;
            inspectReward(boxes, tree, imageWidth, imageHeight, capturedAt);
            return;
        }
        if (stage != 0) return;
        if (AdCarouselPolicy.isContinuePrompt(boxes)) { // 上次运行中断在广告轮播页时，先左滑继续
            handleCarouselPrompt(imageWidth, imageHeight);
            return;
        }
        if (dismissKnownDialog(tree, boxes, imageWidth, imageHeight)) return;
        WelfareTaskLocator.Row row = WelfareTaskLocator.find(boxes, imageWidth);
        if (row == null) {
            priorReadingTarget = null;
            Log.i(TAG, WelfareTaskLocator.describe(boxes, imageHeight));
            logSigninGeometry(boxes);
            if (WelfareTaskLocator.claimedSignin(boxes, imageWidth)) {
                // 携程行已是“已领取”，其余任务行也不可点：本轮任务全部完成，不必再滑动到超时。
                if (++claimedFrames >= 2) {
                    finish("「去携程签到领积分」已领取，本轮任务全部完成；请在福利中心核对各项奖励。");
                }
                return;
            }
            claimedFrames = 0;
            boolean onWelfarePage = navigationAttempted || WelfareTaskLocator.isWelfareCenter(boxes);
            if (onWelfarePage) {
                emptyScans++;
                if (emptyScans >= 2 && scrollAttempts < 6) {
                    emptyScans = 0;
                    scrollAttempts++;
                    swipe(imageWidth, imageHeight, !scrollReversed);
                } else if (emptyScans >= 2 && scrollAttempts >= 6 && !scrollReversed) {
                    // 起点可能停在福利中心底部：继续往下滑不会动，反向回扫找回任务行。
                    emptyScans = 0;
                    scrollAttempts = 0;
                    scrollReversed = true;
                    swipe(imageWidth, imageHeight, false);
                }
            } else {
                emptyScans = 0;
                attemptTabNavigation(boxes, imageWidth, imageHeight, capturedAt);
            }
            return;
        }
        emptyScans = 0;
        claimedFrames = 0;
        if (priorWidth != imageWidth || priorHeight != imageHeight
                || !VisualTargetGuard.stable(priorReadingTarget, row.button, imageWidth, imageHeight)
                || host.now() - capturedAt > 5000) {
            priorReadingTarget = row.button;
            priorWidth = imageWidth;
            priorHeight = imageHeight;
            return;
        }
        priorReadingTarget = null;
        if (entryBudget.stalled(row.title) >= TaskEntryBudget.MAX_STALLED_ENTRIES) {
            // 进去了但连续两轮都没走到广告页：任务状态确实没推进，立刻停止而不是继续重试。
            stop("「" + row.title + "」连续 " + entryBudget.stalled(row.title)
                    + " 次进入后都没有走到广告页，任务状态未推进，已停止；请手动核对。");
            return;
        }
        if (!entryBudget.tryEnter(row.title)) {
            // 累计型任务每轮推进一档，只有打到硬上限才停；一次性任务则说明状态没有刷新。
            stop("「" + row.title + "」已进入 " + entryBudget.entries(row.title)
                    + " 次（上限 " + TaskEntryBudget.maxEntries(row.title)
                    + " 次）仍未完成，已停止；请手动核对任务状态。");
            return;
        }
        if (!allow(ActionGuard.Surface.TASK_ROW, row.button.text)) {
            stop("任务行「" + row.title + "」被安全策略拒绝，已停止。");
            return;
        }
        if (row.signin) {
            // 签到类任务：点「去完成」后进入携程签到页，由 STAGE_SIGNIN 点「立即签到」再切回。
            // 该行已是「领奖励」时是「签到过但没领」，复用同一路径等待奖励弹窗即可。
            String buttonText = row.button.text == null ? "" : row.button.text.replace(" ", "");
            boolean claimOnly = buttonText.startsWith("领");
            signinTask = true;
            signinFrames = 0;
            signinTapAt = 0;
            signinClaimSeen = false;
            signinIdleFrames = 0;
            signinClaimTaps = 0;
            signinClaimFrames = 0;
            currentTaskTitle = row.title;
            currentSignin = true;
            adRoundTracked = false;
            stage = STAGE_SIGNIN;
            // 签到要跨应用跳转（起点 → 携程 → 起点），45 秒会在回跳途中超时；放到 90 秒。
            deadline = host.now() + 90000;
            tap(row.button.left, row.button.top, row.button.right, row.button.bottom,
                    claimOnly ? "已点击携程签到行的「领奖励」，等待奖励弹窗。"
                            : "已点击「" + row.title + "」，等待签到页面。",
                    "签到入口点击被中断。");
            return;
        }
        signinTask = false;
        currentSignin = false;
        if (row.goRead) {
            currentTaskTitle = "限时广告加点";
            adRoundTracked = false;
            if (unlockAttempts >= 2) {
                finish("阅读解锁未生效（已尝试 " + unlockAttempts + " 次），请手动完成后重试。");
                return;
            }
            unlockAttempts++;
            stage = 5;
            readingPhase = 0;
            priorBookTarget = null;
            bookScans = 0;
            readingEntryAt = host.now();
            unlockDeadline = readingEntryAt + 200000;
            // 阅读解锁要等页面 + 读满 2 分钟，会超过本轮 300 秒的看门狗；按阅读预算顺延，
            // 否则会在读完前一刻被全局 deadline 掐断（真机实测差 1 秒）。
            deadline = Math.max(deadline, unlockDeadline + 60000);
            tap(row.button.left, row.button.top, row.button.right, row.button.bottom,
                    "已点击「阅读1分钟」，将打开书架第一本书阅读 2 分钟。", "阅读入口点击被中断。");
            return;
        }
        stage = 1;
        adEntryAt = host.now();
        currentTaskTitle = row.title;
        adRoundTracked = true;
        adRoundProgressed = false;
        tap(row.button.left, row.button.top, row.button.right, row.button.bottom,
                "已点击「" + row.title + "」任务入口，等待广告页面。", "任务入口点击被中断。");
    }

    /**
     * 把本轮结果回报给进入预算：走到广告页算「有进展」，没走到算「空进」。
     *
     * 累计型任务（激励任务里程碑）每轮都会让任务行保持「去完成」，因此不能只看进入次数；
     * 只有连续空进才说明点击没生效、页面没打开这类真正的状态未推进。
     */
    private void recordRoundOutcome() {
        if (!adRoundTracked) return;
        if (adRoundProgressed) {
            entryBudget.recordProgress(currentTaskTitle);
        } else {
            entryBudget.recordStalled(currentTaskTitle);
        }
        adRoundTracked = false;
        adRoundProgressed = false;
    }

    /** 单个任务流程结束：回到 stage 0 继续下一个任务；滚动预算与各阶段状态重置，截止时间顺延。 */
    private void nextTask(String reason) {
        recordRoundOutcome();
        stage = 0;
        signinTask = false;
        signinFrames = 0;
        signinTapAt = 0;
        signinClaimSeen = false;
        signinIdleFrames = 0;
        signinClaimTaps = 0;
        signinClaimFrames = 0;
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
        deadline = host.now() + 300000;
        log(reason + "继续查找下一个任务。");
    }

    /** 福利中心之外且尚未进入任务页时，点击底部唯一的“我的”标签实现全自动进入我的页面。 */
    private void attemptTabNavigation(List<TextBlock> boxes, int imageWidth, int imageHeight, long capturedAt) {
        int width = imageWidth, height = imageHeight;
        if (width <= 0 || height <= 0 || navigationAttempted) return;
        boolean markerVisible = false;
        int candidates = 0;
        TextBlock tab = null;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            if (text.equals("我的账户") || text.equals("福利中心")) markerVisible = true;
            if (!"我".equals(box.text)) continue;
            if (box.left < 0 || box.right > width || box.top < height * 88 / 100 || box.top >= box.bottom) continue;
            candidates++;
            tab = box;
        }
        boolean stableNow = tab != null && VisualTargetGuard.stable(priorTabTarget, tab, width, height);
        Log.i(TAG, "tabnav attempted=" + navigationAttempted + " marker=" + markerVisible
                + " candidates=" + candidates + " tab=" + boxSummary(tab) + " prior=" + boxSummary(priorTabTarget)
                + " stable=" + stableNow);
        if (markerVisible || candidates != 1) {
            priorTabTarget = null;
            // candidates == 0 且没有任何我的页锚点：起点停在阅读页/内页，根本没有标签栏可点。
            // 只按返回键（有次数上限）把它带回主页面，返回键无效时如实停止而不是干等到超时。
            if (!markerVisible && candidates == 0) {
                if (++tabStuckFrames < 2) return;
                tabStuckFrames = 0;
                if (tabBacks >= MAX_TAB_BACKS) {
                    stop("未识别到起点标签栏，按返回键 " + MAX_TAB_BACKS + " 次仍无法回到主页面，已停止。");
                    return;
                }
                tabBacks++;
                if (host.env().back()) {
                    log("起点当前不在主页面（无标签栏），已按返回键回到主页面。");
                } else {
                    stop("无法执行返回操作，请手动切回起点主页面。");
                }
            } else {
                tabStuckFrames = 0;
            }
            return;
        }
        tabStuckFrames = 0;
        if (!ReadingNavigationPolicy.mayTapMyTab(1, true)) return;
        if (!stableNow || host.now() - capturedAt > 5000) {
            priorTabTarget = tab;
            return;
        }
        priorTabTarget = null;
        if (!allow(ActionGuard.Surface.NAVIGATION, "我")) return;
        tap(tab.left, tab.top, tab.right, tab.bottom,
                "已点击「我的」标签，前往我的页面。", "「我的」标签点击被中断。");
    }

    /** 福利中心页内找不到任务行时滑动，暴露屏外任务行；每次试运行每方向最多 6 次。 */
    private void swipe(int width, int height, boolean revealBelow) {
        if (width <= 0 || height <= 0) return;
        if (revealBelow) {
            host.gestures().swipe(width / 2, height * 3 / 4, width / 2, height / 4, 300,
                    swipeCallback("swipe", "已向上滑动福利中心，寻找下方任务行。"));
        } else {
            host.gestures().swipe(width / 2, height / 4, width / 2, height * 3 / 4, 300,
                    swipeCallback("swipe back", "已向下滑动福利中心，回扫上方任务行。"));
        }
    }

    /** 广告轮播左滑：手指从右向左，取视频区域中部，避开顶部轮播面板与底部广告卡片。 */
    private void swipeLeft(int width, int height) {
        if (width <= 0 || height <= 0) return;
        host.gestures().swipe(width * 85 / 100, height * 45 / 100, width * 15 / 100, height * 45 / 100, 300,
                swipeCallback("left swipe", "广告轮播已完成一个任务，已左滑继续下一个任务。"));
    }

    private Gestures.Callback swipeCallback(String tag, String success) {
        return new Gestures.Callback() {
            @Override public void onCompleted() {
                Log.i(TAG, tag + " completed");
                if (token.isCurrent() && !finished) log(success);
            }
            @Override public void onCancelled() {
                Log.i(TAG, tag + " cancelled");
            }
            @Override public void onRejected() {
                Log.i(TAG, tag + " rejected");
            }
        };
    }

    /** stage 5 phase 0：任务行可见=去阅读没生效；否则在书架页定位第一本书（双帧稳定后点击）。 */
    private void handleReadingUnlockOcr(List<TextBlock> boxes, int imageWidth, int imageHeight, long capturedAt) {
        long now = host.now();
        // 以「点击阅读入口」为锚点计时；此前用 unlockDeadline-150000 反推，导致进入后前 55 秒空转。
        long sinceEntry = readingEntryAt > 0 ? now - readingEntryAt : 0;
        if (readingPhase == 1) return;
        if (sinceEntry > 10000 && WelfareTaskLocator.find(boxes, imageWidth) != null) {
            stage = 0;
            readingPhase = 0;
            log("阅读页未打开，重新查找任务行。");
            return;
        }
        if (sinceEntry < 5000) return;
        TextBlock book = WelfareTaskLocator.firstBook(boxes, imageHeight);
        StringBuilder content = new StringBuilder();
        for (TextBlock box : boxes) {
            if (box == null || box.text == null || box.text.length() < 2) continue;
            int h = imageHeight;
            if (box.top < h * 12 / 100 || box.bottom > h * 85 / 100) continue;
            if (content.length() > 0) content.append(' ');
            content.append(box.text).append('@').append(box.top).append('-').append(box.bottom);
        }
        Log.i(TAG, "bookshelf book=" + (book == null ? "null" : boxSummary(book))
                + " content=[" + content + "]");
        if (book == null) {
            priorBookTarget = null;
            if (++bookScans >= 2 && hasOcrText(boxes, "书架")) {
                // 书架内容识别不到但标签可见：点“书架”标签回到书架页
                TextBlock tab = null;
                for (TextBlock box : boxes) {
                    if (box == null || !"书架".equals(box.text) || box.top < imageHeight * 88 / 100) continue;
                    if (tab != null) { tab = null; break; }
                    tab = box;
                }
                if (tab != null && allow(ActionGuard.Surface.NAVIGATION, "书架")
                        && VisualTargetGuard.stable(priorBookTarget, tab, imageWidth, imageHeight)) {
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
        if (!VisualTargetGuard.stable(priorBookTarget, book, imageWidth, imageHeight)
                || now - capturedAt > 5000) {
            priorBookTarget = book;
            return;
        }
        priorBookTarget = null;
        if (!allow(ActionGuard.Surface.NAVIGATION, book.text)) return;
        readingPhase = 1;
        readingStartedAt = now;
        tap(book.left, book.top, book.right, book.bottom,
                "已打开书架第一本书，开始阅读 1 分钟。", "打开书籍被中断。");
    }

    /** 签到页：连续两帧确认「立即签到」再点；只认这一个文案，推广按钮一概不碰。 */
    private void handleSigninOcr(List<TextBlock> boxes, UiTree tree, int imageWidth, int imageHeight) {
        if (signinTapAt > 0) return;
        // 签到已完成后，起点把该行按钮换成“领奖励”，点击后直接弹“恭喜获得 +1点币 / 知道了”。
        if (dismissKnownDialog(tree, boxes, imageWidth, imageHeight)) { signinClaimSeen = true; signinIdleFrames = 0; return; }
        TextBlock button = SigninPolicy.findSigninButton(boxes);
        if (button != null) {
            if (++signinFrames < 2) return;
            signinFrames = 0;
            if (!allow(ActionGuard.Surface.SIGNIN, button.text)) {
                stop("签到按钮「" + button.text + "」被安全策略拒绝，已停止。");
                return;
            }
            signinTapAt = host.now();
            tap(button.left, button.top, button.right, button.bottom,
                    "已点击携程「立即签到」，稍后返回起点。", "携程签到点击被中断。");
            return;
        }
        signinFrames = 0;
        if (signinClaimSeen && ++signinIdleFrames >= 2) {
            finish("「去携程签到领积分」奖励已领取，全部任务结束；请在福利中心核对各项奖励。");
        }
    }

    /** 签到点击完成后切回起点，由 stage 4 的签到分支核对奖励并收尾。 */
    private void returnAfterSignin(long now) {
        if (!host.env().reopen(QIDIAN)) {
            stop("无法返回起点，请手动返回。");
            return;
        }
        stage = 4;
        signinFrames = 0;
        adTransitionDeadline = now + 30000;
        returnConfirmDeadline = now + 5000;
        log("携程签到已点击，正在切回起点核对任务状态。");
    }

    /** stage 2 且前台仍在起点：广告底部操作区连续两帧消失即确认起点内落地页；若已回到福利中心则如实停止。 */
    private void handleStageTwoOcr(List<TextBlock> boxes, int imageWidth, int imageHeight) {
        boolean sheetPresent = AdPromptMatcher.findDisclosure(boxes) != null
                || AdPromptMatcher.findDetails(boxes, imageWidth, imageHeight) != null;
        if (sheetPresent) { adSheetFrames = 0; return; }
        if (WelfareTaskLocator.find(boxes, imageWidth) != null) {
            stop("广告点击后回到了福利中心，未打开详情页；请手动核对任务状态。");
            return;
        }
        if (++adSheetFrames >= 2 && adFlow.confirmInAppDetail()) {
            adFlow.observeDetailInApp(host.now());
            stage = 3;
            adRoundProgressed = true;
            log("已在起点内打开广告详情页，停留 15 秒后将自动返回。");
        }
    }

    private void inspectAdPrompt(UiTree tree, List<TextBlock> boxes, int imageWidth, int imageHeight, long capturedAt) {
        if (dismissKnownDialog(tree, boxes, imageWidth, imageHeight)) {
            // 福利中心奖励弹窗会吞掉任务入口点击；当前仍在福利中心，退回找任务阶段重新定位。
            navigationAttempted = true;
            stage = 0;
            return;
        }
        if (AdCarouselPolicy.isContinuePrompt(boxes)) {
            handleCarouselPrompt(imageWidth, imageHeight);
            return;
        }
        if (AdPromptMatcher.hasReward(boxes)) {
            stop("广告显示已获得奖励，请手动关闭并核对任务状态。");
            return;
        }
        int width = imageWidth, height = imageHeight;
        TextBlock anchor = AdPromptMatcher.findStabilityAnchor(boxes, width, height);
        TextBlock target = AdPromptMatcher.findDetails(boxes, width, height);
        Log.i(TAG, "target=" + (target != null) + " anchor=" + (anchor != null)
                + " boxes=" + boxes.size() + " age=" + (host.now() - capturedAt)
                + " prior=" + (priorAdTarget != null)
                + " stable=" + VisualTargetGuard.stableDetails(priorAdTarget, target, priorAdAnchor, anchor, width, height));
        if (target == null || anchor == null) {
            priorAdTarget = null;
            priorAdAnchor = null;
            Log.i(TAG, AdPromptMatcher.describe(boxes, width, height));
            if (WelfareTaskLocator.find(boxes, width) != null
                    && host.now() - adEntryAt > 12000) {
                // 入口点击未生效（弹窗吞点击/按钮无响应）：回到找任务阶段重新定位，最多重试 2 次。
                if (++adEntryRetries >= 3) {
                    stop("点击任务入口后未进入广告页，已停止自动操作。");
                } else {
                    navigationAttempted = true;
                    stage = 0;
                }
            }
            return;
        }
        if (priorWidth != width || priorHeight != height
                || !VisualTargetGuard.stableDetails(priorAdTarget, target, priorAdAnchor, anchor, width, height)
                || host.now() - capturedAt > 5000) {
            Log.i(TAG, "stable-wait prior=" + boxSummary(priorAdTarget) + " target=" + boxSummary(target)
                    + " anchor=" + boxSummary(anchor));
            priorAdTarget = target;
            priorAdAnchor = anchor;
            priorWidth = width;
            priorHeight = height;
            return;
        }
        if (!allow(ActionGuard.Surface.AD_PROMPT, target.text)) {
            stop("广告入口「" + target.text + "」被安全策略拒绝，已停止自动操作。");
            return;
        }
        adFlow.detailsRequested();
        Log.i(TAG, "dispatch details");
        stage = 2;
        adSheetFrames = 0;
        adTransitionDeadline = host.now() + 30000;
        tap(target.left, target.top, target.right, target.bottom,
                "已点击浏览入口，等待进入广告详情页。", "广告详情点击被中断。");
    }

    /** 轮播页“滑动继续完成”：连续两帧确认后左滑到下一支广告，并按新广告页重新识别浏览入口。 */
    private void handleCarouselPrompt(int imageWidth, int imageHeight) {
        long now = host.now();
        if (now - carouselSwipeAt < 6000) { carouselFrames = 0; return; } // 刚滑过，等下一支广告加载
        if (++carouselFrames < 2) return;
        carouselFrames = 0;
        if (carouselSwipes >= 3) {
            stop("广告轮播已连续左滑 3 次仍未切换到下一任务，请手动检查。");
            return;
        }
        carouselSwipes++;
        carouselSwipeAt = now;
        priorAdTarget = null;
        priorAdAnchor = null;
        adSheetFrames = 0;
        if (adFlow != null) adFlow.reset();
        stage = 1;
        adEntryAt = now;
        deadline = now + 300000;
        swipeLeft(imageWidth, imageHeight);
    }

    /**
     * 轮播完成后的奖励弹窗：唯一“知道了”按钮，连续两帧确认后点击关闭。
     *
     * 三级取舍：无障碍节点精确文本 → OCR 文案过闸门 → 居中单行按钮带的几何兜底。
     * 真机实测 OCR 会把同一行的「10点 / 知道了 / 888点」并成「10(0知道了8)8点」，
     * 文案过不了白名单、起点的弹窗又不暴露可点节点（节点查不到），此前会每帧
     * 「找到→拒绝→不点击」空转到 300 秒超时；三者都不成立时立刻停止，不再空转。
     */
    private boolean dismissKnownDialog(UiTree tree, List<TextBlock> boxes, int imageWidth, int imageHeight) {
        TextBlock target = AdCarouselPolicy.findDismissButton(boxes);
        AccessibilityNodeInfo node = null;
        if (target != null && tree != null) {
            node = tree.findClickableText(DISMISS_LABEL);
            if (node == null) node = tree.findClickableContaining(DISMISS_LABEL);
        }
        boolean ocrAllowed = target != null
                && ActionGuard.evaluate(ActionGuard.Surface.DIALOG, target.text).allowed;
        boolean centeredRow = AdCarouselPolicy.isCenteredButtonRow(target, imageWidth, imageHeight);
        if (target != null) {
            Rect nodeRect = new Rect();
            if (node != null) node.getBoundsInScreen(nodeRect);
            Log.i(TAG, "dialog ocr=" + boxSummary(target)
                    + " node=" + (node == null ? "null" : nodeRect.left + "," + nodeRect.top
                    + "-" + nodeRect.right + "," + nodeRect.bottom)
                    + " allowed=" + ocrAllowed + " centered=" + centeredRow);
        }
        DialogDismissPolicy.Action action = DialogDismissPolicy.decide(
                target != null, node != null, ocrAllowed, centeredRow);
        if (action == DialogDismissPolicy.Action.IGNORE) { dialogFrames = 0; return false; }
        long now = host.now();
        if (dialogTapAt > 0 && now - dialogTapAt < 5000) return true; // 刚点过，等弹窗消失
        if (++dialogFrames < 2) return true;
        dialogFrames = 0;
        if (action == DialogDismissPolicy.Action.STOP) {
            // 找到弹窗但按钮无法确认：停止比继续空转更符合“不确定即停止”的边界。
            stop("奖励弹窗按钮「" + target.text + "」无法确认，请手动关闭后重试。");
            return true;
        }
        if (action == DialogDismissPolicy.Action.CLICK_NODE) {
            if (!allow(ActionGuard.Surface.DIALOG, DISMISS_LABEL)) {
                stop("奖励弹窗关闭按钮被安全策略拒绝，已停止。");
                return true;
            }
            dialogTapAt = now;
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                log("已关闭奖励弹窗。");
            } else if (!rect.isEmpty()) {
                tap(rect.left, rect.top, rect.right, rect.bottom,
                        "已关闭奖励弹窗。", "关闭奖励弹窗被中断。");
            } else {
                stop("无法关闭奖励弹窗，请手动关闭后重试。");
            }
            return true;
        }
        if (action == DialogDismissPolicy.Action.CLICK_CENTERED) {
            // 文案被相邻内容粘住，但框落在居中单行按钮带内：按框中心点击。
            if (!allow(ActionGuard.Surface.DIALOG, DISMISS_LABEL)) {
                stop("奖励弹窗关闭按钮被安全策略拒绝，已停止。");
                return true;
            }
            dialogTapAt = now;
            int centerX = (target.left + target.right) / 2;
            int centerY = (target.top + target.bottom) / 2;
            tap(centerX, centerY, centerX, centerY,
                    "已关闭奖励弹窗。", "关闭奖励弹窗被中断。");
            return true;
        }
        if (!allow(ActionGuard.Surface.DIALOG, target.text)) {
            stop("奖励弹窗关闭按钮「" + target.text + "」被安全策略拒绝，已停止。");
            return true;
        }
        dialogTapAt = now;
        tap(target.left, target.top, target.right, target.bottom,
                "已关闭奖励弹窗。", "关闭奖励弹窗被中断。");
        return true;
    }

    private void inspectReward(List<TextBlock> boxes, UiTree tree, int imageWidth, int imageHeight, long capturedAt) {
        AccessibilityNodeInfo root = tree.root;
        if (root == null) { rewardFrames = 0; return; }
        boolean carouselDone = AdCarouselPolicy.isCompletion(boxes);
        if (!adFlow.confirmReward() || (!AdPromptMatcher.hasReward(boxes) && !carouselDone)
                || host.now() - capturedAt > 5000) { rewardFrames = 0; return; }
        if (++rewardFrames < 2) return;
        List<AccessibilityNodeInfo> close = tree.findExact("点击退出关闭视频");
        if (close.size() != 1 || !close.get(0).isVisibleToUser()) return;
        Rect rect = new Rect();
        close.get(0).getBoundsInScreen(rect);
        if (rect.isEmpty() || rect.left < imageWidth * 3 / 4 || rect.bottom > imageHeight / 4) return;
        String reason = carouselDone ? "广告轮播已完成，已关闭广告；" : "广告显示已获得奖励，已尝试关闭；";
        if (!allow(ActionGuard.Surface.DIALOG, "点击退出关闭视频")) return;
        nextTask(reason);
        tap(rect.left, rect.top, rect.right, rect.bottom,
                reason + "继续下一个任务。", "关闭广告被中断，请手动处理。");
    }

    private void tap(int left, int top, int right, int bottom, String success, String failure) {
        AtomicBoolean gestureDone = new AtomicBoolean();
        long dispatchedAt = host.now();
        // dispatchGesture 在后台线程调用（曾在主线程卡死且回调不返回）；回调经流程线程回流。
        host.handler().postDelayed(() -> {
            if (token.isCurrent() && !finished && !gestureDone.get())
                Log.i(TAG, "gesture callback pending "
                        + (host.now() - dispatchedAt) + "ms; flow continues");
        }, 3000);
        host.gestures().tap(new Bounds(left, top, right, bottom), 80, new Gestures.Callback() {
            @Override public void onCompleted() {
                Log.i(TAG, "gesture completed");
                gestureDone.set(true);
                if (token.isCurrent() && !finished) log(success);
            }
            @Override public void onCancelled() {
                Log.i(TAG, "gesture cancelled");
                gestureDone.set(true);
                if (token.isCurrent() && !finished) stop(failure);
            }
            @Override public void onRejected() {
                Log.i(TAG, "gesture rejected");
                if (token.isCurrent() && !finished) stop(failure);
            }
        });
    }

    private boolean openReadingCenterIfNeeded(UiTree tree) {
        if (navigationAttempted) return false;
        List<AccessibilityNodeInfo> entries = tree.findExact("福利中心");
        if (entries.size() != 1) return false;
        AccessibilityNodeInfo label = entries.get(0);
        AccessibilityNodeInfo item = label.getParent();
        Rect bounds = new Rect();
        if (item != null) item.getBoundsInScreen(bounds);
        boolean eligible = item != null && label.isVisibleToUser() && item.isVisibleToUser()
                && item.isEnabled() && item.isClickable() && !bounds.isEmpty()
                && "com.qidian.QDReader:id/layoutItem".equals(item.getViewIdResourceName());
        if (!ReadingNavigationPolicy.mayOpen(tree.packageName,
                tree.hasText("我的账户"), tree.hasText("活动中心"), entries.size(), eligible)) return false;
        if (!allow(ActionGuard.Surface.NAVIGATION, "福利中心")) return false;
        navigationAttempted = true;
        if (!item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            stop("无法打开起点福利中心，请手动进入后重试。");
        } else {
            log("已尝试进入起点福利中心，等待任务页面。");
        }
        return true;
    }

    private String timeoutMessage() {
        switch (stage) {
            case 1: return "已进入广告页，但未确认可点击的浏览入口，已超时停止。";
            case 2: return "广告点击后未确认详情页打开，已超时停止。";
            case 3: return "广告停留期间未能确认返回时机，已超时停止。";
            case 4: return "返回起点后未确认奖励提示，已超时停止。";
            case 5: return "阅读解锁未完成，已超时停止。";
            case STAGE_SIGNIN: return "未在签到页找到「立即签到」，已超时停止。";
            default: return "未找到可执行的广告任务入口，已超时停止。";
        }
    }

    private void finish(String result) {
        if (finished) return;
        finished = true;
        if (adFlow != null) adFlow.cancel();
        log(result);
        host.stopForeground();
    }

    private void stop(String reason) {
        finish(reason);
    }

    private boolean allow(ActionGuard.Surface surface, String label) {
        return gate != null && gate.allow(surface, label);
    }

    private void log(String message) {
        host.log().save(message);
    }

    /** 携程行定位失败时的原始坐标诊断（输出标题整条带内的所有 OCR 框）。 */
    private static void logSigninGeometry(List<TextBlock> boxes) {
        TextBlock anchor = null;
        for (TextBlock box : boxes) {
            if (box != null && box.text != null && box.text.contains("携程") && box.text.contains("签到")) { anchor = box; break; }
        }
        if (anchor == null) return;
        StringBuilder sb = new StringBuilder("signin-geom");
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            if (Math.abs(box.top - anchor.top) > 120) continue;
            sb.append(' ').append(box.text).append('@').append(box.left).append(',').append(box.top)
                    .append('-').append(box.right).append(',').append(box.bottom);
        }
        Log.i(TAG, sb.toString());
    }

    private static boolean hasOcrText(List<TextBlock> boxes, String expected) {
        for (TextBlock box : boxes) {
            if (box != null && expected.equals(box.text)) return true;
        }
        return false;
    }

    private static String boxSummary(TextBlock box) {
        return box == null ? "null" : box.text + "@" + box.left + "," + box.top + "-" + box.right + "," + box.bottom;
    }

    private static String allText(List<TextBlock> boxes) {
        StringBuilder sb = new StringBuilder();
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(box.text).append('@').append(box.top);
        }
        return sb.toString();
    }
}
