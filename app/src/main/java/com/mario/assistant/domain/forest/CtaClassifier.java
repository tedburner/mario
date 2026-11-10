package com.mario.assistant.domain.forest;

/**
 * 任务行按钮的语义分类。
 *
 * <p>原来用精确文案白名单（只认「逛一逛/去看看/立即领取」等少数几个），支付宝每换一批活动词
 * （去抽奖、去守护、去逛集市、马上玩…）就整行解析不出来，表现为「任务永远不做」。
 * 这里改成按动词归类，新文案只要还在「进入 / 领取 / 抽奖」这三类语义内就能自动覆盖。</p>
 */
public final class CtaClassifier {
    public enum Action { CLAIM, DRAW, ENTER, DONE, REJECT, NONE }

    private static final String[] DONE_WORDS = {
            "今日完成", "已完成", "已领取", "已领", "领完", "已参与", "明天再来", "已抽奖"
    };
    /** 交易/安装类入口：点进去会产生下单、支付或下载，一律不做。 */
    private static final String[] REJECT_WORDS = {
            "下单", "购买", "支付", "付款", "开通", "充值", "借款", "贷款", "投保",
            "签约", "缴费", "转账", "下载", "安装", "办卡"
    };
    private static final String[] DRAW_WORDS = {"抽奖", "摇奖", "抽一次", "抽一抽", "开奖"};
    private static final String[] CLAIM_WORDS = {
            "立即领取", "领取", "领奖励", "领取奖励", "签到", "收下奖励", "收下"
    };
    /** 进入型任务的动词前缀（浏览、游玩、守护、参与…）。 */
    private static final String[] ENTER_PREFIXES = {
            "去", "逛", "看", "玩", "马上", "立即", "参与", "参加", "领", "签到",
            "做", "守护", "查", "测", "收", "兑"
    };
    /** 森林主页上的非任务入口，避免滚动后误当任务行点击。 */
    private static final String[] HOME_NOISE = {
            "去兑换", "去浇水", "去施肥", "去给TA", "去画鱼", "去偷", "去看全部"
    };

    /** 按钮文案都很短；超过 6 字的基本是同行标题被滚动错位抓过来的。 */
    private static final int MAX_CTA_LENGTH = 6;

    private CtaClassifier() { }

    public static Action classify(String rawCta) {
        String c = normalize(rawCta);
        if (c.isEmpty() || c.length() > MAX_CTA_LENGTH) return Action.NONE;
        if (containsAny(c, DONE_WORDS)) return Action.DONE;
        if (containsAny(c, REJECT_WORDS)) return Action.REJECT;
        if (containsAny(c, HOME_NOISE)) return Action.NONE;
        if (containsAny(c, DRAW_WORDS)) return Action.DRAW;
        if (containsAny(c, CLAIM_WORDS)) return Action.CLAIM;
        for (String prefix : ENTER_PREFIXES) {
            if (c.startsWith(prefix)) return Action.ENTER;
        }
        return Action.NONE;
    }

    private static boolean containsAny(String text, String[] words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace(" ", "").replace("\u00a0", "").trim();
    }
}
