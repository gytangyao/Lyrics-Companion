package wemew.lrc.sync;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * App-wide logger: every entry goes to logcat <em>and</em> a rolling local file so it can be
 * pulled via adb now and uploaded to the cloud later. Files live under
 * {@code Android/data/wemew.lrc.sync/files/logs/applog-yyyyMMdd.txt} (external-first, internal
 * fallback) with one rotation per day and a 7-day retention window.
 */
public final class AppLog {
    private static final Object LOCK = new Object();
    private static final String DIRECTORY = "logs";
    private static final String PREFIX = "applog-";
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int ROTATED_DAYS = 7;
    private static final int MAX_ENTRY_CHARS = 4_000;

    private static volatile Context appContext;

    private AppLog() {}

    /** Call once from {@link WemewApp#onCreate}; keeps the application context for the process. */
    static void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    public static void i(String tag, String message) {
        write(Log.INFO, tag, message, null);
    }

    public static void w(String tag, String message) {
        write(Log.WARN, tag, message, null);
    }

    public static void e(String tag, String message, Throwable error) {
        write(Log.ERROR, tag, message, error);
    }

    private static void write(int priority, String tag, String message, Throwable error) {
        String text = message == null ? "" : message;
        if (error != null) {
            text = text + " :: " + Log.getStackTraceString(error);
        }
        // logcat always sees the entry, even when file storage is unavailable.
        Log.println(priority, tag, text);
        Context context = appContext;
        if (context == null) return;
        synchronized (LOCK) {
            try {
                File directory = resolveDirectory(context);
                if (directory == null) return;
                pruneOldFiles(directory);
                String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
                File target = new File(directory, PREFIX + day + ".txt");
                if (target.length() > MAX_FILE_BYTES) {
                    File rotated = new File(directory, PREFIX + day + ".old.txt");
                    if (rotated.exists() && !rotated.delete()) return;
                    if (!target.renameTo(rotated)) return;
                }
                String timestamp = new SimpleDateFormat(
                        "HH:mm:ss.SSS", Locale.US).format(new Date());
                String level = priority >= Log.ERROR ? "E" : priority >= Log.WARN ? "W" : "I";
                String entry = timestamp + " " + level + "/" + clean(tag, 48)
                        + ": " + clean(text, MAX_ENTRY_CHARS) + "\n";
                try (FileOutputStream output = new FileOutputStream(target, true)) {
                    output.write(entry.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
                // Logging must never affect playback or overlay rendering.
            }
        }
    }

    /** External-first so files are adb-pullable and later cloud-uploadable; internal as fallback. */
    private static File resolveDirectory(Context context) {
        File external = context.getExternalFilesDir(null);
        File base = external != null ? external : context.getFilesDir();
        File directory = new File(base, DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) return null;
        return directory;
    }

    private static void pruneOldFiles(File directory) {
        long horizon = System.currentTimeMillis() - ROTATED_DAYS * 24L * 60L * 60L * 1000L;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < horizon) file.delete();
        }
    }

    private static String clean(String value, int maximum) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return text.length() <= maximum ? text : text.substring(0, maximum);
    }
}
