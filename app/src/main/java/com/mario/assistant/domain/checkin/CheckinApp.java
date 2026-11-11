package com.mario.assistant.domain.checkin;

/**
 * 一个 APP 的每日签到规格。新增 APP 只需在 {@link CheckinApps} 注册一条，
 * 运行器按同一套「找入口 → 点签到 → 认完成标记」状态机执行。
 *
 * <p>入口路径按字段组合：popupFirst=首页先找签到弹窗按钮；homeEntry=首页直接入口；
 * 观察窗内都没有再走 tab → tabEntry。tapToday=签到页没有按钮，点日历里的「今天」格。</p>
 */
public final class CheckinApp {
    public final String name;
    public final String packageName;
    /** 首页签到入口文案（如 RQrun「签到福利」、E充站右上角「签到」）；null 表示首页没有直接入口。 */
    public final String homeEntry;
    /** 首页是否可能弹签到弹窗（如 E充站）：优先点弹窗按钮，没有再走首页入口/标签。 */
    public final boolean popupFirst;
    /** 底部标签文案（「我」/「我的」）；null 表示不需要切标签。 */
    public final String tab;
    /** 标签页内的签到入口文案（右上角「签到」）；null 表示标签页无入口。 */
    public final String tabEntry;
    /** 签到页是否靠点「今天」格完成（如 E充站），此时不找签到按钮。 */
    public final boolean tapToday;

    public CheckinApp(String name, String packageName, String homeEntry,
                      boolean popupFirst, String tab, String tabEntry, boolean tapToday) {
        this.name = name;
        this.packageName = packageName;
        this.homeEntry = homeEntry;
        this.popupFirst = popupFirst;
        this.tab = tab;
        this.tabEntry = tabEntry;
        this.tapToday = tapToday;
    }
}