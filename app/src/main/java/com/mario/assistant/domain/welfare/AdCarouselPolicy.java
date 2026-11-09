package com.mario.assistant.domain.welfare;

import com.mario.assistant.domain.model.TextBlock;

import java.util.List;

/**
 * 起点激励视频的多任务轮播：完成一支广告后，顶部进度条提示“滑动继续完成”，
 * 左滑进入下一支广告；全部完成后提示“恭喜完成任务”，可关闭广告页。
 * 这里只做 OCR 文本判定，手势与流程状态在 TaskService。
 */
public final class AdCarouselPolicy {
    public static boolean isContinuePrompt(List<TextBlock> boxes) {
        // 真机整行为“已完成第2个任务 滑动继续完成 10点章节卡”；ML Kit 可能把它拆成多个框，
        // 因此“滑动”和“继续”允许分布在不同框里，但必须同时出现。
        boolean slide = false, cont = false;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            if (text.contains("滑动")) slide = true;
            if (text.contains("继续")) cont = true;
        }
        return slide && cont;
    }

    public static boolean isCompletion(List<TextBlock> boxes) {
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            if (box.text.replace(" ", "").contains("恭喜完成任务")) return true;
        }
        return false;
    }

    /** 轮播完成后的奖励弹窗按钮（真机：“恭喜获得 10点章节卡（1天）”+“知道了”）。 */
    public static TextBlock findDismissButton(List<TextBlock> boxes) {
        boolean rewardDialog = false;
        TextBlock found = null;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            // ML Kit 实测会把“恭喜获得”误读为“恭套获得”，不能只认完整“恭喜”。
            if (text.contains("恭") || text.contains("获得")) rewardDialog = true;
            // OCR 可能带标点或误识，按“知道”包含匹配；必须伴随奖励文案，避免误点其他页面的提示。
            if (!text.contains("知道")) continue;
            if (found != null) return null;
            found = box;
        }
        return rewardDialog ? found : null;
    }
}
