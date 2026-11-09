package com.mario.assistant.domain.model;

/** 拍平后的界面节点；滚动容器会把屏外行裁掉，中心落在可见区内才算可点。 */
public final class UiNode {
    public final int id;
    public final String text;
    public final int left, top, right, bottom;
    public final int clipTop, clipBottom;
    public final boolean clickable;
    public final boolean visible;
    public final boolean scrollable;

    public UiNode(int id, String text, int left, int top, int right, int bottom,
                  boolean clickable, boolean visible) {
        this(id, text, left, top, right, bottom, clickable, visible, 0, Integer.MAX_VALUE);
    }

    public UiNode(int id, String text, int left, int top, int right, int bottom,
                  boolean clickable, boolean visible, int clipTop, int clipBottom) {
        this(id, text, left, top, right, bottom, clickable, visible, false, clipTop, clipBottom);
    }

    public UiNode(int id, String text, int left, int top, int right, int bottom,
                  boolean clickable, boolean visible, boolean scrollable,
                  int clipTop, int clipBottom) {
        this.id = id;
        this.text = text;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.clickable = clickable;
        this.visible = visible;
        this.scrollable = scrollable;
        this.clipTop = clipTop;
        this.clipBottom = clipBottom;
    }

    public boolean hasBounds() { return right > left && bottom > top; }

    public int centerY() { return (top + bottom) / 2; }

    public boolean inViewport() {
        int center = centerY();
        return center >= clipTop && center <= clipBottom;
    }

    public boolean exactText(String expected) {
        return expected.contentEquals(text == null ? "" : text);
    }
}
