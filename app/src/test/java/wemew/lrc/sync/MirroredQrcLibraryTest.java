package wemew.lrc.sync;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Bulk check over the whole mirrored QRC library (the directory the adb mirror loop fills).
 * Run with -Dqrc.dir=path/to/mirror; skipped when the property is absent so CI stays green.
 */
public class MirroredQrcLibraryTest {

    @Test public void everyMirroredQrcYieldsATimeline() throws Exception {
        String dirPath = System.getProperty("qrc.dir", "");
        File dir = new File(dirPath);
        Assume.assumeTrue("set -Dqrc.dir to a mirror directory to run this", dir.isDirectory());

        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".qrc"));
        assertFalse("no .qrc files in " + dirPath, files == null || files.length == 0);
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));

        List<String> failed = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        for (File file : files) {
            if (!file.getName().contains("_original") && !file.getName().contains("_dynamic")) {
                report.append(String.format("%-34s skipped (variant not used)\n", file.getName()));
                continue;
            }
            try {
                String hex = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
                String qrc = QrcLyricCodec.decryptToTimedLyric(hex);
                LrcTimeline timeline = LrcTimeline.parse("", "", qrc);
                assertTrue(file.getName() + " parsed empty", !timeline.isEmpty());
                report.append(String.format("%-34s lines=%-4d %s\n",
                        file.getName(), timeline.lineCount(), firstLineSummary(timeline)));
            } catch (Throwable error) {
                failed.add(file.getName() + ": " + error);
                report.append(String.format("%-34s FAILED %s\n", file.getName(), error));
            }
        }
        System.out.println("=== mirrored QRC library (" + files.length + " files) ===");
        System.out.print(report);
        assertTrue("failures: " + failed, failed.isEmpty());
    }

    /** The mirrored file is only useful if its first timed line lands where WeSing starts singing. */
    private static String firstLineSummary(LrcTimeline timeline) {
        for (LrcTimeline.Line line : timeline.lines()) {
            String text = line.text == null ? "" : line.text.trim();
            if (text.isEmpty()) continue;
            return String.format("first=%dms words=%-3d \"%s\"", line.timeMs, line.words.size(),
                    text.length() > 12 ? text.substring(0, 12) + "…" : text);
        }
        return "first=<none>";
    }
}
