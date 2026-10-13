package wemew.lrc.sync;

/**
 * Pure capture-path decision for {@link AudioSpectrumSource}, kept free of Android APIs so the
 * fallback machine is unit-testable the way {@link OverlayPlaybackVisibility} is.
 *
 * <p>The auto mode prefers the platform Visualizer (works on old head units, no projection
 * consent); Android 10+ ROMs usually start it but keep it silent, so a silent-too-long
 * Visualizer demotes to playback capture once a consent token exists.
 */
final class SpectrumCaptureDecision {
    static final int MIN_PLAYBACK_CAPTURE_API = 29;

    enum Outcome {
        /** Nothing is requested; release every capture resource. */
        STOP,
        /** Playback is paused; release capture but keep the projection grant for a fast resume. */
        PAUSED,
        /** RECORD_AUDIO is missing; nothing can run. */
        NEED_PERMISSION,
        /** Visualizer or playback capture is already running; leave it alone. */
        KEEP_CURRENT,
        /** Start (or retry) the platform Visualizer. */
        TRY_VISUALIZER,
        /** The Visualizer stayed silent too long; one-shot demotion to playback capture. */
        FALLBACK_TO_PLAYBACK_CAPTURE,
        /** The Visualizer cannot run here; a stored consent makes playback capture possible. */
        START_PLAYBACK_CAPTURE,
        /** Android 10+ without consent: ask the user to authorize the projection. */
        NEED_CONSENT,
        /** Pre-10 ROM without a working Visualizer: nothing this app can do. */
        UNSUPPORTED
    }

    private SpectrumCaptureDecision() { }

    static Outcome evaluate(boolean enabled, boolean playing, boolean recordGranted,
                            boolean visualizerRunning, boolean playbackCaptureRunning,
                            boolean visualizerStartFailed, boolean visualizerSilentTooLong,
                            boolean alreadyFellBack, boolean hasConsent, int apiLevel) {
        if (!enabled) return Outcome.STOP;
        if (!recordGranted) return Outcome.NEED_PERMISSION;
        if (!playing) return Outcome.PAUSED;
        if (visualizerRunning) {
            if (!alreadyFellBack && visualizerSilentTooLong && hasConsent
                    && apiLevel >= MIN_PLAYBACK_CAPTURE_API) {
                return Outcome.FALLBACK_TO_PLAYBACK_CAPTURE;
            }
            return Outcome.KEEP_CURRENT;
        }
        if (playbackCaptureRunning) return Outcome.KEEP_CURRENT;
        if (visualizerStartFailed) {
            if (apiLevel < MIN_PLAYBACK_CAPTURE_API) return Outcome.UNSUPPORTED;
            return hasConsent ? Outcome.START_PLAYBACK_CAPTURE : Outcome.NEED_CONSENT;
        }
        return Outcome.TRY_VISUALIZER;
    }
}
