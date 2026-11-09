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
    private static final String[] CTA_LABELS = {
            "逛一逛", "去看看", "去逛逛", "去参与", "去领取", "立即领取", "领取",
            "签到", "马上玩", "去完成", "今日完成", "已领取", "已完成", "去践行",
            "去收取", "去收", "收取"
    };
    private static final int ROW_BAND = 90;
    private static final Pattern REWARD =
            Pattern.compile("^\\+?\\d+(\\.\\d+)?(g|G|t|T|kg|吨|次|个)?$");

    public static List<ForestTask> parse(List<UiNode> nodes, int width, int height,
                                  ForestTaskPolicy.Installed installed) {
        List<ForestTask> tasks = new ArrayList<>();
        if (width <= 0 || height <= 0) return tasks;
        Set<String> seen = new HashSet<>();
        for (UiNode cta : nodes) {
            if (cta == null || !cta.visible || !cta.hasBounds() || !cta.inViewport()) continue;
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
                    if (!isCta(text) && !isReward(text)) {
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
            if (title.isEmpty()) continue;
            for (UiNode node : nodes) {
                if (node == null || node == cta || node == titleNode) continue;
                if (!node.visible || !node.hasBounds() || !node.inViewport()) continue;
                if (Math.abs(node.centerY() - cta.centerY()) > ROW_BAND) continue;
                String text = normalize(node.text);
                if (text.isEmpty() || text.equals(title) || isCta(text) || isReward(text)) continue;
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
        for (String cta : CTA_LABELS) if (cta.equals(text)) return true;
        return false;
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
