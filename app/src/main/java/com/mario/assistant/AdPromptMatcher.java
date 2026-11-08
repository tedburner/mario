package com.mario.assistant;

import java.util.List;

/**
 * 激励广告底部按钮的结构化定位，两种广告页状态：
 * 1. 底部操作区态：以平台固定披露行“进入详情页或第三方应用”为锚点，按几何在其正上方
 *    找唯一按钮，按钮文案不限（各广告主不同）；
 * 2. 视频播放态（真机观察：披露行消失，仅剩右下角“广告”平台标识）：兜底只接受已验证
 *    安全文案家族（查看详情/去逛逛）的唯一按钮，且必须位于“广告”标识上方。
 * 明确有真实副作用的动作（下载/安装/充值/支付）仍然拒绝。
 */
final class AdPromptMatcher {
    private static final String[] DENIED_LABELS = {"下载", "安装", "充值", "支付"};
    // 已在真机观察过的浏览类按钮文案（含已记录的 OCR 误读变体）；黑名单优先于本名单。
    private static final String[] VERIFIED_SAFE_LABELS = {"查看详情", "去逛逛", "去微信看看", "去徽信看看"};

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
            String text = box.text.replace(" ", "");
            if (!isSafeVideoLabel(text)) continue;
            if (box.left < 0 || box.right > width || box.top < height * 3 / 4 || box.bottom > height) continue;
            if (box.top >= adMark.top) continue;
            if (isDeniedLabel(text)) return null;
            if (result != null) return null;
            result = box;
        }
        return result;
    }

    /**
     * 视频态无披露行、证据较弱，文案必须属于已验证安全家族，但允许两类已观察的
     * OCR 抖动：同长度单字误读（情→惰、微→徽），动画图形被合并进末尾多出一个字
     * （查看详情惰）。更长的后缀（如“并支付”）不匹配；黑名单优先于本判定。
     */
    private static boolean isSafeVideoLabel(String text) {
        for (String label : VERIFIED_SAFE_LABELS) {
            if (text.equals(label)) return true;
            if (text.length() == label.length() && oneCharDiffers(text, label)) return true;
            if (text.length() == label.length() + 1 && text.startsWith(label)) return true;
        }
        return false;
    }

    private static boolean oneCharDiffers(String a, String b) {
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i) && ++diff > 1) return false;
        }
        return diff == 1;
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
