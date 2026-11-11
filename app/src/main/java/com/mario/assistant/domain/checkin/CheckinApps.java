package com.mario.assistant.domain.checkin;

import java.util.Arrays;
import java.util.List;

/**
 * 每日签到名单：按顺序执行，单个 APP 失败跳过继续。
 *
 * <p>包名来源（2026-11-11 真机 pm list + aapt 标签核对）：
 * RQrun=cn.runningquotient.rq；E充站=cn.charge（依威能源，用户口述「E充电」实为此 App）；
 * 比亚迪=com.byd.aeri.caranywhere。国网真·E充电 com.sgcc.evs.echarge 本机未安装，不纳入。</p>
 */
public final class CheckinApps {
    private CheckinApps() { }

    public static List<CheckinApp> all() {
        return Arrays.asList(
                // RQrun：首页「签到福利」入口 → 签到页。
                new CheckinApp("RQrun", "cn.runningquotient.rq",
                        "签到福利", false, null, null, false),
                // E充站：冷启动可能弹签到弹窗（「立即签到」贴底）；没有弹窗走首页右上角「签到」，
                // 签到页无按钮，需点日历里的「今天」格完成（真机 2026-11-11 取证）。
                new CheckinApp("E充站", "cn.charge",
                        "签到", true, null, null, true),
                // 比亚迪：「我的」→ 右上角「签到」→ 签到页。
                new CheckinApp("比亚迪", "com.byd.aeri.caranywhere",
                        null, false, "我的", "签到", false));
    }
}