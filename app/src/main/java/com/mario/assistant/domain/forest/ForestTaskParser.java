package com.mario.assistant.domain.forest;

import com.mario.assistant.domain.model.UiNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从无障碍树拍平出的节点中解析森林任务行。
 * 不依赖任务名：用 CTA 文案定位行，再按几何关系取左侧标题、右侧奖励和行内说明。
 */
public final class ForestTaskParser {
    /**
     * 用于「这一行左边那句是不是标题」的严格判定。
     * 不能直接用 CtaClassifier：标题本身常以动词开头（「玩一玩森林乐消消」「去守护古树得奖励」），
     * 宽松判定会把标题误判成按钮，整行就丢了。
     */
    private static final String[] STRICT_CTA = {
            "逛一逛", "去看看", "去逛逛", "去参与", "去领取", "立即领取", "领取",
            "签到", "马上玩", "去完成", "今日完成", "已领取", "已完成", "去践行",
            "去收取", "去收", "收取", "去抽奖", "去守护", "去下单", "去购买", "去兑换",
            "去看全部", "立即抽奖", "收下奖励"
    };
    private static final int ROW_BAND = 90;
    /** 弹窗头部（活力值、森林寻宝、页签、顶部横幅）在锚点失效时会被误当任务行，直接排除。 */
    private static final String[] HEADER_TITLES = {
            "我的活力值", "森林寻宝", "天地灵根", "1吨能量", "活力值奖励", "小队奖励",
            "返回签到", "限时挑战", "今日累计奖励", "任务进度", "翻倍卡", "今日继续赚能量"
    };
    /** 标题至少要有中文或字母数字，排除滚动错位时抓到的「g」「--」这类碎片。 */
    private static final Pattern MEANINGFUL_TITLE = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9]");
    private static final Pattern REWARD =
            Pattern.compile("^\\+?\\d+(\\.\\d+)?(g|G|t|T|kg|吨|次|个)?$");
    /** 里程碑那排（+40g已领取 / +60g立即领取），用作任务列表的上边界。 */
    private static final Pattern MILESTONE_ROW =
            Pattern.compile("^\\+?\\d+(\\.\\d+)?(g|G|t|T|kg)?(立即领取|已领取)$");

    public static List<ForestTask> parse(List<UiNode> nodes, int width, int height,
                                  ForestTaskPolicy.Installed installed) {
        List<ForestTask> tasks = new ArrayList<>();
        if (width <= 0 || height <= 0) return tasks;
        // 「任务进度:」以下才是任务列表；上方是活力值兑换、森林寻宝抽奖、里程碑奖励，不能当任务行点。
        int listTop = taskListTop(nodes);
        Set<String> seen = new HashSet<>();
        for (UiNode cta : nodes) {
            if (cta == null || !cta.visible || !cta.hasBounds() || !cta.inViewport()) continue;
            if (cta.centerY() < listTop) continue;
            String ctaText = normalize(cta.text);
            if (!isCta(ctaText)) continue;
            if (cta.left < 0 || cta.right > width || cta.top < 0 || cta.bottom > height) continue;

            UiNode titleNode = null;
            String reward = null;
            StringBuilder detail = new StringBuilder();
            for (UiNode node : nodes) {
                if (node == null || node == cta || !node.visible || !node.hasBounds() || !node.inViewport()) continue;
                if (Math.abs(node.centerY() - cta.centerY()) > ROW_BAND) continue;
                String text = normalize(node.text);
                if (text.isEmpty()) continue;
                if (node.right <= cta.left) {
                    if (!isCtaLike(text) && !isReward(text)) {
                        if (titleNode == null || node.right > titleNode.right
                                || (node.right == titleNode.right && area(node) < area(titleNode))) {
                            titleNode = node;
                        }
                    }
                } else if (reward == null && isReward(text)) {
                    reward = text;
                }
            }
            if (titleNode == null) continue;
            String title = normalize(titleNode.text);
            if (title.isEmpty() || title.equals(ctaText)) continue;
            if (title.length() < 2 || !MEANINGFUL_TITLE.matcher(title).find()) continue;
            if (isHeaderTitle(title)) continue;
            for (UiNode node : nodes) {
                if (node == null || node == cta || node == titleNode) continue;
                if (!node.visible || !node.hasBounds() || !node.inViewport()) continue;
                if (Math.abs(node.centerY() - cta.centerY()) > ROW_BAND) continue;
                String text = normalize(node.text);
                if (text.isEmpty() || text.equals(title) || isCtaLike(text) || isReward(text)) continue;
                if (detail.length() > 0) detail.append(' ');
                detail.append(text);
            }
            String key = title + "|" + ctaText;
            if (!seen.add(key)) continue;
            ForestTaskPolicy.Decision decision =
                    ForestTaskPolicy.decide(title, ctaText, detail.toString(), installed);
            tasks.add(new ForestTask(title, ctaText, reward, detail.toString(), cta.id,
                    cta.left, cta.top, cta.right, cta.bottom, decision.kind,
                    decision.targetPackage, decision.observeThirdParty, decision.reason));
        }
        tasks.sort((a, b) -> Integer.compare(a.top, b.top));
        return tasks;
    }

    public static String describe(List<ForestTask> tasks) {
        StringBuilder sb = new StringBuilder();
        for (ForestTask task : tasks) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(task.title).append('/').append(task.cta).append('/').append(task.kind);
            if (task.kind == ForestTask.Kind.SKIP) sb.append("(").append(task.reason).append(')');
        }
        return sb.toString();
    }

    private static boolean isCta(String text) {
        return CtaClassifier.classify(text) != CtaClassifier.Action.NONE;
    }

    private static boolean isCtaLike(String text) {
        for (String cta : STRICT_CTA) if (cta.equals(text)) return true;
        return false;
    }

    private static boolean isHeaderTitle(String title) {
        for (String header : HEADER_TITLES) if (title.contains(header)) return true;
        return false;
    }

    /**
     * 任务列表的起始 y。
     *
     * <p>真机上「今日累计奖励」是画布文本，无障碍树里没有；任务做满后「任务进度」又会变成
     * 「明天再来」，两个锚点都会消失。所以再退一层：用里程碑那排（+40g已领取 / +60g立即领取）
     * 的底边作分界。三者都没有（已经滚出屏）才按 0 处理——那种情况下头部也不在屏上。</p>
     */
    private static int taskListTop(List<UiNode> nodes) {
        int top = 0;
        for (UiNode node : nodes) {
            if (node == null || node.text == null) continue;
            String text = normalize(node.text);
            if (text.startsWith("任务进度") || text.contains("今日累计奖励")
                    || text.startsWith("明天再来") || MILESTONE_ROW.matcher(text).matches()) {
                top = Math.max(top, node.bottom);
            }
        }
        return top;
    }

    private static boolean isReward(String text) {
        return REWARD.matcher(text).matches();
    }

    private static int area(UiNode node) {
        return (node.right - node.left) * (node.bottom - node.top);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace(" ", "").replace("\u00a0", "").trim();
    }
}
