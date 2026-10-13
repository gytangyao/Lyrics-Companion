package wemew.lrc.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PublisherDiffTest {
    private static final long DRIFT = 700L;
    private static final long INTERVAL = 1_000L;

    @org.junit.Test public void firstObservationPushesTrackAndState() {
        PublisherDiff.State state = new PublisherDiff.State();
        PublisherDiff.Decision decision = PublisherDiff.update(state, "a", true, true, 1f,
                1000L, "", 10_000L, DRIFT, INTERVAL);
        assertTrue(decision.track);
        assertFalse(decision.state);
        assertFalse(decision.line);
    }

    @org.junit.Test public void quietPlaybackPushesNothing() {
        PublisherDiff.State state = new PublisherDiff.State();
        PublisherDiff.update(state, "a", true, true, 1f, 1000L, "", 10_000L, DRIFT, INTERVAL);
        // Exactly the predicted position half a second later: nothing to send.
        PublisherDiff.Decision decision = PublisherDiff.update(state, "a", true, true, 1f,
                1500L, "", 10_500L, DRIFT, INTERVAL);
        assertFalse(decision.track);
        assertFalse(decision.state);
    }

    @org.junit.Test public void periodicAnchorWhilePlaying() {
        PublisherDiff.State state = new PublisherDiff.State();
        PublisherDiff.update(state, "a", true, true, 1f, 1000L, "", 10_000L, DRIFT, INTERVAL);
        PublisherDiff.Decision decision = PublisherDiff.update(state, "a", true, true, 1f,
                2000L, "", 11_000L, DRIFT, INTERVAL);
        assertFalse(decision.track);
        assertTrue(decision.state);
    }

    @org.junit.Test public void pausedPlaybackStaysQuiet() {
        PublisherDiff.State state = new PublisherDiff.State();
        PublisherDiff.update(state, "a", true, false, 1f, 1000L, "", 10_000L, DRIFT, INTERVAL);
        // Paused and unmoved: no periodic anchors are needed.
        PublisherDiff.Decision far = PublisherDiff.update(state, "a", true, false, 1f,
                1000L, "", 600_000L, DRIFT, INTERVAL);
        assertFalse(far.track);
        assertFalse(far.state);
    }

    @org.junit.Test public void pauseAndResumePushStateImmediately() {
        PublisherDiff.State state = new PublisherDiff.State();
        PublisherDiff.update(state, "a", true, true, 1f, 1000L, "", 10_000L, DRIFT, INTERVAL);
        PublisherDiff.Decision paused = PublisherDiff.update(state, "a", true, false, 1f,
                1000L, "", 10_100L, DRIFT, INTERVAL);
        assertTrue(paused.state);

        PublisherDiff.Decision resumed = PublisherDiff.update(state, "a", true, true, 1f,
                1000L, "", 10_200L, DRIFT, INTERVAL);
        assertTrue(resumed.state);
    }

    @org.junit.Test public void seekBeyondDriftWindowPushesState() {
        PublisherDiff.State state = new PublisherDiff.State();
        PublisherDiff.update(state, "a", true, true, 1f, 1000L, "", 10_000L, DRIFT, INTERVAL);
        // Client drift of 5 seconds in half a second of wall time is a seek.
        PublisherDiff.Decision decision = PublisherDiff.update(state, "a", true, true, 1f,
                6000L, "", 10_500L, DRIFT, INTERVAL);
        assertFalse(decision.track);
        assertTrue(decision.state);
    }

    @org.junit.Test public void smallCorrectionInsideDriftWindowIsIgnored() {
        PublisherDiff.State state = new PublisherDiff.State();
        PublisherDiff.update(state, "a", true, true, 1f, 1000L, "", 10_000L, DRIFT, INTERVAL);
        PublisherDiff.Decision decision = PublisherDiff.update(state, "a", true, true, 1f,
                1300L, "", 10_500L, DRIFT, INTERVAL);
        assertFalse(decision.state);
    }

    @org.junit.Test public void trackChangePushesTrackAndSkipsState() {
        PublisherDiff.State state = new PublisherDiff.State();
        PublisherDiff.update(state, "a", true, true, 1f, 1000L, "", 10_000L, DRIFT, INTERVAL);
        PublisherDiff.Decision decision = PublisherDiff.update(state, "b", true, true, 1f,
                50_000L, "", 12_000L, DRIFT, INTERVAL);
        assertTrue(decision.track);
        assertFalse(decision.state);
    }

    @org.junit.Test public void liveLineChangePushesLine() {
        PublisherDiff.State state = new PublisherDiff.State();
        PublisherDiff.update(state, "a", true, true, 1f, 1000L, "first", 10_000L, DRIFT,
                INTERVAL);
        PublisherDiff.Decision decision = PublisherDiff.update(state, "a", true, true, 1f,
                1500L, "second", 10_500L, DRIFT, INTERVAL);
        assertTrue(decision.line);
        assertFalse(decision.track);
        assertFalse(decision.state);
    }

    @org.junit.Test public void speedChangePushesState() {
        PublisherDiff.State state = new PublisherDiff.State();
        PublisherDiff.update(state, "a", true, true, 1f, 1000L, "", 10_000L, DRIFT, INTERVAL);
        PublisherDiff.Decision decision = PublisherDiff.update(state, "a", true, true, 2f,
                1500L, "", 10_500L, DRIFT, INTERVAL);
        assertTrue(decision.state);
    }

    @org.junit.Test public void statePushReanchorsThePrediction() {
        PublisherDiff.State state = new PublisherDiff.State();
        PublisherDiff.update(state, "a", true, true, 1f, 1000L, "", 10_000L, DRIFT, INTERVAL);
        // A seek pushes state at 10_500; the anchor moves so the next tick stays quiet.
        PublisherDiff.update(state, "a", true, true, 1f, 6000L, "", 10_500L, DRIFT, INTERVAL);
        PublisherDiff.Decision decision = PublisherDiff.update(state, "a", true, true, 1f,
                6000L, "", 11_000L, DRIFT, INTERVAL);
        assertFalse(decision.state);
    }
}
