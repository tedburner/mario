package com.mario.assistant.domain.welfare;

public final class AdFlow {
    private enum Stage { START, DETAILS_REQUESTED, VISITING, RETURNING, CANCELLED }
    private Stage stage = Stage.START;
    private final AdVisitTimer timer = new AdVisitTimer();
    private String destination;
    private boolean inApp;
    public void detailsRequested() { if (stage == Stage.START) stage = Stage.DETAILS_REQUESTED; }
    public boolean observeDetail(String pkg, long now) {
        if (stage != Stage.DETAILS_REQUESTED || !eligible(pkg)) return false;
        destination = pkg;
        timer.detailOpened(now);
        stage = Stage.VISITING;
        return true;
    }
    public boolean confirmInAppDetail() { return stage == Stage.DETAILS_REQUESTED; }
    public void observeDetailInApp(long now) {
        if (stage != Stage.DETAILS_REQUESTED) return;
        inApp = true;
        destination = "com.qidian.QDReader";
        timer.detailOpened(now);
        stage = Stage.VISITING;
    }
    public boolean isInApp() { return inApp; }
    private boolean eligible(String pkg) {
        return pkg != null && !pkg.isEmpty() && !pkg.equals("android")
                && !pkg.equals("com.qidian.QDReader") && !pkg.equals("com.mario.assistant")
                && !pkg.startsWith("com.android.") && !pkg.startsWith("com.google.android.packageinstaller")
                && !pkg.equals("com.miui.home") && !pkg.equals("com.miui.securitycenter")
                && !pkg.equals("com.lbe.security.miui");
    }
    public boolean isDestination(String pkg) { return stage == Stage.VISITING && destination != null && destination.equals(pkg); }
    public boolean shouldReturn(String pkg, long now) { return isDestination(pkg) && timer.readyToReturn(now); }
    public void returnRequested() { if (stage == Stage.VISITING) stage = Stage.RETURNING; }
    public boolean confirmReward() { return stage == Stage.RETURNING; }
    /** 多任务轮播中切到下一支广告：每支广告独立走“点击→停留→返回”流程。 */
    public void reset() { timer.cancel(); stage = Stage.START; destination = null; inApp = false; }
    public void cancel() { timer.cancel(); stage = Stage.CANCELLED; }
}
