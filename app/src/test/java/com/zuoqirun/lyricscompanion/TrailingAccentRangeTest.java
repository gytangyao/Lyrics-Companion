package com.zuoqirun.lyricscompanion;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

/** Covers the wrap-aware range the trailing accent is drawn from. */
public class TrailingAccentRangeTest {
    @Test public void accentsOnlyTheShareOfTheWordInsideEachChunk() {
        // Line "abcdefghij" wrapped as [0, 6) + [6, 10), word at [4, 8) = "efgh".
        assertArrayEquals(new int[] { 4, 8 },
                LyricsPanelView.trailingWordInChunk(0, 10, 4, 8));
        assertArrayEquals(new int[] { 4, 6 },
                LyricsPanelView.trailingWordInChunk(0, 6, 4, 8));
        assertArrayEquals(new int[] { 0, 2 },
                LyricsPanelView.trailingWordInChunk(6, 4, 4, 8));
    }

    @Test public void aChunkTheWordNeverReachesAccentsNothing() {
        assertNull(LyricsPanelView.trailingWordInChunk(0, 4, 4, 8));
        assertNull(LyricsPanelView.trailingWordInChunk(8, 2, 4, 8));
    }

    @Test public void clampsToTheChunkAndRejectsEmptyInput() {
        // A word running past the end of its chunk stops at the chunk edge.
        assertArrayEquals(new int[] { 2, 4 }, LyricsPanelView.trailingWordInChunk(0, 4, 2, 99));
        assertNull("an empty word has nothing to accent",
                LyricsPanelView.trailingWordInChunk(0, 4, 3, 3));
        assertNull("an empty chunk has no room",
                LyricsPanelView.trailingWordInChunk(0, 0, 0, 5));
    }
}
