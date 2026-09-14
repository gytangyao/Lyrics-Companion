package com.zuoqirun.lyricscompanion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricDissolveEffectTest {
    private static final long ENDED_LINE_ID = 1_000L;
    private static final long CURRENT_LINE_ID = 2_000L;
    /** The line whose words are being erased in the 逐字歌词及时擦除 tests. */
    private static final long LINE_A = 3_000L;
    private static final long START_MS = 5_000L;
    /** Seven codepoints, so the schedule has something to stagger. */
    private static final String LINE = "上一句歌词文字";

    /** Primes a line, then ends it, leaving the effect mid-dissolve at {@code nowMs}. */
    private static LyricDissolveEffect effectAt(long nowMs) {
        LyricDissolveEffect effect = new LyricDissolveEffect();
        effect.sync(ENDED_LINE_ID, "", true, false, 0L);
        effect.sync(CURRENT_LINE_ID, LINE, true, false, START_MS);
        effect.sync(CURRENT_LINE_ID, LINE, true, false, nowMs);
        return effect;
    }

    @Test public void charactersFadeOneAfterAnotherFromTheLeft() {
        LyricDissolveEffect effect = effectAt(START_MS + LyricDissolveEffect.DURATION_MS / 2);
        int chars = LINE.codePointCount(0, LINE.length());
        float previous = -1f;
        int midFade = 0;
        for (int index = 0; index < chars; index++) {
            float alpha = effect.characterAlpha(ENDED_LINE_ID, index, chars);
            assertTrue("alpha must never fall towards the right", alpha >= previous);
            if (alpha > 0f && alpha < 1f) midFade++;
            previous = alpha;
        }
        assertTrue("the left glyph must be gone first",
                effect.characterAlpha(ENDED_LINE_ID, 0, chars) < .5f);
        assertTrue("the right glyph must still be there",
                effect.characterAlpha(ENDED_LINE_ID, chars - 1, chars) > .5f);
        // "One after another" means a couple of glyphs overlap, not the whole line at once.
        assertTrue("expected a narrow wavefront, " + midFade + " glyphs were mid-fade",
                midFade >= 2 && midFade <= 4);
    }

    @Test public void theWaveReachesTheRightEdgeAndEndsExactlyAtTheEnd() {
        long almostDone = START_MS + LyricDissolveEffect.DURATION_MS - 20L;
        LyricDissolveEffect effect = effectAt(almostDone);
        int chars = LINE.codePointCount(0, LINE.length());
        float last = effect.characterAlpha(ENDED_LINE_ID, chars - 1, chars);
        assertTrue("the last glyph must still be fading, not already finished", last > 0f);
        assertTrue("the last glyph must be nearly gone, was " + last, last < .2f);
    }

    @Test public void aDissolvedLineStaysGone() {
        int chars = LINE.codePointCount(0, LINE.length());
        LyricDissolveEffect effect = effectAt(START_MS + LyricDissolveEffect.DURATION_MS);
        for (int index = 0; index < chars; index++) {
            assertEquals(0f, effect.characterAlpha(ENDED_LINE_ID, index, chars), .02f);
        }
        effect.sync(CURRENT_LINE_ID, LINE, true, false,
                START_MS + LyricDissolveEffect.DURATION_MS + 10_000L);
        for (int index = 0; index < chars; index++) {
            assertEquals("a consumed line must not come back",
                    0f, effect.characterAlpha(ENDED_LINE_ID, index, chars), .02f);
        }
    }

    /**
     * The compact strip reveals the new line only to the left of the eraser. That clip must be
     * dropped once the old line is gone, or a new line that is longer than the old one keeps its
     * tail cut off at the old line's right edge for as long as the line stays current.
     */
    @Test public void aFinishedDissolveReportsNoVisibleGlyphsEvenThoughItStillAffectsTheLine() {
        int chars = LINE.codePointCount(0, LINE.length());
        LyricDissolveEffect effect = effectAt(START_MS + LyricDissolveEffect.DURATION_MS);
        assertTrue("the consumed line still belongs to the effect", effect.affects(ENDED_LINE_ID));
        assertFalse("nothing of it is left to hide the next line behind",
                effect.hasVisibleCharacter(ENDED_LINE_ID, chars));
    }

    @Test public void glyphsAreVisibleForTheWholeDissolve() {
        int chars = LINE.codePointCount(0, LINE.length());
        assertTrue("the untouched line is fully visible",
                effectAt(START_MS).hasVisibleCharacter(ENDED_LINE_ID, chars));
        assertTrue("the eraser has not passed the last glyph yet",
                effectAt(START_MS + LyricDissolveEffect.DURATION_MS - 20L)
                        .hasVisibleCharacter(ENDED_LINE_ID, chars));
    }

    @Test public void aConsumedLineNeverPopsBackWhenItMovesToAnOlderSlot() {
        LyricDissolveEffect effect = new LyricDissolveEffect();
        effect.sync(ENDED_LINE_ID, "", true, false, 0L);
        effect.sync(CURRENT_LINE_ID, LINE, true, false, START_MS);
        effect.sync(3_000L, LINE, true, false, START_MS + 2_000L);
        assertEquals("the line that just ended owns the dissolve", CURRENT_LINE_ID,
                effect.dissolvingLineId());
        assertTrue(effect.affects(ENDED_LINE_ID));
        assertEquals("the older line stays consumed in its older slot",
                0f, effect.characterAlpha(ENDED_LINE_ID, 0, 7), .0001f);
        assertEquals("the line dissolving now starts intact",
                1f, effect.characterAlpha(CURRENT_LINE_ID, 0, 7), .0001f);
        assertEquals("a line that never ended is untouched",
                1f, effect.characterAlpha(3_000L, 0, 7), .0001f);
    }

    @Test public void eachCharacterShedsItsDustOnceFromItsOwnGlyphBox() {
        long now = START_MS + 200L;
        LyricDissolveEffect effect = effectAt(now);
        int chars = 4;
        float boxLeft = 100f;
        float boxTop = 300f;
        float boxWidth = 22f;
        float boxHeight = 32f;
        float size = 32f;
        effect.characterAlpha(ENDED_LINE_ID, 0, chars);
        effect.emitFromCharacter(ENDED_LINE_ID, 0, boxLeft, boxTop, boxWidth, boxHeight, size,
                0xffffffff);
        int shed = effect.liveDust(now);
        assertTrue(shed > 0);
        int slot = firstLiveSlot(effect, now);
        assertTrue("dust must come off the glyph, not the line centre",
                effect.dustX(slot, now) >= boxLeft - 1f
                        && effect.dustX(slot, now) <= boxLeft + boxWidth + 1f);
        assertTrue(effect.dustY(slot, now) >= boxTop - size
                && effect.dustY(slot, now) <= boxTop + boxHeight + 1f);

        // Redrawing the same glyph on later frames must not shed a second burst.
        effect.emitFromCharacter(ENDED_LINE_ID, 0, boxLeft, boxTop, boxWidth, boxHeight, size,
                0xffffffff);
        assertEquals(shed, effect.liveDust(now));
        // A glyph that has not started fading must not shed anything yet.
        effect.emitFromCharacter(ENDED_LINE_ID, chars - 1, 500f, boxTop, boxWidth, boxHeight,
                size, 0xffffffff);
        assertEquals(shed, effect.liveDust(now));

        assertTrue("dust outlives the glyph that shed it",
                effect.liveDust(now + LyricDissolveEffect.DUST_LIFE_MS - 50L) > 0);
        assertEquals(0, effect.liveDust(now + LyricDissolveEffect.DUST_LIFE_MS + 50L));
    }

    @Test public void staleLinesNeverShedDust() {
        long now = START_MS + 200L;
        LyricDissolveEffect effect = effectAt(now);
        effect.characterAlpha(ENDED_LINE_ID, 0, 3);
        effect.emitFromCharacter(CURRENT_LINE_ID, 0, 10f, 20f, 30f, 40f, 30f, 0xffffffff);
        assertEquals(0, effect.liveDust(now));
    }

    @Test public void pausingAndScrubbingNeverStartADissolve() {
        LyricDissolveEffect effect = new LyricDissolveEffect();
        effect.sync(ENDED_LINE_ID, "", true, false, 0L);
        effect.sync(CURRENT_LINE_ID, LINE, false, false, 100L);
        assertEquals("a line change while paused must not dissolve",
                LyricDissolveEffect.UNKNOWN_LINE, effect.dissolvingLineId());
        effect.sync(ENDED_LINE_ID, LINE, true, false, 200L);
        assertEquals("a backward seek must not dissolve",
                LyricDissolveEffect.UNKNOWN_LINE, effect.dissolvingLineId());
        effect.sync(CURRENT_LINE_ID, "", true, false, 300L);
        assertEquals("an empty previous line has nothing to dissolve",
                LyricDissolveEffect.UNKNOWN_LINE, effect.dissolvingLineId());
        // With nothing in flight the panel must take its untouched draw path.
        assertFalse(effect.affects(CURRENT_LINE_ID));
        assertEquals(1f, effect.characterAlpha(CURRENT_LINE_ID, 0, 7), .0001f);
        assertFalse(effect.isAnimating(400L));
    }

    @Test public void pausingHoldsTheDissolveWhereItStopped() {
        long pausedAt = START_MS + 300L;
        LyricDissolveEffect effect = effectAt(pausedAt);
        int chars = LINE.codePointCount(0, LINE.length());
        // A glyph in the middle of the wavefront, so freezing is observable.
        int glyph = 2;
        float before = effect.characterAlpha(ENDED_LINE_ID, glyph, chars);
        assertTrue("this glyph should be mid-fade, was " + before, before > 0f && before < 1f);
        // Pausing stops the clock: glyphs hold their alpha rather than jumping or clearing.
        effect.sync(CURRENT_LINE_ID, LINE, false, false, pausedAt);
        assertEquals(before, effect.characterAlpha(ENDED_LINE_ID, glyph, chars), .0001f);
        effect.sync(CURRENT_LINE_ID, LINE, false, false, pausedAt + 5_000L);
        assertEquals("a pause must not let the line finish dissolving",
                before, effect.characterAlpha(ENDED_LINE_ID, glyph, chars), .0001f);
        // Resuming continues from where it stopped instead of jumping ahead.
        effect.sync(CURRENT_LINE_ID, LINE, true, false, pausedAt + 5_000L);
        assertEquals(before, effect.characterAlpha(ENDED_LINE_ID, glyph, chars), .0001f);
        effect.sync(CURRENT_LINE_ID, LINE, true, false, pausedAt + 5_100L);
        assertTrue(effect.characterAlpha(ENDED_LINE_ID, glyph, chars) < before);
    }

    @Test public void browsingNeverHidesALineTheUserScrolledTo() {
        // Play far enough that both earlier lines were consumed by dissolves.
        LyricDissolveEffect effect = new LyricDissolveEffect();
        effect.sync(ENDED_LINE_ID, "", true, false, 0L);
        effect.sync(CURRENT_LINE_ID, LINE, true, false, START_MS);
        effect.sync(3_000L, LINE, true, false, START_MS + 1_000L);
        effect.sync(3_000L, LINE, true, false, START_MS + 20_000L);
        assertEquals("both lines are consumed while playing",
                0f, effect.characterAlpha(ENDED_LINE_ID, 0, 7), .0001f);
        assertEquals(0f, effect.characterAlpha(CURRENT_LINE_ID, 0, 7), .0001f);

        // Now the user scrolls back through the lyrics.
        effect.sync(ENDED_LINE_ID, LINE, true, true, START_MS + 21_000L);
        assertTrue(effect.isBrowsing());
        assertFalse(effect.affects(ENDED_LINE_ID));
        assertEquals("a consumed line must be fully drawn while browsing",
                1f, effect.characterAlpha(ENDED_LINE_ID, 0, 7), .0001f);
        assertEquals(1f, effect.characterAlpha(CURRENT_LINE_ID, 0, 7), .0001f);
        assertEquals(0, effect.liveDust(START_MS + 21_000L));
    }

    @Test public void leavingABrowseDoesNotConsumeWhateverWasScrolledTo() {
        LyricDissolveEffect effect = new LyricDissolveEffect();
        effect.sync(ENDED_LINE_ID, "", true, false, 0L);
        effect.sync(CURRENT_LINE_ID, LINE, true, true, 100L);
        effect.sync(5_000L, LINE, true, true, 200L);
        assertEquals("scrolling must not start a dissolve",
                LyricDissolveEffect.UNKNOWN_LINE, effect.dissolvingLineId());
        // Leaving the browse on a different line is not a line ending either.
        effect.sync(CURRENT_LINE_ID, LINE, true, false, 300L);
        assertEquals(LyricDissolveEffect.UNKNOWN_LINE, effect.dissolvingLineId());
        assertEquals(1f, effect.characterAlpha(5_000L, 0, 7), .0001f);
        // A real forward change after that dissolves normally.
        effect.sync(6_000L, LINE, true, false, 400L);
        assertEquals(CURRENT_LINE_ID, effect.dissolvingLineId());
        assertTrue(effect.affects(CURRENT_LINE_ID));
    }

    @Test public void aWordIsErasedOnlyOnceItHasBeenSung() {
        LyricDissolveEffect effect = new LyricDissolveEffect();
        effect.syncWordErase(LINE_A, "", 1, true, true, 1_000L);
        assertFalse("nothing sung yet", effect.isErasingWords(LINE_A));
        assertEquals(1f, effect.currentLineAlpha(LINE_A, 0), .0001f);

        effect.syncWordErase(LINE_A, "上海", 1, true, true, 2_000L);
        assertEquals(2, effect.sungUnits(LINE_A));
        // Neither unit has had time to go yet.
        assertEquals(1f, effect.currentLineAlpha(LINE_A, 0), .0001f);
        // Half a fade later both are in the air, and the left one leads.
        effect.syncWordErase(LINE_A, "上海", 1, true, true, 2_000L + 100L);
        float first = effect.currentLineAlpha(LINE_A, 0);
        float second = effect.currentLineAlpha(LINE_A, 1);
        assertTrue("the left unit must lead the one after it", first < second);
        assertTrue(first > 0f && first < 1f);
        assertTrue(second > 0f && second < 1f);
        // A unit that has not been sung yet must never be touched.
        assertEquals(1f, effect.currentLineAlpha(LINE_A, 2), .0001f);
        // Once the fade is over the units are gone for good.
        effect.syncWordErase(LINE_A, "上海", 1, true, true, 2_000L + LyricDissolveEffect.WORD_FADE_MS + 200L);
        assertEquals(0f, effect.currentLineAlpha(LINE_A, 0), .0001f);
        assertEquals(0f, effect.currentLineAlpha(LINE_A, 1), .0001f);
        assertFalse(effect.isEraseAnimating(LINE_A));
    }

    @Test public void eachSungUnitShedsItsDustOnce() {
        LyricDissolveEffect effect = new LyricDissolveEffect();
        effect.syncWordErase(LINE_A, "上海", 1, true, true, 1_000L);
        effect.syncWordErase(LINE_A, "上海", 1, true, true, 1_150L);
        assertTrue(effect.isEraseAnimating(LINE_A));
        effect.emitFromCurrentLine(LINE_A, 0, 100f, 300f, 20f, 30f, 30f, 0xffffffff);
        int shed = effect.liveDust(1_150L);
        assertTrue(shed > 0);
        // Later frames redraw the same unit: no second burst.
        effect.emitFromCurrentLine(LINE_A, 0, 100f, 300f, 20f, 30f, 30f, 0xffffffff);
        assertEquals(shed, effect.liveDust(1_150L));
        // A unit that has not been sung must not shed anything.
        effect.emitFromCurrentLine(LINE_A, 4, 500f, 300f, 20f, 30f, 30f, 0xffffffff);
        assertEquals(shed, effect.liveDust(1_150L));
    }

    @Test public void seekingBackInsideTheLineReArmsTheErase() {
        LyricDissolveEffect effect = new LyricDissolveEffect();
        effect.syncWordErase(LINE_A, "上海人", 1, true, true, 1_000L);
        effect.syncWordErase(LINE_A, "上海人", 1, true, true, 2_000L);
        assertEquals(0f, effect.currentLineAlpha(LINE_A, 2), .0001f);
        // Seek back: the third unit is no longer sung, so it has to be visible again.
        effect.syncWordErase(LINE_A, "上", 1, true, true, 3_000L);
        assertEquals(1, effect.sungUnits(LINE_A));
        assertEquals(1f, effect.currentLineAlpha(LINE_A, 2), .0001f);
        // Playing on re-arms it with a fresh fade rather than an instant disappearance.
        effect.syncWordErase(LINE_A, "上海", 1, true, true, 4_000L);
        assertEquals(1f, effect.currentLineAlpha(LINE_A, 1), .0001f);
        effect.syncWordErase(LINE_A, "上海", 1, true, true, 4_200L);
        assertTrue(effect.currentLineAlpha(LINE_A, 1) < 1f);
    }

    @Test public void aNewLineStartsWhollyVisible() {
        LyricDissolveEffect effect = new LyricDissolveEffect();
        effect.sync(ENDED_LINE_ID, "", true, false, 0L);
        effect.syncWordErase(ENDED_LINE_ID, "上海", 2, true, true, 100L);
        effect.syncWordErase(ENDED_LINE_ID, "上海", 2, true, true, 900L);
        assertEquals(0f, effect.currentLineAlpha(ENDED_LINE_ID, 0), .0001f);
        // The next line erases on its own clock and inherits nothing from the line before it.
        effect.sync(CURRENT_LINE_ID, LINE, true, false, 1_000L);
        effect.syncWordErase(CURRENT_LINE_ID, "", LINE.length(), true, true, 1_000L);
        assertEquals(0, effect.sungUnits(CURRENT_LINE_ID));
        assertEquals(1f, effect.currentLineAlpha(CURRENT_LINE_ID, 0), .0001f);
        assertFalse("the line that left no longer owns the erase",
                effect.isErasingWords(ENDED_LINE_ID));
    }

    @Test public void scrollingNeverRestoresTheLineBeingSung() {
        LyricDissolveEffect effect = new LyricDissolveEffect();
        effect.syncWordErase(LINE_A, "上海", 8, true, true, 1_000L);
        effect.syncWordErase(LINE_A, "上海", 8, true, true, 1_500L);
        assertEquals(0f, effect.currentLineAlpha(LINE_A, 0), .0001f);

        // The user scrolls through the sheet. The playing line keeps its eaten words — restoring
        // them and eating them again is exactly the flash this used to show.
        effect.sync(CURRENT_LINE_ID, LINE, true, true, 1_600L);
        assertTrue(effect.isErasingWords(LINE_A));
        assertEquals("the line being sung stays erased while scrolling",
                0f, effect.currentLineAlpha(LINE_A, 0), .0001f);

        // A line the user scrolled to is a different identity, so it is drawn whole: no holes.
        assertFalse(effect.isErasingWords(ENDED_LINE_ID));
        assertEquals(1f, effect.currentLineAlpha(ENDED_LINE_ID, 0), .0001f);
    }

    @Test public void framesWithoutWordTimingHoldTheErase() {
        LyricDissolveEffect effect = new LyricDissolveEffect();
        effect.syncWordErase(LINE_A, "上海", 8, true, true, 1_000L);
        effect.syncWordErase(LINE_A, "上海", 8, true, true, 1_500L);
        assertEquals(0f, effect.currentLineAlpha(LINE_A, 0), .0001f);
        // The line runs out of words before the next one starts: the timeline drops its word
        // timings for that gap. The erased words must stay erased through it.
        effect.syncWordErase(LINE_A, "", 8, true, false, 2_000L);
        assertTrue(effect.isErasingWords(LINE_A));
        assertEquals(2, effect.sungUnits(LINE_A));
        assertEquals(0f, effect.currentLineAlpha(LINE_A, 0), .0001f);
        // Turning the feature off is the one thing that does give the words back.
        effect.syncWordErase(LINE_A, "", 8, false, false, 2_100L);
        assertFalse(effect.isErasingWords(LINE_A));
        assertEquals(1f, effect.currentLineAlpha(LINE_A, 0), .0001f);
    }

    @Test public void pausingHoldsTheEraseWhereItWas() {
        LyricDissolveEffect effect = new LyricDissolveEffect();
        effect.sync(ENDED_LINE_ID, "", true, false, 0L);
        effect.syncWordErase(LINE_A, "上海", 8, true, true, 1_000L);
        effect.syncWordErase(LINE_A, "上海", 8, true, true, 1_100L);
        float mid = effect.currentLineAlpha(LINE_A, 0);
        assertTrue(mid > 0f && mid < 1f);
        effect.sync(ENDED_LINE_ID, "", false, false, 1_100L);
        assertEquals(mid, effect.currentLineAlpha(LINE_A, 0), .0001f);
        effect.sync(ENDED_LINE_ID, "", false, false, 9_000L);
        assertEquals(mid, effect.currentLineAlpha(LINE_A, 0), .0001f);
    }

    @Test public void aLineAlreadyEatenByTheWordEraseDoesNotComeBack() {
        LyricDissolveEffect effect = new LyricDissolveEffect();
        effect.sync(ENDED_LINE_ID, "", true, false, 0L);
        effect.syncWordErase(ENDED_LINE_ID, LINE, LINE.length(), true, true, 100L);
        effect.syncWordErase(ENDED_LINE_ID, LINE, LINE.length(), true, true, 5_000L);
        // The line ends. It has nothing left, so it must stay gone instead of dissolving again.
        effect.sync(CURRENT_LINE_ID, LINE, true, false, 5_100L);
        assertEquals(LyricDissolveEffect.UNKNOWN_LINE, effect.dissolvingLineId());
        assertEquals("an eaten line must not be repainted by the end-of-line dissolve",
                0f, effect.characterAlpha(ENDED_LINE_ID, 0, 7), .0001f);
        assertTrue(effect.affects(ENDED_LINE_ID));
    }

    @Test public void theParticleAmountScalesHowMuchDustIsShed() {
        int designed = dustFromOneUnit(100);
        int heavy = dustFromOneUnit(300);
        int light = dustFromOneUnit(20);
        assertTrue("100% must still shed dust", designed > 0);
        assertTrue("300% must shed more than 100%: " + heavy + " vs " + designed,
                heavy > designed);
        assertTrue("20% must shed less than 100%: " + light + " vs " + designed,
                light < designed);
    }

    private static int dustFromOneUnit(int amountPercent) {
        LyricDissolveEffect effect = new LyricDissolveEffect();
        effect.setParticleAmount(amountPercent);
        // One unit on a one-character line: the density then depends only on the amount.
        effect.syncWordErase(LINE_A, "上", 1, true, true, 1_000L);
        effect.syncWordErase(LINE_A, "上", 1, true, true, 1_100L);
        effect.emitFromCurrentLine(LINE_A, 0, 0f, 0f, 10f, 10f, 10f, 0xffffffff);
        return effect.liveDust(1_100L);
    }

    private static int firstLiveSlot(LyricDissolveEffect effect, long nowMs) {
        for (int slot = 0; slot < LyricDissolveEffect.DUST_CAPACITY; slot++) {
            if (effect.dustAlive(slot, nowMs)) return slot;
        }
        throw new AssertionError("no dust in the air");
    }
}
