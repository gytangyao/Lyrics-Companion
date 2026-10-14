package wemew.lrc.sync;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The ladder must survive any resolution/density — a phone grid (71/101 px) and a tablet grid
 * at double scale (142/202 px) pass the same ratio tests, and chrome inside the band is
 * dropped by the uniform-grid run filter.
 */
public class WeSingLadderTest {

    private static List<WeSingLyricReader.Row> band(String... texts) {
        List<WeSingLyricReader.Row> rows = new ArrayList<>();
        int top = 938;
        for (String text : texts) {
            rows.add(new WeSingLyricReader.Row(text, top, 71));
            top += 101;
        }
        return rows;
    }

    @Test public void phoneScaleGridIsKeptInOrder() {
        List<WeSingLyricReader.Row> ladder =
                WeSingLyricReader.extractLadder(band("茫茫人海遇见了你", "缘分让我们走到一起",
                        "你说那是我们的前世", "都把彼此放在了心里"));
        assertEquals(4, ladder.size());
        assertEquals("茫茫人海遇见了你", ladder.get(0).text);
        assertEquals(938, ladder.get(0).top);
    }

    @Test public void tabletDoubleScaleGridIsKept() {
        List<WeSingLyricReader.Row> rows = new ArrayList<>();
        int top = 1876;
        for (String text : new String[]{"a行歌词一", "b行歌词二", "c行歌词三", "d行歌词四", "e行歌词五"}) {
            rows.add(new WeSingLyricReader.Row(text, top, 142));
            top += 202;
        }
        List<WeSingLyricReader.Row> ladder = WeSingLyricReader.extractLadder(rows);
        assertEquals(5, ladder.size());
        assertEquals("a行歌词一", ladder.get(0).text);
    }

    @Test public void offScreenRowsAndChromeAreDropped() {
        List<WeSingLyricReader.Row> rows = band("第一行歌词", "第二行歌词", "第三行歌词");
        // Off-screen (folded) row with negative height never enters the band; a giant banner
        // inside the band is dropped by the median-height filter, an odd-height button by the
        // grid-run filter.
        rows.add(new WeSingLyricReader.Row("用推荐模板 有机会得更多曝光", 1100, 300));
        rows.add(new WeSingLyricReader.Row("按钮", 1297, 44));
        List<WeSingLyricReader.Row> ladder = WeSingLyricReader.extractLadder(rows);
        assertEquals(3, ladder.size());
        assertEquals("第三行歌词", ladder.get(2).text);
    }

    @Test public void unsortedInputIsSortedByTop() {
        List<WeSingLyricReader.Row> rows = new ArrayList<>();
        rows.add(new WeSingLyricReader.Row("第二行歌词", 1039, 71));
        rows.add(new WeSingLyricReader.Row("第一行歌词", 938, 71));
        rows.add(new WeSingLyricReader.Row("第三行歌词", 1140, 71));
        List<WeSingLyricReader.Row> ladder = WeSingLyricReader.extractLadder(rows);
        assertEquals("第一行歌词", ladder.get(0).text);
    }

    @Test public void singleRowPassesThrough() {
        List<WeSingLyricReader.Row> rows = new ArrayList<>();
        rows.add(new WeSingLyricReader.Row("唯一一行歌词", 1000, 71));
        assertEquals(1, WeSingLyricReader.extractLadder(rows).size());
    }
}
