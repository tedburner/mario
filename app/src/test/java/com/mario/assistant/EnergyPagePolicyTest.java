package com.mario.assistant;

import com.mario.assistant.domain.forest.EnergyPagePolicy;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

/** 收能量页规则：一键收识别、气泡边界、好友循环收敛。 */
public class EnergyPagePolicyTest {
    private static final int W = 1200;
    private static final int H = 2670;

    @Test public void recognizesCollectAllButton() {
        assertTrue(EnergyPagePolicy.isCollectAll("一键收"));
        assertTrue(EnergyPagePolicy.isCollectAll("一键收能量"));
        assertTrue(EnergyPagePolicy.isCollectAll(" 一键收 "));
        assertFalse(EnergyPagePolicy.isCollectAll("用道具"));
        assertFalse(EnergyPagePolicy.isCollectAll(null));
    }

    @Test public void bubbleMustBePlainNumberInGrams() {
        assertTrue(EnergyPagePolicy.isEnergyBubble("25g"));
        assertTrue(EnergyPagePolicy.isEnergyBubble("5g"));
        assertTrue(EnergyPagePolicy.isEnergyBubble("1.5kg"));
        // 真机上「立得5g」「36g 10天」是标签不是气泡，点它们会跑到别的页面
        assertFalse(EnergyPagePolicy.isEnergyBubble("立得5g"));
        assertFalse(EnergyPagePolicy.isEnergyBubble("36g 10天"));
        assertFalse(EnergyPagePolicy.isEnergyBubble("绿色能量"));
    }

    @Test public void bubbleBandExcludesHeaderAndPlazaCards() {
        // 真机坐标：树冠附近的成熟气泡（1200x2670 下 y≈600~900）
        assertTrue(EnergyPagePolicy.isBubbleInTreeBand(480, 700, W, H));
        // 右上角总能量 252716g
        assertFalse(EnergyPagePolicy.isBubbleInTreeBand(1040, 300, W, H));
        // 右下 PK 进度 540g（真机 2026-11-10 误点后跳到了 PK 页）
        assertFalse(EnergyPagePolicy.isBubbleInTreeBand(1060, 1080, W, H));
        // 左上角「0g」绿色能量计数
        assertFalse(EnergyPagePolicy.isBubbleInTreeBand(30, 1250, W, H));
        // 底部森林广场卡片 260g
        assertFalse(EnergyPagePolicy.isBubbleInTreeBand(360, 2440, W, H));
    }

    @Test public void friendPageAnchorIsScrollHint() {
        assertTrue(EnergyPagePolicy.isFriendPage(Collections.singletonList("上划找更多能量")));
        assertFalse(EnergyPagePolicy.isFriendPage(Arrays.asList("找能量", "森林广场")));
        assertFalse(EnergyPagePolicy.isFriendPage(null));
    }

    @Test public void forestHomeAnchorDiffersFromFriendPage() {
        assertTrue(EnergyPagePolicy.isForestHome(Collections.singletonList("找能量")));
        assertFalse(EnergyPagePolicy.isForestHome(Collections.singletonList("上划找更多能量")));
    }

    @Test public void stopsAfterThreeEmptyFriends() {
        assertFalse(EnergyPagePolicy.shouldStop(1));
        assertFalse(EnergyPagePolicy.shouldStop(2));
        assertTrue(EnergyPagePolicy.shouldStop(3));
    }

    @Test public void friendBudgetCapsRunawaySwiping() {
        assertFalse(EnergyPagePolicy.reachedFriendBudget(EnergyPagePolicy.MAX_FRIENDS - 1));
        assertTrue(EnergyPagePolicy.reachedFriendBudget(EnergyPagePolicy.MAX_FRIENDS));
    }

    @Test public void sameFriendMayBeCollectedTwiceOnly() {
        assertTrue(EnergyPagePolicy.canCollectAgain(0));
        assertTrue(EnergyPagePolicy.canCollectAgain(1));
        assertFalse(EnergyPagePolicy.canCollectAgain(2));
        assertEquals(2, EnergyPagePolicy.MAX_COLLECT_PER_FRIEND);
    }

    /** 真机 2026-11-10 15:07：奖励页顶着「找能量共获得 627g」，被当成主页后点了 627g。 */
    @Test public void rewardSheetIsNotMistakenForForestHome() {
        java.util.List<String> reward = Arrays.asList(
                "找能量共获得", "627g", "今日共获得1163g", "任务进度: 12/12", "立即领取");
        assertTrue(EnergyPagePolicy.isRewardSheet(reward));
        assertFalse(EnergyPagePolicy.isForestHome(reward));
        assertTrue(EnergyPagePolicy.isForestHome(Arrays.asList("找能量", "森林广场")));
    }

    /** 真机 2026-11-10 15:03：点「找能量」后被奖励弹窗盖住，被误判成离开好友页。 */
    @Test public void rewardSheetIsDetectedBeforeGivingUp() {
        assertTrue(EnergyPagePolicy.isRewardSheet(Arrays.asList(
                "奖励", "今日继续赚能量 获胜得奖励", "去守护", "去下单", "去完成")));
        assertFalse(EnergyPagePolicy.isRewardSheet(Arrays.asList(
                "上划找更多能量", "一键收")));
        assertFalse(EnergyPagePolicy.isRewardSheet(Collections.singletonList("奖励")));
        assertFalse(EnergyPagePolicy.isRewardSheet(null));
    }
}
