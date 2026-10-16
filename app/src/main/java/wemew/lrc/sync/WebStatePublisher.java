package wemew.lrc.sync;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * Polls {@link MusicStateStore} on a light tick and pushes protocol frames into the cloud
 * room: a full {@code track} frame on track/timeline changes, a cheap {@code state} anchor
 * around once a second (or immediately on seek/pause/speed change), and a {@code line} frame
 * whenever the player's live lyric text changes. Displays interpolate between anchors, which
 * keeps karaoke smooth at a fraction of the traffic.
 */
final class WebStatePublisher implements CloudSyncClient.SnapshotSource {
    static final long DRIFT_THRESHOLD_MS = 700L;
    static final long STATE_INTERVAL_MS = 1_000L;
    private static final long TICK_MS = 500L;
    private static final String TAG = "WebStatePublisher";

    private final Context context;
    private final PublisherDiff.State diff = new PublisherDiff.State();
    /** Held so reference equality against the previous timeline stays meaningful. */
    private LrcTimeline previousTimeline = LrcTimeline.EMPTY;
    private int timelineEpoch;
    private HandlerThread thread;
    private Handler handler;
    private String lastStateJson;
    /** Elapsed-realtime of the last completed tick; the service watchdog reads this. */
    private volatile long lastTickElapsedMs;
    /** Human-readable summary of the last pushed frame, for the settings page. */
    private volatile String lastPushSummary = "";
    /** Cloud-room leg; every pushed frame fans out through it. */
    private volatile CloudSyncClient cloud;
    /** True while the wrong-track guard has already blanked the displays. */
    private boolean blankPushed;
    /** True while the PC watcher's heartbeat makes it the WeSing publisher. */
    private boolean deferringToWatcher;

    WebStatePublisher(Context context) {
        this.context = context.getApplicationContext();
    }

    /** How long since the last publish tick; grows when the thread is wedged. */
    long tickAgeMs() {
        long last = lastTickElapsedMs;
        return last <= 0L ? 0L : Math.max(0L, SystemClock.elapsedRealtime() - last);
    }

    String lastPushSummary() {
        return lastPushSummary;
    }

    void start() {
        stop();
        // Seed the heartbeat so a publisher whose very first tick blocks is still detected.
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        thread = new HandlerThread("WebStatePublisher");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::tick);
    }

    void stop() {
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
    }

    /** Attaches the cloud leg; frames published from now on reach the room. */
    void setCloud(CloudSyncClient cloudLeg) {
        cloud = cloudLeg;
    }

    private void tick() {
        try {
            publish();
        } catch (Throwable error) {
            Log.w(TAG, "publish tick failed", error);
        }
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        if (handler != null) handler.postDelayed(this::tick, TICK_MS);
    }

    /** Song identity without the timeline epoch: the unit the wrong-track guard pauses. */
    static String songKey(String title, String artist, String sourceId) {
        return (title == null ? "" : title) + "\n" + (artist == null ? "" : artist)
                + "\n" + (sourceId == null ? "" : sourceId);
    }

    /**
     * The PC watcher (wesing_watcher.js) pushes exact word-level QRC timelines for WeSing
     * straight into the room. While its heartbeat is fresh it owns publishing — two
     * publishers would fight over every anchor (the losing frames blank the display).
     * The phone only keeps the floor for a genuinely playing, titled track from another
     * app: WeSing's own media session is anonymous and title-less ("音乐播放器"), so
     * "active with empty title" is exactly the junk the watcher must shadow.
     */
    private boolean watcherOwnsWeSing() {
        CloudSyncClient leg = cloud;
        if (leg == null || !leg.watcherHeartbeatFresh()) return false;
        MusicSnapshot snapshot = MusicStateStore.snapshot(0);
        boolean otherAppPlaying = snapshot.active
                && !"wesing".equals(MusicStateStore.activeSourceId())
                && !TextUtils.isEmpty(snapshot.title);
        return !otherAppPlaying;
    }

    private void publish() {
        long nowWall = System.currentTimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        MusicSnapshot snapshot =
                MusicStateStore.snapshot(AppPreferences.lyricOffsetMs(context));
        LrcTimeline timeline = MusicStateStore.currentTimeline();
        float speed = MusicStateStore.playbackSpeed();

        boolean defer = watcherOwnsWeSing();
        if (defer != deferringToWatcher) {
            deferringToWatcher = defer;
            AppLog.i(TAG, defer ? "WeSing publishing handed to PC watcher"
                    : "PC watcher gone; resuming WeSing publishing");
            if (!defer) {
                // Force a full track so displays switch back to our data immediately.
                diff.trackKey = null;
                diff.lastStatePushElapsedMs = null;
            }
        }
        if (defer) {
            lastPushSummary = "deferred to PC watcher";
            return;
        }

        // Wrong-track guard: only the paused song is blanked; a track change auto-resumes.
        String pausedSong = AppPreferences.pausedSongKey(context);
        if (!pausedSong.isEmpty()) {
            String currentSong = songKey(snapshot.title, snapshot.artist,
                    MusicStateStore.activeSourceId());
            if (pausedSong.equals(currentSong)) {
                if (!blankPushed) {
                    blankPushed = true;
                    fanOut(WebProtocolCodec.blank(nowWall, "output-paused"));
                    lastPushSummary = "blank（本曲已停止显示）";
                }
                return;
            }
            // The song changed: lift the guard and force a full track frame right away.
            AppPreferences.setPausedSongKey(context, "");
            blankPushed = false;
            diff.trackKey = null;
            diff.lastStatePushElapsedMs = null;
            DiagnosticLog.record(context, "WebSync", "曲目切换，自动恢复歌词输出");
        } else if (blankPushed) {
            blankPushed = false;
        }

        if (timeline != previousTimeline) {
            timelineEpoch++;
            previousTimeline = timeline;
        }
        boolean liveMode = snapshot.liveLyricActive;
        String trackKey = snapshot.title + "\n" + snapshot.artist + "\n"
                + MusicStateStore.activeSourceId() + "\n" + snapshot.durationMs + "\n"
                + (liveMode ? "live" : snapshot.lyricSourceName) + "\n" + timelineEpoch;
        String liveLine = liveMode ? snapshot.lyrics.lyric : "";

        PublisherDiff.Decision decision = PublisherDiff.update(diff, trackKey, snapshot.active,
                snapshot.playing, speed, snapshot.positionMs, liveLine, nowElapsed,
                DRIFT_THRESHOLD_MS, STATE_INTERVAL_MS);
        if (decision.track) {
            WebProtocolCodec.TrackPayload payload = payload(snapshot, timeline, speed,
                    liveMode);
            WebProtocolCodec.TrackStream stream = WebProtocolCodec.trackStream(nowWall, payload);
            fanOutAll(stream.frames);
            lastPushSummary = "track " + snapshot.title + (stream.chunkCount > 0
                    ? " [" + stream.chunkCount + " chunks, " + stream.byteCount + "B]"
                    : " [" + stream.byteCount + "B]");
        }
        if (decision.state) {
            lastStateJson = WebProtocolCodec.state(nowWall, snapshot.active, snapshot.playing,
                    snapshot.positionMs, speed, snapshot.durationMs, true);
            fanOut(lastStateJson);
            if (!decision.track) lastPushSummary = "state @" + snapshot.positionMs + "ms";
        }
        if (decision.line && liveMode) {
            fanOut(WebProtocolCodec.line(nowWall, snapshot.lyrics.lyric,
                    snapshot.lyrics.nextLyric, snapshot.lyrics.translatedLyric));
            lastPushSummary = "line " + snapshot.lyrics.lyric;
        }
    }

    private void fanOut(String json) {
        CloudSyncClient cloudLeg = cloud;
        if (cloudLeg != null) cloudLeg.send(json);
    }

    /** Head first, then its chunks: the relay forwards each socket write in order. */
    private void fanOutAll(List<String> frames) {
        for (String frame : frames) fanOut(frame);
    }

    private WebProtocolCodec.TrackPayload payload(MusicSnapshot snapshot, LrcTimeline timeline,
                                                  float speed, boolean liveMode) {
        WebProtocolCodec.TrackPayload payload = new WebProtocolCodec.TrackPayload();
        payload.active = snapshot.active;
        payload.playing = snapshot.playing;
        payload.sourceId = MusicStateStore.activeSourceId();
        payload.sourceName = snapshot.sourceName;
        payload.title = snapshot.title;
        payload.artist = snapshot.artist;
        payload.durationMs = snapshot.durationMs;
        payload.positionMs = snapshot.positionMs;
        payload.speed = speed;
        payload.offsetMs = AppPreferences.lyricOffsetMs(context);
        payload.timeline = liveMode ? LrcTimeline.EMPTY : timeline;
        payload.liveMode = liveMode;
        payload.lyricSourceName = snapshot.lyricSourceName;
        payload.liveLine = liveMode ? snapshot.lyrics.lyric : "";
        // No cover transport exists without a local HTTP leg; reserved in the protocol.
        payload.hasArt = false;
        payload.artVersion = 0;
        return payload;
    }

    @Override public List<String> trackFrames() {
        if (watcherOwnsWeSing()) return null;
        MusicSnapshot snapshot =
                MusicStateStore.snapshot(AppPreferences.lyricOffsetMs(context));
        // A display joining while the current song is paused gets the blank, not a track.
        String pausedSong = AppPreferences.pausedSongKey(context);
        if (!pausedSong.isEmpty() && pausedSong.equals(songKey(snapshot.title, snapshot.artist,
                MusicStateStore.activeSourceId()))) {
            return Collections.singletonList(
                    WebProtocolCodec.blank(System.currentTimeMillis(), "output-paused"));
        }
        // Always freshly built: the snapshot must reflect "now", never a cached frame (a
        // stale position anchor makes a joining display jump).
        WebProtocolCodec.TrackPayload payload = payload(snapshot,
                MusicStateStore.currentTimeline(), MusicStateStore.playbackSpeed(),
                snapshot.liveLyricActive);
        return WebProtocolCodec.trackStream(System.currentTimeMillis(), payload).frames;
    }

    @Override public String stateJson() {
        if (watcherOwnsWeSing()) return null;
        MusicSnapshot snapshot =
                MusicStateStore.snapshot(AppPreferences.lyricOffsetMs(context));
        lastStateJson = WebProtocolCodec.state(System.currentTimeMillis(), snapshot.active,
                snapshot.playing, snapshot.positionMs, MusicStateStore.playbackSpeed(),
                snapshot.durationMs, true);
        return lastStateJson;
    }
}
