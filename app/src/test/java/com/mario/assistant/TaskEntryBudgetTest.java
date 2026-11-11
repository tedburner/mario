package com.mario.assistant;

import com.mario.assistant.domain.welfare.TaskEntryBudget;

import org.junit.Test;
import static org.junit.Assert.*;

public class TaskEntryBudgetTest {
    @Test public void oneShotTaskStopsAfterBudgetIsExhausted() {
        TaskEntryBudget budget = new TaskEntryBudget();
        for (int i = 0; i < TaskEntryBudget.MAX_ENTRIES; i++) {
            assertTrue("第 " + (i + 1) + " 次进入应放行", budget.tryEnter("做任务领惊喜福利"));
            budget.recordProgress("做任务领惊喜福利");
        }
        assertFalse("超出预算应停止", budget.tryEnter("做任务领惊喜福利"));
        // 被拒绝的尝试不计入实际进入次数，停止文案才不会多报一次。
        assertEquals(TaskEntryBudget.MAX_ENTRIES, budget.entries("做任务领惊喜福利"));
    }

    @Test public void limitedTimeAdTaskAllowsTwentyEntries() {
        // 用户指定：限时广告加点按累计任务处理，上限 20 次（每支广告后还要阅读解锁）。
        TaskEntryBudget budget = new TaskEntryBudget();
        assertTrue(TaskEntryBudget.isCumulative("限时广告加点"));
        assertEquals(TaskEntryBudget.MAX_LIMITED_AD_ENTRIES, TaskEntryBudget.maxEntries("限时广告加点"));
        for (int i = 0; i < TaskEntryBudget.MAX_LIMITED_AD_ENTRIES; i++) {
            assertTrue("第 " + (i + 1) + " 次进入应放行", budget.tryEnter("限时广告加点"));
            budget.recordProgress("限时广告加点");
        }
        assertEquals(20, TaskEntryBudget.MAX_LIMITED_AD_ENTRIES);
        assertFalse("达到 20 次后停止", budget.tryEnter("限时广告加点"));
        assertEquals(TaskEntryBudget.MAX_LIMITED_AD_ENTRIES, budget.entries("限时广告加点"));
    }

    @Test public void cumulativeTaskKeepsGoingThroughMilestones() {
        // 真机：激励任务第 4 次进入被上限 3 掐断，而屏幕上正好 3 个里程碑勾，第 4 次才是合法进展。
        TaskEntryBudget budget = new TaskEntryBudget();
        for (int i = 0; i < TaskEntryBudget.MAX_CUMULATIVE_ENTRIES; i++) {
            assertTrue("第 " + (i + 1) + " 档应放行", budget.tryEnter("激励任务"));
            budget.recordProgress("激励任务");
        }
        assertFalse("里程碑硬上限仍要兜住跑飞", budget.tryEnter("激励任务"));
    }

    @Test public void twoStalledEntriesStopEvenForCumulativeTask() {
        TaskEntryBudget budget = new TaskEntryBudget();
        assertTrue(budget.tryEnter("激励任务"));
        budget.recordStalled("激励任务");
        assertTrue(budget.tryEnter("激励任务"));
        budget.recordStalled("激励任务");
        assertFalse("连续两轮没走到广告页应停止", budget.tryEnter("激励任务"));
        assertEquals(TaskEntryBudget.MAX_STALLED_ENTRIES, budget.stalled("激励任务"));
    }

    @Test public void progressClearsStalledStreak() {
        TaskEntryBudget budget = new TaskEntryBudget();
        assertTrue(budget.tryEnter("激励任务"));
        budget.recordStalled("激励任务");
        assertTrue(budget.tryEnter("激励任务"));
        budget.recordProgress("激励任务"); // 这一轮走到了广告页，空进计数清零
        assertEquals(0, budget.stalled("激励任务"));
        assertTrue(budget.tryEnter("激励任务"));
    }

    @Test public void budgetIsPerTaskTitle() {
        TaskEntryBudget budget = new TaskEntryBudget();
        for (int i = 0; i < TaskEntryBudget.MAX_ENTRIES; i++) budget.tryEnter("做任务领惊喜福利");
        assertFalse(budget.tryEnter("做任务领惊喜福利"));
        // 另一条任务行有自己的预算，不应被前一条拖累。
        assertTrue(budget.tryEnter("去携程签到领积分"));
        assertEquals(0, budget.entries("从未进入过的任务"));
    }

    @Test public void cumulativeTitlesAreRecognised() {
        assertTrue(TaskEntryBudget.isCumulative("激励任务"));
        assertTrue(TaskEntryBudget.isCumulative("完成3个广告任务得奖励"));
        assertFalse(TaskEntryBudget.isCumulative("做任务领惊喜福利"));
        assertFalse(TaskEntryBudget.isCumulative(null));
        assertEquals(TaskEntryBudget.MAX_CUMULATIVE_ENTRIES, TaskEntryBudget.maxEntries("激励任务"));
        assertEquals(TaskEntryBudget.MAX_ENTRIES, TaskEntryBudget.maxEntries("去携程签到领积分"));
    }
}
