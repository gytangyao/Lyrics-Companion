package wemew.lrc.sync;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.audiofx.Visualizer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * One process-wide spectrum producer, ported from audio_flux's AudioCaptureEngine and fused
 * with this app's original Visualizer source. Two capture paths feed the same frame pipeline:
 *
 * <ul>
 *   <li>System mix — the platform {@link Visualizer} on session 0. Works on old head units
 *       with just RECORD_AUDIO; Android 10+ ROMs commonly start it but keep it permanently
 *       silent because a third-party app lacks CAPTURE_AUDIO_OUTPUT.</li>
 *   <li>Playback capture — MediaProjection consent + AudioPlaybackCapture + our own FFT
 *       ({@link SpectrumFft}), the reliable Android 10+ path.</li>
 * </ul>
 *
 * A one-second watchdog demotes a silent Visualizer to playback capture when a consent token
 * exists ({@link SpectrumCaptureDecision} holds the rules). Capture runs only while the
 * spectrum feature is on, an overlay is enabled and music is playing.
 */
final class AudioSpectrumSource {
    private static final String TAG = "LyricsSpectrum";
    private static final int SAMPLE_RATE = 44_100;
    private static final int FFT_SIZE = 1024;
    /** Folded linear bins out of {@link SpectrumFft}, before the log-band fold. */
    private static final int FFT_BINS = 256;
    /** Waveform points carried per frame for the wave style. */
    static final int WAVE_POINTS = 48;
    private static final float[] SILENCE = new float[SpectrumMath.BAND_COUNT];
    private static final float[] SILENT_WAVE = new float[WAVE_POINTS];
    private static final float FLOOR_DB = -70f;
    private static final long SILENT_FALLBACK_MS = 2_000L;
    private static final long WATCHDOG_TICK_MS = 1_000L;

    private static final SpectrumMath.Analyzer ANALYZER = new SpectrumMath.Analyzer();
    private static final SpectrumFft FFT = new SpectrumFft(FFT_SIZE);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Frame latestFrame = new Frame(SILENCE, SILENT_WAVE, false);
    /** Last waveform seen from the Visualizer; u8 PCM with its zero point at 128. */
    private static volatile float[] visualizerWave = new float[WAVE_POINTS];

    private static Visualizer visualizer;
    private static MediaProjection mediaProjection;
    /** Consent version the live projection was built from; see {@link #obtainProjection}. */
    private static int projectionVersion;
    private static AudioRecord audioRecord;
    private static Thread captureThread;
    private static volatile boolean capturing;
    private static Context appContext;

    private static String state = "待命";
    private static boolean playbackActive;
    /** This ROM refused the Visualizer; retrying needs a fresh session (stop-all). */
    private static boolean visualizerStartFailed;
    /** One-shot demotion from a silent Visualizer to playback capture for this session. */
    private static boolean fellBackToPlaybackCapture;
    private static volatile long lastSignalAtMs;
    private static long visualizerStartedAtMs;
    private static boolean watchdogRunning;

    private AudioSpectrumSource() { }

    static synchronized void sync(Context context) {
        if (context != null) appContext = context.getApplicationContext();
        Context prefs = appContext != null ? appContext : context;
        if (prefs == null) return;
        boolean enabled = AppPreferences.spectrumEnabled(prefs)
                && (AppPreferences.mainEnabled(prefs) || AppPreferences.secondaryEnabled(prefs));
        boolean recordGranted = Build.VERSION.SDK_INT < 23 || prefs.checkSelfPermission(
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        SpectrumCaptureDecision.Outcome outcome = SpectrumCaptureDecision.evaluate(
                enabled, playbackActive, recordGranted,
                visualizer != null, audioRecord != null, visualizerStartFailed,
                visualizerSilentTooLong(), fellBackToPlaybackCapture,
                ProjectionTokenStore.hasConsent(), Build.VERSION.SDK_INT);
        switch (outcome) {
            case STOP:
                stopAll("待命");
                break;
            case NEED_PERMISSION:
                stopAll("未授权录音权限");
                break;
            case PAUSED:
                pauseCapture("播放暂停：已释放频谱采集");
                break;
            case TRY_VISUALIZER:
            case KEEP_CURRENT:
                ensureWatchdog();
                if (outcome == SpectrumCaptureDecision.Outcome.TRY_VISUALIZER) {
                    startVisualizer(prefs);
                }
                break;
            case FALLBACK_TO_PLAYBACK_CAPTURE:
                fellBackToPlaybackCapture = true;
                stopVisualizerOnly();
                Log.i(TAG, "Visualizer stayed silent; falling back to playback capture");
                startPlaybackCaptureViaService();
                break;
            case START_PLAYBACK_CAPTURE:
                startPlaybackCaptureViaService();
                break;
            case NEED_CONSENT:
                state = "此系统限制混音采集，请授权内录";
                break;
            case UNSUPPORTED:
            default:
                state = "当前 ROM 不支持系统音频频谱";
                break;
        }
        Log.i(TAG, "sync outcome=" + outcome + " state=" + state
                + " playing=" + playbackActive
                + " consent=" + ProjectionTokenStore.hasConsent()
                + " visualizer=" + (visualizer != null) + " record=" + (audioRecord != null));
    }

    static synchronized void release() {
        stopAll("待命");
    }

    static Frame latestFrame() {
        Frame frame = latestFrame;
        if (frame.live && SystemClock.elapsedRealtime() - frame.capturedAtMs > 1_500L) {
            return new Frame(frame.levels, frame.wave, false);
        }
        return frame;
    }

    static synchronized void setPlaybackActive(Context context, boolean active) {
        if (playbackActive == active) return;
        playbackActive = active;
        sync(context);
    }

    static String status(Context context) {
        if (context == null) return state;
        if (!AppPreferences.spectrumEnabled(context)) return "频谱背景已关闭";
        if (Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission(
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "未授权录音权限，无法采集频谱";
        }
        return state;
    }

    // ------------------------------------------------------------------
    // System mix: platform Visualizer on session 0
    // ------------------------------------------------------------------

    private static void startVisualizer(Context context) {
        try {
            Visualizer candidate = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            boolean highRate = "high".equals(AppPreferences.realSpectrumCaptureRate(context));
            int captureSize = chooseCaptureSize(range, highRate);
            if (candidate.setCaptureSize(captureSize) != Visualizer.SUCCESS) {
                candidate.release();
                visualizerStartFailed = true;
                state = "设备拒绝频谱采样";
                redispatchAfterVisualizerFailure(context);
                return;
            }
            // Low mode suits genuinely ancient head units; high asks for the device maximum so
            // the bars feel locked to the beat instead of lagging a fifth of a second behind.
            int requestedRate = highRate ? 1_000_000 : 10_000;
            int captureRate = Math.max(1, Math.min(requestedRate, Visualizer.getMaxCaptureRate()));
            int listenerResult = candidate.setDataCaptureListener(
                    new Visualizer.OnDataCaptureListener() {
                        @Override public void onWaveFormDataCapture(Visualizer ignored,
                                                                    byte[] waveform,
                                                                    int samplingRate) {
                            fillVisualizerWave(waveform);
                        }

                        @Override public void onFftDataCapture(Visualizer ignored, byte[] fft,
                                                               int samplingRate) {
                            float[] levels = ANALYZER.process(fft, samplingRate);
                            if (!SpectrumMath.isSilent(levels)) {
                                lastSignalAtMs = SystemClock.elapsedRealtime();
                            }
                            latestFrame = new Frame(levels, visualizerWave.clone(), true,
                                    SystemClock.elapsedRealtime());
                        }
                    }, captureRate, true, true);
            if (listenerResult != Visualizer.SUCCESS) {
                candidate.release();
                visualizerStartFailed = true;
                state = "设备拒绝频谱回调";
                redispatchAfterVisualizerFailure(context);
                return;
            }
            candidate.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            if (candidate.setEnabled(true) != Visualizer.SUCCESS) {
                candidate.release();
                visualizerStartFailed = true;
                state = "设备拒绝启用频谱效果";
                redispatchAfterVisualizerFailure(context);
                return;
            }
            visualizer = candidate;
            visualizerStartFailed = false;
            visualizerStartedAtMs = SystemClock.elapsedRealtime();
            lastSignalAtMs = visualizerStartedAtMs;
            latestFrame = new Frame(SILENCE, SILENT_WAVE, false);
            state = "系统混音：" + (captureRate / 1_000) + " Hz / " + captureSize + " 点";
        } catch (Throwable error) {
            Log.w(TAG, "Unable to capture system audio spectrum", error);
            visualizerStartFailed = true;
            stopVisualizerOnly();
            state = "设备混音采集不可用";
            redispatchAfterVisualizerFailure(context);
        }
    }

    /** Hands the decision back to {@link #sync} so a failed Visualizer can demote to capture. */
    private static void redispatchAfterVisualizerFailure(Context context) {
        if (playbackActive) sync(context);
    }

    private static void fillVisualizerWave(byte[] waveform) {
        if (waveform == null || waveform.length == 0) return;
        float[] next = new float[WAVE_POINTS];
        for (int i = 0; i < WAVE_POINTS; i++) {
            int index = i * waveform.length / WAVE_POINTS;
            next[i] = ((waveform[index] & 0xFF) - 128) / 128f;
        }
        visualizerWave = next;
    }

    private static int chooseCaptureSize(int[] range, boolean highRate) {
        int minimum = range != null && range.length > 0 ? range[0] : 128;
        int maximum = range != null && range.length > 1 ? range[1] : 1024;
        int target = highRate ? 512 : 128;
        int selected = 1;
        while (selected < target) selected *= 2;
        return Math.max(minimum, Math.min(maximum, selected));
    }

    // ------------------------------------------------------------------
    // Playback capture: MediaProjection + AudioRecord + own FFT
    // ------------------------------------------------------------------

    /**
     * Starts the capture engine from {@link SpectrumCaptureService} once its typed foreground
     * notification is up. The service stops itself again when this returns false.
     */
    static synchronized boolean startPlaybackCapture(Context context) {
        if (Build.VERSION.SDK_INT < SpectrumCaptureDecision.MIN_PLAYBACK_CAPTURE_API) return false;
        // The service starts asynchronously; music may have paused or the feature been switched
        // off meanwhile. Refusing makes the service stop itself instead of recording blindly.
        if (!playbackActive || !AppPreferences.spectrumEnabled(context)) return false;
        if (audioRecord != null) return true;
        stopVisualizerOnly();
        try {
            Context app = context.getApplicationContext();
            MediaProjection projection = obtainProjection(app);
            if (projection == null) {
                state = "内录授权已失效，请重新授权";
                return false;
            }
            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
            int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord record = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuffer, FFT_SIZE * 4))
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                state = "内录初始化失败，可能被系统限制";
                return false;
            }
            record.startRecording();
            audioRecord = record;
            capturing = true;
            captureThread = new Thread(() -> runCaptureLoop(record), "spectrum-capture");
            captureThread.start();
            state = "应用内录：44.1 kHz / FFT 1024";
            Log.i(TAG, "Playback capture running via AudioRecord");
            ensureWatchdog();
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Playback capture failed", error);
            releaseRecordOnly();
            state = "内录启动失败：" + (error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
            return false;
        }
    }

    /**
     * Android 14 consumes a consent result on every {@code getMediaProjection} call, so the
     * live projection is kept across pause/resume and only rebuilt for a brand-new consent.
     */
    private static MediaProjection obtainProjection(Context context) {
        // Self-contained guard: lint cannot see that every caller already checked, and the
        // projection APIs below are 21+ (the capture config itself needs 29+).
        if (Build.VERSION.SDK_INT < SpectrumCaptureDecision.MIN_PLAYBACK_CAPTURE_API) return null;
        if (!ProjectionTokenStore.hasConsent()) return null;
        if (mediaProjection != null
                && projectionVersion == ProjectionTokenStore.version()) {
            return mediaProjection;
        }
        releaseProjectionQuietly();
        MediaProjectionManager manager = (MediaProjectionManager)
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) return null;
        try {
            MediaProjection projection = manager.getMediaProjection(
                    ProjectionTokenStore.resultCode(), ProjectionTokenStore.resultData());
            if (projection == null) return null;
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    MAIN.post(() -> onProjectionStopped());
                }
            }, MAIN);
            mediaProjection = projection;
            projectionVersion = ProjectionTokenStore.version();
            return projection;
        } catch (Throwable error) {
            Log.w(TAG, "Unable to rebuild MediaProjection", error);
            return null;
        }
    }

    private static void onProjectionStopped() {
        Log.i(TAG, "MediaProjection stopped by the system");
        ProjectionTokenStore.clear();
        stopAll("内录已停止，需重新授权");
    }

    private static void runCaptureLoop(AudioRecord record) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] pcmShorts = new short[FFT_SIZE];
        float[] pcmFloats = new float[FFT_SIZE];
        float[] linear = new float[FFT_BINS];
        float[] wave = new float[WAVE_POINTS];
        while (capturing) {
            int read;
            try {
                read = record.read(pcmShorts, 0, FFT_SIZE);
            } catch (Exception error) {
                Log.w(TAG, "AudioRecord read failed", error);
                break;
            }
            if (read <= 0) {
                if (read < 0) break;
                continue;
            }
            for (int i = 0; i < FFT_SIZE; i++) {
                pcmFloats[i] = i < read ? pcmShorts[i] / 32768f : 0f;
            }
            FFT.magnitudes(pcmFloats, read, linear, FLOOR_DB);
            float[] levels = ANALYZER.processLinear(linear, SAMPLE_RATE);
            if (!SpectrumMath.isSilent(levels)) lastSignalAtMs = SystemClock.elapsedRealtime();
            for (int i = 0; i < WAVE_POINTS; i++) {
                wave[i] = pcmFloats[i * read / WAVE_POINTS];
            }
            latestFrame = new Frame(levels, wave.clone(), true, SystemClock.elapsedRealtime());
        }
    }

    private static void startPlaybackCaptureViaService() {
        Context context = appContext;
        if (context == null || Build.VERSION.SDK_INT
                < SpectrumCaptureDecision.MIN_PLAYBACK_CAPTURE_API) {
            state = "应用内录需要 Android 10 及以上系统";
            return;
        }
        if (!ProjectionTokenStore.hasConsent()) {
            state = "此系统限制混音采集，请授权内录";
            return;
        }
        SpectrumCaptureService.start(context);
        state = "正在启动内录…";
    }

    // ------------------------------------------------------------------
    // Watchdog and teardown
    // ------------------------------------------------------------------

    private static final Runnable watchdog = new Runnable() {
        private int tick;

        @Override public void run() {
            if (visualizer != null && playbackActive && !fellBackToPlaybackCapture
                    && ProjectionTokenStore.hasConsent()
                    && Build.VERSION.SDK_INT >= SpectrumCaptureDecision.MIN_PLAYBACK_CAPTURE_API
                    && visualizerSilentTooLong()) {
                fellBackToPlaybackCapture = true;
                stopVisualizerOnly();
                Log.i(TAG, "Visualizer silent for " + SILENT_FALLBACK_MS + "ms; demoting");
                startPlaybackCaptureViaService();
            } else if (++tick % 5 == 0) {
                Frame frame = latestFrame;
                float peak = 0f;
                if (frame.live) {
                    for (float value : frame.levels) peak = Math.max(peak, value);
                }
                Log.i(TAG, "watchdog visualizer=" + (visualizer != null)
                        + " record=" + (audioRecord != null)
                        + " playing=" + playbackActive
                        + " framePeak=" + String.format(java.util.Locale.US, "%.3f", peak)
                        + " frameAgeMs=" + (frame.live
                        ? SystemClock.elapsedRealtime() - frame.capturedAtMs : -1));
            }
            MAIN.postDelayed(this, WATCHDOG_TICK_MS);
        }
    };

    private static void ensureWatchdog() {
        if (watchdogRunning) return;
        watchdogRunning = true;
        MAIN.postDelayed(watchdog, WATCHDOG_TICK_MS);
    }

    private static void stopWatchdog() {
        watchdogRunning = false;
        MAIN.removeCallbacks(watchdog);
    }

    private static boolean visualizerSilentTooLong() {
        if (visualizer == null) return false;
        long now = SystemClock.elapsedRealtime();
        long reference = Math.max(visualizerStartedAtMs, lastSignalAtMs);
        return now - reference >= SILENT_FALLBACK_MS;
    }

    /** Stops capture but keeps the projection grant: a resume must not re-ask for consent. */
    private static synchronized void pauseCapture(String message) {
        stopVisualizerOnly();
        releaseRecordOnly();
        requestServiceStop();
        latestFrame = new Frame(SILENCE, SILENT_WAVE, false);
        state = message;
    }

    private static synchronized void stopAll(String message) {
        stopWatchdog();
        stopVisualizerOnly();
        releaseRecordOnly();
        releaseProjectionQuietly();
        fellBackToPlaybackCapture = false;
        visualizerStartFailed = false;
        requestServiceStop();
        latestFrame = new Frame(SILENCE, SILENT_WAVE, false);
        state = message;
    }

    /** The capture service died; only the record loop has to go, the grant stays usable. */
    static synchronized void onCaptureServiceDestroyed() {
        releaseRecordOnly();
    }

    private static void requestServiceStop() {
        Context context = appContext;
        if (context != null) SpectrumCaptureService.requestStop(context);
    }

    private static void stopVisualizerOnly() {
        if (visualizer == null) return;
        try {
            visualizer.setEnabled(false);
            visualizer.release();
        } catch (Throwable ignored) { }
        visualizer = null;
    }

    private static void releaseRecordOnly() {
        capturing = false;
        Thread thread = captureThread;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }
        AudioRecord record = audioRecord;
        audioRecord = null;
        if (record != null) {
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop();
                }
                record.release();
            } catch (Throwable ignored) { }
        }
    }

    private static void releaseProjectionQuietly() {
        if (Build.VERSION.SDK_INT < SpectrumCaptureDecision.MIN_PLAYBACK_CAPTURE_API) return;
        MediaProjection projection = mediaProjection;
        mediaProjection = null;
        if (projection != null) {
            try {
                projection.stop();
            } catch (Throwable ignored) { }
        }
    }

    static final class Frame {
        final float[] levels;
        final float[] wave;
        final boolean live;
        final long capturedAtMs;

        Frame(float[] levels, float[] wave, boolean live) {
            this(levels, wave, live, live ? SystemClock.elapsedRealtime() : 0L);
        }

        Frame(float[] levels, float[] wave, boolean live, long capturedAtMs) {
            this.levels = levels;
            this.wave = wave;
            this.live = live;
            this.capturedAtMs = capturedAtMs;
        }
    }
}
