package com.canoe.play;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.security.MessageDigest;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds box art on the free libretro thumbnail server even when the ROM's file name
 * doesn't match exactly (e.g. "Donkey Kong Country (U) (V1.2) [!]").
 * The list of covers for each system is downloaded once and kept for two weeks.
 */
public class ArtFinder {

    static final String BASE = "https://thumbnails.libretro.com/";
    private static final Pattern HREF = Pattern.compile("href=\"([^\"/?]+\\.png)\"", Pattern.CASE_INSENSITIVE);
    private static final long MAX_AGE = 14L * 24 * 60 * 60 * 1000;

    private final File dir;
    private final File thumbs;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, List<String>> lists = new HashMap<>();
    // normalised name -> real cover names, built once per system for instant lookups
    private final Map<String, Map<String, List<String>>> indexes = new HashMap<>();
    private final Map<String, String> matches = new HashMap<>();

    ArtFinder(Context ctx) {
        dir = new File(ctx.getCacheDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // small copies of covers, kept so the library loads fast and works offline
        thumbs = new File(ctx.getFilesDir(), "covers");
        //noinspection ResultOfMethodCallIgnored
        thumbs.mkdirs();
    }

    /** Full image URL for this game, or null if there's no cover. */
    String find(String system, String stem) {
        String key = system + "|" + stem;
        synchronized (matches) {
            if (matches.containsKey(key)) return matches.get(key);
        }
        List<String> names = list(system);
        String name;
        if (names == null) {
            name = stem.replaceAll("[&*/:`<>?\\\\|\"]", "_"); // offline guess
        } else {
            name = best(index(system, names), names, stem);
        }
        String url = name == null ? null : BASE + enc(system) + "/Named_Boxarts/" + enc(name) + ".png";
        if (names != null) synchronized (matches) { matches.put(key, url); }
        return url;
    }

    private synchronized Map<String, List<String>> index(String system, List<String> names) {
        Map<String, List<String>> idx = indexes.get(system);
        if (idx != null) return idx;
        idx = new HashMap<>();
        for (String n : names) {
            String k = norm(n);
            List<String> l = idx.get(k);
            if (l == null) { l = new ArrayList<>(); idx.put(k, l); }
            l.add(n);
        }
        indexes.put(system, idx);
        return idx;
    }

    /**
     * A small JPEG of the cover (about 400 px wide) stored on the device, or null if
     * this game has no cover. Downloads and shrinks it the first time only.
     */
    File thumb(String system, String stem) {
        String key = sha1(system + "|" + stem);
        File jpg = new File(thumbs, key + ".jpg");
        File none = new File(thumbs, key + ".none");
        if (jpg.isFile()) return jpg;
        if (none.isFile() && System.currentTimeMillis() - none.lastModified() < MAX_AGE) return null;
        Object lock = locks.putIfAbsent(key, new Object());
        if (lock == null) lock = locks.get(key);
        synchronized (lock) {
            if (jpg.isFile()) return jpg;
            String url = find(system, stem);
            if (url == null) { touch(none); return null; }
            try {
                byte[] data = downloadBytes(url);
                if (data == null) { touch(none); return null; }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 400) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
                if (b == null) { touch(none); return null; }
                if (b.getWidth() > 420) {
                    Bitmap s = Bitmap.createScaledBitmap(b, 400, Math.max(1, b.getHeight() * 400 / b.getWidth()), true);
                    b.recycle();
                    b = s;
                }
                File tmp = new File(thumbs, key + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                b.compress(Bitmap.CompressFormat.JPEG, 85, out);
                out.close();
                b.recycle();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(jpg);
                return jpg.isFile() ? jpg : null;
            } catch (Exception e) {
                return null; // network hiccup: try again next time
            }
        }
    }

    private static byte[] downloadBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "CanoePlay");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        if (c.getResponseCode() != 200) { c.disconnect(); return null; }
        InputStream in = c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        c.disconnect();
        return b.toByteArray();
    }

    private static void touch(File f) {
        try { new FileOutputStream(f).close(); } catch (Exception ignored) {}
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format(Locale.US, "%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private synchronized List<String> list(String system) {
        if (lists.containsKey(system)) return lists.get(system);
        File cache = new File(dir, system.replaceAll("[^A-Za-z0-9]", "_") + ".txt");
        List<String> names = null;
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < MAX_AGE) {
            names = readCache(cache);
        }
        if (names == null || names.isEmpty()) {
            List<String> fresh = download(system);
            if (fresh != null && !fresh.isEmpty()) {
                names = fresh;
                writeCache(cache, fresh);
            } else if (cache.isFile()) {
                names = readCache(cache); // old list is better than nothing
            }
        }
        if (names != null && !names.isEmpty()) lists.put(system, names);
        return names;
    }

    private static List<String> download(String system) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + enc(system) + "/Named_Boxarts/").openConnection();
            c.setRequestProperty("User-Agent", "CanoePlay");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            List<String> out = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = HREF.matcher(line);
                while (m.find()) {
                    String n = URLDecoder.decode(m.group(1).replace("+", "%2B"), "UTF-8");
                    out.add(n.substring(0, n.length() - 4));
                }
            }
            r.close();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> readCache(File f) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            List<String> out = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) if (!line.isEmpty()) out.add(line);
            r.close();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(File f, List<String> names) {
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            for (String n : names) w.write(n + "\n");
            w.close();
        } catch (Exception ignored) {}
    }

    /** "Legend of Zelda, The - A Link to the Past (U) [!]" -> "legendofzeldaalinktothepast" */
    static String norm(String s) {
        String t = s.toLowerCase(Locale.US)
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("\\[[^\\]]*\\]", " ")
                .replace("&", " and ")
                .replaceAll("\\bthe\\b", " ")
                .replaceAll("\\bv\\d+(\\.\\d+)?\\b", " ");
        return t.replaceAll("[^a-z0-9]", "");
    }

    private static String regionHint(String stem) {
        String s = stem.toLowerCase(Locale.US);
        if (s.matches(".*\\((u|usa|us|ue|ju|jue)\\).*") || s.contains("(usa")) return "usa";
        if (s.matches(".*\\((e|europe|eu)\\).*") || s.contains("(europe")) return "europe";
        if (s.matches(".*\\((j|japan|jp)\\).*") || s.contains("(japan")) return "japan";
        if (s.matches(".*\\((w|world)\\).*")) return "world";
        return "usa";
    }

    static String best(Map<String, List<String>> idx, List<String> names, String stem) {
        String want = norm(stem);
        if (want.isEmpty()) return null;
        // fast path: exact title match from the index
        List<String> exact = idx.get(want);
        if (exact != null) return pick(exact, want, stem);
        return pick(names, want, stem); // slower fuzzy search, only for odd names
    }

    private static String pick(List<String> names, String want, String stem) {
        String hint = regionHint(stem);
        String bestName = null;
        int bestScore = Integer.MIN_VALUE;
        for (String n : names) {
            String nn = norm(n);
            int score;
            if (nn.equals(want)) score = 100;
            else if (want.length() >= 6 && (nn.startsWith(want) || want.startsWith(nn)) && nn.length() >= 6) {
                score = 60 - Math.abs(nn.length() - want.length());
            } else continue;
            String low = n.toLowerCase(Locale.US);
            if (low.contains(hint)) score += 8;
            if (low.contains("(usa")) score += 3;
            else if (low.contains("(world")) score += 2;
            else if (low.contains("europe")) score += 1;
            if (low.matches(".*\\b(beta|proto|demo|sample|kiosk|pirate|hack)\\b.*")) score -= 20;
            if (score > bestScore) { bestScore = score; bestName = n; }
        }
        return bestName;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }
}
