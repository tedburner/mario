package com.mario.assistant.domain.checkin;

import com.mario.assistant.domain.model.TextBlock;

import java.util.List;

/**
 * 每日签到的 OCR 匹配策略：全部精确文案 + 几何带 + 唯一候选，歧义即放弃。
 * 签到页同屏常有推广按钮，宁可找不到也不泛化点击（与携程 SigninPolicy 同一边界）。
 */
public final class CheckinPolicy {
    /** 签到动作按钮候选，按优先级排列；只认精确文案，「签到领红包」等推广不匹配。 */
    public static final String[] ACTION_LABELS = {"立即签到", "去签到", "签到"};
    /** 完成标记：出现任一即视为今日已签到（含点过入口后页面直接显示已签到的情况）。 */
    public static final String[] DONE_MARKERS = {"已签到", "签到成功", "明日再来", "明天再来"};
    /** 「签到成功」的形近字容错：中间一个字可能被 OCR 读错（成→戌/戍）。 */
    private static final java.util.regex.Pattern SUCCESS_PATTERN =
            java.util.regex.Pattern.compile("签到.功");
    /** 收尾领奖按钮候选：与 ActionGuard.CHECKIN_DISMISS 白名单保持一致。 */
    private static final String[] WRAPUP_CLAIM_LABELS = {"打开", "领取", "领取奖励"};
    /** 收尾关闭按钮候选：与 ActionGuard.CHECKIN_DISMISS 白名单保持一致。 */
    private static final String[] WRAPUP_DISMISS_LABELS = {"好的", "我知道了", "知道了", "关闭", "收下", "开心收下"};

    private CheckinPolicy() { }

    /** 首页直接入口（如「签到福利」）：全屏唯一精确匹配。 */
    public static TextBlock findHomeEntry(List<TextBlock> boxes, String label) {
        return uniqueExact(boxes, label, 0, Integer.MAX_VALUE, 0);
    }

    /**
     * 首页签到弹窗按钮：15%~98% 高（排除状态栏与手势条）内按优先级找唯一精确匹配。
     * 弹窗的签到按钮常贴底（真机 E充站「立即签到」底边在 97%），所以下界要贴近屏幕底部；
     * 上界仍留出状态栏与首页右上角「签到」入口，避免把入口误当弹窗按钮。
     * 标题「签到」与按钮「签到」同屏时视为歧义返回 null，交由标签路径兜底。
     */
    public static TextBlock findPopupAction(List<TextBlock> boxes, int width, int height) {
        if (width <= 0 || height <= 0) return null;
        for (String label : ACTION_LABELS) {
            TextBlock found = uniqueExact(boxes, label, height * 15 / 100, height * 98 / 100, 0);
            if (found != null) return found;
        }
        return null;
    }

    /** 底部导航标签（「我」/「我的」）：底边 12% 带内唯一精确匹配。 */
    public static TextBlock findTab(List<TextBlock> boxes, String label, int width, int height) {
        if (width <= 0 || height <= 0 || label == null) return null;
        return uniqueExact(boxes, label, height * 88 / 100, Integer.MAX_VALUE, 0);
    }

    /** 标签页右上角签到入口：顶栏 20% 带内、右半屏、唯一匹配；容忍按钮图标被 OCR 成前缀（如「6签到」）。 */
    public static TextBlock findTabEntry(List<TextBlock> boxes, String label, int width, int height) {
        if (width <= 0 || height <= 0 || label == null) return null;
        return unique(boxes, label, 0, height * 20 / 100, width / 2, true);
    }

    /** 签到页动作按钮：按优先级全屏唯一精确匹配。 */
    public static TextBlock findAction(List<TextBlock> boxes) {
        for (String label : ACTION_LABELS) {
            TextBlock found = uniqueExact(boxes, label, 0, Integer.MAX_VALUE, 0);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * 日历型签到页的「今天」格（真机 E充站）：签到动作就是点今天那一格，页面没有签到按钮。
     * 限定 20%~90% 高，避开顶栏标题与底部广告；唯一精确匹配，多处出现即放弃。
     */
    public static TextBlock findTodaySign(List<TextBlock> boxes, int width, int height) {
        if (width <= 0 || height <= 0) return null;
        return uniqueExact(boxes, "今天", height * 20 / 100, height * 90 / 100, 0);
    }

    /** 任一文本包含完成标记即视为已签到。 */
    public static boolean isDone(List<TextBlock> boxes) {
        if (boxes == null) return false;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            for (String marker : DONE_MARKERS) {
                if (text.contains(marker)) return true;
            }
            // 真机 OCR 会把「签到成功」误读成「签到戌功」等形近字，容忍中间单字替换。
            if (SUCCESS_PATTERN.matcher(text).find()) return true;
        }
        return false;
    }

    /**
     * 收尾阶段成功弹窗上的领奖按钮（真机比亚迪「签到成功→打开@550,1686」，此前每轮
     * 判完「签到成功」就走人，奖励一直留在弹窗里）。按优先级取全屏唯一精确匹配。
     */
    public static TextBlock findWrapupClaim(List<TextBlock> boxes) {
        for (String label : WRAPUP_CLAIM_LABELS) {
            TextBlock found = uniqueExact(boxes, label, 0, Integer.MAX_VALUE, 0);
            if (found != null) return found;
        }
        return null;
    }

    /** 收尾/遮挡弹窗的通用关闭按钮（E充站活动弹窗「好的@550,1924」曾把日历盖住 46 秒）。 */
    public static TextBlock findWrapupDismiss(List<TextBlock> boxes) {
        for (String label : WRAPUP_DISMISS_LABELS) {
            TextBlock found = uniqueExact(boxes, label, 0, Integer.MAX_VALUE, 0);
            if (found != null) return found;
        }
        return null;
    }

    /** 定位失败时的诊断输出：真机取证用，逐框列出文案与坐标。 */
    public static String describe(List<TextBlock> boxes) {
        if (boxes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(box.text).append('@').append(box.left).append(',').append(box.top)
                    .append('-').append(box.right).append(',').append(box.bottom);
        }
        return sb.toString();
    }

    private static TextBlock uniqueExact(List<TextBlock> boxes, String label,
                                         int minTop, int maxBottom, int minCenterX) {
        return unique(boxes, label, minTop, maxBottom, minCenterX, false);
    }

    private static TextBlock unique(List<TextBlock> boxes, String label,
                                    int minTop, int maxBottom, int minCenterX, boolean stripIcon) {
        if (boxes == null || label == null) return null;
        TextBlock found = null;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            if (stripIcon) text = stripLeadingNonCjk(text);
            if (!label.equals(text)) continue;
            if (box.top < minTop || box.bottom > maxBottom) continue;
            if (box.centerX() < minCenterX) continue;
            if (found != null) return null;
            found = box;
        }
        return found;
    }

    /** 去掉开头被 OCR 误读成数字/符号的按钮图标，只保留到首个汉字为止的前缀噪声。 */
    private static String stripLeadingNonCjk(String text) {
        int i = 0;
        while (i < text.length() && !isCjk(text.charAt(i))) i++;
        return text.substring(i);
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}