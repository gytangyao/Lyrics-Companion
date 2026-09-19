package com.zuoqirun.lyricscompanion;

import android.content.Context;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

final class LyricCache {
    private static final long MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L;
    private final File directory;
    private final File previousDirectory;
    private static final Object IO_LOCK = new Object();
    private final String policy;
    private final int capacityLimitMb;

    LyricCache(Context context, String provider) {
        policy = AppPreferences.lyricCachePolicy(context);
        capacityLimitMb = AppPreferences.lyricCacheLimitMb(context);
        // Only the 30-day policy uses cache storage. Persistent policies use
        // app files storage so Android's cache cleaner cannot silently discard them.
        File root = "30d".equals(policy)
                ? context.getCacheDir() : context.getFilesDir();
        directory = new File(root, "lyrics_" + provider + "_v1");
        previousDirectory = new File("30d".equals(policy) ? context.getFilesDir()
                : context.getCacheDir(), "lyrics_" + provider + "_v1");
    }

    String read(String key) {
        synchronized (IO_LOCK) { return readLocked(key); }
    }

    private String readLocked(String key) {
        File file = file(key);
        boolean previous = !file.isFile() && !new File(file.getPath() + ".bak").isFile();
        if (previous) file = new File(previousDirectory, file.getName());
        AtomicFile atomic = new AtomicFile(file);
        // openRead restores an interrupted write from its backup.
        try (InputStream input = atomic.openRead()) {
            if (!file.isFile() || file.length() > 2_000_000L || isExpired(file)) {
                return null;
            }
            byte[] buffer = new byte[(int) Math.min(file.length(), 2_000_000L)];
            int offset = 0;
            int count;
            while (offset < buffer.length
                    && (count = input.read(buffer, offset, buffer.length - offset)) > 0) {
                offset += count;
            }
            String value = new String(buffer, 0, offset, StandardCharsets.UTF_8);
            if (previous) write(key, value);
            return value;
        } catch (Exception ignored) {
            return null;
        }
    }

    void write(String key, String value) {
        if (value == null || value.isEmpty()) return;
        synchronized (IO_LOCK) {
            AtomicFile atomic = new AtomicFile(file(key));
            FileOutputStream output = null;
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) return;
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 2_000_000) return;
                output = atomic.startWrite();
                output.write(bytes);
                atomic.finishWrite(output);
                output = null;
                if ("capacity".equals(policy)) {
                    trimToBytes((long) capacityLimitMb * 1024L * 1024L);
                } else if ("30d".equals(policy)) {
                    // 只按时间上限清理，容量由系统缓存目录负责。
                    trimToBytes(Long.MAX_VALUE);
                }
            } catch (Exception ignored) {
                if (output != null) atomic.failWrite(output);
            }
        }
    }

    private File file(String key) {
        String safe = key == null ? "unknown" : key.replaceAll("[^A-Za-z0-9_-]", "_");
        return new File(directory, safe + ".lrc");
    }

    private void trimToBytes(long maxBytes) {
        File[] files = directory.listFiles();
        if (files == null) return;
        // 先按时间上限清一遍：歌少、缓存总量到不了容量上限时，按容量淘汰永远触发不了，旧条目会
        // 一直命中（issue #20）。「永久保留」不受这条限制。
        for (File file : files) {
            if (file.isFile() && isExpired(file)) file.delete();
        }
        files = directory.listFiles();
        if (files == null) return;
        // Comparator.comparingLong is only available from API 24. Keep cache trimming
        // available on API 19+ car systems with an equivalent platform-safe comparator.
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File first, File second) {
                long firstModified = first.lastModified();
                long secondModified = second.lastModified();
                return firstModified < secondModified ? -1
                        : firstModified > secondModified ? 1 : 0;
            }
        });
        long total = 0L;
        for (File file : files) if (file.isFile()) total += file.length();
        for (File file : files) {
            if (total <= maxBytes) break;
            long length = file.length();
            if (file.isFile() && file.delete()) total -= length;
        }
    }

    /**
     * 「永久保留」以外的策略都带 30 天上限。默认的按容量淘汰以前只比容量，歌少时旧缓存永远
     * 不会过期，匹配错了也一直命中（issue #20）。
     */
    private boolean isExpired(File file) {
        return !"forever".equals(policy)
                && System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS;
    }

    /** 歌词与匹配结果缓存当前占用的字节数，供设置页显示（issue #20）。 */
    static long cachedBytes(Context context) {
        long total = 0L;
        for (File directory : cacheDirectories(context)) {
            File[] files = directory.listFiles();
            if (files == null) continue;
            for (File file : files) if (file.isFile()) total += file.length();
        }
        return total;
    }

    /** 歌词与匹配结果缓存的条目数（issue #20）。 */
    static int cachedEntries(Context context) {
        int count = 0;
        for (File directory : cacheDirectories(context)) {
            File[] files = directory.listFiles();
            if (files == null) continue;
            for (File file : files) if (file.isFile()) count++;
        }
        return count;
    }

    /**
     * 清除所有歌词缓存（含"匹配结果"缓存），返回删除的文件数（issue #20）。设置页的「清除歌词
     * 缓存」按钮用它；调用方负责提示用户下次播放会重新联网匹配。
     */
    static int clearAll(Context context) {
        int removed = 0;
        for (File directory : cacheDirectories(context)) {
            File[] files = directory.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (file.isFile() && file.delete()) removed++;
            }
        }
        return removed;
    }

    /** 缓存目录名都以 {@code lyrics_} 开头（含 MatchedLyricCache 的 {@code lyrics_matched_v1}）。 */
    private static java.util.List<File> cacheDirectories(Context context) {
        java.util.List<File> directories = new java.util.ArrayList<>();
        addCacheDirectories(context.getCacheDir(), directories);
        addCacheDirectories(context.getFilesDir(), directories);
        return directories;
    }

    private static void addCacheDirectories(File root, java.util.List<File> into) {
        if (root == null) return;
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("lyrics_")) into.add(child);
        }
    }
}
