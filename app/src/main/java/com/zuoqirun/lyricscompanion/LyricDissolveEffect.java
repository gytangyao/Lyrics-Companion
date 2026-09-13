package com.zuoqirun.lyricscompanion;

import java.util.Arrays;

/**
 * Dissolve state for the lyric line that just ended.
 *
 * <p>The line is never wiped as a whole. Every character owns a slice of the timeline, so the
 * leftmost glyph starts coming apart first and the wave travels right, with only a couple of
 * characters fading at any moment. A character that starts fading also sheds its own dust,
 * emitted from the glyph box the panel reports while it draws, and that dust outlives the
 * glyph that produced it.
 *
 * <p>The panel keeps drawing the previous line for many frames, and in the layouts that show a
 * window of lines the same text later moves to an older slot. So the effect also remembers
 * which line identities it has consumed: a line that finished dissolving reports alpha 0
 * forever instead of popping back one slot later.
 */
final class LyricDissolveEffect {
    /** Identity for call sites that only ever draw the single most recent line. */
    static final long UNKNOWN_LINE = Long.MIN_VALUE;
    static final long DURATION_MS = 600L;
    static final long DUST_LIFE_MS = 520L;
    /** How long one already-sung unit takes to let go in "逐字歌词及时擦除". */
    static final long WORD_FADE_MS = 260L;
    /** Left-to-right stagger inside a word that finishes all at once. */
    private static final long WORD_STAGGER_MS = 45L;
    static final int DUST_CAPACITY = 384;
    /** The density the effect was designed around; the user's amount is a share of it. */
    private static final int DUST_BUDGET = 192;
    /** Glyphs below this alpha are invisible, so the draw call can be skipped. */
    static final float VISIBLE_ALPHA = .006f;
    /** How many characters may be mid-fade at once; keeps the wave readable on long lines. */
    private static final float OVERLAP_CHARS = 2.5f;
    private static final int CHAR_CAPACITY = 256;
    private static final int DISSOLVED_MEMORY = 8;
    private static final int DUST_PER_CHAR_MIN = 2;
    private static final int DUST_PER_CHAR_MAX = 10;

    private final long[] dissolvedLines = new long[DISSOLVED_MEMORY];
    private final boolean[] dustedChars = new boolean[CHAR_CAPACITY];
    /** Per UTF-16 unit of the current line: when it was sung, and whether it has shed dust. */
    private final long[] sungAtMs = new long[CHAR_CAPACITY];
    private final boolean[] sungDusted = new boolean[CHAR_CAPACITY];
    private int sungUnits;
    private int wordLineChars = 1;
    private boolean wordEraseOn;
    /** The one line identity the per-word erase belongs to; it is never applied to another. */
    private long eraseLineId = UNKNOWN_LINE;
    private int amountPercent = 100;
    private final float[] dustX = new float[DUST_CAPACITY];
    private final float[] dustY = new float[DUST_CAPACITY];
    private final float[] dustDriftX = new float[DUST_CAPACITY];
    private final float[] dustDriftY = new float[DUST_CAPACITY];
    private final float[] dustRadius = new float[DUST_CAPACITY];
    private final float[] dustGlyphSize = new float[DUST_CAPACITY];
    private final int[] dustColor = new int[DUST_CAPACITY];
    private final long[] dustBornMs = new long[DUST_CAPACITY];
    private int dustCursor;
    private long dustSerial;
    private long lineStartMs = Long.MIN_VALUE;
    private long dissolvingLineId = UNKNOWN_LINE;
    private long startedAtMs;
    private long frozenAtMs = -1L;
    private long frameNowMs;
    private boolean browsing;
    private int scheduleChars;
    private float charStep;
    private float charFade;
    private boolean scheduleReady;

    LyricDissolveEffect() {
        Arrays.fill(dissolvedLines, UNKNOWN_LINE);
        Arrays.fill(dustBornMs, Long.MIN_VALUE);
    }

    /**
     * Tracks which line is current. A forward line change starts the dissolve of the line that
     * just ended; a backward seek, a change while paused, or the frame that leaves a freeze just
     * re-bases the timeline, so scrubbing through lyrics never starts or consumes anything.
     */
    void sync(long currentLineStartMs, String previousLine, boolean playing, boolean browsing,
              long nowMs) {
        frameNowMs = nowMs;
        this.browsing = browsing;
        boolean frozen = !playing || browsing;
        boolean resumed = false;
        if (frozen) {
            if (frozenAtMs < 0L) frozenAtMs = nowMs;
        } else if (frozenAtMs >= 0L) {
            startedAtMs += nowMs - frozenAtMs;
            frozenAtMs = -1L;
            resumed = true;
        }
        if (currentLineStartMs < 0L) return;
        if (lineStartMs == Long.MIN_VALUE || frozen || resumed || currentLineStartMs <= lineStartMs) {
            lineStartMs = currentLineStartMs;
            return;
        }
        long endedLineId = lineStartMs;
        lineStartMs = currentLineStartMs;
        // 逐字歌词及时擦除 has already eaten this line word by word while it played. Handing it
        // to the end-of-line dissolve would paint it back for the length of that dissolve.
        boolean alreadyErased = wordEraseOn && eraseLineId == endedLineId && sungUnits > 0;
        if (previousLine == null || previousLine.isEmpty()) return;
        if (alreadyErased) {
            rememberDissolved(dissolvingLineId);
            rememberDissolved(endedLineId);
            dissolvingLineId = UNKNOWN_LINE;
            scheduleReady = false;
            return;
        }
        rememberDissolved(dissolvingLineId);
        dissolvingLineId = endedLineId;
        scheduleReady = false;
        scheduleChars = 0;
        Arrays.fill(dustedChars, false);
        clearDust();
        startedAtMs = nowMs;
        frozenAtMs = -1L;
    }

    /** Dust density as a percentage of the designed amount. */
    void setParticleAmount(int percent) {
        amountPercent = Math.max(20, Math.min(300, percent));
    }

    /** Drops the previous-line dissolve. The per-word erase is left alone: it is driven by its
     * own preference every frame, and the two can be switched independently.
     */
    void reset() {
        dissolvingLineId = UNKNOWN_LINE;
        lineStartMs = Long.MIN_VALUE;
        scheduleReady = false;
        scheduleChars = 0;
        Arrays.fill(dissolvedLines, UNKNOWN_LINE);
        Arrays.fill(dustedChars, false);
    }

    /**
     * Advances the per-word erase of the line that is being sung. Every UTF-16 unit that has just
     * been sung gets its own fade clock, staggered left to right inside the word, so a word lets
     * go as a short sweep instead of blinking out.
     *
     * <p>The erase belongs to one line identity and is only ever applied to that line, which is
     * what lets it survive scrolling: the user reading an older line sees it whole, while the
     * playing line stays eaten away instead of being restored and re-eaten on every frame the
     * timeline loses its word timings.
     */
    void syncWordErase(long lineId, String completedLyric, int lineChars, boolean featureOn,
                       boolean timed, long nowMs) {
        frameNowMs = nowMs;
        wordEraseOn = featureOn;
        if (!featureOn) {
            clearErase();
            return;
        }
        if (lineId < 0L) return;
        if (lineId != eraseLineId) {
            // A different line is being sung now. The one that left keeps its eaten look through
            // the dissolve memory, so there is nothing to carry over here.
            eraseLineId = lineId;
            clearErase();
        }
        if (!timed) return;
        wordLineChars = Math.max(1, lineChars);
        int sung = completedLyric == null ? 0
                : Math.min(completedLyric.length(), CHAR_CAPACITY);
        if (sung <= sungUnits) {
            // A seek back inside the line re-arms the units that are no longer sung.
            if (sung < sungUnits) sungUnits = sung;
            return;
        }
        int added = sung - sungUnits;
        for (int unit = sungUnits; unit < sung; unit++) {
            sungAtMs[unit] = nowMs + Math.round((float) WORD_STAGGER_MS
                    * (unit - sungUnits) / Math.max(1, added));
            sungDusted[unit] = false;
        }
        sungUnits = sung;
    }

    /** True while this exact line is being eaten away word by word. */
    boolean isErasingWords(long lineId) {
        return wordEraseOn && lineId == eraseLineId && sungUnits > 0;
    }

    /** How many UTF-16 units of this line have been sung. */
    int sungUnits(long lineId) { return isErasingWords(lineId) ? sungUnits : 0; }

    /** 1 while this unit is still on screen, 0 once it has been erased. */
    float currentLineAlpha(long lineId, int unit) {
        if (!isErasingWords(lineId) || unit < 0 || unit >= sungUnits) return 1f;
        return 1f - clamp01((clockMs() - sungAtMs[unit]) / (float) WORD_FADE_MS);
    }

    /** True while some already-sung unit of this line is still in its fade window. */
    boolean isEraseAnimating(long lineId) {
        if (!isErasingWords(lineId)) return false;
        int first = firstVisibleUnit(lineId, 0, sungUnits);
        for (int unit = first; unit < sungUnits; unit++) {
            float alpha = currentLineAlpha(lineId, unit);
            if (alpha > 0f && alpha < 1f) return true;
        }
        return false;
    }

    /** First unit at or after {@code from} that is still visible, else {@code upTo}. */
    int firstVisibleUnit(long lineId, int from, int upTo) {
        int end = Math.min(upTo, sungUnits(lineId));
        for (int unit = Math.max(0, from); unit < end; unit++) {
            if (currentLineAlpha(lineId, unit) > VISIBLE_ALPHA) return unit;
        }
        return end;
    }

    /** Dust for a unit of the line being sung, shed once as that unit comes apart. */
    void emitFromCurrentLine(long lineId, int unit, float left, float top, float width,
                             float height, float textSize, int color) {
        if (!isErasingWords(lineId) || unit < 0 || unit >= CHAR_CAPACITY) return;
        if (unit >= sungUnits || sungDusted[unit]) return;
        if (currentLineAlpha(lineId, unit) >= 1f) return;
        sungDusted[unit] = true;
        spawnDust(left, top, width, height, textSize, color, wordLineChars);
    }

    private void clearErase() {
        sungUnits = 0;
        Arrays.fill(sungDusted, false);
    }

    /** Frozen clock, so a pause also holds the per-word erase where it was. */
    private long clockMs() {
        return frozenAtMs >= 0L ? frozenAtMs : frameNowMs;
    }

    /** True while glyphs are still fading or dust is still in the air. */
    boolean isAnimating(long nowMs) {
        if (browsing) return false;
        if (dissolvingLineId != UNKNOWN_LINE && scheduleReady && progress(nowMs) < 1f) return true;
        for (int slot = 0; slot < DUST_CAPACITY; slot++) {
            if (dustAlive(slot, nowMs)) return true;
        }
        return false;
    }

    float progress(long nowMs) {
        return Math.max(0f, Math.min(1f, ageMs(nowMs) / (float) DURATION_MS));
    }

    long dissolvingLineId() { return dissolvingLineId; }

    /**
     * True when this line is either dissolving right now or was already consumed. Call sites
     * use it to keep the untouched fast path whenever no dissolve is in flight.
     */
    boolean affects(long lineId) {
        if (browsing) return false;
        long id = resolve(lineId);
        return id != UNKNOWN_LINE && (id == dissolvingLineId || isRememberedDissolved(id));
    }

    /** True while the user is scrolling through lyrics; the panel draws every line intact. */
    boolean isBrowsing() { return browsing; }

    /**
     * Alpha for one glyph of the line the panel is about to draw. 1 means draw it normally,
     * 0 means it is gone for good, anything between is mid-dissolve.
     */
    float characterAlpha(long lineId, int charIndex, int charCount) {
        // Browsing shows the lyric sheet as it is: a line that was consumed during playback
        // must not be missing, or scrolling back would leave holes in the lyrics.
        if (browsing) return 1f;
        long id = resolve(lineId);
        if (id == UNKNOWN_LINE) return 1f;
        if (id != dissolvingLineId) return isRememberedDissolved(id) ? 0f : 1f;
        prepareSchedule(charCount);
        return 1f - characterProgress(charIndex);
    }

    /**
     * The panel reports every glyph box while drawing the dissolving line. The first frame a
     * glyph is seen fading it sheds its dust; later frames only come back for the box.
     */
    void emitFromCharacter(long lineId, int charIndex, float left, float top, float width,
                           float height, float textSize, int color) {
        long id = resolve(lineId);
        if (browsing || id == UNKNOWN_LINE || id != dissolvingLineId) return;
        if (charIndex < 0 || charIndex >= CHAR_CAPACITY || charIndex >= scheduleChars) return;
        if (dustedChars[charIndex]) return;
        if (characterProgress(charIndex) <= 0f) return;
        dustedChars[charIndex] = true;
        spawnDust(left, top, width, height, textSize, color, scheduleChars);
    }

    int liveDust(long nowMs) {
        int live = 0;
        for (int slot = 0; slot < DUST_CAPACITY; slot++) {
            if (dustAlive(slot, nowMs)) live++;
        }
        return live;
    }

    boolean dustAlive(int slot, long nowMs) {
        return dustBornMs[slot] != Long.MIN_VALUE && nowMs - dustBornMs[slot] < DUST_LIFE_MS;
    }

    float dustX(int slot, long nowMs) {
        return dustX[slot] + dustDriftX[slot] * dustProgress(slot, nowMs);
    }

    float dustY(int slot, long nowMs) {
        float local = dustProgress(slot, nowMs);
        return dustY[slot] + dustDriftY[slot] * local
                + dustGlyphSize[slot] * .30f * local * local;
    }

    float dustRadius(int slot, long nowMs) {
        return dustRadius[slot] * (1f - dustProgress(slot, nowMs) * .55f);
    }

    int dustAlpha(int slot, long nowMs) {
        return Math.round((1f - dustProgress(slot, nowMs)) * 210f);
    }

    int dustColor(int slot) { return dustColor[slot]; }

    private float characterProgress(int charIndex) {
        if (!scheduleReady) return 1f;
        int slot = Math.max(0, Math.min(charIndex, scheduleChars - 1));
        return clamp01((progress(frameNowMs) - slot * charStep) / charFade);
    }

    /**
     * A call site without a line identity only ever draws the most recent line, so it resolves
     * to whatever the effect is dissolving right now.
     */
    private long resolve(long lineId) {
        return lineId == UNKNOWN_LINE ? dissolvingLineId : lineId;
    }

    /** The schedule follows the text the panel actually draws, which may be ellipsized. */
    private void prepareSchedule(int charCount) {
        if (scheduleReady) return;
        scheduleChars = Math.max(1, Math.min(CHAR_CAPACITY, charCount));
        // The last glyph has to finish exactly at the end of the timeline.
        charStep = 1f / (scheduleChars + OVERLAP_CHARS - 1f);
        charFade = OVERLAP_CHARS * charStep;
        scheduleReady = true;
    }

    private void spawnDust(float left, float top, float width, float height, float textSize,
                           int color, int lineChars) {
        float size = Math.max(1f, textSize);
        // 100% reproduces the designed density exactly; the user's setting scales around it.
        int baseline = Math.max(DUST_PER_CHAR_MIN,
                Math.min(DUST_PER_CHAR_MAX, DUST_BUDGET / Math.max(1, lineChars)));
        int perChar = Math.max(1, Math.round(baseline * amountPercent / 100f));
        float glyphWidth = width > 0f ? width : size * .6f;
        float glyphHeight = height > 0f ? height : size;
        for (int index = 0; index < perChar; index++) {
            int slot = dustCursor;
            dustCursor = (dustCursor + 1) % DUST_CAPACITY;
            long seed = dustSerial++;
            dustX[slot] = left + glyphWidth * noise(seed, 1);
            dustY[slot] = top + glyphHeight * (.10f + noise(seed, 2) * .75f);
            dustDriftX[slot] = (noise(seed, 3) - .5f) * size;
            dustDriftY[slot] = -(0.30f + noise(seed, 4) * .95f) * size;
            dustRadius[slot] = (.020f + noise(seed, 5) * .045f) * size;
            dustGlyphSize[slot] = size;
            dustColor[slot] = color;
            dustBornMs[slot] = frameNowMs;
        }
    }

    private void clearDust() {
        Arrays.fill(dustBornMs, Long.MIN_VALUE);
        dustCursor = 0;
    }

    private void rememberDissolved(long lineId) {
        if (lineId == UNKNOWN_LINE || isRememberedDissolved(lineId)) return;
        for (int index = 0; index < DISSOLVED_MEMORY; index++) {
            if (dissolvedLines[index] == UNKNOWN_LINE) {
                dissolvedLines[index] = lineId;
                return;
            }
        }
        // Full: drop the oldest, which is also the line furthest from being redrawn.
        System.arraycopy(dissolvedLines, 1, dissolvedLines, 0, DISSOLVED_MEMORY - 1);
        dissolvedLines[DISSOLVED_MEMORY - 1] = lineId;
    }

    private boolean isRememberedDissolved(long lineId) {
        for (int index = 0; index < DISSOLVED_MEMORY; index++) {
            if (dissolvedLines[index] == lineId) return true;
        }
        return false;
    }

    private float dustProgress(int slot, long nowMs) {
        if (dustBornMs[slot] == Long.MIN_VALUE) return 0f;
        return Math.max(0f, Math.min(1f, (nowMs - dustBornMs[slot]) / (float) DUST_LIFE_MS));
    }

    private static float noise(long seed, int salt) {
        int value = (int) (seed * 1103515245L + salt * 12345L);
        value ^= value >>> 15;
        value *= 0x2545F49;
        value ^= value >>> 13;
        return (value & 0xffff) / 65535f;
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : Math.min(value, 1f);
    }

    private long ageMs(long nowMs) {
        long reference = frozenAtMs >= 0L ? frozenAtMs : nowMs;
        return Math.max(0L, reference - startedAtMs);
    }
}
