package wemew.lrc.sync;

/**
 * Pure decision core of {@link WebStatePublisher}: given the previous pushed state and the
 * current observation, decides which protocol messages to send. Kept free of Android types
 * so the transition rules are unit-testable.
 *
 * <p>Rules (see docs/web-protocol.md): {@code track} on any track/timeline identity change;
 * {@code state} on playback change, speed change, seek (position beyond the interpolation
 * window), or once per second while playing; {@code line} whenever the live-lyric text
 * changes.</p>
 */
final class PublisherDiff {

    /** Which messages this tick should broadcast. */
    static final class Decision {
        boolean track;
        boolean state;
        boolean line;

        boolean nothing() {
            return !track && !state && !line;
        }
    }

    /** Mutable memory of what was last pushed to the clients. */
    static final class State {
        String trackKey;
        boolean active;
        boolean playing;
        float speed = 1f;
        long positionMs;
        /** Elapsed-realtime of the last anchor (track or state push). */
        Long lastStatePushElapsedMs;
        String liveLine;
    }

    /**
     * @param trackKey      identity of track + timeline + lyric mode; any change forces a
     *                      full {@code track} push.
     * @param liveLine      current live-lyric text, or "" when the matched timeline is on
     *                      display; a change forces a {@code line} push.
     * @param driftMs       how far the observed position may sit from the prediction before
     *                      it counts as a seek.
     * @param intervalMs    target {@code state} cadence while playing.
     */
    static Decision update(State state, String trackKey, boolean active, boolean playing,
                           float speed, long positionMs, String liveLine, long nowElapsedMs,
                           long driftMs, long intervalMs) {
        Decision decision = new Decision();
        boolean firstObservation = state.trackKey == null;
        boolean trackChanged = firstObservation || !nullSafe(trackKey).equals(state.trackKey);
        decision.track = trackChanged;

        if (!firstObservation && !trackChanged) {
            long expected = state.positionMs;
            if (state.playing && state.speed != 0f && state.lastStatePushElapsedMs != null) {
                expected += (long) ((nowElapsedMs - state.lastStatePushElapsedMs) * state.speed);
            }
            boolean seek = Math.abs(positionMs - expected) > driftMs;
            boolean periodic = playing && state.lastStatePushElapsedMs != null
                    && nowElapsedMs - state.lastStatePushElapsedMs >= intervalMs;
            decision.state = seek || periodic
                    || playing != state.playing || active != state.active || speed != state.speed;
        } else {
            decision.state = true;
        }

        String live = liveLine == null ? "" : liveLine;
        decision.line = !live.equals(state.liveLine == null ? "" : state.liveLine);

        if (decision.track) {
            // A track push carries the full state, so no separate state frame is needed and
            // the anchor moves here.
            decision.state = false;
        }
        if (decision.track || decision.state) {
            state.playing = playing;
            state.speed = speed;
            state.positionMs = positionMs;
            state.lastStatePushElapsedMs = nowElapsedMs;
        }
        state.trackKey = nullSafe(trackKey);
        state.active = active;
        state.liveLine = live;
        return decision;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private PublisherDiff() {}
}
