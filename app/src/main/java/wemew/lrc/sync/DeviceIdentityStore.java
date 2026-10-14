package wemew.lrc.sync;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Device identity that survives an uninstall: the primary copy is a signed watermark file in
 * public Downloads (via MediaStore on API 29+, where a reinstall of the same package keeps
 * ownership of its own entry and can read it back without any permission), mirrored into
 * SharedPreferences for fast access. The signature is an HMAC with a build secret — tamper
 * evidence, not secrecy (an Android Keystore key dies with the uninstall and could not verify
 * old data).
 *
 * <p>Arbitration: when channels disagree (a reinstall re-derived a different id while the
 * watermark kept the registered one), the id the gateway reports as bound wins; the binding
 * poller calls {@link #adopt(Context, String, long)} to write it back everywhere.</p>
 */
final class DeviceIdentityStore {
    private static final String WATERMARK_NAME = ".wemew_device_identity";
    /**
     * Obfuscation-grade build secret for the tamper signature. Not a security boundary
     * against a determined attacker; it makes casual edits (id swap, clock reset in the
     * file) detectable by signature mismatch.
     */
    private static final String BUILD_SECRET = "wemew-lrc-sync/1:device-identity";

    static final class Identity {
        final String id;
        final long firstSeenMs;
        final long trialStartMs;

        Identity(String id, long firstSeenMs, long trialStartMs) {
            this.id = id;
            this.firstSeenMs = firstSeenMs;
            this.trialStartMs = trialStartMs;
        }
    }

    private DeviceIdentityStore() {}

    /** The canonical id: prefs fast path, then the watermark, then a fresh derivation. */
    static synchronized String deviceId(Context context) {
        Identity identity = load(context);
        return identity == null ? null : identity.id;
    }

    /** Lazily-fixed anchor kept in the watermark blob; reserved for future server-side trials. */
    static synchronized long trialStartMs(Context context) {
        Identity identity = load(context);
        if (identity != null && identity.trialStartMs > 0L) return identity.trialStartMs;
        long now = System.currentTimeMillis();
        persist(context, identity == null ? null : identity.id,
                identity == null || identity.firstSeenMs <= 0L ? now : identity.firstSeenMs,
                now);
        return now;
    }

    /** Writes one agreed id (and the trial anchor) into every channel. */
    static synchronized void adopt(Context context, String id, long trialStartMs) {
        Identity existing = load(context);
        long now = System.currentTimeMillis();
        long firstSeen = existing == null || existing.firstSeenMs <= 0L
                ? now : existing.firstSeenMs;
        persist(context, id, firstSeen, trialStartMs > 0L ? trialStartMs : firstSeen);
    }

    /** The id recorded in the uninstall-surviving watermark, or null when absent/invalid. */
    static synchronized String watermarkId(Context context) {
        Identity identity = readWatermark(context);
        return identity == null ? null : identity.id;
    }

    private static Identity load(Context context) {
        SharedPreferences preferences = AppPreferences.get(context);
        String id = preferences.getString(AppPreferences.KEY_DEVICE_ID, "");
        long firstSeen = preferences.getLong("device_first_seen_ms", 0L);
        long trial = preferences.getLong("device_trial_start_ms", 0L);
        if (id != null && id.trim().length() == 12) {
            if (trial <= 0L) {
                trial = System.currentTimeMillis();
                persist(context, id.trim(), firstSeen > 0L ? firstSeen : trial, trial);
            }
            return new Identity(id.trim().toUpperCase(Locale.US), firstSeen, trial);
        }
        Identity fromWatermark = readWatermark(context);
        if (fromWatermark != null) {
            persist(context, fromWatermark.id, fromWatermark.firstSeenMs,
                    fromWatermark.trialStartMs);
            return fromWatermark;
        }
        String derived = derive(context);
        long now = System.currentTimeMillis();
        persist(context, derived, now, now);
        DiagnosticLog.record(context, "Binding", "device id generated: " + derived);
        return new Identity(derived, now, now);
    }

    private static void persist(Context context, String id, long firstSeenMs, long trialStartMs) {
        if (id == null || id.trim().length() != 12) return;
        String normalized = id.trim().toUpperCase(Locale.US);
        AppPreferences.get(context).edit()
                .putString(AppPreferences.KEY_DEVICE_ID, normalized)
                .putLong("device_first_seen_ms", firstSeenMs)
                .putLong("device_trial_start_ms", trialStartMs).apply();
        writeWatermark(context, normalized, firstSeenMs, trialStartMs);
    }

    private static String derive(Context context) {
        try {
            String seed = android.provider.Settings.Secure.getString(
                    context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (seed == null || seed.trim().isEmpty()) seed = UUID.randomUUID().toString();
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
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

    // ---- signed blob (pure, unit-tested) ----

    static String buildBlob(String id, long firstSeenMs, long trialStartMs, String secret) {
        String payload = payloadOf(id, firstSeenMs, trialStartMs);
        return payload.substring(0, payload.length() - 1)
                + ",\"sig\":\"" + hmac(payload, secret) + "\"}";
    }

    /** Parses and verifies a blob; null when malformed or the signature does not match. */
    static Identity parseBlob(String blob, String secret) {
        if (blob == null || blob.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(blob);
            String id = root.optString("id", "");
            long firstSeen = root.optLong("firstSeenMs", 0L);
            long trial = root.optLong("trialStartMs", 0L);
            String sig = root.optString("sig", "");
            if (id.trim().length() != 12 || sig.isEmpty()) return null;
            String normalized = id.trim().toUpperCase(Locale.US);
            if (!sig.equals(hmac(payloadOf(normalized, firstSeen, trial), secret))) {
                return null;
            }
            return new Identity(normalized, firstSeen, trial);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String payloadOf(String id, long firstSeenMs, long trialStartMs) {
        JSONObject payload = new JSONObject();
        putJson(payload, "id", id);
        putJson(payload, "firstSeenMs", firstSeenMs);
        putJson(payload, "trialStartMs", trialStartMs);
        return payload.toString();
    }

    private static void putJson(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (Throwable error) {
            throw new IllegalStateException(error);
        }
    }

    private static String hmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(Locale.US, "%02x", value));
            return hex.toString();
        } catch (Throwable error) {
            return "";
        }
    }

    // ---- watermark I/O (best-effort on every API level) ----

    private static Identity readWatermark(Context context) {
        String blob = null;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentResolver resolver = context.getContentResolver();
                try (Cursor cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.MediaColumns._ID},
                        MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                        new String[]{WATERMARK_NAME}, null)) {
                    // Only entries this package owns are visible, no permission needed.
                    while (cursor != null && cursor.moveToNext()) {
                        Uri uri = ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                cursor.getLong(0));
                        try (InputStream input = resolver.openInputStream(uri)) {
                            Identity parsed = parseBlob(readAll(input), BUILD_SECRET);
                            if (parsed != null) return parsed;
                        } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable ignored) { }
            return null;
        }
        try (InputStream input = new FileInputStream(new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                WATERMARK_NAME))) {
            blob = readAll(input);
        } catch (Throwable ignored) { }
        return blob == null ? null : parseBlob(blob, BUILD_SECRET);
    }

    private static void writeWatermark(Context context, String id, long firstSeenMs,
                                       long trialStartMs) {
        byte[] bytes = buildBlob(id, firstSeenMs, trialStartMs, BUILD_SECRET)
                .getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentResolver resolver = context.getContentResolver();
                Uri existing = findOwnWatermark(resolver);
                if (existing != null) {
                    try (OutputStream out = resolver.openOutputStream(existing, "wt")) {
                        out.write(bytes);
                    }
                    return;
                }
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, WATERMARK_NAME);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return;
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    out.write(bytes);
                }
                ContentValues clear = new ContentValues();
                clear.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, clear, null, null);
            } catch (Throwable ignored) { }
            return;
        }
        try {
            File directory = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            try (OutputStream out = new java.io.FileOutputStream(
                    new File(directory, WATERMARK_NAME))) {
                out.write(bytes);
            }
        } catch (Throwable ignored) { }
    }

    private static Uri findOwnWatermark(ContentResolver resolver) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try (Cursor cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{WATERMARK_NAME}, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0));
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        byte[] buffer = new byte[512];
        int count;
        while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
        return out.toString("UTF-8");
    }
}
