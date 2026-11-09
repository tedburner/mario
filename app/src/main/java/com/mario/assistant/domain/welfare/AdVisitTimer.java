package com.mario.assistant.domain.welfare;

public final class AdVisitTimer {
    private long openedAt = -1;
    public void detailOpened(long nowMillis) { openedAt = nowMillis >= 0 ? nowMillis : -1; }
    public boolean readyToReturn(long nowMillis) {
        return openedAt >= 0 && nowMillis >= openedAt && nowMillis - openedAt >= 15_000;
    }
    public void cancel() { openedAt = -1; }
}
