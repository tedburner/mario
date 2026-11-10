package com.mario.assistant.application.forest;

import android.view.accessibility.AccessibilityNodeInfo;
import com.mario.assistant.application.ActionGate;
import com.mario.assistant.application.RunHost;
import com.mario.assistant.application.RunToken;
import com.mario.assistant.application.TaskRunner;
import com.mario.assistant.domain.forest.ForestMilestone;
import com.mario.assistant.domain.forest.ForestTask;
import com.mario.assistant.domain.forest.ForestTaskParser;
import com.mario.assistant.domain.forest.ForestTaskProgress;
import com.mario.assistant.domain.model.Bounds;
import com.mario.assistant.domain.model.TextBlock;
import com.mario.assistant.domain.model.UiNode;
import com.mario.assistant.domain.safety.ActionGuard;
import com.mario.assistant.infrastructure.a11y.UiTree;
import com.mario.assistant.infrastructure.system.DeviceEnvironment;
import java.util.List;

/** 蚂蚁森林奖励页任务：动态解析任务行，逐个浏览/领取并自动回到奖励页。 */
public final class ForestTaskRunner implements TaskRunner {
    public enum Mode { NORMAL, DEBUG_RECOVER, DEBUG_COLLECT }

    private static final String ALIPAY = DeviceEnvironment.ALIPAY;
    private static final long BROWSE_GRACE_MS = 2000;
    /**
     * 安全网：只在奖励页不断生成新任务（如双 11 会场轮换行）时兜底。
     * 正常收尾靠「没有可执行任务 + 页面稳定 + 滑到底」，不靠次数。
     */
    private static final int MAX_ATTEMPTS = 100;
    /** 里程碑最多点这么多次，防止按钮文案不变时死循环。 */
    private static final int MAX_MILESTONE_TAPS = 6;
    private static final long MILESTONE_SETTLE_MS = 2000;

    private final Mode mode;
    private RunHost host;
    private RunToken token;
    private ActionGate gate;
    private boolean finished;

    private int stage; // 0=找任务, 1=点击后观察, 2=等待返回奖励页, 3=抽奖页识别, 4=第三方滞留后恢复
    private long deadline;
    private long stageAt;
    private String taskTitle;
    private String taskCta;
    private String targetPackage;
    private boolean observeThirdParty;
    private boolean usedGesture;
    private boolean energyProbed;
    private boolean energyCollected;
    private boolean returnBacked;
    private boolean claimMode;
    private int returnBacks;
    private int recoverStep;
    private boolean recoverBacked;
    private long recoverProbeAt;
    private int recoverRelaunch;
    private int recoverBacks;
    private int recoverCloses;
    private int lotteryStep; // 0=找「立即抽奖」, 1=找「收下奖励」, 2=已收下
    private int lotteryAttempts;
    private int browseSeconds;
    private int tapLeft, tapTop, tapRight, tapBottom;
    private int attempts;
    private int scrolls;
    private int emptyScans;
    private int milestoneTaps;
    private long milestoneAt;
    private boolean drawMode;
    private int offRewardScans;
    private String lastSignature;
    private String priorKey;
    private boolean lastClickWasClaim;
    private final ForestTaskProgress progress = new ForestTaskProgress();

    public ForestTaskRunner(Mode mode) {
        this.mode = mode;
    }

    @Override public String id() { return "forest"; }

    @Override public long tickIntervalMs() { return 1200; }

    @Override public long initialDelayMs() { return mode == Mode.NORMAL ? 800 : 0; }

    @Override public boolean isFinished() { return finished; }

    @Override public void onStart(RunHost host, RunToken token) {
        this.host = host;
        this.token = token;
        this.gate = new ActionGate(host.log(), token);
        long now = host.now();
        if (mode == Mode.DEBUG_RECOVER) {
            taskTitle = "(调试恢复)";
            deadline = now + 2 * 60 * 1000;
            beginRecover(now);
            return;
        }
        if (mode == Mode.DEBUG_COLLECT) {
            taskTitle = "(调试收能量)";
            targetPackage = ALIPAY;
            observeThirdParty = false;
            browseSeconds = 12;
            deadline = now + 2 * 60 * 1000;
            stage = 1;
            stageAt = now;
            usedGesture = false;
            energyProbed = false;
            energyCollected = false;
            probeEnergyPage();
            return;
        }
        deadline = now + 15 * 60 * 1000;
        log("森林任务已启动：请停留在蚂蚁森林-奖励页。");
    }

    @Override public void onTick(long now) {
        if (finished || !token.isCurrent()) return;
        if (now >= deadline) {
            stop("森林任务超时停止；" + progress.summary() + "。");
            return;
        }
        try {
            if (stage == 0) scanRewardPage(now);
            else if (stage == 1) observeAfterTap(now);
            else if (stage == 2) waitReturn(now);
            else if (stage == 3) waitClaimPage(now);
            else if (stage == 4) waitRecover(now);
        } catch (RuntimeException e) {
            log("forest error " + e);
            stop("森林任务异常停止：" + e.getMessage());
        }
    }

    @Override public void onStop() {
        finished = true;
    }

    /** 奖励页：每轮重新解析任务行，动态选择下一个可执行任务；完成一个再回来重新解析。 */
    private void scanRewardPage(long now) {
        UiTree tree = host.trees().capture();
        if (tree.root == null) { log("森林：等待支付宝页面。"); return; }
        if (!ALIPAY.equals(tree.packageName)) {
            log("森林：等待支付宝前台（当前 " + (tree.packageName.isEmpty() ? "未知" : tree.packageName) + "）。");
            return;
        }
        if (!isRewardPage(tree)) {
            // 停在森林主页/内页时不能一直刷「请停留」直到超时，主动去点「领奖」重开弹窗
            if (++offRewardScans >= 5) {
                offRewardScans = 0;
                beginRecover(now);
                log("森林：连续未识别到奖励弹窗，正在重新打开。");
            } else {
                log("森林：请停留在蚂蚁森林-奖励页。");
            }
            return;
        }
        offRewardScans = 0;
        if (dismissForestDialog(tree)) return;
        if (dismissForestBlocker(tree)) return;
        if (tree.hasText("立即抽奖") || tree.hasText("收下奖励")) {
            stage = 3;
            stageAt = now;
            lotteryStep = 0;
            lotteryAttempts = 0;
            log("森林：奖励页上出现抽奖弹窗，先处理抽奖。");
            return;
        }
        if (claimMilestone(tree, now)) return;
        List<ForestTask> tasks = ForestTaskParser.parse(tree.nodes, tree.width, tree.height,
                host.env()::isInstalled);
        log("forest tasks=" + ForestTaskParser.describe(tasks));
        // 之前判过「浏览后仍可点」或「行已消失」的任务，一旦行上出现领取/抽奖入口，就该重新做一次
        for (ForestTask task : tasks) {
            if (task.kind != ForestTask.Kind.CLAIM && task.kind != ForestTask.Kind.DRAW) continue;
            if (!progress.isSettled(task.title)) continue;
            if (progress.claimFailures(task.title) >= ForestTaskProgress.MAX_CLAIM_FAILURES) continue;
            progress.clearSettled(task.title);
            log("森林：「" + task.title + "」已出现「" + task.cta + "」，重新纳入执行。");
        }
        if (taskTitle != null) {
            String title = taskTitle;
            ForestTask same = findTask(tasks, title);
            boolean stillActionable = same != null && same.actionable();
            if (!stillActionable) {
                progress.markDone(title);
                log("「" + title + "」已完成，继续下一个任务。");
            } else if (lastClickWasClaim) {
                // 点过领取但行还是可点：重试有限次，超限如实记为「未确认」，不再空转
                int failures = progress.recordClaimFailure(title);
                if (progress.canRetryClaim(title)) {
                    log("「" + title + "」领取后仍可点击，准备重试（第 " + failures + " 次）。");
                } else {
                    progress.markStuck(title);
                    log("「" + title + "」领取 " + failures + " 次仍可点击，已跳过，收尾时列出。");
                }
            } else if (same.kind == ForestTask.Kind.CLAIM || same.kind == ForestTask.Kind.DRAW) {
                // 浏览任务完成后行会变成「立即领取」或「去抽奖」，这一跳是正常后续动作，不算失败
                log("「" + title + "」已变成「" + same.cta + "」，继续执行后续动作。");
            } else {
                progress.markStuck(title);
                log("「" + title + "」浏览后仍可点击，未确认完成，已跳过。");
            }
            taskTitle = null;
            claimMode = false;
            lastClickWasClaim = false;
        }
        if (attempts >= MAX_ATTEMPTS) {
            stop("森林：已点击 " + attempts + " 次，达到 " + MAX_ATTEMPTS + " 次安全网上限；"
                    + progress.summary() + "。");
            return;
        }
        ForestTask next = null;
        for (ForestTask task : tasks) {
            if (task.actionable() && !progress.isSettled(task.title)) { next = task; break; }
        }
        if (next == null) {
            String signature = signature(tasks);
            if (signature.equals(lastSignature)) {
                if (++emptyScans >= 2) {
                    stop("森林：奖励页没有更多可执行任务；" + progress.summary() + "，共点击 "
                            + attempts + " 次。");
                    return;
                }
            } else {
                emptyScans = 0;
                lastSignature = signature;
            }
            if (scrolls >= 8) {
                stop("森林：已滑到奖励页底部；" + progress.summary() + "，共点击 "
                        + attempts + " 次。");
                return;
            }
            scrolls++;
            swipe(true);
            log("森林：已向上滑动，继续查找下方任务。");
            return;
        }
        emptyScans = 0;
        lastSignature = signature(tasks);
        String key = next.title + "|" + next.cta + "|" + next.left + "," + next.top;
        if (!key.equals(priorKey)) {
            priorKey = key;
            log("森林：已定位「" + next.title + "」，等待稳定。");
            return;
        }
        AccessibilityNodeInfo node = tree.live(next.ctaId);
        if (node == null) {
            progress.markStuck(next.title);
            log("森林：「" + next.title + "」节点已失效，已跳过。");
            return;
        }
        if (!allow(ActionGuard.Surface.TASK_ROW, next.cta)) {
            progress.markStuck(next.title);
            log("森林：「" + next.title + "」被安全策略拒绝，已跳过。");
            return;
        }
        attempts++;
        taskTitle = next.title;
        taskCta = next.cta;
        targetPackage = next.targetPackage;
        observeThirdParty = next.observeThirdParty;
        claimMode = next.kind == ForestTask.Kind.CLAIM;
        drawMode = next.kind == ForestTask.Kind.DRAW;
        lastClickWasClaim = claimMode;
        browseSeconds = next.browseSeconds();
        tapLeft = next.left;
        tapTop = next.top;
        tapRight = next.right;
        tapBottom = next.bottom;
        usedGesture = false;
        energyProbed = false;
        energyCollected = false;
        stage = 1;
        stageAt = now;
        priorKey = null;
        if (!host.uiActions().clickNodeOrParent(node)) {
            usedGesture = true;
            tapForest(next.left, next.top, next.right, next.bottom);
        }
        log("森林：已点击「" + next.title + "」，等待页面跳转。");
    }

    /**
     * 「今日累计奖励」里程碑（+40g/+60g/+100g）先领掉。
     * 它们不是独立的任务行，也不会因为任务做满就自动入账，必须点一次。
     */
    private boolean claimMilestone(UiTree tree, long now) {
        if (milestoneTaps >= MAX_MILESTONE_TAPS) return false;
        if (now - milestoneAt < MILESTONE_SETTLE_MS) return true;
        List<UiNode> pending = ForestMilestone.claimable(tree.nodes);
        if (pending.isEmpty()) return false;
        UiNode target = pending.get(0);
        if (!allow(ActionGuard.Surface.TASK_ROW, "立即领取")) return false;
        milestoneTaps++;
        milestoneAt = now;
        log("森林：发现未领取的里程碑奖励「" + target.text + "」，正在领取。");
        tapForest(target.left, target.top, target.right, target.bottom);
        return true;
    }

    /** 点击后观察前台：应用商店/下载页立即返回并跳过；第三方或支付宝内页停留浏览时长再返回。 */
    private void observeAfterTap(long now) {
        long elapsed = now - stageAt;
        if (elapsed < 1500) return;
        String pkg = host.env().foreground();
        UiTree tree = host.trees().capture();
        boolean rewardPage = ALIPAY.equals(pkg) && tree.root != null && isRewardPage(tree);
        log("forest observe task=" + taskTitle + " target=" + targetPackage
                + " observe=" + observeThirdParty + " pkg=" + pkg + " elapsed=" + elapsed);
        if (drawMode) {
            if (elapsed < 2200) return;
            if (ALIPAY.equals(pkg) || pkg.isEmpty()) {
                drawMode = false;
                claimMode = false;
                stage = 3;
                stageAt = now;
                lotteryStep = 0;
                lotteryAttempts = 0;
                log("森林：「" + taskTitle + "」已点开抽奖，正在识别抽奖页。");
                return;
            }
            if (elapsed >= browseSeconds * 1000L + BROWSE_GRACE_MS) {
                if (host.env().back() || tapMiniAppClose()) {
                    drawMode = false;
                    beginReturn(now);
                    log("森林：抽奖拉到「" + pkg + "」，已返回奖励页继续。");
                } else {
                    drawMode = false;
                    beginRecover(now);
                }
            }
            return;
        }
        if (claimMode) {
            // 领取后可能进入抽奖页：交给 OCR 分支识别「立即抽奖→收下奖励」
            if (elapsed < 2500) return;
            claimMode = false;
            stage = 3;
            stageAt = now;
            lotteryStep = 0;
            lotteryAttempts = 0;
            return;
        }
        if (pkg.isEmpty()) {
            if (elapsed >= 8000) {
                stage = 0;
                stageAt = now;
                priorKey = null;
                log("森林：「" + taskTitle + "」未观察到前台变化，已跳过。");
            }
            return;
        }
        if (DeviceEnvironment.isAppMarket(pkg) || (!rewardPage && tree.root != null && looksLikeInstallPage(tree))) {
            host.env().back();
            stage = 0;
            stageAt = now;
            priorKey = null;
            log("森林：「" + taskTitle + "」落在应用商店/下载页，已返回并跳过。");
            return;
        }
        if (ALIPAY.equals(pkg)) {
            if (elapsed < 4000) return;
            if (rewardPage) {
                if (!usedGesture && stillAtSameSpot(tree)
                        && allow(ActionGuard.Surface.TASK_ROW, taskCta)) {
                    usedGesture = true;
                    stageAt = now;
                    tapForest(tapLeft, tapTop, tapRight, tapBottom);
                    log("森林：「" + taskTitle + "」无障碍点击未跳转，改用坐标点击重试。");
                    return;
                }
                stage = 0;
                stageAt = now;
                priorKey = null;
                log("森林：「" + taskTitle + "」点击后仍停留在奖励页，已跳过。");
                return;
            }
            if (elapsed >= browseSeconds * 1000L + BROWSE_GRACE_MS) {
                if (host.env().back() || tapMiniAppClose()) {
                    beginReturn(now);
                    log("森林：已在支付宝任务页停留 " + browseSeconds + " 秒，正在返回奖励页。");
                } else {
                    beginRecover(now);
                    log("森林：任务页无法返回，正在重新打开奖励页。");
                }
                return;
            }
            if (!energyProbed && elapsed > 2500) probeEnergyPage();
            return;
        }
        if (elapsed >= browseSeconds * 1000L + BROWSE_GRACE_MS) {
            if (host.env().back() || tapMiniAppClose()) {
                beginReturn(now);
                log("森林：已在「" + pkg + "」停留 " + browseSeconds + " 秒，正在返回。");
            } else {
                beginRecover(now);
                log("森林：无法从「" + pkg + "」返回，正在重新打开支付宝。");
            }
        }
    }

    /** 「找能量」好友页：只点「一键收」，浇水/道具/留言等会消耗资源的按钮一律不碰。 */
    private void probeEnergyPage() {
        if (host.ocr().busy()) return;
        energyProbed = true;
        host.ocr().recognize(boxes -> {
            if (!token.isCurrent() || stage != 1 || boxes == null || energyCollected) return;
            TextBlock collect = findOcr(boxes, "一键收");
            if (collect == null) return;
            if (!allow(ActionGuard.Surface.ENERGY_PAGE, collect.text)) return;
            energyCollected = true;
            tapForest(collect.left, collect.top, collect.right, collect.bottom);
            log("森林：已识别好友能量页，正在点击「一键收」。");
        }, null);
    }

    private void waitReturn(long now) {
        String pkg = host.env().foreground();
        long elapsed = now - stageAt;
        if (ALIPAY.equals(pkg)) {
            UiTree tree = host.trees().capture();
            if (tree.root != null && isRewardPage(tree)) {
                stage = 0;
                stageAt = now;
                priorKey = null;
                scrolls = 0;
                emptyScans = 0;
                log("森林：已返回奖励页，继续查找下一个任务。");
                return;
            }
            if (elapsed > 8000 && !returnBacked) {
                returnBacked = true;
                if (host.env().back()) {
                    stageAt = now;
                    log("森林：正在返回奖励页。");
                } else {
                    beginRecover(now);
                }
                return;
            }
            if (elapsed > 8000 && returnBacked) {
                // 返回后停在森林主页/其他内页：交给通用恢复，OCR 点「领奖励」重开奖励弹窗
                beginRecover(now);
            }
            return;
        }
        // 第三方弹窗可能连吃多次 BACK（如淘宝直播落到小游戏），两次后直接重新唤起支付宝
        if (returnBacks >= 2) {
            beginRecover(now);
            return;
        }
        if (elapsed > 6000) {
            returnBacks++;
            if (host.env().back()) {
                stageAt = now;
                log("森林：仍在「" + pkg + "」，再次返回（第 " + returnBacks + " 次）。");
            } else {
                beginRecover(now);
            }
        }
    }

    private void beginReturn(long now) {
        stage = 2;
        stageAt = now;
        returnBacked = false;
        returnBacks = 0;
    }

    private void beginRecover(long now) {
        String pkg = host.env().foreground();
        stage = 4;
        stageAt = now;
        recoverStep = 0;
        recoverBacked = false;
        recoverProbeAt = now;
        recoverRelaunch = 0;
        recoverBacks = 0;
        returnBacks = 0;
        if (ALIPAY.equals(pkg)) {
            // 支付宝内部页面（如领取后跳进的「背包」）：无需重启，OCR/返回回到森林主页
            log("森林：当前停留在支付宝内部页面，正在尝试回到森林主页。");
            return;
        }
        host.env().home();
        boolean launched = host.env().launch(ALIPAY, false);
        log("forest recover pkg=" + pkg + " launched=" + launched);
        if (launched) {
            log("森林：「" + (targetPackage != null ? targetPackage : pkg)
                    + "」滞留过久，正在重新打开支付宝回到奖励页。");
        } else {
            stop("森林：无法重新打开支付宝，请手动进入奖励页。");
        }
    }

    /** 回到森林主页：OCR 找「领奖励」；背包等内页先返回一次；支付宝首页再点「蚂蚁森林」。 */
    private void waitRecover(long now) {
        long elapsed = now - stageAt;
        if (elapsed > 45000) {
            stop("森林：无法自动回到奖励页，请手动打开。");
            return;
        }
        String pkg = host.env().foreground();
        if (!ALIPAY.equals(pkg)) {
            // 支付宝可能停在跳转页/首页动画，辅助返回一次；仍不到前台就再唤起
            if (elapsed > 5000 && !recoverBacked) {
                recoverBacked = true;
                host.env().back();
                log("森林：等待支付宝回到前台。");
            } else if (elapsed > 8000 + recoverRelaunch * 8000L && recoverRelaunch < 3) {
                recoverRelaunch++;
                if (host.env().launch(ALIPAY, false)) {
                    log("森林：支付宝未到前台，正在再次唤起（第 " + recoverRelaunch + " 次）。");
                } else {
                    stop("森林：无法重新打开支付宝，请手动进入奖励页。");
                }
            }
            return;
        }
        if (recoverStep == 0) {
            if (host.ocr().busy() || now - recoverProbeAt < 1800) return;
            recoverProbeAt = now;
            host.ocr().recognize(boxes -> {
                if (!token.isCurrent() || stage != 4 || recoverStep != 0) return;
                long at = host.now();
                if (isForestSheetOcr(boxes)) {
                    stage = 0;
                    stageAt = at;
                    priorKey = null;
                    log("森林：已自动回到奖励页，继续查找任务。");
                    return;
                }
                // 主页上的入口文案是「领奖」，奖励弹窗里才是「领奖励」；用「领奖」兼容两者
                TextBlock entry = findOcr(boxes, "领奖");
                if (entry != null && allow(ActionGuard.Surface.NAVIGATION, entry.text)) {
                    recoverStep = 2;
                    stageAt = at;
                    tapForest(entry.left, entry.top, entry.right, entry.bottom);
                    log("森林：已识别「领奖励」，正在重新打开奖励弹窗。");
                    return;
                }
                TextBlock claim = findOcr(boxes, "返回领奖");
                if (claim != null && allow(ActionGuard.Surface.NAVIGATION, claim.text)) {
                    tapForest(claim.left, claim.top, claim.right, claim.bottom);
                    log("森林：已识别「返回领奖」，正在退回任务页。");
                    return;
                }
                // 只有确认在支付宝首页时才点入口；背包等内页背后的旧节点不可信
                boolean alipayHome = hasOcr(boxes, "扫一扫") || hasOcr(boxes, "收付款")
                        || hasOcr(boxes, "卡包");
                if (alipayHome) {
                    UiTree home = host.trees().capture();
                    AccessibilityNodeInfo icon = home.findClickableText("蚂蚁森林");
                    if (icon != null && allow(ActionGuard.Surface.NAVIGATION, "蚂蚁森林")
                            && host.uiActions().clickNodeOrParent(icon)) {
                        recoverProbeAt = at;
                        log("森林：已点击「蚂蚁森林」入口，等待森林主页。");
                        return;
                    }
                }
                long sinceStart = at - stageAt;
                if (recoverCloses < 3 && sinceStart > 4000 + recoverCloses * 3000L
                        && tapMiniAppClose()) {
                    recoverCloses++;
                    recoverProbeAt = at;
                    log("森林：已点击内页右上角「关闭」。");
                    return;
                }
                if (recoverBacks < 3 && sinceStart > 6000 + recoverBacks * 6000L) {
                    recoverBacks++;
                    recoverProbeAt = at;
                    host.env().back();
                    log("森林：当前页面不是森林主页，返回（第 " + recoverBacks + " 次）。");
                } else if (recoverBacks >= 3 && sinceStart > 24000 && recoverRelaunch < 1) {
                    recoverRelaunch++;
                    recoverProbeAt = at;
                    host.env().home();
                    host.env().launch(ALIPAY, true);
                    recoverBacks = 0;
                    log("森林：内页多次返回无效，正在重新唤起支付宝。");
                }
            }, null);
            return;
        }
        if (elapsed < 2000) return;
        UiTree tree = host.trees().capture();
        if (tree.root != null && isRewardPage(tree)) {
            stage = 0;
            stageAt = now;
            priorKey = null;
            log("森林：已重新打开奖励页，继续查找任务。");
            return;
        }
        if (elapsed > 12000) {
            if (host.env().back()) {
                recoverStep = 0;
                recoverBacked = false;
                recoverProbeAt = now;
                stageAt = now;
                log("森林：奖励弹窗未出现，正在重试。");
            } else {
                stop("森林：无法自动回到奖励页，请手动打开。");
            }
        }
    }

    /**
     * 领取奖励后可能落在画布渲染的抽奖页：OCR 找「立即抽奖」，抽中后找「收下奖励」。
     * 只有 OCR 读到奖励弹窗标记才回到找任务阶段，避免在遮挡页上误点列表。
     */
    private void waitClaimPage(long now) {
        if (host.ocr().busy()) return;
        long elapsed = now - stageAt;
        if (elapsed < 1500) return;
        String pkg = host.env().foreground();
        // 领取后直接拉起第三方（如淘宝直播）：按浏览处理，不去找抽奖页
        if (!pkg.isEmpty() && !ALIPAY.equals(pkg)) {
            if (DeviceEnvironment.isAppMarket(pkg)) {
                host.env().back();
                stage = 0;
                stageAt = now;
                priorKey = null;
                log("森林：「" + taskTitle + "」进入应用商店，已返回并跳过。");
                return;
            }
            if (elapsed >= browseSeconds * 1000L) {
                if (host.env().back()) {
                    beginReturn(now);
                    log("森林：「" + taskTitle + "」已在「" + pkg + "」浏览完成，正在返回。");
                } else {
                    beginRecover(now);
                }
            }
            return;
        }
        if (lotteryStep == 0 && lotteryAttempts >= 5 && elapsed > 12000) {
            // 抽奖页认不出来时不能整轮停掉：记一次失败，退回奖励页继续做其他任务。
            int fails = progress.recordClaimFailure(taskTitle);
            if (fails >= ForestTaskProgress.MAX_CLAIM_FAILURES) {
                progress.markStuck(taskTitle);
                log("森林：「" + taskTitle + "」抽奖页 " + fails + " 次无法识别，已跳过。");
            } else {
                log("森林：「" + taskTitle + "」抽奖页无法识别，正在返回奖励页重试。");
            }
            beginRecover(now);
            return;
        }
        lotteryAttempts++;
        host.ocr().recognize(boxes -> {
            if (!token.isCurrent() || stage != 3) return;
            long at = host.now();
            if (lotteryStep == 2) {
                if (isForestSheetOcr(boxes)) {
                    stage = 0;
                    stageAt = at;
                    priorKey = null;
                    log("森林：「" + taskTitle + "」抽奖奖励已收下，继续下一个任务。");
                    return;
                }
                if (elapsed > 4000) {
                    // 收下奖励后可能跳进「背包」等内部页：直接走通用恢复，先返回再重开奖励弹窗
                    beginRecover(at);
                    log("森林：已收下奖励但未回到奖励页，正在重新打开奖励页。");
                }
                return;
            }
            TextBlock draw = findOcr(boxes, "立即抽奖");
            if (draw != null && lotteryStep == 0
                    && allow(ActionGuard.Surface.DIALOG, draw.text)) {
                lotteryStep = 1;
                stageAt = at;
                lotteryAttempts = 0;
                tapForest(draw.left, draw.top, draw.right, draw.bottom);
                log("森林：抽奖页已识别，正在点击「立即抽奖」。");
                return;
            }
            TextBlock collect = findOcr(boxes, "收下奖励");
            if (collect != null && allow(ActionGuard.Surface.DIALOG, collect.text)) {
                lotteryStep = 2;
                stageAt = at;
                lotteryAttempts = 0;
                tapForest(collect.left, collect.top, collect.right, collect.bottom);
                log("森林：抽奖结果已识别，正在收取奖励。");
                return;
            }
            TextBlock know = findOcr(boxes, "知道了");
            if (know != null && allow(ActionGuard.Surface.DIALOG, know.text)) {
                tapForest(know.left, know.top, know.right, know.bottom);
                stage = 0;
                stageAt = at;
                priorKey = null;
                log("森林：「" + taskTitle + "」奖励已触发。");
                return;
            }
            if (isForestSheetOcr(boxes)) {
                stage = 0;
                stageAt = at;
                priorKey = null;
                log("森林：「" + taskTitle + "」奖励已触发。");
                return;
            }
            log("forest stage3 step=" + lotteryStep + " ocr=" + ocrSummary(boxes));
        }, null);
    }

    private void tapForest(int left, int top, int right, int bottom) {
        host.gestures().tap(new Bounds(left, top, right, bottom), 80, new com.mario.assistant.infrastructure.a11y.Gestures.Callback() {
            @Override public void onCompleted() {
                log("forest gesture completed");
            }
            @Override public void onCancelled() {
                if (token.isCurrent()) stop("森林：任务入口点击被中断。");
            }
            @Override public void onRejected() {
                if (token.isCurrent()) stop("森林：任务入口点击被拒绝。");
            }
        });
    }

    private void swipe(boolean revealBelow) {
        int width = host.env().screenWidth();
        int height = host.env().screenHeight();
        if (revealBelow) {
            host.gestures().swipe(width / 2, height * 3 / 4, width / 2, height / 4, 350, NO_OP);
        } else {
            host.gestures().swipe(width / 2, height / 4, width / 2, height * 3 / 4, 350, NO_OP);
        }
    }

    private final com.mario.assistant.infrastructure.a11y.Gestures.Callback NO_OP =
            new com.mario.assistant.infrastructure.a11y.Gestures.Callback() {
                @Override public void onCompleted() { }
                @Override public void onCancelled() { }
                @Override public void onRejected() { }
            };

    /** 小游戏页（XRiverLite）会吞掉返回键，标题栏右上角的「关闭」是唯一可用出口。 */
    private boolean tapMiniAppClose() {
        AccessibilityNodeInfo close = host.trees().capture().findCloseButton();
        return close != null && allow(ActionGuard.Surface.DIALOG, "关闭")
                && host.uiActions().clickNodeOrParent(close);
    }

    /** 奖励弹窗可能在列表顶部或滚动后：标题或任意任务行按钮出现即认定为奖励页。 */
    private static boolean isRewardPage(UiTree tree) {
        // 「我的活力值」在森林主页同样存在，不能作为奖励页标记
        return tree.hasText("关闭奖励弹窗") || tree.hasText("今日累计奖励")
                || tree.hasText("任务进度:") || tree.hasText("更多任务，敬请期待");
    }

    /** 领取/浏览后偶发弹窗（如「给TA们共浇水」）会挡住任务列表，安全返回即可。 */
    private boolean dismissForestBlocker(UiTree tree) {
        if (!tree.hasText("给TA们共浇水")) return false;
        if (host.env().back()) log("森林：已关闭阻挡弹窗。");
        return true;
    }

    private boolean dismissForestDialog(UiTree tree) {
        for (AccessibilityNodeInfo node : tree.findExact("知道了")) {
            if (!node.isVisibleToUser() || !node.isEnabled()) continue;
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.isEmpty()) continue;
            if (!allow(ActionGuard.Surface.DIALOG, "知道了")) return false;
            if (host.uiActions().clickNodeOrParent(node)) {
                log("森林：已关闭奖励弹窗。");
                return true;
            }
        }
        return false;
    }

    /** 只有同一任务仍停在原坐标时才补一次坐标点击，避免页面已变化时误点其他按钮。 */
    private boolean stillAtSameSpot(UiTree tree) {
        List<ForestTask> tasks = ForestTaskParser.parse(tree.nodes, tree.width, tree.height,
                host.env()::isInstalled);
        for (ForestTask task : tasks) {
            if (task.title.equals(taskTitle) && task.left == tapLeft && task.top == tapTop) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeInstallPage(UiTree tree) {
        return tree.hasText("立即下载") || tree.hasText("下载APP")
                || tree.hasText("安装应用") || tree.hasText("下载应用");
    }

    private static boolean hasOcr(List<TextBlock> boxes, String needle) {
        return findOcr(boxes, needle) != null;
    }

    /**
     * 奖励弹窗的强特征。不能拿「立即领取」「去看看」判断——森林主页上也有这些字样，
     * 会把主页误认成奖励页，然后卡在「请停留在奖励页」直到超时。
     */
    private static boolean isForestSheetOcr(List<TextBlock> boxes) {
        return hasOcr(boxes, "今日累计奖励") || hasOcr(boxes, "任务进度")
                || hasOcr(boxes, "更多任务") || hasOcr(boxes, "关闭奖励弹窗");
    }

    private static TextBlock findOcr(List<TextBlock> boxes, String needle) {
        if (boxes == null) return null;
        for (TextBlock box : boxes) {
            if (box != null && box.text != null && box.text.contains(needle)) return box;
        }
        return null;
    }

    private static String ocrSummary(List<TextBlock> boxes) {
        if (boxes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < boxes.size() && i < 12; i++) {
            TextBlock box = boxes.get(i);
            if (box != null) sb.append(box.text).append('|');
        }
        return sb.toString();
    }

    private static String signature(List<ForestTask> tasks) {
        StringBuilder sb = new StringBuilder();
        for (ForestTask task : tasks) sb.append(task.title).append('/').append(task.cta).append(';');
        return sb.toString();
    }

    private static ForestTask findTask(List<ForestTask> tasks, String title) {
        for (ForestTask task : tasks) if (task.title.equals(title)) return task;
        return null;
    }

    private void stop(String reason) {
        if (finished) return;
        finished = true;
        log(reason);
        host.stopForeground();
    }

    private boolean allow(ActionGuard.Surface surface, String label) {
        return gate != null && gate.allow(surface, label);
    }

    private void log(String message) {
        host.log().save(message);
    }
}
