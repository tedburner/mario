package com.mario.assistant.domain.forest;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 「找能量」页的判定规则（纯 JVM，可单测）。
 *
 * <p>真机取证（2026-11-10 / Redmi 24129PN74C / 屏 1200x2670）：
 * 好友森林页是画布渲染，「一键收」与能量气泡都不在无障碍树里，只能靠 OCR 定位。
 * 一键收收集完按钮会消失；上滑切换下一位好友，下一位又出现「一键收」；
 * 少数好友有两批能量，第一轮收完稍后还会再出一次按钮。</p>
 */
public final class EnergyPagePolicy {
    /** 单次试运行最多切换多少位好友，防止在好友链上无限上滑。 */
    public static final int MAX_FRIENDS = 30;
    /** 连续多少位好友没有可收能量就收尾。 */
    public static final int MAX_EMPTY_FRIENDS = 3;
    /** 自己树上/边上最多点几个能量气泡。 */
    public static final int MAX_SELF_BUBBLES = 8;
    /** 同一位好友最多点几次「一键收」（有些好友有两批能量）。 */
    public static final int MAX_COLLECT_PER_FRIEND = 2;

    private static final Pattern BUBBLE =
            Pattern.compile("^\\d+(\\.\\d+)?\\s*(g|kg|G|KG)$");

    private EnergyPagePolicy() { }

    /** 收集全部能量的入口按钮。 */
    public static boolean isCollectAll(String text) {
        return text != null && text.replace(" ", "").contains("一键收");
    }

    /** 好友森林页锚点：上划提示只在好友页出现。 */
    public static boolean isFriendPage(List<String> texts) {
        if (texts == null) return false;
        for (String text : texts) {
            if (text != null && text.contains("上划找更多能量")) return true;
        }
        return false;
    }

    /**
     * 森林主页锚点：底部有「找能量」按钮。
     *
     * <p>真机踩到：奖励页有「找能量共获得 627g」，只按 contains("找能量") 会把奖励页认成主页，
     * 随后把奖励页上的数字当能量气泡点掉。这里排除非按钮文案，并排除奖励弹窗。</p>
     */
    public static boolean isForestHome(List<String> texts) {
        if (texts == null) return false;
        if (isRewardSheet(texts)) return false;
        for (String text : texts) {
            if (text == null) continue;
            if (text.contains("森林广场")) return true;
            if (text.contains("找能量") && !text.contains("找能量共获得")) return true;
        }
        return false;
    }

    /** 能量气泡文案：严格「数字 + g」，避免把「立得5g」「36g 10天」当成气泡。 */
    public static boolean isEnergyBubble(String text) {
        return text != null && BUBBLE.matcher(text.trim()).matches();
    }

    /**
     * 气泡是否落在树冠可收区域。
     *
     * <p>真机（1200x2670）实测的干扰项：右上「252716g」总能量、右下 PK 进度「540g / 674g」、
     * 左上「0g」计数、右侧「1t」抽奖入口都在 x&gt;0.80 或 x&lt;0.12；成熟气泡集中在树冠周围。
     * 收窄到中间区域，宁可少收也不误点。</p>
     */
    public static boolean isBubbleInTreeBand(int centerX, int centerY, int width, int height) {
        if (width <= 0 || height <= 0) return false;
        double xRatio = centerX / (double) width;
        double yRatio = centerY / (double) height;
        return xRatio > 0.12 && xRatio < 0.80 && yRatio > 0.14 && yRatio < 0.60;
    }

    /**
     * 是否是「奖励」弹窗。
     *
     * <p>真机取证（2026-11-10 15:03）：点「找能量」后页面常被奖励弹窗盖住，
     * 弹窗里只有「今日继续赚能量 / 去守护 / 去下单 / 去完成」等文案，没有好友页的「上划找更多能量」。
     * 命中后必须先关掉它，否则会被误判成「已离开好友页」。</p>
     */
    public static boolean isRewardSheet(List<String> texts) {
        if (texts == null) return false;
        boolean headline = false;
        boolean action = false;
        for (String text : texts) {
            if (text == null) continue;
            // 奖励页独有文案，出现即判定
            if (text.contains("找能量共获得")) return true;
            if (text.contains("今日继续赚能量") || text.contains("任务进度")
                    || text.contains("今日累计奖励")) headline = true;
            if (text.contains("去守护") || text.contains("去下单")
                    || text.contains("去完成") || text.contains("立即领取")) action = true;
        }
        return headline && action;
    }

    /** 连续空好友数达到阈值即收尾。 */
    public static boolean shouldStop(int emptyFriends) {
        return emptyFriends >= MAX_EMPTY_FRIENDS;
    }

    public static boolean reachedFriendBudget(int visited) {
        return visited >= MAX_FRIENDS;
    }

    /** 同一好友还能不能再点一次一键收。 */
    public static boolean canCollectAgain(int tapsOnFriend) {
        return tapsOnFriend < MAX_COLLECT_PER_FRIEND;
    }
}
