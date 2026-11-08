package com.mario.assistant;

import java.util.List;

final class ReadingTaskLocator {
    /** 福利中心里已确认使用“去完成”按钮打开广告任务的行，按优先级排列。 */
    static final String[] ROW_TITLES = {"激励任务", "做任务领惊喜福利", "完成3个广告任务得奖励"};

    static final class Box {
        final String text; final int left, top, right, bottom;
        Box(String text, int left, int top, int right, int bottom) {
            this.text=text; this.left=left; this.top=top; this.right=right; this.bottom=bottom;
        }
    }
    static final class Row {
        final String title; final Box button;
        Row(String title, Box button) { this.title = title; this.button = button; }
    }
    static Row find(List<Box> boxes, int width) {
        if (width <= 0) return null;
        for (String title : ROW_TITLES) {
            Box titleBox = soleTitle(boxes, title);
            if (titleBox == null) continue;
            Box button = soleButton(boxes, titleBox, width);
            if (button == null) continue;
            return new Row(title, button);
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
    private static Box soleButton(List<Box> boxes, Box title, int width) {
        if (title.left < 0 || title.right >= width || title.top >= title.bottom) return null;
        Box result = null;
        for (Box box : boxes) {
            if (box == null || !"去完成".equals(box.text) || box.left <= title.right
                    || box.right > width || box.left >= box.right || box.top >= box.bottom) continue;
            int overlap = Math.min(title.bottom, box.bottom) - Math.max(title.top, box.top);
            if (overlap <= 0) continue;
            if (result != null) return null;
            result = box;
        }
        return result;
    }
    static String describe(List<Box> boxes) {
        StringBuilder titles = new StringBuilder();
        StringBuilder buttons = new StringBuilder();
        for (Box box : boxes) {
            if (box == null || box.text == null) continue;
            for (String title : ROW_TITLES) if (title.equals(box.text)) titles.append(title).append(' ');
            if ("去完成".equals(box.text) || "已领取".equals(box.text)) {
                buttons.append(box.text).append('@').append(box.top).append(' ');
            }
        }
        return "titles=[" + titles.toString().trim() + "] buttons=[" + buttons.toString().trim() + "]";
    }
}
