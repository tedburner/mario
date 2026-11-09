package com.mario.assistant;

import java.util.List;

/**
 * 携程签到页：只认「立即签到」主按钮。页面上同时有「签到·任务」页签和
 * 「去移动/升级赚积分」等推广，必须精确限定文案，避免点到第三方推广。
 */
final class SigninPolicy {
    static ReadingTaskLocator.Box findSigninButton(List<ReadingTaskLocator.Box> boxes) {
        ReadingTaskLocator.Box found = null;
        for (ReadingTaskLocator.Box box : boxes) {
            if (box == null || box.text == null) continue;
            if (!box.text.replace(" ", "").contains("立即签到")) continue;
            if (found != null) return null;
            found = box;
        }
        return found;
    }
}
