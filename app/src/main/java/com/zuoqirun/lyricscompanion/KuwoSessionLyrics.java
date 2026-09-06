package com.zuoqirun.lyricscompanion;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** One instance per MediaSession. Extras have no RID, so require a matching song header. */
final class KuwoSessionLyrics {
    private String identity = "";
    private String payload = "";
    private String blockedPayload = "";
    private String acceptedPayload = "";
    private LrcTimeline parsed = LrcTimeline.EMPTY;

    LrcTimeline read(String mediaId, String title, String artist, String incoming) {
        String nextIdentity = mediaId + "\n" + title + "\n" + artist;
        String raw = incoming == null ? "" : incoming;
        boolean changed = !identity.equals(nextIdentity);
        if (changed) {
            if (!acceptedPayload.isEmpty()) blockedPayload = acceptedPayload;
            acceptedPayload = "";
            identity = nextIdentity;
            parsed = LrcTimeline.EMPTY;
        } else if (payload.equals(raw)) {
            return parsed;
        }
        payload = raw;
        parsed = LrcTimeline.EMPTY;
        if (raw.isEmpty() || raw.length() > 500000 || raw.equals(blockedPayload)) return parsed;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("resultCode", -1) != 20000) return parsed;
            JSONArray lines = root.optJSONArray("AUDIO_LYRIC");
            if (lines == null || lines.length() == 0 || lines.length() > 10000) return parsed;
            JSONObject header = lines.optJSONObject(0);
            if (header == null || !matchesHeader(header.optString("text", ""), title, artist)) {
                return parsed;
            }
            java.util.List<LrcTimeline.Line> timedLines = new java.util.ArrayList<>();
            for (int i = 0; i < lines.length(); i++) {
                JSONObject line = lines.optJSONObject(i);
                if (line == null) continue;
                long start = line.optLong("startTime", -1L);
                if (start < 0 || start > 86400000L) continue;
                String text = line.optString("text", "").trim();
                long duration = line.optLong("time", 0L);
                if (text.isEmpty()) continue;
                timedLines.add(new LrcTimeline.Line(start, Math.max(0L,
                        Math.min(duration, 86400000L - start)), text));
            }
            if (timedLines.size() < 2) return parsed;
            parsed = LrcTimeline.fromTimedLines(timedLines);
            if (!parsed.isEmpty()) acceptedPayload = raw;
        } catch (Exception ignored) {
            // Invalid, unbound or incomplete extras leave RID/catalog fallback available.
        }
        return parsed;
    }

    static boolean matchesHeader(String header, String title, String artist) {
        if (title == null || title.trim().isEmpty() || artist == null || artist.trim().isEmpty()) {
            return false;
        }
        String[] parts = header.split("\\s+[-–—]\\s+", 2);
        if (parts.length != 2 || !normalize(parts[0]).equals(normalize(title))) return false;
        String singer = normalize(parts[1]);
        String wanted = normalize(artist);
        // Kuwo may append an English alias in parentheses to the artist header.
        return singer.equals(wanted) || normalize(parts[1].replaceAll("[（(].*?[）)]", ""))
                .equals(wanted);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]+", "");
    }
}
