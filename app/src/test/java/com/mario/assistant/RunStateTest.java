package com.mario.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

public class RunStateTest {
    @Test public void onlyOneRunCanStartAndStopAllowsRestart() {
        RunState state = new RunState();
        assertTrue(state.start());
        assertFalse(state.start());
        state.stop();
        assertTrue(state.start());
    }
    @Test public void cancellationIsVisibleUntilNextRun() {
        RunState state = new RunState();
        state.start();
        state.stop();
        assertTrue(state.isCancelled());
        state.start();
        assertFalse(state.isCancelled());
    }
}
