package com.mario.assistant.application.forest;

import android.view.accessibility.AccessibilityNodeInfo;
import com.mario.assistant.application.ActionGate;
import com.mario.assistant.application.RunHost;
import com.mario.assistant.application.RunToken;
import com.mario.assistant.application.TaskRunner;
import com.mario.assistant.domain.forest.ForestMilestone;
import com.mario.assistant.domain.forest.ForestTask;
import com.mario.assistant.domain.forest.ForestTaskParser;
import com.mario.assistant.domain.forest.ForestTaskPolicy;
import com.mario.assistant.domain.forest.ForestTaskProgress;
import com.mario.assistant.domain.model.Bounds;
import com.mario.assistant.domain.model.TextBlock;
import com.mario.assistant.domain.model.UiNode;
import com.mario.assistant.domain.safety.ActionGuard;
import com.mario.assistant.infrastructure.a11y.UiTree;
import com.mario.assistant.infrastructure.system.DeviceEnvironment;
import com.mario.assistant.infrastructure.ui.Diag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 蚂蚁森林奖励页任务：动态解析任务行，逐个浏览/领取并自动回到奖励页。 */
public final class ForestTaskRunner implements TaskRunner {
    public enum Mode { NORMAL, DEBUG_RECOVER, DEBUG_COLLECT, DEBUG_TREASURE }

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
    /**
     * 第三方 BACK 重试节奏：返回键亚秒即生效，6 秒是保守值。真机 11 天 60 次滞留统计，
     * 第二次返回仍有 ~30% 成功率（不能省，只提速）：单次滞留少等 ~6 秒。
     */
    private static final long RETURN_BACK_RETRY_MS = 3000;

    private final Mode mode;
    private RunHost host;
    private RunToken token;
    private ActionGate gate;
    private boolean finished;
    /** 以「请手动」类结果收尾：协调器据此不再接续串联队列中的下一棒。 */
    private boolean chainBlocked;

    private static final String TAG = "MarioAdDiag";
    /** 落地页 OCR 里的浏览时长要求：「浏览5秒」「浏览30s」。 */
    private static final Pattern BROWSE_SECONDS_OCR = Pattern.compile("浏览(\\d+)(?:秒|s)");
    private int stage; // 0=找任务, 1=点击后观察, 2=等待返回奖励页, 3=抽奖页识别, 4=第三方滞留后恢复, 5=寻宝内页
    private long deadline;
    /** 本 run 启动时刻：finish 行带 run=Ns，复盘不用再手推时间戳。 */
    private long startedAtMs;
    private long stageAt;
    private String taskTitle;
    private String taskCta;
    private String targetPackage;
    private boolean observeThirdParty;
    private boolean usedGesture;
    private boolean energyProbed;
    private boolean energyCollected;
    /** 本任务已用 OCR 读过落地页的「浏览Ns」要求：一次运行只读一次。 */
    private boolean browseProbed;
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
    /** 连续不在支付宝前台的扫描次数：淘宝等任务把前台抢走后不能干等到 20min 总超时。 */
    private int offAlipayScans;
    /** 森林寻宝内页是 H5 画布，按钮只有文本节点，进页后统一按文本坐标轻点。 */
    private boolean treasureMode;
    private boolean treasureBrowsing;
    private long treasureBusyUntil;
    private long treasureBrowseEndAt;
    private long treasureBrowseSwipeAt;
    private int treasureBrowseSwipes;
    private int treasureSignIns;
    private int treasureClaimTaps;
    private int treasureBrowseTaps;
    private int treasureDrawTaps;
    private int treasureExchangeTaps;
    private int treasureTabTaps;
    private int treasureTabSeekSwipes;
    private int treasureTabOcrTries;
    private int treasureMarketTaps;
    /** 轻任务（签到/兑换/领取/抽奖）先跨池做完再逛市集；只切一次，防在两池间打转。 */
    private boolean treasureCheapSwitched;
    private int treasureOcrTries;
    private long treasureOcrAt;
    /** 浏览页的「已完成 可领奖励」角标点过一次就不再点。 */
    private boolean treasureBrowseClaim;
    /** 最近一次奖励页扫描解析出的任务行：超时收尾时列出「没轮到」的行，用户不用再猜。 */
    private List<ForestTask> lastTasks = Collections.emptyList();
    private int treasureTopSwipes;
    private long treasureTopSwipeAt;
    private long treasureScanAt;
    /** 抽奖后等动画结束再 OCR 找结果弹窗的关闭按钮。 */
    private long treasureVerifyAt;
    private static final int OCR_ENTRY = 0;
    private static final int OCR_DRAW = 1;
    private static final int OCR_DISMISS = 2;
    private static final int OCR_BROWSE_CLAIM = 3;
    private static final int OCR_TAB = 4;
    private static final int OCR_BROWSE_SCAN = 4;
    private int treasureCloseTaps;
    private int treasureBackTaps;
    /** 「还有N次机会」有时读不到，签到/领取成功就记一笔待抽，保证机会不会被漏掉。 */
    private int treasureDrawPending;
    private int treasureDismissTries;
    /** H5 抓树会瞬时空窗，连续 3 个 tick 认不出寻宝页才允许按返回。 */
    private int treasureUnseenTicks;
    /** 浏览行偶尔从树里消失，多等几个 tick 让树刷新再决定收尾。 */
    private int treasureIdleTicks;
    private boolean treasureDiagOnce;
    private static final int MAX_TREASURE_CLAIMS = 4;
    private static final int MAX_TREASURE_BROWSES = 6;
    private static final int MAX_TREASURE_DRAWS = 6;
    /** 「消耗活力值兑抽奖机会」每轮 run 最多兑 2 次（用户确认的兑换额度，防超花活力值）。 */
    private static final int MAX_TREASURE_EXCHANGES = 2;
    /** 抽奖页左右两个「主题装扮」奖池：当前池任务做完后切到另一个池各做一轮。 */
    private static final int MAX_TREASURE_TAB_SWITCHES = 2;
    /**
     * 市集「去逛逛」每轮 run 最多点 3 次：真机 11-28 实测 (0/2) 计数有延迟，
     * 第 2 次访问后行仍显示可点、第 3 次才记数；不设上限会在单池上无限循环
     * （每次市集浏览含 90s 停留 + 小游戏处理，实测 2~3 分钟一轮），把另一个池饿死。
     */
    private static final int MAX_TREASURE_MARKET_TAPS = 3;
    private static final int MAX_TREASURE_CLOSES = 4;
    /** WebView 抓树偶发卡死 20~35 秒，浏览页要留够解冻后继续滑动的时间。 */
    private static final long TREASURE_BROWSE_WAIT_MS = 90000;
    /**
     * 真机 11-28：单池内一次市集浏览（90s 停留 + 滑动 + 小游戏 + 领取）实测约 2~3 分钟，
     * 7 分钟只够一个奖池；两个奖池各做一轮（签到/兑换/市集×2/30s 游戏）需要 ~10 分钟。
     */
    private static final long TREASURE_DEADLINE_MS = 10 * 60 * 1000;
    private static final Pattern TREASURE_CHANCES = Pattern.compile("还有\\s*(\\d+)\\s*次机会");
    private static final Pattern BLANKS = Pattern.compile("[\\s\\u00a0\\u2000-\\u200a\\u3000]");
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
        startedAtMs = now;
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
        if (mode == Mode.DEBUG_TREASURE) {
            taskTitle = "(调试寻宝)";
            deadline = now + TREASURE_DEADLINE_MS; // 两池各做一轮需要 ~10 分钟，5 分钟会中途被掐
            enterTreasure(now);
            return;
        }
        deadline = now + 20 * 60 * 1000;
        // 15min 版实测三天全部撞满（11-14/11-15/11-17 完成 16-22 项即超时），底部任务
        // 全部漏掉；等待时长自适应压缩后 20min 作为兜底上限。
        // 串联运行时前台可能还在起点：自行唤起支付宝，后续由恢复路径进森林奖励页。
        if (!ALIPAY.equals(host.env().foreground())) {
            if (!host.env().launch(ALIPAY, false)) {
                stop("未找到支付宝，已跳过森林任务。");
                return;
            }
            log("森林任务已启动：已唤起支付宝，将自动进入蚂蚁森林奖励页。");
            return;
        }
        log("森林任务已启动：请停留在蚂蚁森林-奖励页。");
    }

    @Override public void onTick(long now) {
        if (finished || !token.isCurrent()) return;
        if (now >= deadline) {
            stop("森林任务超时停止；" + progress.summary() + unattemptedSummary() + "。");
            return;
        }
        try {
            if (stage == 0) scanRewardPage(now);
            else if (stage == 1) observeAfterTap(now);
            else if (stage == 2) waitReturn(now);
            else if (stage == 3) waitClaimPage(now);
            else if (stage == 4) waitRecover(now);
            else if (stage == 5) runTreasure(now);
        } catch (RuntimeException e) {
            log("forest error " + e);
            stop("森林任务异常停止：" + e.getMessage());
        }
    }

    @Override public void onStop() {
        finished = true;
    }

    /**
     * 超时收尾时列出「没轮到」的可执行行：{@link ForestTaskProgress#summary()} 只覆盖
     * 试过的行（完成/未确认），预算耗尽时列表下方的行从未被点击——真机 11-28 09:11
     * 超时只报「完成 14 项」，用户在奖励页看到 6 行挂着不知道是不是漏做，得靠人工对账。
     * 只统计最后一次扫描视口内的行，视口外的行本来就看不见，如实不列。
     */
    private String unattemptedSummary() {
        List<String> pending = new ArrayList<>();
        for (ForestTask task : lastTasks) {
            if (task.actionable() && !progress.isSettled(task.title)) pending.add(task.title);
        }
        if (pending.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("；未轮到 ").append(pending.size()).append(" 项：");
        for (int i = 0; i < pending.size() && i < 3; i++) {
            if (i > 0) sb.append('、');
            sb.append(pending.get(i));
        }
        if (pending.size() > 3) sb.append(" 等");
        return sb.toString();
    }

    /** 奖励页：每轮重新解析任务行，动态选择下一个可执行任务；完成一个再回来重新解析。 */
    private void scanRewardPage(long now) {
        UiTree tree = host.trees().capture();
        if (tree.root == null) { log("森林：等待支付宝页面。"); return; }
        if (!ALIPAY.equals(tree.packageName)) {
            String pkg = tree.packageName.isEmpty() ? "未知" : tree.packageName;
            if (++offAlipayScans >= 4) {
                // 真机 11-18 09:35 淘宝任务把前台抢走后这里只打日志，空转 5 分钟直到 20min 总超时。
                offAlipayScans = 0;
                beginRecover(now);
                log("森林：连续不在支付宝前台（当前 " + pkg + "），正在尝试回到奖励页。");
            } else {
                log("森林：等待支付宝前台（当前 " + pkg + "）。");
            }
            return;
        }
        offAlipayScans = 0;
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
        lastTasks = tasks;
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
            } else if (ForestTaskPolicy.isGameBrowse(title)
                    && progress.recordBrowseFailure(title) <= ForestTaskProgress.MAX_BROWSE_FAILURES) {
                // 真机 11-25：小游戏首次浏览（默认 10 秒）不够，行仍是「去看看」；
                // 11-27 同一行第二次停留后变「去抽奖」完成。只多给一次，避免在永不完成的行上空转。
                log("「" + title + "」浏览后仍可点击，再给一次浏览机会。");
            } else {
                progress.markStuck(title);
                log("「" + title + "」浏览后仍可点击，未确认完成，已跳过。");
            }
            taskTitle = null;
            claimMode = false;
            lastClickWasClaim = false;
            treasureMode = false;
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
        treasureMode = next.kind == ForestTask.Kind.BROWSE && next.title.contains("寻宝");
        lastClickWasClaim = claimMode;
        browseSeconds = next.browseSeconds();
        tapLeft = next.left;
        tapTop = next.top;
        tapRight = next.right;
        tapBottom = next.bottom;
        usedGesture = false;
        energyProbed = false;
        energyCollected = false;
        browseProbed = false;
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
        if (treasureMode && ALIPAY.equals(pkg) && !rewardPage) {
            if (elapsed < 2500) return;
            treasureMode = false;
            enterTreasure(now);
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
            if (!browseProbed && elapsed > 2500) probeBrowseSeconds();
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
            return;
        }
        if (!browseProbed && elapsed > 2500) probeBrowseSeconds();
    }

    /**
     * 落地页 OCR 读「浏览Ns」要求，动态调整停留时长。
     * 任务行内没有时长文案（22/22 全走默认值，真机 2026-11-17），「浏览5秒」「浏览30s」
     * 只出现在落地页上：5 秒的任务不必陪等 15 秒，30 秒的提前知道可以避免返回后
     * 「浏览后仍可点击」再点一次的双倍开销。
     */
    private void probeBrowseSeconds() {
        if (host.ocr().busy()) return;
        browseProbed = true;
        host.ocr().recognize(boxes -> {
            if (!token.isCurrent() || stage != 1 || boxes == null) return;
            for (TextBlock box : boxes) {
                if (box == null || box.text == null) continue;
                Matcher matcher = BROWSE_SECONDS_OCR.matcher(box.text.replace(" ", ""));
                if (!matcher.find()) continue;
                try {
                    int seconds = Math.max(5, Math.min(60, Integer.parseInt(matcher.group(1))));
                    if (seconds != browseSeconds) {
                        browseSeconds = seconds;
                        log("森林：「" + taskTitle + "」落地页要求浏览 " + seconds
                                + " 秒，已调整停留时长。");
                    }
                } catch (NumberFormatException ignored) {
                    // OCR 数字识别异常时保持默认时长。
                }
                return;
            }
        }, null);
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
        if (elapsed > RETURN_BACK_RETRY_MS) {
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
            // 打印实际滞留的包，而不是要回到的目标包（真机 11-22 08:02：卡在淘宝却报「支付宝」滞留）。
            log("森林：「" + pkg + "」滞留过久，正在重新打开支付宝回到奖励页。");
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
                // 真机 11-19 09:10：普通唤起恢复的是支付宝小程序容器，0.9 秒后又跳回淘宝；
                // 第二次起改为回桌面后清栈重启，直接落到支付宝首页再点「蚂蚁森林」。
                boolean fresh = recoverRelaunch >= 2;
                if (fresh) host.env().home();
                if (host.env().launch(ALIPAY, fresh)) {
                    log(fresh
                            ? "森林：支付宝被小程序反复带回第三方，正在重启支付宝（第 " + recoverRelaunch + " 次）。"
                            : "森林：支付宝未到前台，正在再次唤起（第 " + recoverRelaunch + " 次）。");
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

    private void enterTreasure(long now) {
        stage = 5;
        stageAt = now;
        treasureBusyUntil = 0;
        treasureBrowsing = false;
        treasureSignIns = 0;
        treasureClaimTaps = 0;
        treasureBrowseTaps = 0;
        treasureDrawTaps = 0;
        treasureTabTaps = 0;
        treasureTabSeekSwipes = 0;
        treasureTabOcrTries = 0;
        treasureMarketTaps = 0;
        treasureCheapSwitched = false;
        // treasureExchangeTaps 不在这里清零：奖励页可能反复重进寻宝页，
        // 兑换预算按整轮 run 计（每次进页清零会在重进时超花活力值）。
        treasureOcrTries = 0;
        treasureOcrAt = 0;
        treasureVerifyAt = 0;
        treasureCloseTaps = 0;
        treasureBackTaps = 0;
        treasureDrawPending = 0;
        treasureDismissTries = 0;
        treasureUnseenTicks = 0;
        treasureIdleTicks = 0;
        treasureDiagOnce = true;
        treasureBrowseClaim = false;
        log("森林：已进入森林寻宝页，开始处理签到、逛逛与抽奖。");
        requestTreasureOcr(OCR_ENTRY, now);
    }

    /**
     * 寻宝内页是 H5 画布：按钮只有文本节点，全部按坐标轻点。
     * 页面状态只认文本（「还有N次机会」「领取」「去逛逛」「【限时】」），
     * 每个动作都有次数上限，超时或做完就回奖励页，不阻塞主任务列表。
     */
    private void runTreasure(long now) {
        if (now < treasureBusyUntil) return;
        if (host.ocr().busy()) return;
        if (now - stageAt > TREASURE_DEADLINE_MS) {
            finishTreasure(now, "森林：寻宝内页处理超时，正在返回奖励页。");
            return;
        }
        String pkg = host.env().foreground();
        if (!ALIPAY.equals(pkg)) {
            if (treasureBrowsing) {
                closeTreasureMiniApp(now);
            } else {
                beginRecover(now);
                log("森林：寻宝页切到「" + pkg + "」，正在恢复奖励页。");
            }
            return;
        }
        UiTree tree = host.trees().capture();
        if (tree.root == null) return;
        if (!isTreasurePage(tree)) {
            if (isRewardPage(tree)) {
                stage = 0;
                stageAt = now;
                priorKey = null;
                log("森林：寻宝任务已处理完，已回到奖励页。");
                return;
            }
            if (treasureBrowsing && now < treasureBrowseEndAt) {
                swipeTreasureBrowse(now, tree);
                return;
            }
            if (treasureBrowsing && !treasureBrowseClaim && treasureOcrTries < 3) {
                // 角标是页面顶部的横幅，滑出去就 OCR 不到：先滑回顶部再截
                if (treasureTopSwipes < 4) {
                    if (now >= treasureTopSwipeAt) {
                        treasureTopSwipes++;
                        treasureTopSwipeAt = now + 1200;
                        if (treasureTopSwipes == 1) {
                            log("森林：浏览完成，正在滑回市集顶部找领取入口。");
                        }
                        swipe(false);
                        return;
                    }
                    treasureBusyUntil = now + 800;
                    return;
                }
                if (now - treasureOcrAt > 3000) {
                    treasureOcrTries++;
                    requestTreasureOcr(OCR_BROWSE_CLAIM, now);
                } else {
                    treasureBusyUntil = now + 800;
                }
                return;
            }
            if (treasureBrowsing || treasureBrowseTaps > 0) {
                closeTreasureMiniApp(now);
                return;
            }
            if (treasureUnseenTicks < 3) {
                treasureUnseenTicks++;
                treasureBusyUntil = now + 1200;
                return;
            }
            if (treasureBackTaps < 2) {
                treasureBackTaps++;
                host.env().back();
                treasureBusyUntil = now + 1500;
                log("森林：寻宝页未识别，正在返回（第 " + treasureBackTaps + " 次）。");
            } else {
                beginRecover(now);
                log("森林：寻宝页无法识别，正在恢复奖励页。");
            }
            return;
        }
        treasureBrowsing = false;
        treasureBackTaps = 0;
        treasureUnseenTicks = 0;

        if (treasureVerifyAt > 0) {
            if (now < treasureVerifyAt) return;
            treasureVerifyAt = 0;
            treasureOcrTries = 0;
            if (host.ocr().busy()) {
                treasureBusyUntil = now + 800;
                treasureVerifyAt = now + 800;
                return;
            }
            requestTreasureOcr(OCR_DISMISS, now);
            return;
        }

        // 抽奖结果弹窗右上角的 X 点不动，实测「做任务继续抽」可以关闭；
        // 「确认兑换」是活力值兑换抽奖机会的确认弹窗（真机 11-28 取证）。
        UiNode popup = findTreasureNode(tree, "做任务继续抽");
        if (popup == null) popup = findTreasureNode(tree, "收下奖励");
        if (popup == null) popup = findTreasureNode(tree, "知道了");
        if (popup == null) popup = findTreasureNode(tree, "确认兑换");
        if (popup != null && allow(ActionGuard.Surface.DIALOG, popup.text)) {
            treasureVerifyAt = 0;
            treasureOcrTries = 0;
            tapForest(popup.left, popup.top, popup.right, popup.bottom);
            treasureBusyUntil = now + 1600;
            log("森林：已关闭寻宝抽奖结果弹窗。");
            return;
        }

        if (treasureSignIns == 0) {
            treasureSignIns = 1; // 已签到时按钮会消失，每次进页只找一次
            UiNode signIn = findTreasureNode(tree, "签到");
            if (signIn != null && allow(ActionGuard.Surface.TASK_ROW, "签到")) {
                treasureDrawPending++;
                tapForest(signIn.left, signIn.top, signIn.right, signIn.bottom);
                treasureBusyUntil = now + 1800;
                log("森林：寻宝页已点击「签到」领取抽奖机会。");
                return;
            }
        }

        UiNode claim = findTreasureNode(tree, "领取");
        if (claim != null && treasureClaimTaps < MAX_TREASURE_CLAIMS
                && allow(ActionGuard.Surface.TASK_ROW, "领取")) {
            treasureClaimTaps++;
            treasureDrawPending++;
            tapForest(claim.left, claim.top, claim.right, claim.bottom);
            treasureBusyUntil = now + 1800;
            log("森林：寻宝页已点击「领取」抽奖机会。");
            return;
        }

        // 「消耗活力值得机会(0/2)」：点「去兑换」→「兑换确认」弹窗由上方弹窗分支确认（真机 11-28）。
        UiNode exchange = findTreasureNode(tree, "去兑换");
        if (exchange != null && treasureExchangeTaps < MAX_TREASURE_EXCHANGES
                && allow(ActionGuard.Surface.TASK_ROW, "去兑换")) {
            treasureExchangeTaps++;
            treasureDrawPending++;
            tapForest(exchange.left, exchange.top, exchange.right, exchange.bottom);
            treasureBusyUntil = now + 1800;
            log("森林：寻宝页已点击「去兑换」（第 " + treasureExchangeTaps + " 次），等待确认弹窗。");
            return;
        }

        int chances = treasureChances(tree);
        if (chances > 0) treasureDrawPending = Math.max(treasureDrawPending, chances);
        UiNode draw = findTreasureNode(tree, "立即抽奖");
        if (draw == null && treasureDiagOnce && (chances > 0 || treasureDrawPending > 0)) {
            treasureDiagOnce = false;
            log("森林：树里未见抽奖按钮，页面「机会」文本=" + chanceTexts(tree));
        }
        boolean wantDraw = chances > 0 || treasureDrawPending > 0;
        if (wantDraw && treasureDrawTaps < MAX_TREASURE_DRAWS) {
            if (draw != null && allow(ActionGuard.Surface.DIALOG, "立即抽奖")) {
                treasureDrawTaps++;
                        treasureOcrTries = 0;
                treasureDismissTries = 0;
                treasureIdleTicks = 0;
                if (treasureDrawPending > 0) treasureDrawPending--;
                tapForest(draw.left, draw.top, draw.right, draw.bottom);
                treasureBusyUntil = now + 3200;
                treasureVerifyAt = now + 3600;
                log("森林：寻宝剩余机会 " + Math.max(chances, 1) + "，正在抽奖（第 " + treasureDrawTaps + " 次）。");
                return;
            }
            // 「立即抽奖」是画布元素，树里经常没有：改用 OCR 截图定位
            if (treasureOcrTries >= 3) {
                treasureDrawPending = 0; // OCR 也找不到就这轮放弃，先去逛逛
            } else if (now - treasureOcrAt < 4000) {
                treasureBusyUntil = now + 1000; // 等 OCR 冷却，别把机会丢了
                return;
            } else {
                treasureOcrTries++;
                requestTreasureOcr(OCR_DRAW, now);
                return;
            }
        }

        // 双奖池调度（真机 11-28）：先切池把两个池的轻任务（签到/兑换/领取/抽奖，各 ~1 分钟）
        // 都做完，再按剩余预算逛市集——否则首池的市集浏览（2~3 分钟/轮）会把预算吃光，
        // 另一个池的轻任务整轮漏做。轻任务切池只发生一次，之后由浏览耗尽后的切换分支回访。
        if (!treasureCheapSwitched && treasureTabTaps < MAX_TREASURE_TAB_SWITCHES) {
            if (trySwitchTreasureTab(tree, now, "轻任务收尾，切换到")) return;
        }

        if (treasureBrowseTaps < MAX_TREASURE_BROWSES) {
            boolean marketExhausted = treasureMarketTaps >= MAX_TREASURE_MARKET_TAPS;
            UiNode browse = findTreasureBrowse(tree, marketExhausted);
            if (browse != null && allow(ActionGuard.Surface.TASK_ROW, browse.text)) {
                treasureBrowseTaps++;
                if ("去逛逛".equals(browse.text)) treasureMarketTaps++;
                treasureIdleTicks = 0;
                treasureBrowsing = true;
                treasureBrowseEndAt = now + TREASURE_BROWSE_WAIT_MS;
                treasureBrowseSwipes = 0;
                treasureBrowseSwipeAt = now + 2500;
                treasureBrowseClaim = false;
                treasureOcrTries = 0;
                treasureTopSwipes = 0;
                treasureTopSwipeAt = 0;
                treasureScanAt = now + 5000;
                tapForest(browse.left, browse.top, browse.right, browse.bottom);
                treasureBusyUntil = now + 3000;
                log("森林：寻宝页已点击「" + browse.text + "」，停留 "
                        + TREASURE_BROWSE_WAIT_MS / 1000 + " 秒后返回。");
                return;
            }
            if (treasureIdleTicks < 5) {
                treasureIdleTicks++;
                treasureBusyUntil = now + 1200;
                if (treasureIdleTicks == 1) {
                    log("森林：寻宝页暂未发现可浏览任务，等待列表刷新。");
                }
                return;
            }
        }

        // 当前奖池可做任务做完：切到另一个「主题装扮」奖池再各做一轮
        // （真机 11-28：页顶有左右两个奖池 tab，旧流程只处理默认激活的右池，左池的
        // 签到/兑换/浏览/30s 游戏整轮漏做）。按 left 排序轮流点，两个池都会被访问到。
        if (treasureTabTaps < MAX_TREASURE_TAB_SWITCHES) {
            if (trySwitchTreasureTab(tree, now, "已切换到")) return;
        }

        finishTreasure(now, "森林：寻宝页内可做任务已完成，正在返回奖励页。");
    }

    /**
     * 切换奖池 tab（「XX主题装扮」）。三级兜底：无障碍树 → OCR 截图 → 滑回页顶。
     * 返回 true 表示本帧已发起动作（点中/OCR 请求/寻位滑动），调用方应 return 等下一帧；
     * 返回 false 表示预算耗尽或页面上确无第二个奖池，调用方按单池收尾。
     */
    private boolean trySwitchTreasureTab(UiTree tree, long now, String reason) {
        UiNode tab = findTreasureTab(tree, treasureTabTaps);
        if (tab != null) {
            treasureTabTaps++;
            treasureSignIns = 0; // 签到每池各一次，切换后要重新找
            treasureIdleTicks = 0;
            treasureCheapSwitched = true;
            tapForest(tab.left, tab.top, tab.right, tab.bottom);
            treasureBusyUntil = now + 2500;
            log("森林：" + reason + "「" + tab.text + "」奖池，继续做该池任务。");
            return true;
        }
        // tab 固定在页顶，视口常停在任务列表：先滑回顶部（真机 11-28 实测：任务列表处
        // OCR 三帧都读不到 tab，必须先滚动），滑动冷却期间也不让浏览分支插队
        if (treasureTabSeekSwipes < 2) {
            if (now >= treasureTopSwipeAt) {
                treasureTabSeekSwipes++;
                treasureTopSwipeAt = now + 1500;
                swipe(false);
                log("森林：滑回抽奖页顶部寻找另一个奖池入口（第 " + treasureTabSeekSwipes + " 次）。");
            }
            treasureBusyUntil = now + 800;
            return true;
        }
        // tab 胶囊是画布元素，树里读不到：页面到顶后用 OCR 截图定位。
        // 截图要等上一次滑动 settle 完（真机 11-28：滑动后 1.2s 内截图会静默失败）。
        if (treasureTabOcrTries < 4) {
            if (now < treasureTopSwipeAt + 800 || now - treasureOcrAt < 4000) {
                treasureBusyUntil = now + 1000; // 等 OCR 冷却
                return true;
            }
            treasureTabOcrTries++;
            requestTreasureOcr(OCR_TAB, now);
            treasureBusyUntil = now + 1200;
            return true;
        }
        return false;
    }

    /** 抽奖页顶部的奖池切换 tab（「XX主题装扮」）：按 left 排序后轮流点击。 */
    private static UiNode findTreasureTab(UiTree tree, int tapIndex) {
        List<UiNode> tabs = new ArrayList<>();
        for (UiNode node : tree.nodes) {
            if (node == null || !node.visible || !node.hasBounds() || !node.inViewport()) continue;
            if (node.text == null || !node.text.contains("主题装扮")) continue;
            if (node.bottom - node.top > tree.height * 12 / 100) continue;
            tabs.add(node);
        }
        if (tabs.isEmpty()) return null;
        tabs.sort((a, b) -> a.left - b.left);
        return tabs.get(tapIndex % tabs.size());
    }

    /** 寻宝内的小游戏吞返回键：先找「关闭」节点，再按真机坐标 (0.875w, 0.075h) 兜底，最后才返回。 */
    private void closeTreasureMiniApp(long now) {
        if (treasureCloseTaps >= MAX_TREASURE_CLOSES) {
            treasureBrowsing = false;
            beginRecover(now);
            log("森林：寻宝内的小游戏退不出去，正在恢复奖励页。");
            return;
        }
        treasureCloseTaps++;
        UiTree tree = host.trees().capture();
        AccessibilityNodeInfo close = tree.findCloseButton();
        if (close != null && allow(ActionGuard.Surface.DIALOG, "关闭")
                && host.uiActions().clickNodeOrParent(close)) {
            treasureBusyUntil = now + 2000;
            log("森林：已点击寻宝内页右上角「关闭」。");
            return;
        }
        if (treasureCloseTaps % 2 == 1 && allow(ActionGuard.Surface.NAVIGATION, "关闭小游戏")) {
            int cx = host.env().screenWidth() * 7 / 8;
            int cy = host.env().screenHeight() * 15 / 200;
            host.gestures().tap(new Bounds(cx - 30, cy - 30, cx + 30, cy + 30), 80, NO_OP);
            treasureBusyUntil = now + 2000;
            log("森林：已按坐标点击小游戏右上角关闭。");
            return;
        }
        host.env().back();
        treasureBusyUntil = now + 1500;
        log("森林：正在退出寻宝内的小游戏（返回）。");
    }

    private void finishTreasure(long now, String reason) {
        host.env().back();
        beginReturn(now);
        log(reason);
    }

    /**
     * 市集页写着「滑动浏览得抽奖机会」：只停留不划动不计入，真机实测约 6~7 次滑动后
     * 右上角变「已完成 可领奖励」；滑到一半还会弹「猜价格」小游戏挡住后续滑动，直接放弃。
     * WebView 抓树偶尔卡顿导致划不动，所以按 60 秒兜底、看到「已完成」就提前返回。
     */
    private void swipeTreasureBrowse(long now, UiTree tree) {
        if (now < treasureBrowseSwipeAt) return;
        if (now >= treasureScanAt && now < treasureBrowseEndAt - 8000) {
            treasureScanAt = now + 6000;
            requestTreasureOcr(OCR_BROWSE_SCAN, now);
            return;
        }
        UiNode badge = findTreasureNodeContains(tree, "可领奖励");
        if (badge != null && !treasureBrowseClaim && allow(ActionGuard.Surface.TASK_ROW, "可领奖励")) {
            treasureBrowseClaim = true;
            tapForest(badge.left, badge.top, badge.right, badge.bottom);
            treasureBusyUntil = now + 2500;
            log("森林：市集浏览已完成，已点击「可领奖励」。");
            return;
        }
        if (treasureBrowseDone(tree)) {
            treasureBrowseEndAt = now;
            log("森林：市集浏览已完成，准备领取奖励。");
            return;
        }
        if (now > treasureBrowseEndAt - 6000) return;
        UiNode skip = findTreasureNode(tree, "放弃奖励");
        if (skip != null && allow(ActionGuard.Surface.TASK_ROW, "放弃奖励")) {
            treasureBrowseSwipeAt = now + 2500;
            tapForest(skip.left, skip.top, skip.right, skip.bottom);
            treasureBusyUntil = now + 2200;
            log("森林：已放弃市集「猜价格」小游戏，继续滑动浏览。");
            return;
        }
        if (treasureBrowseSwipes >= 14) return;
        treasureBrowseSwipes++;
        treasureBrowseSwipeAt = now + 3000;
        swipe(true);
        log("森林：正在滑动浏览寻宝内页（第 " + treasureBrowseSwipes + " 次）。");
    }

    private static boolean treasureBrowseDone(UiTree tree) {
        for (UiNode node : tree.nodes) {
            if (node != null && node.visible && node.text != null && node.text.contains("已完成")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTreasurePage(UiTree tree) {
        // 抽奖结果弹窗会盖住整页，树里只剩弹窗按钮：不认出来就会误按返回
        if (tree.hasText("立即抽奖") || tree.hasText("返回森林") || tree.hasText("森林寻宝")
                || tree.hasText("去抽奖") || tree.hasText("做任务继续抽")
                || tree.hasText("收下奖励") || tree.hasText("去兑换")) return true;
        for (UiNode node : tree.nodes) {
            if (node == null || !node.visible || node.text == null) continue;
            if (node.text.contains("次机会") || node.text.contains("去森林市集逛一逛")
                    || node.text.contains("玩一玩找不停")) return true;
        }
        return false;
    }

    private static UiNode findTreasureNode(UiTree tree, String text) {
        for (UiNode node : tree.nodes) {
            if (node != null && node.visible && node.hasBounds() && node.inViewport()
                    && text.equals(node.text)) return node;
        }
        return null;
    }

    /** 角标类文案会拼接（「已完成 可领奖励」），按包含匹配并限制在按钮尺寸内。 */
    private static UiNode findTreasureNodeContains(UiTree tree, String text) {
        for (UiNode node : tree.nodes) {
            if (node == null || !node.visible || !node.hasBounds() || !node.inViewport()) continue;
            if (node.text == null || !node.text.contains(text)) continue;
            if (node.bottom - node.top > tree.height * 15 / 100) continue;
            return node;
        }
        return null;
    }

    /**
     * 只逛非【限时】的浏览行：限时行要升建筑/打怪，超出自动浏览的能力边界。
     * {@code skipMarket}：市集点击达到上限后跳过「去逛逛」，让 30s 游戏的「马上玩」有机会执行，
     * 避免计数延迟的市集行把浏览预算全部吃掉（真机 11-28 (0/2) 行连点 3 次才记数）。
     */
    private static UiNode findTreasureBrowse(UiTree tree, boolean skipMarket) {
        int limitedTop = Integer.MAX_VALUE;
        for (UiNode node : tree.nodes) {
            if (node == null || !node.visible || node.text == null) continue;
            if (node.text.contains("【限时】")) limitedTop = Math.min(limitedTop, node.top);
        }
        UiNode fallback = null;
        for (UiNode node : tree.nodes) {
            if (node == null || !node.visible || !node.hasBounds() || !node.inViewport()) continue;
            if (node.top >= limitedTop) continue;
            if (!skipMarket && "去逛逛".equals(node.text)) return node;
            if ("马上玩".equals(node.text) && fallback == null) fallback = node;
        }
        return fallback;
    }

    private static int treasureChances(UiTree tree) {
        int chances = 0;
        for (UiNode node : tree.nodes) {
            if (node == null || !node.visible || node.text == null) continue;
            Matcher matcher = TREASURE_CHANCES.matcher(BLANKS.matcher(node.text).replaceAll(""));
            if (!matcher.find()) continue;
            try {
                chances = Math.max(chances, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // 识别异常按 0 次处理，不抽奖。
            }
        }
        return chances;
    }

    private static String chanceTexts(UiTree tree) {
        StringBuilder sb = new StringBuilder();
        for (UiNode node : tree.nodes) {
            if (node == null || !node.visible || node.text == null || !node.text.contains("机会")) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(node.text);
            if (sb.length() > 160) break;
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /** 寻宝页主区是画布：机会计数、立即抽奖、抽奖结果弹窗在树里时有时无，统一 OCR 兜底。 */
    private void requestTreasureOcr(int purpose, long now) {
        if (host.ocr().busy()) return;
        treasureOcrAt = now;
        host.ocr().recognize(boxes -> {
            if (finished || !token.isCurrent() || stage != 5) return;
            onTreasureOcr(purpose, boxes);
        }, code -> log("森林：寻宝 OCR 截图失败 code=" + code + "（purpose=" + purpose + "），稍后重试。"));
    }

    private void onTreasureOcr(int purpose, List<TextBlock> boxes) {
        if (boxes == null) return;
        long now = host.now();
        int chances = ocrChances(boxes);
        if (chances > 0) {
            treasureDrawPending = Math.max(treasureDrawPending, Math.min(chances, MAX_TREASURE_DRAWS));
            if (purpose == OCR_ENTRY) {
                log("森林：寻宝页 OCR 读到剩余 " + chances + " 次抽奖机会。");
            }
        }
        if (purpose == OCR_DRAW) {
            TextBlock draw = findOcrBlock(boxes, "立即抽奖");
            if (draw == null || treasureDrawTaps >= MAX_TREASURE_DRAWS
                    || !allow(ActionGuard.Surface.DIALOG, "立即抽奖")) return;
            // 0 次时按钮仍在页面上，只有同一张图读到「还有N次机会」才点
            if (chances == 0) return;
            treasureDrawTaps++;
            treasureDismissTries = 0;
            if (treasureDrawPending > 0) treasureDrawPending--;
            host.gestures().tap(new Bounds(draw.left, draw.top, draw.right, draw.bottom), 80, NO_OP);
            treasureBusyUntil = now + 3200;
            treasureVerifyAt = now + 3600;
            log("森林：OCR 已点击「立即抽奖」（第 " + treasureDrawTaps + " 次）。");
            return;
        }
        if (purpose == OCR_BROWSE_SCAN) {
            // 猜价格小游戏会盖住市集页且划不动，只能靠截图认字关掉
            TextBlock skip = findOcrBlock(boxes, "放弃奖励");
            if (skip != null && allow(ActionGuard.Surface.TASK_ROW, "放弃奖励")) {
                host.gestures().tap(new Bounds(skip.left, skip.top, skip.right, skip.bottom), 80, NO_OP);
                treasureBusyUntil = now + 2000;
                log("森林：已放弃市集「猜价格」小游戏（OCR）。");
                return;
            }
            TextBlock claim = findOcrBlock(boxes, "可领奖励");
            if (claim != null && !treasureBrowseClaim
                    && allow(ActionGuard.Surface.TASK_ROW, "可领奖励")) {
                treasureBrowseClaim = true;
                host.gestures().tap(new Bounds(claim.left, claim.top, claim.right, claim.bottom), 80, NO_OP);
                treasureBusyUntil = now + 2500;
                log("森林：市集浏览已完成，已点击「可领奖励」（OCR）。");
                return;
            }
            if (findOcrBlock(boxes, "猜价格") != null && allow(ActionGuard.Surface.NAVIGATION, "关闭小游戏")) {
                int cx = host.env().screenWidth() * 867 / 1000;
                int cy = host.env().screenHeight() * 254 / 1000;
                host.gestures().tap(new Bounds(cx - 40, cy - 40, cx + 40, cy + 40), 80, NO_OP);
                treasureBusyUntil = now + 2000;
                log("森林：已按坐标关闭市集「猜价格」小游戏。");
            }
            return;
        }
        if (purpose == OCR_BROWSE_CLAIM) {
            treasureBrowseClaim = true;
            TextBlock claim = findOcrBlock(boxes, "可领奖励");
            if (claim != null && allow(ActionGuard.Surface.TASK_ROW, "可领奖励")) {
                host.gestures().tap(new Bounds(claim.left, claim.top, claim.right, claim.bottom), 80, NO_OP);
                treasureBusyUntil = now + 2500;
                log("森林：市集浏览已完成，已点击「可领奖励」（OCR）。");
            } else {
                log("森林：市集页未见「可领奖励」，直接返回。");
            }
            return;
        }
        if (purpose == OCR_TAB) {
            // 「XX主题装扮」奖池 tab：树里读不到，按 OCR 框点击；两个 tab 按 left 排序轮流点
            List<TextBlock> tabs = new ArrayList<>();
            for (TextBlock block : boxes) {
                if (block != null && block.hasBounds() && block.text != null
                        && block.text.replace(" ", "").contains("主题装扮")) {
                    tabs.add(block);
                }
            }
            if (tabs.isEmpty()) {
                log("森林：OCR 未读到奖池 tab，放弃切换（单池收尾）。");
                return;
            }
            tabs.sort((a, b) -> a.left - b.left);
            TextBlock tab = tabs.get(treasureTabTaps % tabs.size());
            String tabText = tab.text.replace(" ", "");
            if (!allow(ActionGuard.Surface.NAVIGATION, tabText)) return;
            treasureTabTaps++;
            treasureSignIns = 0; // 签到每池各一次，切换后要重新找
            treasureIdleTicks = 0;
            treasureCheapSwitched = true;
            treasureTabOcrTries = 0;
            host.gestures().tap(new Bounds(tab.left, tab.top, tab.right, tab.bottom), 80, NO_OP);
            treasureBusyUntil = now + 2500;
            log("森林：已切换到「" + tabText + "」奖池，继续做该池任务（OCR）。");
            return;
        }
        if (purpose == OCR_DISMISS) {
            TextBlock btn = findOcrBlock(boxes, "做任务继续抽");
            if (btn == null) btn = findOcrBlock(boxes, "收下奖励");
            if (btn == null) btn = findOcrBlock(boxes, "知道了");
            if (btn == null) {
                if (treasureDismissTries < 2) {
                    treasureDismissTries++;
                    treasureVerifyAt = now + 1600; // 弹窗可能还在动画，稍后再截一次
                }
                return;
            }
            if (!allow(ActionGuard.Surface.DIALOG, btn.text)) return;
            treasureDismissTries = 0;
            host.gestures().tap(new Bounds(btn.left, btn.top, btn.right, btn.bottom), 80, NO_OP);
            treasureBusyUntil = now + 2000;
            log("森林：已关闭寻宝抽奖结果弹窗（OCR）。");
        }
    }

    private static TextBlock findOcrBlock(List<TextBlock> boxes, String text) {
        for (TextBlock block : boxes) {
            if (block != null && block.hasBounds() && block.text.contains(text)) return block;
        }
        return null;
    }

    private static int ocrChances(List<TextBlock> boxes) {
        int chances = 0;
        for (TextBlock block : boxes) {
            if (block == null || block.text == null) continue;
            Matcher matcher = TREASURE_CHANCES.matcher(block.text);
            if (!matcher.find()) continue;
            try {
                chances = Math.max(chances, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // 识别失败按 0 处理。
            }
        }
        return chances;
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
        // 「请手动」类收尾说明页面停在不确定状态，串联队列不再自动接续。
        chainBlocked = reason != null && reason.contains("请手动");
        Diag.i(TAG, "finish run=" + (host.now() - startedAtMs) / 1000 + "s " + reason);
        log(reason);
        host.stopForeground();
    }

    @Override public boolean chainShouldContinue() { return !chainBlocked; }

    private boolean allow(ActionGuard.Surface surface, String label) {
        return gate != null && gate.allow(surface, label);
    }

    private void log(String message) {
        host.log().save(message);
    }
}
