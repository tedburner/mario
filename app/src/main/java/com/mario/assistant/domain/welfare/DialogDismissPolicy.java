package com.mario.assistant.domain.welfare;

/**
 * 奖励弹窗关闭的取舍：无障碍节点精确文本优先，OCR 兜底。
 *
 * 真机实测（起点「恭喜获得 10点章节卡（1天）」弹窗）：ML Kit 会把同一行相邻文案
 * 「10点 / 知道了 / 888点」合并成一个文本框「10(0知道了8)8点」，OCR 坐标与文案都不可信；
 * 而安全闸门按精确文案白名单放行，于是出现「找到按钮→拒绝→不点击→下一帧再找到」的空转。
 *
 * 2026-11-23 真机补测暴露另一种粘连形态：整行被读成
 * 「10点章节卡惊喜礼(知道了)券@120,1706-788,1752」——宽度 55.7% 超过按钮带上限、
 * 框中心 454 偏离屏幕中线 146px，文案闸门与几何闸门同时判死，弹窗永远关不掉
 * （叠加 STOP 计数失效，空转 160 秒）。该弹窗的按钮行本身仍水平居中（干净帧为
 * 「知道了@533,1711-661,1756」），故新增一条兜底：框里含「知道了」时按
 * 「屏幕水平中心 + 框垂直中线」点击——不依赖框的左右边界，对合并行同样成立。
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
        /** 节点不可用、文案被粘成整行：按「屏幕中线 + 框垂直中线」点击按钮所在行。 */
        CLICK_EMBEDDED_CENTER,
        /** 找到弹窗却无法确认按钮：停止并由用户手动处理，避免空转。 */
        STOP
    }

    private DialogDismissPolicy() { }

    public static Action decide(boolean dialogPresent, boolean dismissNodeFound, boolean ocrLabelAllowed) {
        return decide(dialogPresent, dismissNodeFound, ocrLabelAllowed, false, false);
    }

    public static Action decide(boolean dialogPresent, boolean dismissNodeFound, boolean ocrLabelAllowed,
                                boolean centeredButtonRow) {
        return decide(dialogPresent, dismissNodeFound, ocrLabelAllowed, centeredButtonRow, false);
    }

    /**
     * @param labelEmbedded OCR 框里含关闭按钮文案、但整行被粘连（文案已不等于「知道了」）
     */
    public static Action decide(boolean dialogPresent, boolean dismissNodeFound, boolean ocrLabelAllowed,
                                boolean centeredButtonRow, boolean labelEmbedded) {
        if (!dialogPresent) return Action.IGNORE;
        if (dismissNodeFound) return Action.CLICK_NODE;
        if (ocrLabelAllowed) return Action.CLICK_OCR;
        if (centeredButtonRow) return Action.CLICK_CENTERED;
        // 文案被粘成整行：框左右边界不可信，但按钮行仍水平居中，改按屏幕中线点。
        return labelEmbedded ? Action.CLICK_EMBEDDED_CENTER : Action.STOP;
    }
}
