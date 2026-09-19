package com.zuoqirun.lyricscompanion;

import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AudioFileCandidatesTest {
    @Test public void acceptsEverySupportedAudioExtension() {
        for (String extension : AudioFileCandidates.EXTENSIONS) {
            assertEquals("周杰伦 - 晴天.lrc",
                    AudioFileCandidates.lyricCandidateKey("周杰伦 - 晴天." + extension));
            assertEquals("uppercase " + extension, "周杰伦 - 晴天.lrc",
                    AudioFileCandidates.lyricCandidateKey("周杰伦 - 晴天."
                            + extension.toUpperCase(Locale.ROOT)));
        }
    }

    @Test public void ignoresFormatsWhoseTagsTheReaderCannotSee() {
        String[] notReadable = {"wav", "wma", "ape", "aiff", "aif", "dsf", "dff", "alac", "mka",
                "txt", "jpg", "png", "lrc", "cue", "mp3.bak"};
        for (String extension : notReadable) {
            assertEquals(extension, "", AudioFileCandidates.lyricCandidateKey("晴天." + extension));
        }
    }

    @Test public void ignoresNamesWithoutAnAudioFileName() {
        assertEquals("", AudioFileCandidates.lyricCandidateKey(null));
        assertEquals("", AudioFileCandidates.lyricCandidateKey(""));
        assertEquals("", AudioFileCandidates.lyricCandidateKey("   "));
        assertEquals("", AudioFileCandidates.lyricCandidateKey("晴天"));
        assertEquals("", AudioFileCandidates.lyricCandidateKey(".mp3"));
        assertEquals("", AudioFileCandidates.lyricCandidateKey("晴天."));
        assertEquals("", AudioFileCandidates.lyricCandidateKey(" .MP3"));
    }

    @Test public void keepsTrackNumbersAndInnerDots() {
        assertEquals("01. track.lrc", AudioFileCandidates.lyricCandidateKey("01. Track.MP3"));
        assertEquals("track 1.mp3.lrc",
                AudioFileCandidates.lyricCandidateKey("Track 1.MP3.flac"));
        assertEquals("晴天.lrc.lrc", AudioFileCandidates.lyricCandidateKey("晴天.lrc.mp3"));
    }

    @Test public void collapsesBlankRunsLikeTheLrcCandidateNames() {
        assertEquals("晴天 (live).lrc",
                AudioFileCandidates.lyricCandidateKey("  晴天   (Live).FLAC  "));
        assertEquals("周杰伦 - 晴天.lrc",
                AudioFileCandidates.lyricCandidateKey("周杰伦\t-\t晴天.M4A"));
        assertEquals("周杰伦 - 晴天.lrc",
                AudioFileCandidates.lyricCandidateKey("周杰伦  -  晴天.M4A"));
    }

    /** The key has to land in the very set of names a .lrc scan looks for. */
    @Test public void matchesCandidateNamesBuiltFromPlayerMetadata() {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(normalize("晴天") + ".lrc");
        candidates.add(normalize("周杰伦 - 晴天") + ".lrc");
        candidates.add(normalize("晴天 - 周杰伦") + ".lrc");

        assertTrue(candidates.contains(AudioFileCandidates.lyricCandidateKey("晴天.mp3")));
        assertTrue(candidates.contains(AudioFileCandidates.lyricCandidateKey("周杰伦 - 晴天.flac")));
        assertTrue(candidates.contains(
                AudioFileCandidates.lyricCandidateKey("晴天 - 周杰伦  .M4A")));
        // A different song in the same folder must not be opened.
        assertTrue(!candidates.contains(AudioFileCandidates.lyricCandidateKey("晴天 (Live).mp3")));
    }

    /** Mirrors LocalLyricClient.normalize, which builds the .lrc candidate names. */
    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\\\/:*?\"<>|]", " ")
                .replaceAll("\\s+", " ");
    }
}
