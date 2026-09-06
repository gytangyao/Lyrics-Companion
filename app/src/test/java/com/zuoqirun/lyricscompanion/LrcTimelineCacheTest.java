package com.zuoqirun.lyricscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class LrcTimelineCacheTest {
    @Test public void preservesTranslationAndWordProgress() throws Exception {
        LrcTimeline original = LrcTimeline.parse("[00:01.00]你好世界", "[00:01.00]Hello world",
                "[1000,2000](1000,500,0)你(1500,500,0)好(2000,1000,0)世界");
        LrcTimeline restored = LrcTimeline.fromCacheBytes(original.toCacheBytes());
        for (long time : new long[]{0, 1000, 1750, 2500, 6000}) {
            LrcTimeline.At a = original.at(time), b = restored.at(time);
            assertEquals(a.lyric, b.lyric);
            assertEquals(a.translatedLyric, b.translatedLyric);
            assertEquals(a.completedLyric, b.completedLyric);
            assertEquals(a.currentWord, b.currentWord);
            assertEquals(a.wordProgressPermille, b.wordProgressPermille);
            assertEquals(a.trailingWord, b.trailingWord);
        }
    }

    @Test(expected = java.io.IOException.class) public void rejectsTruncatedCache() throws Exception {
        byte[] bytes = LrcTimeline.parse("[00:01.00]hello", "").toCacheBytes();
        LrcTimeline.fromCacheBytes(java.util.Arrays.copyOf(bytes, bytes.length - 1));
    }

    @Test public void cacheKeysSeparateChineseSongsAndVersions() {
        String first = MatchedLyricCache.key("qqmusic", "晴天", "周杰伦", -1, "", "");
        assertNotEquals(first, MatchedLyricCache.key("qqmusic", "七里香", "周杰伦", -1, "", ""));
        assertNotEquals(first, MatchedLyricCache.key("qqmusic", "晴天 (Live)", "周杰伦", -1, "", ""));
        assertNotEquals(first, MatchedLyricCache.key("kuwo", "晴天", "周杰伦", -1, "", ""));
    }
}
