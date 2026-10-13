package com.zuoqirun.lyricscompanion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class SpectrumCaptureDecisionTest {
    private static final SpectrumCaptureDecision.Outcome decide(
            boolean enabled, boolean playing, boolean recordGranted,
            boolean visualizerRunning, boolean captureRunning,
            boolean visualizerStartFailed, boolean silentTooLong,
            boolean alreadyFellBack, boolean hasConsent, int apiLevel) {
        return SpectrumCaptureDecision.evaluate(enabled, playing, recordGranted,
                visualizerRunning, captureRunning, visualizerStartFailed, silentTooLong,
                alreadyFellBack, hasConsent, apiLevel);
    }

    @Test public void disabledOrUnpermittedStopsEverything() {
        assertEquals(SpectrumCaptureDecision.Outcome.STOP,
                decide(false, true, true, true, false, false, false, false, true, 33));
        assertEquals(SpectrumCaptureDecision.Outcome.NEED_PERMISSION,
                decide(true, true, false, true, false, false, false, false, true, 33));
    }

    @Test public void pausedPlaybackHoldsTheGrantButReleasesCapture() {
        assertEquals(SpectrumCaptureDecision.Outcome.PAUSED,
                decide(true, false, true, true, false, false, false, false, true, 33));
    }

    @Test public void healthyVisualizerIsLeftAlone() {
        assertEquals(SpectrumCaptureDecision.Outcome.KEEP_CURRENT,
                decide(true, true, true, true, false, false, false, false, false, 28));
    }

    @Test public void silentVisualizerDemotesOnlyOnceAndOnlyWithConsentOnTenPlus() {
        assertEquals(SpectrumCaptureDecision.Outcome.FALLBACK_TO_PLAYBACK_CAPTURE,
                decide(true, true, true, true, false, false, true, false, true, 33));
        // Already demoted once this session: no flapping back and forth.
        assertEquals(SpectrumCaptureDecision.Outcome.KEEP_CURRENT,
                decide(true, true, true, true, false, false, true, true, true, 33));
        // Nothing to demote to without a consent token.
        assertEquals(SpectrumCaptureDecision.Outcome.KEEP_CURRENT,
                decide(true, true, true, true, false, false, true, false, false, 33));
        // Pre-10 has no playback capture at all.
        assertEquals(SpectrumCaptureDecision.Outcome.KEEP_CURRENT,
                decide(true, true, true, true, false, false, true, false, true, 28));
    }

    @Test public void freshSessionTriesTheVisualizerFirst() {
        assertEquals(SpectrumCaptureDecision.Outcome.TRY_VISUALIZER,
                decide(true, true, true, false, false, false, false, false, false, 33));
    }

    @Test public void failedVisualizerRoutesByApiAndConsent() {
        assertEquals(SpectrumCaptureDecision.Outcome.UNSUPPORTED,
                decide(true, true, true, false, false, true, false, false, false, 28));
        assertEquals(SpectrumCaptureDecision.Outcome.START_PLAYBACK_CAPTURE,
                decide(true, true, true, false, false, true, false, false, true, 33));
        assertEquals(SpectrumCaptureDecision.Outcome.NEED_CONSENT,
                decide(true, true, true, false, false, true, false, false, false, 33));
    }

    @Test public void runningCaptureIsKept() {
        assertEquals(SpectrumCaptureDecision.Outcome.KEEP_CURRENT,
                decide(true, true, true, false, true, false, false, true, true, 33));
    }
}
