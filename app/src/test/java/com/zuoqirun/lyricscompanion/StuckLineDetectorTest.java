package com.zuoqirun.lyricscompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.function.LongFunction;
import java.util.function.LongUnaryOperator;

import org.junit.Test;

/**
 * Covers the "matched but it never scrolls" fallback of issue #44: a timeline that stays on one
 * line while the player keeps publishing its own scrolling lyric has to be given up, while a player
 * that stopped on its own (pure music, a single AVRCP metadata broadcast) must keep it.
 *
 * <p>The detector is driven frame by frame the way {@code MusicStateStore} feeds it: one frame per
 * second, positions advancing with the clock unless a scenario says otherwise.</p>
 */
public class StuckLineDetectorTest {
    private static final long FRAME_MS = 1_000L;
    /** 四分钟的歌曲。 */
    private static final long TRACK_MS = 240_000L;
    /** 普通歌词一行大约四秒。 */
    private static final long LINE_MS = 4_000L;
    /** 播放器实时歌词大约五秒换一句。 */
    private static final long LYRIC_MS = 5_000L;

    /** 匹配到的时间轴卡在第一句：行身份永远不变。 */
    private static final LongUnaryOperator STUCK_FIRST_LINE = now -> 0L;
    /** 正常滚动的时间轴：每四秒换一行。 */
    private static final LongUnaryOperator SCROLLING_LINES = now -> now / LINE_MS * LINE_MS;
    /** 播放器实时歌词正常滚动。 */
    private static final LongFunction<String> SCROLLING_LIVE_LYRIC =
            now -> "实时第 " + (now / LYRIC_MS) + " 句";

    @Test public void normalScrollingIsNeverTakenForAStuckLine() {
        StuckLineDetector detector = new StuckLineDetector();
        detector.onTimeline(60, TRACK_MS);

        feed(detector, 90_000L, SCROLLING_LINES, SCROLLING_LIVE_LYRIC);

        assertFalse("时间轴自己在滚动就不能回退", detector.timelineUnusable());
        assertFalse(detector.consumeFallbackNotice());
    }

    @Test public void aFrozenLineWithAScrollingLiveLyricFallsBack() {
        StuckLineDetector detector = new StuckLineDetector();
        detector.onTimeline(60, TRACK_MS);

        feed(detector, 24_000L, STUCK_FIRST_LINE, SCROLLING_LIVE_LYRIC);
        assertFalse("还差一秒到阈值", detector.timelineUnusable());

        feed(detector, 25_000L, 30_000L, STUCK_FIRST_LINE, now -> now, SCROLLING_LIVE_LYRIC,
                true, TRACK_MS);

        assertTrue(detector.timelineUnusable());
        assertTrue("判定来自实时歌词在滚动", detector.stuckByLiveLyric());
        String description = detector.describe();
        assertTrue(description, description.startsWith("lyric fallback reason=stuck"));
        assertTrue(description, description.contains("lineStartMs=0"));
        assertTrue(description, description.contains("elapsed=25s"));
        assertTrue(description, description.contains("liveLyricChanges=5"));
        assertTrue(detector.consumeFallbackNotice());
        assertFalse("同一条时间轴只写一条诊断", detector.consumeFallbackNotice());
    }

    @Test public void aLiveLyricThatStopsUpdatingKeepsTheMatchedTimeline() {
        StuckLineDetector detector = new StuckLineDetector();
        detector.onTimeline(60, TRACK_MS);

        // 前四秒发了两句就不再更新：纯音乐，或者 AVRCP 只发了一次元数据。
        feed(detector, 60_000L, STUCK_FIRST_LINE,
                now -> now < 6_000L ? "实时第 " + (now / 2_000L) + " 句" : "实时第 2 句");

        assertFalse("实时歌词自己停了，保持原时间轴", detector.timelineUnusable());
        assertFalse(detector.consumeFallbackNotice());
    }

    @Test public void pureMusicWithoutALiveLyricIsNeverGivenUp() {
        StuckLineDetector detector = new StuckLineDetector();
        detector.onTimeline(60, TRACK_MS);

        feed(detector, 90_000L, STUCK_FIRST_LINE, now -> "");

        assertFalse(detector.timelineUnusable());
    }

    @Test public void aSingleLineTimelineIsUnusableWithoutAnyFrames() {
        StuckLineDetector detector = new StuckLineDetector();
        detector.onTimeline(1, 20_000L);

        assertTrue("只有一行的时间轴本来就没法滚动", detector.timelineUnusable());
        assertFalse(detector.stuckByLiveLyric());
        assertTrue(detector.describe(), detector.describe().contains("reason=single_line"));
        assertTrue(detector.consumeFallbackNotice());
    }

    @Test public void aTimelineThatDoesNotFitTheTrackIsUnusable() {
        assertFalse(StuckLineDetector.isDurationMismatch(240_000L, 240_000L));
        assertFalse("2.9 倍还在容忍范围内", StuckLineDetector.isDurationMismatch(700_000L, 240_000L));
        assertTrue("超过 3 倍", StuckLineDetector.isDurationMismatch(721_000L, 240_000L));
        assertTrue("不足三分之一", StuckLineDetector.isDurationMismatch(79_000L, 240_000L));
        assertFalse("时间轴跨度未知时不比对",
                StuckLineDetector.isDurationMismatch(0L, 240_000L));
        assertFalse("歌曲时长未知时不比对", StuckLineDetector.isDurationMismatch(240_000L, -1L));

        // 时间轴总跨度 800 秒对四分钟的歌：匹配明显不对，第一帧就能判。
        StuckLineDetector detector = new StuckLineDetector();
        detector.onTimeline(80, 800_000L);
        detector.onFrame(0L, 0L, "实时第 0 句", true, TRACK_MS, 0L);

        assertTrue(detector.timelineUnusable());
        assertTrue(detector.describe(), detector.describe().contains("reason=duration_mismatch"));
    }

    @Test public void aPauseRestartsTheStuckClock() {
        StuckLineDetector detector = new StuckLineDetector();
        detector.onTimeline(60, TRACK_MS);

        feed(detector, 0L, 20_000L, STUCK_FIRST_LINE, now -> now, SCROLLING_LIVE_LYRIC, true,
                TRACK_MS);
        // 暂停二十秒：位置不动，不能算成卡住。
        feed(detector, 21_000L, 40_000L, STUCK_FIRST_LINE, now -> 20_000L, SCROLLING_LIVE_LYRIC,
                false, TRACK_MS);
        assertFalse(detector.timelineUnusable());

        // 恢复播放后又过了二十四秒，其中只有二十四秒在播放。
        feed(detector, 41_000L, 64_000L, STUCK_FIRST_LINE, now -> 20_000L + (now - 41_000L),
                SCROLLING_LIVE_LYRIC, true, TRACK_MS);
        assertFalse("暂停的时间不算进卡住窗口", detector.timelineUnusable());

        feed(detector, 65_000L, 70_000L, STUCK_FIRST_LINE, now -> 20_000L + (now - 41_000L),
                SCROLLING_LIVE_LYRIC, true, TRACK_MS);
        assertTrue(detector.timelineUnusable());
    }

    @Test public void aFrozenPositionIsNotAStuckLine() {
        StuckLineDetector detector = new StuckLineDetector();
        detector.onTimeline(60, TRACK_MS);

        // 会话报告"播放中"，但进度一直不动：分不清是歌词卡住还是播放器卡住，不能回退。
        feed(detector, 90_000L, STUCK_FIRST_LINE, now -> 30_000L, SCROLLING_LIVE_LYRIC, true,
                TRACK_MS);

        assertFalse(detector.timelineUnusable());
    }

    @Test public void aBackwardSeekRestartsTheDetection() {
        StuckLineDetector detector = new StuckLineDetector();
        detector.onTimeline(60, TRACK_MS);

        feed(detector, 30_000L, STUCK_FIRST_LINE, SCROLLING_LIVE_LYRIC);
        assertTrue(detector.timelineUnusable());
        assertTrue(detector.consumeFallbackNotice());

        // 用户往回拖二十秒：判定作废，重新用原来的时间轴。
        detector.onFrame(0L, 10_000L, "实时第 2 句", true, TRACK_MS, 30_500L);
        assertFalse(detector.timelineUnusable());
        assertFalse(detector.consumeFallbackNotice());

        feed(detector, 31_000L, 55_000L, STUCK_FIRST_LINE, now -> now - 20_000L,
                SCROLLING_LIVE_LYRIC, true, TRACK_MS);
        assertFalse("seek 之后要重新攒够二十五秒", detector.timelineUnusable());

        feed(detector, 56_000L, 60_000L, STUCK_FIRST_LINE, now -> now - 20_000L,
                SCROLLING_LIVE_LYRIC, true, TRACK_MS);
        assertTrue(detector.timelineUnusable());
        assertTrue("重新判定成功，再写一条诊断", detector.consumeFallbackNotice());
    }

    @Test public void aSmallBackwardJitterIsNotASeek() {
        StuckLineDetector detector = new StuckLineDetector();
        detector.onTimeline(60, TRACK_MS);

        // AVRCP 的进度粒度很粗，来回抖动一两秒不能当成用户 seek 而清掉判定。
        feed(detector, 40_000L, STUCK_FIRST_LINE, now -> now >= 30_000L ? now - 1_500L : now,
                SCROLLING_LIVE_LYRIC, true, TRACK_MS);

        assertTrue(detector.timelineUnusable());
    }

    @Test public void aTrackChangeForgetsTheVerdict() {
        StuckLineDetector detector = new StuckLineDetector();
        detector.onTimeline(60, TRACK_MS);
        feed(detector, 30_000L, STUCK_FIRST_LINE, SCROLLING_LIVE_LYRIC);
        assertTrue(detector.timelineUnusable());

        detector.reset();
        assertFalse(detector.timelineUnusable());

        // 下一首歌重新判断：先在二十分钟内没有结论，二十五秒后才再次判定。
        detector.onTimeline(80, TRACK_MS);
        feed(detector, 20_000L, STUCK_FIRST_LINE, SCROLLING_LIVE_LYRIC);
        assertFalse(detector.timelineUnusable());
        feed(detector, 21_000L, 30_000L, STUCK_FIRST_LINE, now -> now, SCROLLING_LIVE_LYRIC,
                true, TRACK_MS);
        assertTrue(detector.timelineUnusable());
    }

    @Test public void aNewTimelineRearmsTheDiagnostic() {
        StuckLineDetector detector = new StuckLineDetector();
        detector.onTimeline(60, TRACK_MS);
        feed(detector, 30_000L, STUCK_FIRST_LINE, SCROLLING_LIVE_LYRIC);
        assertTrue(detector.consumeFallbackNotice());
        assertFalse(detector.consumeFallbackNotice());

        detector.onTimeline(80, TRACK_MS);
        assertFalse("换了时间轴要重新判断", detector.timelineUnusable());

        detector.onTimeline(1, 10_000L);
        assertTrue(detector.timelineUnusable());
        assertTrue("新时间轴同样明显没用，再写一条诊断", detector.consumeFallbackNotice());
    }

    @Test public void liveLyricChangesAreCountedInsideTheCurrentLine() {
        StuckLineDetector detector = new StuckLineDetector();
        detector.onTimeline(60, TRACK_MS);

        // 时间轴在第 10 秒换了一行；实时歌词在第 1、3 秒各变一次（属于旧的那一行），
        // 之后停住，第 30 秒才再变一次。
        LongUnaryOperator line = now -> now < 10_000L ? 0L : 10_000L;
        LongFunction<String> lyric = now -> now < 1_000L ? "实时 A"
                : now < 3_000L ? "实时 B" : now < 30_000L ? "实时 C"
                : now < 36_000L ? "实时 D" : "实时 E";

        feed(detector, 35_000L, line, lyric);
        assertFalse("换行之前的两句实时歌词不算进当前行的窗口", detector.timelineUnusable());

        feed(detector, 36_000L, 40_000L, line, now -> now, lyric, true, TRACK_MS);
        assertTrue(detector.timelineUnusable());
    }

    @Test public void aDetectorThatIsNeverFedNeverRejectsATimeline() {
        // 开关关闭时 MusicStateStore 根本不会把帧喂进来：没有观测就没有判定，行为与改动前一致。
        StuckLineDetector detector = new StuckLineDetector();
        detector.onTimeline(60, TRACK_MS);

        assertFalse(detector.timelineUnusable());
        assertFalse(detector.consumeFallbackNotice());
    }

    /** 每秒一帧，从零开始，位置跟着时钟走。 */
    private static void feed(StuckLineDetector detector, long toMs, LongUnaryOperator lineAt,
                             LongFunction<String> lyricAt) {
        feed(detector, 0L, toMs, lineAt, now -> now, lyricAt, true, TRACK_MS);
    }

    /** 每秒一帧，从零开始，位置由场景决定。 */
    private static void feed(StuckLineDetector detector, long toMs, LongUnaryOperator lineAt,
                             LongUnaryOperator positionAt, LongFunction<String> lyricAt,
                             boolean playing, long trackMs) {
        feed(detector, 0L, toMs, lineAt, positionAt, lyricAt, playing, trackMs);
    }

    /** 每秒一帧，模拟面板和显示服务读取快照的节奏。 */
    private static void feed(StuckLineDetector detector, long fromMs, long toMs,
                             LongUnaryOperator lineAt, LongUnaryOperator positionAt,
                             LongFunction<String> lyricAt, boolean playing, long trackMs) {
        for (long now = fromMs; now <= toMs; now += FRAME_MS) {
            detector.onFrame(StuckLineDetector.Signal.of(lineAt.applyAsLong(now),
                    positionAt.applyAsLong(now), lyricAt.apply(now), playing, trackMs, now));
        }
    }
}
