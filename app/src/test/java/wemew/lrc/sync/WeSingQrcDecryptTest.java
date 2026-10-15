package wemew.lrc.sync;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/**
 * Verifies that WeSing's locally cached QRC files decrypt with our existing pipeline
 * (triple-DES + zlib inflate) and produce a parseable enhanced timeline.
 */
public class WeSingQrcDecryptTest {

    @Test public void decryptWeSingCachedQrc() throws Exception {
        // Read the QRC hex pulled from the device.
        String hex = new String(Files.readAllBytes(Paths.get("test.qrc"))).trim();
        assertTrue("file not found or empty — pull test.qrc first", hex.length() > 100);
        String timeline = QrcLyricCodec.decryptToTimedLyric(hex);
        assertTrue(timeline.length() > 50);
        System.out.println("=== 解密成功，QRC 时间轴 ===");
        System.out.println(timeline.length() > 1200
                ? timeline.substring(0, 1200) + "\n... (" + timeline.length() + " chars total)"
                : timeline);
    }
}
