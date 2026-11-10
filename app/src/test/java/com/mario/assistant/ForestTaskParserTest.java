package com.mario.assistant;

import com.mario.assistant.domain.forest.ForestTask;
import com.mario.assistant.domain.forest.ForestTaskParser;
import com.mario.assistant.domain.forest.ForestTaskPolicy;
import com.mario.assistant.domain.model.UiNode;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ForestTaskParserTest {
    private static final ForestTaskPolicy.Installed INSTALLED = pkg ->
            ForestTaskPolicy.ALIPAY.equals(pkg)
                    || "com.sankuai.meituan".equals(pkg)
                    || "com.taobao.taobao".equals(pkg);

    private static UiNode n(int id, String text, int l, int t, int r, int b) {
        return new UiNode(id, text, l, t, r, b, true, true);
    }

    private static List<UiNode> rewardPage() {
        List<UiNode> nodes = new ArrayList<>();
        // 行 1：逛支付宝会员中心（真机 bounds）
        nodes.add(n(0, "逛支付宝会员中心", 71, 1215, 1131, 1446));
        nodes.add(n(1, "逛支付宝会员中心", 100, 1248, 260, 1407));
        nodes.add(n(2, "逛支付宝会员中心", 282, 1287, 799, 1368));
        nodes.add(n(3, "逛一逛", 838, 1287, 1101, 1368));
        nodes.add(n(4, "+30g", 916, 1270, 1108, 1313));
        // 行 2：来淘金币狂享20亿
        nodes.add(n(5, "来淘金币狂享20亿", 71, 1446, 1131, 1677));
        nodes.add(n(6, "来淘金币狂享20亿", 100, 1478, 260, 1638));
        nodes.add(n(7, "来淘金币狂享20亿", 282, 1517, 799, 1599));
        nodes.add(n(8, "逛一逛", 838, 1517, 1101, 1599));
        nodes.add(n(9, "抽1次", 916, 1501, 1108, 1543));
        // 行 3：去书旗看书追剧（本机未安装）
        nodes.add(n(10, "去书旗看书追剧", 71, 1677, 1131, 1907));
        nodes.add(n(11, "去书旗看书追剧", 100, 1709, 260, 1868));
        nodes.add(n(12, "去书旗看书追剧", 282, 1748, 799, 1829));
        nodes.add(n(13, "逛一逛", 838, 1748, 1101, 1829));
        nodes.add(n(14, "+10g", 916, 1732, 1108, 1774));
        // 行 4：玩一玩森林乐消消
        nodes.add(n(15, "玩一玩森林乐消消", 71, 1907, 1131, 2138));
        nodes.add(n(16, "玩一玩森林乐消消", 100, 1940, 260, 2099));
        nodes.add(n(17, "玩一玩森林乐消消", 282, 1979, 799, 2060));
        nodes.add(n(18, "去看看", 838, 1979, 1101, 2060));
        nodes.add(n(19, "抽1次", 916, 1963, 1108, 2005));
        // 行 5：抽奖页式带说明的任务行
        nodes.add(n(20, "去森林市集逛一逛", 269, 2500, 884, 2574));
        nodes.add(n(21, "浏览市集15s，可得1次机会", 269, 2578, 708, 2630));
        nodes.add(n(22, "去逛逛", 923, 2526, 1118, 2604));
        // 屏外行：零 bounds，应被忽略
        nodes.add(new UiNode(23, "连续7天收自己能量", 0, 0, 0, 0, true, false));
        nodes.add(new UiNode(24, "今日完成", 0, 0, 0, 0, true, false));
        return nodes;
    }

    @Test public void parsesVisibleRowsWithoutHardcodedTitles() {
        List<ForestTask> tasks = ForestTaskParser.parse(rewardPage(), 1200, 2620, INSTALLED);
        assertEquals(5, tasks.size());
        assertEquals("逛支付宝会员中心", tasks.get(0).title);
        assertEquals("逛一逛", tasks.get(0).cta);
        assertEquals("+30g", tasks.get(0).reward);
        assertEquals(3, tasks.get(0).ctaId);
        assertEquals(ForestTask.Kind.BROWSE, tasks.get(0).kind);
        assertEquals(ForestTaskPolicy.ALIPAY, tasks.get(0).targetPackage);
    }

    @Test public void classifiesDynamicTaskListByPolicy() {
        List<ForestTask> tasks = ForestTaskParser.parse(rewardPage(), 1200, 2620, INSTALLED);
        assertEquals(ForestTask.Kind.SKIP, tasks.get(2).kind);
        assertTrue(tasks.get(2).reason.contains("未安装"));
        assertEquals(ForestTaskPolicy.ALIPAY, tasks.get(3).targetPackage);
    }

    @Test public void readsBrowseSecondsFromRowDetail() {
        List<ForestTask> tasks = ForestTaskParser.parse(rewardPage(), 1200, 2620, INSTALLED);
        ForestTask market = tasks.get(4);
        assertEquals("去森林市集逛一逛", market.title);
        assertEquals(15, market.browseSeconds());
    }

    @Test public void ignoresZeroBoundsRows() {
        List<ForestTask> tasks = ForestTaskParser.parse(rewardPage(), 1200, 2620, INSTALLED);
        for (ForestTask task : tasks) {
            assertNotEquals("连续7天收自己能量", task.title);
        }
    }

    @Test public void ignoresRowsClippedByScrollViewport() {
        List<UiNode> nodes = new ArrayList<>();
        // 奖励页向上滚动后，顶部横幅后面的行坐标仍正常，但已被滚动容器裁掉
        nodes.add(new UiNode(0, "逛惊喜市集领红包", 282, 16, 799, 100, true, true, 445, 2620));
        nodes.add(new UiNode(1, "逛一逛", 838, 16, 1101, 100, true, true, 445, 2620));
        // 可见行
        nodes.add(new UiNode(2, "逛淘宝直播抢0元付定", 282, 711, 799, 793, true, true, 445, 2620));
        nodes.add(new UiNode(3, "逛一逛", 838, 711, 1101, 789, true, true, 445, 2620));
        List<ForestTask> tasks = ForestTaskParser.parse(nodes, 1200, 2670, INSTALLED);
        assertEquals(1, tasks.size());
        assertEquals("逛淘宝直播抢0元付定", tasks.get(0).title);
    }

    @Test public void rewardAndDuplicateTitleAreNotInDetail() {
        List<ForestTask> tasks = ForestTaskParser.parse(rewardPage(), 1200, 2620, INSTALLED);
        assertFalse(tasks.get(0).detail.contains("+30g"));
        assertFalse(tasks.get(0).detail.contains("逛支付宝会员中心"));
    }

    @Test public void parsesEnergyCollectActionableRow() {
        List<UiNode> nodes = new ArrayList<>();
        nodes.add(new UiNode(0, "去收一次好友能量", 282, 711, 799, 793, true, true));
        nodes.add(new UiNode(1, "去收取", 838, 711, 1101, 789, true, true));
        nodes.add(new UiNode(2, "+10g", 916, 695, 1108, 740, true, true));
        List<ForestTask> tasks = ForestTaskParser.parse(nodes, 1200, 2670, INSTALLED);
        assertEquals(1, tasks.size());
        assertEquals("去收一次好友能量", tasks.get(0).title);
        assertEquals(ForestTask.Kind.BROWSE, tasks.get(0).kind);
        assertEquals(ForestTaskPolicy.ALIPAY, tasks.get(0).targetPackage);
    }

    /** 真机 2026-11-10：奖励页顶部还有里程碑和「活力值去兑换」，它们不能当任务行点。 */
    @Test public void ignoresNodesAboveTaskProgressHeader() {
        List<UiNode> nodes = new ArrayList<>();
        nodes.add(n(0, "任务进度:", 71, 930, 250, 990));
        nodes.add(n(1, "12", 250, 930, 300, 990));
        nodes.add(n(2, "+40g立即领取", 440, 880, 620, 940));
        nodes.add(n(3, "我的活力值", 71, 460, 370, 520));
        nodes.add(n(4, "去兑换", 71, 560, 200, 620));
        nodes.add(n(5, "去守护古树得奖励", 282, 1979, 799, 2060));
        nodes.add(n(6, "去守护", 838, 1979, 1101, 2060));
        nodes.add(n(7, "+10g", 916, 1963, 1108, 2005));
        List<ForestTask> tasks = ForestTaskParser.parse(nodes, 1200, 2670, INSTALLED);
        assertEquals(1, tasks.size());
        assertEquals("去守护古树得奖励", tasks.get(0).title);
        assertEquals(ForestTask.Kind.BROWSE, tasks.get(0).kind);
    }

    /** 真机 2026-11-10 16:26：任务做满后「任务进度」变成「明天再来」，头部横幅被当成任务行点了。 */
    @Test public void headerBannerIsNotTaskAfterProgressBecomesTomorrow() {
        List<UiNode> nodes = new ArrayList<>();
        nodes.add(n(0, "今日累计奖励", 71, 900, 400, 1010));
        nodes.add(n(1, "明天再来", 71, 1000, 260, 1040));
        nodes.add(n(2, "我的活力值", 71, 500, 370, 560));
        nodes.add(n(3, "去看看", 1000, 500, 1150, 560));
        nodes.add(n(4, "2人每日得能量翻倍卡", 300, 380, 900, 440));
        nodes.add(n(5, "去看看", 1000, 380, 1150, 440));
        nodes.add(n(6, "登录灵光识别得能量", 282, 1330, 799, 1400));
        nodes.add(n(7, "去完成", 838, 1330, 1101, 1400));
        nodes.add(n(8, "+100g", 916, 1270, 1108, 1313));
        List<ForestTask> tasks = ForestTaskParser.parse(nodes, 1200, 2670, INSTALLED);
        assertEquals(1, tasks.size());
        assertEquals("登录灵光识别得能量", tasks.get(0).title);
    }

    /** 「明天再来」+ 里程碑都在、但「任务进度」已消失时，仍要挡住顶部横幅。 */
    @Test public void milestoneRowBoundsTaskListWhenProgressIsGone() {
        List<UiNode> nodes = new ArrayList<>();
        nodes.add(n(0, "明天再来", 71, 1000, 260, 1040));
        nodes.add(n(1, "+40g已领取", 440, 880, 620, 940));
        nodes.add(n(2, "2人每日得能量翻倍卡", 300, 380, 900, 440));
        nodes.add(n(3, "去看看", 1000, 380, 1150, 440));
        nodes.add(n(4, "去组队种树得森林周边", 282, 1330, 799, 1400));
        nodes.add(n(5, "立即领取", 838, 1330, 1101, 1400));
        nodes.add(n(6, "+10g", 916, 1270, 1108, 1313));
        List<ForestTask> tasks = ForestTaskParser.parse(nodes, 1200, 2670, INSTALLED);
        assertEquals(1, tasks.size());
        assertEquals("去组队种树得森林周边", tasks.get(0).title);
    }

    /** 真机 2026-11-10 16:32 滚动后行错位，抓到了「g」「--」这种碎片当标题。 */
    @Test public void ignoresFragmentTitlesFromScrollMisalignment() {
        List<UiNode> nodes = new ArrayList<>();
        nodes.add(n(0, "g", 282, 1500, 300, 1560));
        nodes.add(n(1, "去看看", 838, 1500, 1101, 1560));
        nodes.add(n(2, "--", 282, 1700, 320, 1760));
        nodes.add(n(3, "去看看", 838, 1700, 1101, 1760));
        nodes.add(n(4, "逛森林集市得能量", 282, 1900, 799, 1960));
        nodes.add(n(5, "去看看", 838, 1900, 1101, 1960));
        List<ForestTask> tasks = ForestTaskParser.parse(nodes, 1200, 2670, INSTALLED);
        assertEquals(1, tasks.size());
        assertEquals("逛森林集市得能量", tasks.get(0).title);
    }

    /** 真机 2026-11-10：玩一玩森林乐消消 的按钮是「去抽奖」，之前整行解析不出来，所以从来不去抽奖。 */
    @Test public void parsesDrawRowAsDrawKind() {
        List<UiNode> nodes = new ArrayList<>();
        nodes.add(n(0, "任务进度:", 71, 930, 250, 990));
        nodes.add(n(1, "玩一玩森林乐消消", 282, 1748, 799, 1829));
        nodes.add(n(2, "去抽奖", 838, 1748, 1101, 1829));
        nodes.add(n(3, "x1", 916, 1732, 1108, 1774));
        List<ForestTask> tasks = ForestTaskParser.parse(nodes, 1200, 2670, INSTALLED);
        assertEquals(1, tasks.size());
        assertEquals(ForestTask.Kind.DRAW, tasks.get(0).kind);
        assertTrue(tasks.get(0).actionable());
    }
}
