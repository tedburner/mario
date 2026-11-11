package com.mario.assistant;

import com.mario.assistant.domain.model.TextBlock;
import com.mario.assistant.domain.welfare.AdPromptMatcher;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdPromptMatcherTest {
    // 真机截图 (1200x2670) 实测几何：京东/贝亲激励广告底部操作区。
    private static final int W = 1200, H = 2670;
    private static final TextBlock PROMPT = new TextBlock("点击后，看15秒可获得奖励", 133, 1723, 977, 1824);
    private static final TextBlock DISCLOSURE = new TextBlock("进入详情页或第三方应用", 361, 2265, 838, 2316);
    private static final TextBlock ACTION = new TextBlock("查看详情", 489, 2130, 702, 2188);

    @Test public void matchesObservedDetailButtonGeometry() {
        assertSame(ACTION, AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, ACTION), W, H));
    }
    @Test public void matchesBrowseVariantWithoutLabelKnowledge() {
        TextBlock browse = new TextBlock("去逛逛", 489, 2130, 702, 2188);
        assertSame(browse, AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, browse), W, H));
    }
    @Test public void matchesPurchaseStyleCtaFromLiveAd() {
        // 用户实机确认：底部按钮文案为“立即抢购”，披露行仍为平台样板，点击仅进入落地页。
        TextBlock buy = new TextBlock("立即抢购", 489, 2130, 702, 2188);
        assertSame(buy, AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, buy), W, H));
    }
    @Test public void acceptsObservedOcrTyposInDisclosure() {
        // 实机 ML Kit 已观察两种单字误读：详惰（局部裁剪）、详倩（京东广告实时帧）。
        for (String variant : new String[]{"进入详情页或第三方应用", "进入详惰页或第三方应用", "进入详倩页或第三方应用"}) {
            TextBlock typo = new TextBlock(variant, 361, 2265, 838, 2316);
            assertSame(variant, ACTION, AdPromptMatcher.findDetails(Arrays.asList(PROMPT, typo, ACTION), W, H));
        }
    }
    @Test public void refusesDisclosureLookalikesWithChangedStructure() {
        for (String variant : new String[]{"进入详倩页或第三方应用啦", "浏览详倩页或第三方应用", "进入详倩页第第三方应用", "进入详情页或第三方应用 App"}) {
            TextBlock lookalike = new TextBlock(variant, 361, 2265, 838, 2316);
            assertNull(variant, AdPromptMatcher.findDetails(Arrays.asList(PROMPT, lookalike, ACTION), W, H));
        }
    }
    @Test public void refusesWithoutPlatformDisclosure() {
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(PROMPT, ACTION), W, H));
    }
    @Test public void refusesAmbiguousDisclosureOrButtons() {
        TextBlock second = new TextBlock("立即抢购", 489, 2100, 702, 2160);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, DISCLOSURE, ACTION), W, H));
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, ACTION, second), W, H));
    }
    @Test public void refusesButtonsOutsideAnchorBand() {
        TextBlock farAbove = new TextBlock("查看详情", 489, 1900, 702, 1950);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, farAbove), W, H));
        TextBlock offCenter = new TextBlock("查看详情", 40, 2130, 253, 2188);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, offCenter), W, H));
    }
    @Test public void refusesInstallStyleButtons() {
        for (String label : new String[]{"立即下载", "安装应用"}) {
            TextBlock denied = new TextBlock(label, 489, 2130, 702, 2188);
            assertNull(label, AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, denied), W, H));
        }
    }
    @Test public void allowsPaymentStyleCopyWithoutTextGating() {
        // 按钮文案不参与安全判定：点击后仅打开落地页，项目在落地页内不做任何点击。
        for (String label : new String[]{"立即支付", "去充值", "立即抢购"}) {
            TextBlock pay = new TextBlock(label, 489, 2130, 702, 2188);
            assertSame(label, pay, AdPromptMatcher.findDetails(Arrays.asList(PROMPT, DISCLOSURE, pay), W, H));
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
        TextBlock brand = new TextBlock("千问", 489, 1988, 630, 2030);
        TextBlock slogan = new TextBlock("怎么现在才发现!!", 396, 2057, 703, 2097);
        TextBlock action = new TextBlock("查看详情", 497, 2215, 693, 2268);
        TextBlock adMark = new TextBlock("广告", 1067, 2402, 1133, 2433);
        assertSame(action, AdPromptMatcher.findDetails(Arrays.asList(brand, slogan, action, adMark), W, H));
        // 无“广告”标识时不兜底（证据不足）。
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(brand, slogan, action), W, H));
        // 按钮在广告标识下方时不匹配。
        TextBlock below = new TextBlock("查看详情", 497, 2440, 693, 2493);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(below, adMark), W, H));
        // 真机实测（闲鱼素材视频播放态）：无披露行，“立即抢购”位于“广告”标识上方，点击仅进落地页。
        TextBlock buy = new TextBlock("立即抢购", 497, 2215, 693, 2268);
        assertSame(buy, AdPromptMatcher.findDetails(Arrays.asList(buy, adMark), W, H));
        // 仅拒绝安装类动作；支付类文案放行。
        TextBlock install = new TextBlock("立即下载", 497, 2215, 693, 2268);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(install, adMark), W, H));
        // 两个候选时放弃。
        TextBlock second = new TextBlock("去逛逛", 497, 2140, 693, 2193);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(second, action, adMark), W, H));
    }
    @Test public void picksVideoActionWhenOcrMergesDisclosureWithTrailingCopy() {
        // 真机实测（2026-11-11，ANA 视频广告）：披露行被并成
        // “进入详倩页或第三方应用后,广告继续计时”，严格模板不成立，视频态兜底又把它
        // 当成第二个候选，导致「点击了解详情」每帧被放弃、stage 1 空转到超时。
        TextBlock merged = new TextBlock("进入详倩页或第三方应用后,广告继续计时", 360, 2261, 840, 2304);
        TextBlock creative = new TextBlock("ANA小程序上线,多重优惠来袭!", 300, 1962, 900, 2010);
        TextBlock action = new TextBlock("点击了解详情", 470, 2168, 730, 2223);
        TextBlock adMark = new TextBlock("广告", 1071, 2363, 1131, 2398);
        assertSame(action, AdPromptMatcher.findDetails(
                Arrays.asList(creative, action, merged, adMark), W, H));
        // 严格模板仍只认 11 字披露行，避免把广告素材里的普通长句当成披露行。
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(PROMPT, merged, ACTION), W, H));
    }

    @Test public void matchesWeChatBrowseLabelWithObservedMisreadFromLiveFrame() {
        // 真机实测：vivo 广告视频播放态——按钮“去微信看看”被 ML Kit 误读为“去徽信看看”；
        // 广告素材内嵌的“查看详情@1985”位于按钮区之外（top < 3/4 高度），被几何规则排除。
        TextBlock brand = new TextBlock("vivO", 444, 2031, 570, 2059);
        TextBlock creative = new TextBlock("查看详情", 444, 1985, 570, 2031);
        TextBlock copy = new TextBlock("11.11換机正当时,vivoY600续航强,低...", 252, 2054, 901, 2101);
        TextBlock action = new TextBlock("去徽信看看", 443, 2216, 694, 2268);
        TextBlock adMark = new TextBlock("广告", 1067, 2402, 1133, 2433);
        assertSame(action, AdPromptMatcher.findDetails(Arrays.asList(brand, creative, copy, action, adMark), W, H));
        // 正确写法“去微信看看”同样匹配。
        TextBlock correct = new TextBlock("去微信看看", 443, 2216, 694, 2268);
        assertSame(correct, AdPromptMatcher.findDetails(Arrays.asList(brand, creative, copy, correct, adMark), W, H));
    }
    @Test public void matchesJdEducationLabelWithTrailingMergedGlyph() {
        // 真机实测：京东教育优惠广告视频播放态——按钮被读成“查看详情惰”（查看详情+动画合并字符）。
        TextBlock brand = new TextBlock("专届推荐", 444, 1988, 570, 2030);
        TextBlock copy = new TextBlock("教育优惠11.11来装,亨敦育优惠叠国补..", 252, 2057, 901, 2100);
        TextBlock action = new TextBlock("查看详情惰", 443, 2216, 694, 2267);
        TextBlock adMark = new TextBlock("广告", 1067, 2402, 1133, 2433);
        assertSame(action, AdPromptMatcher.findDetails(Arrays.asList(brand, copy, action, adMark), W, H));
        // 单字内部误读同样匹配。
        TextBlock inner = new TextBlock("查看详惰", 443, 2216, 694, 2267);
        assertSame(inner, AdPromptMatcher.findDetails(Arrays.asList(brand, copy, inner, adMark), W, H));
        // 文案后缀不参与判定：支付类文案同样放行（点击只打开落地页）。
        TextBlock paymentCopy = new TextBlock("查看详情并支付", 443, 2216, 694, 2267);
        assertSame(paymentCopy, AdPromptMatcher.findDetails(Arrays.asList(brand, copy, paymentCopy, adMark), W, H));
        // 超出按钮几何带的素材文字不会被误选。
        TextBlock farAbove = new TextBlock("查看详情", 443, 1985, 694, 2035);
        assertNull(AdPromptMatcher.findDetails(Arrays.asList(brand, copy, farAbove, adMark), W, H));
    }
    @Test public void describeReportsVideoState() {
        TextBlock action = new TextBlock("查看详情", 497, 2215, 693, 2268);
        TextBlock adMark = new TextBlock("广告", 1067, 2402, 1133, 2433);
        String summary = AdPromptMatcher.describe(Arrays.asList(action, adMark), W, H);
        assertTrue(summary.contains("anchor=true"));
        assertTrue(summary.contains("videoState=true"));
    }
    @Test public void recognizesRewardConfirmationWithoutTreatingOtherMentionsAsSuccess() {
        assertTrue(AdPromptMatcher.hasReward(Collections.singletonList(new TextBlock("恭喜已获得奖励", 1, 1, 100, 50))));
        assertFalse(AdPromptMatcher.hasReward(Collections.singletonList(new TextBlock("看15秒可获得奖励", 1, 1, 100, 50))));
    }
}
