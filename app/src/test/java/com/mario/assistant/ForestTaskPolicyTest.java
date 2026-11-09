package com.mario.assistant;

import com.mario.assistant.domain.forest.ForestTask;
import com.mario.assistant.domain.forest.ForestTaskPolicy;

import org.junit.Test;
import static org.junit.Assert.*;

public class ForestTaskPolicyTest {
    private static final ForestTaskPolicy.Installed INSTALLED = pkg ->
            ForestTaskPolicy.ALIPAY.equals(pkg)
                    || "com.sankuai.meituan".equals(pkg)
                    || "com.taobao.taobao".equals(pkg);

    @Test public void skipsThirdPartyAppThatIsNotInstalled() {
        ForestTaskPolicy.Decision d = ForestTaskPolicy.decide(
                "去书旗看书追剧", "逛一逛", "", pkg -> false);
        assertEquals(ForestTask.Kind.SKIP, d.kind);
        assertTrue(d.reason.contains("未安装"));
    }

    @Test public void allowsInstalledThirdPartyApp() {
        ForestTaskPolicy.Decision d = ForestTaskPolicy.decide(
                "逛一逛美团视频", "去看看", "", INSTALLED);
        assertEquals(ForestTask.Kind.BROWSE, d.kind);
        assertEquals("com.sankuai.meituan", d.targetPackage);
        assertFalse(d.observeThirdParty);
    }

    @Test public void unknownThirdPartyIsObservedInsteadOfSkipped() {
        ForestTaskPolicy.Decision d = ForestTaskPolicy.decide(
                "逛惊喜市集领红包", "去看看", "", INSTALLED);
        assertEquals(ForestTask.Kind.BROWSE, d.kind);
        assertNull(d.targetPackage);
        assertTrue(d.observeThirdParty);
    }

    @Test public void alipayInternalTaskIsMappedToAlipay() {
        assertEquals(ForestTaskPolicy.ALIPAY, ForestTaskPolicy.decide(
                "逛支付宝会员中心", "逛一逛", "", INSTALLED).targetPackage);
        assertEquals(ForestTaskPolicy.ALIPAY, ForestTaskPolicy.decide(
                "来淘金币狂享20亿", "逛一逛", "", INSTALLED).targetPackage);
        assertEquals(ForestTaskPolicy.ALIPAY, ForestTaskPolicy.decide(
                "玩一玩森林乐消消", "去看看", "", INSTALLED).targetPackage);
    }

    @Test public void claimCtaIsClaimKind() {
        ForestTaskPolicy.Decision d = ForestTaskPolicy.decide(
                "逛农场得落叶肥料", "去领取", "", INSTALLED);
        assertEquals(ForestTask.Kind.CLAIM, d.kind);
    }

    @Test public void doneCtaIsNotActionable() {
        ForestTaskPolicy.Decision d = ForestTaskPolicy.decide(
                "连续7天收自己能量", "今日完成", "", INSTALLED);
        assertEquals(ForestTask.Kind.DONE, d.kind);
        assertFalse(d.kind == ForestTask.Kind.BROWSE || d.kind == ForestTask.Kind.CLAIM);
    }

    @Test public void skipsInsuranceFormAndTransactions() {
        assertEquals(ForestTask.Kind.SKIP, ForestTaskPolicy.decide(
                "给孩子添加1份保障", "去完成", "", INSTALLED).kind);
        assertEquals(ForestTask.Kind.SKIP, ForestTaskPolicy.decide(
                "生活缴费 每笔262g", "", "", INSTALLED).kind);
        assertEquals(ForestTask.Kind.SKIP, ForestTaskPolicy.decide(
                "地铁出行 每笔52g", "", "", INSTALLED).kind);
        assertEquals(ForestTask.Kind.SKIP, ForestTaskPolicy.decide(
                "ETC缴费 每笔23g", "", "", INSTALLED).kind);
        assertEquals(ForestTask.Kind.SKIP, ForestTaskPolicy.decide(
                "网购火车票 每笔136g", "", "", INSTALLED).kind);
    }

    @Test public void skipsResourceSpendAndLevelPassTasks() {
        assertEquals(ForestTask.Kind.SKIP, ForestTaskPolicy.decide(
                "玩灵画师消耗30个包子得2次机会", "马上玩", "", INSTALLED).kind);
        assertEquals(ForestTask.Kind.SKIP, ForestTaskPolicy.decide(
                "通过1关一步两步可得2次机会", "马上玩", "", INSTALLED).kind);
    }

    @Test public void goCompleteIsSkippedByDefault() {
        assertEquals(ForestTask.Kind.SKIP, ForestTaskPolicy.decide(
                "某个未识别任务", "去完成", "", INSTALLED).kind);
    }
}
