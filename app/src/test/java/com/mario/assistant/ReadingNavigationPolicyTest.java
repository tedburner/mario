package com.mario.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReadingNavigationPolicyTest {
    @Test public void allowsUniqueEntryOnVerifiedProfilePage() {
        assertTrue(ReadingNavigationPolicy.mayOpen("com.qidian.QDReader", true, true, 1, true));
    }
    @Test public void rejectsOtherPagesAndAmbiguousEntries() {
        assertFalse(ReadingNavigationPolicy.mayOpen("com.example.other", true, true, 1, true));
        assertFalse(ReadingNavigationPolicy.mayOpen("com.qidian.QDReader", false, true, 1, true));
        assertFalse(ReadingNavigationPolicy.mayOpen("com.qidian.QDReader", true, true, 2, true));
        assertFalse(ReadingNavigationPolicy.mayOpen("com.qidian.QDReader", true, true, 1, false));
        assertFalse(ReadingNavigationPolicy.mayTapMyTab(2, true));
        assertFalse(ReadingNavigationPolicy.mayTapMyTab(1, false));
        assertTrue(ReadingNavigationPolicy.mayTapMyTab(1, true));
    }
}
