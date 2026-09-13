package com.zuoqirun.lyricscompanion;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Finds a sidecar .lrc without uploading local paths, song names or file contents. */
final class LocalLyricClient {
    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_DOCUMENTS = 2_000;
    private final Context context;

    LocalLyricClient(Context context) {
        this.context = context.getApplicationContext();
    }

    LrcTimeline load(String mediaUri, String title, String artist) {
        try {
            File sidecar = sidecarFile(mediaUri);
            if (sidecar == null && (title.startsWith("/") || title.startsWith("file://"))) {
                sidecar = sidecarFile(title);
            }
            if (sidecar != null && sidecar.isFile()) {
                LrcTimeline found = parse(new FileInputStream(sidecar));
                if (!found.isEmpty()) return found;
            }
        } catch (Throwable ignored) { }
        Set<String> candidates = candidateNames(mediaUri, title, artist);
        String path = AppPreferences.localLyricDirectoryPath(context);
        if (!path.isEmpty()) {
            try {
                LrcTimeline found = searchDirectory(new File(path), candidates);
                if (!found.isEmpty()) return found;
            } catch (Throwable ignored) { }
        }
        String tree = AppPreferences.localLyricDirectoryUri(context);
        if (tree.isEmpty() || Build.VERSION.SDK_INT < 21) return LrcTimeline.EMPTY;
        try {
            return searchTree(Uri.parse(tree), candidates);
        } catch (Throwable ignored) {
            return LrcTimeline.EMPTY;
        }
    }

    private File sidecarFile(String mediaUri) throws Exception {
        if (TextUtils.isEmpty(mediaUri)) return null;
        Uri uri = Uri.parse(mediaUri);
        String path = null;
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) path = uri.getPath();
        else if (uri.getScheme() == null || uri.getScheme().isEmpty()) path = mediaUri;
        if (path == null) return null;
        // Uri.getPath already decodes file URIs. Raw paths must preserve '+' and literal '%'.
        int dot = path.lastIndexOf('.');
        return new File((dot > path.lastIndexOf(File.separatorChar) ? path.substring(0, dot) : path)
                + ".lrc");
    }

    private Set<String> candidateNames(String mediaUri, String title, String artist) {
        Set<String> names = new LinkedHashSet<>();
        addCandidate(names, title);
        addCandidate(names, LocalTrackQueryRules.cleanFileTitle(title));
        addCandidate(names, artist + " - " + title);
        addCandidate(names, title + " - " + artist);
        try {
            Uri uri = Uri.parse(mediaUri);
            String displayName = queryName(uri);
            int dot = displayName.lastIndexOf('.');
            addCandidate(names, dot > 0 ? displayName.substring(0, dot) : displayName);
        } catch (Throwable ignored) { }
        return names;
    }

    private static void addCandidate(Set<String> names, String value) {
        String normalized = normalize(value);
        if (!normalized.isEmpty()) names.add(normalized + ".lrc");
    }

    @android.annotation.TargetApi(21)
    private LrcTimeline searchTree(Uri tree, Set<String> candidates) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ArrayDeque<String> directories = new ArrayDeque<>();
        directories.add(DocumentsContract.getTreeDocumentId(tree));
        int visited = 0;
        while (!directories.isEmpty() && visited < MAX_DOCUMENTS) {
            String parentId = directories.removeFirst();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
            try (Cursor cursor = resolver.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor == null) continue;
                while (cursor.moveToNext() && visited++ < MAX_DOCUMENTS) {
                    String id = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        directories.addLast(id);
                    } else if (candidates.contains(normalize(name))) {
                        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                        try (InputStream input = resolver.openInputStream(document)) {
                            LrcTimeline timeline = parse(input);
                            if (!timeline.isEmpty()) return timeline;
                        }
                    }
                }
            }
        }
        return LrcTimeline.EMPTY;
    }

    private String queryName(Uri uri) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return "";
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : "";
        } catch (Throwable ignored) { return ""; }
    }

    private LrcTimeline searchDirectory(File root, Set<String> candidates) throws Exception {
        if (root == null || !root.isDirectory()) return LrcTimeline.EMPTY;
        ArrayDeque<File> directories = new ArrayDeque<>();
        directories.add(root);
        int visited = 0;
        while (!directories.isEmpty() && visited < MAX_DOCUMENTS) {
            File directory = directories.removeFirst();
            File[] children = directory.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (visited++ >= MAX_DOCUMENTS) break;
                if (child.isDirectory()) {
                    directories.addLast(child);
                } else if (candidates.contains(normalize(child.getName()))) {
                    LrcTimeline timeline = parse(new FileInputStream(child));
                    if (!timeline.isEmpty()) return timeline;
                }
            }
        }
        return LrcTimeline.EMPTY;
    }

    private static LrcTimeline parse(InputStream input) throws Exception {
        if (input == null) return LrcTimeline.EMPTY;
        byte[] bytes;
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (count == 0) continue;
                if (output.size() + count > MAX_BYTES) return LrcTimeline.EMPTY;
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('\uFFFD') >= 0) text = new String(bytes, Charset.forName("GB18030"));
        return LrcTimeline.parse(text, "");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\\\/:*?\"<>|]", " ")
                .replaceAll("\\s+", " ");
        return normalized;
    }
}
