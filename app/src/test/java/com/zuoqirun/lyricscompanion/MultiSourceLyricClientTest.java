package com.zuoqirun.lyricscompanion;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class MultiSourceLyricClientTest {
    @Test public void triesTraditionalMetadataBeforeSimplifiedVariants() {
        List<LocalTrackQueryRules.Query> queries = MultiSourceLyricClient.catalogQueries(
                "spotify", "說好不哭", "周杰倫");

        assertQuery(queries.get(0), "說好不哭", "周杰倫");
        assertTrue(contains(queries, "说好不哭", "周杰伦"));
        assertTrue(contains(queries, "说好不哭", "周杰倫"));
        assertTrue(contains(queries, "說好不哭", "周杰伦"));
    }

    @Test public void convertsFilenameDerivedTraditionalCandidate() {
        List<LocalTrackQueryRules.Query> queries = MultiSourceLyricClient.catalogQueries(
                "media", "周杰倫 - 夜曲.flac", "");

        assertTrue(contains(queries, "夜曲", "周杰伦"));
    }

    @Test public void doesNotDuplicateAlreadySimplifiedQuery() {
        List<LocalTrackQueryRules.Query> queries = MultiSourceLyricClient.catalogQueries(
                "spotify", "夜曲", "周杰伦");

        assertEquals(1, queries.size());
    }

    private static boolean contains(List<LocalTrackQueryRules.Query> queries,
                                    String title, String artist) {
        for (LocalTrackQueryRules.Query query : queries) {
            if (title.equals(query.title) && artist.equals(query.artist)) return true;
        }
        return false;
    }

    private static void assertQuery(LocalTrackQueryRules.Query query, String title, String artist) {
        assertEquals(title, query.title);
        assertEquals(artist, query.artist);
    }
}
