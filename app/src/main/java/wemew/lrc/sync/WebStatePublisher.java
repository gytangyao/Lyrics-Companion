package wemew.lrc.sync;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

/**
 * Polls {@link MusicStateStore} on a light tick and broadcasts protocol messages through the
 * diff engine: a full {@code track} frame on track/timeline changes, a cheap {@code state}
 * anchor around once a second (or immediately on seek/pause/speed change), and a {@code line}
 * frame whenever the player's live lyric text changes. Clients interpolate between anchors,
 * which keeps karaoke smooth at a fraction of the traffic.
 */
final class WebStatePublisher implements WebSyncSocket.Hooks, WebDisplayServer.RestSource {
    static final long DRIFT_THRESHOLD_MS = 700L;
    static final long STATE_INTERVAL_MS = 1_000L;
    private static final long TICK_MS = 500L;
    private static final String TAG = "WebStatePublisher";

    private final Context context;
    private final WebSocketClients clients;
    private final CoverRegistry covers;
    private final String serverVersion;
    private final PublisherDiff.State diff = new PublisherDiff.State();
    /** Held so reference equality against the previous timeline stays meaningful. */
    private LrcTimeline previousTimeline = LrcTimeline.EMPTY;
    private int timelineEpoch;
    private HandlerThread thread;
    private Handler handler;
    private String lastTrackJson;
    private String lastStateJson;
    /** Elapsed-realtime of the last completed tick; the service watchdog reads this. */
    private volatile long lastTickElapsedMs;
    /** Human-readable summary of the last pushed frame, for the settings page. */
    private volatile String lastPushSummary = "";

    /** How long since the last publish tick; grows when the thread is wedged. */
    long tickAgeMs() {
        long last = lastTickElapsedMs;
        return last <= 0L ? 0L : Math.max(0L, SystemClock.elapsedRealtime() - last);
    }

    String lastPushSummary() {
        return lastPushSummary;
    }

    WebStatePublisher(Context context, WebSocketClients clients, CoverRegistry covers,
                      String serverVersion) {
        this.context = context.getApplicationContext();
        this.clients = clients;
        this.covers = covers;
        this.serverVersion = serverVersion;
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

    private void tick() {
        try {
            publish();
        } catch (Throwable error) {
            Log.w(TAG, "publish tick failed", error);
        }
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        if (handler != null) handler.postDelayed(this::tick, TICK_MS);
    }

    private void publish() {
        long nowWall = System.currentTimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        MusicSnapshot snapshot =
                MusicStateStore.snapshot(AppPreferences.lyricOffsetMs(context));
        LrcTimeline timeline = MusicStateStore.currentTimeline();
        float speed = MusicStateStore.playbackSpeed();
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
            int artVersion = covers.update(trackKey, snapshot.albumArt);
            WebProtocolCodec.TrackPayload payload = payload(snapshot, timeline, speed,
                    liveMode, artVersion);
            lastTrackJson = WebProtocolCodec.track(nowWall, payload);
            clients.broadcast(lastTrackJson);
            lastPushSummary = "track " + snapshot.title;
        }
        if (decision.state) {
            lastStateJson = WebProtocolCodec.state(nowWall, snapshot.active, snapshot.playing,
                    snapshot.positionMs, speed, snapshot.durationMs, true);
            clients.broadcast(lastStateJson);
            if (!decision.track) lastPushSummary = "state @" + snapshot.positionMs + "ms";
        }
        if (decision.line && liveMode) {
            clients.broadcast(WebProtocolCodec.line(nowWall, snapshot.lyrics.lyric,
                    snapshot.lyrics.nextLyric, snapshot.lyrics.translatedLyric));
            lastPushSummary = "line " + snapshot.lyrics.lyric;
        }
    }

    private WebProtocolCodec.TrackPayload payload(MusicSnapshot snapshot, LrcTimeline timeline,
                                                  float speed, boolean liveMode, int artVersion) {
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
        payload.hasArt = snapshot.albumArt != null;
        payload.artVersion = artVersion;
        return payload;
    }

    @Override public String welcomeJson() {
        return WebProtocolCodec.welcome(System.currentTimeMillis(), serverVersion);
    }

    @Override public String trackJson() {
        // Always freshly built: the connect snapshot must reflect "now", never a frame cached
        // when the track first appeared (a stale position anchor makes a joining client jump).
        MusicSnapshot snapshot =
                MusicStateStore.snapshot(AppPreferences.lyricOffsetMs(context));
        boolean liveMode = snapshot.liveLyricActive;
        WebProtocolCodec.TrackPayload payload = payload(snapshot,
                MusicStateStore.currentTimeline(), MusicStateStore.playbackSpeed(), liveMode,
                covers.version());
        lastTrackJson = WebProtocolCodec.track(System.currentTimeMillis(), payload);
        return lastTrackJson;
    }

    @Override public String stateJson() {
        MusicSnapshot snapshot =
                MusicStateStore.snapshot(AppPreferences.lyricOffsetMs(context));
        lastStateJson = WebProtocolCodec.state(System.currentTimeMillis(), snapshot.active,
                snapshot.playing, snapshot.positionMs, MusicStateStore.playbackSpeed(),
                snapshot.durationMs, true);
        return lastStateJson;
    }

    @Override public String stateSnapshot() {
        MusicSnapshot snapshot =
                MusicStateStore.snapshot(AppPreferences.lyricOffsetMs(context));
        LrcTimeline timeline = MusicStateStore.currentTimeline();
        float speed = MusicStateStore.playbackSpeed();
        boolean liveMode = snapshot.liveLyricActive;
        JSONObject trackData = snapshot.active
                ? WebProtocolCodec.trackData(payload(snapshot, timeline, speed, liveMode,
                        covers.version()))
                : null;
        JSONObject stateData = WebProtocolCodec.stateData(snapshot.active, snapshot.playing,
                snapshot.positionMs, speed, snapshot.durationMs, true);
        return WebProtocolCodec.restSnapshot(trackData, stateData);
    }

    @Override public String trackSnapshot() {
        MusicSnapshot snapshot =
                MusicStateStore.snapshot(AppPreferences.lyricOffsetMs(context));
        if (!snapshot.active) return "{\"v\":1,\"track\":null}";
        return "{\"v\":1,\"track\":" + WebProtocolCodec.trackData(payload(snapshot,
                MusicStateStore.currentTimeline(), MusicStateStore.playbackSpeed(),
                snapshot.liveLyricActive, covers.version())).toString() + "}";
    }

    @Override public byte[] coverBytes(int requestedVersion) {
        return covers.bytes(requestedVersion);
    }
}
