package com.mario.assistant;

final class VisualTargetGuard {
    /** 福利中心入口：同文案且逐边一致（该页面无动画干扰，用紧容差）。 */
    static boolean stable(ReadingTaskLocator.Box first, ReadingTaskLocator.Box second, int width, int height) {
        if (first == null || second == null || first.text == null || !first.text.equals(second.text)) return false;
        return tightPlace(first, second, width, height);
    }

    /**
     * 广告按钮跨帧稳定：文案不参与比较（设备端 OCR 对同一按钮输出抖动，且动画图形
     * 偶尔被合并进文字框）；以披露行锚点两帧位置严格一致证明页面未切换布局，
     * 按钮仅比中心点，容差放宽到允许动画合并带来的漂移。
     */
    static boolean stableDetails(ReadingTaskLocator.Box firstTarget, ReadingTaskLocator.Box secondTarget,
            ReadingTaskLocator.Box firstAnchor, ReadingTaskLocator.Box secondAnchor, int width, int height) {
        if (firstTarget == null || secondTarget == null || firstAnchor == null || secondAnchor == null) return false;
        if (!tightPlace(firstAnchor, secondAnchor, width, height)) return false;
        if (!validPlace(firstTarget, width, height) || !validPlace(secondTarget, width, height)) return false;
        int tolerance = Math.max(16, width / 6);
        return Math.abs(centerX(firstTarget) - centerX(secondTarget)) <= tolerance
                && Math.abs(centerY(firstTarget) - centerY(secondTarget)) <= tolerance;
    }

    private static boolean tightPlace(ReadingTaskLocator.Box a, ReadingTaskLocator.Box b, int width, int height) {
        if (!validPlace(a, width, height) || !validPlace(b, width, height)) return false;
        int tolerance = Math.max(4, width / 100);
        return Math.abs(a.left - b.left) <= tolerance && Math.abs(a.top - b.top) <= tolerance
                && Math.abs(a.right - b.right) <= tolerance && Math.abs(a.bottom - b.bottom) <= tolerance;
    }

    private static boolean validPlace(ReadingTaskLocator.Box b, int width, int height) {
        return b != null && width > 0 && height > 0
                && b.left >= 0 && b.top >= 0 && b.right <= width && b.bottom <= height
                && b.left < b.right && b.top < b.bottom;
    }

    private static int centerX(ReadingTaskLocator.Box b) { return (b.left + b.right) / 2; }
    private static int centerY(ReadingTaskLocator.Box b) { return (b.top + b.bottom) / 2; }
}
