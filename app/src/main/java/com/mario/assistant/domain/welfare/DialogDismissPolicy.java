package com.mario.assistant.domain.welfare;

/**
 * 奖励弹窗关闭的取舍：无障碍节点精确文本优先，OCR 兜底。
 *
 * 真机实测（起点「恭喜获得 10点章节卡（1天）」弹窗）：ML Kit 会把同一行相邻文案
 * 「10点 / 知道了 / 888点」合并成一个文本框「10(0知道了8)8点」，OCR 坐标与文案都不可信；
 * 而安全闸门按精确文案白名单放行，于是出现「找到按钮→拒绝→不点击→下一帧再找到」的空转。
 * 因此节点可用时一律走节点，节点不可用且 OCR 文案过不了闸门时立刻停止，不再重试。
 */
public final class DialogDismissPolicy {
    public enum Action {
        /** 当前帧没有奖励弹窗，继续正常流程。 */
        IGNORE,
        /** 无障碍节点精确命中关闭按钮，按节点点击（坐标与文案都不依赖 OCR）。 */
        CLICK_NODE,
        /** 节点不可用，但 OCR 文案通过安全闸门，按 OCR 坐标点击。 */
        CLICK_OCR,
        /** 节点不可用、文案被相邻内容粘住，但框落在居中的单行按钮带内：按框中心点击。 */
        CLICK_CENTERED,
        /** 找到弹窗却无法确认按钮：停止并由用户手动处理，避免空转。 */
        STOP
    }

    private DialogDismissPolicy() { }

    public static Action decide(boolean dialogPresent, boolean dismissNodeFound, boolean ocrLabelAllowed) {
        return decide(dialogPresent, dismissNodeFound, ocrLabelAllowed, false);
    }

    public static Action decide(boolean dialogPresent, boolean dismissNodeFound, boolean ocrLabelAllowed,
                                boolean centeredButtonRow) {
        if (!dialogPresent) return Action.IGNORE;
        if (dismissNodeFound) return Action.CLICK_NODE;
        if (ocrLabelAllowed) return Action.CLICK_OCR;
        return centeredButtonRow ? Action.CLICK_CENTERED : Action.STOP;
    }
}
