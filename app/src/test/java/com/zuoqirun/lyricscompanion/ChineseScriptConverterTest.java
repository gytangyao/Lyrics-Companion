package com.zuoqirun.lyricscompanion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class ChineseScriptConverterTest {
    @Test public void convertsCommonTraditionalMetadataCharacters() {
        assertEquals("说好不哭", ChineseScriptConverter.toSimplified("說好不哭"));
        assertEquals("周杰伦", ChineseScriptConverter.toSimplified("周杰倫"));
        assertEquals("音乐后台", ChineseScriptConverter.toSimplified("音樂後臺"));
    }

    @Test public void leavesSimplifiedMetadataUntouched() {
        String simplified = "说好不哭 周杰伦";
        assertSame(simplified, ChineseScriptConverter.toSimplified(simplified));
    }
}
