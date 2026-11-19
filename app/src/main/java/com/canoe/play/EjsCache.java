package com.canoe.play;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offline mode: the emulator engine (EmulatorJS) is downloaded file by file the first
 * time it's needed and kept on the device. After a system has been played once, it
 * starts from local storage with no internet connection.
 *
 * All files come from one pinned EmulatorJS version so cached and new files always match.
 */
public class EjsCache {

    private static final String CDN = "https://cdn.emulatorjs.org/";

    private final File dir;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private volatile String base;

    EjsCache(Context ctx) {
        dir = new File(ctx.getFilesDir(), "emulator");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** Returns the local copy of an emulator file, downloading it first if needed. Null if unavailable. */
    File get(String path) {
        if (path.isEmpty() || path.contains("..") || path.startsWith("/")) return null;
        File f = new File(dir, path);
        if (f.isFile() && f.length() > 0) return f;

        Object lock = locks.putIfAbsent(path, new Object());
        if (lock == null) lock = locks.get(path);
        synchronized (lock) {
            if (f.isFile() && f.length() > 0) return f;
            String b = base();
            if (b == null) return null;
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            File tmp = new File(f.getPath() + ".part");
            try {
                HttpURLConnection c = open(b + path);
                if (c.getResponseCode() != 200) { c.disconnect(); return null; }
                InputStream in = c.getInputStream();
                OutputStream out = new FileOutputStream(tmp);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                c.disconnect();
                if (!tmp.renameTo(f)) return null;
                return f;
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
        }
    }

    /** Works out (once) which EmulatorJS version to use and remembers it. */
    private synchronized String base() {
        if (base != null) return base;
        File saved = new File(dir, ".base");
        if (saved.isFile()) {
            try {
                String b = readAll(new FileInputStream(saved)).trim();
                if (!b.isEmpty()) return base = b;
            } catch (Exception ignored) {}
        }
        String chosen = null;
        try {
            HttpURLConnection c = open(CDN + "stable/data/version.json");
            if (c.getResponseCode() == 200) {
                String v = new JSONObject(readAll(c.getInputStream())).optString("version", "");
                c.disconnect();
                if (!v.isEmpty()) {
                    String pinned = CDN + v + "/data/";
                    HttpURLConnection t = open(pinned + "loader.js");
                    if (t.getResponseCode() == 200) chosen = pinned;
                    t.disconnect();
                }
            }
        } catch (Exception ignored) {}
        if (chosen == null) {
            // fall back to the "stable" channel; still cached so it works offline afterwards
            try {
                HttpURLConnection t = open(CDN + "stable/data/loader.js");
                if (t.getResponseCode() == 200) chosen = CDN + "stable/data/";
                t.disconnect();
            } catch (Exception ignored) {}
        }
        if (chosen == null) return null; // offline and nothing chosen yet
        try {
            OutputStream o = new FileOutputStream(saved);
            o.write(chosen.getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) {}
        return base = chosen;
    }

    /** Space used by downloaded emulator files, in MB. */
    long sizeMb() {
        return folderSize(dir) / (1024 * 1024);
    }

    private static long folderSize(File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += folderSize(k);
        return total;
    }

    private static HttpURLConnection open(String url) throws Exception {
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", "CanoePlay");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            return c;
        }
        throw new Exception("Too many redirects");
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }
}
