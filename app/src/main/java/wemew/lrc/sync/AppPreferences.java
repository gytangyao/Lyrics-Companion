package wemew.lrc.sync;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Lyrics-acquisition and web-server preferences. Display styling deliberately lives on each web
 * client (localStorage), so nothing visual is stored here.
 */
final class AppPreferences {
    static final String FILE = "lyrics_companion";
    static final String KEY_LYRIC_CATALOG = "lyric_catalog";
    /** Optional per-player-category override kept for backward compatibility. */
    static final String KEY_PLAYER_LYRIC_CATALOG = "player_lyric_catalog";
    /** Exact package names that have published a playable MediaSession on this device. */
    static final String KEY_OBSERVED_PLAYER_PACKAGES = "observed_player_packages";
    static final String KEY_PLAYER_CATALOG_FALLBACK = "player_catalog_fallback";
    /** 忽略这些应用发布的媒体元数据（issue #35）。 */
    static final String KEY_IGNORED_PLAYER_PACKAGES = "ignored_player_packages";
    /** 匹配到的歌词长时间不滚动时，改用播放器实时歌词（issue #44）。 */
    static final String KEY_STUCK_LYRIC_FALLBACK = "stuck_lyric_fallback";
    /** Some phone apps hand the car "歌名 - 歌手" in the artist slot; see the accessor. */
    static final String KEY_COMPOSITE_IDENTITY_FROM_ARTIST = "composite_identity_from_artist";
    static final String KEY_LYRIC_OFFSET = "lyric_offset";
    static final String KEY_LOCAL_LYRIC_ENABLED = "local_lyric_enabled";
    static final String KEY_LOCAL_LYRIC_DIRECTORY_URI = "local_lyric_directory_uri";
    static final String KEY_LOCAL_LYRIC_DIRECTORY_PATH = "local_lyric_directory_path";
    static final String KEY_LYRIC_CACHE_POLICY = "lyric_cache_policy";
    static final String KEY_LYRIC_CACHE_LIMIT_MB = "lyric_cache_limit_mb";
    static final String KEY_THEME_MODE = "theme_mode";
    /** Stable device identity the store registers server-side (wemew binding). */
    static final String KEY_DEVICE_ID = "device_id";
    static final String KEY_BOUND_BAR_ID = "bound_bar_id";
    static final String KEY_BOUND_BAR_NAME = "bound_bar_name";
    static final String KEY_BINDING_LAST_CHECK_MS = "binding_last_check_ms";
    static final String KEY_BINDING_LAST_ERROR = "binding_last_error";

    private AppPreferences() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String lyricCatalog(Context context) {
        return normalizeLyricCatalog(get(context).getString(KEY_LYRIC_CATALOG, "auto"));
    }

    /** Resolves a player-specific rule first, then retains the existing global default. */
    static String lyricCatalog(Context context, String sourceId) {
        String override = get(context).getString(playerLyricCatalogKey(sourceId), "");
        return resolvePlayerLyricCatalog(sourceId, override, lyricCatalog(context));
    }

    /**
     * Resolves the rule for one concrete player application.  Package-specific rules deliberately
     * do not share a value with another application that happens to use the same provider type.
     */
    static String lyricCatalog(Context context, String sourceId, String packageName) {
        String override = get(context).getString(playerPackageLyricCatalogKey(packageName), "");
        return resolvePlayerLyricCatalog(sourceId, override, lyricCatalog(context));
    }

    static String resolvePlayerLyricCatalog(String sourceId, String override, String fallback) {
        return resolveLyricCatalog(override, "kuwo".equals(sourceId) ? "kuwo" : fallback);
    }

    static String resolveLyricCatalog(String playerOverride, String fallback) {
        return playerOverride == null || playerOverride.trim().isEmpty()
                ? normalizeLyricCatalog(fallback) : normalizeLyricCatalog(playerOverride);
    }

    /** Empty removes the override so this player follows the global default again. */
    static void putPlayerLyricCatalog(Context context, String sourceId, String catalog) {
        SharedPreferences.Editor editor = get(context).edit();
        if (catalog == null || catalog.trim().isEmpty()) {
            editor.remove(playerLyricCatalogKey(sourceId));
        } else {
            editor.putString(playerLyricCatalogKey(sourceId), normalizeLyricCatalog(catalog));
        }
        editor.apply();
    }

    static String playerLyricCatalogOverride(Context context, String sourceId) {
        String value = get(context).getString(playerLyricCatalogKey(sourceId), "");
        return value == null || value.trim().isEmpty() ? "" : normalizeLyricCatalog(value);
    }

    static void putPlayerPackageLyricCatalog(Context context, String packageName, String catalog) {
        SharedPreferences.Editor editor = get(context).edit();
        if (catalog == null || catalog.trim().isEmpty()) {
            editor.remove(playerPackageLyricCatalogKey(packageName));
        } else {
            editor.putString(playerPackageLyricCatalogKey(packageName), normalizeLyricCatalog(catalog));
        }
        editor.apply();
    }

    static String playerPackageLyricCatalogOverride(Context context, String packageName) {
        String value = get(context).getString(playerPackageLyricCatalogKey(packageName), "");
        return value == null || value.trim().isEmpty() ? "" : normalizeLyricCatalog(value);
    }

    /** A package selected from the catalog-centred app list must not fall through to another catalog. */
    static boolean hasForcedPlayerPackageCatalog(Context context, String packageName) {
        return !playerPackageLyricCatalogOverride(context, packageName).isEmpty();
    }

    static void rememberPlayerPackage(Context context, String packageName) {
        String normalized = normalizePackageName(packageName);
        if (normalized.isEmpty()) return;
        Set<String> current = get(context).getStringSet(KEY_OBSERVED_PLAYER_PACKAGES,
                Collections.emptySet());
        if (current.contains(normalized)) return;
        Set<String> updated = new LinkedHashSet<>(current);
        updated.add(normalized);
        get(context).edit().putStringSet(KEY_OBSERVED_PLAYER_PACKAGES, updated).apply();
    }

    static Set<String> observedPlayerPackages(Context context) {
        return new LinkedHashSet<>(get(context).getStringSet(KEY_OBSERVED_PLAYER_PACKAGES,
                Collections.emptySet()));
    }

    private static String normalizeLyricCatalog(String value) {
        if ("netease".equals(value) || "qqmusic".equals(value)
                || "kugou".equals(value) || "kuwo".equals(value)
                || "soda".equals(value) || "auto".equals(value)) return value;
        return "auto";
    }

    static boolean playerCatalogFallback(Context context) {
        return get(context).getBoolean(KEY_PLAYER_CATALOG_FALLBACK, true);
    }

    static void setPlayerCatalogFallback(Context context, boolean enabled) {
        get(context).edit().putBoolean(KEY_PLAYER_CATALOG_FALLBACK, enabled).apply();
    }

    /**
     * 这些应用发布的媒体元数据会被忽略（issue #35）。车机自带的媒体中心、导航或蓝牙通道常常
     * 抢走歌词，把它们列进来就不会再顶掉正在播放的播放器。
     */
    static Set<String> ignoredPlayerPackages(Context context) {
        Set<String> stored = get(context).getStringSet(KEY_IGNORED_PLAYER_PACKAGES,
                Collections.emptySet());
        return stored == null ? new LinkedHashSet<>() : new LinkedHashSet<>(stored);
    }

    static void setIgnoredPlayerPackages(Context context, Set<String> packages) {
        get(context).edit().putStringSet(KEY_IGNORED_PLAYER_PACKAGES,
                packages == null ? Collections.emptySet() : new LinkedHashSet<>(packages)).apply();
    }

    static boolean ignoredPlayerPackage(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        return ignoredPlayerPackages(context).contains(packageName.trim());
    }

    /** 匹配到的歌词长时间不滚动时改用播放器实时歌词；默认开启（issue #44）。 */
    static boolean stuckLyricFallback(Context context) {
        return get(context).getBoolean(KEY_STUCK_LYRIC_FALLBACK, true);
    }

    static void setStuckLyricFallback(Context context, boolean enabled) {
        get(context).edit().putBoolean(KEY_STUCK_LYRIC_FALLBACK, enabled).apply();
    }

    /**
     * Some phone music apps hand the car a composite artist slot ("歌名 - 歌手") and put the live
     * lyric line in the title, which makes the title useless as a track identity. With this on,
     * the artist slot is parsed for the identity and the raw title becomes the live lyric.
     */
    static boolean compositeIdentityFromArtist(Context context) {
        return get(context).getBoolean(KEY_COMPOSITE_IDENTITY_FROM_ARTIST, false);
    }

    static void setCompositeIdentityFromArtist(Context context, boolean enabled) {
        get(context).edit().putBoolean(KEY_COMPOSITE_IDENTITY_FROM_ARTIST, enabled).apply();
    }

    /** Global lyric timing correction in ms; the web client receives it already applied. */
    static int lyricOffsetMs(Context context) {
        return get(context).getInt(KEY_LYRIC_OFFSET, 0);
    }

    static void setLyricOffsetMs(Context context, int valueMs) {
        get(context).edit().putInt(KEY_LYRIC_OFFSET, valueMs).apply();
    }

    static boolean localLyricEnabled(Context context) {
        return get(context).getBoolean(KEY_LOCAL_LYRIC_ENABLED, true);
    }

    static void setLocalLyricEnabled(Context context, boolean enabled) {
        get(context).edit().putBoolean(KEY_LOCAL_LYRIC_ENABLED, enabled).apply();
    }

    static String localLyricDirectoryUri(Context context) {
        return get(context).getString(KEY_LOCAL_LYRIC_DIRECTORY_URI, "");
    }

    static void setLocalLyricDirectoryUri(Context context, String uri) {
        get(context).edit().putString(KEY_LOCAL_LYRIC_DIRECTORY_URI,
                uri == null ? "" : uri).apply();
    }

    static String localLyricDirectoryPath(Context context) {
        return get(context).getString(KEY_LOCAL_LYRIC_DIRECTORY_PATH, "");
    }

    static void setLocalLyricDirectoryPath(Context context, String path) {
        get(context).edit().putString(KEY_LOCAL_LYRIC_DIRECTORY_PATH,
                path == null ? "" : path).apply();
    }

    static String lyricCachePolicy(Context context) {
        String value = get(context).getString(KEY_LYRIC_CACHE_POLICY, "capacity");
        return "forever".equals(value) || "capacity".equals(value) ? value : "30d";
    }

    static int lyricCacheLimitMb(Context context) {
        return Math.max(16, Math.min(512, get(context).getInt(KEY_LYRIC_CACHE_LIMIT_MB, 128)));
    }

    /** Requested theme: {@code light}, {@code dark} or {@code auto} (follow the system). */
    static String themeMode(Context context) {
        String value = get(context).getString(KEY_THEME_MODE, "auto");
        return "light".equals(value) || "dark".equals(value) ? value : "auto";
    }

    static void setThemeMode(Context context, String value) {
        String normalized = "light".equals(value) || "dark".equals(value) ? value : "auto";
        get(context).edit().putString(KEY_THEME_MODE, normalized).apply();
    }

    /** The theme in effect; {@code auto} is resolved by the system night mode. */
    static String resolvedThemeMode(Context context) {
        return themeMode(context);
    }

    static String deviceId(Context context, String generatedId) {
        String existing = get(context).getString(KEY_DEVICE_ID, "");
        if (existing != null && !existing.isEmpty()) return existing;
        get(context).edit().putString(KEY_DEVICE_ID,
                generatedId == null ? "" : generatedId).apply();
        return generatedId == null ? "" : generatedId;
    }

    static String boundBarId(Context context) {
        return get(context).getString(KEY_BOUND_BAR_ID, "");
    }

    static String boundBarName(Context context) {
        return get(context).getString(KEY_BOUND_BAR_NAME, "");
    }

    static long bindingLastCheckMs(Context context) {
        return get(context).getLong(KEY_BINDING_LAST_CHECK_MS, 0L);
    }

    static String bindingLastError(Context context) {
        return get(context).getString(KEY_BINDING_LAST_ERROR, "");
    }

    /** Written by the binding poller; the UI only ever reads the cached values. */
    static void putBinding(Context context, String barId, String barName, long checkedAtMs,
                           String lastError) {
        get(context).edit()
                .putString(KEY_BOUND_BAR_ID, barId == null ? "" : barId)
                .putString(KEY_BOUND_BAR_NAME, barName == null ? "" : barName)
                .putLong(KEY_BINDING_LAST_CHECK_MS, checkedAtMs)
                .putString(KEY_BINDING_LAST_ERROR, lastError == null ? "" : lastError)
                .apply();
    }

    /** Clears every stored setting; lyric caches on disk are managed by {@link LyricCache}. */
    static int resetUserSettings(Context context) {
        SharedPreferences preferences = get(context);
        int removed = preferences.getAll().size();
        preferences.edit().clear().commit();
        return removed;
    }

    private static String playerLyricCatalogKey(String sourceId) {
        String safe = sourceId == null ? "media" : sourceId.trim().toLowerCase();
        if (!safe.matches("[a-z0-9_]+")) safe = "media";
        return KEY_PLAYER_LYRIC_CATALOG + "_" + safe;
    }

    private static String playerPackageLyricCatalogKey(String packageName) {
        String safe = normalizePackageName(packageName).replace('.', '_');
        if (safe.isEmpty()) safe = "unknown";
        return KEY_PLAYER_LYRIC_CATALOG + "_app_" + safe;
    }

    private static String normalizePackageName(String packageName) {
        String normalized = packageName == null ? "" : packageName.trim().toLowerCase();
        return normalized.matches("[a-z0-9_.]+") ? normalized : "";
    }
}
