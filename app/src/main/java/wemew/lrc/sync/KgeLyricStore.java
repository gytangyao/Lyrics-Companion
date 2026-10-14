package wemew.lrc.sync;

import android.content.Context;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates the KGe lyric stream (line text + line start ms) into a session timeline and
 * feeds it into {@link MusicStateStore} as the "wesing" source — the same plumbing the Kuwo
 * in-session lyrics use. A start time that jumps backwards marks a new song and resets the
 * accumulation; there is no song identity on the K62 wire, only lines.
 */
final class KgeLyricStore {
    /** A start-time regression larger than this means a new song restarted the clock. */
    private static final long NEW_SONG_REGRESSION_MS = 3_000L;
    private static final long IDLE_CLEAR_MS = 60_000L;

    private static final List<LrcTimeline.Line> lines = new ArrayList<>();
    private static long lastStartMs = -1L;
    private static long lastEventAtElapsedMs;

    private KgeLyricStore() {}

    static void onLyricEvent(Context context, String text, long startMs) {
        synchronized (KgeLyricStore.class) {
            lastEventAtElapsedMs = SystemClock.elapsedRealtime();
            if (lastStartMs >= 0L && startMs + NEW_SONG_REGRESSION_MS < lastStartMs) {
                lines.clear();
            }
            lastStartMs = startMs;
            if (text != null && !text.trim().isEmpty()) {
                lines.add(new LrcTimeline.Line(startMs, 1000L, text.trim()));
            }
            pushLocked(context, text == null ? "" : text.trim(), startMs);
        }
    }

    /** Clears the accumulated song when nothing arrived for a while (display idles out). */
    static void maintain(Context context) {
        synchronized (KgeLyricStore.class) {
            if (!lines.isEmpty() && lastEventAtElapsedMs > 0L
                    && SystemClock.elapsedRealtime() - lastEventAtElapsedMs > IDLE_CLEAR_MS) {
                lines.clear();
                lastStartMs = -1L;
                MusicStateStore.clear();
            }
        }
    }

    private static void pushLocked(Context context, String currentLine, long startMs) {
        LrcTimeline timeline = LrcTimeline.fromTimedLines(new ArrayList<>(lines));
        MusicPlaybackData data = new MusicPlaybackData(
                "kge", "全民K歌", "", null, "", "", -1L,
                true, MusicPlaybackData.STATE_PLAYING, startMs,
                SystemClock.elapsedRealtime(), 1f,
                true, currentLine, timeline);
        MusicStateStore.update(context, "wesing", "全民K歌", "com.tencent.karaoke", data);
    }
}
