package wemew.lrc.sync;

import android.content.Context;

/**
 * Unbound devices get a seven-day trial. Local timing can never be tamper-proof on its own —
 * so the trial anchor lives in the uninstall-surviving watermark
 * ({@link DeviceIdentityStore}), every binding poll feeds in the server's HTTP Date so a
 * rolled-back clock is caught, and going offline is tolerated only for 48 hours. A bound
 * store always wins. Once the wemew gateway returns {@code trialDaysLeft} in
 * {@code getVipByDevice}, the server value can replace this local track entirely.
 */
final class LicenseGate {
    static final long TRIAL_MS = 7L * 24 * 60 * 60 * 1000L;
    static final long OFFLINE_GRACE_MS = 48L * 60 * 60 * 1000L;
    /** HTTP Date granularity plus routing jitter; a bigger step back means a clock rollback. */
    static final long CLOCK_BACK_TOLERANCE_MS = 5 * 60 * 1000L;

    private static final String KEY_LAST_SERVER_MS = "license_last_server_ms";
    private static final String KEY_LAST_SERVER_LOCAL_MS = "license_last_server_local_ms";
    private static final String KEY_TAMPERED = "license_tampered";

    private LicenseGate() {}

    /** Lyrics sync runs only for a bound store or an active trial. */
    static boolean serviceAllowed(Context context) {
        if (!AppPreferences.boundBarId(context).isEmpty()) return true;
        return trialActive(DeviceIdentityStore.trialStartMs(context), System.currentTimeMillis(),
                lastServerMs(context), lastServerLocalMs(context), tampered(context));
    }

    /** One-line state for the binding card; null when a store is bound. */
    static String trialSummary(Context context) {
        if (!AppPreferences.boundBarId(context).isEmpty()) return null;
        if (tampered(context)) return "设备时间异常，登记设备码后恢复";
        long trialStart = DeviceIdentityStore.trialStartMs(context);
        long now = System.currentTimeMillis();
        Long serverMs = lastServerMs(context);
        long lastContact = lastServerLocalMs(context);
        if (serverMs != null && lastContact > 0L && now - lastContact > OFFLINE_GRACE_MS) {
            return "离线超 48 小时，联网后恢复";
        }
        long left = trialStart + TRIAL_MS - now;
        if (left <= 0L) return "试用已结束，登记设备码后恢复";
        long days = (left + 86_399_999L) / 86_400_000L;
        return "试用剩余 " + days + " 天（未绑定门店）";
    }

    /**
     * Pure decision, unit-tested: trial is active while inside the window, no clock rollback
     * was seen, and the device has talked to the gateway within the offline grace.
     */
    static boolean trialActive(long trialStartMs, long nowMs, Long lastServerMs,
                               long lastServerLocalMs, boolean tampered) {
        if (tampered) return false;
        if (trialStartMs <= 0L) return true;
        if (nowMs >= trialStartMs + TRIAL_MS) return false;
        if (lastServerMs == null || lastServerLocalMs <= 0L) return true;
        return nowMs - lastServerLocalMs <= OFFLINE_GRACE_MS;
    }

    /** Feeds the gateway's HTTP Date into the rollback detector; call from the poll thread. */
    static void recordServerTime(Context context, long serverMs) {
        if (serverMs <= 0L) return;
        Long previous = lastServerMs(context);
        boolean rolled = previous != null && serverMs < previous - CLOCK_BACK_TOLERANCE_MS;
        AppPreferences.get(context).edit()
                .putLong(KEY_LAST_SERVER_MS, serverMs)
                .putLong(KEY_LAST_SERVER_LOCAL_MS, System.currentTimeMillis())
                .putBoolean(KEY_TAMPERED, tampered(context) || rolled)
                .apply();
        if (rolled && !tampered(context)) {
            DiagnosticLog.record(context, "Binding", "clock rollback detected: server "
                    + serverMs + " < previous " + previous);
        }
    }

    private static Long lastServerMs(Context context) {
        long value = AppPreferences.get(context).getLong(KEY_LAST_SERVER_MS, 0L);
        return value <= 0L ? null : value;
    }

    private static long lastServerLocalMs(Context context) {
        return AppPreferences.get(context).getLong(KEY_LAST_SERVER_LOCAL_MS, 0L);
    }

    private static boolean tampered(Context context) {
        return AppPreferences.get(context).getBoolean(KEY_TAMPERED, false);
    }
}
