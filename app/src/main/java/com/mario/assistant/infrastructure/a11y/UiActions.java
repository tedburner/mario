package com.mario.assistant.infrastructure.a11y;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;

/** 无障碍动作：节点点击优先，失败时由调用方决定是否退回坐标手势。 */
public final class UiActions {
    private final AccessibilityService service;

    public UiActions(AccessibilityService service) {
        this.service = service;
    }

    public boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 4 && current != null; i++) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    public boolean back() {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    public boolean home() {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
    }
}
