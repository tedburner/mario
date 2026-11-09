package com.mario.assistant.domain.model;

/** OCR 识别出的一行文本；坐标基于截图原始像素。 */
public final class TextBlock {
    public final String text;
    public final int left, top, right, bottom;

    public TextBlock(String text, int left, int top, int right, int bottom) {
        this.text = text;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public int centerX() { return (left + right) / 2; }

    public int centerY() { return (top + bottom) / 2; }

    public int height() { return bottom - top; }

    public boolean hasBounds() { return right > left && bottom > top; }
}
