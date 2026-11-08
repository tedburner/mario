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
        assertTrue(summary.contains("anchor=true"));
        assertTrue(summary.contains("videoState=false"));
        assertTrue(summary.contains("查看详情@2130-2188"));
        String lowerOnly = AdPromptMatcher.describe(Collections.singletonList(PROMPT), W, H);
        assertTrue(lowerOnly.contains("prompt=true"));
        assertTrue(lowerOnly.contains("anchor=false"));
        assertTrue(lowerOnly.contains("lower=[]"));
    }
    @Test public void matchesVideoStateActionAboveAdMarkFromLiveFrame() {
        // 真机实测：千问广告视频播放态——披露行消失，仅剩右下角“广告”标识和上方“查看详情”。
        ReadingTaskLocator.Box brand = new ReadingTaskLocator.Box("千问", 489, 1988, 630, 2030);
        ReadingTaskLocator.Box slogan = new ReadingTaskLocator.Box("怎么现在才发现!!", 396, 2057, 703, 2097);
        ReadingTaskLocator.Box action = new ReadingTaskLocator.Box("查看详情", 497, 2215, 693, 2268);
        ReadingTaskLocator.Box adMark = new ReadingTaskLocator.Box("广告", 1067, 2402, 1133, 2433);
        assertSame(action, AdPromptMatcher.findDetails(Arrays.asList(brand, slogan, action, adMark), W, H));
        // 无“广告”标识时不兜底（证据不足）。
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(brand, slogan, action), W, H));
        // 按钮在广告标识下方时不匹配。
        ReadingTaskLocator.Box below = new ReadingTaskLocator.Box("查看详情", 497, 2440, 693, 2493);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(below, adMark), W, H));
        // 兜底只接受已验证安全文案家族；“立即抢购”无披露行时仍拒绝。
        ReadingTaskLocator.Box buy = new ReadingTaskLocator.Box("立即抢购", 497, 2215, 693, 2268);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(buy, adMark), W, H));
        // 两个候选时放弃。
        ReadingTaskLocator.Box second = new ReadingTaskLocator.Box("去逛逛", 497, 2140, 693, 2193);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(second, action, adMark), W, H));
    }
    @Test public void matchesWeChatBrowseLabelWithObservedMisreadFromLiveFrame() {
        // 真机实测：vivo 广告视频播放态——按钮“去微信看看”被 ML Kit 误读为“去徽信看看”；
        // 广告素材内嵌的“查看详情@1985”位于按钮区之外（top < 3/4 高度），被几何规则排除。
        ReadingTaskLocator.Box brand = new ReadingTaskLocator.Box("vivO", 444, 2031, 570, 2059);
        ReadingTaskLocator.Box creative = new ReadingTaskLocator.Box("查看详情", 444, 1985, 570, 2031);
        ReadingTaskLocator.Box copy = new ReadingTaskLocator.Box("11.11換机正当时,vivoY600续航强,低...", 252, 2054, 901, 2101);
        ReadingTaskLocator.Box action = new ReadingTaskLocator.Box("去徽信看看", 443, 2216, 694, 2268);
        ReadingTaskLocator.Box adMark = new ReadingTaskLocator.Box("广告", 1067, 2402, 1133, 2433);
        assertSame(action, AdPromptMatcher.findDetails(Arrays.asList(brand, creative, copy, action, adMark), W, H));
        // 正确写法“去微信看看”同样匹配。
        ReadingTaskLocator.Box correct = new ReadingTaskLocator.Box("去微信看看", 443, 2216, 694, 2268);
        assertSame(correct, AdPromptMatcher.findDetails(Arrays.asList(brand, creative, copy, correct, adMark), W, H));
    }
    @Test public void matchesJdEducationLabelWithTrailingMergedGlyph() {
        // 真机实测：京东教育优惠广告视频播放态——按钮被读成“查看详情惰”（查看详情+动画合并字符）。
        ReadingTaskLocator.Box brand = new ReadingTaskLocator.Box("专届推荐", 444, 1988, 570, 2030);
        ReadingTaskLocator.Box copy = new ReadingTaskLocator.Box("教育优惠11.11来装,亨敦育优惠叠国补..", 252, 2057, 901, 2100);
        ReadingTaskLocator.Box action = new ReadingTaskLocator.Box("查看详情惰", 443, 2216, 694, 2267);
        ReadingTaskLocator.Box adMark = new ReadingTaskLocator.Box("广告", 1067, 2402, 1133, 2433);
        assertSame(action, AdPromptMatcher.findDetails(Arrays.asList(brand, copy, action, adMark), W, H));
        // 单字内部误读同样匹配。
        ReadingTaskLocator.Box inner = new ReadingTaskLocator.Box("查看详惰", 443, 2216, 694, 2267);
        assertSame(inner, AdPromptMatcher.findDetails(Arrays.asList(brand, copy, inner, adMark), W, H));
        // 危险后缀长度超过 +1 容忍：仍然拒绝。
        ReadingTaskLocator.Box danger = new ReadingTaskLocator.Box("查看详情并支付", 443, 2216, 694, 2267);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(brand, copy, danger, adMark), W, H));
        // 超过单字误读（两个字符不同）：拒绝。
        ReadingTaskLocator.Box twoDiff = new ReadingTaskLocator.Box("查看详惰惰", 443, 2216, 694, 2267);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(brand, copy, twoDiff, adMark), W, H));
    }
    @Test public void describeReportsVideoState() {
        ReadingTaskLocator.Box action = new ReadingTaskLocator.Box("查看详情", 497, 2215, 693, 2268);
        ReadingTaskLocator.Box adMark = new ReadingTaskLocator.Box("广告", 1067, 2402, 1133, 2433);
        String summary = AdPromptMatcher.describe(Arrays.asList(action, adMark), W, H);
        assertTrue(summary.contains("anchor=true"));
        assertTrue(summary.contains("videoState=true"));
    }
    @Test public void recognizesRewardConfirmationWithoutTreatingOtherMentionsAsSuccess() {
        assertTrue(AdPromptMatcher.hasReward(Collections.singletonList(new ReadingTaskLocator.Box("恭喜已获得奖励", 1, 1, 100, 50))));
        assertFalse(AdPromptMatcher.hasReward(Collections.singletonList(new ReadingTaskLocator.Box("看15秒可获得奖励", 1, 1, 100, 50))));
    }
}
