package wemew.lrc.sync;

import static org.junit.Assert.assertEquals;

public class KaraokeTitleRulesTest {

    @org.junit.Test public void stripsKaraokeSuffixes() {
        assertEquals("晴天", KaraokeTitleRules.clean("晴天（原唱：周杰伦）"));
        assertEquals("晴天", KaraokeTitleRules.clean("晴天(原唱:周杰伦)"));
        assertEquals("孤勇者", KaraokeTitleRules.clean("孤勇者（伴奏）"));
        assertEquals("海底", KaraokeTitleRules.clean("海底（纯伴奏）"));
        assertEquals("起风了", KaraokeTitleRules.clean("起风了（cover：小敏）"));
        assertEquals("稻香", KaraokeTitleRules.clean("稻香（翻唱：阿飞）"));
        assertEquals("听妈妈的话", KaraokeTitleRules.clean("听妈妈的话（DJ版）"));
    }

    @org.junit.Test public void keepsOrdinaryTitlesIntact() {
        assertEquals("七里香", KaraokeTitleRules.clean("七里香"));
        assertEquals("Cover Girl", KaraokeTitleRules.clean("Cover Girl"));
        // 伴奏 inside the actual title without parentheses is left alone.
        assertEquals("伴奏带", KaraokeTitleRules.clean("伴奏带"));
    }

    @org.junit.Test public void nullAndEmptyAreSafe() {
        assertEquals("", KaraokeTitleRules.clean(null));
        assertEquals("", KaraokeTitleRules.clean("  "));
    }
}
