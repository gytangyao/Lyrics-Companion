package wemew.lrc.sync;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QQMusicLyricClient {
    private static final String REFERER = "https://y.qq.com/";
    private static final String QRC_URL =
            "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg";
    private static final Pattern TI_TAG = Pattern.compile("\\[ti:([^\\]]*)]");
    private final LyricCache cache;

    QQMusicLyricClient(Context context) { cache = new LyricCache(context, "qq"); }

    LrcTimeline load(String title, String artist, long durationMs) throws Exception {
        String query = LyricHttp.encode(title + (artist == null || artist.isEmpty() ? "" : " " + artist));
        JSONObject root = new JSONObject(LyricHttp.get(
                "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=10&w="
                        + query + "&format=json", REFERER));
        JSONObject data = root.optJSONObject("data");
        JSONObject song = data == null ? null : data.optJSONObject("song");
        JSONArray list = song == null ? null : song.optJSONArray("list");
        if (list == null) return LrcTimeline.EMPTY;
        int bestScore = Integer.MIN_VALUE;
        String bestMid = "";
        long bestId = -1L;
        for (int i = 0; i < list.length(); i++) {
            JSONObject candidate = list.optJSONObject(i);
            if (candidate == null) continue;
            JSONArray singers = candidate.optJSONArray("singer");
            JSONObject singer = singers == null ? null : singers.optJSONObject(0);
            int score = NetEaseLyricClient.matchScore(title, artist, durationMs,
                    candidate.optString("songname", ""),
                    singer == null ? "" : singer.optString("name", ""),
                    candidate.optLong("interval", -1L) * 1000L);
            if (score > bestScore) {
                bestScore = score;
                bestMid = candidate.optString("songmid", "");
                bestId = candidate.optLong("songid", -1L);
            }
        }
        if (bestScore < 100 || bestMid.isEmpty()) return LrcTimeline.EMPTY;
        String original = cache.read(bestMid + "_original");
        String translated = cache.read(bestMid + "_translated");
        String enhanced = cache.read(bestMid + "_qrc");
        boolean translationChecked = translated != null
                || cache.read(bestMid + "_translation_checked") != null;
        if ((enhanced == null || !translationChecked) && bestId > 0L) {
            try {
                String response = LyricHttp.request("POST", QRC_URL, REFERER,
                        "version=15&miniversion=100&lrctype=4&musicid=" + bestId);
                if (enhanced == null) {
                    enhanced = QrcLyricCodec.decryptToTimedLyric(
                            QrcLyricCodec.encryptedContent(response, "content"));
                    if (!enhanced.isEmpty()) cache.write(bestMid + "_qrc", enhanced);
                }
                if (!translationChecked) {
                    translated = QrcLyricCodec.decryptToLrc(
                            QrcLyricCodec.encryptedContent(response, "contentts"));
                    if (!translated.isEmpty()) cache.write(bestMid + "_translated", translated);
                }
            } catch (Throwable ignored) { }
        }
        if (original == null || translated == null && !translationChecked) {
            JSONObject lyric = new JSONObject(LyricHttp.get(
                    "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="
                            + LyricHttp.encode(bestMid) + "&format=json&nobase64=1", REFERER));
            if (original == null) {
                original = decodeEntities(lyric.optString("lyric", ""));
                if (!original.isEmpty()) cache.write(bestMid + "_original", original);
            }
            if (translated == null || translated.isEmpty()) {
                translated = decodeEntities(lyric.optString("trans", ""));
                if (!translated.isEmpty()) cache.write(bestMid + "_translated", translated);
            }
            cache.write(bestMid + "_translation_checked", "1");
        }
        return LrcTimeline.parse(original == null ? "" : original,
                translated == null ? "" : translated, enhanced == null ? "" : enhanced);
    }

    /**
     * WeSing leg: the recording log already identifies the song, so try the direct
     * musicid download first (WeSing shares QQ's catalogue, its numeric songId is
     * expected to be a QQ musicid) and only fall back to the keyword-search path
     * {@link #load} when the ids do not resolve. Cache is keyed by songMid/songId.
     */
    LrcTimeline loadForWeSing(String songId, String songMid, String title, String artist,
            long durationMs) {
        String key = songMid == null || songMid.trim().isEmpty() ? songId : songMid.trim();
        String original = cache.read(key + "_original");
        String translated = cache.read(key + "_translated");
        String enhanced = cache.read(key + "_qrc");
        if (original != null && cacheDisqualified(original, title)) {
            // Written by the interim build's unvalidated songmid rung, an entry under
            // this key can be another song's lyric (observed on a pad build). Drop the
            // whole write — otherwise its non-null original short-circuits every fetch
            // rung below and the wrong lyric gets served forever.
            original = null;
            translated = null;
        }
        if (original != null && translated != null && enhanced != null) {
            return LrcTimeline.parse(original, translated, enhanced);
        }
        if (songId != null && songId.matches("\\d+")) {
            // Numeric ids (phone build's enterRecordData) address the QQ download
            // endpoint directly — WeSing and QQ share that catalogue.
            try {
                String response = LyricHttp.request("POST", QRC_URL, REFERER,
                        "version=15&miniversion=100&lrctype=4&musicid=" + songId);
                if (enhanced == null) {
                    enhanced = QrcLyricCodec.decryptToTimedLyric(
                            QrcLyricCodec.encryptedContent(response, "content"));
                    if (!enhanced.isEmpty()) cache.write(key + "_qrc", enhanced);
                }
                if (translated == null) {
                    translated = QrcLyricCodec.decryptToLrc(
                            QrcLyricCodec.encryptedContent(response, "contentts"));
                    if (!translated.isEmpty()) cache.write(key + "_translated", translated);
                }
            } catch (Throwable ignored) { }
        }
        if (enhanced == null && title != null && !title.trim().isEmpty()) {
            // SongInfo from the recording room is the trustworthy identity on pad builds;
            // the keyword search is self-validating (matchScore floor of 100).
            try {
                LrcTimeline searched = load(title, artist, durationMs);
                if (searched != null && searched.lineCount() > 0) return searched;
            } catch (Throwable ignored) { }
        }
        if (original == null && songMid != null && !songMid.trim().isEmpty()) {
            // Pad builds expose a songMid that may live in WeSing's own id space: fed to
            // QQ it can return another song's lyric (observed). Only trust the result
            // when the returned LRC names the song being recorded.
            try {
                JSONObject lyric = new JSONObject(LyricHttp.get(
                        "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="
                                + LyricHttp.encode(songMid.trim()) + "&format=json&nobase64=1",
                        REFERER));
                String candidate = decodeEntities(lyric.optString("lyric", ""));
                if (lyricNamesSong(candidate, title)) {
                    original = candidate;
                    if (!original.isEmpty()) cache.write(key + "_original", original);
                    if (translated == null || translated.isEmpty()) {
                        translated = decodeEntities(lyric.optString("trans", ""));
                        if (!translated.isEmpty()) cache.write(key + "_translated", translated);
                    }
                }
            } catch (Throwable ignored) { }
        }
        return LrcTimeline.parse(original == null ? "" : original,
                translated == null ? "" : translated, enhanced == null ? "" : enhanced);
    }

    /** Cross-checks an LRC's [ti:] tag against the song being recorded. */
    private static boolean lyricNamesSong(String lrc, String title) {
        if (lrc == null || lrc.isEmpty()) return false;
        if (title == null || title.trim().isEmpty()) return false; // nothing to check against
        Matcher ti = TI_TAG.matcher(lrc);
        return ti.find() && namesMatch(ti.group(1), title);        // unverifiable → reject
    }

    /** A cached entry is poisoned when its [ti:] provably names a different song. */
    private static boolean cacheDisqualified(String original, String title) {
        if (original == null || title == null || title.trim().isEmpty()) return false;
        Matcher ti = TI_TAG.matcher(original);
        if (!ti.find()) return false;               // no [ti:] → cannot judge → keep
        return !namesMatch(ti.group(1), title);
    }

    private static boolean namesMatch(String got, String want) {
        String a = got.trim();
        String b = want.trim();
        return a.contains(b) || b.contains(a);
    }

    private static String decodeEntities(String value) {
        return value.replace("&#58;", ":").replace("&#46;", ".")
                .replace("&apos;", "'").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
