package com.mario.assistant;

final class RunState {
    private boolean running;
    private boolean cancelled;
    synchronized boolean start() {
        if (running) return false;
        running = true;
        cancelled = false;
        return true;
    }
    synchronized void stop() { cancelled = true; running = false; }
    synchronized boolean isCancelled() { return cancelled; }
}
