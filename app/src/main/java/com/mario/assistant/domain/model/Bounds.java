package com.mario.assistant.domain.model;

/** 屏幕坐标矩形，用于点击目标与审计记录。 */
public final class Bounds {
    public final int left, top, right, bottom;

    public Bounds(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public int centerX() { return (left + right) / 2; }

    public int centerY() { return (top + bottom) / 2; }

    public int width() { return right - left; }

    public int height() { return bottom - top; }

    public boolean isEmpty() { return right <= left || bottom <= top; }

    @Override public String toString() {
        return left + "," + top + "-" + right + "," + bottom;
    }
}
