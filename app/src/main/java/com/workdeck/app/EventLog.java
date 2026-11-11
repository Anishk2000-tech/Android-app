package com.workdeck.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;
import android.webkit.WebView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A short diary the app keeps on the phone about its sign-in: sign-outs it refused or
 * carried out, sign-in pages that appeared, pages that failed to load and what it did
 * about them. No message content, no passwords or tokens - only times, page addresses
 * without their parameters, and what the app did. It never leaves the phone unless the
 * user shares it (to find out why something happened on their phone).
 */
public final class EventLog {
    private EventLog() {}

    private static final String TAG = "WorkDeck/Events";
    private static final String FILE = "events.log";
    private static final int MAX_BYTES = 96 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** Adds a line (with the time). Safe from any thread. */
    public static void add(Context c, final String what) {
        Log.i(TAG, what);
        if (c == null) return;
        final Context app = c.getApplicationContext();
        final String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + what + "\n";
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File f = new File(app.getFilesDir(), FILE);
                    if (f.length() > MAX_BYTES) keepNewestHalf(f);
                    FileOutputStream out = new FileOutputStream(f, true);
                    try {
                        out.write(line.getBytes(StandardCharsets.UTF_8));
                    } finally {
                        out.close();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "could not write the event log", t);
                }
            }
        });
    }

    private static void keepNewestHalf(File f) throws java.io.IOException {
        byte[] all = readAll(f);
        int from = all.length / 2;
        while (from < all.length && all[from] != '\n') from++;
        FileOutputStream out = new FileOutputStream(f, false);
        try {
            if (from + 1 < all.length) out.write(all, from + 1, all.length - from - 1);
        } finally {
            out.close();
        }
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** The whole diary with a heading (app, phone, browser engine), for sharing. */
    public static String text(Context c) {
        final Context app = c.getApplicationContext();
        StringBuilder sb = new StringBuilder();
        String version = "?";
        try {
            version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        String engine = "?";
        try {
            PackageInfo wv = Build.VERSION.SDK_INT >= 26 ? WebView.getCurrentWebViewPackage() : null;
            if (wv != null) engine = wv.packageName + " " + wv.versionName;
        } catch (Throwable ignored) {
        }
        sb.append(app.getString(R.string.app_name)).append(' ').append(!Edition.solo(app) ? "(WorkDeck)"
                        : Edition.site(app) == Site.TEAMS ? "Teams" : "Outlook").append(' ').append(version)
                .append(" (").append(app.getPackageName()).append(")\n")
                .append("Android ").append(Build.VERSION.RELEASE).append(", ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(", engine ").append(engine).append("\n")
                .append("Times are the phone's local time.\n\n");
        try {
            // Wait for lines still being written.
            IO.submit(new Runnable() {
                @Override
                public void run() {
                }
            }).get(2, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
        try {
            File f = new File(app.getFilesDir(), FILE);
            if (f.exists()) sb.append(new String(readAll(f), StandardCharsets.UTF_8));
            else sb.append("(nothing recorded yet)\n");
        } catch (Throwable t) {
            sb.append("(could not read the log: ").append(t).append(")\n");
        }
        return sb.toString();
    }
}
