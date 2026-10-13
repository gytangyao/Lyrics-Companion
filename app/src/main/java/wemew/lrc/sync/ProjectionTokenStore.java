package wemew.lrc.sync;

import android.content.Intent;

/**
 * Holds one MediaProjection consent result for the playback-capture path, ported from
 * audio_flux's ProjectionTokenStore. The grant only lives in memory: Android deliberately
 * refuses to persist it across reboots, so the first playback session after boot asks the
 * user once more.
 */
final class ProjectionTokenStore {
    private static volatile int resultCode;
    private static volatile Intent resultData;
    /** Bumped on every store/clear so callers can tell a fresh consent from a reused one. */
    private static volatile int version;

    private ProjectionTokenStore() { }

    static void store(int code, Intent data) {
        resultCode = code;
        resultData = data;
        version++;
    }

    static boolean hasConsent() {
        return resultData != null;
    }

    /** Returns a copy: the projection builder consumes the intent it is handed. */
    static Intent resultData() {
        Intent data = resultData;
        return data == null ? null : new Intent(data);
    }

    static int resultCode() {
        return resultCode;
    }

    static int version() {
        return version;
    }

    static void clear() {
        resultCode = 0;
        resultData = null;
        version++;
    }
}
