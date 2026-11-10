package com.mario.assistant.infrastructure.a11y;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.mario.assistant.domain.model.UiNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 一次无障碍树快照：不可变节点列表 + 可回查的实时节点，避免流程中途读到半新半旧的树。 */
public final class UiTree {
    public final AccessibilityNodeInfo root;
    public final String packageName;
    public final int width;
    public final int height;
    public final List<UiNode> nodes;
    private final Map<Integer, AccessibilityNodeInfo> live;

    private UiTree(AccessibilityNodeInfo root, String packageName, int width, int height,
                   List<UiNode> nodes, Map<Integer, AccessibilityNodeInfo> live) {
        this.root = root;
        this.packageName = packageName;
        this.width = width;
        this.height = height;
        this.nodes = nodes;
        this.live = live;
    }

    public static UiTree capture(AccessibilityNodeInfo root, int fallbackWidth, int fallbackHeight) {
        List<UiNode> nodes = new ArrayList<>();
        Map<Integer, AccessibilityNodeInfo> live = new HashMap<>();
        if (root == null) {
            return new UiTree(null, "", fallbackWidth, fallbackHeight, nodes, live);
        }
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        int width = screen.width() > 0 ? screen.width() : fallbackWidth;
        int height = screen.height() > 0 ? screen.height() : fallbackHeight;
        flatten(root, nodes, live, 0, 0, Integer.MAX_VALUE);
        String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
        return new UiTree(root, pkg, width, height, nodes, live);
    }

    private static void flatten(AccessibilityNodeInfo node, List<UiNode> out,
                                Map<Integer, AccessibilityNodeInfo> live, int depth,
                                int clipTop, int clipBottom) {
        if (node == null || depth > 40 || out.size() >= 4000) return;
        String text = node.getText() == null ? "" : node.getText().toString();
        if (text.isEmpty() && node.getContentDescription() != null) {
            text = node.getContentDescription().toString();
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int nextTop = clipTop;
        int nextBottom = clipBottom;
        if (node.isScrollable() && bounds.height() > 0) {
            // 只保留最近滚动容器的可见区，滚动到屏外的行不再可点。
            nextTop = Math.max(clipTop, bounds.top);
            nextBottom = Math.min(clipBottom, bounds.bottom);
        }
        int id = out.size();
        out.add(new UiNode(id, text, bounds.left, bounds.top, bounds.right, bounds.bottom,
                node.isClickable(), node.isVisibleToUser(), node.isScrollable(), nextTop, nextBottom));
        live.put(id, node);
        for (int i = 0; i < node.getChildCount(); i++) {
            flatten(node.getChild(i), out, live, depth + 1, nextTop, nextBottom);
        }
    }

    public AccessibilityNodeInfo live(int id) {
        return live.get(id);
    }

    public List<AccessibilityNodeInfo> findExact(String label) {
        return TreeSearch.find(root, label, NODE_ACCESS, 1000);
    }

    public boolean hasText(String expected) {
        for (AccessibilityNodeInfo node : findExact(expected)) {
            if (exactText(node, expected)) return true;
        }
        return false;
    }

    public AccessibilityNodeInfo findClickableText(String text) {
        return findClickableText(root, text);
    }

    /**
     * 按文案「包含」匹配可点击节点，并向上最多 6 层找可点祖先。
     * 用于 OCR 把相邻文案并进按钮行（如「10(0知道了8)8点」）时——此时 OCR 坐标与文案都不可信，
     * 但节点本身仍就是那个按钮。要求命中节点尺寸像按钮，避免误点到整块容器。
     */
    public AccessibilityNodeInfo findClickableContaining(String keyword) {
        if (keyword == null || keyword.isEmpty()) return null;
        return findClickableContaining(root, keyword, 0);
    }

    private AccessibilityNodeInfo findClickableContaining(AccessibilityNodeInfo node, String keyword, int depth) {
        if (node == null || depth > 40) return null;
        String text = node.getText() == null ? "" : node.getText().toString();
        if (text.isEmpty() && node.getContentDescription() != null) {
            text = node.getContentDescription().toString();
        }
        if (text.replace(" ", "").contains(keyword)) {
            AccessibilityNodeInfo current = node;
            for (int level = 0; current != null && level < 6; level++) {
                if (current.isClickable() && current.isVisibleToUser()) {
                    Rect r = new Rect();
                    current.getBoundsInScreen(r);
                    if (!r.isEmpty() && r.height() > 0 && r.height() <= height * 15 / 100) return current;
                    break;
                }
                current = current.getParent();
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findClickableContaining(node.getChild(i), keyword, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo findClickableText(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        if (node.getText() != null && text.contentEquals(node.getText())) {
            AccessibilityNodeInfo current = node;
            for (int depth = 0; current != null && depth < 4; depth++) {
                if (current.isClickable()) return current;
                current = current.getParent();
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findClickableText(node.getChild(i), text);
            if (found != null) return found;
        }
        return null;
    }

    /** 小游戏页会吞掉返回键：标题栏右上角的「关闭」是唯一可用出口。 */
    public AccessibilityNodeInfo findCloseButton() {
        return findCloseButton(root, 0);
    }

    private AccessibilityNodeInfo findCloseButton(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 25) return null;
        CharSequence desc = node.getContentDescription();
        if (desc != null && "关闭".contentEquals(desc.toString().trim()) && node.isClickable()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.top < height / 6 && bounds.left > width / 2 && bounds.bottom > bounds.top) {
                return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findCloseButton(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    public static boolean exactText(AccessibilityNodeInfo node, String expected) {
        return expected.contentEquals(node.getText() == null ? "" : node.getText())
                || expected.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription());
    }

    private static final TreeSearch.Access<AccessibilityNodeInfo> NODE_ACCESS =
            new TreeSearch.Access<AccessibilityNodeInfo>() {
                @Override public boolean matches(AccessibilityNodeInfo node, String label) {
                    return exactText(node, label);
                }
                @Override public List<AccessibilityNodeInfo> children(AccessibilityNodeInfo node) {
                    List<AccessibilityNodeInfo> children = new ArrayList<>();
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) children.add(child);
                    }
                    return children;
                }
            };
}
