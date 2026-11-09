package com.mario.assistant.infrastructure.a11y;

import android.accessibilityservice.AccessibilityService;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;

/** 每次扫描拍一张新的无障碍树，流程只读快照。 */
public final class UiTreeSource {
    private final AccessibilityService service;

    public UiTreeSource(AccessibilityService service) {
        this.service = service;
    }

    public UiTree capture() {
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return UiTree.capture(root, metrics.widthPixels, metrics.heightPixels);
    }

    /** 只读当前活动窗口包名，不遍历节点（跨应用阶段判断前台时用）。 */
    public String activePackage() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return "";
        return root.getPackageName().toString();
    }
}
