package wemew.lrc.sync;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Strips the karaoke suffixes 全民K歌 puts into track titles before the title is used for
 * matching — "歌名（原唱：周杰伦）"、"歌名（伴奏）"、"歌名（cover）" — so catalog searches see
 * the plain song name. Conservative by design: only parenthesised suffixes are removed.
 */
final class KaraokeTitleRules {
    private static final Pattern[] SUFFIXES = {
            // （原唱：xxx） / (原唱:xxx) / （原唱 xxx） with full- or half-width parens.
            Pattern.compile("[（(]\\s*原唱\\s*[:：]?\\s*[^()（）]*[)）]\\s*$"),
            // （伴奏） / (伴奏) / （纯伴奏） / （KTV伴奏）.
            Pattern.compile("[（(]\\s*(?:纯|KTV|ktv)?\\s*伴奏\\s*[)）]\\s*$"),
            // （cover：xxx） / (Cover) / （翻唱：xxx）.
            Pattern.compile("[（(]\\s*(?:cover|Cover|翻唱)\\s*[:：]?\\s*[^()（）]*[)）]\\s*$"),
            // （女生版） / (DJ版) / （动态歌词版）— performance-variant markers.
            Pattern.compile("[（(]\\s*(?:DJ|dj|女生|男声|live|Live|现场)版?\\s*[)）]\\s*$")
    };

    private KaraokeTitleRules() {}

    static String clean(String title) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) return value;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Pattern suffix : SUFFIXES) {
                String stripped = suffix.matcher(value).replaceFirst("");
                if (!stripped.equals(value)) {
                    value = stripped.trim();
                    changed = true;
                }
            }
        }
        return value;
    }

    static boolean looksLikeKaraokeTitle(String title) {
        return !clean(title == null ? "" : title).equals(title == null ? "" : title.trim())
                || (title != null && title.toLowerCase(Locale.ROOT).contains("k歌"));
    }
}
