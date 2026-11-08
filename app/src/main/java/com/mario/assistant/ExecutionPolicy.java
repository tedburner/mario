package com.mario.assistant;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class ExecutionPolicy {
    private static final Set<String> PACKAGES = new HashSet<>(Arrays.asList(
            "com.eg.android.AlipayGphone", "com.qidian.QDReader"));
    private static final Set<String> ALLOWED_LABELS = new HashSet<>(Arrays.asList("领取", "立即领取"));

    static boolean mayClick(String currentPackage, String expectedPackage, String text,
                            boolean verified, boolean cancelled) {
        return !cancelled && verified && PACKAGES.contains(expectedPackage)
                && expectedPackage.equals(currentPackage) && ALLOWED_LABELS.contains(text);
    }
}
