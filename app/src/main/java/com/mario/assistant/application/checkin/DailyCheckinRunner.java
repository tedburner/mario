package com.mario.assistant.application.checkin;

import android.os.Build;
import android.util.Log;
import com.mario.assistant.application.ActionGate;
import com.mario.assistant.application.RunHost;
import com.mario.assistant.application.RunToken;
import com.mario.assistant.application.TaskRunner;
import com.mario.assistant.domain.checkin.CheckinApp;
import com.mario.assistant.domain.checkin.CheckinApps;
import com.mario.assistant.domain.checkin.CheckinPolicy;
import com.mario.assistant.domain.model.Bounds;
import com.mario.assistant.domain.model.TextBlock;
import com.mario.assistant.domain.safety.ActionGuard;
import com.mario.assistant.domain.welfare.VisualTargetGuard;
import com.mario.assistant.infrastructure.a11y.Gestures;
import java.util.ArrayList;
import java.util.List;

/**
 * 每日签到：按 {@link CheckinApps} 名单逐个打开 APP，找签到入口并点击，认完成标记收尾。
 * 单个 APP 未安装/打不开/找不到入口/超时都记录后跳过，最后汇总；不阻塞名单里的下一个。
 *
 * <p>每个 APP 三段：等前台（启动+闪屏）→ 找入口（首页入口/弹窗/底部标签→右上角入口）
 * → 签到页（点动作按钮，等完成标记）。所有点击过安全闸门，双帧稳定才下手。</p>
 */
public final class DailyCheckinRunner implements TaskRunner {
    private static final String TAG = "MarioAdDiag";
    /** 单个 APP 的总预算：覆盖冷启动、找入口、签到页加载与确认。 */
    private static final long APP_TIMEOUT_MS = 60000;
    /** 签到页多为慢 webview：点过入口后单独重新计时，避免被整 APP 预算提前掐断。 */
    private static final long SIGN_PAGE_TIMEOUT_MS = 45000;
    private static final long LAUNCH_TIMEOUT_MS = 20000;
    /** 前台包名到位后再等闪屏/首页加载。 */
    private static final long LAUNCH_SETTLE_MS = 3500;
    /** E充站类弹窗观察窗：这段时间内首页没有签到按钮就改走标签路径。 */
    private static final long POPUP_WAIT_MS = 8000;
    private static final long TAB_SETTLE_MS = 1500;
    /** 每次点击后等页面响应，避免把上一个页面的按钮再点一次。 */
    private static final long TAP_SETTLE_MS = 4000;
    private static final int MAX_ACTION_TAPS = 2;

    private RunHost host;
    private RunToken token;
    private ActionGate gate;
    private boolean finished;

    private List<CheckinApp> apps;
    private final List<String> results = new ArrayList<>();
    private int doneCount;
    private int unsureCount;
    private int skippedCount;
    private int index;
    private CheckinApp app;
    private long appDeadline;
    private long phaseAt;
    private long lastTapAt;
    private int actionTaps;
    private int phase;     // 0=等前台 1=找入口 2=签到页动作/确认
    private int entryStep; // 0=首页入口/弹窗 1=底部标签 2=标签页入口
    private TextBlock priorTarget;
    private int priorWidth, priorHeight;

    @Override public String id() { return "checkin"; }

    @Override public long tickIntervalMs() { return 2000; }

    @Override public long initialDelayMs() { return 1000; }

    @Override public boolean isFinished() { return finished; }

    @Override public void onStart(RunHost host, RunToken token) {
        this.host = host;
        this.token = token;
        this.gate = new ActionGate(host.log(), token);
        apps = CheckinApps.all();
        index = 0;
        StringBuilder names = new StringBuilder();
        for (CheckinApp item : apps) {
            if (names.length() > 0) names.append('、');
            names.append(item.name);
        }
        log("每日签到已启动：" + names + "；单个失败跳过继续。");
        beginApp(host.now());
    }

    @Override public void onTick(long now) {
        if (finished || !token.isCurrent() || app == null) return;
        if (now >= appDeadline) {
            recordUnsure(timeoutReason());
            nextApp(now);
            return;
        }
        if (phase == 0) {
            if (now - phaseAt >= LAUNCH_TIMEOUT_MS) {
                recordUnsure("未能打开应用");
                nextApp(now);
                return;
            }
            String pkg = host.trees().activePackage();
            if (pkg.isEmpty()) pkg = host.env().foreground();
            if (app.packageName.equals(pkg) && now - phaseAt >= LAUNCH_SETTLE_MS) {
                phase = 1;
                phaseAt = now;
                entryStep = 0;
                priorTarget = null;
                log("每日签到：「" + app.name + "」已打开，正在查找签到入口。");
            }
            return;
        }
        if (host.ocr().busy()) return;
        inspectScreen();
    }

    @Override public void onStop() {
        finished = true;
    }

    /** 打开名单里的下一个 APP；未安装/启动失败的直接记录跳过。 */
    private void beginApp(long now) {
        while (index < apps.size()) {
            app = apps.get(index);
            if (!host.env().isInstalled(app.packageName)) {
                skippedCount++;
                results.add(app.name + "：未安装，已跳过");
                log("每日签到：「" + app.name + "」未安装，已跳过。");
                index++;
                continue;
            }
            host.env().resetForeground();
            if (!host.env().launch(app.packageName, true)) {
                skippedCount++;
                results.add(app.name + "：无法启动，已跳过");
                log("每日签到：「" + app.name + "」无法启动，已跳过。");
                index++;
                continue;
            }
            phase = 0;
            entryStep = 0;
            phaseAt = now;
            appDeadline = now + APP_TIMEOUT_MS;
            actionTaps = 0;
            lastTapAt = 0;
            priorTarget = null;
            log("每日签到：正在打开「" + app.name + "」。");
            return;
        }
        app = null;
        finish(summary());
    }

    private void nextApp(long now) {
        index++;
        beginApp(now);
    }

    private void inspectScreen() {
        if (Build.VERSION.SDK_INT < 30) {
            finish("系统不支持屏幕识别，每日签到未执行。");
            return;
        }
        final long capturedAt = host.now();
        final int width = host.env().screenWidth();
        final int height = host.env().screenHeight();
        final CheckinApp captured = app;
        host.ocr().recognize(boxes -> {
            if (!token.isCurrent() || finished || captured != app) return;
            handleOcrText(boxes, width, height, capturedAt);
        }, errorCode -> {
            if (!token.isCurrent() || finished || captured != app) return;
            // 截图偶发失败不立即停：下一帧重试，持续失败由单 APP 超时兜底。
            Log.i(TAG, "checkin screenshot error " + errorCode);
        });
    }

    private void handleOcrText(List<TextBlock> boxes, int width, int height, long capturedAt) {
        if (finished || app == null || boxes == null) return;
        String pkg = host.trees().activePackage();
        if (!app.packageName.equals(pkg) && !app.packageName.equals(host.env().foreground())) {
            return; // 系统弹窗等抢走前台：等它回来，超时兜底
        }
        Log.i(TAG, "checkin app=" + app.name + " phase=" + phase + " step=" + entryStep
                + " ocr=" + CheckinPolicy.describe(boxes));
        if (CheckinPolicy.isDone(boxes)) {
            doneCount++;
            String status = lastTapAt > 0 ? "签到完成" : "今日已签到";
            results.add(app.name + "：" + status);
            log("每日签到：「" + app.name + "」" + status + "。");
            nextApp(host.now());
            return;
        }
        if (phase == 1) handleEntry(boxes, width, height, capturedAt);
        else handleSign(boxes, width, height, capturedAt);
    }

    /** 找入口：首页直接入口 / 弹窗按钮 / 底部标签 → 标签页右上角入口。 */
    private void handleEntry(List<TextBlock> boxes, int width, int height, long capturedAt) {
        long now = host.now();
        TextBlock target = null;
        ActionGuard.Surface surface = ActionGuard.Surface.CHECKIN;
        if (entryStep == 0) {
            // 弹窗优先：贴底的「立即签到」比首页右上角入口更直接，避免先误点入口把弹窗关掉。
            if (app.popupFirst) target = CheckinPolicy.findPopupAction(boxes, width, height);
            if (target == null && app.homeEntry != null) target = CheckinPolicy.findHomeEntry(boxes, app.homeEntry);
            if (target == null) {
                priorTarget = null;
                // 弹窗类 APP 先等弹窗观察窗；纯标签类 APP（如比亚迪）没有弹窗，直接走标签。
                boolean popupWindowOver = !app.popupFirst || now - phaseAt >= POPUP_WAIT_MS;
                if (app.tab != null && popupWindowOver) {
                    entryStep = 1;
                    log("每日签到：「" + app.name + "」前往「" + app.tab + "」标签找签到入口。");
                }
                return;
            }
        } else if (entryStep == 1) {
            target = CheckinPolicy.findTab(boxes, app.tab, width, height);
            surface = ActionGuard.Surface.NAVIGATION;
            if (target == null) {
                priorTarget = null;
                return;
            }
        } else {
            if (now - phaseAt < TAB_SETTLE_MS) return;
            target = CheckinPolicy.findTabEntry(boxes, app.tabEntry, width, height);
            if (target == null) {
                priorTarget = null;
                return;
            }
        }
        if (!stable(target, width, height, capturedAt)) {
            priorTarget = target;
            priorWidth = width;
            priorHeight = height;
            return;
        }
        priorTarget = null;
        // 闸门校验匹配器认定的规范文案；右上角入口容忍图标前缀（OCR「6签到」），原始文案仅用于诊断。
        String gateLabel = entryStep == 2 ? app.tabEntry : (entryStep == 1 ? app.tab : target.text);
        if (!allow(surface, gateLabel)) {
            recordUnsure("入口「" + target.text + "」被安全策略拒绝");
            nextApp(now);
            return;
        }
        lastTapAt = now;
        if (entryStep == 1) {
            entryStep = 2;
            phaseAt = now;
            tap(target, "每日签到：已点击「" + app.name + "」「" + target.text + "」标签。",
                    "「" + app.tab + "」标签点击被中断");
        } else {
            phase = 2;
            phaseAt = now;
            actionTaps = 0;
            appDeadline = now + SIGN_PAGE_TIMEOUT_MS;
            tap(target, "每日签到：已点击「" + app.name + "」入口「" + target.text + "」，等待签到页。",
                    "签到入口点击被中断");
        }
    }

    /** 签到页：双帧稳定后点动作按钮；点过等完成标记，按钮仍在且过了结算期最多补点一次。 */
    private void handleSign(List<TextBlock> boxes, int width, int height, long capturedAt) {
        long now = host.now();
        // 日历型签到页（E充站）没有签到按钮，动作是点「今天」格；点顶栏「签到」标题不会有任何效果。
        TextBlock button = app.tapToday
                ? CheckinPolicy.findTodaySign(boxes, width, height)
                : CheckinPolicy.findAction(boxes);
        if (button == null) {
            priorTarget = null;
            return; // 等完成标记出现，或超时如实报未确认
        }
        if (now - lastTapAt < TAP_SETTLE_MS) return;
        if (actionTaps >= MAX_ACTION_TAPS) return;
        if (!stable(button, width, height, capturedAt)) {
            priorTarget = button;
            priorWidth = width;
            priorHeight = height;
            return;
        }
        priorTarget = null;
        if (!allow(ActionGuard.Surface.CHECKIN, button.text)) {
            recordUnsure("按钮「" + button.text + "」被安全策略拒绝");
            nextApp(now);
            return;
        }
        actionTaps++;
        lastTapAt = now;
        tap(button, "每日签到：已点击「" + app.name + "」「" + button.text + "」。",
                "「" + button.text + "」点击被中断");
    }

    private boolean stable(TextBlock target, int width, int height, long capturedAt) {
        return priorWidth == width && priorHeight == height
                && VisualTargetGuard.stable(priorTarget, target, width, height)
                && host.now() - capturedAt <= 5000;
    }

    private void tap(TextBlock box, String success, String failure) {
        final CheckinApp tapped = app;
        host.gestures().tap(new Bounds(box.left, box.top, box.right, box.bottom), 80,
                new Gestures.Callback() {
                    @Override public void onCompleted() {
                        if (token.isCurrent() && !finished && tapped == app) log(success);
                    }
                    @Override public void onCancelled() {
                        if (token.isCurrent() && !finished && tapped == app) {
                            recordUnsure(failure);
                            nextApp(host.now());
                        }
                    }
                    @Override public void onRejected() {
                        if (token.isCurrent() && !finished && tapped == app) {
                            recordUnsure(failure);
                            nextApp(host.now());
                        }
                    }
                });
    }

    private String timeoutReason() {
        switch (phase) {
            case 0: return "未能打开应用";
            case 1: return "未找到签到入口";
            default: return actionTaps > 0 ? "点击后未见完成标记" : "未找到签到按钮";
        }
    }

    private void recordUnsure(String reason) {
        unsureCount++;
        results.add(app.name + "：未确认（" + reason + "）");
        log("每日签到：「" + app.name + "」未确认（" + reason + "），跳过。");
    }

    private String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("每日签到结束：完成 ").append(doneCount)
                .append("，未确认 ").append(unsureCount)
                .append("，跳过 ").append(skippedCount).append("。");
        for (int i = 0; i < results.size(); i++) {
            sb.append(i == 0 ? " " : "；").append(results.get(i));
        }
        return sb.toString();
    }

    private void finish(String result) {
        if (finished) return;
        finished = true;
        log(result);
        host.stopForeground();
    }

    private boolean allow(ActionGuard.Surface surface, String label) {
        return gate != null && gate.allow(surface, label);
    }

    private void log(String message) {
        host.log().save(message);
    }
}