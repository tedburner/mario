package com.mario.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mario.assistant.domain.safety.ActionGuard;

import org.junit.Test;

public class ActionGuardTest {
    @Test public void taskRowAllowsBrowseAndClaim() {
        assertTrue(ActionGuard.evaluate(ActionGuard.Surface.TASK_ROW, "逛一逛").allowed);
        assertTrue(ActionGuard.evaluate(ActionGuard.Surface.TASK_ROW, "立即领取").allowed);
        assertTrue(ActionGuard.evaluate(ActionGuard.Surface.TASK_ROW, "去完成").allowed);
    }

    @Test public void taskRowRejectsTradeAndInstall() {
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.TASK_ROW, "去购买").allowed);
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.TASK_ROW, "立即下单").allowed);
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.TASK_ROW, "确认支付").allowed);
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.TASK_ROW, "下载APP").allowed);
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.TASK_ROW, "安装应用").allowed);
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.TASK_ROW, "投保领保障").allowed);
    }

    @Test public void adPromptAllowsAnyCopyExceptInstall() {
        assertTrue(ActionGuard.evaluate(ActionGuard.Surface.AD_PROMPT, "立即抢购").allowed);
        assertTrue(ActionGuard.evaluate(ActionGuard.Surface.AD_PROMPT, "了解详情").allowed);
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.AD_PROMPT, "立即下载").allowed);
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.AD_PROMPT, "安装应用").allowed);
    }

    @Test public void signinOnlyAllowsSigninButton() {
        assertTrue(ActionGuard.evaluate(ActionGuard.Surface.SIGNIN, "立即签到").allowed);
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.SIGNIN, "立即预订").allowed);
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.SIGNIN, "领取优惠券").allowed);
    }

    @Test public void energyPageOnlyAllowsCollectAll() {
        assertTrue(ActionGuard.evaluate(ActionGuard.Surface.ENERGY_PAGE, "一键收").allowed);
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.ENERGY_PAGE, "一键浇水").allowed);
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.ENERGY_PAGE, "送道具").allowed);
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.ENERGY_PAGE, "留言").allowed);
    }

    @Test public void dialogOnlyAllowsKnownButtons() {
        assertTrue(ActionGuard.evaluate(ActionGuard.Surface.DIALOG, "知道了").allowed);
        assertTrue(ActionGuard.evaluate(ActionGuard.Surface.DIALOG, "关闭").allowed);
        assertTrue(ActionGuard.evaluate(ActionGuard.Surface.DIALOG, "立即抽奖").allowed);
        assertTrue(ActionGuard.evaluate(ActionGuard.Surface.DIALOG, "收下奖励").allowed);
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.DIALOG, "去购买").allowed);
    }

    @Test public void navigationRejectsTradeAndAcceptsEntry() {
        assertTrue(ActionGuard.evaluate(ActionGuard.Surface.NAVIGATION, "蚂蚁森林").allowed);
        assertTrue(ActionGuard.evaluate(ActionGuard.Surface.NAVIGATION, "福利中心").allowed);
        assertFalse(ActionGuard.evaluate(ActionGuard.Surface.NAVIGATION, "开通会员").allowed);
    }
}
