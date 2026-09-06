package com.zuoqirun.lyricscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class KuwoLyricParserTest {
    @Test public void acceptsOnlyStandalonePositiveRids() {
        assertEquals("228908", KuwoLyricParser.trackId("MUSIC_228908"));
        assertEquals("228908", KuwoLyricParser.trackId(" 000228908 "));
        for (String invalid : new String[]{"", "0", "-1", "book:228908", "https://x/228908",
                "228908-123", "999999999999999999999"}) {
            assertEquals("", KuwoLyricParser.trackId(invalid));
        }
    }

    @Test public void parsesReorderedLegacyFieldsAndHtmlEntities() throws Exception {
        String search = "{'abslist':[{'NAME':'A&apos;B','MUSICRID':'MUSIC_12',"
                + "'DURATION':'200','ARTIST':'Singer'}]}";
        assertEquals("12", KuwoLyricParser.candidates(search, "A'B", "Singer", 200000)
                .get(0).id);
    }

    @Test public void skipsMalformedCandidatesWithoutLosingGoodOnes() throws Exception {
        String search = "{\"abslist\":[null,{},"
                + "{\"NAME\":\"歌\",\"ARTIST\":\"歌手\",\"DURATION\":200,\"MUSICRID\":\"MUSIC_9\"}]}";
        assertEquals(1, KuwoLyricParser.candidates(search, "歌", "歌手", 200000).size());
    }

    @Test public void rejectsAccompanimentEvenWhenOldScoreReachedThreshold() throws Exception {
        String search = "{'abslist':[{'NAME':'晴天 (KTV版伴奏)','ARTIST':'周杰伦',"
                + "'DURATION':'269','MUSICRID':'MUSIC_51685512'}]}";
        assertTrue(KuwoLyricParser.candidates(search, "晴天", "周杰伦", 269000).isEmpty());
        assertEquals(1, KuwoLyricParser.candidates(search, "晴天 (KTV版伴奏)", "周杰伦", 269000).size());
    }

    @Test public void rejectsWrongDurationAndDjArtist() throws Exception {
        String search = "{'abslist':[{'NAME':'歌','ARTIST':'歌手','DURATION':30,'MUSICRID':'MUSIC_1'},"
                + "{'NAME':'歌','ARTIST':'歌手&DJ Wave','DURATION':200,'MUSICRID':'MUSIC_2'}]}";
        assertTrue(KuwoLyricParser.candidates(search, "歌", "歌手", 200000).isEmpty());
    }

    @Test public void distinguishesHttpSuccessFromBusinessFailure() throws Exception {
        try {
            KuwoLyricParser.webLyrics("{\"status\":301,\"data\":null}");
            fail("Business error must not look like an empty lyric");
        } catch (KuwoLyricParser.ApiException error) {
            assertEquals(301, error.status);
        }
    }

    @Test public void webTimesAreSecondsAndInvalidTimesAreIgnored() throws Exception {
        String lrc = KuwoLyricParser.webLyrics("{\"status\":200,\"data\":{\"lrclist\":["
                + "{\"time\":\"1.125\",\"lineLyric\":\"示例\"},"
                + "{\"time\":\"NaN\",\"lineLyric\":\"无效\"},"
                + "{\"time\":-1,\"lineLyric\":\"负数\"}]}}");
        assertEquals("[00:01.125]示例\n", lrc);
    }

    @Test public void decodesRepeatedLegacyAmpersandEscapes() {
        assertEquals("A&B", KuwoLyricParser.decode("A\\\\\\\\u0026B"));
    }
}
