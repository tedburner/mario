package com.mario.assistant.domain.forest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 森林任务行：标题、CTA、奖励与执行分类。 */
public final class ForestTask {
    /** DRAW：行上已经是「去抽奖」，点完直接进抽奖页，不再按浏览计时。 */
    public enum Kind { BROWSE, CLAIM, DRAW, DONE, SKIP }

    public final String title;
    public final String cta;
    public final String reward;
    public final String detail;
    public final int ctaId;
    public final int left, top, right, bottom;
    public final Kind kind;
    public final String targetPackage;
    public final boolean observeThirdParty;
    public final String reason;

    public ForestTask(String title, String cta, String reward, String detail, int ctaId,
               int left, int top, int right, int bottom, Kind kind,
               String targetPackage, boolean observeThirdParty, String reason) {
        this.title = title;
        this.cta = cta;
        this.reward = reward;
        this.detail = detail;
        this.ctaId = ctaId;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.kind = kind;
        this.targetPackage = targetPackage;
        this.observeThirdParty = observeThirdParty;
        this.reason = reason;
    }

    public boolean actionable() {
        return kind == Kind.BROWSE || kind == Kind.CLAIM || kind == Kind.DRAW;
    }

    /**
     * 浏览时长：优先读取行内“浏览15s/30秒”，未知时默认 10 秒。
     * 默认 15s 的版本实测 22/22 全走默认、单任务 ~35s，900s deadline 只能做完 22 项；
     * 降到 10s 后首次浏览不够的任务靠第二次点击（实测 ~3s 即触发奖励）兜底。
     */
    public int browseSeconds() {
        Matcher matcher = BROWSE_SECONDS.matcher(detail == null ? "" : detail);
        if (!matcher.find()) return 10;
        try {
            int seconds = Integer.parseInt(matcher.group(1));
            return Math.max(5, Math.min(60, seconds));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    /**
     * 「浏览Ns」允许中间夹少量汉字（真机行文案是「浏览市集15s」「浏览30s」），
     * 此前要求紧跟数字导致「浏览市集15s」永远匹配不上、整行走默认时长。
     */
    private static final Pattern BROWSE_SECONDS =
            Pattern.compile("浏览[\\u4e00-\\u9fa5]{0,4}\\s*(\\d+)\\s*(?:s|秒)");
}
