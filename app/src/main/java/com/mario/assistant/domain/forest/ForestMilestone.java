package com.mario.assistant.domain.forest;

import com.mario.assistant.domain.model.UiNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 「今日累计奖励」里程碑（+40g / +60g / +100g）。
 *
 * <p>真机上这三个按钮不是独立的「立即领取」节点，而是把奖励和文案拼成了一串
 * （`+40g立即领取`），按等于「立即领取」去匹配永远匹配不到，于是任务进度早就 12/12 了，
 * 三个奖励一直挂在那里没人领。这里单独把它们认出来。</p>
 */
public final class ForestMilestone {
    private static final Pattern CLAIMABLE = Pattern.compile(
            "^\\+?\\s*\\d+(\\.\\d+)?\\s*[a-zA-Z吨次个]{0,2}\\s*(立即领取|领取)$");
    private static final int MAX_TEXT = 16;

    private ForestMilestone() { }

    /** 返回当前可领取的里程碑节点，按屏幕从左到右排序。 */
    public static List<UiNode> claimable(List<UiNode> nodes) {
        List<UiNode> pending = new ArrayList<>();
        if (nodes == null) return pending;
        for (UiNode node : nodes) {
            if (node == null || !node.visible || !node.hasBounds() || !node.inViewport()) continue;
            String text = normalize(node.text);
            if (text.isEmpty() || text.length() > MAX_TEXT) continue;
            if (!text.contains("领取")) continue;
            if (!CLAIMABLE.matcher(text).matches()) continue;
            pending.add(node);
        }
        pending.sort(Comparator.comparingInt(node -> node.left));
        return pending;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace(" ", "").replace("\u00a0", "").trim();
    }
}
