package com.workdeck.app.web;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phone files the user wants to attach in Teams/Outlook. The page reads them through
 * the bridge in chunks (by an unguessable id) and hands them to the message box the
 * same way dragging a file onto it would.
 */
public final class Attachments {
    private static final String TAG = "WorkDeck/Attach";
    /** Raw bytes per bridge call (base64 makes it ~1 MB of text). */
    private static final int CHUNK = 768 * 1024;
    private static final long TTL_MS = 30L * 60 * 1000;
    private static final Map<String, Item> sItems = new HashMap<>();

    private Attachments() {}

    public static final class Item {
        public final String id;
        public final String name;
        public final String mime;
        public final long size;
        final Uri uri;
        final File file;
        final long createdAt = System.currentTimeMillis();
        InputStream in;
        long pos;

        Item(String id, String name, String mime, long size, Uri uri, File file) {
            this.id = id;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.uri = uri;
            this.file = file;
        }
    }

    private static String newId() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Registers a picked/shared content URI (name, type and size read from the provider). */
    public static synchronized Item add(Context c, Uri uri) {
        purgeOld();
        String name = null;
        long size = -1;
        ContentResolver cr = c.getContentResolver();
        try (Cursor cur = cr.query(uri, new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = cur.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = cur.getString(ni);
                if (si >= 0 && !cur.isNull(si)) size = cur.getLong(si);
            }
        } catch (Throwable t) {
            Log.w(TAG, "metadata unavailable for " + uri, t);
        }
        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment() == null ? "file" : uri.getLastPathSegment();
        }
        String mime = null;
        try {
            mime = cr.getType(uri);
        } catch (Throwable ignored) {
        }
        Item it = new Item(newId(), cleanName(name, mime), mimeFor(name, mime), size, uri, null);
        sItems.put(it.id, it);
        return it;
    }

    /** Registers a file WorkDeck copied into its cache (shared from another app). */
    public static synchronized Item add(File f, String name, String mime) {
        purgeOld();
        Item it = new Item(newId(), cleanName(name, mime), mimeFor(name, mime), f.length(), null, f);
        sItems.put(it.id, it);
        return it;
    }

    static String cleanName(String name, String mime) {
        String n = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (n.isEmpty()) n = "file";
        if (n.indexOf('.') < 0 && mime != null) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null) n = n + "." + ext;
        }
        return n;
    }

    static String mimeFor(String name, String mime) {
        if (mime != null && !mime.isEmpty() && !mime.equals("application/octet-stream")) return mime;
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    /** JSON list the page script reads: [{id, name, type, size}]. */
    public static String json(List<Item> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Item it : items) {
                arr.put(new JSONObject().put("id", it.id).put("name", it.name).put("type", it.mime).put("size", it.size));
            }
        } catch (Throwable ignored) {
        }
        return arr.toString();
    }

    /**
     * Next chunk of a file as base64, starting at {@code offset} (the page reads
     * sequentially); "" at the end, null if the id is unknown or reading failed.
     */
    static synchronized String read(Context c, String id, long offset) {
        Item it = sItems.get(id);
        if (it == null) return null;
        try {
            if (it.in == null || offset != it.pos) {
                closeQuietly(it);
                it.in = it.file != null ? new FileInputStream(it.file) : c.getContentResolver().openInputStream(it.uri);
                it.pos = 0;
                if (it.in == null) return null;
                long skip = offset;
                while (skip > 0) {
                    long n = it.in.skip(skip);
                    if (n <= 0) break;
                    skip -= n;
                    it.pos += n;
                }
            }
            byte[] buf = new byte[CHUNK];
            int total = 0;
            while (total < CHUNK) {
                int n = it.in.read(buf, total, CHUNK - total);
                if (n < 0) break;
                total += n;
            }
            it.pos += total;
            if (total == 0) {
                closeQuietly(it);
                return "";
            }
            return Base64.encodeToString(buf, 0, total, Base64.NO_WRAP);
        } catch (Throwable t) {
            Log.w(TAG, "read failed for " + it.name, t);
            closeQuietly(it);
            return null;
        }
    }

    private static void closeQuietly(Item it) {
        if (it.in != null) {
            try {
                it.in.close();
            } catch (IOException ignored) {
            }
            it.in = null;
        }
    }

    private static void purgeOld() {
        long now = System.currentTimeMillis();
        for (Item it : sItems.values().toArray(new Item[0])) {
            if (now - it.createdAt > TTL_MS) {
                closeQuietly(it);
                sItems.remove(it.id);
                if (it.file != null) //noinspection ResultOfMethodCallIgnored
                    it.file.delete();
            }
        }
    }

    // ------------------------------------------------------------------ files shared from other apps

    /** Copies a shared file into WorkDeck's cache (the sharing app's permission is temporary). */
    public static File copyToCache(Context c, Uri uri, String name) throws IOException {
        File dir = new File(c.getCacheDir(), "shared");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("no cache dir");
        File out = new File(dir, newId().substring(0, 12) + "_" + cleanName(name, null));
        try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("cannot open " + uri);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    /** Removes shared-file copies older than a day. */
    public static void cleanCache(Context c) {
        File dir = new File(c.getCacheDir(), "shared");
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (File f : files) {
            if (f.lastModified() < cutoff) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    /** Display name of a content URI (for the share screen). */
    public static String displayName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (Throwable ignored) {
        }
        return uri.getLastPathSegment() == null ? "file" : uri.getLastPathSegment();
    }
}
