package com.zuoqirun.lyricscompanion;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Name rules for the fallback that reads lyrics out of an audio file when the player never handed
 * us a usable path to it (Poweramp keeps its media URI to itself, so {@code mediaUri} is empty or
 * unreadable for other apps).
 *
 * <p>A directory scan then accepts one more kind of candidate next to "歌名.lrc": an audio file
 * standing in for the same name, for example "歌名.mp3". Its embedded tag holds the very text the
 * player itself displays.
 *
 * <p>Only containers {@link EmbeddedLyricReader} can parse are listed. Opening one costs a read of
 * up to 8 MB, so formats whose tags the reader cannot see (wav/wma/ape/aiff/dsf) are filtered out
 * by extension before any file is opened.
 */
final class AudioFileCandidates {
    /** Extensions worth opening: ID3 v2 (mp3/aac), FLAC, Vorbis/Opus and MP4 boxes. */
    static final Set<String> EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "mp3", "flac", "m4a", "m4b", "mp4", "ogg", "oga", "opus", "aac")));
    private static final String LRC_SUFFIX = ".lrc";
    /** Runs of blanks are collapsed the same way the .lrc candidate names are built. */
    private static final Pattern SPACE_RUN = Pattern.compile("\\s+");

    private AudioFileCandidates() { }

    /**
     * The .lrc candidate key this file name stands in for - "晴天.lrc" for "晴天.mp3" - or "" when
     * the name is not an audio file worth opening.
     *
     * <p>Keys are compared against the names built by {@code LocalLyricClient.candidateNames}, so
     * this mirrors their wording: only the extension decides, the remaining name is lower-cased
     * with runs of blanks collapsed and the edges trimmed.
     */
    static String lyricCandidateKey(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot + 1 >= name.length()) return "";
        if (!EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) return "";
        String base = SPACE_RUN.matcher(name.substring(0, dot)).replaceAll(" ").trim()
                .toLowerCase(Locale.ROOT);
        return base.isEmpty() ? "" : base + LRC_SUFFIX;
    }
}
