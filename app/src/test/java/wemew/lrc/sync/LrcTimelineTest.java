package wemew.lrc.sync;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LrcTimelineTest {
    @Test public void parsesLrcAndClosestTranslation() {
        LrcTimeline timeline = LrcTimeline.parse(
                "[00:01.00]第一句\n[00:04.500]第二句",
                "[00:01.20]First\n[00:04.50]Second");
        LrcTimeline.At at = timeline.at(4_800L);
        assertEquals("第一句", at.previousLyric);
        assertEquals("第二句", at.lyric);
        assertEquals("Second", at.translatedLyric);
    }

    @Test public void yrcTracksCompletedTextAndCurrentWordProgress() {
        LrcTimeline timeline = LrcTimeline.parse("", "",
                "[1000,2000](1000,500,0)你(1500,500,0)好(2000,1000,0)世界");
        LrcTimeline.At at = timeline.at(1_750L);
        assertEquals("你", at.completedLyric);
        assertEquals("好", at.currentWord);
        assertEquals(500, at.wordProgressPermille);
    }

    @Test public void yrcMarksOnlySustainedPhraseEndingAsTrailing() {
        LrcTimeline timeline = LrcTimeline.parse("", "",
                "[1000,2200](1000,500,0)我(1500,1200,0)爱(2700,500,0)你");
        assertFalse(timeline.at(1_750L).trailingWord);
        assertFalse(timeline.at(2_100L).trailingWord);

        LrcTimeline ending = LrcTimeline.parse("", "",
                "[1000,1800](1000,500,0)我(1500,1300,0)爱");
        assertTrue(ending.at(2_100L).trailingWord);
    }

    @Test public void yrcTrimsTimedEdgesWithoutShiftingKaraokeBoundaries() {
        LrcTimeline timeline = LrcTimeline.parse("", "",
                "[1000,1500](1000,500,0) 你(1500,500,0)好(2000,500,0) ");

        LrcTimeline.At first = timeline.at(1_250L);
        assertEquals("你好", first.lyric);
        assertEquals("", first.completedLyric);
        assertEquals("你", first.currentWord);
        assertEquals(500, first.wordProgressPermille);

        LrcTimeline.At second = timeline.at(1_750L);
        assertEquals("你", second.completedLyric);
        assertEquals("好", second.currentWord);
    }

    @Test public void yrcTranslationUsesMatchingLrcLineWhenFirstWordStartsLate() {
        LrcTimeline timeline = LrcTimeline.parse(
                "[00:01.00]Catch my breath\n[00:04.00]Next line",
                "[00:01.00]停下来缓缓气\n[00:04.00]下一句",
                "[3100,600](3100,600,0)Catch my breath\n"
                        + "[4200,600](4200,600,0)Next line");
        assertEquals("停下来缓缓气", timeline.at(3_300L).translatedLyric);
        assertEquals("下一句", timeline.at(4_300L).translatedLyric);
    }

    @Test public void yrcDoesNotBorrowTranslationForMatchingUntranslatedAdlib() {
        LrcTimeline timeline = LrcTimeline.parse(
                "[00:01.00]Main line\n[00:02.50]Oh",
                "[00:01.00]主句",
                "[1000,800](1000,800,0)Main line\n[2500,500](2500,500,0)Oh");
        assertEquals("", timeline.at(2_600L).translatedLyric);
    }

    @Test public void longUntimedGapBecomesInterlude() {
        LrcTimeline timeline = LrcTimeline.parse(
                "[00:01.00]开场\n[00:20.00]下一句", "");
        LrcTimeline.At gap = timeline.at(10_000L);
        assertTrue(gap.interlude);
        assertEquals("下一句", gap.nextLyric);
        assertFalse(timeline.isEmpty());
    }

    @Test public void plainLrcNeverPretendsToHaveWordTiming() {
        LrcTimeline timeline = LrcTimeline.parse("[00:01.00]整句歌词", "");
        LrcTimeline.At at = timeline.at(1_500L);
        assertEquals("整句歌词", at.lyric);
        assertFalse(at.wordTimed);
        assertEquals("", at.completedLyric);
        assertEquals("", at.currentWord);
    }

    @Test public void revealQuantizationCountsWholeUnicodeCodePoints() {
        String value = "你😀好";
        assertEquals(0, LrcTimeline.revealedCodePointCount(value, 0));
        assertEquals(1, LrcTimeline.revealedCodePointCount(value, 1));
        assertEquals(1, LrcTimeline.revealedCodePointCount(value, 333));
        assertEquals(2, LrcTimeline.revealedCodePointCount(value, 334));
        assertEquals(3, LrcTimeline.revealedCodePointCount(value, 1000));
    }

    @Test public void exposesNearbyLinesForRefinedScrollingLayout() {
        LrcTimeline timeline = LrcTimeline.parse(
                "[00:01.00]一\n[00:02.00]二\n[00:03.00]三\n[00:04.00]四\n[00:05.00]五",
                "[00:03.00]Three");
        LrcTimeline.At at = timeline.at(3_200L);
        assertEquals(5, at.nearbyLines.size());
        assertEquals(-2, at.nearbyLines.get(0).offset);
        assertEquals(0, at.nearbyLines.get(2).offset);
        assertEquals("三", at.nearbyLines.get(2).text);
        assertEquals("Three", at.nearbyLines.get(2).translated);
        assertEquals(2, at.nearbyLines.get(4).offset);
    }

    @Test public void enhancedGapCreatesARealInterludeLine() {
        LrcTimeline timeline = LrcTimeline.parse("", "",
                "[1000,1000](1000,1000,0)前句\n[8000,1000](8000,1000,0)后句");
        LrcTimeline.At at = timeline.at(4_000L);
        assertTrue(at.interlude);
        assertEquals(3, at.nearbyLines.size());
        assertTrue(at.nearbyLines.get(1).interlude);
        assertEquals(0, at.nearbyLines.get(1).offset);
    }

    @Test public void leadingGapOfFiveSecondsCreatesInterludeFromZero() {
        LrcTimeline timeline = LrcTimeline.parse("[00:05.00]第一句", "");
        LrcTimeline.At at = timeline.at(0L);
        assertTrue(at.interlude);
        assertEquals(0L, at.lineStartMs);
        assertEquals(5_000L, at.lineDurationMs);
        assertTrue(at.nearbyLines.get(0).interlude);
    }

    @Test public void browseMovesBetweenTimedLyricLines() {
        LrcTimeline timeline = LrcTimeline.parse(
                "[00:01.00]一\n[00:02.00]二\n[00:03.00]三", "");
        assertEquals(3_000L, timeline.shiftedPosition(2_200L, 1));
        assertEquals(1_000L, timeline.shiftedPosition(2_200L, -1));
    }

    @Test public void sentinelDurationsAreSanitized() {
        // Soda's native timing marks the final line with JS MAX_SAFE_INTEGER as "until the
        // end"; it must not inflate the timeline span (which tripped the live fallback).
        List<LrcTimeline.Word> words = new ArrayList<>();
        words.add(new LrcTimeline.Word(1000, 400, "山"));
        words.add(new LrcTimeline.Word(1400, 9007199254740991L, "哦"));
        List<LrcTimeline.Line> lines = new ArrayList<>();
        lines.add(new LrcTimeline.Line(0, 900, "前奏"));
        lines.add(new LrcTimeline.Line(1000, 9007199254740991L, "山哦", "", words));
        lines.add(new LrcTimeline.Line(3000, 2000, "尾声", "",
                java.util.Collections.emptyList()));
        LrcTimeline timeline = LrcTimeline.fromTimedLines(lines);
        List<LrcTimeline.Line> result = timeline.lines();
        assertEquals(3, result.size());
        assertEquals(900, result.get(0).durationMs);
        assertEquals(1400, result.get(1).durationMs);
        assertEquals(1000, result.get(1).words.get(1).durationMs);
        assertEquals(2000, result.get(2).durationMs);
    }

    @Test public void absurdLineStartsAreDropped() {
        List<LrcTimeline.Line> lines = new ArrayList<>();
        lines.add(new LrcTimeline.Line(1000, 900, "正常行"));
        lines.add(new LrcTimeline.Line(9007199254740991L, 900, "哨兵行"));
        LrcTimeline timeline = LrcTimeline.fromTimedLines(lines);
        assertEquals(1, timeline.lineCount());
        assertEquals("正常行", timeline.lines().get(0).text);
    }
}
