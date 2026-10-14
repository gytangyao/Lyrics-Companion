package wemew.lrc.sync;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lyric source for 全民K歌. WeSing exposes neither a usable MediaSession nor track
 * notifications, so this reader takes the lyrics straight off the singing screen — they are
 * by definition the exact words being sung, which no catalog match can beat. On every content
 * change it walks the karaoke window: the badge row yields the song title and the per-second
 * position clock, and each newly visible lyric line is stamped with the clock value at the
 * moment it appears, building a line timeline live. The timeline rides the existing
 * sessionTimeline plumbing (wesing source), so the display protocol is untouched: track
 * frames carry the lines, state frames carry the clock.
 *
 * <p>Strategy: while WeSing is in the foreground this reader feeds the store; the moment it
 * goes to the background the events stop and the channel arbitration lets the normal
 * MediaSession players take over.</p>
 */
public final class WeSingLyricReader extends AccessibilityService {
    private static final long WALK_INTERVAL_MS = 700L;
    /** A visible line shorter/longer than this is not a lyric. */
    private static final int LINE_MIN_CHARS = 4;
    private static final int LINE_MAX_CHARS = 40;

    private long lastWalkAtMs;
    private String lastTitle = "";
    private long lastPositionMs = -1L;
    private long lastDurationMs = -1L;
    private String songKey = "";
    private String lastArtist = "";
    /** Lyric lines in start order: text → clock value when the line reached the ladder top. */
    private final LinkedHashMap<String, Long> lineStarts = new LinkedHashMap<>();
    /** The current ladder-top line; a change means the previous line ended and this started. */

    static boolean enabled(Context context) {
        String raw = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(raw)) return false;
        ComponentName target = new ComponentName(context, WeSingLyricReader.class);
        for (String entry : raw.split(":")) {
            ComponentName name = ComponentName.unflattenFromString(entry);
            if (target.equals(name)) return true;
        }
        return false;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !"com.tencent.karaoke".contentEquals(pkg)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastWalkAtMs < WALK_INTERVAL_MS) return;
        lastWalkAtMs = now;
        AccessibilityNodeInfo root = usableRoot(event);
        if (root == null) return;
        try {
            handle(root);
        } catch (Throwable error) {
            android.util.Log.w("WeSing", "walk failed", error);
        } finally {
            root.recycle();
        }
    }

    @Override public void onInterrupt() { }

    // ---- collection ----

    private void handle(AccessibilityNodeInfo root) {
        // Ordered full-tree texts feed the title and clock extraction.
        List<String> ordered = new ArrayList<>(64);
        walkOrdered(root, ordered, 0);

        Clock clock = clockFrom(ordered);
        // Gate on the singing screen: only it carries a running m:ss clock AND a lyric
        // ladder. Settings pages / song lists have neither; 原唱/伴唱 badges can toggle.
        if (clock.positionMs < 0L) return;
        List<Row> band = new ArrayList<>(24);
        walkLadder(root, band, 0);
        List<Row> ladder = extractLadder(band);
        if (ladder.isEmpty()) return;

        String title = titleFrom(ordered);
        boolean newSong = !TextUtils.equals(title, lastTitle)
                || (lastPositionMs >= 0L && clock.positionMs < lastPositionMs - 3_000L);
        if (newSong) {
            lineStarts.clear();
            lastArtist = "";
            songKey = title + "/" + clock.durationMs;
            lastDurationMs = -1L;
            if (!TextUtils.isEmpty(lastTitle) && !TextUtils.equals(title, lastTitle)) {
                DiagnosticLog.record(this, "WeSing", "song changed: " + title);
            }
        }
        boolean playing = clock.positionMs != lastPositionMs;
        lastPositionMs = clock.positionMs;
        lastDurationMs = clock.durationMs;
        lastTitle = title;

        for (Row row : ladder) {
            if (!lineStarts.containsKey(row.text)) {
                lineStarts.put(row.text, -1L);
            }
        }

        // Extract artist from the tree (verified pattern: "歌手名/演唱者" after the duration,
        // or plain text near the title). The catalog match needs title + artist together.
        String artist = artistFrom(ordered, title);
        lastArtist = artist;

        MusicPlaybackData data = new MusicPlaybackData(
                "", title == null ? "" : title, artist == null ? "" : artist,
                null, "", "",
                lastDurationMs > 0L ? lastDurationMs : -1L,
                true, playing ? MusicPlaybackData.STATE_PLAYING : MusicPlaybackData.STATE_PAUSED,
                clock.positionMs, SystemClock.elapsedRealtime(), 1f,
                false, "", LrcTimeline.EMPTY);
        MusicStateStore.update(this, "wesing", "全民K歌", "com.tencent.karaoke", data);
    }

    /**
     * The artist sits in the tree near the title/duration (verified: "心落凡尘/刘芝怡",
     * "蒙小哥" as standalone text). Look for a slash-separated pair or a short text after
     * the duration clock that isn't chrome.
     */
    private String artistFrom(List<String> ordered, String title) {
        if (!TextUtils.isEmpty(lastArtist)) return lastArtist;
        for (int i = 0; i < ordered.size(); i++) {
            String text = ordered.get(i).trim();
            // Slash pair "词曲/演唱" pattern
            if (text.matches(".{1,20}/.{1,20}") && !text.equals(title)) {
                return text.substring(text.indexOf('/') + 1);
            }
        }
        // Short standalone text right after the duration that isn't chrome or the title
        for (int i = 0; i < ordered.size() - 1; i++) {
            if (ordered.get(i).trim().matches("\\d{1,2}:\\d{2}")) {
                for (int j = i + 1; j < Math.min(i + 4, ordered.size()); j++) {
                    String text = ordered.get(j).trim();
                    if (text.isEmpty() || WeSingChrome.isChrome(text)) continue;
                    if (text.matches("[+\\d\\s.%/·]+")) continue;
                    if (text.equals(title) || text.contains(title)) continue;
                    if (text.length() <= 12) return text;
                }
            }
        }
        return "";
    }

    /** A candidate ladder row: a text node with its screen geometry. */
    static final class Row {
        final String text;
        final int top;
        final int height;

        Row(String text, int top, int height) {
            this.text = text;
            this.top = top;
            this.height = height;
        }
    }

    /** The lyric band as fractions of screen height; no absolute pixels anywhere. */
    private boolean inLyricBand(int top, int height) {
        int screen = getResources().getDisplayMetrics().heightPixels;
        return height > 0 && top >= screen * 22 / 100 && top <= screen * 88 / 100;
    }

    /**
     * Resolution-independent ladder extraction: rows that share the median height (within a
     * ratio band), then the longest run of consecutive rows whose vertical gaps stay close to
     * the median gap. The lyric ladder is a uniform grid on every device — a phone at 71/101 px
     * and a tablet at 142/202 px pass the same ratio tests.
     */
    static List<Row> extractLadder(List<Row> bandRows) {
        if (bandRows.size() < 2) return new ArrayList<>(bandRows);
        List<Row> byHeight = new ArrayList<>(bandRows);
        java.util.Collections.sort(byHeight, (a, b) -> Integer.compare(a.height, b.height));
        int medianHeight = byHeight.get(byHeight.size() / 2).height;
        List<Row> sized = new ArrayList<>();
        for (Row row : bandRows) {
            if (row.height * 10 >= medianHeight * 6 && row.height * 10 <= medianHeight * 18) {
                sized.add(row);
            }
        }
        if (sized.size() < 2) return sized;
        List<Row> byTop = new ArrayList<>(sized);
        java.util.Collections.sort(byTop, (a, b) -> Integer.compare(a.top, b.top));
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < byTop.size(); i++) {
            int gap = byTop.get(i).top - byTop.get(i - 1).top;
            if (gap > 0) gaps.add(gap);
        }
        if (gaps.isEmpty()) return byTop;
        java.util.Collections.sort(gaps);
        int medianGap = gaps.get(gaps.size() / 2);
        int bestStart = 0;
        int bestLength = 1;
        int runStart = 0;
        for (int i = 1; i < byTop.size(); i++) {
            int gap = byTop.get(i).top - byTop.get(i - 1).top;
            boolean regular = gap * 10 >= medianGap * 7 && gap * 10 <= medianGap * 14;
            if (!regular) runStart = i;
            if (i - runStart + 1 > bestLength) {
                bestLength = i - runStart + 1;
                bestStart = runStart;
            }
        }
        return new ArrayList<>(byTop.subList(bestStart, bestStart + bestLength));
    }

    private void walkLadder(AccessibilityNodeInfo node, List<Row> band, int depth) {
        if (node == null || depth > 25 || band.size() > 40) return;
        CharSequence text = node.getText();
        if (text != null) {
            String value = text.toString().trim();
            if (value.length() >= 2 && value.length() <= 40
                    && !value.matches("\\d{1,2}:\\d{2}") && !value.matches("[+\\d\\s.%/·]+")) {
                android.graphics.Rect bounds = new android.graphics.Rect();
                node.getBoundsInScreen(bounds);
                if (inLyricBand(bounds.top, bounds.height())) {
                    band.add(new Row(value, bounds.top, bounds.height()));
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walkLadder(node.getChild(i), band, depth + 1);
        }
    }



    /** Lyric-candidate filter: Chinese-ish sentence, not chrome, not the title, not a clock. */
    static boolean isLyricLine(String raw, String title) {
        String text = raw == null ? "" : raw.trim();
        if (text.length() < LINE_MIN_CHARS || text.length() > LINE_MAX_CHARS) return false;
        if (text.matches("\\d{1,2}:\\d{2}.*") || text.matches("[+\\d\\s.%/·]+")) return false;
        if (WeSingChrome.isChrome(text)) return false;
        if (title != null && !title.isEmpty()
                && (text.contains(title) || title.contains(text))) return false;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
        }
        return cjk >= 2;
    }

    /**
     * The title sits right before the first clock in tree order (verified layout:
     * "…原唱|等红灯|0:25…" and "…完成|潮湿的心|·|2:14…"), so scan backwards from the first
     * m:ss text for the nearest non-chrome, non-number text.
     */
    private String titleFrom(List<String> ordered) {
        int clockIndex = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).trim().matches("\\d{1,2}:\\d{2}")) {
                clockIndex = i;
                break;
            }
        }
        if (clockIndex > 0) {
            for (int i = clockIndex - 1; i >= 0 && i >= clockIndex - 4; i--) {
                String text = ordered.get(i).trim();
                if (text.isEmpty() || WeSingChrome.isChrome(text)) continue;
                if (text.matches("[+\\d\\s.%/·]+")) continue;
                return text;
            }
        }
        return lastTitle;
    }

    /** Position = the changing m:ss clock; duration = the constant one. */
    private Clock clockFrom(List<String> ordered) {
        List<Long> times = new ArrayList<>(4);
        for (String value : ordered) {
            String text = value == null ? "" : value.trim();
            if (text.matches("\\d{1,2}:\\d{2}")) {
                String[] parts = text.split(":");
                times.add((Long.parseLong(parts[0]) * 60L + Long.parseLong(parts[1])) * 1000L);
            }
        }
        Clock clock = new Clock();
        if (times.isEmpty()) return clock;
        long a = times.get(0);
        long b = times.size() > 1 ? times.get(1) : -1L;
        if (lastPositionMs >= 0L) {
            boolean aMoves = Math.abs(a - lastPositionMs) <= 2_500L;
            boolean bMoves = b >= 0L && Math.abs(b - lastPositionMs) <= 2_500L;
            if (aMoves && !bMoves) {
                clock.positionMs = a;
                clock.durationMs = b;
                return clock;
            }
            if (bMoves && !aMoves) {
                clock.positionMs = b;
                clock.durationMs = a;
                return clock;
            }
        }
        if (b < 0L) {
            clock.positionMs = a;
            clock.durationMs = lastDurationMs;
            return clock;
        }
        clock.positionMs = Math.min(a, b);
        clock.durationMs = Math.max(a, b);
        return clock;
    }

    private static final class Clock {
        long positionMs = -1L;
        long durationMs = -1L;
    }

    /** The most recent stamp in insertion order (the current line's anchor). */
    private long lastAnchorMs() {
        long last = 0L;
        for (Long value : lineStarts.values()) last = value;
        return last;
    }

    /** Chrome texts observed on the singing screen across songs. */
    private static final class WeSingChrome {
        private static final String[] CHROME = {
                "调音", "练唱", "选片段", "录视频", "完成", "和友友一起大合唱", "乐团和声", "换主题",
                "前往歌房演唱", "发起合唱", "升降调", "高品质", "开始大合唱", "重录", "AI演唱",
                "开始演唱", "合唱", "歌房唱", "原唱", "HQ", "·", "/", "原唱伴唱", "已合拍",
                "轻唱热身", "录屏", "跳过尾奏", "立即演唱", "标准音质", "超级会员上线,开通解锁高等级权益",
                "首句助唱", "尾句助唱", "智能修音", "抢唱", "接唱", "已开嗓", "热麦"};

        static boolean isChrome(String text) {
            String clean = text.trim();
            for (String item : CHROME) {
                if (clean.equals(item) || clean.startsWith(item + "·")
                        || clean.endsWith("·" + item) || clean.contains("·" + item + "·")) {
                    return true;
                }
                if ((item.equals("kcal") || item.equals("会员")) && clean.contains(item)) {
                    return true;
                }
            }
            return clean.contains("kcal") || clean.contains("会员");
        }
    }

    // ---- tree access ----

    /**
     * The recording screen sometimes yields no active-window root, so try the window list for
     * the karaoke window and finally climb from the event source — any of the three works.
     */
    private AccessibilityNodeInfo usableRoot(AccessibilityEvent event) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && "com.tencent.karaoke".contentEquals(
                    root.getPackageName() == null ? "" : root.getPackageName())) {
                return root;
            }
            if (root != null) root.recycle();
        } catch (Throwable ignored) { }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                for (AccessibilityWindowInfo window : getWindows()) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    CharSequence pkg = root.getPackageName();
                    if (pkg != null && "com.tencent.karaoke".contentEquals(pkg)) return root;
                }
            }
        } catch (Throwable ignored) { }
        try {
            AccessibilityNodeInfo source = event.getSource();
            while (source != null) {
                AccessibilityNodeInfo parent = source.getParent();
                if (parent == null) return source;
                source.recycle();
                source = parent;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private void walkOrdered(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 25 || out.size() > 400) return;
        CharSequence text = node.getText();
        if (text != null) {
            String value = text.toString().trim();
            if (!value.isEmpty() && value.length() <= 80 && !out.contains(value)) {
                out.add(value);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walkOrdered(node.getChild(i), out, depth + 1);
        }
    }

    /** Forensic dump with bounds so lyric geometry (current line = tallest) can be derived. */

    // ---- UI helpers ----

    static String lastIdentity() {
        WeSingLyricReader reader = instance;
        return reader == null ? "" : reader.lastTitle;
    }

    static boolean reading() {
        WeSingLyricReader reader = instance;
        return reader != null && SystemClock.elapsedRealtime() - reader.lastWalkAtMs < 10_000L;
    }

    static int collectedLines() {
        WeSingLyricReader reader = instance;
        return reader == null ? 0 : reader.lineStarts.size();
    }

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
}
