package com.mario.assistant.domain.safety;

/**
 * 所有点击文案的统一安全闸门。
 * 硬边界：绝不下单/支付/购买/充值/开通/借款/投保，绝不下载或安装应用；
 * 「找能量」页只允许「一键收」，「立即签到」只允许签到入口。
 */
public final class ActionGuard {
    public enum Surface { TASK_ROW, AD_PROMPT, SIGNIN, CLAIM, ENERGY_PAGE, DIALOG, NAVIGATION }

    public static final class Decision {
        public final boolean allowed;
        public final String reason;

        private Decision(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        static Decision allow(String reason) { return new Decision(true, reason); }
        static Decision deny(String reason) { return new Decision(false, reason); }
    }

    private static final String[] FORBIDDEN = {
            "下载", "安装", "下单", "购买", "支付", "充值", "开通", "续费", "订购",
            "借款", "贷款", "投保", "保险", "签约"
    };
    private static final String[] DIALOG_LABELS = {
            "知道了", "关闭", "点击退出关闭视频", "立即抽奖", "收下奖励"
    };
    /** 签到完成后起点把任务行按钮换成「领奖励」：只认领取类文案，避免误点同位置的其他按钮。 */
    private static final String[] CLAIM_LABELS = {"领奖励", "领取奖励"};

    private ActionGuard() { }

    public static Decision evaluate(Surface surface, String label) {
        String text = label == null ? "" : label.replace(" ", "").trim();
        if (text.isEmpty()) return Decision.deny("文案为空");
        if (surface == Surface.SIGNIN) {
            return text.contains("立即签到") ? Decision.allow("签到入口")
                    : Decision.deny("签到页只允许「立即签到」");
        }
        if (surface == Surface.CLAIM) {
            for (String allowed : CLAIM_LABELS) {
                if (allowed.equals(text)) return Decision.allow("签到行领奖励");
            }
            return Decision.deny("领奖入口只允许「领奖励」");
        }
        if (surface == Surface.ENERGY_PAGE) {
            return text.contains("一键收") ? Decision.allow("一键收")
                    : Decision.deny("找能量页只允许「一键收」");
        }
        if (surface == Surface.DIALOG) {
            for (String allowed : DIALOG_LABELS) {
                if (allowed.equals(text)) return Decision.allow("弹窗关闭/领奖");
            }
            return Decision.deny("仅允许已知关闭与领奖按钮");
        }
        if (surface == Surface.AD_PROMPT) {
            // 广告入口文案不设限，只拒绝会直接安装应用的按钮。
            if (text.contains("下载")) return Decision.deny("禁止下载");
            if (text.contains("安装")) return Decision.deny("禁止安装");
            return Decision.allow("广告入口");
        }
        for (String forbidden : FORBIDDEN) {
            if (text.contains(forbidden)) return Decision.deny("禁止交易/安装类文案：" + forbidden);
        }
        return Decision.allow(surface == Surface.TASK_ROW ? "任务行" : "页面导航");
    }
}
