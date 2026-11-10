package com.mario.assistant.application.forest;

import android.view.accessibility.AccessibilityNodeInfo;
import com.mario.assistant.application.ActionGate;
import com.mario.assistant.application.RunHost;
import com.mario.assistant.application.RunToken;
import com.mario.assistant.application.TaskRunner;
import com.mario.assistant.domain.forest.EnergyPagePolicy;
import com.mario.assistant.domain.model.Bounds;
import com.mario.assistant.domain.model.TextBlock;
import com.mario.assistant.domain.safety.ActionGuard;
import com.mario.assistant.infrastructure.a11y.Gestures;
import com.mario.assistant.infrastructure.a11y.UiTree;
import com.mario.assistant.infrastructure.system.DeviceEnvironment;
import java.util.ArrayList;
import java.util.List;

/**
 * 蚂蚁森林收能量：先收自己树上/边上的气泡，再进「找能量」逐个好友「一键收」并上滑切下一位。
 *
 * <p>真机取证（2026-11-10）：好友森林页是画布渲染，按钮与气泡都不在无障碍树里，只能 OCR。
 * 一键收收集完按钮消失；上滑切换下一位好友后按钮再出现；少数好友有两批能量，收完会再出一次。</p>
 */
public final class ForestEnergyRunner implements TaskRunner {
    private static final String ALIPAY = DeviceEnvironment.ALIPAY;
    /** 每次手势后留给页面结算的时间。 */
    private static final long ACTION_SETTLE_MS = 1200;
    private static final long TAP_SETTLE_MS = 900;
    /** 好友页是画布渲染，切换后按钮要 1~2s 才画出来；连续这么多帧都没有才认定为空。 */
    private static final int EMPTY_GRACE_FRAMES = 3;
    /** 点「找能量」后页面可能被奖励/活动弹窗盖住，先给足加载与弹窗出现的时间再判定离开。 */
    private static final int FRIEND_PAGE_GRACE_FRAMES = 5;

    private RunHost host;
    private RunToken token;
    private ActionGate gate;
    private boolean finished;

    /** 0=导航到森林主页 1=收自己能量 2=打开找能量 3=逐个好友 4=收尾 */
    private int stage;
    private long deadline;
    private long stageAt;
    private long lastActionAt;

    private int bubblesTapped;
    private int selfEmptyRounds;
    private int entryTries;
    private int visitedFriends;
    private int emptyFriends;
    private int tapsOnFriend;
    private int collectMisses;
    private int collectTaps;
    private int backTries;
    private int strayPages;
    private int lostFriendPage;
    private int friendPageMisses;
    private int rewardCloseTries;

    @Override public String id() { return "forest-energy"; }

    @Override public long tickIntervalMs() { return 1200; }

    @Override public long initialDelayMs() { return 600; }

    @Override public boolean isFinished() { return finished; }

    @Override public void onStart(RunHost host, RunToken token) {
        this.host = host;
        this.token = token;
        this.gate = new ActionGate(host.log(), token);
        deadline = host.now() + 10 * 60 * 1000;
        log("森林能量已启动：先收自己树上的能量，再进「找能量」逐个好友一键收。");
    }

    @Override public void onTick(long now) {
        if (finished || !token.isCurrent()) return;
        if (now >= deadline) {
            stop("森林能量：超时停止；已点「一键收」" + collectTaps + " 次，走完 "
                    + visitedFriends + " 位好友，自收气泡 " + bubblesTapped + " 个。");
            return;
        }
        if (host.ocr().busy()) return;
        try {
            if (stage == 0) navigateToForest(now);
            else if (stage == 1) collectOwn(now);
            else if (stage == 2) openEnergyList(now);
            else if (stage == 3) collectFriends(now);
            else if (stage == 4) wrapUp(now);
        } catch (RuntimeException e) {
            log("forest energy error " + e);
            stop("森林能量异常停止：" + e.getMessage());
        }
    }

    @Override public void onStop() { finished = true; }

    /** 不在支付宝就唤起；在支付宝首页就点「蚂蚁森林」；已到森林主页则进入收能量。 */
    private void navigateToForest(long now) {
        String pkg = host.env().foreground();
        if (pkg.isEmpty()) return;
        if (!ALIPAY.equals(pkg)) {
            if (now - stageAt < 4000) return;
            stageAt = now;
            host.env().home();
            if (!host.env().launch(ALIPAY, false)) stop("森林能量：无法打开支付宝，请手动进入蚂蚁森林。");
            else log("森林能量：已唤起支付宝（当前前台 " + pkg + "）。");
            return;
        }
        if (now - lastActionAt < ACTION_SETTLE_MS) return;
        lastActionAt = now;
        host.ocr().recognize(boxes -> {
            if (!token.isCurrent() || stage != 0) return;
            List<String> texts = texts(boxes);
            if (EnergyPagePolicy.isForestHome(texts)) {
                stage = 1;
                stageAt = host.now();
                log("森林能量：已在蚂蚁森林主页，开始收取自己树上的能量。");
                return;
            }
            if (hasAny(texts, "扫一扫", "收付款", "卡包")) {
                UiTree tree = host.trees().capture();
                AccessibilityNodeInfo icon = tree.findClickableText("蚂蚁森林");
                if (icon != null && host.uiActions().clickNodeOrParent(icon)) {
                    log("森林能量：已点击「蚂蚁森林」入口。");
                    return;
                }
            }
            if (backTries < 2 && host.now() - stageAt > 6000) {
                backTries++;
                stageAt = host.now();
                host.env().back();
                log("森林能量：当前不在森林主页，返回（第 " + backTries + " 次）。");
            } else if (backTries >= 2 && host.now() - stageAt > 12000) {
                stop("森林能量：无法自动进入蚂蚁森林主页，请手动打开后重试。");
            }
        }, null);
    }

    /** 自己树上/边上：逐个点成熟气泡，连续两轮没有气泡就进「找能量」。 */
    private void collectOwn(long now) {
        if (now - lastActionAt < TAP_SETTLE_MS) return;
        lastActionAt = now;
        host.ocr().recognize(boxes -> {
            if (!token.isCurrent() || stage != 1) return;
            List<String> texts = texts(boxes);
            if (!EnergyPagePolicy.isForestHome(texts)) {
                // 真机踩到：部分「立得5g」推广气泡点开会跳到购物页；离开主页就先退回再定位
                if (strayPages < 2) {
                    strayPages++;
                    host.env().back();
                    stage = 0;
                    stageAt = host.now();
                    log("森林能量：点击后离开了森林主页，已返回（第 " + strayPages + " 次）。");
                    return;
                }
                stop("森林能量：页面连续偏离森林主页，请手动回到主页后重试。");
                return;
            }
            TextBlock bubble = firstOwnBubble(boxes);
            if (bubble != null && bubblesTapped < EnergyPagePolicy.MAX_SELF_BUBBLES) {
                bubblesTapped++;
                selfEmptyRounds = 0;
                tap(bubble);
                log("森林能量：已收取自己/边上能量「" + bubble.text + "」。");
                return;
            }
            if (++selfEmptyRounds >= 2) {
                stage = 2;
                stageAt = host.now();
                entryTries = 0;
                log("森林能量：自己树上暂无更多能量，进入「找能量」。");
            }
        }, null);
    }

    private void openEnergyList(long now) {
        if (now - stageAt < 800) return;
        if (host.ocr().busy()) return;
        stageAt = now;
        host.ocr().recognize(boxes -> {
            if (!token.isCurrent() || stage != 2) return;
            if (!EnergyPagePolicy.isForestHome(texts(boxes))) {
                stage = 0;
                stageAt = host.now();
                log("森林能量：不在森林主页，重新定位。");
                return;
            }
            TextBlock entry = rightmost(boxes, "找能量");
            if (entry != null && allow(ActionGuard.Surface.NAVIGATION, entry.text)) {
                // OCR 框圈住的是「找能量 >」这串文字，比按钮热区偏右下约 1/4 框宽；
                // 真机（1200x2670）实测热区中心在 (935~1000, 1712)，即框左移 0.28 框宽、上移 0.2 框高。
                int w = entry.right - entry.left;
                int h = entry.bottom - entry.top;
                int x = entry.left - (int) (w * 0.28);
                int y = entry.top - (int) (h * 0.2);
                log("森林能量：「找能量」框 (" + entry.left + "," + entry.top + ")-("
                        + entry.right + "," + entry.bottom + ")，点击 (" + x + "," + y + ")");
                tapAt(x, y);
                stage = 3;
                stageAt = host.now();
                lastActionAt = host.now();
                tapsOnFriend = 0;
                collectMisses = 0;
                friendPageMisses = 0;
                lostFriendPage = 0;
                log("森林能量：已点击「找能量」，开始逐个好友收取。");
                return;
            }
            if (++entryTries >= 3) {
                stop("森林能量：找不到「找能量」入口，请确认支付宝停留在蚂蚁森林主页。");
            }
        }, null);
    }

    /**
     * 好友循环：看到「一键收」就点，同一位最多两次；按钮一消失立刻上滑下一位。
     *
     * <p>不判断能量数值是否变化——按钮消失就是收完了，按钮还在就再点一次。</p>
     */
    private void collectFriends(long now) {
        if (now - lastActionAt < ACTION_SETTLE_MS) return;
        lastActionAt = now;
        host.ocr().recognize(boxes -> {
            if (!token.isCurrent() || stage != 3) return;
            TextBlock collect = findCollectAll(boxes);
            if (collect != null && EnergyPagePolicy.canCollectAgain(tapsOnFriend)) {
                collectMisses = 0;
                rewardCloseTries = 0;
                tapsOnFriend++;
                collectTaps++;
                emptyFriends = 0;
                tap(collect);
                log("森林能量：第 " + (visitedFriends + 1) + " 位好友，已点击「一键收」（第 "
                        + tapsOnFriend + " 次）。");
                return;
            }
            if (collect != null || tapsOnFriend > 0) {
                // 按钮已点满两次仍可点，或按钮已消失（说明收完了）：换下一位
                swipeUp();
                return;
            }
            List<String> texts = texts(boxes);
            if (!EnergyPagePolicy.isFriendPage(texts)) {
                // 真机踩到：点「找能量」后常被「奖励」弹窗盖住，锚点识别不到；先等几帧再判断
                if (++friendPageMisses < FRIEND_PAGE_GRACE_FRAMES) return;
                friendPageMisses = 0;
                if (EnergyPagePolicy.isRewardSheet(texts)) {
                    // 奖励弹窗是浮层，通常几秒后自己消失；点关闭会把整个「找能量」页一起退掉
                    if (++rewardCloseTries <= 3) {
                        log("森林能量：奖励弹窗遮挡，先等它自动消失（" + rewardCloseTries + "/3）。");
                        return;
                    }
                    rewardCloseTries = 0;
                    stage = 2;
                    stageAt = host.now();
                    entryTries = 0;
                    log("森林能量：奖励弹窗持续遮挡，回主页重新进入。");
                    return;
                }
                if (lostFriendPage < 3) {
                    lostFriendPage++;
                    friendPageMisses = 0;
                    stage = 2;
                    stageAt = host.now();
                    entryTries = 0;
                    log("森林能量：已不在好友能量页，重新定位入口（第 " + lostFriendPage + " 次）。");
                    return;
                }
                stage = 4;
                stageAt = host.now();
                log("森林能量：多次离开好友能量页，准备收尾。");
                return;
            }
            friendPageMisses = 0;
            lostFriendPage = 0;
            // 走到这里说明这一位没有「一键收」可点；先给画布渲染留几帧，避免把加载中当成空
            if (++collectMisses < EMPTY_GRACE_FRAMES) return;
            collectMisses = 0;
            emptyFriends++;
            if (EnergyPagePolicy.shouldStop(emptyFriends)) {
                stage = 4;
                stageAt = host.now();
                log("森林能量：连续 " + emptyFriends + " 位好友没有可收能量，准备收尾。");
                return;
            }
            if (EnergyPagePolicy.reachedFriendBudget(visitedFriends + 1)) {
                stage = 4;
                stageAt = host.now();
                log("森林能量：已走完 " + EnergyPagePolicy.MAX_FRIENDS + " 位好友，准备收尾。");
                return;
            }
            swipeUp();
        }, null);
    }

    private void swipeUp() {
        int width = host.env().screenWidth();
        int height = host.env().screenHeight();
        host.gestures().swipe(width / 2, height * 3 / 4, width / 2, height / 4, 400, NO_OP);
        visitedFriends++;
        tapsOnFriend = 0;
        collectMisses = 0;
        friendPageMisses = 0;
        log("森林能量：已上滑到下一位好友（第 " + (visitedFriends + 1) + " 位）。");
    }

    private void wrapUp(long now) {
        if (now - stageAt < 1200) return;
        stageAt = now;
        host.env().back();
        stop("森林能量：收取完成。共点「一键收」" + collectTaps + " 次，走完 " + visitedFriends
                + " 位好友，自收气泡 " + bubblesTapped + " 个。");
    }

    private TextBlock firstOwnBubble(List<TextBlock> boxes) {
        if (boxes == null) return null;
        int width = host.env().screenWidth();
        int height = host.env().screenHeight();
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            if (!EnergyPagePolicy.isEnergyBubble(box.text)) continue;
            int cx = (box.left + box.right) / 2;
            int cy = (box.top + box.bottom) / 2;
            if (!EnergyPagePolicy.isBubbleInTreeBand(cx, cy, width, height)) continue;
            return box;
        }
        return null;
    }

    private TextBlock findCollectAll(List<TextBlock> boxes) {
        if (boxes == null) return null;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            if (!EnergyPagePolicy.isCollectAll(box.text)) continue;
            if (!allow(ActionGuard.Surface.ENERGY_PAGE, box.text)) continue;
            return box;
        }
        return null;
    }

    private void tap(TextBlock box) {
        host.gestures().tap(new Bounds(box.left, box.top, box.right, box.bottom), 80, NO_OP);
    }

    private void tapAt(int x, int y) {
        int width = host.env().screenWidth();
        int height = host.env().screenHeight();
        int cx = Math.max(1, Math.min(width - 2, x));
        int cy = Math.max(1, Math.min(height - 2, y));
        host.gestures().tap(new Bounds(cx - 20, cy - 20, cx + 20, cy + 20), 80, NO_OP);
    }

    private static List<String> texts(List<TextBlock> boxes) {
        List<String> texts = new ArrayList<>();
        if (boxes == null) return texts;
        for (TextBlock box : boxes) {
            if (box != null && box.text != null) texts.add(box.text);
        }
        return texts;
    }

    private static boolean hasAny(List<String> texts, String... needles) {
        for (String text : texts) {
            for (String needle : needles) if (text.contains(needle)) return true;
        }
        return false;
    }

    /**
     * 在屏幕下半部找最靠右的「找能量」入口。
     *
     * <p>真机踩到：底部图标行左「领奖励」、右「找能量」，OCR 取第一个框时会点到「领奖励」，
     * 弹出奖励页；这里只认下半部、排除「上划找更多能量」，并取最右侧那个。</p>
     */
    private TextBlock rightmost(List<TextBlock> boxes, String needle) {
        if (boxes == null) return null;
        int height = host.env().screenHeight();
        TextBlock best = null;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            if (!box.text.contains(needle) || box.text.contains("上划")) continue;
            if ((box.top + box.bottom) / 2 < height * 0.5) continue;
            if (best == null || box.right > best.right) best = box;
        }
        return best;
    }

    private boolean allow(ActionGuard.Surface surface, String label) {
        return gate != null && gate.allow(surface, label);
    }

    private void stop(String reason) {
        if (finished) return;
        finished = true;
        log(reason);
        host.stopForeground();
    }

    private void log(String message) {
        host.log().save(message);
    }

    private final Gestures.Callback NO_OP = new Gestures.Callback() {
        @Override public void onCompleted() { }
        @Override public void onCancelled() { }
        @Override public void onRejected() { }
    };
}
