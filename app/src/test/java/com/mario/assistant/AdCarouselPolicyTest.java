package com.mario.assistant;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class AdCarouselPolicyTest {
    private static ReadingTaskLocator.Box box(String text) {
        return new ReadingTaskLocator.Box(text, 0, 0, 10, 10);
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
        ReadingTaskLocator.Box know = box("知道了");
        assertSame(know, AdCarouselPolicy.findDismissButton(Arrays.asList(
                box("恭喜获得"), box("10点章节卡（1天）"), know)));
        // OCR 带标点/误识时按“知道”包含匹配。
        ReadingTaskLocator.Box knowPunct = box("知道了。");
        assertSame(knowPunct, AdCarouselPolicy.findDismissButton(Arrays.asList(
                box("恭喜获得"), knowPunct)));
        // 没有奖励文案时不认“知道了”，避免误点其他页面的提示。
        assertNull(AdCarouselPolicy.findDismissButton(Arrays.asList(box("知道了"))));
        assertNull(AdCarouselPolicy.findDismissButton(Arrays.asList(box("10点章节卡"))));
        assertNull(AdCarouselPolicy.findDismissButton(Arrays.asList(
                box("恭喜获得"), box("知道了"), box("知道了。"))));
    }
    @Test public void toleratesCongratsMisreadOnCouponDialog() {
        // 真机 ML Kit 把“恭喜获得200-30点订阅券”读成“恭套获得200-30点订阅券”。
        ReadingTaskLocator.Box know = box("知道了");
        assertSame(know, AdCarouselPolicy.findDismissButton(Arrays.asList(
                box("恭套获得200-30点订阅券"), know)));
        ReadingTaskLocator.Box knowFull = box("知道了");
        assertSame(knowFull, AdCarouselPolicy.findDismissButton(Arrays.asList(
                box("恭喜获得 200-30点订阅券"), knowFull)));
    }
}
