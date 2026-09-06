package com.zuoqirun.lyricscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class KuwoSessionLyricsTest {
    private static String payload(String title) {
        return "{\"resultCode\":20000,\"AUDIO_LYRIC\":["
                + "{\"startTime\":0,\"time\":15000,\"text\":\"" + title + " - 歌手\"},"
                + "{\"startTime\":15000,\"time\":20000,\"text\":\"示例歌词\"}]}";
    }

    @Test public void coldReadUsesMatchingHeaderAndCachesTimeline() {
        KuwoSessionLyrics reader = new KuwoSessionLyrics();
        LrcTimeline result = reader.read("12", "歌一", "歌手", payload("歌一"));
        assertEquals(2, result.lineCount());
        assertSame(result, reader.read("12", "歌一", "歌手", payload("歌一")));
        assertFalse(result.at(26000).interlude);
        assertEquals(15000L, result.at(26000).lineStartMs);
        assertEquals(20000L, result.at(26000).lineDurationMs);
    }

    @Test public void refusesPreviousTrackPayloadAfterRidChangeEvenWithSameTitle() {
        KuwoSessionLyrics reader = new KuwoSessionLyrics();
        assertFalse(reader.read("12", "歌一", "歌手", payload("歌一")).isEmpty());
        assertTrue(reader.read("13", "歌一", "歌手", payload("歌一")).isEmpty());
        assertTrue(reader.read("13", "歌一", "歌手", payload("歌一")).isEmpty());
    }

    @Test public void acceptsNewExtrasThatArrivedBeforeMetadata() {
        KuwoSessionLyrics reader = new KuwoSessionLyrics();
        reader.read("12", "歌一", "歌手", payload("歌一"));
        assertTrue(reader.read("12", "歌一", "歌手", payload("歌二")).isEmpty());
        assertFalse(reader.read("13", "歌二", "歌手", payload("歌二")).isEmpty());
    }

    @Test public void acceptsNewExtrasAfterMetadataAndLoadingState() {
        KuwoSessionLyrics reader = new KuwoSessionLyrics();
        reader.read("12", "歌一", "歌手", payload("歌一"));
        assertTrue(reader.read("13", "歌二", "歌手", payload("歌一")).isEmpty());
        assertTrue(reader.read("13", "歌二", "歌手", "{\"resultCode\":20001}").isEmpty());
        assertFalse(reader.read("13", "歌二", "歌手", payload("歌二")).isEmpty());
    }

    @Test public void ignoresUnboundMalformedAndFailedExtras() {
        KuwoSessionLyrics reader = new KuwoSessionLyrics();
        assertTrue(reader.read("12", "歌一", "别人", payload("歌一")).isEmpty());
        assertTrue(reader.read("12", "歌一", "歌手", "broken").isEmpty());
        assertTrue(reader.read("12", "歌一", "歌手", payload("歌一")
                .replace("20000", "20003")).isEmpty());
    }

    @Test public void supportsArtistAliasButNotAnotherArtistOrSongVersion() {
        assertTrue(KuwoSessionLyrics.matchesHeader("晴天 - 周杰伦 (Jay Chou)", "晴天", "周杰伦"));
        assertFalse(KuwoSessionLyrics.matchesHeader("晴天 - 周杰伦", "晴天", "别人"));
        assertFalse(KuwoSessionLyrics.matchesHeader("晴天 (Live) - 周杰伦", "晴天", "周杰伦"));
    }
}
