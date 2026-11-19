package com.canoe.play;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Small web server inside the app. The TV's own browser view loads the game
 * library from it, and a phone or computer on the same Wi-Fi can open
 * http://TV-IP:8080/upload to add games.
 */
public class GameServer extends NanoHTTPD {

    private final AssetManager assets;
    private final RomLibrary library;
    private final ArtFinder art;
    private final EjsCache ejs;

    GameServer(Context ctx, int port, RomLibrary library) {
        super(port);
        this.assets = ctx.getAssets();
        this.library = library;
        this.art = new ArtFinder(ctx);
        this.ejs = new EjsCache(ctx);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        try {
            if (uri.equals("/") || uri.equals("/index.html")) return asset("index.html");
            if (uri.equals("/play")) return asset("play.html");
            if (uri.equals("/upload")) return asset("upload.html");
            if (uri.startsWith("/static/")) return asset(uri.substring("/static/".length()));
            if (uri.equals("/api/games")) return json(library.list().toString());
            if (uri.equals("/api/info")) {
                JSONObject o = new JSONObject();
                o.put("ip", localIp());
                o.put("port", getListeningPort());
                return json(o.toString());
            }
            if (uri.startsWith("/rom/")) return rom(uri.substring("/rom/".length()));
            if (uri.equals("/api/art")) return art(session);
            if (uri.startsWith("/ejs/")) return emulatorFile(uri.substring("/ejs/".length()));
            if (uri.equals("/api/upload") && session.getMethod() == Method.POST) return upload(session);
            if (uri.equals("/api/delete") && session.getMethod() == Method.POST) return delete(session);
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: " + e.getMessage());
        }
    }

    private Response asset(String path) {
        if (path.contains("..")) return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "No");
        try {
            InputStream in = assets.open("web/" + path);
            Response r = newChunkedResponse(Response.Status.OK, mimeFor(path), in);
            r.addHeader("Cache-Control", "no-cache");
            return r;
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }
    }

    private Response json(String body) {
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json", body);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private Response rom(String id) throws IOException {
        File f = library.resolve(id);
        if (f == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Game not found");
        Response r = newFixedLengthResponse(Response.Status.OK, "application/octet-stream",
                new FileInputStream(f), f.length());
        r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }

    private Response emulatorFile(String path) throws IOException {
        File f = ejs.get(path);
        if (f == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT,
                    "Emulator file not available offline yet: " + path);
        }
        Response r = newFixedLengthResponse(Response.Status.OK, mimeFor(path), new FileInputStream(f), f.length());
        r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }

    private Response art(IHTTPSession session) {
        List<String> sys = session.getParameters().get("sys");
        List<String> stem = session.getParameters().get("stem");
        if (sys == null || stem == null || sys.isEmpty() || stem.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing");
        }
        File f = art.thumb(sys.get(0), stem.get(0));
        if (f == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No cover");
        try {
            Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", new FileInputStream(f), f.length());
            r.addHeader("Cache-Control", "max-age=604800");
            return r;
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No cover");
        }
    }

    private Response upload(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String tmp = files.get("rom");
        // The page sends the real file name in the address (?name=...), because the
        // multipart parser cuts names off at an apostrophe, e.g. "Diddy's Kong Quest".
        List<String> names = session.getParameters().get("name");
        if (names == null || names.isEmpty() || names.get(0).trim().isEmpty()) {
            names = session.getParameters().get("rom");
        }
        if (tmp == null || names == null || names.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No file received");
        }
        String name = new File(names.get(0)).getName().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.startsWith(".") || !RomLibrary.acceptable(name)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT,
                    "Not a supported game file: " + name);
        }
        File dest = new File(library.internalDir(), name);
        copy(new File(tmp), dest);
        if (RomLibrary.detect(dest) == null) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT,
                    "Couldn't find a supported game inside " + name);
        }
        return json("{\"ok\":true,\"name\":" + JSONObject.quote(name) + "}");
    }

    private Response delete(IHTTPSession session) {
        List<String> ids = session.getParameters().get("id");
        if (ids == null || ids.isEmpty()) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No id");
        File f = library.resolve(ids.get(0));
        if (f == null || !f.getParentFile().equals(library.internalDir())) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT,
                    "Only games added over Wi-Fi can be removed here");
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        return json("{\"ok\":true}");
    }

    private static void copy(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    private static String mimeFor(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".wasm")) return "application/wasm";
        if (path.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    /** The TV's address on the home network, e.g. 192.168.1.40 */
    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }
}
