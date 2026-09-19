package com.zuoqirun.lyricscompanion;

/**
 * Detects a matched lyric timeline that will not scroll, so the live player lyric can take over
 * (issue #44).
 *
 * <p>Over Bluetooth AVRCP a catalog match occasionally succeeds and still stays on the first line
 * while the player keeps publishing its own scrolling lyric. The timeline is unusable then even
 * though it is not empty, and {@code MusicStateStore}'s existing live-lyric fallback — which only
 * covers "nothing matched" — never runs. This class marks such a timeline unusable.</p>
 *
 * <p>Two kinds of evidence:</p>
 * <ul>
 *   <li>Static: the match cannot scroll at all — a single line, or a total span above
 *       {@link #DURATION_RATIO}× the track duration (or below a third of it).</li>
 *   <li>Dynamic: while playing with the position advancing, the current line has not changed for
 *       {@link #STUCK_AFTER_MS}, and the live lyric changed at least
 *       {@link #MIN_LIVE_LYRIC_CHANGES} times inside that window — the player is scrolling and our
 *       timeline is the wrong one. Counting those changes also keeps pure music and instrumental
 *       breaks, where nothing scrolls at all, from triggering a fallback.</li>
 * </ul>
 *
 * <p>Reverse guard: when the live lyric itself stops updating (pure music, an AVRCP sender that
 * publishes its metadata once), the matched timeline is kept. The dynamic verdict sticks once
 * taken; {@link #reset()} — called on a track change, a source change or a backward seek — makes
 * the next playback judge again.</p>
 *
 * <p>Deliberately free of Android types so the rules are unit-testable: timestamps are passed in by
 * the caller instead of being read from {@code SystemClock}.</p>
 */
final class StuckLineDetector {
    /**
     * How long the current line may stay unchanged before it counts as stuck.
     *
     * <p>25 s: an ordinary lyric line lasts 3–6 s, so this easily clears a whole chorus and cannot
     * fire on a single long instrumental line, while staying inside the 20–30 s the issue
     * suggests.</p>
     */
    static final long STUCK_AFTER_MS = 25_000L;

    /**
     * How many live-lyric changes the stuck window has to contain.
     *
     * <p>Two changes prove the player is scrolling line by line instead of publishing a single
     * metadata update, and they still arrive quickly enough on a slow ballad.</p>
     */
    static final int MIN_LIVE_LYRIC_CHANGES = 2;

    /**
     * How long the live lyric may go unchanged before the player itself counts as stopped.
     *
     * <p>This is the reverse guard the issue asks for: pure music, and an AVRCP sender that
     * publishes one lyric line and stops, have to keep the matched timeline. 15 s is roughly three
     * ordinary lyric lines and still far below {@link #STUCK_AFTER_MS}, so a frozen live lyric can
     * never be mistaken for scrolling.</p>
     */
    static final long LIVE_LYRIC_STALE_MS = 15_000L;

    /** A timeline this many times longer than the track, or that much shorter, is a wrong match. */
    static final int DURATION_RATIO = 3;

    /** How far the position has to move inside one line before the clock counts as advancing. */
    static final long PROGRESS_ADVANCE_MS = 250L;

    /**
     * A backward jump larger than this is the user seeking. AVRCP positions are coarse and jitter
     * by a second or so, which must not be mistaken for a seek.
     */
    static final long SEEK_BACK_TOLERANCE_MS = 3_000L;

    /** One observation: the visible timeline line, the playback clock and the live lyric. */
    static final class Signal {
        /**
         * Identity of the line currently shown by the timeline. The line's start timestamp is used
         * because {@link LrcTimeline} exposes no line number: it is stable for as long as the line
         * is, and it is readable in a diagnostic log.
         */
        final long lineId;
        final long positionMs;
        final String liveLyric;
        final boolean playing;
        /** The track duration, or {@code -1} when the player does not publish one. */
        final long trackDurationMs;
        final long nowElapsedMs;

        private Signal(long lineId, long positionMs, String liveLyric, boolean playing,
                       long trackDurationMs, long nowElapsedMs) {
            this.lineId = lineId;
            this.positionMs = positionMs;
            this.liveLyric = liveLyric;
            this.playing = playing;
            this.trackDurationMs = trackDurationMs;
            this.nowElapsedMs = nowElapsedMs;
        }

        /** A frame that also carries the track duration, which the static verdict needs. */
        static Signal of(long lineId, long positionMs, String liveLyric, boolean playing,
                         long trackDurationMs, long nowElapsedMs) {
            return new Signal(lineId, positionMs, liveLyric, playing, trackDurationMs,
                    nowElapsedMs);
        }

        /** A frame without a track duration: the duration comparison is skipped. */
        static Signal of(long lineId, long positionMs, String liveLyric, boolean playing,
                         long nowElapsedMs) {
            return new Signal(lineId, positionMs, liveLyric, playing, -1L, nowElapsedMs);
        }
    }

    /** Lines in the matched timeline; 0 means nothing matched, which the old fallback covers. */
    private int lineCount;
    /** Total span of the timeline: the last line's start plus how long it is shown. */
    private long timelineSpanMs;
    /** Latest track duration seen, kept for the diagnostic message. */
    private long trackDurationMs = -1L;
    /** The match cannot scroll at all: one line, or a duration that does not fit the track. */
    private boolean clearlyUnusable;
    /** The live lyric proved the timeline wrong; sticky until the detection is reset. */
    private boolean stuck;
    /** Whether the verdict of this timeline has already been reported. */
    private boolean reported;
    private boolean hasLine;
    private long lineId;
    private long lineSinceMs;
    private long linePositionMs;
    private long lastPositionMs;
    private boolean hasLiveLyric;
    private String liveLyric = "";
    private int liveLyricChanges;
    private long lastLiveLyricChangeMs;
    private long stuckLineId;
    private long stuckElapsedMs;
    private int stuckLiveLyricChanges;

    /**
     * The timeline was replaced (or cleared): forget the previous line tracking and judge the new
     * one from scratch.
     */
    void onTimeline(int lineCount, long timelineSpanMs) {
        this.lineCount = Math.max(0, lineCount);
        this.timelineSpanMs = Math.max(0L, timelineSpanMs);
        this.trackDurationMs = -1L;
        this.clearlyUnusable = this.lineCount == 1;
        this.stuck = false;
        this.reported = false;
        clearLineTracking();
    }

    /**
     * Track change, source change or manual re-match: everything is dropped and the next playback
     * is judged again. Nothing is persisted.
     */
    void reset() {
        onTimeline(0, 0L);
    }

    /** Feeds one frame of playback and lyric state. */
    void onFrame(long lineId, long positionMs, String liveLyric, boolean playing,
                 long nowElapsedMs) {
        onFrame(Signal.of(lineId, positionMs, liveLyric, playing, nowElapsedMs));
    }

    /** Feeds one frame of playback and lyric state, together with the track duration. */
    void onFrame(long lineId, long positionMs, String liveLyric, boolean playing,
                 long trackDurationMs, long nowElapsedMs) {
        onFrame(Signal.of(lineId, positionMs, liveLyric, playing, trackDurationMs, nowElapsedMs));
    }

    /** Feeds one frame of playback and lyric state. */
    void onFrame(Signal signal) {
        if (signal == null || lineCount <= 0) return;
        long now = signal.nowElapsedMs;
        String currentLyric = signal.liveLyric == null ? "" : signal.liveLyric.trim();
        trackDurationMs = signal.trackDurationMs;
        // The track duration can arrive after the timeline does, so the static verdict is
        // recomputed on every frame instead of once at load time.
        clearlyUnusable = lineCount == 1
                || isDurationMismatch(timelineSpanMs, signal.trackDurationMs);

        if (hasLine && signal.positionMs + SEEK_BACK_TOLERANCE_MS < lastPositionMs) {
            // The user dragged the position back. Two things follow: the jump itself changes the
            // live lyric, which is not evidence of a scrolling player, and the stuck verdict is
            // void because the playback is being re-synchronised. Dropping the line tracking makes
            // the code below re-baseline both. Only the static verdict survives — a one-line
            // timeline stays a one-line timeline no matter where the position is.
            clearLineTracking();
            stuck = false;
            // A fresh epoch: if the timeline was rejected dynamically, the next verdict deserves
            // its own diagnostic line. A static verdict has already been reported and cannot change.
            reported = clearlyUnusable;
        }

        if (!hasLine || signal.lineId != lineId) {
            // A new line in the timeline: the stuck clock and the live-lyric counter start over,
            // so "two changes" always means two changes while this very line was on screen.
            hasLine = true;
            lineId = signal.lineId;
            lineSinceMs = now;
            linePositionMs = signal.positionMs;
            liveLyricChanges = 0;
        }

        if (!currentLyric.equals(liveLyric)) {
            if (hasLiveLyric && signal.playing && !currentLyric.isEmpty()) {
                // The player moved to another lyric line: evidence that it is scrolling, and the
                // measurement that stays at zero for pure music or a one-shot metadata broadcast.
                liveLyricChanges++;
                lastLiveLyricChangeMs = now;
            }
            liveLyric = currentLyric;
            hasLiveLyric = true;
        }

        if (!signal.playing) {
            // A pause is not a stuck line: restart both clocks so the paused stretch is never
            // counted. The live-lyric counter survives, so scrolling seen before the pause still
            // counts once playback resumes.
            lineSinceMs = now;
            linePositionMs = signal.positionMs;
        }
        lastPositionMs = signal.positionMs;

        if (!stuck && isStuck(signal, now, currentLyric)) {
            stuck = true;
            stuckLineId = lineId;
            stuckElapsedMs = now - lineSinceMs;
            stuckLiveLyricChanges = liveLyricChanges;
        }
    }

    /** Whether this timeline must not be used: the live lyric takes over instead. */
    boolean timelineUnusable() {
        return clearlyUnusable || stuck;
    }

    /**
     * Whether the unusable verdict still owes a diagnostic. Answers {@code true} once per timeline,
     * so the fallback is reported exactly once even though it is re-evaluated on every frame.
     */
    boolean consumeFallbackNotice() {
        if (reported || !timelineUnusable()) return false;
        reported = true;
        return true;
    }

    /** Whether the verdict came from the live lyric scrolling under a frozen timeline line. */
    boolean stuckByLiveLyric() {
        return stuck;
    }

    /** A one-line, log-friendly description of why the timeline was rejected. */
    String describe() {
        if (stuck) {
            // "lineIndex" would need a line number, which LrcTimeline does not expose; the line's
            // start timestamp identifies the same line just as unambiguously.
            return "lyric fallback reason=stuck lineStartMs=" + stuckLineId
                    + " elapsed=" + (stuckElapsedMs / 1_000L) + "s"
                    + " liveLyricChanges=" + stuckLiveLyricChanges
                    + " lines=" + lineCount;
        }
        if (lineCount == 1) {
            return "lyric fallback reason=single_line lines=1";
        }
        return "lyric fallback reason=duration_mismatch timelineSpanMs=" + timelineSpanMs
                + " trackDurationMs=" + trackDurationMs + " lines=" + lineCount;
    }

    /**
     * Whether a timeline span and a track duration are too far apart to be the same song.
     *
     * <p>Either side may be unknown ({@code <= 0}); the check is then skipped, because an unknown
     * duration is not evidence of a bad match.</p>
     */
    static boolean isDurationMismatch(long timelineSpanMs, long trackDurationMs) {
        if (timelineSpanMs <= 0L || trackDurationMs <= 0L) return false;
        return timelineSpanMs > trackDurationMs * DURATION_RATIO
                || timelineSpanMs * DURATION_RATIO < trackDurationMs;
    }

    /** The dynamic rule, spelled out in the order the issue lists it. */
    private boolean isStuck(Signal signal, long nowElapsedMs, String currentLyric) {
        if (!signal.playing) return false;
        if (!hasLine) return false;
        // The clock has to be running: a session that reports PLAYING while the position stands
        // still cannot tell a stuck line from a stuck player.
        if (signal.positionMs - linePositionMs < PROGRESS_ADVANCE_MS) return false;
        if (nowElapsedMs - lineSinceMs < STUCK_AFTER_MS) return false;
        // Nothing to fall back to.
        if (currentLyric.isEmpty()) return false;
        if (liveLyricChanges < MIN_LIVE_LYRIC_CHANGES) return false;
        // Reverse guard: the live lyric has to have moved recently, otherwise the player is the one
        // that stopped — pure music, or an AVRCP sender that published its metadata once.
        return nowElapsedMs - lastLiveLyricChangeMs <= LIVE_LYRIC_STALE_MS;
    }

    private void clearLineTracking() {
        hasLine = false;
        lineId = 0L;
        lineSinceMs = 0L;
        linePositionMs = 0L;
        lastPositionMs = 0L;
        hasLiveLyric = false;
        liveLyric = "";
        liveLyricChanges = 0;
        lastLiveLyricChangeMs = 0L;
        stuckLineId = 0L;
        stuckElapsedMs = 0L;
        stuckLiveLyricChanges = 0;
    }
}
