package com.mario.assistant.domain.welfare;

import java.util.HashMap;
import java.util.Map;

/**
 * 同一条任务行在一次试运行内的进入预算。
 *
 * 真机实测（2026-11-10）福利中心的任务行有两种语义：
 * 一次性任务（做任务领惊喜福利 / 携程签到）做完一轮就该变成「已领取」；
 * 累计型任务（激励任务里程碑、完成3个广告任务得奖励）每完成一轮广告只推进一档，
 * 按钮会一直是「去完成」，直到所有里程碑打完。
 *
 * 因此「进入次数」不能单独当作「状态没推进」的判据：累计型任务第 4、5 次进入都是
 * 合法进展（真机第 4 次被上限 3 掐断时，屏幕上正好是 3 个里程碑勾）。真正该停的是
 * 「进去了却始终没走到广告页」以及累计型任务打到硬上限。
 *
 * 「限时广告加点」同样是累计型：每支广告走完会切到「去阅读」解锁下一支，一轮就是
 * 3 个广告 + 1 次阅读，真机第 4 次进入被判一次性预算 3 次误停；按用户要求放宽到 20 次。
 */
public final class TaskEntryBudget {
    /** 一次性任务的进入上限。 */
    public static final int MAX_ENTRIES = 3;
    /** 累计型任务的进入上限：真机里程碑为 9 档，留三次余量。 */
    public static final int MAX_CUMULATIVE_ENTRIES = 12;
    /** 限时广告加点：每条广告后还要阅读解锁，单轮就会进入 4 次以上。 */
    public static final int MAX_LIMITED_AD_ENTRIES = 20;
    /** 连续多少次「进去了却没走到广告页」判定为状态未推进。 */
    public static final int MAX_STALLED_ENTRIES = 2;

    /** 限时广告加点的归一化标题（OCR 装饰符号已由定位器去掉）。 */
    public static final String LIMITED_AD_TITLE = "限时广告加点";

    /** 累计型任务标题：每轮广告推进一档，按钮不会变成「已领取」。 */
    private static final String[] CUMULATIVE_TITLES = {LIMITED_AD_TITLE, "激励任务", "完成3个广告任务得奖励"};

    private static final class Stat {
        int entries;
        int stalled;
    }

    private final Map<String, Stat> stats = new HashMap<>();

    public static boolean isCumulative(String title) {
        if (title == null) return false;
        for (String candidate : CUMULATIVE_TITLES) if (candidate.equals(title)) return true;
        return false;
    }

    public static int maxEntries(String title) {
        if (LIMITED_AD_TITLE.equals(title)) return MAX_LIMITED_AD_ENTRIES;
        return isCumulative(title) ? MAX_CUMULATIVE_ENTRIES : MAX_ENTRIES;
    }

    /** 记录一次进入；返回 false 表示本条任务行已达预算，调用方应停止。 */
    public boolean tryEnter(String title) {
        Stat stat = stat(title);
        if (stat.entries >= maxEntries(title) || stat.stalled >= MAX_STALLED_ENTRIES) return false;
        stat.entries++;
        return true;
    }

    /** 本轮确实走到了广告页：清零「连续空进」计数。 */
    public void recordProgress(String title) {
        stat(title).stalled = 0;
    }

    /** 本轮进去了却没走到广告页：累计空进次数。 */
    public void recordStalled(String title) {
        stat(title).stalled++;
    }

    public int entries(String title) {
        Stat stat = existing(title);
        return stat == null ? 0 : stat.entries;
    }

    public int stalled(String title) {
        Stat stat = existing(title);
        return stat == null ? 0 : stat.stalled;
    }

    private Stat existing(String title) {
        return stats.get(title == null ? "" : title);
    }

    private Stat stat(String title) {
        String key = title == null ? "" : title;
        Stat stat = stats.get(key);
        if (stat == null) {
            stat = new Stat();
            stats.put(key, stat);
        }
        return stat;
    }
}
