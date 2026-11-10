package com.mario.assistant;

import com.mario.assistant.domain.forest.ForestMilestone;
import com.mario.assistant.domain.model.UiNode;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 真机 2026-11-10 奖励页：三个里程碑在无障碍树里是「+40g立即领取」这种把奖励和按钮拼在一起的
 * 单个节点，按「立即领取」精确匹配永远匹配不到，导致任务进度 12/12 了奖励还挂着。
 */
public class ForestMilestoneTest {

    private static UiNode node(String text, int left, int top) {
        return new UiNode(1, text, left, top, left + 180, top + 60, false, true);
    }

    @Test public void recognizesMergedRewardAndClaimText() {
        List<UiNode> nodes = Arrays.asList(
                node("+40g立即领取", 440, 880),
                node("+60g立即领取", 660, 880),
                node("+100g立即领取", 880, 880));
        List<UiNode> pending = ForestMilestone.claimable(nodes);
        assertEquals(3, pending.size());
        assertEquals("+40g立即领取", pending.get(0).text);
        assertEquals("+100g立即领取", pending.get(2).text);
    }

    @Test public void ignoresClaimedAndBareRewardNodes() {
        List<UiNode> nodes = Arrays.asList(
                node("+40g已领取", 440, 880),
                node("+60g", 660, 880),
                node("立即领取", 880, 880),
                node("今日累计奖励", 100, 780));
        assertTrue(ForestMilestone.claimable(nodes).isEmpty());
    }

    @Test public void sortsLeftToRightEvenWhenTreeOrderIsReversed() {
        List<UiNode> nodes = new ArrayList<>(Arrays.asList(
                node("+100g立即领取", 880, 880),
                node("+40g立即领取", 440, 880)));
        assertEquals("+40g立即领取", ForestMilestone.claimable(nodes).get(0).text);
    }

    @Test public void ignoresInvisibleNodes() {
        UiNode hidden = new UiNode(2, "+40g立即领取", 440, 880, 620, 940, false, false);
        assertTrue(ForestMilestone.claimable(Arrays.asList(hidden)).isEmpty());
    }
}
