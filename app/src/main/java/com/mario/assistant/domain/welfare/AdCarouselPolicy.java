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

    /**
     * 关闭按钮被相邻文案「粘」住时的几何兜底：真机把「10点 / 知道了 / 888点」并成
     * 「10(0知道了8)8点@418,1714-802,1767」，框中心正好落在居中的胶囊按钮上。
     * 只认「水平居中 + 按钮宽度 + 单行行高」三件事同时成立，避免把整行或整块容器当成按钮。
     */
    public static boolean isCenteredButtonRow(TextBlock box, int imageWidth, int imageHeight) {
        if (box == null || imageWidth <= 0 || imageHeight <= 0) return false;
        int width = box.right - box.left;
        int height = box.bottom - box.top;
        if (width <= 0 || height <= 0) return false;
        int centerX = (box.left + box.right) / 2;
        return Math.abs(centerX - imageWidth / 2) <= imageWidth / 10
                && width >= imageWidth / 5 && width <= imageWidth * 45 / 100
                && height <= imageHeight * 6 / 100;
    }
}
