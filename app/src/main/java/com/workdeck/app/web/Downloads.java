package com.workdeck.app.web;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.widget.Toast;

import com.workdeck.app.R;
import com.workdeck.app.notify.Notifier;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** File downloads: http(s) via DownloadManager, blob:/data: streamed from the page. */
public final class Downloads {
    private static final String TAG = "WorkDeck/Downloads";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Map<String, Pending> PENDING = new HashMap<>();

    private Downloads() {}

    private static final class Pending {
        final File file;
        final String name;
        final String mime;
        final OutputStream out;

        Pending(File file, String name, String mime, OutputStream out) {
            this.file = file;
            this.name = name;
            this.mime = mime;
            this.out = out;
        }
    }

    public static void enqueueHttp(Context c, String url, String userAgent, String contentDisposition,
            String mime, CookieManager cookies) {
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String cookie = cookies.getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            if (userAgent != null) r.addRequestHeader("User-Agent", userAgent);
            r.setTitle(name);
            r.setMimeType(mime);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= 29 || c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            } else {
                // Android 8/9 without storage permission: app's own download folder (still listed
                // in the system Downloads app and openable from the notification).
                r.setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, name);
            }
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(r);
            Toast.makeText(c, c.getString(R.string.download_started, name), Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "download failed", t);
            Toast.makeText(c, R.string.download_failed, Toast.LENGTH_LONG).show();
        }
    }

    // ---- streamed (blob:/data:) downloads, called from the JS bridge thread ----

    static String start(Context c, String name, String mime) {
        try {
            if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
            if (name == null || name.trim().isEmpty()) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                name = "download-" + System.currentTimeMillis() + (ext == null ? "" : "." + ext);
            }
            name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
            File dir = new File(c.getCacheDir(), "downloads");
            if (!dir.isDirectory() && !dir.mkdirs()) return "";
            String id = "d" + SEQ.incrementAndGet();
            File f = new File(dir, id + ".part");
            Pending p = new Pending(f, name, mime, new FileOutputStream(f));
            synchronized (PENDING) {
                PENDING.put(id, p);
            }
            return id;
        } catch (IOException e) {
            Log.w(TAG, "start failed", e);
            return "";
        }
    }

    static void chunk(String id, String base64) {
        Pending p;
        synchronized (PENDING) {
            p = PENDING.get(id);
        }
        if (p == null) return;
        try {
            p.out.write(Base64.decode(base64, Base64.DEFAULT));
        } catch (Throwable t) {
            Log.w(TAG, "chunk failed", t);
        }
    }

    static void finish(Context c, String id, String status) {
        Pending p;
        synchronized (PENDING) {
            p = PENDING.remove(id);
        }
        if (p == null) return;
        try {
            p.out.close();
        } catch (IOException ignored) {
        }
        if (!"ok".equals(status)) {
            //noinspection ResultOfMethodCallIgnored
            p.file.delete();
            Notifier.toast(c, c.getString(R.string.download_failed));
            return;
        }
        try {
            Uri uri = publish(c, p);
            Notifier.downloadComplete(c, p.name, p.mime, uri);
        } catch (Throwable t) {
            Log.w(TAG, "publish failed", t);
            Notifier.toast(c, c.getString(R.string.download_failed));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            p.file.delete();
        }
    }

    private static Uri publish(Context c, Pending p) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, p.name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, p.mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("insert failed");
            OutputStream out = cr.openOutputStream(uri);
            if (out == null) throw new IOException("open failed");
            copy(p.file, out);
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return uri;
        }
        if (c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw new IOException("storage permission missing");
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dest = new File(dir, p.name);
        copy(p.file, new FileOutputStream(dest));
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        //noinspection deprecation
        dm.addCompletedDownload(p.name, p.name, true, p.mime, dest.getAbsolutePath(), dest.length(), true);
        return null;
    }

    private static void copy(File src, OutputStream out) throws IOException {
        InputStream in = new FileInputStream(src);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }
}
