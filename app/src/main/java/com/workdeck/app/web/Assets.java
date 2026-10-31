package com.workdeck.app.web;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class Assets {
    private Assets() {}

    static byte[] readBytes(Context c, String path) throws IOException {
        InputStream in = c.getAssets().open(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    static String readText(Context c, String path) {
        try {
            return new String(readBytes(c, path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing asset " + path, e);
        }
    }
}
