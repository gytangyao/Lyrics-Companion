package com.zuoqirun.lyricscompanion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClassicLayoutMathTest {
    @Test public void wideningFixedHeightDoesNotIncreaseClassicScalePastHeight() {
        float normal = ClassicLayoutMath.contentScale(390f, 226f, 1f);
        float wide = ClassicLayoutMath.contentScale(780f, 226f, 1f);
        assertEquals(1f, normal, 0.001f);
        assertEquals(normal, wide, 0.001f);
    }

    @Test public void textScaleShrinksWhenNextLineHasLittleVerticalRoom() {
        float roomy = ClassicLayoutMath.constrainedTextScale(1.8f, 1f, 1f,
                1f, 0.7f, 33f, 57f, 84f, 116f, 140f, 190f, true);
        float tight = ClassicLayoutMath.constrainedTextScale(1.8f, 1f, 1f,
                1f, 0.7f, 33f, 57f, 84f, 116f, 140f, 154f, true);
        assertTrue(roomy < 1.8f);
        assertTrue(tight < roomy);
        assertTrue(tight >= 0.45f);
    }

    @Test public void classicRowsFollowTheLineCountSetting() {
        // 经典样式最多摆三行；1 行时只剩本句（issue #26）。
        assertEquals(3, ClassicLayoutMath.visibleRowCount(7));
        assertEquals(3, ClassicLayoutMath.visibleRowCount(3));
        assertEquals(2, ClassicLayoutMath.visibleRowCount(2));
        assertEquals(1, ClassicLayoutMath.visibleRowCount(1));
        assertEquals(1, ClassicLayoutMath.visibleRowCount(0));
    }

    @Test public void rowGapGrowsWithTheTitleSizeAndKeepsItsMinimum() {
        // 默认字号下间距保持改动前的 24dp / 27dp，观感不变。
        assertEquals(24f, ClassicLayoutMath.stackedGapDp(11f, 15f, 24f), 0.01f);
        assertEquals(27f, ClassicLayoutMath.stackedGapDp(15f, 12.1f, 27f), 0.01f);
        // 歌名调到 180%：间距跟着长，两行不再互相挤（issue #38）。
        assertTrue(ClassicLayoutMath.stackedGapDp(11f, 27f, 24f) > 24f);
    }

    @Test public void raisingTheTitleSizeNoLongerShrinksTheOtherRows() {
        // 歌名 180%：旧实现里固定的 27dp 行距会把整块歌词一起缩小到 0.45~0.7，
        // 现在行距跟着歌名走，其它行按用户设的字号原样显示（issue #38）。
        float titleScale = 1.8f;
        float titleBaseline = 33f + ClassicLayoutMath.stackedGapDp(11f, 15f * titleScale, 24f);
        float previousBaseline = titleBaseline
                + ClassicLayoutMath.stackedGapDp(15f * titleScale, 12.1f, 27f);
        float currentBaseline = (previousBaseline + 190f) * 0.5f;
        float scale = ClassicLayoutMath.constrainedTextScale(1f, 1f, 1f, titleScale, 0.7f,
                new ClassicLayoutMath.Card(33f, titleBaseline, previousBaseline, currentBaseline,
                        currentBaseline, 190f, true, true, false, true));
        assertEquals(1f, scale, 0.0001f);
    }

    @Test public void droppingThePreviousRowStopsConstrainingTheRowsBelowIt() {
        // 行数设成 2：没有上一句，本句上方只剩歌名，收缩不该再按上一句的位置算（issue #26）。
        float three = ClassicLayoutMath.constrainedTextScale(2f, 1f, 1f, 1f, 0.7f,
                new ClassicLayoutMath.Card(33f, 57f, 84f, 116f, 140f, 190f,
                        true, true, true, true));
        float two = ClassicLayoutMath.constrainedTextScale(2f, 1f, 1f, 1f, 0.7f,
                new ClassicLayoutMath.Card(33f, 57f, 57f, 116f, 140f, 190f,
                        true, false, true, true));
        assertTrue("2 行时歌词应能更大，实际 " + two + " vs " + three, two > three);
    }

    @Test public void contentAlignmentMovesTheWholeBlock() {
        // 留空＝沿用样式自己的摆法，位移为 0。
        assertEquals(0f, ClassicLayoutMath.alignedRowShift("", 90f, 60f, 10f, 200f), 0.0001f);
        // 顶部：块顶贴到区域上沿。
        assertEquals(-80f, ClassicLayoutMath.alignedRowShift("top", 90f, 60f, 10f, 200f), 0.0001f);
        // 居中：剩余空间上下各一半。
        assertEquals(-15f, ClassicLayoutMath.alignedRowShift("center", 90f, 60f, 10f, 200f), 0.0001f);
        // 底部：块底贴到区域下沿。
        assertEquals(50f, ClassicLayoutMath.alignedRowShift("bottom", 90f, 60f, 10f, 200f), 0.0001f);
        // 内容比区域还高时不再往外推，仍然从上沿开始。
        assertEquals(-80f, ClassicLayoutMath.alignedRowShift("center", 90f, 260f, 10f, 200f),
                0.0001f);
    }
}
