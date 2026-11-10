package com.mario.assistant;

import com.mario.assistant.domain.forest.ForestTaskProgress;

import org.junit.Test;
import static org.junit.Assert.*;

/** 任务台账：完成/未确认判定与领取重试预算。 */
public class ForestTaskProgressTest {

    @Test public void doneTaskIsSettled() {
        ForestTaskProgress progress = new ForestTaskProgress();
        progress.markDone("逛支付宝会员中心");
        assertTrue(progress.isDone("逛支付宝会员中心"));
        assertTrue(progress.isSettled("逛支付宝会员中心"));
        assertEquals(1, progress.doneCount());
    }

    @Test public void stuckTaskIsSettledButNotDone() {
        ForestTaskProgress progress = new ForestTaskProgress();
        progress.markStuck("玩一玩森林乐消消");
        assertTrue(progress.isStuck("玩一玩森林乐消消"));
        assertTrue(progress.isSettled("玩一玩森林乐消消"));
        assertFalse(progress.isDone("玩一玩森林乐消消"));
        assertEquals(0, progress.doneCount());
        assertEquals(1, progress.stuckCount());
    }

    @Test public void unknownTaskIsNotSettled() {
        ForestTaskProgress progress = new ForestTaskProgress();
        assertFalse(progress.isSettled("与兵马俑共守青绿"));
        assertFalse(progress.isSettled(null));
    }

    @Test public void laterDoneOverridesEarlierStuck() {
        ForestTaskProgress progress = new ForestTaskProgress();
        progress.markStuck("逛惊喜市集领红包");
        progress.markDone("逛惊喜市集领红包");
        assertTrue(progress.isDone("逛惊喜市集领红包"));
        assertFalse(progress.isStuck("逛惊喜市集领红包"));
        assertEquals(1, progress.doneCount());
        assertEquals(0, progress.stuckCount());
    }

    @Test public void laterStuckDropsEarlierDone() {
        ForestTaskProgress progress = new ForestTaskProgress();
        progress.markDone("登录灵光识别得能量");
        progress.markStuck("登录灵光识别得能量");
        assertFalse(progress.isDone("登录灵光识别得能量"));
        assertTrue(progress.isStuck("登录灵光识别得能量"));
    }

    @Test public void claimRetryStopsAtBudget() {
        ForestTaskProgress progress = new ForestTaskProgress();
        assertTrue(progress.canRetryClaim("逛一逛芝麻攒粒攻略"));
        assertEquals(1, progress.recordClaimFailure("逛一逛芝麻攒粒攻略"));
        assertTrue(progress.canRetryClaim("逛一逛芝麻攒粒攻略"));
        assertEquals(2, progress.recordClaimFailure("逛一逛芝麻攒粒攻略"));
        assertFalse(progress.canRetryClaim("逛一逛芝麻攒粒攻略"));
        assertEquals(ForestTaskProgress.MAX_CLAIM_FAILURES, 2);
    }

    @Test public void claimFailureIsTrackedPerTitle() {
        ForestTaskProgress progress = new ForestTaskProgress();
        progress.recordClaimFailure("A");
        assertTrue(progress.canRetryClaim("B"));
        assertEquals(0, progress.claimFailures("B"));
    }

    @Test public void summaryWithoutStuckOnlyCountsDone() {
        ForestTaskProgress progress = new ForestTaskProgress();
        progress.markDone("A");
        progress.markDone("B");
        assertEquals("完成 2 项", progress.summary());
    }

    @Test public void summaryListsUnconfirmedTitles() {
        ForestTaskProgress progress = new ForestTaskProgress();
        progress.markDone("A");
        progress.markStuck("玩一玩森林乐消消");
        String summary = progress.summary();
        assertTrue(summary.startsWith("完成 1 项；未确认 1 项："));
        assertTrue(summary.contains("玩一玩森林乐消消"));
    }

    @Test public void summaryTruncatesLongStuckList() {
        ForestTaskProgress progress = new ForestTaskProgress();
        for (String title : new String[]{"A", "B", "C", "D", "E"}) progress.markStuck(title);
        String summary = progress.summary();
        assertTrue(summary.contains("未确认 5 项"));
        assertTrue(summary.contains("等 5 项"));
        assertFalse(summary.contains("E"));
    }

    @Test public void clearedStuckTaskBecomesActionableAgain() {
        ForestTaskProgress progress = new ForestTaskProgress();
        progress.markStuck("玩一玩森林乐消消");
        assertTrue(progress.isSettled("玩一玩森林乐消消"));
        progress.clearStuck("玩一玩森林乐消消");
        assertFalse(progress.isStuck("玩一玩森林乐消消"));
        assertFalse(progress.isSettled("玩一玩森林乐消消"));
        assertEquals(0, progress.stuckCount());
    }

    /**
     * 真机 2026-11-10：浏览完行会先消失（记已完成），过一会儿又带着「立即领取」回来。
     * 不清掉 done 就再也不会去领。
     */
    @Test public void clearedSettledTaskCanBeClaimedAgain() {
        ForestTaskProgress progress = new ForestTaskProgress();
        progress.markDone("逛森林集市得能量");
        assertTrue(progress.isSettled("逛森林集市得能量"));
        progress.clearSettled("逛森林集市得能量");
        assertFalse(progress.isSettled("逛森林集市得能量"));
        assertEquals(0, progress.doneCount());
    }

    @Test public void blankTitleIsIgnored() {
        ForestTaskProgress progress = new ForestTaskProgress();
        progress.markDone(null);
        progress.markDone("  ");
        progress.markStuck("");
        assertEquals(0, progress.doneCount());
        assertEquals(0, progress.stuckCount());
        assertEquals(0, progress.recordClaimFailure(null));
    }
}
