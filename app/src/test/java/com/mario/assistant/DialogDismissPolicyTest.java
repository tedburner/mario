package com.mario.assistant;

import com.mario.assistant.domain.model.TextBlock;
import com.mario.assistant.domain.safety.ActionGuard;
import com.mario.assistant.domain.welfare.AdCarouselPolicy;
import com.mario.assistant.domain.welfare.DialogDismissPolicy;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class DialogDismissPolicyTest {
    private static TextBlock box(String text) {
        return new TextBlock(text, 0, 0, 10, 10);
    }

    @Test public void ignoresFramesWithoutRewardDialog() {
        assertEquals(DialogDismissPolicy.Action.IGNORE,
                DialogDismissPolicy.decide(false, false, false));
        // 没有弹窗时即使页面里有个可点的“知道了”也不认。
        assertEquals(DialogDismissPolicy.Action.IGNORE,
                DialogDismissPolicy.decide(false, true, true));
    }

    @Test public void prefersNodeOverUnusableOcrLabel() {
        // 真机：OCR 把「10点 / 知道了 / 888点」并成一行，文案过不了白名单，但节点仍是精确“知道了”。
        assertEquals(DialogDismissPolicy.Action.CLICK_NODE,
                DialogDismissPolicy.decide(true, true, false));
    }

    @Test public void fallsBackToOcrOnlyWhenLabelIsClean() {
        assertEquals(DialogDismissPolicy.Action.CLICK_OCR,
                DialogDismissPolicy.decide(true, false, true));
    }

    @Test public void stopsInsteadOfSpinningWhenNeitherPathWorks() {
        assertEquals(DialogDismissPolicy.Action.STOP,
                DialogDismissPolicy.decide(true, false, false));
    }

    @Test public void centeredButtonRowIsAcceptedWhenTextIsGlued() {
        // 真机：文案被粘住且弹窗不暴露可点节点，但框落在居中单行按钮带内。
        assertEquals(DialogDismissPolicy.Action.CLICK_CENTERED,
                DialogDismissPolicy.decide(true, false, false, true));
        // 位置不符合按钮带时仍然停止，绝不盲点。
        assertEquals(DialogDismissPolicy.Action.STOP,
                DialogDismissPolicy.decide(true, false, false, false));
        // 节点/文案可用时优先级高于几何兜底。
        assertEquals(DialogDismissPolicy.Action.CLICK_NODE,
                DialogDismissPolicy.decide(true, true, false, true));
        assertEquals(DialogDismissPolicy.Action.CLICK_OCR,
                DialogDismissPolicy.decide(true, false, true, true));
    }

    /** 锁定真机回归帧：「恭喜获得 10点章节卡（1天）」+ 并行的「10点 / 知道了 / 888点」。 */
    @Test public void reproducedMergedFrameStopsOnlyWhenNodeIsMissing() {
        TextBlock target = AdCarouselPolicy.findDismissButton(Arrays.asList(
                box("恭喜获得 10点章节卡（1天）"), box("10(0知道了8)8点")));
        assertNotNull("OCR 仍按“知道”包含匹配定位到按钮所在行", target);
        assertFalse("并成一行的文案不该通过弹窗白名单",
                ActionGuard.evaluate(ActionGuard.Surface.DIALOG, target.text).allowed);
        assertEquals(DialogDismissPolicy.Action.STOP,
                DialogDismissPolicy.decide(true, false, false));
        assertEquals("节点可用时走节点，绕开 OCR 合并",
                DialogDismissPolicy.Action.CLICK_NODE,
                DialogDismissPolicy.decide(true, true, false));
    }
}
