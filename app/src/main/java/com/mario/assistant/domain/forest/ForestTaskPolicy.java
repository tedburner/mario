package com.mario.assistant.domain.forest;

/** 森林任务的动态分类与安全边界；任务名不硬编码，按标题关键词 + CTA 判定。 */
public final class ForestTaskPolicy {
    public interface Installed {
        boolean has(String pkg);
    }

    public static final String ALIPAY = "com.eg.android.AlipayGphone";

    private static final String[] SKIP_TITLE_KEYWORDS = {
            "保障", "借款", "贷款", "信用卡", "开户",
            "缴费", "地铁", "ETC", "火车票", "还款", "充值", "转账", "身份证", "银行卡",
            "践行绿色行为",
            // 真机 11-11~11-22 三份日志实证「永不完成」的任务，浏览只会白耗 ~30 秒/次：
            // 能量雨需交互接雨滴（0 完成/8 未确认）；点淘点击后始终留在支付宝内、需跳装点淘 App（0/11）；
            // 小荷包插件 11-22/23/26/27 四轮 0 完成，无障碍+坐标两种点击都不跳转，需手动加桌面插件。
            // 注意「玩一玩」类小游戏（森林乐消消/梦幻消除战/青云诀）浏览后 CTA 变「去抽奖」，大多能完成，不能跳。
            "能量雨", "点淘", "插件"
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

    /**
     * 「玩一玩」类小游戏行：首次浏览常不够，按钮仍「去看看」时值得再浏览一次，
     * 之后行会变「去抽奖」走抽奖链收尾（真机 11-25 失败 / 11-27 成功对照）。
     */
    public static boolean isGameBrowse(String title) {
        return normalize(title).contains("玩一玩");
    }

    public static Decision decide(String title, String cta, String detail, Installed installed) {
        String t = normalize(title);
        String c = normalize(cta);
        // 真机 11-28：任务访问后按钮常变「继续看 / 得更多」。寻宝行的「继续看」是多段任务，
        // 必须重进寻宝页继续；其余行的「继续看 / 得更多」当日部分已完成，浏览只会白耗
        // 30~90 秒并留下一串「未确认」（实测一轮 19 项），如实跳过。
        boolean treasureContinue = "继续看".equals(c) && t.contains("寻宝");
        if (("继续看".equals(c) || "得更多".equals(c)) && !treasureContinue) {
            return skip("当日任务已完成（" + c + "）");
        }
        CtaClassifier.Action action = CtaClassifier.classify(c);
        if (action == CtaClassifier.Action.DONE) {
            return new Decision(ForestTask.Kind.DONE, null, false, "已完成");
        }
        if (action == CtaClassifier.Action.REJECT) {
            return skip("交易/安装类入口：" + c);
        }
        if (containsAny(t, SKIP_TITLE_KEYWORDS)) {
            return skip("标题命中跳过规则");
        }
        if (treasureContinue) {
            // 寻宝行多段任务：重进寻宝页继续（运行器按标题含「寻宝」进入寻宝子流程）。
            String pkg = packageFor(t);
            if (pkg == null && containsAny(t, INTERNAL_KEYWORDS)) pkg = ALIPAY;
            if (pkg != null && !installed.has(pkg)) return skip("未安装 " + pkg);
            return new Decision(ForestTask.Kind.BROWSE, pkg, false, "寻宝任务继续看");
        }
        if (t.contains("消耗") && (t.contains("包子") || t.contains("活力值")
                || t.contains("金币") || t.contains("积分"))) {
            return skip("需消耗资源");
        }
        if (t.contains("通过1关") || t.contains("过一关") || t.contains("闯关")) {
            return skip("需完成关卡");
        }
        if (action == CtaClassifier.Action.NONE) {
            return skip("未识别的按钮文案：" + c);
        }
        ForestTask.Kind kind;
        if (action == CtaClassifier.Action.CLAIM) {
            kind = ForestTask.Kind.CLAIM;
        } else if (action == CtaClassifier.Action.DRAW) {
            kind = ForestTask.Kind.DRAW;
        } else {
            // 「去完成」「去守护」「去逛集市」等一律按浏览执行：只进页面停留，落地页内不做任何点击。
            kind = ForestTask.Kind.BROWSE;
        }
        String pkg = packageFor(t);
        if (pkg == null && containsAny(t, INTERNAL_KEYWORDS)) pkg = ALIPAY;
        if (pkg != null) {
            if (!installed.has(pkg)) return skip("未安装 " + pkg);
            return new Decision(kind, pkg, false, "已映射 " + pkg);
        }
        return new Decision(kind, null, true, "未知第三方，点开观察");
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
