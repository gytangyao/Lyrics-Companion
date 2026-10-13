package wemew.lrc.sync;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves the current album art to {@code GET /cover?v=N}. Encoding happens lazily on the
 * requesting thread (a few ms for a 512px JPEG) and the result is cached in a tiny LRU; the
 * version counter invalidates everything whenever the art changes, so a stale browser cache
 * can never serve yesterday's cover.
 */
final class CoverRegistry {
    private static final int MAX_EDGE_PX = 512;
    private static final int JPEG_QUALITY = 82;
    private static final int CACHE_LIMIT = 4;

    private final Map<String, byte[]> cache =
            new LinkedHashMap<String, byte[]>(8, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };
    private Bitmap current;
    private String currentKey = "";
    private int version;

    /** Called from the publisher tick whenever the track changes; bumps the art version. */
    synchronized int update(String trackKey, Bitmap art) {
        String key = trackKey == null ? "" : trackKey;
        if (key.equals(currentKey) && art == current) return version;
        currentKey = key;
        current = art;
        version++;
        cache.clear();
        return version;
    }

    synchronized int version() {
        return version;
    }

    synchronized boolean hasArt() {
        return current != null;
    }

    /** Bytes for exactly this version, or null when the version is unknown or there is no art. */
    synchronized byte[] bytes(int requestedVersion) {
        if (requestedVersion != version || current == null) return null;
        String key = Integer.toString(version);
        byte[] cached = cache.get(key);
        if (cached != null) return cached;
        Bitmap scaled = scale(current);
        ByteArrayOutputStream out = new ByteArrayOutputStream(24 * 1024);
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null;
        byte[] data = out.toByteArray();
        cache.put(key, data);
        return data;
    }

    private static Bitmap scale(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int edge = Math.max(width, height);
        if (edge <= MAX_EDGE_PX) return source;
        float factor = MAX_EDGE_PX / (float) edge;
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(width * factor)),
                Math.max(1, Math.round(height * factor)), true);
    }
}
