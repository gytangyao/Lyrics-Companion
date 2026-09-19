package com.zuoqirun.lyricscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 无逐字时间轴时按本句时长估算的进度（issue #21）。 */
public class KaraokeProgressEstimateTest {
    @Test public void progressIsLinearAcrossTheLine() {
        assertEquals(0f, KaraokeProgress.estimatedFraction(10_000L, 10_000L, 4_000L), 0.0001f);
        assertEquals(0.5f, KaraokeProgress.estimatedFraction(12_000L, 10_000L, 4_000L), 0.0001f);
        assertEquals(1f, KaraokeProgress.estimatedFraction(14_000L, 10_000L, 4_000L), 0.0001f);
    }

    @Test public void overflowPastTheLineEndStaysFullySung() {
        // 长音拖过本句末尾时不能超过 100%，否则高亮会跑到下一句的宽度上。
        assertEquals(1f, KaraokeProgress.estimatedFraction(30_000L, 10_000L, 4_000L), 0.0001f);
    }

    @Test public void anUnknownDurationOrAPositionBeforeTheLineIsNotEstimated() {
        assertEquals(-1f, KaraokeProgress.estimatedFraction(12_000L, 10_000L, 0L), 0.0001f);
        assertEquals(-1f, KaraokeProgress.estimatedFraction(12_000L, 10_000L, -1L), 0.0001f);
        assertEquals(-1f, KaraokeProgress.estimatedFraction(9_000L, 10_000L, 4_000L), 0.0001f);
        assertEquals(-1f, KaraokeProgress.estimatedFraction(12_000L, -1L, 4_000L), 0.0001f);
    }

    @Test public void theFractionDrivesAWidthThatOnlyGrows() {
        float width = 300f;
        float previous = -1f;
        for (long position = 10_000L; position <= 14_000L; position += 250L) {
            float fraction = KaraokeProgress.estimatedFraction(position, 10_000L, 4_000L);
            float current = width * fraction;
            assertTrue("宽度必须随播放单调不减", current >= previous);
            assertTrue(current >= 0f && current <= width);
            previous = current;
        }
    }
}
