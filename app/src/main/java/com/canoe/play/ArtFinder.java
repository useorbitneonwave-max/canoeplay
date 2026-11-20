package com.canoe.play;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Box art from the free libretro thumbnail collection.
 * - Gets the list of cover names for each system (from thumbnails.libretro.com, or
 *   libretro's GitHub copy as a backup) and matches games to it, even with old-style
 *   file names like "Donkey Kong Country (U) (V1.2) [!]".
 * - Shrinks each cover once and keeps it on the device.
 * - Only remembers "no cover" when that's really true, never after a network error.
 */
public class ArtFinder {

    static final String BASE = "https://thumbnails.libretro.com/";
    static final String RAW = "https://raw.githubusercontent.com/libretro-thumbnails/";
    private static final Pattern HREF = Pattern.compile("href=\"([^\"/?]+\\.png)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern TREE = Pattern.compile("\"path\"\\s*:\\s*\"Named_Boxarts/([^\"]+)\\.png\"");
    private static final long LIST_AGE = 14L * 24 * 60 * 60 * 1000;
    private static final long MISS_AGE = 3L * 24 * 60 * 60 * 1000;
    private static final long RETRY_LIST_AFTER = 10L * 60 * 1000;

    private final File dir;
    private final File covers;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, List<String>> lists = new HashMap<>();
    private final Map<String, Map<String, List<String>>> indexes = new HashMap<>();
    private final Map<String, Long> listFailedAt = new HashMap<>();
    private final Semaphore downloads = new Semaphore(4); // a few covers at a time

    ArtFinder(Context ctx) {
        dir = new File(ctx.getCacheDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        covers = new File(ctx.getFilesDir(), "covers");
        //noinspection ResultOfMethodCallIgnored
        covers.mkdirs();
        // clear "no cover" notes written by older versions after network errors
        File[] old = covers.listFiles();
        if (old != null) for (File f : old) {
            if (f.getName().endsWith(".none")) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    /** A small JPEG of the cover kept on the device, or null if there isn't one (yet). */
    File thumb(String system, String stem) {
        String key = sha1(system + "|" + stem);
        File jpg = new File(covers, key + ".jpg");
        File miss = new File(covers, key + ".nocover");
        if (jpg.isFile()) return jpg;
        if (miss.isFile() && System.currentTimeMillis() - miss.lastModified() < MISS_AGE) return null;

        Object lock = locks.putIfAbsent(key, new Object());
        if (lock == null) lock = locks.get(key);
        synchronized (lock) {
            if (jpg.isFile()) return jpg;
            List<String> names = list(system);
            List<String> candidates = new ArrayList<>();
            if (names != null) {
                String best = best(index(system, names), names, stem);
                if (best != null) candidates.add(best);
            } else {
                candidates.addAll(guesses(stem)); // list unavailable: try likely names
            }
            if (candidates.isEmpty()) { touch(miss); return null; }

            boolean networkError = false;
            for (String name : candidates) {
                List<String> urls = new ArrayList<>();
                urls.add(BASE + enc(system) + "/Named_Boxarts/" + enc(name) + ".png");
                if (names != null) urls.add(RAW + repo(system) + "/master/Named_Boxarts/" + enc(name) + ".png");
                for (String url : urls) {
                    try {
                        byte[] data = fetch(url);
                        if (data != null && save(data, jpg, key)) return jpg;
                    } catch (Exception e) {
                        networkError = true;
                    }
                }
            }
            if (!networkError) touch(miss);
            return null;
        }
    }

    private boolean save(byte[] data, File jpg, String key) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return false;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 400) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        if (b == null) return false;
        if (b.getWidth() > 420) {
            Bitmap sc = Bitmap.createScaledBitmap(b, 400, Math.max(1, b.getHeight() * 400 / b.getWidth()), true);
            b.recycle();
            b = sc;
        }
        File tmp = new File(covers, key + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        out.close();
        b.recycle();
        return tmp.renameTo(jpg);
    }

    /** "Donkey Kong Country (U) [!]" -> stem, "Donkey Kong Country (USA)", "(USA, Europe)", ... */
    static List<String> guesses(String stem) {
        String title = RomLibrary.cleanTitle(stem);
        String hint = regionHint(stem);
        LinkedHashSet<String> g = new LinkedHashSet<>();
        g.add(stem.replaceAll("[&*/:`<>?\\\\|\"]", "_"));
        if (hint.equals("europe")) { g.add(title + " (Europe)"); }
        if (hint.equals("japan")) { g.add(title + " (Japan)"); }
        g.add(title + " (USA)");
        g.add(title + " (USA, Europe)");
        g.add(title + " (World)");
        g.add(title + " (Europe)");
        List<String> out = new ArrayList<>();
        for (String s : g) out.add(s.replaceAll("[&*/:`<>?\\\\|\"]", "_"));
        return out;
    }

    private byte[] fetch(String url) throws Exception {
        downloads.acquire();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", "CanoePlay");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code == 404) { c.disconnect(); return null; }          // really not there
            if (code != 200) { c.disconnect(); throw new Exception("HTTP " + code); }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            c.disconnect();
            return b.toByteArray();
        } finally {
            downloads.release();
        }
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

    private synchronized List<String> list(String system) {
        if (lists.containsKey(system)) return lists.get(system);
        File cache = new File(dir, system.replaceAll("[^A-Za-z0-9]", "_") + ".txt");
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < LIST_AGE) {
            List<String> names = readCache(cache);
            if (names != null && !names.isEmpty()) { lists.put(system, names); return names; }
        }
        Long failed = listFailedAt.get(system);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_LIST_AFTER) {
            return cache.isFile() ? readCache(cache) : null;
        }
        List<String> fresh = fromLibretroSite(system);
        if (fresh == null || fresh.size() < 50) fresh = fromGitHub(system);
        if (fresh != null && fresh.size() >= 50) {
            writeCache(cache, fresh);
            lists.put(system, fresh);
            return fresh;
        }
        listFailedAt.put(system, System.currentTimeMillis());
        return cache.isFile() ? readCache(cache) : null;
    }

    private static List<String> fromLibretroSite(String system) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + enc(system) + "/Named_Boxarts/").openConnection();
            c.setRequestProperty("User-Agent", "CanoePlay");
            c.setConnectTimeout(15000);
            c.setReadTimeout(45000);
            if (c.getResponseCode() != 200) return null;
            List<String> out = new ArrayList<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
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

    /** Backup: the same covers are mirrored in libretro's GitHub repositories. */
    private static List<String> fromGitHub(String system) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/libretro-thumbnails/"
                    + repo(system) + "/git/trees/master?recursive=1").openConnection();
            c.setRequestProperty("User-Agent", "CanoePlay");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            if (c.getResponseCode() != 200) return null;
            List<String> out = new ArrayList<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[16384];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
            Matcher m = TREE.matcher(sb);
            while (m.find()) out.add(m.group(1).replace("\\u0026", "&").replace("\\\"", "\""));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** "Nintendo - Super Nintendo Entertainment System" -> "Nintendo_-_Super_Nintendo_Entertainment_System" */
    static String repo(String system) {
        return system.replace(' ', '_');
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
