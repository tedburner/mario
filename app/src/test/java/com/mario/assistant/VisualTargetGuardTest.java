package com.mario.assistant;

import com.mario.assistant.domain.model.TextBlock;
import com.mario.assistant.domain.welfare.VisualTargetGuard;

import org.junit.Test;
import static org.junit.Assert.*;

public class VisualTargetGuardTest {
    private static final int W = 1200, H = 2670;
    private static final TextBlock ANCHOR = new TextBlock("进入详情页或第三方应用", 361, 2265, 838, 2316);
    private static final TextBlock ANCHOR_TYPO = new TextBlock("进入详倩页或第三方应用", 361, 2265, 838, 2316);

    @Test public void acceptsSameVisibleTargetOnTwoConsecutiveFrames() {
        TextBlock a = new TextBlock("去完成", 800, 805, 950, 855);
        TextBlock b = new TextBlock("去完成", 802, 806, 952, 856);
        assertTrue(VisualTargetGuard.stable(a, b, 1000, 2000));
        assertFalse(VisualTargetGuard.stable(a, new TextBlock("其他", 802, 806, 952, 856), 1000, 2000));
    }
    @Test public void checksAdStabilityByAnchorPositionNotButtonLabel() {
        // 按钮文案两帧不同（误读/动画合并）但位置一致，且锚点严格一致 → 稳定。
        TextBlock a = new TextBlock("去看看", 489, 2130, 702, 2188);
        TextBlock b = new TextBlock("去看看", 490, 2131, 703, 2189);
        assertTrue(VisualTargetGuard.stableDetails(a, b, ANCHOR, ANCHOR_TYPO, W, H));
    }
    @Test public void toleratesMergedAnimationGlyphShiftOnButton() {
        // 动画图形被合并进文字框时中心漂移约 99px，仍在宽容差内。
        TextBlock a = new TextBlock("去看看", 489, 2130, 702, 2188);
        TextBlock merged = new TextBlock("去看看长", 489, 2125, 900, 2195);
        assertTrue(VisualTargetGuard.stableDetails(a, merged, ANCHOR, ANCHOR, W, H));
    }
    @Test public void rejectsWhenAnchorMovesOrTargetJumps() {
        TextBlock a = new TextBlock("去看看", 489, 2130, 702, 2188);
        TextBlock b = new TextBlock("去看看", 490, 2131, 703, 2189);
        TextBlock movedAnchor = new TextBlock("进入详倩页或第三方应用", 361, 2300, 838, 2351);
        assertFalse(VisualTargetGuard.stableDetails(a, b, ANCHOR, movedAnchor, W, H));
        assertFalse(VisualTargetGuard.stableDetails(a, b, ANCHOR, null, W, H));
        TextBlock jumped = new TextBlock("去看看", 489, 1800, 702, 1860);
        assertFalse(VisualTargetGuard.stableDetails(a, jumped, ANCHOR, ANCHOR, W, H));
        assertFalse(VisualTargetGuard.stableDetails(a, null, ANCHOR, ANCHOR, W, H));
    }
    @Test public void rejectsMovedOrOutOfBoundsTarget() {
        TextBlock a = new TextBlock("去完成", 800, 805, 950, 855);
        assertFalse(VisualTargetGuard.stable(a, new TextBlock("去完成", 800, 1005, 950, 1055), 1000, 2000));
        assertFalse(VisualTargetGuard.stable(a, new TextBlock("去完成", 800, 805, 1100, 855), 1000, 2000));
        assertFalse(VisualTargetGuard.stable(a, null, 1000, 2000));
    }
}
