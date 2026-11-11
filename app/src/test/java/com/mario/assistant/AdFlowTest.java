package com.mario.assistant;

import com.mario.assistant.domain.welfare.AdFlow;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdFlowTest {
    @Test public void cannotReturnOrConfirmBeforeDetailAndWait() {
        AdFlow flow = new AdFlow();
        assertFalse(flow.shouldReturn("com.jingdong.app.mall", 20000));
        assertFalse(flow.confirmReward());
        flow.detailsRequested();
        assertFalse(flow.observeDetail("com.android.settings", 1000));
        assertTrue(flow.observeDetail("com.jingdong.app.mall", 1000));
        assertFalse(flow.shouldReturn("com.jingdong.app.mall", 15999));
        // 系统界面不是广告落地页，不能作为返回对象。
        assertFalse(flow.shouldReturn("com.android.systemui", 20000));
        assertTrue(flow.shouldReturn("com.jingdong.app.mall", 16000));
        flow.returnRequested();
        assertTrue(flow.confirmReward());
    }
    @Test public void followsNestedAdChainAndReturnsFromCurrentApp() {
        // 真机问题：起点广告 → 微信 → 京东，换包后旧逻辑判定“已离开详情页”直接停止。
        AdFlow flow = new AdFlow();
        flow.detailsRequested();
        assertTrue(flow.observeDetail("com.example.shop", 1000));
        assertTrue(flow.observeChainHop("com.jingdong.app.mall"));
        // 同一应用重复上报不重复记录。
        assertFalse(flow.observeChainHop("com.jingdong.app.mall"));
        assertTrue(flow.isDestination("com.example.shop"));
        assertTrue(flow.isDestination("com.jingdong.app.mall"));
        // 15 秒从第一次进入外部应用开始计时，链路跳转不重置。
        assertFalse(flow.shouldReturn("com.jingdong.app.mall", 15999));
        assertTrue(flow.shouldReturn("com.jingdong.app.mall", 16000));
        // 再跳第三跳也能从当前应用直接切回起点。
        assertTrue(flow.observeChainHop("com.taobao.idlefish"));
        assertTrue(flow.shouldReturn("com.taobao.idlefish", 20000));
        assertEquals("com.example.shop → com.jingdong.app.mall → com.taobao.idlefish",
                flow.chainDescription());
        // 系统商店/安装页绝不能作为广告落地页继续等待。
        assertTrue(AdFlow.isBlockedLanding("com.xiaomi.market"));
        assertTrue(AdFlow.isBlockedLanding("com.android.packageinstaller"));
        assertFalse(AdFlow.isBlockedLanding("com.jingdong.app.mall"));
    }
    @Test public void cancellationStopsReturnAndConfirmation() {
        AdFlow flow = new AdFlow();
        flow.detailsRequested(); flow.observeDetail("com.jingdong.app.mall", 1000); flow.cancel();
        assertFalse(flow.shouldReturn("com.jingdong.app.mall", 20000));
        assertFalse(flow.confirmReward());
    }
    @Test public void supportsInAppDetailPageWithBackReturn() {
        AdFlow flow = new AdFlow();
        flow.detailsRequested();
        assertTrue(flow.confirmInAppDetail());
        assertFalse(flow.isInApp());
        flow.observeDetailInApp(1000);
        assertTrue(flow.isInApp());
        assertTrue(flow.isDestination("com.qidian.QDReader"));
        assertFalse(flow.shouldReturn("com.qidian.QDReader", 15999));
        assertTrue(flow.shouldReturn("com.qidian.QDReader", 16000));
        flow.returnRequested();
        assertTrue(flow.confirmReward());
    }
    @Test public void resetAllowsNextCarouselAd() {
        AdFlow flow = new AdFlow();
        flow.detailsRequested();
        flow.observeDetail("com.taobao.idlefish", 1000);
        assertTrue(flow.shouldReturn("com.taobao.idlefish", 16000));
        flow.returnRequested();
        assertTrue(flow.confirmReward());
        flow.reset();
        assertFalse(flow.confirmReward());
        assertFalse(flow.isInApp());
        flow.detailsRequested();
        assertTrue(flow.observeDetail("com.jingdong.app.mall", 2000));
        assertFalse(flow.shouldReturn("com.jingdong.app.mall", 16999));
        assertTrue(flow.shouldReturn("com.jingdong.app.mall", 17000));
    }
    @Test public void resetClearsInAppDetail() {
        AdFlow flow = new AdFlow();
        flow.detailsRequested();
        flow.observeDetailInApp(1000);
        assertTrue(flow.isInApp());
        flow.reset();
        assertFalse(flow.isInApp());
        flow.detailsRequested();
        flow.observeDetailInApp(2000);
        assertTrue(flow.isInApp());
        assertTrue(flow.shouldReturn("com.qidian.QDReader", 17000));
    }
    @Test public void inAppModeIgnoredAfterExternalDetailConfirmed() {
        AdFlow flow = new AdFlow();
        flow.detailsRequested();
        assertTrue(flow.observeDetail("com.jingdong.app.mall", 1000));
        flow.observeDetailInApp(2000);
        assertFalse(flow.isInApp());
        assertTrue(flow.isDestination("com.jingdong.app.mall"));
        assertTrue(flow.shouldReturn("com.jingdong.app.mall", 16000));
    }
}
