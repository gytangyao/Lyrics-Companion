package com.zuoqirun.lyricscompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/** 黑名单 / 白名单两个方向的判定（issue #43、#28）。 */
public class AppRuleDecisionTest {
    private static final Set<String> LAUNCHER_ONLY = setOf("com.android.launcher3");
    private static final Set<String> BOTH = setOf("com.android.launcher3", "com.byd.mediacenter");

    @Test public void blacklistHidesOnlyWhenTheListedAppIsInFront() {
        assertTrue(AppRuleDecision.hides(true, false, true, BOTH, "com.byd.mediacenter"));
        assertFalse(AppRuleDecision.hides(true, false, true, BOTH, "com.tencent.qqmusic"));
        // 识别不到前台应用时不隐藏：黑名单保持改动前的行为。
        assertFalse(AppRuleDecision.hides(true, false, true, BOTH, ""));
        assertFalse(AppRuleDecision.hides(true, false, true, BOTH, null));
    }

    @Test public void whitelistHidesEverywhereExceptTheListedApps() {
        assertFalse(AppRuleDecision.hides(true, true, true, LAUNCHER_ONLY, "com.android.launcher3"));
        assertTrue(AppRuleDecision.hides(true, true, true, LAUNCHER_ONLY, "com.tencent.qqmusic"));
        // 车机桌面常常不发前台事件：识别不到 ⇒ 按白名单语义隐藏（这是 #28 的关键）。
        assertTrue(AppRuleDecision.hides(true, true, true, LAUNCHER_ONLY, ""));
        assertTrue(AppRuleDecision.hides(true, true, true, LAUNCHER_ONLY, null));
    }

    /** 没授权使用情况访问时永远读不到前台包名，白名单会把歌词一律藏掉，所以按不隐藏处理。 */
    @Test public void whitelistStandsDownWhenTheForegroundCannotBeReadAtAll() {
        assertFalse(AppRuleDecision.hides(true, true, false, LAUNCHER_ONLY, ""));
        assertFalse(AppRuleDecision.hides(true, true, false, LAUNCHER_ONLY, null));
        // 能读到前台应用时调用方会把 whitelistUsable 置为真，白名单照常判断。
        assertFalse(AppRuleDecision.hides(true, true, true, LAUNCHER_ONLY, "com.android.launcher3"));
        assertTrue(AppRuleDecision.hides(true, true, true, LAUNCHER_ONLY, "com.tencent.qqmusic"));
    }

    @Test public void aDisabledRuleNeverHides() {
        assertFalse(AppRuleDecision.hides(false, false, true, BOTH, "com.byd.mediacenter"));
        assertFalse(AppRuleDecision.hides(false, true, true, BOTH, "com.tencent.qqmusic"));
        assertFalse(AppRuleDecision.hides(false, true, true, Collections.emptySet(), ""));
    }

    @Test public void anEmptyListStillMeansSomethingInWhiteListMode() {
        // 白名单是空名单＝哪里都不显示；黑名单是空名单＝哪里都不隐藏。
        assertTrue(AppRuleDecision.hides(true, true, true, Collections.emptySet(), "com.tencent.qqmusic"));
        assertTrue(AppRuleDecision.hides(true, true, true, Collections.emptySet(), ""));
        assertFalse(AppRuleDecision.hides(true, false, true, Collections.emptySet(), "com.tencent.qqmusic"));
    }

    @Test public void surroundingWhitespaceInTheForegroundPackageIsIgnored() {
        assertTrue(AppRuleDecision.hides(true, false, true, BOTH, " com.byd.mediacenter "));
    }

    private static Set<String> setOf(String... values) {
        Set<String> set = new HashSet<>();
        Collections.addAll(set, values);
        return set;
    }
}
