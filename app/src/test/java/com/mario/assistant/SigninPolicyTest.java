package com.mario.assistant;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class SigninPolicyTest {
    private static ReadingTaskLocator.Box box(String text) {
        return new ReadingTaskLocator.Box(text, 200, 1100, 1000, 1160);
    }
    @Test public void findsSigninButtonOnly() {
        ReadingTaskLocator.Box sign = box("立即签到");
        assertSame(sign, SigninPolicy.findSigninButton(Arrays.asList(
                box("连签7天最高领192积分"), sign)));
        ReadingTaskLocator.Box spaced = box("立即 签到");
        assertSame(spaced, SigninPolicy.findSigninButton(Collections.singletonList(spaced)));
    }
    @Test public void ignoresTabsAndPromotions() {
        // 真机签到页同时有“签到·任务”页签和“去移动/升级赚积分/去七猫”等推广。
        assertNull(SigninPolicy.findSigninButton(Arrays.asList(
                box("签到·任务"), box("去移动"), box("升级赚积分"), box("去七猫免费看小说短剧"))));
        assertNull(SigninPolicy.findSigninButton(Collections.emptyList()));
        // 同名按钮出现两次时不点，避免点到推广层里的同名按钮。
        assertNull(SigninPolicy.findSigninButton(Arrays.asList(box("立即签到"), box("立即签到"))));
    }
}
