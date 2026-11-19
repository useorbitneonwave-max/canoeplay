package com.canoe.play;

import android.content.Context;

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
    private final Map<String, List<String>> lists = new HashMap<>();
    private final Map<String, String> matches = new HashMap<>();

    ArtFinder(Context ctx) {
        dir = new File(ctx.getCacheDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
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
            name = best(names, stem);
        }
        String url = name == null ? null : BASE + enc(system) + "/Named_Boxarts/" + enc(name) + ".png";
        if (names != null) synchronized (matches) { matches.put(key, url); }
        return url;
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

    static String best(List<String> names, String stem) {
        String want = norm(stem);
        if (want.isEmpty()) return null;
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
