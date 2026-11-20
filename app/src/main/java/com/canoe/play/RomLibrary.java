package com.canoe.play;

import android.content.Context;
import android.os.Environment;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Finds ROM files and works out which emulator core each one needs. */
public class RomLibrary {

    /** One supported system. */
    static class Sys {
        final String core, label, thumbs;
        Sys(String core, String label, String thumbs) { this.core = core; this.label = label; this.thumbs = thumbs; }
    }

    static final Sys SNES = new Sys("snes", "Super Nintendo", "Nintendo - Super Nintendo Entertainment System");
    static final Sys NES = new Sys("nes", "Nintendo (NES)", "Nintendo - Nintendo Entertainment System");
    static final Sys GB = new Sys("gb", "Game Boy", "Nintendo - Game Boy");
    static final Sys GBC = new Sys("gb", "Game Boy Color", "Nintendo - Game Boy Color");
    static final Sys GBA = new Sys("gba", "Game Boy Advance", "Nintendo - Game Boy Advance");
    static final Sys N64 = new Sys("n64", "Nintendo 64", "Nintendo - Nintendo 64");
    static final Sys NDS = new Sys("nds", "Nintendo DS", "Nintendo - Nintendo DS");
    static final Sys MD = new Sys("segaMD", "Sega Genesis", "Sega - Mega Drive - Genesis");
    static final Sys SMS = new Sys("segaMS", "Sega Master System", "Sega - Master System - Mark III");
    static final Sys GG = new Sys("segaGG", "Sega Game Gear", "Sega - Game Gear");
    static final Sys S32X = new Sys("sega32x", "Sega 32X", "Sega - 32X");
    static final Sys PSX = new Sys("psx", "PlayStation", "Sony - PlayStation");
    static final Sys PCE = new Sys("pce", "TurboGrafx-16", "NEC - PC Engine - TurboGrafx 16");
    static final Sys NGP = new Sys("ngp", "Neo Geo Pocket", "SNK - Neo Geo Pocket Color");
    static final Sys WS = new Sys("ws", "WonderSwan", "Bandai - WonderSwan Color");
    static final Sys VB = new Sys("vb", "Virtual Boy", "Nintendo - Virtual Boy");
    static final Sys A2600 = new Sys("atari2600", "Atari 2600", "Atari - 2600");
    static final Sys A5200 = new Sys("atari5200", "Atari 5200", "Atari - 5200");
    static final Sys A7800 = new Sys("atari7800", "Atari 7800", "Atari - 7800");
    static final Sys LYNX = new Sys("lynx", "Atari Lynx", "Atari - Lynx");
    static final Sys JAG = new Sys("jaguar", "Atari Jaguar", "Atari - Jaguar");
    static final Sys COLECO = new Sys("coleco", "ColecoVision", "Coleco - ColecoVision");
    static final Sys ARCADE = new Sys("arcade", "Arcade", "FBNeo - Arcade Games");

    static Sys systemForExt(String ext) {
        switch (ext) {
            case "sfc": case "smc": case "fig": case "swc": return SNES;
            case "nes": case "fds": case "unf": case "unif": return NES;
            case "gb": return GB;
            case "gbc": return GBC;
            case "gba": return GBA;
            case "n64": case "z64": case "v64": return N64;
            case "nds": return NDS;
            case "md": case "gen": case "smd": return MD;
            case "sms": return SMS;
            case "gg": return GG;
            case "32x": return S32X;
            case "chd": case "pbp": case "cue": return PSX;
            case "pce": return PCE;
            case "ngp": case "ngc": return NGP;
            case "ws": case "wsc": return WS;
            case "vb": case "vboy": return VB;
            case "a26": return A2600;
            case "a52": return A5200;
            case "a78": return A7800;
            case "lnx": return LYNX;
            case "j64": case "jag": return JAG;
            case "col": return COLECO;
            default: return null;
        }
    }

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }

    static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** True if this file name looks like something we can play (or a zip to peek into). */
    static boolean acceptable(String name) {
        String e = ext(name);
        return (e.equals("zip") || systemForExt(e) != null) && !e.equals("cue");
    }

    /**
     * Works out the system for a file, looking inside zips. A zip with no console game
     * inside (just chip dumps like "sf2.zip") is treated as an arcade game.
     */
    static Sys detect(File f) {
        String e = ext(f.getName());
        if (!e.equals("zip")) return systemForExt(e);
        String parent = f.getParentFile() == null ? "" : f.getParentFile().getName().toLowerCase(Locale.US);
        if (parent.equals("arcade") || parent.equals("mame") || parent.equals("fbneo") || parent.equals("neogeo")) {
            return ARCADE;
        }
        ZipFile zip = null;
        int files = 0;
        try {
            zip = new ZipFile(f);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry z = entries.nextElement();
                if (z.isDirectory()) continue;
                files++;
                Sys s = systemForExt(ext(z.getName()));
                if (s != null) return s;
            }
        } catch (IOException ignored) {
            return null;
        } finally {
            if (zip != null) try { zip.close(); } catch (IOException ignored) {}
        }
        return files > 0 ? ARCADE : null;
    }

    /** PlayStation BIOS files, e.g. scph5501.bin (optional: games run without one). */
    static boolean isBios(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.matches("scph[-_]?\\d+[a-z]?\\.bin") || n.matches("psx.*bios.*\\.bin");
    }

    private final File internalDir;
    // detection results keyed by path+size+date, so zips are only opened once
    private final java.util.Map<String, Sys> detectCache = new java.util.HashMap<>();
    private String lastSignature = null;
    private JSONArray lastList = null;

    RomLibrary(Context ctx) {
        internalDir = new File(ctx.getFilesDir(), "roms");
        //noinspection ResultOfMethodCallIgnored
        internalDir.mkdirs();
    }

    File internalDir() { return internalDir; }

    /** Folders named ROMs on internal storage and on any USB stick / SD card. */
    List<File> externalRoots() {
        List<File> roots = new ArrayList<>();
        try {
            File primary = Environment.getExternalStorageDirectory();
            if (primary != null) {
                addRomFolders(primary, roots);
            }
        } catch (Exception ignored) {}
        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) {
                String n = v.getName();
                if (n.equals("self") || n.equals("emulated")) continue;
                addRomFolders(v, roots);
            }
        }
        File[] mnt = new File("/mnt/media_rw").listFiles();
        if (mnt != null) for (File v : mnt) addRomFolders(v, roots);
        return roots;
    }

    private static void addRomFolders(File base, List<File> out) {
        File[] kids = base.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory() && k.getName().equalsIgnoreCase("roms")) out.add(k);
        }
    }

    static String idFor(String kind, String value) {
        String raw = kind + ":" + value;
        return Base64.encodeToString(raw.getBytes(Charset.forName("UTF-8")),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    /** Turns a game id back into a file, refusing anything outside the ROM folders. */
    File resolve(String id) {
        String raw;
        try {
            raw = new String(Base64.decode(id, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                    Charset.forName("UTF-8"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            if (raw.startsWith("i:")) {
                String name = raw.substring(2);
                if (name.contains("/") || name.contains("\\") || name.startsWith(".")) return null;
                File f = new File(internalDir, name);
                return f.isFile() ? f : null;
            }
            if (raw.startsWith("e:")) {
                File f = new File(raw.substring(2)).getCanonicalFile();
                for (File root : externalRoots()) {
                    String r = root.getCanonicalPath() + File.separator;
                    if (f.getPath().startsWith(r) && f.isFile()) return f;
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    /** Cheap: lists files only. The expensive part (opening zips) is cached per file. */
    synchronized JSONArray list() {
        List<File> files = new ArrayList<>();
        File[] internal0 = internalDir.listFiles();
        if (internal0 != null) for (File f : internal0) files.add(f);
        List<File> ext = new ArrayList<>();
        for (File root : externalRoots()) collect(root, ext, 0);
        files.addAll(ext);
        StringBuilder sig = new StringBuilder();
        for (File f : files) sig.append(f.getPath()).append('|').append(f.length()).append('|').append(f.lastModified()).append('\n');
        String signature = sig.toString();
        if (signature.equals(lastSignature) && lastList != null) return lastList;
        lastSignature = signature;
        lastList = buildList();
        return lastList;
    }

    private static void collect(File dir, List<File> out, int depth) {
        if (depth > 3) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().startsWith(".")) continue;
            if (k.isDirectory()) collect(k, out, depth + 1);
            else if (acceptable(k.getName())) out.add(k);
        }
    }

    private Sys detectCached(File f) {
        String key = f.getPath() + "|" + f.length() + "|" + f.lastModified();
        if (detectCache.containsKey(key)) return detectCache.get(key);
        Sys s = detect(f);
        detectCache.put(key, s);
        return s;
    }

    private JSONArray buildList() {
        List<JSONObject> games = new ArrayList<>();
        File[] internal = internalDir.listFiles();
        if (internal != null) {
            for (File f : internal) add(games, f, idFor("i", f.getName()), true, detectCached(f));
        }
        for (File root : externalRoots()) walk(root, games, 0);

        Collections.sort(games, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                int c = a.optString("system").compareTo(b.optString("system"));
                return c != 0 ? c : a.optString("title").compareToIgnoreCase(b.optString("title"));
            }
        });
        JSONArray arr = new JSONArray();
        for (JSONObject g : games) arr.put(g);
        return arr;
    }

    private void walk(File dir, List<JSONObject> out, int depth) {
        // (instance method so it can use the detection cache)
        if (depth > 3) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().startsWith(".")) continue;
            if (k.isDirectory()) walk(k, out, depth + 1);
            else {
                try {
                    add(out, k, idFor("e", k.getCanonicalPath()), false, detectCached(k));
                } catch (IOException ignored) {}
            }
        }
    }

    private static void add(List<JSONObject> out, File f, String id, boolean removable, Sys s) {
        if (!f.isFile() || !acceptable(f.getName())) return;
        if (s == null) return;
        try {
            String stem = stem(f.getName());
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("file", f.getName());
            o.put("stem", stem);
            o.put("title", cleanTitle(stem));
            o.put("core", s.core);
            o.put("system", s.label);
            o.put("thumbSystem", s.thumbs);
            o.put("thumbName", stem.replaceAll("[&*/:`<>?\\\\|\"]", "_"));
            o.put("removable", removable);
            out.add(o);
        } catch (Exception ignored) {}
    }

    /** "Super Mario World (USA) [!]" -> "Super Mario World" */
    static String cleanTitle(String stem) {
        String t = stem.replaceAll("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]", "").replace('_', ' ').trim();
        return t.isEmpty() ? stem : t;
    }
}
