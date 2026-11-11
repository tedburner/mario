package com.mario.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mario.assistant.domain.checkin.CheckinApp;
import com.mario.assistant.domain.checkin.CheckinApps;
import com.mario.assistant.domain.checkin.CheckinPolicy;
import com.mario.assistant.domain.model.TextBlock;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CheckinPolicyTest {
    private static final int W = 1080;
    private static final int H = 2400;

    private static TextBlock box(String text, int left, int top, int right, int bottom) {
        return new TextBlock(text, left, top, right, bottom);
    }

    @Test public void homeEntryRequiresUniqueExactMatch() {
        TextBlock entry = box("签到福利", 100, 800, 400, 880);
        assertEquals(entry, CheckinPolicy.findHomeEntry(Arrays.asList(entry), "签到福利"));
        TextBlock spaced = box("签到 福利", 100, 800, 400, 880);
        assertEquals(spaced, CheckinPolicy.findHomeEntry(Arrays.asList(spaced), "签到福利"));
        assertNull(CheckinPolicy.findHomeEntry(Arrays.asList(entry,
                box("签到福利", 100, 1200, 400, 1280)), "签到福利"));
        assertNull(CheckinPolicy.findHomeEntry(Arrays.asList(box("签到福利中心", 100, 800, 400, 880)), "签到福利"));
        assertNull(CheckinPolicy.findHomeEntry(null, "签到福利"));
    }

    @Test public void popupActionPrefersLongerLabelInMiddleBand() {
        TextBlock title = box("签到", 400, 700, 680, 780);
        TextBlock button = box("立即签到", 340, 1300, 740, 1400);
        assertEquals(button, CheckinPolicy.findPopupAction(Arrays.asList(title, button), W, H));
        // 顶部状态栏/底部导航里的文案不算弹窗按钮
        assertNull(CheckinPolicy.findPopupAction(Arrays.asList(box("立即签到", 340, 100, 740, 200)), W, H));
        assertNull(CheckinPolicy.findPopupAction(Arrays.asList(box("立即签到", 340, 2300, 740, 2390)), W, H));
        assertNull(CheckinPolicy.findPopupAction(new ArrayList<>(), W, H));
    }

    @Test public void popupActionReachesBottomOfEchargePopup() {
        // 真机 E充站 1200x2670：弹窗「立即签到」bounds 124,2442-1076,2592，底边在 97.1%
        TextBlock bottom = box("立即签到", 124, 2442, 1076, 2592);
        assertEquals(bottom, CheckinPolicy.findPopupAction(Arrays.asList(bottom), 1200, 2670));
        // 但首页右上角「签到」入口在 15% 以上，仍不能被当成弹窗按钮
        assertNull(CheckinPolicy.findPopupAction(Arrays.asList(box("签到", 1010, 200, 1130, 300)), 1200, 2670));
    }

    @Test public void todayCellIsOnlyMiddleBandAndUnique() {
        TextBlock today = box("今天", 405, 1303, 503, 1348);
        assertEquals(today, CheckinPolicy.findTodaySign(Arrays.asList(today), 1200, 2670));
        // 顶栏标题/底部广告里的「今天」不算签到格
        assertNull(CheckinPolicy.findTodaySign(Arrays.asList(box("今天", 100, 100, 200, 180)), 1200, 2670));
        // 同屏两个「今天」→ 歧义放弃
        assertNull(CheckinPolicy.findTodaySign(Arrays.asList(today,
                box("今天", 600, 1303, 700, 1348)), 1200, 2670));
        assertNull(CheckinPolicy.findTodaySign(Arrays.asList(box("明天", 405, 1303, 503, 1348)), 1200, 2670));
        assertNull(CheckinPolicy.findTodaySign(null, 1200, 2670));
    }

    @Test public void popupActionAmbiguousLabelFallsThrough() {
        // 两个「签到」（标题+图标）歧义：没有更长文案时放弃，交由标签路径
        assertNull(CheckinPolicy.findPopupAction(Arrays.asList(
                box("签到", 400, 700, 680, 780),
                box("签到", 400, 1300, 680, 1380)), W, H));
        // 但同屏有唯一「立即签到」时仍优先它
        TextBlock button = box("立即签到", 340, 1500, 740, 1600);
        assertEquals(button, CheckinPolicy.findPopupAction(Arrays.asList(
                box("签到", 400, 700, 680, 780),
                box("签到", 400, 1300, 680, 1380), button), W, H));
    }

    @Test public void tabOnlyInBottomBand() {
        TextBlock tab = box("我", 900, 2280, 980, 2360);
        assertEquals(tab, CheckinPolicy.findTab(Arrays.asList(tab), "我", W, H));
        assertNull(CheckinPolicy.findTab(Arrays.asList(box("我", 900, 1000, 980, 1080)), "我", W, H));
        assertNull(CheckinPolicy.findTab(Arrays.asList(tab, box("我", 100, 2280, 180, 2360)), "我", W, H));
        assertNull(CheckinPolicy.findTab(Arrays.asList(tab), null, W, H));
    }

    @Test public void tabEntryOnlyTopRight() {
        TextBlock entry = box("签到", 950, 120, 1050, 200);
        assertEquals(entry, CheckinPolicy.findTabEntry(Arrays.asList(entry), "签到", W, H));
        // 左半屏不算右上角入口
        assertNull(CheckinPolicy.findTabEntry(Arrays.asList(box("签到", 100, 120, 200, 200)), "签到", W, H));
        // 超出顶栏带不算
        assertNull(CheckinPolicy.findTabEntry(Arrays.asList(box("签到", 950, 600, 1050, 680)), "签到", W, H));
        assertNull(CheckinPolicy.findTabEntry(Arrays.asList(entry,
                box("签到", 800, 120, 900, 200)), "签到", W, H));
    }

    @Test public void tabEntryToleratesIconPrefixButNotPromoSuffix() {
        // 真机比亚迪把日历图标 OCR 成前缀「6签到」，应容忍
        TextBlock icon = box("6签到", 950, 120, 1050, 200);
        assertEquals(icon, CheckinPolicy.findTabEntry(Arrays.asList(icon), "签到", W, H));
        // 但推广后缀不能靠去噪混进来：「签到VIP」不是签到入口
        assertNull(CheckinPolicy.findTabEntry(Arrays.asList(box("签到VIP", 950, 120, 1050, 200)), "签到", W, H));
        assertNull(CheckinPolicy.findTabEntry(Arrays.asList(box("签到领红包", 950, 120, 1050, 200)), "签到", W, H));
        // 图标前缀 + 裸文案同屏 → 歧义放弃
        assertNull(CheckinPolicy.findTabEntry(Arrays.asList(icon, box("签到", 800, 120, 900, 200)), "签到", W, H));
        assertNull(CheckinPolicy.findTabEntry(Arrays.asList(box("6", 950, 120, 1050, 200)), "签到", W, H));
    }

    @Test public void actionPrefersPriorityOrderAndUniqueness() {
        TextBlock go = box("去签到", 340, 1300, 740, 1400);
        TextBlock plain = box("签到", 340, 1600, 740, 1700);
        assertEquals(go, CheckinPolicy.findAction(Arrays.asList(plain, go)));
        TextBlock immediate = box("立即签到", 340, 1300, 740, 1400);
        assertEquals(immediate, CheckinPolicy.findAction(Arrays.asList(plain, go, immediate)));
        // 同一文案两个候选 → 歧义，落到下一优先级
        assertEquals(plain, CheckinPolicy.findAction(Arrays.asList(plain, go,
                box("去签到", 340, 1800, 740, 1900))));
        assertNull(CheckinPolicy.findAction(Arrays.asList(box("签到领红包", 340, 1300, 740, 1400))));
        assertNull(CheckinPolicy.findAction(null));
    }

    @Test public void doneMarkersAreContainsMatch() {
        assertTrue(CheckinPolicy.isDone(Arrays.asList(box("今日已签到", 100, 100, 400, 180))));
        assertTrue(CheckinPolicy.isDone(Arrays.asList(box("签到成功", 100, 100, 400, 180))));
        // 真机 OCR 形近字误读：签到戌功 仍应认作完成
        assertTrue(CheckinPolicy.isDone(Arrays.asList(box("签到戌功", 100, 100, 400, 180))));
        assertFalse(CheckinPolicy.isDone(Arrays.asList(box("签到领红包", 100, 100, 400, 180))));
        assertTrue(CheckinPolicy.isDone(Arrays.asList(box("明日再来", 100, 100, 400, 180))));
        assertTrue(CheckinPolicy.isDone(Arrays.asList(box("明天再来", 100, 100, 400, 180))));
        assertFalse(CheckinPolicy.isDone(Arrays.asList(box("签到", 100, 100, 400, 180))));
        assertFalse(CheckinPolicy.isDone(Arrays.asList(box("立即签到", 100, 100, 400, 180))));
        assertFalse(CheckinPolicy.isDone(null));
        List<TextBlock> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(box("已签到", 100, 100, 400, 180));
        assertTrue(CheckinPolicy.isDone(withNull));
    }

    @Test public void registryOrderAndSpecs() {
        List<CheckinApp> apps = CheckinApps.all();
        assertEquals(3, apps.size());
        assertEquals("RQrun", apps.get(0).name);
        assertEquals("cn.runningquotient.rq", apps.get(0).packageName);
        assertEquals("签到福利", apps.get(0).homeEntry);
        assertFalse(apps.get(0).popupFirst);
        assertNull(apps.get(0).tab);

        assertEquals("E充站", apps.get(1).name);
        assertEquals("cn.charge", apps.get(1).packageName);
        assertTrue(apps.get(1).popupFirst);
        assertEquals("签到", apps.get(1).homeEntry);
        assertNull(apps.get(1).tab);
        assertTrue(apps.get(1).tapToday);

        assertEquals("比亚迪", apps.get(2).name);
        assertEquals("com.byd.aeri.caranywhere", apps.get(2).packageName);
        assertFalse(apps.get(2).popupFirst);
        assertEquals("我的", apps.get(2).tab);
        assertEquals("签到", apps.get(2).tabEntry);
        assertFalse(apps.get(2).tapToday);
    }
}