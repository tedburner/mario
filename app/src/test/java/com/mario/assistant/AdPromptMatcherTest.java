package com.mario.assistant;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdPromptMatcherTest {
    // 真机截图 (1200x2670) 实测几何：京东/贝亲激励广告底部操作区。
    private static final int W = 1200, H = 2670;
    private static final ReadingTaskLocator.Box PROMPT = new ReadingTaskLocator.Box("点击后，看15秒可获得奖励", 133, 1723, 977, 1824);
    private static final ReadingTaskLocator.Box DISCLOSURE = new ReadingTaskLocator.Box("进入详情页或第三方应用", 361, 2265, 838, 2316);
    private static final ReadingTaskLocator.Box ACTION = new ReadingTaskLocator.Box("查看详情", 489, 2130, 702, 2188);

    @Test public void matchesObservedDetailButtonGeometry() {
        assertSame(ACTION, AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, ACTION), W, H));
    }
    @Test public void matchesBrowseVariantWithoutLabelKnowledge() {
        ReadingTaskLocator.Box browse = new ReadingTaskLocator.Box("去逛逛", 489, 2130, 702, 2188);
        assertSame(browse, AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, browse), W, H));
    }
    @Test public void matchesPurchaseStyleCtaFromLiveAd() {
        // 用户实机确认：底部按钮文案为“立即抢购”，披露行仍为平台样板，点击仅进入落地页。
        ReadingTaskLocator.Box buy = new ReadingTaskLocator.Box("立即抢购", 489, 2130, 702, 2188);
        assertSame(buy, AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, buy), W, H));
    }
    @Test public void acceptsObservedOcrTyposInDisclosure() {
        // 实机 ML Kit 已观察两种单字误读：详惰（局部裁剪）、详倩（京东广告实时帧）。
        for (String variant : new String[]{"进入详情页或第三方应用", "进入详惰页或第三方应用", "进入详倩页或第三方应用"}) {
            ReadingTaskLocator.Box typo = new ReadingTaskLocator.Box(variant, 361, 2265, 838, 2316);
            assertSame(variant, ACTION, AdPromptMatcher.findDetails(Arrays.asList(PROMPT, typo, ACTION), W, H));
        }
    }
    @Test public void refusesDisclosureLookalikesWithChangedStructure() {
        for (String variant : new String[]{"进入详倩页或第三方应用啦", "浏览详倩页或第三方应用", "进入详倩页第第三方应用", "进入详情页或第三方应用 App"}) {
            ReadingTaskLocator.Box lookalike = new ReadingTaskLocator.Box(variant, 361, 2265, 838, 2316);
            assertNull(variant, AdPromptMatcher.findDetails(Arrays.asList(PROMPT, lookalike, ACTION), W, H));
        }
    }
    @Test public void refusesWithoutPlatformDisclosure() {
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(PROMPT, ACTION), W, H));
    }
    @Test public void refusesAmbiguousDisclosureOrButtons() {
        ReadingTaskLocator.Box second = new ReadingTaskLocator.Box("立即抢购", 489, 2100, 702, 2160);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, DISCLOSURE, ACTION), W, H));
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, ACTION, second), W, H));
    }
    @Test public void refusesButtonsOutsideAnchorBand() {
        ReadingTaskLocator.Box farAbove = new ReadingTaskLocator.Box("查看详情", 489, 1900, 702, 1950);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, farAbove), W, H));
        ReadingTaskLocator.Box offCenter = new ReadingTaskLocator.Box("查看详情", 40, 2130, 253, 2188);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, offCenter), W, H));
    }
    @Test public void refusesButtonsWithRealSideEffects() {
        for (String label : new String[]{"立即下载", "安装应用", "去充值", "立即支付"}) {
            ReadingTaskLocator.Box denied = new ReadingTaskLocator.Box(label, 489, 2130, 702, 2188);
            assertNull(label, AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, denied), W, H));
        }
    }
    @Test public void describeReportsPromptDisclosureAndLowerZoneTexts() {
        String summary = AdPromptMatcher.describe(Arrays.asList(PROMPT, ACTION, DISCLOSURE), W, H);
        assertTrue(summary.contains("prompt=true"));
        assertTrue(summary.contains("disclosure=true"));
        assertTrue(summary.contains("查看详情@2130-2188"));
        assertTrue(summary.contains("进入详情页或第三方应用@2265-2316"));
        String lowerOnly = AdPromptMatcher.describe(Collections.singletonList(PROMPT), W, H);
        assertTrue(lowerOnly.contains("prompt=true"));
        assertTrue(lowerOnly.contains("disclosure=false"));
        assertTrue(lowerOnly.contains("lower=[]"));
    }
    @Test public void recognizesRewardConfirmationWithoutTreatingOtherMentionsAsSuccess() {
        assertTrue(AdPromptMatcher.hasReward(Collections.singletonList(new ReadingTaskLocator.Box("恭喜已获得奖励", 1, 1, 100, 50))));
        assertFalse(AdPromptMatcher.hasReward(Collections.singletonList(new ReadingTaskLocator.Box("看15秒可获得奖励", 1, 1, 100, 50))));
    }
}
