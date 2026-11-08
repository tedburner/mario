package com.mario.assistant;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskRowMatcherTest {
    @Test public void findsOnlyMatchingVisibleTaskRow() {
        assertEquals(1, TaskRowMatcher.find(Arrays.asList(
            new TaskRowMatcher.Row("来淘金币狂享20亿", "逛一逛", true),
            new TaskRowMatcher.Row("逛支付宝会员中心", "逛一逛", true))));
    }
    @Test public void refusesInvisibleOrUnexpectedTasks() {
        assertEquals(-1, TaskRowMatcher.find(Collections.singletonList(
            new TaskRowMatcher.Row("逛支付宝会员中心", "逛一逛", false))));
        assertEquals(-1, TaskRowMatcher.find(Collections.singletonList(
            new TaskRowMatcher.Row("购买会员", "逛一逛", true))));
    }
    @Test public void findsClaimOnlyOnTheVerifiedTask() {
        assertEquals(1, TaskRowMatcher.findClaim(Arrays.asList(
            new TaskRowMatcher.Row("其他任务", "立即领取", true),
            new TaskRowMatcher.Row("逛支付宝会员中心", "立即领取", true))));
        assertEquals(-1, TaskRowMatcher.findClaim(Collections.singletonList(
            new TaskRowMatcher.Row("逛支付宝会员中心", "逛一逛", true))));
    }
    @Test public void findsCompletedSesameGuideClaim() {
        assertEquals(0, TaskRowMatcher.findClaim(Collections.singletonList(
            new TaskRowMatcher.Row("逛一逛芝麻攒粒攻略", "立即领取", true))));
    }
    @Test public void refusesAmbiguousDuplicateRows() {
        TaskRowMatcher.Row row = new TaskRowMatcher.Row("逛支付宝会员中心", "逛一逛", true);
        assertEquals(-1, TaskRowMatcher.find(Arrays.asList(row, row)));
    }
}
