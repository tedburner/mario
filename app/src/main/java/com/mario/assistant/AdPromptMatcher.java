package com.mario.assistant;

import java.util.List;

/**
 * 激励广告底部按钮的结构化定位：
 * 以固定锚点（平台披露行“进入详情页或第三方应用”，或视频播放态右下角“广告”标识）
 * 为基准，在其上方固定几何带内找唯一按钮。按钮文案不限——抢购、支付、充值等文案都可能
 * 出现，点击后本项目只在落地页停留计时、不做任何点击，不会产生订单；只拒绝会直接安装
 * 应用的按钮。
 */
final class AdPromptMatcher {
    private static final String[] DENIED_LABELS = {"下载", "安装"};

    static ReadingTaskLocator.Box findDetails(List<ReadingTaskLocator.Box> boxes, int width, int height) {
        if (width <= 0 || height <= 0) return null;
        ReadingTaskLocator.Box disclosure = findDisclosure(boxes);
        if (disclosure != null) {
            ReadingTaskLocator.Box action = findActionAbove(boxes, disclosure, width, height);
            if (action != null) return action;
        }
        return findVideoStateAction(boxes, width, height);
    }

    static ReadingTaskLocator.Box findDisclosure(List<ReadingTaskLocator.Box> boxes) {
        ReadingTaskLocator.Box found = null;
        for (ReadingTaskLocator.Box box : boxes) {
            if (box == null || box.text == null) continue;
            if (!isDisclosureLine(box.text.replace(" ", ""))) continue;
            if (found != null) return null;
            found = box;
        }
        return found;
    }

    /** 跨帧稳定性的锚点：优先披露行；视频播放态用“广告”平台标识证明页面未变。 */
    static ReadingTaskLocator.Box findStabilityAnchor(List<ReadingTaskLocator.Box> boxes, int width, int height) {
        if (width <= 0 || height <= 0) return null;
        ReadingTaskLocator.Box disclosure = findDisclosure(boxes);
        if (disclosure != null) return disclosure;
        return findAdMark(boxes, height);
    }

    private static ReadingTaskLocator.Box findAdMark(List<ReadingTaskLocator.Box> boxes, int height) {
        ReadingTaskLocator.Box found = null;
        for (ReadingTaskLocator.Box box : boxes) {
            if (box == null || box.text == null) continue;
            if (!"广告".equals(box.text.replace(" ", ""))) continue;
            if (box.top < height * 88 / 100) continue;
            if (found != null) return null;
            found = box;
        }
        return found;
    }

    private static ReadingTaskLocator.Box findActionAbove(List<ReadingTaskLocator.Box> boxes,
            ReadingTaskLocator.Box disclosure, int width, int height) {
        ReadingTaskLocator.Box result = null;
        for (ReadingTaskLocator.Box box : boxes) {
            if (box == null || box.text == null || box == disclosure) continue;
            if (!inActionBand(box, disclosure, width, height)) continue;
            if (isDeniedLabel(box.text.replace(" ", ""))) return null;
            if (result != null) return null;
            result = box;
        }
        return result;
    }

    private static ReadingTaskLocator.Box findVideoStateAction(List<ReadingTaskLocator.Box> boxes,
            int width, int height) {
        ReadingTaskLocator.Box adMark = findAdMark(boxes, height);
        if (adMark == null) return null;
        ReadingTaskLocator.Box result = null;
        for (ReadingTaskLocator.Box box : boxes) {
            if (box == null || box.text == null || box == adMark) continue;
            if (!inActionBand(box, adMark, width, height)) continue;
            if (isDeniedLabel(box.text.replace(" ", ""))) return null;
            if (result != null) return null;
            result = box;
        }
        return result;
    }

    private static boolean isDisclosureLine(String text) {
        // 平台样板“进入详情页或第三方应用”共 11 字；实机 ML Kit 对第 4 字不稳定
        // （已观察 详惰/详倩/洋倩），仅放行该单字差异，其余字符必须逐一一致。
        return text.length() == 11 && text.startsWith("进入详") && text.endsWith("页或第三方应用");
    }

    /** 真机实测 (1200x2670)：按钮文字行距披露行约 77px、水平居中、行高约 58px。 */
    private static boolean inActionBand(ReadingTaskLocator.Box box, ReadingTaskLocator.Box disclosure,
            int width, int height) {
        return box.left >= 0 && box.right <= width && box.left < box.right && box.top < box.bottom
                && box.top >= height * 7 / 10
                && box.bottom >= disclosure.top - 220 && box.bottom <= disclosure.top - 10
                && box.bottom - box.top <= 200
                && Math.abs((box.left + box.right) / 2 - width / 2) <= width / 8;
    }

    private static boolean isDeniedLabel(String text) {
        for (String denied : DENIED_LABELS) if (text.contains(denied)) return true;
        return false;
    }

    /** One-line diagnosis of why a frame did not match; mirrors findDetails' own predicates. */
    static String describe(List<ReadingTaskLocator.Box> boxes, int width, int height) {
        boolean prompt = false;
        ReadingTaskLocator.Box anchor = width > 0 && height > 0 ? findStabilityAnchor(boxes, width, height) : null;
        boolean videoState = anchor != null && "广告".equals(anchor.text);
        StringBuilder lower = new StringBuilder();
        for (ReadingTaskLocator.Box box : boxes) {
            if (box == null || box.text == null) continue;
            String text = box.text.replace(" ", "");
            if (text.contains("点击") && text.contains("15") && text.contains("秒") && text.contains("奖励")) prompt = true;
            if (width > 0 && height > 0 && box.top >= height * 7 / 10 && box.top < box.bottom) {
                if (lower.length() > 0) lower.append(' ');
                lower.append(text).append('@').append(box.top).append('-').append(box.bottom);
            }
        }
        return "prompt=" + prompt + " anchor=" + (anchor != null) + " videoState=" + videoState
                + " lower=[" + lower + "]";
    }

    static boolean hasReward(List<ReadingTaskLocator.Box> boxes) {
        for (ReadingTaskLocator.Box box : boxes) {
            if (box != null && box.text != null && box.text.replace(" ", "").contains("恭喜已获得奖励")) return true;
        }
        return false;
    }
}
