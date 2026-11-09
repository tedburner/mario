package com.mario.assistant.domain.forest;

/** 森林任务的动态分类与安全边界；任务名不硬编码，按标题关键词 + CTA 判定。 */
public final class ForestTaskPolicy {
    public interface Installed {
        boolean has(String pkg);
    }

    public static final String ALIPAY = "com.eg.android.AlipayGphone";

    private static final String[] SKIP_TITLE_KEYWORDS = {
            "保障", "保险", "投保", "受益", "借款", "贷款", "信用卡", "开户",
            "缴费", "地铁", "ETC", "火车票", "还款", "充值", "转账", "身份证", "银行卡",
            "践行绿色行为"
    };
    private static final String[] INTERNAL_KEYWORDS = {
            "支付宝", "蚂蚁", "森林", "农场", "神奇海洋", "淘金币", "公益", "庄园", "芭芭", "能量", "活力值"
    };
    private static final String[][] APP_KEYWORDS = {
            {"书旗", "com.shuqi.controller"},
            {"美团", "com.sankuai.meituan"},
            {"一淘", "com.taobao.etao"},
            {"淘宝直播", "com.taobao.taobao"},
            {"淘宝", "com.taobao.taobao"},
            {"天猫", "com.tmall.wireless"},
            {"闲鱼", "com.taobao.idlefish"},
            {"优酷", "com.youku.phone"},
            {"微博", "com.sina.weibo"},
            {"高德", "com.autonavi.minimap"},
            {"饿了么", "me.ele"},
            {"飞猪", "com.taobao.trip"},
            {"京东", "com.jingdong.app.mall"},
            {"拼多多", "com.xunmeng.pinduoduo"},
            {"小红书", "com.xingin.xhs"},
            {"抖音", "com.ss.android.ugc.aweme"},
            {"快手", "com.smile.gifmaker"},
            {"爱奇艺", "com.qiyi.video"},
            {"腾讯视频", "com.tencent.qqlive"},
            {"夸克", "com.quark.browser"}
    };
    private static final String[] CLAIM_CTAS = {"去领取", "立即领取", "领取", "签到"};
    private static final String[] DONE_CTAS = {"今日完成", "已领取", "已完成", "已领", "领完"};

    public static final class Decision {
        public final ForestTask.Kind kind;
        public final String targetPackage;
        public final boolean observeThirdParty;
        public final String reason;

        Decision(ForestTask.Kind kind, String targetPackage, boolean observeThirdParty, String reason) {
            this.kind = kind;
            this.targetPackage = targetPackage;
            this.observeThirdParty = observeThirdParty;
            this.reason = reason;
        }
    }

    public static Decision decide(String title, String cta, String detail, Installed installed) {
        String t = normalize(title);
        String c = normalize(cta);
        if (isDone(c)) return new Decision(ForestTask.Kind.DONE, null, false, "已完成");
        if (containsAny(t, SKIP_TITLE_KEYWORDS)) {
            return skip("标题命中跳过规则");
        }
        if (t.contains("消耗") && (t.contains("包子") || t.contains("活力值")
                || t.contains("金币") || t.contains("积分"))) {
            return skip("需消耗资源");
        }
        if (t.contains("通过1关") || t.contains("过一关") || t.contains("闯关")) {
            return skip("需完成关卡");
        }
        if ("去完成".equals(c)) {
            // 当前真机上的“去完成”均为填写保障类表单；无更明确的动作描述时宁可跳过。
            return skip("「去完成」默认跳过");
        }
        ForestTask.Kind kind = containsAny(c, CLAIM_CTAS) ? ForestTask.Kind.CLAIM : ForestTask.Kind.BROWSE;
        String pkg = packageFor(t);
        if (pkg == null && containsAny(t, INTERNAL_KEYWORDS)) pkg = ALIPAY;
        if (pkg != null) {
            if (!installed.has(pkg)) return skip("未安装 " + pkg);
            return new Decision(kind, pkg, false, "已映射 " + pkg);
        }
        return new Decision(kind, null, true, "未知第三方，点开观察");
    }

    private static boolean isDone(String cta) {
        if (cta.startsWith("已")) return true;
        return containsAny(cta, DONE_CTAS);
    }

    private static String packageFor(String title) {
        for (String[] entry : APP_KEYWORDS) {
            if (title.contains(entry[0])) return entry[1];
        }
        return null;
    }

    private static Decision skip(String reason) {
        return new Decision(ForestTask.Kind.SKIP, null, false, reason);
    }

    private static boolean containsAny(String text, String[] words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace(" ", "").replace("\u00a0", "").trim();
    }
}
