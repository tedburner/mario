package com.mario.assistant;

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
        assertFalse(flow.shouldReturn("com.example.other", 20000));
        assertTrue(flow.shouldReturn("com.jingdong.app.mall", 16000));
        flow.returnRequested();
        assertTrue(flow.confirmReward());
    }
    @Test public void supportsDifferentExternalAppButRequiresSameAppAtReturn() {
        AdFlow flow = new AdFlow();
        flow.detailsRequested();
        assertTrue(flow.observeDetail("com.example.shop", 1000));
        assertFalse(flow.shouldReturn("com.example.other", 20000));
        assertTrue(flow.shouldReturn("com.example.shop", 20000));
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
