package com.mario.assistant;

import com.mario.assistant.domain.forest.CtaClassifier;
import org.junit.Test;
import static org.junit.Assert.*;

/** 任务行按钮的动词语义分类：新活动词覆盖与噪音/交易拦截。 */
public class CtaClassifierTest {

    @Test public void classicEnterAndDrawWordsAreClassified() {
        assertEquals(CtaClassifier.Action.ENTER, CtaClassifier.classify("去参与"));
        assertEquals(CtaClassifier.Action.ENTER, CtaClassifier.classify("逛一逛"));
        assertEquals(CtaClassifier.Action.ENTER, CtaClassifier.classify("马上玩"));
        assertEquals(CtaClassifier.Action.DRAW, CtaClassifier.classify("去抽奖"));
        assertEquals(CtaClassifier.Action.CLAIM, CtaClassifier.classify("立即领取"));
    }

    @Test public void doneRejectAndNoiseAreNotActionable() {
        assertEquals(CtaClassifier.Action.DONE, CtaClassifier.classify("已完成"));
        assertEquals(CtaClassifier.Action.DONE, CtaClassifier.classify("今日完成"));
        assertEquals(CtaClassifier.Action.REJECT, CtaClassifier.classify("去下单"));
        assertEquals(CtaClassifier.Action.REJECT, CtaClassifier.classify("去购买"));
        // 森林主页噪音入口不能被当成任务行。
        assertEquals(CtaClassifier.Action.NONE, CtaClassifier.classify("去兑换"));
        assertEquals(CtaClassifier.Action.NONE, CtaClassifier.classify("去浇水"));
        // 「继续看 / 得更多」的行级取舍在 ForestTaskPolicy（只有寻宝行的继续看会进入）。
        assertEquals(CtaClassifier.Action.NONE, CtaClassifier.classify("继续看"));
        assertEquals(CtaClassifier.Action.NONE, CtaClassifier.classify("得更多"));
    }

    @Test public void unknownShortWordsAndLongRowsAreNone() {
        assertEquals(CtaClassifier.Action.NONE, CtaClassifier.classify("冲鸭"));
        // 超过 6 字的是同行标题错位抓取，不是按钮。
        assertEquals(CtaClassifier.Action.NONE, CtaClassifier.classify("完成广告任务多重好礼等你解锁"));
        assertEquals(CtaClassifier.Action.NONE, CtaClassifier.classify(""));
        assertEquals(CtaClassifier.Action.NONE, CtaClassifier.classify(null));
    }
}
