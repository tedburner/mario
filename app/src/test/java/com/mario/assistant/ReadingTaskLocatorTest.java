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
        // 激励任务已领取（无去完成按钮）→ 回退到“做任务领惊喜福利”。
        ReadingTaskLocator.Box claimed = new ReadingTaskLocator.Box("已领取", 800, 500, 950, 550);
        ReadingTaskLocator.Box hourlyTitle = new ReadingTaskLocator.Box("做任务领惊喜福利", 50, 900, 260, 950);
        ReadingTaskLocator.Box hourlyAction = new ReadingTaskLocator.Box("去完成", 800, 905, 950, 955);
        ReadingTaskLocator.Row row = ReadingTaskLocator.find(Arrays.asList(claimed, hourlyTitle, hourlyAction), 1000);
        assertNotNull(row);
        assertEquals("做任务领惊喜福利", row.title);
        assertEquals(hourlyAction, row.button);
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
    @Test public void prefersHighestPriorityRowWhenSeveralAvailable() {
        ReadingTaskLocator.Box incentive = new ReadingTaskLocator.Box("激励任务", 50, 500, 180, 550);
        ReadingTaskLocator.Box incentiveAction = new ReadingTaskLocator.Box("去完成", 800, 505, 950, 555);
        ReadingTaskLocator.Box hourly = new ReadingTaskLocator.Box("做任务领惊喜福利", 50, 900, 260, 950);
        ReadingTaskLocator.Box hourlyAction = new ReadingTaskLocator.Box("去完成", 800, 905, 950, 955);
        ReadingTaskLocator.Row row = ReadingTaskLocator.find(Arrays.asList(incentive, incentiveAction, hourly, hourlyAction), 1000);
        assertEquals("激励任务", row.title);
    }
    @Test public void refusesAmbiguousOrUnrelatedButtons() {
        ReadingTaskLocator.Box title = new ReadingTaskLocator.Box("激励任务", 50, 800, 180, 850);
        ReadingTaskLocator.Box a = new ReadingTaskLocator.Box("去完成", 600, 805, 700, 855);
        ReadingTaskLocator.Box b = new ReadingTaskLocator.Box("去完成", 800, 805, 900, 855);
        assertNull(ReadingTaskLocator.find(Arrays.asList(title, a, b), 1000));
        assertNull(ReadingTaskLocator.find(Arrays.asList(title, new ReadingTaskLocator.Box("去购买", 800, 805, 900, 855)), 1000));
    }
    @Test public void describeListsRecognizedRowTitlesAndButtons() {
        String summary = ReadingTaskLocator.describe(Arrays.asList(
                new ReadingTaskLocator.Box("激励任务", 50, 500, 180, 550),
                new ReadingTaskLocator.Box("已领取", 800, 505, 950, 555),
                new ReadingTaskLocator.Box("做任务领惊喜福利", 50, 900, 260, 950),
                new ReadingTaskLocator.Box("去完成", 800, 905, 950, 955)));
        assertTrue(summary.contains("激励任务"));
        assertTrue(summary.contains("做任务领惊喜福利"));
        assertTrue(summary.contains("已领取@505"));
        assertTrue(summary.contains("去完成@905"));
    }
}
