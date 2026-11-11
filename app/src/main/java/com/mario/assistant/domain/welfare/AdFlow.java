package com.mario.assistant.domain.welfare;

import java.util.ArrayList;
import java.util.List;

/**
 * 单支广告的“点击详情 → 停留 15 秒 → 返回起点”流程。
 *
 * 真机实测广告会嵌套跳转：起点 → 微信 → 京东（或美团 → 应用商店），原先只记第一个
 * 外部包名，链路一换包就判定“已离开广告详情页”直接停止。这里改为记录整条链路，
 * 15 秒计时仍从第一次进入外部应用开始，到点后从当前前台应用直接切回起点。
 */
public final class AdFlow {
    private static final String QIDIAN = "com.qidian.QDReader";
    private static final String SELF = "com.mario.assistant";
    /** 链路最多记录几跳；超出后仍允许从当前外部应用返回。 */
    static final int MAX_CHAIN_HOPS = 4;

    private enum Stage { START, DETAILS_REQUESTED, VISITING, RETURNING, CANCELLED }

    private Stage stage = Stage.START;
    private final AdVisitTimer timer = new AdVisitTimer();
    private final List<String> chain = new ArrayList<>();
    private String destination;
    private boolean inApp;

    public void detailsRequested() { if (stage == Stage.START) stage = Stage.DETAILS_REQUESTED; }

    public boolean observeDetail(String pkg, long now) {
        if (stage != Stage.DETAILS_REQUESTED || !isExternalApp(pkg)) return false;
        destination = pkg;
        chain.clear();
        chain.add(pkg);
        timer.detailOpened(now);
        stage = Stage.VISITING;
        return true;
    }

    /** 广告链继续跳到另一个外部应用：保留首次进入的 15 秒计时，只扩展链路。 */
    public boolean observeChainHop(String pkg) {
        if (stage != Stage.VISITING || inApp || !isExternalApp(pkg)) return false;
        if (!chain.isEmpty() && chain.get(chain.size() - 1).equals(pkg)) return false;
        destination = pkg;
        if (chain.size() < MAX_CHAIN_HOPS) chain.add(pkg);
        return true;
    }

    public boolean confirmInAppDetail() { return stage == Stage.DETAILS_REQUESTED; }

    public void observeDetailInApp(long now) {
        if (stage != Stage.DETAILS_REQUESTED) return;
        inApp = true;
        destination = QIDIAN;
        timer.detailOpened(now);
        stage = Stage.VISITING;
    }

    public boolean isInApp() { return inApp; }

    /** 外部广告落地应用（非系统界面、非起点自身）。 */
    public static boolean isExternalApp(String pkg) {
        return pkg != null && !pkg.isEmpty() && !pkg.equals("android")
                && !pkg.equals(QIDIAN) && !pkg.equals(SELF)
                && !pkg.startsWith("com.android.")
                && !pkg.startsWith("com.google.android.packageinstaller")
                && !pkg.equals("com.miui.home") && !pkg.equals("com.miui.securitycenter")
                && !pkg.equals("com.lbe.security.miui") && !pkg.equals("miui.systemui.plugin");
    }

    /** 系统浮层 / 桌面 / 自身：扫码过程中的瞬时前台，不代表离开了广告。 */
    public static boolean isTransient(String pkg) {
        return pkg == null || pkg.isEmpty() || pkg.equals("android") || pkg.equals(SELF)
                || pkg.equals("com.miui.home") || pkg.equals("com.android.systemui")
                || pkg.equals("miui.systemui.plugin") || pkg.equals("com.miui.securitycenter")
                || pkg.equals("com.lbe.security.miui") || pkg.equals("com.google.android.gms");
    }

    /** 应用商店 / 安装器：广告引导下载时落到这里，绝不能继续点，也不能靠返回键绕。 */
    public static boolean isBlockedLanding(String pkg) {
        if (pkg == null) return false;
        return pkg.startsWith("com.google.android.packageinstaller")
                || pkg.startsWith("com.android.packageinstaller")
                || pkg.equals("com.miui.packageinstaller")
                || pkg.equals("com.xiaomi.market") || pkg.equals("com.android.vending")
                || pkg.equals("com.huawei.appmarket") || pkg.equals("com.oppo.market")
                || pkg.equals("com.vivo.appstore") || pkg.equals("com.tencent.android.qqdownloader")
                || pkg.equals("com.qihoo.appstore") || pkg.equals("com.baidu.appsearch")
                || pkg.equals("com.wandoujia.phoenix2");
    }

    public boolean isDestination(String pkg) {
        if (stage != Stage.VISITING) return false;
        if (inApp) return QIDIAN.equals(pkg);
        return chain.contains(pkg);
    }

    public boolean shouldReturn(String pkg, long now) {
        if (stage != Stage.VISITING || !timer.readyToReturn(now)) return false;
        if (inApp) return QIDIAN.equals(pkg);
        return isExternalApp(pkg);
    }

    /** 广告链诊断文案，写进运行日志方便复盘跳转路径。 */
    public String chainDescription() {
        return String.join(" → ", chain);
    }

    public void returnRequested() { if (stage == Stage.VISITING) stage = Stage.RETURNING; }

    public boolean confirmReward() { return stage == Stage.RETURNING; }

    /** 多任务轮播中切到下一支广告：每支广告独立走“点击→停留→返回”流程。 */
    public void reset() {
        timer.cancel();
        stage = Stage.START;
        destination = null;
        inApp = false;
        chain.clear();
    }

    public void cancel() { timer.cancel(); stage = Stage.CANCELLED; }
}
