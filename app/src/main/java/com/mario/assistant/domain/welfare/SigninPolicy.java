package com.mario.assistant.domain.welfare;

import com.mario.assistant.domain.model.TextBlock;

import java.util.List;

/**
 * 携程签到页：只认「立即签到」主按钮。页面上同时有「签到·任务」页签和
 * 「去移动/升级赚积分」等推广，必须精确限定文案，避免点到第三方推广。
 */
public final class SigninPolicy {
    public static TextBlock findSigninButton(List<TextBlock> boxes) {
        TextBlock found = null;
        for (TextBlock box : boxes) {
            if (box == null || box.text == null) continue;
            if (!box.text.replace(" ", "").contains("立即签到")) continue;
            if (found != null) return null;
            found = box;
        }
        return found;
    }
}
