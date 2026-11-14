package com.mario.assistant.domain.welfare;

import com.mario.assistant.domain.model.TextBlock;
import java.util.List;

public final class WelfareTaskLocator {
    /** 福利中心任务行，按用户指定优先级排列（限时任务在变体列表里，最优先）。 */
    public static final String[] ROW_TITLES = {"激励任务", "完成3个广告任务得奖励", "去携程签到领积分"};
    /** 标题变体：去掉装饰字符后比对（真机截图：点数页同一里程碑行标题为“【限时】广告·加点！”）。 */
    public static final String[] NORMALIZED_TITLES = {"限时广告加点"};

    /** 福利中心页的稳定文案锚点，覆盖顶部、任务区、限时福利和底部兑换区（页面滚到任何位置都能认出）。 */
    public static final String[] WELFARE_MARKERS = {"福利中心", "完成任务得奖励", "做任务领惊喜福利", "本周收益", "积分商城",
            "限时福利", "携程签到", "添加小组件", "阅读得积分", "积分全额兑", "今日必抢", "补签卡", "仅正式会员可兑换", "更多积分好礼"};

    public static boolean isWelfareCenter(List<TextBlock> boxes) {
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            for (String marker : WELFARE_MARKERS) if (text.contains(marker)) return true;
        }
        return false;
    }

    public static final class Row {
        public final String title;
        public final TextBlock button;
        public final boolean goRead;
        public final boolean signin;
        Row(String title, TextBlock button, boolean goRead, boolean signin) {
            this.title = title; this.button = button; this.goRead = goRead; this.signin = signin;
        }
    }
    public static Row find(List<TextBlock> boxes, int width) {
        if (width <= 0) return null;
        for (String normalized : NORMALIZED_TITLES) {
            TextBlock titleBox = soleNormalizedTitle(boxes, normalized);
            if (titleBox == null) continue;
            // 真机观察：该行的首个任务可直接“去完成”；后续任务需先点“阅读1分钟/去阅读”解锁。
            TextBlock button = soleButton(boxes, titleBox, width, "去完成", "阅读1分钟", "去阅读");
            if (button == null) continue;
            return new Row(normalized, button, !"去完成".equals(button.text), false);
        }
        for (String title : ROW_TITLES) {
            TextBlock titleBox = soleTitle(boxes, title);
            if (titleBox == null && "去携程签到领积分".equals(title)) titleBox = soleCtripSigninTitle(boxes);
            if (titleBox == null && "完成3个广告任务得奖励".equals(title)) titleBox = soleThreeAdsTitle(boxes);
            if (titleBox == null) continue;
            if ("去携程签到领积分".equals(title)) {
                // 真机 ML Kit 把该行按钮读成“领奖励”（其余行是“去完成”）；文案不可靠，
                // 按“标题右侧、同一行内的唯一短标签”做几何定位。
                TextBlock button = soleSigninButton(boxes, titleBox, width);
                if (button == null || button.text.replace(" ", "").startsWith("已")) continue;
                return new Row(title, button, false, true);
            }
            TextBlock button = soleButton(boxes, titleBox, width, new String[]{"去完成"});
            if (button == null) continue;
            return new Row(title, button, false, false);
        }
        return null;
    }
    /** 携程行已领取：标题唯一，右侧同一行带内有“已…”标签（真机为“已领取”或误读成“已领”）。 */
    public static boolean claimedSignin(List<TextBlock> boxes, int width) {
        TextBlock title = soleCtripSigninTitle(boxes);
        if (title == null) return false;
        if (title.left < 0 || title.right >= width || title.top >= title.bottom) return false;
        int titleCenter = (title.top + title.bottom) / 2;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            if (!text.startsWith("已") || text.length() > 6 || text.contains("携程")) continue;
            if (box.left <= title.right || box.right > width || box.left >= box.right || box.top >= box.bottom) continue;
            int overlap = Math.min(title.bottom, box.bottom) - Math.max(title.top, box.top);
            int centerDiff = Math.abs((box.top + box.bottom) / 2 - titleCenter);
            if (overlap <= 0 && centerDiff > 60) continue;
            return true;
        }
        return false;
    }
    /**
     * 携程行签到完成后的「领奖励」按钮。
     *
     * 真机（2026-11-10 14:01）：点完携程「立即签到」回到福利中心，该行按钮从「去完成」
     * 变成「领奖励」，必须再点一次 +1 点币才真正到账；此前流程直接判定完成收尾。
     * 仍然按「标题右侧、同一行带内的唯一短标签」定位，只接受领取类文案。
     */
    public static TextBlock signinClaimButton(List<TextBlock> boxes, int width) {
        TextBlock title = soleCtripSigninTitle(boxes);
        if (title == null) return null;
        TextBlock button = soleSigninButton(boxes, title, width);
        if (button == null || button.text == null) return null;
        String text = button.text.replace(" ", "");
        if (text.length() > 6 || !text.startsWith("领")) return null;
        return button;
    }
    /** 签到行的按钮：标题右侧、纵向同一条带内的唯一短标签（不接受含“携程”的文案）。 */
    private static TextBlock soleSigninButton(List<TextBlock> boxes, TextBlock title, int width) {
        if (title.left < 0 || title.right >= width || title.top >= title.bottom) return null;
        int titleCenter = (title.top + title.bottom) / 2;
        TextBlock result = null;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            if (text.isEmpty() || text.length() > 6 || text.contains("携程")) continue;
            if (box.left <= title.right || box.right > width || box.left >= box.right || box.top >= box.bottom) continue;
            int overlap = Math.min(title.bottom, box.bottom) - Math.max(title.top, box.top);
            int centerDiff = Math.abs((box.top + box.bottom) / 2 - titleCenter);
            if (overlap <= 0 && centerDiff > 60) continue;
            if (result != null) return null;
            result = box;
        }
        return result;
    }
    private static TextBlock soleTitle(List<TextBlock> boxes, String text) {
        TextBlock found = null;
        for (TextBlock box : boxes) {
            if (box == null || !text.equals(box.text)) continue;
            if (found != null) return null;
            found = box;
        }
        return found;
    }
    /** 携程行标题允许 OCR 轻微差异（真机为“去携程签到领积分”）。 */
    private static TextBlock soleCtripSigninTitle(List<TextBlock> boxes) {
        TextBlock found = null;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            if (!text.contains("携程") || !text.contains("签到")) continue;
            if (found != null) return null;
            found = box;
        }
        return found;
    }

    /**
     * 「完成3个广告任务得奖励」标题容忍 OCR 变形：真机 ML Kit 会把“广告”读成
     * “广广告”（34 帧）或插入“厂”字，精确 equals 匹配失败导致整行被跳过、
     * 直接落到下一优先级的携程行（真机 2026-11-14 09:02 做完第 1 个广告就去做携程了）。
     * 用“3个广 + 任务得奖励”双片段定位，不会误中「完成1个广告任务得奖励」等相邻行。
     */
    private static TextBlock soleThreeAdsTitle(List<TextBlock> boxes) {
        TextBlock found = null;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            if (!text.contains("3个广") || !text.contains("任务得奖励")) continue;
            if (found != null) return null;
            found = box;
        }
        return found;
    }
    private static TextBlock soleNormalizedTitle(List<TextBlock> boxes, String normalized) {
        TextBlock found = null;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            if (!normalized.equals(stripDecoration(box.text))) continue;
            if (found != null) return null;
            found = box;
        }
        return found;
    }
    public static String stripDecoration(String text) {
        return text.replaceAll("[【】\\[\\]·・!！!。.，,]", "");
    }
    private static final String[] EXCLUDED_SHELF_TEXTS =
            {"书架", "精选", "发现", "福利中心", "我的账户", "活动中心", "阅读1分钟", "去阅读", "去完成",
                    "已领取", "规则", "每日导读", "浏览记录", "最近阅读", "筛选", "领福利", "管理"};

    /** 书架页第一本书：“书架”页头以下的区域才是书籍列表（页头上方是每日导读等推荐卡）；页头/导航文字排除。 */
    public static TextBlock firstBook(List<TextBlock> boxes, int height) {
        if (height <= 0) return null;
        int listTop = 0;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            // 实测页头文字会被 OCR 合并（“书架浏览记录”），包含“书架”且在上部 30% 即认作页头。
            if (!text.contains("书架")) continue;
            if (box.top >= height * 30 / 100) continue; // 屏幕底部标签栏的“书架”不算页头
            listTop = Math.max(listTop, box.bottom);
        }
        if (listTop == 0) return null; // 无页头锚点时宁可不点（扫描日志可诊断，书架标签兜底可重定向）
        int minTop = Math.max(height * 12 / 100, listTop);
        TextBlock top = null;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            if (text.length() < 2) continue;
            boolean excluded = false;
            for (String excludedText : EXCLUDED_SHELF_TEXTS) {
                if (text.contains(excludedText)) { excluded = true; break; }
            }
            if (excluded) continue;
            if (box.top < minTop || box.bottom > height * 85 / 100) continue;
            if (top == null || box.top < top.top) top = box;
        }
        return top;
    }
    private static TextBlock soleButton(List<TextBlock> boxes, TextBlock title, int width, String... labels) {
        if (title.left < 0 || title.right >= width || title.top >= title.bottom) return null;
        TextBlock result = null;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            boolean matched = false;
            for (String label : labels) if (label.equals(box.text)) { matched = true; break; }
            if (!matched || box.left <= title.right
                    || box.right > width || box.left >= box.right || box.top >= box.bottom) continue;
            int overlap = Math.min(title.bottom, box.bottom) - Math.max(title.top, box.top);
            if (overlap <= 0) continue;
            if (result != null) return null;
            result = box;
        }
        return result;
    }
    public static String describe(List<TextBlock> boxes, int height) {
        StringBuilder titles = new StringBuilder();
        StringBuilder buttons = new StringBuilder();
        StringBuilder bottom = new StringBuilder();
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            for (String title : ROW_TITLES) if (title.equals(box.text)) titles.append(title).append(' ');
            String stripped = stripDecoration(box.text);
            for (String title : NORMALIZED_TITLES) if (title.equals(stripped)) titles.append(box.text).append(' ');
            if ("去完成".equals(box.text) || "已领取".equals(box.text)) {
                buttons.append(box.text).append('@').append(box.top).append(' ');
            }
            if (height > 0 && box.top >= height * 88 / 100 && box.top < box.bottom) {
                if (bottom.length() > 0) bottom.append(' ');
                bottom.append(box.text).append('@').append(box.left).append(',').append(box.top)
                        .append('-').append(box.right).append(',').append(box.bottom);
            }
        }
        return "titles=[" + titles.toString().trim() + "] buttons=[" + buttons.toString().trim()
                + "] bottom=[" + bottom.toString().trim() + "]";
    }
}
