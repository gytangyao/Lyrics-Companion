package wemew.lrc.sync;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WebProtocolCodecTest {

    private static LrcTimeline sampleTimeline() {
        List<LrcTimeline.Line> lines = new ArrayList<>();
        List<LrcTimeline.Word> words = new ArrayList<>();
        words.add(new LrcTimeline.Word(40100, 410, "对"));
        words.add(new LrcTimeline.Word(40510, 410, "这"));
        LrcTimeline.Line enhanced = new LrcTimeline.Line(40100, 5200,
                "对这个世界如果你有太多的抱怨", "If you have too many complaints", words);
        lines.add(enhanced);
        lines.add(new LrcTimeline.Line(45300, 3000, "割掉人生的另一番风景"));
        return LrcTimeline.fromTimedLines(lines);
    }

    private static WebProtocolCodec.TrackPayload payload(LrcTimeline timeline) {
        WebProtocolCodec.TrackPayload payload = new WebProtocolCodec.TrackPayload();
        payload.active = true;
        payload.playing = true;
        payload.sourceId = "netease";
        payload.sourceName = "网易云音乐";
        payload.title = "稻香";
        payload.artist = "周杰伦";
        payload.durationMs = 223000;
        payload.positionMs = 41200;
        payload.speed = 1f;
        payload.offsetMs = -120;
        payload.timeline = timeline;
        payload.lyricSourceName = "网易云歌词";
        payload.hasArt = true;
        payload.artVersion = 7;
        return payload;
    }

    @org.junit.Test public void trackEnvelopeCarriesVersionTypeTsData() throws Exception {
        String json = WebProtocolCodec.track(1760000000000L, payload(sampleTimeline()));
        JSONObject root = new JSONObject(json);
        assertEquals(1, root.getInt("v"));
        assertEquals("track", root.getString("type"));
        assertEquals(1760000000000L, root.getLong("ts"));
        JSONObject data = root.getJSONObject("data");
        assertEquals("稻香", data.getString("title"));
        assertEquals("周杰伦", data.getString("artist"));
        assertEquals("netease", data.getString("source"));
        assertEquals(223000, data.getLong("durationMs"));
        assertEquals(41200, data.getLong("positionMs"));
        assertEquals(1d, data.getDouble("speed"), 0.0001);
        assertEquals(-120, data.getInt("offsetMs"));
        assertTrue(data.getBoolean("hasArt"));
        assertEquals(7, data.getInt("artVersion"));
    }

    @org.junit.Test public void matchedLyricSerializesLinesAndWords() throws Exception {
        JSONObject lyric = WebProtocolCodec.trackData(payload(sampleTimeline()))
                .getJSONObject("lyric");
        assertFalse(lyric.getBoolean("live"));
        assertEquals("网易云歌词", lyric.getString("sourceName"));
        JSONObject first = lyric.getJSONArray("lines").getJSONObject(0);
        assertEquals(40100, first.getLong("t"));
        assertEquals(5200, first.getLong("d"));
        assertEquals("对这个世界如果你有太多的抱怨", first.getString("text"));
        assertEquals("If you have too many complaints", first.getString("translation"));
        JSONObject word = first.getJSONArray("words").getJSONObject(1);
        assertEquals(40510, word.getLong("t"));
        assertEquals(410, word.getLong("d"));
        assertEquals("这", word.getString("text"));
        JSONObject second = lyric.getJSONArray("lines").getJSONObject(1);
        assertFalse(second.has("translation"));
        assertFalse(second.has("words"));
    }

    @org.junit.Test public void liveModeOmitsLinesAndCarriesCurrentText() throws Exception {
        WebProtocolCodec.TrackPayload live = payload(LrcTimeline.EMPTY);
        live.liveMode = true;
        live.liveLine = "正在唱歌";
        JSONObject lyric = WebProtocolCodec.trackData(live).getJSONObject("lyric");
        assertTrue(lyric.getBoolean("live"));
        assertEquals("正在唱歌", lyric.getString("currentText"));
        assertFalse(lyric.has("lines"));
    }

    @org.junit.Test public void missingLyricBecomesNull() throws Exception {
        WebProtocolCodec.TrackPayload empty = payload(LrcTimeline.EMPTY);
        JSONObject data = WebProtocolCodec.trackData(empty);
        assertTrue(data.isNull("lyric"));
    }

    @org.junit.Test public void stateFrameShape() throws Exception {
        String json = WebProtocolCodec.state(123L, true, false, 45678, 1.5f, 200000, true);
        JSONObject data = new JSONObject(json).getJSONObject("data");
        assertTrue(data.getBoolean("active"));
        assertFalse(data.getBoolean("playing"));
        assertEquals(45678, data.getLong("positionMs"));
        assertEquals(1.5d, data.getDouble("speed"), 0.0001);
        assertEquals(200000, data.getLong("durationMs"));
        assertTrue(data.getBoolean("track"));
    }

    @org.junit.Test public void stateDataIsReusable() throws Exception {
        // trackData/stateData are the building blocks of the track/state frames.
        JSONObject state = WebProtocolCodec.stateData(false, false, 0, 1f, -1, false);
        assertFalse(state.getBoolean("active"));
    }

    @org.junit.Test public void pongEchoesClientTimestamp() throws Exception {
        JSONObject data = new JSONObject(WebProtocolCodec.pong(111L, 222L))
                .getJSONObject("data");
        assertEquals(111L, data.getLong("t0"));
        assertEquals(222L, new JSONObject(WebProtocolCodec.pong(111L, 222L)).getLong("ts"));
    }

    @org.junit.Test public void parseInboundReadsHelloPingAndUnknownTypes() throws Exception {
        WebProtocolCodec.Inbound ping = WebProtocolCodec.parseInbound(
                "{\"v\":1,\"type\":\"ping\",\"ts\":777}");
        assertEquals("ping", ping.type);
        assertEquals(1, ping.version);
        assertEquals(777L, ping.ts);

        WebProtocolCodec.Inbound unknown = WebProtocolCodec.parseInbound(
                "{\"v\":3,\"type\":\"future-thing\",\"data\":{\"x\":1}}");
        assertEquals("future-thing", unknown.type);
        assertEquals(3, unknown.version);

        WebProtocolCodec.Inbound missingVersion = WebProtocolCodec.parseInbound(
                "{\"type\":\"hello\",\"data\":{\"protocol\":1}}");
        assertEquals("hello", missingVersion.type);
        assertEquals(1, missingVersion.version);
    }

    @org.junit.Test public void parseInboundNeverThrowsOnGarbage() throws Exception {
        assertEquals("", WebProtocolCodec.parseInbound(null).type);
        assertEquals("", WebProtocolCodec.parseInbound("").type);
        assertEquals("", WebProtocolCodec.parseInbound("not json").type);
        assertEquals("", WebProtocolCodec.parseInbound("[1,2,3]").type);
        assertNull(WebProtocolCodec.parseInbound("garbage").data);
    }

    @org.junit.Test public void welcomeAdvertisesProtocolAndCapabilities() throws Exception {
        JSONObject root = new JSONObject(WebProtocolCodec.welcome(9L, "1.2.3"));
        assertEquals("welcome", root.getString("type"));
        JSONObject data = root.getJSONObject("data");
        assertEquals(1, data.getInt("protocol"));
        assertEquals("wemew-lrc-sync", data.getJSONObject("server").getString("name"));
        assertEquals("1.2.3", data.getJSONObject("server").getString("version"));
        assertTrue(data.getJSONArray("capabilities").length() >= 5);
    }
}
