package wemew.lrc.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds and parses web sync protocol frames. Pure JVM (no android.* imports) so the exact
 * wire format is unit-testable against the schema in docs/web-protocol.md.
 *
 * <p>Both sides must ignore unknown message types and unknown fields; this parser follows that
 * rule and never throws on malformed input. {@link #put} folds the checked JSONException of
 * the Android org.json build into a state error — our keys are constants, so it cannot fire.</p>
 */
final class WebProtocolCodec {

    /**
     * Largest frame this build puts on the wire. The cloud relay buffers a whole inbound text
     * message before handing it out and closes the socket with 1009 above its limit (8KB), while
     * a 40-line word-level QRC timeline serializes to 10-12KB — so a timeline that big arrives
     * as a {@code track} head plus {@code lyric_chunk} frames under this ceiling.
     */
    static final int MAX_FRAME_BYTES = 6_000;

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

    /** One track push on the wire: the frames to send, in order, plus what they carry. */
    static final class TrackStream {
        /** Stream id, "" when the timeline fit in a single frame. */
        final String id;
        final List<String> frames;
        final int chunkCount;
        final int byteCount;

        TrackStream(String id, List<String> frames, int chunkCount) {
            this.id = id == null ? "" : id;
            this.frames = frames;
            this.chunkCount = chunkCount;
            int bytes = 0;
            for (String frame : frames) bytes += utf8Length(frame);
            this.byteCount = bytes;
        }
    }

    /**
     * Builds a track push that never exceeds {@link #MAX_FRAME_BYTES} per frame: one frame when
     * the timeline fits, otherwise a {@code track} head that names a stream and the
     * {@code lyric_chunk} frames that fill it.
     *
     * <p>The id is a hash of the timeline itself, so re-pushing the same song (a reconnect
     * snapshot, a publisher restart) reuses the id and a display that already holds those lines
     * drops the chunks instead of rebuilding the surface.</p>
     */
    static TrackStream trackStream(long ts, TrackPayload payload) {
        String single = track(ts, payload);
        if (utf8Length(single) <= MAX_FRAME_BYTES) {
            return new TrackStream("", Collections.singletonList(single), 0);
        }
        JSONArray lines = linesData(payload.timeline);
        String id = streamId(lines);
        List<String> chunks = chunkFrames(ts, id, lines);
        List<String> frames = new ArrayList<>();
        frames.add(envelope(WebProtocol.TYPE_TRACK, ts, trackData(payload,
                streamLyricData(payload, id, lines.length(), chunks.size()))));
        frames.addAll(chunks);
        return new TrackStream(id, frames, chunks.size());
    }

    /**
     * Greedy split: as many whole lines per frame as the ceiling allows, in timeline order.
     * The budget is measured against a worst-case envelope (three-digit seq and total), so the
     * emitted frames, which carry no more digits than the real count, are always inside it.
     */
    private static List<String> chunkFrames(long ts, String id, JSONArray lines) {
        int overhead = utf8Length(chunkFrame(ts, id, 999, 999, new JSONArray()));
        int budget = MAX_FRAME_BYTES - overhead;
        List<JSONArray> batches = new ArrayList<>();
        JSONArray batch = new JSONArray();
        int used = 0;
        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = objectAt(lines, i);
            int cost = utf8Length(line.toString()) + 1;
            if (batch.length() > 0 && used + cost > budget) {
                batches.add(batch);
                batch = new JSONArray();
                used = 0;
            }
            batch.put(line);
            used += cost;
        }
        if (batch.length() > 0) batches.add(batch);
        List<String> frames = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            frames.add(chunkFrame(ts, id, i + 1, batches.size(), batches.get(i)));
        }
        return frames;
    }

    private static String chunkFrame(long ts, String id, int seq, int total, JSONArray lines) {
        JSONObject data = new JSONObject();
        put(data, "id", id);
        put(data, "seq", seq);
        put(data, "total", total);
        put(data, "lines", lines);
        return envelope(WebProtocol.TYPE_LYRIC_CHUNK, ts, data);
    }

    /**
     * Content hash of the timeline, hex, 16 chars. FNV-1a: cheap, stable across builds, and
     * wide enough that two different songs colliding is not a case worth handling.
     */
    private static String streamId(JSONArray lines) {
        String text = lines.toString();
        long hash = 0xcbf29ce484222325L;
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (value & 0xff)) * 0x100000001b3L;
        }
        return paddedHex(hash);
    }

    private static String paddedHex(long value) {
        String hex = Long.toHexString(value);
        return "0000000000000000".substring(hex.length()) + hex;
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    static JSONObject trackData(TrackPayload payload) {
        return trackData(payload, lyricData(payload));
    }

    private static JSONObject trackData(TrackPayload payload, Object lyric) {
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
        put(data, "lyric", lyric);
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

    /** The {@code lyric} of a head frame: same identity, lines replaced by a stream promise. */
    private static Object streamLyricData(TrackPayload payload, String id, int lineCount,
                                          int chunkTotal) {
        JSONObject matched = new JSONObject();
        put(matched, "live", false);
        put(matched, "sourceName",
                payload.lyricSourceName == null ? "" : payload.lyricSourceName);
        JSONObject stream = new JSONObject();
        put(stream, "id", id);
        put(stream, "total", chunkTotal);
        put(stream, "lines", lineCount);
        put(matched, "stream", stream);
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

    /** Tells every display to clear its lyric surface (wrong-track guard on the phone). */
    static String blank(long ts, String reason) {
        JSONObject data = new JSONObject();
        put(data, "reason", reason == null ? "" : reason);
        return envelope(WebProtocol.TYPE_BLANK, ts, data);
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

    /** Same, for the checked {@code JSONArray.getJSONObject} of the Android org.json build. */
    private static JSONObject objectAt(JSONArray array, int index) {
        try {
            return array.getJSONObject(index);
        } catch (JSONException error) {
            throw new IllegalStateException("protocol read failed at " + index, error);
        }
    }
}
