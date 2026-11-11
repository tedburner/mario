package com.mario.assistant;

import com.mario.assistant.domain.model.TextBlock;
import com.mario.assistant.domain.welfare.AdCarouselPolicy;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class AdCarouselPolicyTest {
    private static TextBlock box(String text) {
        return new TextBlock(text, 0, 0, 10, 10);
    }
    @Test public void detectsLiveCarouselPromptFromDeviceFrame() {
        // 真机截图整行：“已完成第2个任务 滑动继续完成 10点章节卡”。
        assertTrue(AdCarouselPolicy.isContinuePrompt(Arrays.asList(
                box("已完成第2个任务 滑动继续完成 10点章节卡"),
                box("完成1个"), box("完成2个"), box("领取奖励"))));
    }
    @Test public void detectsSplitLinesAndOcrJitter() {
        assertTrue(AdCarouselPolicy.isContinuePrompt(Arrays.asList(
                box("已完成第2个任务"), box("滑动继续完戚"), box("10点章节卡"))));
        assertTrue(AdCarouselPolicy.isContinuePrompt(Collections.singletonList(box("滑动继续完"))));
    }
    @Test public void acceptsPromptSplitAcrossBoxes() {
        assertTrue(AdCarouselPolicy.isContinuePrompt(Arrays.asList(
                box("已完成第1个任务"), box("滑动"), box("继续完成"), box("10点章节卡"))));
        // 只有“继续”没有“滑动”的普通广告页不触发左滑。
        assertFalse(AdCarouselPolicy.isContinuePrompt(Arrays.asList(
                box("继续阅读"), box("查看详情"), box("广告"))));
    }
    @Test public void ignoresNextAdHeaderAndOrdinaryAdPage() {
        // 左滑后真机顶部变为“点击后看15秒 完成第3个任务”，不能再触发左滑。
        assertFalse(AdCarouselPolicy.isContinuePrompt(Arrays.asList(
                box("点击后看15秒 完成第3个任务"), box("完成1个"), box("完成2个"),
                box("领取奖励"), box("查看详情"), box("广告"))));
        assertFalse(AdCarouselPolicy.isContinuePrompt(Arrays.asList(
                box("已完成第2个任务"), box("立即抢购"), box("广告"))));
        assertFalse(AdCarouselPolicy.isContinuePrompt(Collections.emptyList()));
    }
    @Test public void detectsCompletionPanelWithoutSwipePrompt() {
        // 第3个任务完成后的真机整行：“恭喜完成任务 获得10点章节卡”。
        assertTrue(AdCarouselPolicy.isCompletion(Arrays.asList(
                box("恭喜完成任务 获得10点章节卡"), box("领取奖励"), box("查看详情"))));
        assertFalse(AdCarouselPolicy.isCompletion(Arrays.asList(
                box("已完成第3个任务"), box("查看详情"))));
        assertFalse(AdCarouselPolicy.isCompletion(Collections.emptyList()));
    }
    @Test public void findsUniqueDismissButtonOnly() {
        TextBlock know = box("知道了");
        assertSame(know, AdCarouselPolicy.findDismissButton(Arrays.asList(
                box("恭喜获得"), box("10点章节卡（1天）"), know)));
        // OCR 带标点时仍按完整“知道了”包含匹配。
        TextBlock knowPunct = box("知道了。");
        assertSame(knowPunct, AdCarouselPolicy.findDismissButton(Arrays.asList(
                box("恭喜获得"), knowPunct)));
        // 没有奖励文案时不认“知道了”，避免误点其他页面的提示。
        assertNull(AdCarouselPolicy.findDismissButton(Arrays.asList(box("知道了"))));
        assertNull(AdCarouselPolicy.findDismissButton(Arrays.asList(box("10点章节卡"))));
        assertNull(AdCarouselPolicy.findDismissButton(Arrays.asList(
                box("恭喜获得"), box("知道了"), box("知道了。"))));
    }

    @Test public void ignoresAdCreativeThatOnlyContainsZhiDaoWithoutLe() {
        // 真机（2026-11-11 美团素材）：“别人都不知道的美团”+“点击后,看15秒可获得奖励”
        // 同时满足旧的“知道 + 获得”条件，被误当成奖励弹窗按钮，stage 1 直接停止。
        assertNull(AdCarouselPolicy.findDismissButton(Arrays.asList(
                box("点击后,看15秒可获得奖励"), box("别人都不知道的美团"),
                box("立即领取"), box("广告"))));
    }
    @Test public void toleratesCongratsMisreadOnCouponDialog() {
        // 真机 ML Kit 把“恭喜获得200-30点订阅券”读成“恭套获得200-30点订阅券”。
        TextBlock know = box("知道了");
        assertSame(know, AdCarouselPolicy.findDismissButton(Arrays.asList(
                box("恭套获得200-30点订阅券"), know)));
        TextBlock knowFull = box("知道了");
        assertSame(knowFull, AdCarouselPolicy.findDismissButton(Arrays.asList(
                box("恭喜获得 200-30点订阅券"), knowFull)));
    }

    @Test public void acceptsCenteredButtonRowMeasuredOnDevice() {
        // 真机框：10(0知道了8)8点@418,1714-802,1767，屏幕 1200x2670，中心 610 正落在胶囊按钮上。
        assertTrue(AdCarouselPolicy.isCenteredButtonRow(
                new TextBlock("10(0知道了8)8点", 418, 1714, 802, 1767), 1200, 2670));
    }

    @Test public void rejectsRowsThatAreNotCenteredButtons() {
        // 整行：宽度远超按钮带。
        assertFalse(AdCarouselPolicy.isCenteredButtonRow(
                new TextBlock("10点 知道了 888点", 0, 1714, 1200, 1767), 1200, 2670));
        // 右侧任务行按钮：水平不居中。
        assertFalse(AdCarouselPolicy.isCenteredButtonRow(
                new TextBlock("去完成", 900, 1714, 1130, 1767), 1200, 2670));
        // 高度像整段文字块，不是单行按钮。
        assertFalse(AdCarouselPolicy.isCenteredButtonRow(
                new TextBlock("10(0知道了8)8点", 418, 1500, 802, 2100), 1200, 2670));
        assertFalse(AdCarouselPolicy.isCenteredButtonRow(null, 1200, 2670));
        assertFalse(AdCarouselPolicy.isCenteredButtonRow(
                new TextBlock("知道了", 418, 1714, 802, 1767), 0, 0));
    }
}
