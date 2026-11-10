package com.mario.assistant;

import com.mario.assistant.domain.model.TextBlock;
import com.mario.assistant.domain.welfare.WelfareTaskLocator;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class WelfareTaskLocatorTest {
    @Test public void matchesTheButtonInTheIncentiveTaskRow() {
        TextBlock title = new TextBlock("激励任务", 50, 800, 180, 850);
        TextBlock unrelated = new TextBlock("去完成", 800, 300, 950, 350);
        TextBlock action = new TextBlock("去完成", 800, 805, 950, 855);
        WelfareTaskLocator.Row row = WelfareTaskLocator.find(Arrays.asList(title, unrelated, action), 1000);
        assertNotNull(row);
        assertEquals("激励任务", row.title);
        assertEquals(action, row.button);
    }
    @Test public void fallsBackToLaterRowsWhenEarlierUnavailable() {
        // 激励任务已领取（无去完成按钮）→ 回退到“完成3个广告任务得奖励”。
        TextBlock claimed = new TextBlock("已领取", 800, 500, 950, 550);
        TextBlock incentive = new TextBlock("激励任务", 50, 500, 260, 550);
        TextBlock milestoneTitle = new TextBlock("完成3个广告任务得奖励", 50, 900, 300, 950);
        TextBlock milestoneAction = new TextBlock("去完成", 800, 905, 950, 955);
        WelfareTaskLocator.Row row = WelfareTaskLocator.find(Arrays.asList(claimed, incentive, milestoneTitle, milestoneAction), 1000);
        assertNotNull(row);
        assertEquals("完成3个广告任务得奖励", row.title);
        assertFalse(row.signin);
    }
    @Test public void followsUserSpecifiedPriorityOrder() {
        // 用户指定顺序：限时 → 激励 → 完成3个广告任务的奖励 → 去携程签到领积分。
        TextBlock limitedTitle = new TextBlock("【限时】广告·加点!", 50, 300, 280, 350);
        TextBlock limitedAction = new TextBlock("去完成", 800, 305, 950, 355);
        TextBlock incentive = new TextBlock("激励任务", 50, 700, 260, 750);
        TextBlock incentiveAction = new TextBlock("去完成", 800, 705, 950, 755);
        WelfareTaskLocator.Row row = WelfareTaskLocator.find(Arrays.asList(
                limitedTitle, limitedAction, incentive, incentiveAction), 1000);
        assertEquals("限时广告加点", row.title);
        // 全部可用的完整顺序验证。
        TextBlock milestoneTitle = new TextBlock("完成3个广告任务得奖励", 50, 1100, 300, 1150);
        TextBlock milestoneAction = new TextBlock("去完成", 800, 1105, 950, 1155);
        TextBlock ctripTitle = new TextBlock("去携程签到领积分", 50, 1500, 300, 1550);
        TextBlock ctripAction = new TextBlock("去签到", 800, 1505, 950, 1555);
        assertTrue(WelfareTaskLocator.find(Arrays.asList(
                limitedTitle, limitedAction, incentive, incentiveAction, milestoneTitle, milestoneAction, ctripTitle, ctripAction), 1000)
                .title.equals("限时广告加点"));
        WelfareTaskLocator.Row ctrip = WelfareTaskLocator.find(Arrays.asList(ctripTitle, ctripAction), 1000);
        assertNotNull(ctrip);
        assertTrue(ctrip.signin);
        assertEquals("去签到", ctrip.button.text);
        // “做任务领惊喜福利”已按用户顺序移出匹配列表。
        TextBlock hourly = new TextBlock("做任务领惊喜福利", 50, 1900, 300, 1950);
        TextBlock hourlyAction = new TextBlock("去完成", 800, 1905, 950, 1955);
        assertNull(WelfareTaskLocator.find(Arrays.asList(hourly, hourlyAction), 1000));
    }
    @Test public void supportsMilestoneRowAndRejectsOutOfScopeRows() {
        TextBlock milestone = new TextBlock("完成3个广告任务得奖励", 50, 700, 280, 750);
        TextBlock action = new TextBlock("去完成", 800, 705, 950, 755);
        WelfareTaskLocator.Row row = WelfareTaskLocator.find(Arrays.asList(milestone, action), 1000);
        assertNotNull(row);
        assertEquals("完成3个广告任务得奖励", row.title);
        // 范围外的“完成1个广告任务得奖励”不参与匹配。
        TextBlock other = new TextBlock("完成1个广告任务得奖励", 50, 700, 280, 750);
        assertNull(WelfareTaskLocator.find(Collections.singletonList(other), 1000));
        assertNull(WelfareTaskLocator.find(Arrays.asList(other, action), 1000));
    }
    @Test public void matchesLimitedTimeAdRowWithDecorationTolerance() {
        // 真机截图（点数页）：标题“【限时】广告·加点！”，装饰字符允许 OCR 缺失。
        TextBlock action = new TextBlock("去完成", 800, 1605, 950, 1655);
        for (String variant : new String[]{"【限时】广告·加点！", "限时广告·加点！", "【限时】广告加点", "限时广告加点"}) {
            TextBlock title = new TextBlock(variant, 50, 1600, 280, 1650);
            WelfareTaskLocator.Row row = WelfareTaskLocator.find(Arrays.asList(title, action), 1000);
            assertNotNull(variant, row);
            assertEquals("限时广告加点", row.title);
            assertFalse(row.goRead);
        }
        // 范围外的“限时福利”区块标题不参与匹配。
        TextBlock welfare = new TextBlock("限时福利", 50, 1600, 280, 1650);
        assertNull(WelfareTaskLocator.find(Arrays.asList(welfare, action), 1000));
    }
    @Test public void supportsReadingUnlockButtonOnLimitedTimeRow() {
        // 真机日志：首个任务完成后，限时行按钮变为“阅读1分钟”（解锁下一个广告任务）。
        TextBlock title = new TextBlock("【限时】广告·加点!", 50, 2359, 280, 2386);
        TextBlock reward = new TextBlock("10点章节卡", 400, 2359, 520, 2386);
        TextBlock read = new TextBlock("阅读1分钟", 800, 2359, 950, 2386);
        WelfareTaskLocator.Row row = WelfareTaskLocator.find(Arrays.asList(title, reward, read), 1200);
        assertNotNull(row);
        assertTrue(row.goRead);
        assertEquals("阅读1分钟", row.button.text);
        TextBlock goRead = new TextBlock("去阅读", 800, 2359, 950, 2386);
        assertTrue(WelfareTaskLocator.find(Arrays.asList(title, goRead), 1200).goRead);
        // 其他行的“阅读1分钟”按钮不参与匹配（激励任务只认去完成）。
        TextBlock incentive = new TextBlock("激励任务", 50, 2497, 180, 2544);
        assertNull(WelfareTaskLocator.find(Arrays.asList(incentive, read), 1200));
        // 去完成优先且不误判为解锁。
        TextBlock done = new TextBlock("去完成", 800, 2359, 950, 2386);
        assertFalse(WelfareTaskLocator.find(Arrays.asList(title, done), 1200).goRead);
    }
    @Test public void limitedTimeRowHasHighestPriorityPerUserOrder() {
        // 用户指定：限时任务最优先，即使激励任务同时可用也先做限时。
        TextBlock incentive = new TextBlock("激励任务", 50, 500, 180, 550);
        TextBlock incentiveAction = new TextBlock("去完成", 800, 505, 950, 555);
        TextBlock limited = new TextBlock("【限时】广告·加点！", 50, 1600, 280, 1650);
        TextBlock limitedAction = new TextBlock("去完成", 800, 1605, 950, 1655);
        WelfareTaskLocator.Row row = WelfareTaskLocator.find(Arrays.asList(incentive, incentiveAction, limited, limitedAction), 1000);
        assertEquals("限时广告加点", row.title);
    }
    @Test public void prefersHighestPriorityRowWhenSeveralAvailable() {
        TextBlock incentive = new TextBlock("激励任务", 50, 500, 180, 550);
        TextBlock incentiveAction = new TextBlock("去完成", 800, 505, 950, 555);
        TextBlock milestone = new TextBlock("完成3个广告任务得奖励", 50, 900, 300, 950);
        TextBlock milestoneAction = new TextBlock("去完成", 800, 905, 950, 955);
        WelfareTaskLocator.Row row = WelfareTaskLocator.find(Arrays.asList(incentive, incentiveAction, milestone, milestoneAction), 1000);
        assertEquals("激励任务", row.title);
    }
    @Test public void refusesAmbiguousOrUnrelatedButtons() {
        TextBlock title = new TextBlock("激励任务", 50, 800, 180, 850);
        TextBlock a = new TextBlock("去完成", 600, 805, 700, 855);
        TextBlock b = new TextBlock("去完成", 800, 805, 900, 855);
        assertNull(WelfareTaskLocator.find(Arrays.asList(title, a, b), 1000));
        assertNull(WelfareTaskLocator.find(Arrays.asList(title, new TextBlock("去购买", 800, 805, 900, 855)), 1000));
    }
    @Test public void firstBookPicksTopmostContentTextExcludingHeadersAndTabs() {
        // 真机书架截图 (1200x2670)：每日导读卡片 → 书架页头（可能被 OCR 合并为“书架浏览记录”）→ 书籍列表。
        TextBlock dailyBook = new TextBlock("拯救曼联", 64, 353, 170, 381);
        TextBlock dailyTag = new TextBlock("每日导读", 42, 461, 176, 501);
        TextBlock mergedHeader = new TextBlock("书架浏览记录", 40, 587, 330, 641);
        TextBlock book1 = new TextBlock("天人图谱", 273, 701, 480, 754);
        TextBlock book1Meta = new TextBlock("误道者·169章未读", 273, 786, 530, 825);
        TextBlock book2 = new TextBlock("重走警途1993", 273, 988, 570, 1038);
        TextBlock tab = new TextBlock("发现", 700, 2466, 760, 2520);
        TextBlock found = WelfareTaskLocator.firstBook(Arrays.asList(
                dailyBook, dailyTag, mergedHeader, book1, book1Meta, book2, tab), 2670);
        assertSame(book1, found);
        // 合并页头不存在（OCR 分字识别）时同样锚定成功。
        TextBlock splitHeader = new TextBlock("书架", 40, 598, 160, 638);
        assertSame(book1, WelfareTaskLocator.firstBook(Arrays.asList(
                dailyBook, dailyTag, splitHeader, book1, book1Meta, book2, tab), 2670));
        // 无页头锚点且全是页头/卡片/标签时返回 null。
        assertNull(WelfareTaskLocator.firstBook(Arrays.asList(dailyBook, dailyTag, tab), 2670));
    }
    @Test public void recognizesWelfarePageWithoutTitleBar() {
        // 真机：福利中心滚到顶部时标题栏没有“福利中心”，只有“做任务领惊喜福利”等文案；
        // 只认“福利中心”会漏判，导致找不到任务时也不下滑。
        assertTrue(WelfareTaskLocator.isWelfareCenter(Arrays.asList(
                new TextBlock("做任务领惊喜福利", 50, 1900, 300, 1950),
                new TextBlock("本周收益", 400, 500, 600, 550))));
        assertTrue(WelfareTaskLocator.isWelfareCenter(Collections.singletonList(
                new TextBlock("福利中心", 300, 100, 500, 160))));
        assertFalse(WelfareTaskLocator.isWelfareCenter(Arrays.asList(
                new TextBlock("书架", 40, 600, 160, 640),
                new TextBlock("我的", 900, 2400, 960, 2450))));
    }
    @Test public void ctripRowToleratesOcrVariants() {
        TextBlock action = new TextBlock("去完成", 800, 1505, 950, 1555);
        for (String variant : new String[]{"去携程签到领积分", "携程签到领积分", "去携程签到"}) {
            TextBlock title = new TextBlock(variant, 50, 1500, 300, 1550);
            WelfareTaskLocator.Row row = WelfareTaskLocator.find(Arrays.asList(title, action), 1000);
            assertNotNull(variant, row);
            assertTrue(variant, row.signin);
        }
        // 真机 ML Kit 把该行按钮读成“领奖励”：按标题右侧几何定位，不依赖按钮文案。
        TextBlock title = new TextBlock("去携程签到领积分", 81, 1383, 465, 1429);
        TextBlock misread = new TextBlock("领奖励", 947, 1418, 1057, 1456);
        TextBlock coin = new TextBlock("+1点币(。", 81, 1456, 256, 1496);
        TextBlock above = new TextBlock("去完成", 947, 1225, 1061, 1252);
        WelfareTaskLocator.Row signin = WelfareTaskLocator.find(
                Arrays.asList(title, coin, above, misread), 1200);
        assertNotNull(signin);
        assertSame(misread, signin.button);
        assertTrue(signin.signin);
        // 同一行右侧出现两个短标签时不点（避免误点帮助图标/其他控件）。
        assertNull(WelfareTaskLocator.find(Arrays.asList(title,
                new TextBlock("去完成", 800, 1385, 900, 1430),
                new TextBlock("领奖励", 947, 1418, 1057, 1456)), 1200));
        // 其他行仍只认“去完成”文案，不套用签到行的几何兜底。
        TextBlock incentive = new TextBlock("激励任务", 50, 1383, 260, 1429);
        assertNull(WelfareTaskLocator.find(Arrays.asList(incentive,
                new TextBlock("领奖励", 947, 1418, 1057, 1456)), 1200));
    }
    @Test public void claimedCtripRowIsSkippedAndReported() {
        TextBlock title = new TextBlock("去携程签到领积分", 81, 1383, 465, 1429);
        TextBlock claimed = new TextBlock("已领取", 947, 1418, 1057, 1456);
        // 已领取：不再点击（几何定位会接受任意短标签，必须排除“已…”），并报告已领取用于收尾。
        assertNull(WelfareTaskLocator.find(Arrays.asList(title, claimed), 1200));
        assertTrue(WelfareTaskLocator.claimedSignin(Arrays.asList(title, claimed), 1200));
        // “领奖励”是可点的兑换态，不算已领取。
        TextBlock claimable = new TextBlock("领奖励", 947, 1418, 1057, 1456);
        assertNotNull(WelfareTaskLocator.find(Arrays.asList(title, claimable), 1200));
        assertFalse(WelfareTaskLocator.claimedSignin(Arrays.asList(title, claimable), 1200));
        // 其他行的“已领取”不触发收尾。
        assertFalse(WelfareTaskLocator.claimedSignin(Arrays.asList(
                new TextBlock("激励任务", 50, 1383, 260, 1429), claimed), 1200));
        // 同一屏远处的“已领取”（不同任务行）不算。
        assertFalse(WelfareTaskLocator.claimedSignin(Arrays.asList(title,
                new TextBlock("已领取", 947, 1718, 1057, 1756)), 1200));
    }
    @Test public void signinClaimButtonOnlyAcceptsClaimLabels() {
        // 真机 2026-11-10 14:01：签到完成后该行按钮为「领奖励」，必须再点一次才到账。
        TextBlock title = new TextBlock("去携程签到领积分", 81, 1727, 465, 1773);
        TextBlock claim = new TextBlock("领奖励", 947, 1763, 1057, 1801);
        TextBlock coin = new TextBlock("+1点币(。", 81, 1800, 256, 1840);
        assertSame(claim, WelfareTaskLocator.signinClaimButton(Arrays.asList(title, coin, claim), 1200));
        // 已领取 / 去完成 都不是可领取态。
        assertNull(WelfareTaskLocator.signinClaimButton(Arrays.asList(title,
                new TextBlock("已领取", 947, 1763, 1057, 1801)), 1200));
        assertNull(WelfareTaskLocator.signinClaimButton(Arrays.asList(title,
                new TextBlock("去完成", 947, 1763, 1057, 1801)), 1200));
        // 其他行的「领奖励」不套用签到行的几何兜底。
        TextBlock incentive = new TextBlock("激励任务", 81, 1727, 260, 1773);
        assertNull(WelfareTaskLocator.signinClaimButton(Arrays.asList(incentive, claim), 1200));
        assertNull(WelfareTaskLocator.signinClaimButton(Collections.emptyList(), 1200));
    }

    @Test public void describeListsRecognizedRowTitlesButtonsAndBottomZone() {
        String summary = WelfareTaskLocator.describe(Arrays.asList(
                new TextBlock("激励任务", 50, 500, 180, 550),
                new TextBlock("已领取", 800, 505, 950, 555),
                new TextBlock("完成3个广告任务得奖励", 50, 900, 300, 950),
                new TextBlock("去完成", 800, 905, 950, 955),
                new TextBlock("我的", 900, 1800, 960, 1840)), 2000);
        assertTrue(summary.contains("激励任务"));
        assertTrue(summary.contains("完成3个广告任务得奖励"));
        assertTrue(summary.contains("已领取@505"));
        assertTrue(summary.contains("去完成@905"));
        assertTrue(summary.contains("bottom=[我的@900,1800-960,1840]"));
    }
}
