package com.zuoqirun.lyricscompanion;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Android's JSONTokener also accepts the legacy search response's single quotes. */
final class KuwoLyricParser {
    private KuwoLyricParser() {}

    static String trackId(String value) {
        String id = value == null ? "" : value.trim();
        if (id.startsWith("MUSIC_")) id = id.substring(6);
        if (!id.matches("[0-9]{1,18}")) return "";
        try { return Long.parseLong(id) > 0 ? Long.toString(Long.parseLong(id)) : ""; }
        catch (NumberFormatException ignored) { return ""; }
    }

    static List<Candidate> candidates(String response, String title, String artist,
                                      long durationMs) throws Exception {
        JSONArray items = new JSONObject(response).optJSONArray("abslist");
        List<Candidate> result = new ArrayList<>();
        if (items == null) return result;
        for (int i = 0; i < Math.min(items.length(), 100); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String id = trackId(item.optString("MUSICRID", ""));
            String name = decode(item.optString("NAME", ""));
            String singer = decode(item.optString("ARTIST", ""));
            long seconds = item.optLong("DURATION", 0L);
            long duration = seconds > 0 && seconds <= 86400 ? seconds * 1000L : 0L;
            if (id.isEmpty() || name.isEmpty()) continue;
            if (!versionNoise(title + " " + artist) && versionNoise(name + " " + singer)) continue;
            if (durationMs > 0 && duration > 0 && Math.abs(durationMs - duration) > 15000L) continue;
            int score = NetEaseLyricClient.matchScore(title, artist, durationMs,
                    name, singer, duration);
            if (score >= 100) result.add(new Candidate(id, score));
        }
        Collections.sort(result, (left, right) -> Integer.compare(right.score, left.score));
        return result;
    }

    static String webLyrics(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        int status = root.optInt("status", -1);
        if (status != 200) throw new ApiException(status);
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new ApiException(status);
        JSONArray lines = data.optJSONArray("lrclist");
        if (lines == null) return "";
        StringBuilder lrc = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length(), 10000); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line == null) continue;
            double seconds = line.optDouble("time", -1d);
            if (Double.isNaN(seconds) || Double.isInfinite(seconds)
                    || seconds < 0 || seconds > 86400) continue;
            appendLine(lrc, Math.round(seconds * 1000d), line.optString("lineLyric", ""));
        }
        return lrc.toString();
    }

    static void appendLine(StringBuilder out, long timeMs, String text) {
        String line = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim();
        if (line.isEmpty()) return;
        out.append(String.format(Locale.ROOT, "[%02d:%02d.%03d]", timeMs / 60000L,
                timeMs / 1000L % 60L, timeMs % 1000L)).append(line).append('\n');
    }

    static String decode(String value) {
        return value.replaceAll("\\\\+u0026", "&")
                .replace("&nbsp;", " ").replace("&apos;", "'")
                .replace("&#39;", "'").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim();
    }

    private static boolean versionNoise(String value) {
        return value.toLowerCase(Locale.ROOT).matches(
                ".*(伴奏|翻唱|现场|升调|降调|live|remix|dj|sped|slowed|ktv|cover|片段|串烧).*");
    }

    static final class Candidate {
        final String id;
        final int score;
        Candidate(String id, int score) { this.id = id; this.score = score; }
    }

    static final class ApiException extends Exception {
        final int status;
        ApiException(int status) { super("Kuwo lyric business status=" + status); this.status = status; }
    }
}
