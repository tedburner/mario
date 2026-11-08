package com.mario.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExecutionPolicyTest {
    @Test public void allowsVerifiedRewardOnExpectedPackage() {
        assertTrue(ExecutionPolicy.mayClick("com.eg.android.AlipayGphone", "com.eg.android.AlipayGphone", "领取", true, false));
    }
    @Test public void allowsVerifiedImmediateClaim() {
        assertTrue(ExecutionPolicy.mayClick("com.eg.android.AlipayGphone", "com.eg.android.AlipayGphone", "立即领取", true, false));
    }
    @Test public void rejectsWrongPackage() {
        assertFalse(ExecutionPolicy.mayClick("com.example.other", "com.eg.android.AlipayGphone", "领取", true, false));
    }
    @Test public void rejectsSensitiveOrUnverifiedOrCancelled() {
        assertFalse(ExecutionPolicy.mayClick("com.eg.android.AlipayGphone", "com.eg.android.AlipayGphone", "确认支付", true, false));
        assertFalse(ExecutionPolicy.mayClick("com.eg.android.AlipayGphone", "com.eg.android.AlipayGphone", "领取", false, false));
        assertFalse(ExecutionPolicy.mayClick("com.eg.android.AlipayGphone", "com.eg.android.AlipayGphone", "领取", true, true));
    }
}
