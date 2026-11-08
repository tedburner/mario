package com.mario.assistant;

import java.util.List;

final class ReadingTaskLocator {
    /** 福利中心任务行，按用户指定优先级排列（限时任务在变体列表里，最优先）。 */
    static final String[] ROW_TITLES = {"激励任务", "完成3个广告任务得奖励", "去携程签到领积分"};
    /** 标题变体：去掉装饰字符后比对（真机截图：点数页同一里程碑行标题为“【限时】广告·加点！”）。 */
    static final String[] NORMALIZED_TITLES = {"限时广告加点"};

    static final class Box {
        final String text; final int left, top, right, bottom;
        Box(String text, int left, int top, int right, int bottom) {
            this.text=text; this.left=left; this.top=top; this.right=right; this.bottom=bottom;
        }
    }
    static final class Row {
        final String title; final Box button; final boolean goRead; final boolean signin;
        Row(String title, Box button, boolean goRead, boolean signin) {
            this.title = title; this.button = button; this.goRead = goRead; this.signin = signin;
        }
    }
    static Row find(List<Box> boxes, int width) {
        if (width <= 0) return null;
        for (String normalized : NORMALIZED_TITLES) {
            Box titleBox = soleNormalizedTitle(boxes, normalized);
            if (titleBox == null) continue;
            // 真机观察：该行的首个任务可直接“去完成”；后续任务需先点“阅读1分钟/去阅读”解锁。
            Box button = soleButton(boxes, titleBox, width, "去完成", "阅读1分钟", "去阅读");
            if (button == null) continue;
            return new Row(normalized, button, !"去完成".equals(button.text), false);
        }
        for (String title : ROW_TITLES) {
            Box titleBox = soleTitle(boxes, title);
            if (titleBox == null) continue;
            // 携程签到行为签到类任务，按钮文案待真机确认，先接受去完成/去签到。
            Box button = soleButton(boxes, titleBox, width,
                    "去携程签到领积分".equals(title) ? new String[]{"去完成", "去签到"} : new String[]{"去完成"});
            if (button == null) continue;
            return new Row(title, button, false, "去携程签到领积分".equals(title));
        }
        return null;
    }
    private static Box soleTitle(List<Box> boxes, String text) {
        Box found = null;
        for (Box box : boxes) {
            if (box == null || !text.equals(box.text)) continue;
            if (found != null) return null;
            found = box;
        }
        return found;
    }
    private static Box soleNormalizedTitle(List<Box> boxes, String normalized) {
        Box found = null;
        for (Box box : boxes) {
            if (box == null || box.text == null) continue;
            if (!normalized.equals(stripDecoration(box.text))) continue;
            if (found != null) return null;
            found = box;
        }
        return found;
    }
    static String stripDecoration(String text) {
        return text.replaceAll("[【】\\[\\]·・!！!。.，,]", "");
    }
    private static final String[] EXCLUDED_SHELF_TEXTS =
            {"书架", "精选", "发现", "福利中心", "我的账户", "活动中心", "阅读1分钟", "去阅读", "去完成",
                    "已领取", "规则", "每日导读", "浏览记录", "最近阅读", "筛选", "领福利", "管理"};

    /** 书架页第一本书：“书架”页头以下的区域才是书籍列表（页头上方是每日导读等推荐卡）；页头/导航文字排除。 */
    static Box firstBook(List<Box> boxes, int height) {
        if (height <= 0) return null;
        int listTop = 0;
        for (Box box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            // 实测页头文字会被 OCR 合并（“书架浏览记录”），包含“书架”且在上部 30% 即认作页头。
            if (!text.contains("书架")) continue;
            if (box.top >= height * 30 / 100) continue; // 屏幕底部标签栏的“书架”不算页头
            listTop = Math.max(listTop, box.bottom);
        }
        if (listTop == 0) return null; // 无页头锚点时宁可不点（扫描日志可诊断，书架标签兜底可重定向）
        int minTop = Math.max(height * 12 / 100, listTop);
        Box top = null;
        for (Box box : boxes) {
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
    private static Box soleButton(List<Box> boxes, Box title, int width, String... labels) {
        if (title.left < 0 || title.right >= width || title.top >= title.bottom) return null;
        Box result = null;
        for (Box box : boxes) {
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
    static String describe(List<Box> boxes, int height) {
        StringBuilder titles = new StringBuilder();
        StringBuilder buttons = new StringBuilder();
        StringBuilder bottom = new StringBuilder();
        for (Box box : boxes) {
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
