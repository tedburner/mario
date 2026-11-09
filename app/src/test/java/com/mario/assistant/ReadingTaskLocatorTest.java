package com.mario.assistant;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReadingTaskLocatorTest {
    @Test public void matchesTheButtonInTheIncentiveTaskRow() {
        ReadingTaskLocator.Box title = new ReadingTaskLocator.Box("激励任务", 50, 800, 180, 850);
        ReadingTaskLocator.Box unrelated = new ReadingTaskLocator.Box("去完成", 800, 300, 950, 350);
        ReadingTaskLocator.Box action = new ReadingTaskLocator.Box("去完成", 800, 805, 950, 855);
        ReadingTaskLocator.Row row = ReadingTaskLocator.find(Arrays.asList(title, unrelated, action), 1000);
        assertNotNull(row);
        assertEquals("激励任务", row.title);
        assertEquals(action, row.button);
    }
    @Test public void fallsBackToLaterRowsWhenEarlierUnavailable() {
        // 激励任务已领取（无去完成按钮）→ 回退到“完成3个广告任务得奖励”。
        ReadingTaskLocator.Box claimed = new ReadingTaskLocator.Box("已领取", 800, 500, 950, 550);
        ReadingTaskLocator.Box incentive = new ReadingTaskLocator.Box("激励任务", 50, 500, 260, 550);
        ReadingTaskLocator.Box milestoneTitle = new ReadingTaskLocator.Box("完成3个广告任务得奖励", 50, 900, 300, 950);
        ReadingTaskLocator.Box milestoneAction = new ReadingTaskLocator.Box("去完成", 800, 905, 950, 955);
        ReadingTaskLocator.Row row = ReadingTaskLocator.find(Arrays.asList(claimed, incentive, milestoneTitle, milestoneAction), 1000);
        assertNotNull(row);
        assertEquals("完成3个广告任务得奖励", row.title);
        assertFalse(row.signin);
    }
    @Test public void followsUserSpecifiedPriorityOrder() {
        // 用户指定顺序：限时 → 激励 → 完成3个广告任务的奖励 → 去携程签到领积分。
        ReadingTaskLocator.Box limitedTitle = new ReadingTaskLocator.Box("【限时】广告·加点!", 50, 300, 280, 350);
        ReadingTaskLocator.Box limitedAction = new ReadingTaskLocator.Box("去完成", 800, 305, 950, 355);
        ReadingTaskLocator.Box incentive = new ReadingTaskLocator.Box("激励任务", 50, 700, 260, 750);
        ReadingTaskLocator.Box incentiveAction = new ReadingTaskLocator.Box("去完成", 800, 705, 950, 755);
        ReadingTaskLocator.Row row = ReadingTaskLocator.find(Arrays.asList(
                limitedTitle, limitedAction, incentive, incentiveAction), 1000);
        assertEquals("限时广告加点", row.title);
        // 全部可用的完整顺序验证。
        ReadingTaskLocator.Box milestoneTitle = new ReadingTaskLocator.Box("完成3个广告任务得奖励", 50, 1100, 300, 1150);
        ReadingTaskLocator.Box milestoneAction = new ReadingTaskLocator.Box("去完成", 800, 1105, 950, 1155);
        ReadingTaskLocator.Box ctripTitle = new ReadingTaskLocator.Box("去携程签到领积分", 50, 1500, 300, 1550);
        ReadingTaskLocator.Box ctripAction = new ReadingTaskLocator.Box("去签到", 800, 1505, 950, 1555);
        assertTrue(ReadingTaskLocator.find(Arrays.asList(
                limitedTitle, limitedAction, incentive, incentiveAction, milestoneTitle, milestoneAction, ctripTitle, ctripAction), 1000)
                .title.equals("限时广告加点"));
        ReadingTaskLocator.Row ctrip = ReadingTaskLocator.find(Arrays.asList(ctripTitle, ctripAction), 1000);
        assertNotNull(ctrip);
        assertTrue(ctrip.signin);
        assertEquals("去签到", ctrip.button.text);
        // “做任务领惊喜福利”已按用户顺序移出匹配列表。
        ReadingTaskLocator.Box hourly = new ReadingTaskLocator.Box("做任务领惊喜福利", 50, 1900, 300, 1950);
        ReadingTaskLocator.Box hourlyAction = new ReadingTaskLocator.Box("去完成", 800, 1905, 950, 1955);
        assertNull(ReadingTaskLocator.find(Arrays.asList(hourly, hourlyAction), 1000));
    }
    @Test public void supportsMilestoneRowAndRejectsOutOfScopeRows() {
        ReadingTaskLocator.Box milestone = new ReadingTaskLocator.Box("完成3个广告任务得奖励", 50, 700, 280, 750);
        ReadingTaskLocator.Box action = new ReadingTaskLocator.Box("去完成", 800, 705, 950, 755);
        ReadingTaskLocator.Row row = ReadingTaskLocator.find(Arrays.asList(milestone, action), 1000);
        assertNotNull(row);
        assertEquals("完成3个广告任务得奖励", row.title);
        // 范围外的“完成1个广告任务得奖励”不参与匹配。
        ReadingTaskLocator.Box other = new ReadingTaskLocator.Box("完成1个广告任务得奖励", 50, 700, 280, 750);
        assertNull(ReadingTaskLocator.find(Collections.singletonList(other), 1000));
        assertNull(ReadingTaskLocator.find(Arrays.asList(other, action), 1000));
    }
    @Test public void matchesLimitedTimeAdRowWithDecorationTolerance() {
        // 真机截图（点数页）：标题“【限时】广告·加点！”，装饰字符允许 OCR 缺失。
        ReadingTaskLocator.Box action = new ReadingTaskLocator.Box("去完成", 800, 1605, 950, 1655);
        for (String variant : new String[]{"【限时】广告·加点！", "限时广告·加点！", "【限时】广告加点", "限时广告加点"}) {
            ReadingTaskLocator.Box title = new ReadingTaskLocator.Box(variant, 50, 1600, 280, 1650);
            ReadingTaskLocator.Row row = ReadingTaskLocator.find(Arrays.asList(title, action), 1000);
            assertNotNull(variant, row);
            assertEquals("限时广告加点", row.title);
            assertFalse(row.goRead);
        }
        // 范围外的“限时福利”区块标题不参与匹配。
        ReadingTaskLocator.Box welfare = new ReadingTaskLocator.Box("限时福利", 50, 1600, 280, 1650);
        assertNull(ReadingTaskLocator.find(Arrays.asList(welfare, action), 1000));
    }
    @Test public void supportsReadingUnlockButtonOnLimitedTimeRow() {
        // 真机日志：首个任务完成后，限时行按钮变为“阅读1分钟”（解锁下一个广告任务）。
        ReadingTaskLocator.Box title = new ReadingTaskLocator.Box("【限时】广告·加点!", 50, 2359, 280, 2386);
        ReadingTaskLocator.Box reward = new ReadingTaskLocator.Box("10点章节卡", 400, 2359, 520, 2386);
        ReadingTaskLocator.Box read = new ReadingTaskLocator.Box("阅读1分钟", 800, 2359, 950, 2386);
        ReadingTaskLocator.Row row = ReadingTaskLocator.find(Arrays.asList(title, reward, read), 1200);
        assertNotNull(row);
        assertTrue(row.goRead);
        assertEquals("阅读1分钟", row.button.text);
        ReadingTaskLocator.Box goRead = new ReadingTaskLocator.Box("去阅读", 800, 2359, 950, 2386);
        assertTrue(ReadingTaskLocator.find(Arrays.asList(title, goRead), 1200).goRead);
        // 其他行的“阅读1分钟”按钮不参与匹配（激励任务只认去完成）。
        ReadingTaskLocator.Box incentive = new ReadingTaskLocator.Box("激励任务", 50, 2497, 180, 2544);
        assertNull(ReadingTaskLocator.find(Arrays.asList(incentive, read), 1200));
        // 去完成优先且不误判为解锁。
        ReadingTaskLocator.Box done = new ReadingTaskLocator.Box("去完成", 800, 2359, 950, 2386);
        assertFalse(ReadingTaskLocator.find(Arrays.asList(title, done), 1200).goRead);
    }
    @Test public void limitedTimeRowHasHighestPriorityPerUserOrder() {
        // 用户指定：限时任务最优先，即使激励任务同时可用也先做限时。
        ReadingTaskLocator.Box incentive = new ReadingTaskLocator.Box("激励任务", 50, 500, 180, 550);
        ReadingTaskLocator.Box incentiveAction = new ReadingTaskLocator.Box("去完成", 800, 505, 950, 555);
        ReadingTaskLocator.Box limited = new ReadingTaskLocator.Box("【限时】广告·加点！", 50, 1600, 280, 1650);
        ReadingTaskLocator.Box limitedAction = new ReadingTaskLocator.Box("去完成", 800, 1605, 950, 1655);
        ReadingTaskLocator.Row row = ReadingTaskLocator.find(Arrays.asList(incentive, incentiveAction, limited, limitedAction), 1000);
        assertEquals("限时广告加点", row.title);
    }
    @Test public void prefersHighestPriorityRowWhenSeveralAvailable() {
        ReadingTaskLocator.Box incentive = new ReadingTaskLocator.Box("激励任务", 50, 500, 180, 550);
        ReadingTaskLocator.Box incentiveAction = new ReadingTaskLocator.Box("去完成", 800, 505, 950, 555);
        ReadingTaskLocator.Box milestone = new ReadingTaskLocator.Box("完成3个广告任务得奖励", 50, 900, 300, 950);
        ReadingTaskLocator.Box milestoneAction = new ReadingTaskLocator.Box("去完成", 800, 905, 950, 955);
        ReadingTaskLocator.Row row = ReadingTaskLocator.find(Arrays.asList(incentive, incentiveAction, milestone, milestoneAction), 1000);
        assertEquals("激励任务", row.title);
    }
    @Test public void refusesAmbiguousOrUnrelatedButtons() {
        ReadingTaskLocator.Box title = new ReadingTaskLocator.Box("激励任务", 50, 800, 180, 850);
        ReadingTaskLocator.Box a = new ReadingTaskLocator.Box("去完成", 600, 805, 700, 855);
        ReadingTaskLocator.Box b = new ReadingTaskLocator.Box("去完成", 800, 805, 900, 855);
        assertNull(ReadingTaskLocator.find(Arrays.asList(title, a, b), 1000));
        assertNull(ReadingTaskLocator.find(Arrays.asList(title, new ReadingTaskLocator.Box("去购买", 800, 805, 900, 855)), 1000));
    }
    @Test public void firstBookPicksTopmostContentTextExcludingHeadersAndTabs() {
        // 真机书架截图 (1200x2670)：每日导读卡片 → 书架页头（可能被 OCR 合并为“书架浏览记录”）→ 书籍列表。
        ReadingTaskLocator.Box dailyBook = new ReadingTaskLocator.Box("拯救曼联", 64, 353, 170, 381);
        ReadingTaskLocator.Box dailyTag = new ReadingTaskLocator.Box("每日导读", 42, 461, 176, 501);
        ReadingTaskLocator.Box mergedHeader = new ReadingTaskLocator.Box("书架浏览记录", 40, 587, 330, 641);
        ReadingTaskLocator.Box book1 = new ReadingTaskLocator.Box("天人图谱", 273, 701, 480, 754);
        ReadingTaskLocator.Box book1Meta = new ReadingTaskLocator.Box("误道者·169章未读", 273, 786, 530, 825);
        ReadingTaskLocator.Box book2 = new ReadingTaskLocator.Box("重走警途1993", 273, 988, 570, 1038);
        ReadingTaskLocator.Box tab = new ReadingTaskLocator.Box("发现", 700, 2466, 760, 2520);
        ReadingTaskLocator.Box found = ReadingTaskLocator.firstBook(Arrays.asList(
                dailyBook, dailyTag, mergedHeader, book1, book1Meta, book2, tab), 2670);
        assertSame(book1, found);
        // 合并页头不存在（OCR 分字识别）时同样锚定成功。
        ReadingTaskLocator.Box splitHeader = new ReadingTaskLocator.Box("书架", 40, 598, 160, 638);
        assertSame(book1, ReadingTaskLocator.firstBook(Arrays.asList(
                dailyBook, dailyTag, splitHeader, book1, book1Meta, book2, tab), 2670));
        // 无页头锚点且全是页头/卡片/标签时返回 null。
        assertNull(ReadingTaskLocator.firstBook(Arrays.asList(dailyBook, dailyTag, tab), 2670));
    }
    @Test public void recognizesWelfarePageWithoutTitleBar() {
        // 真机：福利中心滚到顶部时标题栏没有“福利中心”，只有“做任务领惊喜福利”等文案；
        // 只认“福利中心”会漏判，导致找不到任务时也不下滑。
        assertTrue(ReadingTaskLocator.isWelfareCenter(Arrays.asList(
                new ReadingTaskLocator.Box("做任务领惊喜福利", 50, 1900, 300, 1950),
                new ReadingTaskLocator.Box("本周收益", 400, 500, 600, 550))));
        assertTrue(ReadingTaskLocator.isWelfareCenter(Collections.singletonList(
                new ReadingTaskLocator.Box("福利中心", 300, 100, 500, 160))));
        assertFalse(ReadingTaskLocator.isWelfareCenter(Arrays.asList(
                new ReadingTaskLocator.Box("书架", 40, 600, 160, 640),
                new ReadingTaskLocator.Box("我的", 900, 2400, 960, 2450))));
    }
    @Test public void ctripRowToleratesOcrVariants() {
        ReadingTaskLocator.Box action = new ReadingTaskLocator.Box("去完成", 800, 1505, 950, 1555);
        for (String variant : new String[]{"去携程签到领积分", "携程签到领积分", "去携程签到"}) {
            ReadingTaskLocator.Box title = new ReadingTaskLocator.Box(variant, 50, 1500, 300, 1550);
            ReadingTaskLocator.Row row = ReadingTaskLocator.find(Arrays.asList(title, action), 1000);
            assertNotNull(variant, row);
            assertTrue(variant, row.signin);
        }
        // 真机 ML Kit 把该行按钮读成“领奖励”：按标题右侧几何定位，不依赖按钮文案。
        ReadingTaskLocator.Box title = new ReadingTaskLocator.Box("去携程签到领积分", 81, 1383, 465, 1429);
        ReadingTaskLocator.Box misread = new ReadingTaskLocator.Box("领奖励", 947, 1418, 1057, 1456);
        ReadingTaskLocator.Box coin = new ReadingTaskLocator.Box("+1点币(。", 81, 1456, 256, 1496);
        ReadingTaskLocator.Box above = new ReadingTaskLocator.Box("去完成", 947, 1225, 1061, 1252);
        ReadingTaskLocator.Row signin = ReadingTaskLocator.find(
                Arrays.asList(title, coin, above, misread), 1200);
        assertNotNull(signin);
        assertSame(misread, signin.button);
        assertTrue(signin.signin);
        // 同一行右侧出现两个短标签时不点（避免误点帮助图标/其他控件）。
        assertNull(ReadingTaskLocator.find(Arrays.asList(title,
                new ReadingTaskLocator.Box("去完成", 800, 1385, 900, 1430),
                new ReadingTaskLocator.Box("领奖励", 947, 1418, 1057, 1456)), 1200));
        // 其他行仍只认“去完成”文案，不套用签到行的几何兜底。
        ReadingTaskLocator.Box incentive = new ReadingTaskLocator.Box("激励任务", 50, 1383, 260, 1429);
        assertNull(ReadingTaskLocator.find(Arrays.asList(incentive,
                new ReadingTaskLocator.Box("领奖励", 947, 1418, 1057, 1456)), 1200));
    }
    @Test public void claimedCtripRowIsSkippedAndReported() {
        ReadingTaskLocator.Box title = new ReadingTaskLocator.Box("去携程签到领积分", 81, 1383, 465, 1429);
        ReadingTaskLocator.Box claimed = new ReadingTaskLocator.Box("已领取", 947, 1418, 1057, 1456);
        // 已领取：不再点击（几何定位会接受任意短标签，必须排除“已…”），并报告已领取用于收尾。
        assertNull(ReadingTaskLocator.find(Arrays.asList(title, claimed), 1200));
        assertTrue(ReadingTaskLocator.claimedSignin(Arrays.asList(title, claimed), 1200));
        // “领奖励”是可点的兑换态，不算已领取。
        ReadingTaskLocator.Box claimable = new ReadingTaskLocator.Box("领奖励", 947, 1418, 1057, 1456);
        assertNotNull(ReadingTaskLocator.find(Arrays.asList(title, claimable), 1200));
        assertFalse(ReadingTaskLocator.claimedSignin(Arrays.asList(title, claimable), 1200));
        // 其他行的“已领取”不触发收尾。
        assertFalse(ReadingTaskLocator.claimedSignin(Arrays.asList(
                new ReadingTaskLocator.Box("激励任务", 50, 1383, 260, 1429), claimed), 1200));
        // 同一屏远处的“已领取”（不同任务行）不算。
        assertFalse(ReadingTaskLocator.claimedSignin(Arrays.asList(title,
                new ReadingTaskLocator.Box("已领取", 947, 1718, 1057, 1756)), 1200));
    }
    @Test public void describeListsRecognizedRowTitlesButtonsAndBottomZone() {
        String summary = ReadingTaskLocator.describe(Arrays.asList(
                new ReadingTaskLocator.Box("激励任务", 50, 500, 180, 550),
                new ReadingTaskLocator.Box("已领取", 800, 505, 950, 555),
                new ReadingTaskLocator.Box("完成3个广告任务得奖励", 50, 900, 300, 950),
                new ReadingTaskLocator.Box("去完成", 800, 905, 950, 955),
                new ReadingTaskLocator.Box("我的", 900, 1800, 960, 1840)), 2000);
        assertTrue(summary.contains("激励任务"));
        assertTrue(summary.contains("完成3个广告任务得奖励"));
        assertTrue(summary.contains("已领取@505"));
        assertTrue(summary.contains("去完成@905"));
        assertTrue(summary.contains("bottom=[我的@900,1800-960,1840]"));
    }
}
