package com.mario.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdVisitTimerTest {
    @Test public void requiresObservedDetailAndFullWait() {
        AdVisitTimer timer = new AdVisitTimer();
        assertFalse(timer.readyToReturn(50000));
        timer.detailOpened(1000);
        assertFalse(timer.readyToReturn(15999));
        assertTrue(timer.readyToReturn(16000));
    }
    @Test public void cancellationPreventsReturn() {
        AdVisitTimer timer = new AdVisitTimer();
        timer.detailOpened(1000);
        timer.cancel();
        assertFalse(timer.readyToReturn(30000));
    }
}
