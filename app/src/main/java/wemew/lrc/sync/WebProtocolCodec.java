package wemew.lrc.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds and parses web sync protocol frames. Pure JVM (no android.* imports) so the exact
 * wire format is unit-testable against the schema in docs/web-protocol.md.
 *
 * <p>Both sides must ignore unknown message types and unknown fields; this parser follows that
 * rule and never throws on malformed input. {@link #put} folds the checked JSONException of
 * the Android org.json build into a state error — our keys are constants, so it cannot fire.</p>
 */
final class WebProtocolCodec {

    /** Everything the {@code track} message needs; built by the publisher from a snapshot. */
    static final class TrackPayload {
        boolean active;
        boolean playing;
        String sourceId = "";
        String sourceName = "";
        String title = "";
        String artist = "";
        long durationMs = -1L;
        long positionMs;
        float speed = 1f;
        /** The lyric offset already applied to the timeline, so clients never apply it twice. */
        int offsetMs;
        LrcTimeline timeline = LrcTimeline.EMPTY;
        /** True when the displayed lyric is the player's live line, not the matched timeline. */
        boolean liveMode;
        String lyricSourceName = "";
        /** Current live line, included so a joining client immediately shows something. */
        String liveLine = "";
        boolean hasArt;
        int artVersion;
    }

    /** A parsed inbound frame; {@code type} is empty when the input was not usable. */
    static final class Inbound {
        final int version;
        final String type;
        /** Envelope timestamp (the ping echo source), 0 when absent. */
        final long ts;
        /** Cloud-room role of the sender (control_box / control_box_phone), "" on LAN. */
        final String sender;
        final JSONObject data;

        Inbound(int version, String type, long ts, String sender, JSONObject data) {
            this.version = version;
            this.type = type;
            this.ts = ts;
            this.sender = sender == null ? "" : sender;
            this.data = data;
        }
    }

    private WebProtocolCodec() {}

    static String welcome(long ts, String serverVersion) {
        JSONObject server = new JSONObject();
        put(server, "name", WebProtocol.SERVER_NAME);
        put(server, "version", serverVersion == null ? "" : serverVersion);
        JSONArray capabilities = new JSONArray();
        for (String capability : WebProtocol.CAPABILITIES) capabilities.put(capability);
        JSONObject data = new JSONObject();
        put(data, "protocol", WebProtocol.PROTOCOL_VERSION);
        put(data, "server", server);
        put(data, "capabilities", capabilities);
        return envelope(WebProtocol.TYPE_WELCOME, ts, data);
    }

    static String track(long ts, TrackPayload payload) {
        return envelope(WebProtocol.TYPE_TRACK, ts, trackData(payload));
    }

    static JSONObject trackData(TrackPayload payload) {
        JSONObject data = new JSONObject();
        put(data, "active", payload.active);
        put(data, "playing", payload.playing);
        put(data, "source", payload.sourceId == null ? "" : payload.sourceId);
        put(data, "sourceName", payload.sourceName == null ? "" : payload.sourceName);
        put(data, "title", payload.title == null ? "" : payload.title);
        put(data, "artist", payload.artist == null ? "" : payload.artist);
        put(data, "durationMs", payload.durationMs);
        put(data, "positionMs", payload.positionMs);
        put(data, "speed", (double) payload.speed);
        put(data, "offsetMs", payload.offsetMs);
        put(data, "lyric", lyricData(payload));
        put(data, "hasArt", payload.hasArt);
        put(data, "artVersion", payload.artVersion);
        return data;
    }

    /** {@code JSONObject.NULL} when nothing is available; {@code live:true} when only line pushes follow. */
    private static Object lyricData(TrackPayload payload) {
        if (payload.liveMode) {
            JSONObject live = new JSONObject();
            put(live, "live", true);
            put(live, "sourceName", payload.lyricSourceName == null ? "" : payload.lyricSourceName);
            put(live, "currentText", payload.liveLine == null ? "" : payload.liveLine);
            return live;
        }
        if (payload.timeline == null || payload.timeline.isEmpty()) return JSONObject.NULL;
        JSONObject matched = new JSONObject();
        put(matched, "live", false);
        put(matched, "sourceName",
                payload.lyricSourceName == null ? "" : payload.lyricSourceName);
        put(matched, "lines", linesData(payload.timeline));
        return matched;
    }

    private static JSONArray linesData(LrcTimeline timeline) {
        JSONArray lines = new JSONArray();
        for (LrcTimeline.Line line : timeline.lines()) {
            JSONObject entry = new JSONObject();
            put(entry, "t", line.timeMs);
            put(entry, "d", line.durationMs);
            put(entry, "text", line.text == null ? "" : line.text);
            if (line.translated != null && !line.translated.isEmpty()) {
                put(entry, "translation", line.translated);
            }
            if (line.words != null && !line.words.isEmpty()) {
                JSONArray words = new JSONArray();
                for (LrcTimeline.Word word : line.words) {
                    JSONObject wordEntry = new JSONObject();
                    put(wordEntry, "t", word.startMs);
                    put(wordEntry, "d", word.durationMs);
                    put(wordEntry, "text", word.text == null ? "" : word.text);
                    words.put(wordEntry);
                }
                put(entry, "words", words);
            }
            lines.put(entry);
        }
        return lines;
    }

    static String state(long ts, boolean active, boolean playing, long positionMs, float speed,
                        long durationMs, boolean trackCurrent) {
        return envelope(WebProtocol.TYPE_STATE, ts,
                stateData(active, playing, positionMs, speed, durationMs, trackCurrent));
    }

    static JSONObject stateData(boolean active, boolean playing, long positionMs, float speed,
                                long durationMs, boolean trackCurrent) {
        JSONObject data = new JSONObject();
        put(data, "active", active);
        put(data, "playing", playing);
        put(data, "positionMs", positionMs);
        put(data, "speed", (double) speed);
        put(data, "durationMs", durationMs);
        put(data, "track", trackCurrent);
        return data;
    }

    static String line(long ts, String text, String nextText, String translation) {
        return envelope(WebProtocol.TYPE_LINE, ts, lineData(text, nextText, translation));
    }

    static JSONObject lineData(String text, String nextText, String translation) {
        JSONObject data = new JSONObject();
        put(data, "text", text == null ? "" : text);
        put(data, "nextText", nextText == null ? "" : nextText);
        put(data, "translation", translation == null ? "" : translation);
        return data;
    }

    static String pong(long t0, long t1) {
        JSONObject data = new JSONObject();
        put(data, "t0", t0);
        return envelope(WebProtocol.TYPE_PONG, t1, data);
    }

    static String error(long ts, String code, String message) {
        JSONObject data = new JSONObject();
        put(data, "code", code == null ? "" : code);
        put(data, "message", message == null ? "" : message);
        return envelope(WebProtocol.TYPE_ERROR, ts, data);
    }

    /** REST snapshot: the current track data (or null) plus the current state data. */
    static String restSnapshot(JSONObject trackData, JSONObject stateData) {
        JSONObject root = new JSONObject();
        put(root, "v", WebProtocol.PROTOCOL_VERSION);
        put(root, "track", trackData == null ? JSONObject.NULL : trackData);
        put(root, "state", stateData);
        return root.toString();
    }

    static String envelope(String type, long ts, JSONObject data) {
        JSONObject envelope = new JSONObject();
        put(envelope, "v", WebProtocol.PROTOCOL_VERSION);
        put(envelope, "type", type);
        put(envelope, "ts", ts);
        put(envelope, "data", data);
        return envelope.toString();
    }

    /** Tolerant parser: any malformed frame yields {@code type == ""}, never an exception. */
    static Inbound parseInbound(String raw) {
        Inbound empty = new Inbound(WebProtocol.PROTOCOL_VERSION, "", 0L, "", null);
        if (raw == null) return empty;
        String text = raw.trim();
        if (text.isEmpty()) return empty;
        try {
            JSONObject root = new JSONObject(text);
            int version = root.optInt("v", WebProtocol.PROTOCOL_VERSION);
            String type = root.optString("type", "");
            long ts = root.optLong("ts", 0L);
            String sender = root.optString("sender", "");
            JSONObject data = root.optJSONObject("data");
            return new Inbound(version, type, ts, sender, data);
        } catch (Throwable error) {
            return empty;
        }
    }

    /** Puts with the checked Android JSONException folded away; keys here are constants. */
    static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException error) {
            throw new IllegalStateException("protocol put failed for " + key, error);
        }
    }
}
