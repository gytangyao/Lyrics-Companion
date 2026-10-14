package wemew.lrc.sync;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lyric source for 全民K歌, verified on-device: the singing screen exposes the song title
 * (next to the 原唱 badge), a per-second position clock and the total duration through the
 * accessibility tree. Title + position is everything the catalog pipeline needs — the five
 * providers return a word-timed timeline, so the big screen gets full karaoke. WeSing never
 * publishes a usable MediaSession, which is why this reader exists.
 */
public final class WeSingLyricReader extends AccessibilityService {
    /** Verified singing-screen chrome that shares the badge row and the full tree. */
    private static final Set<String> NOISE = new HashSet<>(Arrays.asList(
            "调音", "练唱", "选片段", "录视频", "完成", "和友友一起大合唱", "乐团和声", "换主题",
            "前往歌房演唱", "发起合唱", "升降调", "高品质", "开始大合唱", "重录", "AI演唱",
            "开始演唱", "合唱", "歌房唱", "原唱", "HQ", "·", "/", "超级会员上线,开通解锁高等级权益"));

    private long lastWalkAtMs;
    private String lastTitle = "";
    private long lastPositionMs = -1L;
    private long lastDurationMs = -1L;

    static boolean enabled(Context context) {
        String raw = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(raw)) return false;
        ComponentName target = new ComponentName(context, WeSingLyricReader.class);
        for (String entry : raw.split(":")) {
            if (ComponentName.unflattenFromString(entry) != null
                    && target.equals(ComponentName.unflattenFromString(entry))) {
                return true;
            }
        }
        return false;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !"com.tencent.karaoke".contentEquals(pkg)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastWalkAtMs < 800L) return;
        lastWalkAtMs = now;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            handle(root);
        } finally {
            root.recycle();
        }
    }

    @Override public void onInterrupt() { }

    private void handle(AccessibilityNodeInfo root) {
        List<String> rowTexts = new ArrayList<>();
        findBadgeRow(root, 0, rowTexts);
        String title = null;
        if (!rowTexts.isEmpty()) {
            title = titleFromRow(rowTexts);
        }
        if (title == null) {
            // Fallback patterns from the full tree: "谁（廖俊涛）" / "推荐优质伴奏|谁(Live)—廖俊涛".
            Set<String> all = new HashSet<>();
            walk(root, all, 0);
            title = titleFromPatterns(all);
            if (rowTexts.isEmpty()) rowTexts.addAll(all);
        }
        if (title == null) return;

        long[] clock = clockFromTexts(rowTexts);
        long positionMs = clock[0];
        long durationMs = clock[1];
        if (positionMs < 0L) return;
        // Pause detection: the per-second clock stops advancing while paused.
        boolean playing = positionMs != lastPositionMs;
        if (positionMs < lastPositionMs - 3_000L) {
            // Rewound to a segment start or a new song: forget the old anchors.
            lastDurationMs = -1L;
        }
        lastPositionMs = positionMs;
        lastDurationMs = durationMs;
        lastTitle = title;

        MusicPlaybackData data = new MusicPlaybackData(
                "", title, "", null, "", "", durationMs > 0L ? durationMs : -1L,
                true, playing ? MusicPlaybackData.STATE_PLAYING : MusicPlaybackData.STATE_PAUSED,
                positionMs, SystemClock.elapsedRealtime(), 1f);
        MusicStateStore.update(this, "wesing", "全民K歌", "com.tencent.karaoke", data);
    }

    /** Smallest ancestor row around the 原唱 badge, level-capped like the verified probe. */
    private void findBadgeRow(AccessibilityNodeInfo node, int depth, List<String> out) {
        if (node == null || depth > 30 || !out.isEmpty()) return;
        CharSequence text = node.getText();
        if (text != null && text.toString().contains("原唱")) {
            AccessibilityNodeInfo parent = node.getParent();
            for (int level = 0; level < 3 && parent != null; level++) {
                Set<String> around = new HashSet<>();
                walk(parent, around, 0);
                if (around.size() >= 2) {
                    out.addAll(around);
                    return;
                }
                parent = parent.getParent();
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            findBadgeRow(node.getChild(i), depth + 1, out);
        }
    }

    /** The title is the badge-row text that is neither chrome, a clock, nor a score number. */
    private String titleFromRow(List<String> texts) {
        for (String value : texts) {
            String clean = value.trim();
            if (clean.isEmpty() || NOISE.contains(clean)) continue;
            if (clean.matches("\\d{1,2}:\\d{2}")) continue;
            if (clean.matches("\\+?\\d{1,4}")) continue;
            if (clean.startsWith("+") || clean.contains("kcal") || clean.contains("会员")) continue;
            // Combined form "谁（廖俊涛）" keeps the artist; plain form is just the title.
            return clean;
        }
        return null;
    }

    private String titleFromPatterns(Set<String> all) {
        for (String value : all) {
            java.util.regex.Matcher combined = java.util.regex.Pattern
                    .compile("^(.{1,25})（(.{1,25})）$").matcher(value.trim());
            if (combined.matches()) return combined.group(1);
            java.util.regex.Matcher recommendation = java.util.regex.Pattern
                    .compile("伴奏\\|(.{1,25}?)(?:\\(.*?))?—.{1,25}$").matcher(value.trim());
            if (recommendation.find()) return recommendation.group(1);
        }
        return null;
    }

    /**
     * @return {positionMs, durationMs}; the changing/incrementing clock is the position, the
     * constant one the duration, min wins on the first observation.
     */
    private long[] clockFromTexts(List<String> texts) {
        List<Long> times = new ArrayList<>();
        for (String value : texts) {
            String clean = value.trim();
            if (clean.matches("\\d{1,2}:\\d{2}")) {
                String[] parts = clean.split(":");
                times.add((Long.parseLong(parts[0]) * 60L + Long.parseLong(parts[1])) * 1000L);
            }
        }
        if (times.isEmpty()) return new long[]{-1L, -1L};
        if (times.size() == 1) {
            long only = times.get(0);
            if (lastPositionMs >= 0L && Math.abs(only - lastPositionMs) <= 2_500L) {
                return new long[]{only, lastDurationMs};
            }
            return new long[]{only, -1L};
        }
        long a = times.get(0);
        long b = times.get(1);
        if (lastPositionMs >= 0L) {
            boolean aMoves = Math.abs(a - lastPositionMs) <= 2_500L;
            boolean bMoves = Math.abs(b - lastPositionMs) <= 2_500L;
            if (aMoves && !bMoves) return new long[]{a, b};
            if (bMoves && !aMoves) return new long[]{b, a};
        }
        return a <= b ? new long[]{a, b} : new long[]{b, a};
    }

    private void walk(AccessibilityNodeInfo node, Set<String> out, int depth) {
        if (node == null || depth > 25) return;
        CharSequence text = node.getText();
        if (text != null) {
            String value = text.toString().trim();
            if (!value.isEmpty() && value.length() <= 80) out.add(value);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), out, depth + 1);
        }
    }

    // Accessibility services receive a Context only after onBind; expose last identity for UI.
    static String lastIdentity() {
        WeSingLyricReader reader = instance;
        return reader == null ? "" : reader.lastTitle;
    }

    @SuppressWarnings({"unused", "StaticFieldLeak"})
    private static volatile WeSingLyricReader instance;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        DiagnosticLog.record(this, "WeSing", "歌词读取服务已连接");
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        DiagnosticLog.record(this, "WeSing", "歌词读取服务已断开");
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    static boolean reading() {
        WeSingLyricReader reader = instance;
        return reader != null && !TextUtils.isEmpty(reader.lastTitle);
    }
}
