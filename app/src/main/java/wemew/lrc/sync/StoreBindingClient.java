package wemew.lrc.sync;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONObject;

/**
 * Store binding following the wemew device-identity flow (the nbox pattern): the device shows
 * a stable 12-hex device code, the store registers that code in the wemew admin, and this
 * client polls the gateway until the server reports a non-empty barId. No code is ever
 * entered on the device. Every successful poll also feeds the gateway's HTTP Date into the
 * license rollback detector, and a binding change nudges the service so a gated server
 * comes back the moment a store registers the device.
 */
final class StoreBindingClient {
    private static final String TAG = "StoreBinding";
    /** Prod gateway; the test gateway in nbox is https://test-song.wemew.cn. */
    private static final String GATEWAY = "https://song.wemew.com";
    private static final String DEVICE_ENDPOINT = GATEWAY + "/shazam/getVipByDevice?deviceId=";
    private static final long POLL_INTERVAL_MS = 30_000L;
    // TODO(test): 临时写死已在正式环境登记的测试设备码（门店 TOUCH.）。等后台为这台
    // Android 设备登记自己的设备码后，删除本常量并恢复 DeviceIdentityStore 取值。
    private static final String FIXED_DEVICE_ID = "968A7CEFDD0A";

    /** Outcome of one binding poll, or of parsing a stored response. */
    static final class BindingInfo {
        static final BindingInfo UNBOUND = new BindingInfo(false, "", "");
        final boolean bound;
        final String barId;
        final String barName;

        BindingInfo(boolean bound, String barId, String barName) {
            this.bound = bound;
            this.barId = barId == null ? "" : barId;
            this.barName = barName == null ? "" : barName;
        }
    }

    private final Context context;
    private HandlerThread thread;
    private Handler handler;

    StoreBindingClient(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * The canonical device code: the pinned test id while the admin side has not registered
     * this handset yet, otherwise the uninstall-surviving {@link DeviceIdentityStore} value.
     */
    static synchronized String deviceId(Context context) {
        return FIXED_DEVICE_ID != null ? FIXED_DEVICE_ID
                : DeviceIdentityStore.deviceId(context);
    }

    /**
     * Tolerant parse of the gateway response. Verified against the live API: a bound store
     * returns {@code resultMsg:{mode, enable:false, barName:"TOUCH.", barId:"<uuid>"}} —
     * binding is decided by a non-empty {@code barId} alone; {@code enable} is an unrelated
     * flag and unbound answers still carry {@code resultTrue:true} with {@code barId:null}.
     * {@code resultMsg} is polymorphic: an error response carries a plain string.
     */
    static BindingInfo parseBinding(String body) {
        if (body == null || body.trim().isEmpty()) return BindingInfo.UNBOUND;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject message = root.optJSONObject("resultMsg");
            if (message == null) {
                String text = root.optString("resultMsg", "");
                if (text.trim().startsWith("{")) message = new JSONObject(text);
            }
            if (message == null) return BindingInfo.UNBOUND;
            String barId = message.optString("barId", "");
            String barName = message.optString("barName", "");
            if (barId == null || barId.trim().isEmpty() || "null".equals(barId.trim())) {
                return BindingInfo.UNBOUND;
            }
            return new BindingInfo(true, barId.trim(), barName.trim());
        } catch (Throwable error) {
            return BindingInfo.UNBOUND;
        }
    }

    void start() {
        stop();
        thread = new HandlerThread("StoreBinding");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::poll);
    }

    void stop() {
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
    }

    private void poll() {
        try {
            long[] dateMs = new long[1];
            String id = deviceId(context);
            BindingInfo info = parseBinding(
                    LyricHttp.get(DEVICE_ENDPOINT + LyricHttp.encode(id), null, dateMs));
            if (dateMs[0] > 0L) LicenseGate.recordServerTime(context, dateMs[0]);
            if (!info.bound) {
                // Reinstall arbitration: the watermark may remember the registered id while
                // the derived one changed. Probe it; a bound answer adopts it everywhere.
                String remembered = DeviceIdentityStore.watermarkId(context);
                if (remembered != null && !remembered.equals(id)) {
                    BindingInfo rememberedInfo = parseBinding(
                            LyricHttp.get(DEVICE_ENDPOINT + LyricHttp.encode(remembered), null));
                    if (rememberedInfo.bound) {
                        DeviceIdentityStore.adopt(context, remembered,
                                DeviceIdentityStore.trialStartMs(context));
                        info = rememberedInfo;
                        DiagnosticLog.record(context, "Binding",
                                "reinstalled identity recovered from watermark: " + remembered);
                    }
                }
            }
            boolean wasBound = !AppPreferences.boundBarId(context).isEmpty();
            AppPreferences.putBinding(context, info.barId, info.barName,
                    System.currentTimeMillis(), "");
            if (info.bound && !wasBound) {
                DiagnosticLog.record(context, "Binding",
                        "门店已绑定：" + info.barName + " (" + info.barId + ")");
                // A gated (trial-expired) server must come back the moment binding lands.
                context.startService(new Intent(context, WebSyncService.class)
                        .setAction(WebSyncService.ACTION_REEVALUATE));
            } else if (!info.bound && wasBound) {
                DiagnosticLog.record(context, "Binding", "store binding removed");
                context.startService(new Intent(context, WebSyncService.class)
                        .setAction(WebSyncService.ACTION_REEVALUATE));
            }
        } catch (Throwable error) {
            Log.w(TAG, "binding poll failed", error);
            AppPreferences.putBinding(context, AppPreferences.boundBarId(context),
                    AppPreferences.boundBarName(context), System.currentTimeMillis(),
                    error.getMessage() == null ? error.getClass().getSimpleName()
                            : error.getMessage());
        }
        if (handler != null) handler.postDelayed(this::poll, POLL_INTERVAL_MS);
    }
}
