package wemew.lrc.sync;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/**
 * Store binding following the wemew device-identity flow (the nbox pattern): the device shows a
 * stable 12-hex device code, the store registers that code in the wemew admin, and this client
 * polls the gateway until the server reports the binding as
 * {@code resultMsg:{enable:true, barId, barName}}. No code is ever entered on the device.
 */
final class StoreBindingClient {
    private static final String TAG = "StoreBinding";
    /** Prod gateway; the test gateway in nbox is https://test-song.wemew.cn. */
    private static final String GATEWAY = "https://song.wemew.com";
    private static final String DEVICE_ENDPOINT = GATEWAY + "/shazam/getVipByDevice?deviceId=";
    private static final long POLL_INTERVAL_MS = 30_000L;
    // TODO(test): 临时写死已在正式环境登记的测试设备码（门店 TOUCH.）。等后台为这台
    // Android 设备登记自己的设备码后，删除本常量并恢复 deviceId() 的派生逻辑。
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

    /** Stable 12-hex device code, generated once from ANDROID_ID and persisted forever. */
    static synchronized String deviceId(Context context) {
        // TODO(test): 临时返回写死的测试设备码；必须先于偏好读取返回，否则旧存储值会胜出。
        return FIXED_DEVICE_ID;
    }

    /** 恢复派生逻辑时启用：首次从 ANDROID_ID 生成并永久持久化。 */
    @SuppressWarnings("unused")
    private static String derivedDeviceId(Context context) {
        String existing = AppPreferences.get(context).getString(
                AppPreferences.KEY_DEVICE_ID, "");
        if (existing != null && existing.trim().length() == 12) return existing;
        String generated = deriveDeviceId(Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID));
        AppPreferences.get(context).edit().putString(AppPreferences.KEY_DEVICE_ID,
                generated).apply();
        DiagnosticLog.record(context, "Binding", "device id generated: " + generated);
        return generated;
    }

    /** SHA-256 of any seed, first 12 hex chars upper-cased — the nbox DeviceHelper shape. */
    private static String deriveDeviceId(String seed) {
        try {
            String safeSeed = seed == null || seed.trim().isEmpty()
                    ? UUID.randomUUID().toString() : seed;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(safeSeed.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < hash.length && hex.length() < 12; i++) {
                hex.append(String.format(Locale.US, "%02X", hash[i]));
            }
            return hex.substring(0, 12);
        } catch (Throwable error) {
            return UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 12).toUpperCase(Locale.US);
        }
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
            String id = deviceId(context);
            String body = LyricHttp.get(DEVICE_ENDPOINT + LyricHttp.encode(id), null);
            BindingInfo info = parseBinding(body);
            boolean wasBound = !AppPreferences.boundBarId(context).isEmpty();
            AppPreferences.putBinding(context, info.barId, info.barName,
                    System.currentTimeMillis(), "");
            if (info.bound && !wasBound) {
                DiagnosticLog.record(context, "Binding",
                        "门店已绑定：" + info.barName + " (" + info.barId + ")");
            } else if (!info.bound && wasBound) {
                DiagnosticLog.record(context, "Binding", "store binding removed");
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
