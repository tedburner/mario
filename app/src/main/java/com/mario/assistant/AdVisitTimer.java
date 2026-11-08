package com.mario.assistant;

final class AdVisitTimer {
    private long openedAt = -1;
    void detailOpened(long nowMillis) { openedAt = nowMillis >= 0 ? nowMillis : -1; }
    boolean readyToReturn(long nowMillis) {
        return openedAt >= 0 && nowMillis >= openedAt && nowMillis - openedAt >= 15_000;
    }
    void cancel() { openedAt = -1; }
}
