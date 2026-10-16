package wemew.lrc.sync;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-device WeSing watcher: the PC-side wesing_watcher.js logic running fully in-app.
 * A one-time {@code adb shell pm grant wemew.lrc.sync android.permission.READ_LOGS}
 * (signature|development permission — no runtime dialog, persisted across reboots) lets
 * the app uid poll incremental {@code logcat} batches itself, so the watcher survives
 * power cycles with no Shizuku daemon to resurrect. Word-level lyrics come from WeSing's
 * own QRC, mirrored into our files/qrc directory by {@link #QRC_MIRROR_COMMAND} (its
 * timing already matches the accompaniment WeSing plays); the QQ cloud catalogue via
 * {@link QQMusicLyricClient#loadForWeSing} is the fallback for songs never opened there.
 * The recording lifecycle (songId, anchors, play/pause) still comes from the log.
 * No PC, no root, no Shizuku, no accessibility.
 */
final class WeSingLogcatWatcher {
    private static final String TAG = "WeSingWatch";
    /** The whole on-site SOP: run once per install from a PC, survives reboots. */
    static final String GRANT_COMMAND =
            "adb shell pm grant wemew.lrc.sync android.permission.READ_LOGS";
    private static final long POLL_INTERVAL_MS = 1_000L;
    /** Hard cap on one batch. The watermark only advances over returned lines, so a flood
     *  defers its overflow to the next poll instead of losing it. */
    private static final int MAX_BATCH_BYTES = 128 * 1024;
    /** Cloud fetch attempts per song before the timeline stays empty for this session. */
    private static final int MAX_TIMELINE_ATTEMPTS = 3;
    /** Bounded like the old in-memory qrc cache: song entries are a few KB each. */
    private static final int TIMELINE_CACHE_LIMIT = 60;
    /** WeSing's accompaniment-synced QRC files, mirrored into our own external files
     *  directory by {@link #QRC_MIRROR_COMMAND}. Reading them in place is impossible on
     *  Android 12+ — the framework blocks cross-app {@code Android/data} for every in-app
     *  mechanism (File API, MANAGE_EXTERNAL_STORAGE and SAF alike) — but the adb shell
     *  runs as group {@code ext_data_rw} and can copy them where we can read them. */
    private static final String QRC_MIRROR_DIR = "qrc";
    /** One-time SOP, same shape as the READ_LOGS grant: a mirror loop, re-run after reboot.
     *  The mkdir sits inside the loop so it also recovers an app reinstall, which wipes
     *  our data directory while the loop keeps running.
     *  {@code setsid} is not decoration: a {@code nohup}-only loop on this tablet died
     *  when its spawning adb session ended (observed 21 minutes of stale copies). Owning a
     *  fresh session with no controlling terminal is what keeps it at PPID 1 afterwards. */
    static final String QRC_MIRROR_COMMAND =
            "adb shell \"nohup setsid sh -c 'while :; do mkdir -p "
                    + "/sdcard/Android/data/wemew.lrc.sync/files/qrc; cp -f "
                    + "/sdcard/Android/data/com.tencent.karaoke/files/qrc/* "
                    + "/sdcard/Android/data/wemew.lrc.sync/files/qrc/ 2>/dev/null; "
                    + "sleep 3; done' >/dev/null 2>&1 &\"";

    // Verified against WeSing 11.0.38.278 (see wesing_watcher.js for the JS twin).
    private static final Pattern RE_ENTER = Pattern.compile(
            "enterRecordData=: mSongId = (\\S+?); mSongTitle = ([^;]+);");
    private static final Pattern RE_DEAL_QRC = Pattern.compile(
            "dealQrc begin : (\\S*/qrc/(\\S+?)_(?:original|dynamic|pronounce|homophony)\\.qrc)");
    private static final Pattern RE_META = Pattern.compile(
            "songMid=(\\S+?), songName=([^,]+), singerName=([^,]+), singerMid=(\\S+?)[,)]");
    /** Pad builds (same 11.0.38 line, e.g. HarmonyOS) never log enterRecordData or
     *  dealQrc; their recording page announces the song through RECORD_LOG_V8 with a
     *  real QQ songMid — which is even better for the cloud fetch than a numeric id. */
    private static final Pattern RE_V8_SONGMID = Pattern.compile(
            "RECORD_LOG_V8.*?songMid=([0-9A-Za-z]+)");
    /** WeSing logs the room's enter parameter with mid AND name in one line — the name
     *  makes the binding self-verifying. Pad builds only; mids live in WeSing's own id
     *  space, so they identify the song but never feed the QQ catalogue directly. */
    private static final Pattern RE_ENTER_PARAM = Pattern.compile(
            "RecordEnterParam\\(songMid='([^']+)', songName=([^,]+),");
    private static final Pattern RE_START_RECORD = Pattern.compile(
            "startRecord,recordServiceData=.*?recordDelay=(\\d+)");
    /** WeSing's own playback clock, logged at every finished sentence and at recording start
     *  ({@code timeStamp:0.0}). This is the position the accompaniment is really at. */
    private static final Pattern RE_SCORER_POSITION = Pattern.compile(
            "updateScoreResult seek:(true|false),timeStamp:([0-9.]+)");
    /**
     * WeSing's player announcing the absolute position of a start, resume or drag. Every one of
     * these carries the accompaniment position in ms, which is what the QRC timeline is authored
     * against.
     */
    private static final Pattern RE_PLAYER_POSITION = Pattern.compile(
            "seekToSing, position: (\\d+)|seek: positionMs = (\\d+)");
    /** The line the accompaniment actually begins running on; the position above applies from it. */
    private static final Pattern RE_PLAYER_START = Pattern.compile(
            "start begin\\. recordDelay : (\\d+)");
    /**
     * WeSing's recording service opening and closing the take. {@code pause sing} freezes the
     * accompaniment without ending the session (a resume follows with a seek and a start line),
     * {@code stop sing} ends it — after that the player lines belong to the result page's preview.
     */
    private static final Pattern RE_SING_PAUSE = Pattern.compile(
            "KaraRecordService:.*\\[UI\\]pause sing");
    private static final Pattern RE_SING_STOP = Pattern.compile(
            "KaraRecordService:.*\\bstop sing");
    /** The recording page's own track length, in ms, on the line that hands it the accompaniment. */
    private static final Pattern RE_DURATION = Pattern.compile("iDuration=(\\d+)");
    /** How far a sample may disagree with our own clock before it is treated as another song's. */
    private static final long POSITION_RESYNC_SLACK_MS = 8_000L;
    /**
     * How far one of the scorer's sentence samples has to disagree with a player-derived anchor
     * before we believe it. Measured on this tablet the scorer runs a steady 0.70s behind the
     * accompaniment (both sessions, ±30ms over four minutes), so sentence samples are good for
     * catching a missed drag but never for fine alignment.
     */
    private static final long SCORER_GUARD_MS = 2_500L;
    /** A player anchor this older than a startRecord event outranks its countdown. */
    private static final long SCORER_TRUST_MS = 2_000L;
    /**
     * How far a start line may sit from the seek that set its position. Measured on this tablet the
     * two are 25–300ms apart; anything wider is a leftover from an earlier drag and would anchor
     * the song hundreds of seconds off.
     */
    private static final long PENDING_PAIR_MS = 1_500L;
    /** A resync this small is clock jitter; it is applied but not logged. */
    private static final long RESYNC_LOG_MS = 500L;
    private static final Pattern RE_BT_PLAY = Pattern.compile(
            "try on(Play|Pause|Start): \\$?SongInfo\\(songName=([^,]+), singerName=([^,]+), "
                    + "album=, duration=(\\d+)");
    private static final Pattern RE_SCORE = Pattern.compile(
            "RecordScoreModule: \\[UI\\]songId = (\\S+?), totalScore =(\\d+)");
    private static final Pattern RE_LOG_TIME = Pattern.compile(
            "^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})");

    private static volatile WeSingLogcatWatcher instance;

    private final Context context;
    /** Watched by the publisher thread while the watcher thread writes — concurrent for a
     *  reason the old HashMap only got away with by luck. */
    private final Map<String, LrcTimeline> timelineCache = new ConcurrentHashMap<>();
    private volatile QQMusicLyricClient lyricClient;
    private Thread thread;
    private volatile boolean stopped;
    /** logcat -T watermark: everything strictly before this timestamp is already parsed. */
    private String lastLogTimestamp;
    /** wall-clock minus elapsed-realtime at the last poll (anchor conversion). */
    private volatile long wallMinusElapsedMs;
    /** One-shot flag: log the first non-empty poll so silence always has an explanation. */
    private boolean pollDiagnosticsLogged;
    /** Latched while logcat keeps reporting errors, so a broken grant cannot spam the log. */
    private boolean logcatErrorLatched;

    // ---- song state (writer: watcher thread; reader: publisher thread) ----
    private volatile String songId = "";
    private volatile String title = "";
    private volatile String artist = "";
    /** The log's "songMid" field — in WeSing it carries the same numeric id as mSongId. */
    private volatile String songMid = "";
    private volatile long durationMs = -1L;
    private volatile boolean playing;
    private volatile boolean active;
    /** Elapsed-realtime when accompaniment position 0 started (startRecord + countdown). */
    private volatile long anchorElapsedMs;
    /**
     * Anchor from WeSing's own position-bearing lines (player start, seek, scorer zero), with the
     * log event time it came from. Kept apart from {@link #anchorElapsedMs} so a {@code startRecord}
     * can tell whether the accompaniment position is already known exactly.
     */
    private volatile long exactAnchorElapsedMs;
    private volatile long exactAnchorEventMs;
    /**
     * Position the last player position line asked for, and the event time it was logged at. The
     * accompaniment only runs from the following start line, and logcat hands us lines out of
     * order, so both have to be checked before the two are paired up.
     */
    private volatile long pendingPositionMs = -1L;
    private volatile long pendingPositionEventMs;
    /** Song position when paused; lets a resume rebase the anchor without a jump. */
    private volatile long posAtPauseMs;
    /**
     * True between 开始演唱 ({@code RecordService: startRecord}) and the take ending
     * ({@code KaraRecordService: stop sing}). WeSing runs the accompaniment through the very same
     * {@code KaraM4aPlayer} for the chorus preview it plays while a song page is still loading, so
     * a player line alone does not prove anyone started singing — without this gate the big screen
     * walks the lyrics before 开始演唱 is ever pressed.
     */
    private volatile boolean takeLive;
    /** Watcher-thread-only counter behind {@link #MAX_TIMELINE_ATTEMPTS}. */
    private int timelineAttempts;

    private WeSingLogcatWatcher(Context context) {
        this.context = context.getApplicationContext();
    }

    static WeSingLogcatWatcher get(Context context) {
        WeSingLogcatWatcher current = instance;
        if (current == null) {
            synchronized (WeSingLogcatWatcher.class) {
                if (instance == null) instance = new WeSingLogcatWatcher(context);
                current = instance;
            }
        }
        return current;
    }

    /** READ_LOGS granted to this app — only achievable via adb pm grant, and it persists. */
    static boolean granted(Context context) {
        try {
            return context.getPackageManager().checkPermission(
                    android.Manifest.permission.READ_LOGS, context.getPackageName())
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable error) {
            return false;
        }
    }

    /** logcat -T timestamp filtering (API 21) is the only platform floor left. */
    static boolean supportedOnThisDevice() {
        return Build.VERSION.SDK_INT >= 21;
    }

    synchronized void start() {
        if (!granted(context) || thread != null) return;
        stopped = false;
        wallMinusElapsedMs = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        lastLogTimestamp = logcatTimestampNow();
        thread = new Thread(this::runLoop, "WeSingLogcatWatcher");
        thread.start();
        AppLog.i(TAG, "on-device watcher started");
    }

    synchronized void stop() {
        stopped = true;
        Thread current = thread;
        thread = null;
        if (current != null) current.interrupt();
        active = false;
        AppLog.i(TAG, "on-device watcher stopped");
    }

    boolean running() {
        Thread current = thread;
        return current != null && current.isAlive();
    }

    private void runLoop() {
        while (!stopped) {
            try {
                // Wall/elapsed offset from the same clock the log timestamps live on.
                wallMinusElapsedMs = System.currentTimeMillis() - SystemClock.elapsedRealtime();
                String batch = pollLogcat();
                if (batch != null) consume(batch);
            } catch (InterruptedException wake) {
                Thread.currentThread().interrupt();
            } catch (Throwable error) {
                AppLog.w(TAG, "poll failed: " + error);
                sleepQuietly(2_000L);
                continue;
            }
            sleepQuietly(POLL_INTERVAL_MS);
        }
    }

    /** Parses one incremental batch; skips and notes logcat's own error lines. */
    private void consume(String batch) {
        boolean sawLogcatError = false;
        if (!pollDiagnosticsLogged && !batch.trim().isEmpty()) {
            pollDiagnosticsLogged = true;
            AppLog.i(TAG, "first poll ok: " + batch.split("\n").length
                    + " lines from -T " + lastLogTimestamp);
        }
        for (String line : batch.split("\n")) {
            if (line.startsWith("logcat:")) {
                sawLogcatError = true;
                continue;
            }
            if (isWatcherLine(line)) onLine(line);
            advanceWatermark(line);
        }
        if (sawLogcatError && !logcatErrorLatched) {
            logcatErrorLatched = true;
            AppLog.w(TAG, "logcat reported an error (READ_LOGS grant missing?); "
                    + "suppressing further notes until a clean batch");
        } else if (!sawLogcatError) {
            logcatErrorLatched = false;
        }
    }

    /**
     * One incremental logcat dump in the app's own uid. {@code -d} makes the child exit
     * after the dump; redirectErrorStream merges a missing-grant complaint into the batch
     * where {@link #consume} can surface it.
     */
    private String pollLogcat() throws Exception {
        Process process = new ProcessBuilder("logcat", "-d", "-v", "time",
                "-T", lastLogTimestamp).redirectErrorStream(true).start();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream input = process.getInputStream();
            byte[] buffer = new byte[8192];
            int count;
            while (out.size() < MAX_BATCH_BYTES && (count = input.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            if (out.size() >= MAX_BATCH_BYTES) {
                // Drain the rest so the child exits instead of blocking on a full pipe.
                while (input.read(buffer) > 0) { /* discard */ }
            }
            return out.toString("UTF-8");
        } finally {
            process.destroy();
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Watermark start: look back 5 minutes so a song entered just before the watcher
     *  came up (right after onboarding, say) still gets its enter event. */
    private static String logcatTimestampNow() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(System.currentTimeMillis() - 5 * 60_000L));
    }

    /** Parses a logcat wall timestamp ("MM-dd HH:mm:ss.SSS") into epoch milliseconds. */
    private static long parseLogWallMs(String value) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            return format.parse(year + "-" + value).getTime();
        } catch (Throwable ignored) {
            return System.currentTimeMillis();
        }
    }

    /** Keeps the -T watermark at the newest timestamp seen so far. */
    private void advanceWatermark(String line) {
        Matcher m = RE_LOG_TIME.matcher(line);
        if (!m.find()) return;
        String stamp = m.group(1);
        if (lastLogTimestamp == null || stamp.compareTo(lastLogTimestamp) > 0) {
            lastLogTimestamp = stamp;
        }
    }

    /** Event wall time → elapsed-realtime using the poll's measured clock offset. */
    private long eventElapsedMs(String line) {
        Matcher m = RE_LOG_TIME.matcher(line);
        if (!m.find()) return SystemClock.elapsedRealtime();
        return parseLogWallMs(m.group(1)) - wallMinusElapsedMs;
    }

    private static boolean isWatcherLine(String line) {
        return line.indexOf("RecordingActivity") >= 0
                || line.indexOf("NewRecordingFragment") >= 0
                || line.indexOf("SingLoadHelper") >= 0
                || line.indexOf("RecordLoadingModule") >= 0
                || line.indexOf("RecordService") >= 0
                || line.indexOf("RecordScoreModule") >= 0
                || line.indexOf("Module::Scorer") >= 0
                || line.indexOf("StreamPlayerV2") >= 0
                || line.indexOf("KaraM4aPlayer") >= 0
                || line.indexOf("KaraRecordService") >= 0
                || line.indexOf("AccompanySingController") >= 0
                || line.indexOf("BluetoothMusicManager") >= 0
                || line.indexOf("RecordDataSourceModule") >= 0
                || line.indexOf("RECORD_LOG_V8") >= 0;
    }

    // ---- event handling (watcher thread) ------------------------------------------

    private void onLine(String line) {
        Matcher m;
        // The page that loads the accompaniment states its length, which nothing else on this
        // tablet does reliably: the only other carrier is the Bluetooth session callback, and that
        // one describes whichever track its player last opened — the room's background singer while
        // we are looking at a recording page. Falls through, the line still carries identity.
        if (durationMs < 0L && (m = RE_DURATION.matcher(line)).find()) {
            durationMs = Long.parseLong(m.group(1));
        }
        if ((m = RE_ENTER.matcher(line)).find()) {
            if (!m.group(1).equals(songId)) {
                resetSong("song switch");
                songId = m.group(1);
                title = m.group(2).trim();
                active = true;
                playing = false;
                AppLog.i(TAG, "enter " + songId + " 「" + title + "」");
                // Cache-hit re-entries never log dealQrc; try the cloud fetch directly.
                loadTimeline(songId);
            }
            return;
        }
        if ((m = RE_DEAL_QRC.matcher(line)).find()) {
            if (!m.group(2).equals(songId)) {
                resetSong("qrc mismatch " + m.group(2));
                songId = m.group(2);
                active = true;
            }
            loadTimeline(songId);
            return;
        }
        if ((m = RE_ENTER_PARAM.matcher(line)).find()) {
            if (!m.group(1).equals(songId)) {
                resetSong("enterParam switch");
                songId = m.group(1);
                title = m.group(2).trim();
                active = true;
                playing = false;
                AppLog.i(TAG, "enterParam " + songId + " 「" + title + "」");
                loadTimeline(songId);
            } else if (title.isEmpty()) {
                title = m.group(2).trim();
            }
            return;
        }
        if ((m = RE_V8_SONGMID.matcher(line)).find()) {
            if (!m.group(1).equals(songId)) {
                resetSong("v8 song switch");
                songId = m.group(1);
                active = true;
                playing = false;
                AppLog.i(TAG, "v8 enter " + songId);
                loadTimeline(songId);
            }
            return;
        }
        if ((m = RE_META.matcher(line)).find() && m.group(1).equals(songId)) {
            title = m.group(2).trim();
            artist = m.group(3).trim();
            if (!m.group(1).equals(songMid)) {
                songMid = m.group(1);
                // META usually lands after ENTER; the id is richer now, so retry.
                if (timelineCache.get(songId) == null) loadTimeline(songId);
            }
            return;
        }
        if (RE_SING_PAUSE.matcher(line).find()) {
            if (takeLive && playing) {
                posAtPauseMs = Math.max(0L, positionNow());
                playing = false;
                AppLog.i(TAG, "pause sing pos=" + posAtPauseMs + "ms");
                publish();
            }
            return;
        }
        if (RE_SING_STOP.matcher(line).find()) {
            if (takeLive) {
                takeLive = false;
                pendingPositionMs = -1L;
                playing = false;
                posAtPauseMs = Math.max(0L, positionNow());
                AppLog.i(TAG, "stop sing, take closed at " + posAtPauseMs + "ms");
                publish();
            }
            return;
        }
        if ((m = RE_PLAYER_POSITION.matcher(line)).find()) {
            // WeSing states the absolute accompaniment position twice per seek: the service line
            // that really moves the running player, and an earlier controller line from the button
            // press. Only the service one is a position the audio has taken up — the controller
            // line fired 6.4s before the countdown actually started on this tablet.
            if (!takeLive) return;
            boolean service = m.group(1) != null;
            long position = Long.parseLong(service ? m.group(1) : m.group(2));
            long eventMs = eventElapsedMs(line);
            pendingPositionMs = position;
            pendingPositionEventMs = eventMs;
            if (service) {
                anchorFrom(eventMs - position, eventMs);
                posAtPauseMs = position;
                active = true;
                publish();
            }
            return;
        }
        if ((m = RE_PLAYER_START.matcher(line)).find()
                && (line.indexOf("StreamPlayerV2") >= 0 || line.indexOf("KaraM4aPlayer") >= 0)) {
            if (!takeLive) return;
            // The accompaniment begins running on this line, at the position the player was just
            // told to seek to. recordDelay here is the microphone's delay — the audio is already
            // flowing when it is logged — so it is deliberately not added.
            long eventMs = eventElapsedMs(line);
            // Pair the start with the seek that set its position, but only if the two really are
            // one command; a leftover drag from ten seconds earlier would anchor the song hundreds
            // of seconds off. With nothing to pair, playback resumes where the anchor already says.
            boolean paired = pendingPositionMs >= 0L
                    && Math.abs(eventMs - pendingPositionEventMs) <= PENDING_PAIR_MS;
            if (paired) {
                anchorFrom(eventMs - pendingPositionMs, eventMs);
                posAtPauseMs = pendingPositionMs;
            }
            active = true;
            playing = true;
            AppLog.i(TAG, "player start " + (paired
                    ? "pos=" + pendingPositionMs + "ms" : "kept anchor")
                    + " micDelay=" + m.group(1) + "ms");
            if (timelineCache.get(songId) == null) loadTimeline(songId);
            publish();
            return;
        }
        if ((m = RE_START_RECORD.matcher(line)).find()) {
            long delay = Long.parseLong(m.group(1));
            long eventMs = eventElapsedMs(line);
            // recordDelay here is the microphone's, not the accompaniment's: WeSing logs 3000 on a
            // retake while its own player line logs 0 and the audio starts within ~0.3s of this
            // line. Adding the countdown was the original "逐字进度对不上" — position 0 landed 2.2s
            // late. A fresh session always starts at the top of the song, so the position that
            // belongs with the player line that follows is 0.
            pendingPositionMs = 0L;
            pendingPositionEventMs = eventMs;
            takeLive = true;
            boolean haveExactAnchor = exactAnchorElapsedMs > 0L
                    && Math.abs(eventMs - exactAnchorEventMs) <= SCORER_TRUST_MS;
            anchorElapsedMs = haveExactAnchor ? exactAnchorElapsedMs : eventMs;
            posAtPauseMs = haveExactAnchor ? Math.max(0L, positionNow()) : 0L;
            active = true;
            playing = true;
            AppLog.i(TAG, "startRecord micDelay=" + delay + "ms → " + (haveExactAnchor
                    ? "kept player anchor" : "anchor at line"));
            // Second natural attempt point for a timeline that missed on ENTER.
            if (timelineCache.get(songId) == null) loadTimeline(songId);
            publish();
            return;
        }
        if ((m = RE_SCORER_POSITION.matcher(line)).find()) {
            if (!takeLive) return;
            long implied = (long) Float.parseFloat(m.group(2));
            long eventMs = eventElapsedMs(line);
            if ("true".equals(m.group(1))) {
                // The scorer's own zero coincides with the player start to within milliseconds on
                // both measured sessions, so a seek sample is an exact anchor.
                anchorFrom(eventMs - implied, eventMs);
                posAtPauseMs = implied;
                active = true;
                playing = true;
                publish();
                return;
            }
            if (implied <= 0L) return;
            // Between seeks the scorer only speaks when a sentence finishes being scored, and then
            // it trails the accompaniment by a steady -0.70s (348 samples, +-0.03s, over two full
            // sessions). Fine alignment must not chase that, so a sentence sample is only believed
            // when it disagrees with the player's own anchor by more than the guard — which is what
            // catches a drag our reader never saw.
            long deltaMs = implied - positionNow();
            if (anchorElapsedMs > 0L) {
                // Inside the guard the player's own lines are the authority; outside the slack the
                // sample cannot belong to this song at all (the previous one's tail right after a
                // switch would otherwise fly the panel to its last line).
                if (Math.abs(deltaMs) <= SCORER_GUARD_MS
                        || Math.abs(deltaMs) > POSITION_RESYNC_SLACK_MS) return;
            }
            anchorElapsedMs = eventMs - implied;
            posAtPauseMs = implied;
            active = true;
            playing = true;
            if (Math.abs(deltaMs) >= RESYNC_LOG_MS) {
                AppLog.i(TAG, "scorer resync pos=" + implied + "ms delta=" + deltaMs + "ms");
            }
            publish();
            return;
        }
        if ((m = RE_BT_PLAY.matcher(line)).find()) {
            // Pad builds log no enterRecordData/meta; SongInfo is their identity source.
            if (line.indexOf("unAuthed") >= 0 || !takeLive) return;
            // This is a media-session callback, so on this tablet it also fires for the room's
            // background singer and for the recording page's own preview — following its play/pause
            // walked the big screen before 开始演唱 had been pressed. Inside a live take it may only
            // confirm that the accompaniment is running.
            title = m.group(2).trim();
            artist = m.group(3).trim();
            if (durationMs < 0L && !m.group(4).isEmpty()) {
                durationMs = Long.parseLong(m.group(4)) * 1000L;
            }
            active = true;
            publish();
            return;
        }
        if ((m = RE_SCORE.matcher(line)).find() && m.group(1).equals(songId)) {
            playing = false;
            AppLog.i(TAG, "finished score=" + m.group(2));
            publish();
            return;
        }
    }

    private void resetSong(String reason) {
        if (songId.isEmpty()) return;
        AppLog.i(TAG, "leave " + title + " (" + reason + ")");
        songId = "";
        title = "";
        artist = "";
        songMid = "";
        durationMs = -1L;
        playing = false;
        active = false;
        anchorElapsedMs = 0L;
        posAtPauseMs = 0L;
        pendingPositionMs = -1L;
        takeLive = false;
        // exactAnchor* deliberately survive: on a retake WeSing's player lines arrive in a random
        // order, so dropping the position here is what left startRecord free to re-anchor the song
        // from the microphone countdown. The event-time windows keep a previous song's anchor from
        // being reused.
        timelineAttempts = 0;
    }

    /** Sets the running anchor and records that WeSing itself stated this position. */
    private void anchorFrom(long anchorMs, long eventMs) {
        anchorElapsedMs = anchorMs;
        exactAnchorElapsedMs = anchorMs;
        exactAnchorEventMs = eventMs;
    }

    /** Song position implied by the anchor (0 while not anchored, clamped to duration). */
    private long positionNow() {
        if (anchorElapsedMs <= 0L) return 0L;
        long position = playing
                ? SystemClock.elapsedRealtime() - anchorElapsedMs : posAtPauseMs;
        if (durationMs > 0L) position = Math.min(position, durationMs);
        return Math.max(0L, position);
    }

    // ---- lyric sources (lyric executor) -------------------------------------------

    /**
     * Loads the song from WeSing's own QRC, mirrored into our files/qrc directory. The
     * files are named {@code {songId}[_2]_{original|dynamic|...}.qrc} and are
     * DES-encrypted with the same key as the QQ cloud catalogue, so
     * {@link QrcLyricCodec} decrypts them directly. Their timing is authored against the
     * accompaniment WeSing plays, which is why they need no offset correction.
     * Returns {@code null} when the song has not been opened in WeSing yet.
     */
    private LrcTimeline tryLoadMirroredQrc(String songId) {
        if (songId == null || songId.isEmpty()) return null;
        File dir = context.getExternalFilesDir(QRC_MIRROR_DIR);
        if (dir == null || !dir.isDirectory()) {
            AppLog.i(TAG, "mirrored QRC skip: dir missing");
            return null;
        }
        // Same preference as before the prefix scan: line-level original first, then the
        // word-level dynamic variant; the "_2" duet variants rank below their plain twin.
        String[] suffixes = {"_original.qrc", "_dynamic.qrc",
                "_2_original.qrc", "_2_dynamic.qrc"};
        File[] present = dir.listFiles();
        if (present == null) return null;
        for (String suffix : suffixes) {
            for (File file : present) {
                String name = file.getName();
                if (!name.startsWith(songId) || !name.endsWith(suffix)) continue;
                LrcTimeline timeline = loadQrcFile(file);
                if (timeline != null) return timeline;
            }
        }
        AppLog.i(TAG, "mirrored QRC skip: no file for " + songId);
        return null;
    }

    private static LrcTimeline loadQrcFile(File file) {
        try {
            String hex = readTextFile(file);
            if (hex.isEmpty()) return null;
            String qrc = QrcLyricCodec.decryptToTimedLyric(hex);
            if (qrc == null || qrc.isEmpty()) {
                AppLog.w(TAG, "mirrored QRC decrypt empty for " + file.getName());
                return null;
            }
            LrcTimeline timeline = LrcTimeline.parse("", "", qrc);
            if (timeline.isEmpty()) return null;
            AppLog.i(TAG, "mirrored QRC ok: " + file.getName()
                    + " lines=" + timeline.lineCount());
            return timeline;
        } catch (Throwable error) {
            AppLog.w(TAG, "mirrored QRC failed for " + file.getName() + ": " + error);
            return null;
        }
    }

    private static String readTextFile(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) > 0) out.write(buffer, 0, count);
            return out.toString("UTF-8").trim();
        }
    }

    /** Kicks the QQ-catalogue fetch; the poll loop never blocks on the network. */
    private void loadTimeline(String id) {
        if (id.isEmpty()) return;
        LrcTimeline cached = timelineCache.get(id);
        if (cached != null) {
            publish();
            return;
        }
        if (timelineAttempts >= MAX_TIMELINE_ATTEMPTS) return;
        timelineAttempts++;
        // Snapshot volatile metadata; the fetch runs on the shared lyric executor. On pad
        // builds the QQ songMid arrives as songId itself (V8 line), phones carry it in
        // the meta line — the fetch ladder takes whichever is a usable identity.
        final String target = id;
        final String mid = songMid.isEmpty() ? songId : songMid;
        final String songTitle = title;
        final String songArtist = artist;
        final long duration = durationMs;
        MusicStateStore.LYRIC_EXECUTOR.execute(() -> {
            // WeSing's own QRC is synced to the accompaniment; the QQ cloud catalogue
            // returns the original singer's timing instead.
            LrcTimeline timeline = tryLoadMirroredQrc(target);
            if (timeline != null && !timeline.isEmpty()) {
                timelineCache.put(target, timeline);
                if (timelineCache.size() > TIMELINE_CACHE_LIMIT) {
                    Iterator<String> oldest = timelineCache.keySet().iterator();
                    if (oldest.hasNext()) oldest.remove();
                }
                publish();
                return;
            }
            try {
                timeline = lyricClient()
                        .loadForWeSing(target, mid, songTitle, songArtist, duration);
                if (timeline == null || timeline.lineCount() == 0) {
                    AppLog.w(TAG, "cloud lyric empty for " + target);
                    return;
                }
                timelineCache.put(target, timeline);
                if (timelineCache.size() > TIMELINE_CACHE_LIMIT) {
                    Iterator<String> oldest = timelineCache.keySet().iterator();
                    if (oldest.hasNext()) oldest.remove();
                }
                AppLog.i(TAG, "cloud lyric loaded " + target
                        + " lines=" + timeline.lineCount());
                publish();
            } catch (Throwable error) {
                AppLog.w(TAG, "cloud lyric failed for " + target + ": " + error);
            }
        });
    }

    private QQMusicLyricClient lyricClient() {
        QQMusicLyricClient current = lyricClient;
        if (current == null) {
            synchronized (this) {
                if (lyricClient == null) lyricClient = new QQMusicLyricClient(context);
                current = lyricClient;
            }
        }
        return current;
    }

    // ---- store feed ----------------------------------------------------------------

    /** Pushes the current song into the store so the regular publisher carries it on. */
    private void publish() {
        if (!active || songId.isEmpty()) return;
        LrcTimeline timeline = timelineCache.get(songId);
        MusicPlaybackData data = new MusicPlaybackData(
                songId, title, artist, null, null, null,
                durationMs > 0L ? durationMs : -1L,
                true, playing
                        ? MusicPlaybackData.STATE_PLAYING : MusicPlaybackData.STATE_PAUSED,
                positionNow(), SystemClock.elapsedRealtime(), 1f,
                false, "", timeline == null ? LrcTimeline.EMPTY : timeline);
        MusicStateStore.update(context, "wesing", "全民K歌", "com.tencent.karaoke", data);
    }
}
