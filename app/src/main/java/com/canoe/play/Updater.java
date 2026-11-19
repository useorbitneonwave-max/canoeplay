package com.canoe.play;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * "Check for updates": looks at the latest GitHub release, downloads the APK and
 * hands it to Android's installer, which shows the usual Install confirmation.
 */
public class Updater {

    static final String LATEST = "https://api.github.com/repos/useorbitneonwave-max/canoeplay/releases/latest";
    static final String ACTION_STATUS = "com.canoe.play.INSTALL_STATUS";

    private final Activity act;
    private final WebView web;
    private volatile String apkUrl;
    private volatile String latestTag = "";
    private volatile boolean busy = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(confirm);
                }
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                status("done", latestTag, 100);
                busy = false;
            } else {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                status("error", status == PackageInstaller.STATUS_FAILURE_ABORTED
                        ? "The install was cancelled." : (msg == null ? "Install failed." : msg), 0);
                busy = false;
            }
        }
    };

    Updater(Activity act, WebView web) {
        this.act = act;
        this.web = web;
        act.registerReceiver(receiver, new IntentFilter(ACTION_STATUS));
    }

    void release() {
        try { act.unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    int installedBuild() {
        try {
            //noinspection deprecation
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    String installedVersion() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void status(String state, String detail, int pct) {
        final String js = "window.canoeUpdateStatus&&canoeUpdateStatus(" + JSONObject.quote(state) + ","
                + JSONObject.quote(detail == null ? "" : detail) + "," + pct + ")";
        act.runOnUiThread(new Runnable() {
            @Override public void run() { web.evaluateJavascript(js, null); }
        });
    }

    /** quiet = background check at startup: only reports when an update exists. */
    void check(final boolean quiet) {
        new Thread(new Runnable() {
            @Override public void run() {
                if (!quiet) status("checking", "", 0);
                try {
                    HttpURLConnection c = open(LATEST);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    JSONObject rel = new JSONObject(readAll(c.getInputStream()));
                    c.disconnect();
                    String tag = rel.getString("tag_name");
                    int build = Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1));
                    String url = null;
                    JSONArray assets = rel.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url");
                    }
                    latestTag = tag;
                    apkUrl = url;
                    if (url != null && build > installedBuild()) status("available", tag, 0);
                    else if (!quiet) status("current", installedVersion(), 0);
                } catch (Exception e) {
                    if (!quiet) status("error", "Couldn't reach GitHub. Check the internet connection.", 0);
                }
            }
        }).start();
    }

    void install() {
        if (busy) return;
        if (apkUrl == null) { check(false); return; }
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            status("permission", "", 0);
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
            } catch (Exception e) {
                act.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            }
            return;
        }
        busy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File apk = new File(act.getCacheDir(), "update.apk");
                    download(apkUrl, apk);
                    status("installing", latestTag, 100);

                    PackageInstaller pi = act.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams params =
                            new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    int id = pi.createSession(params);
                    PackageInstaller.Session session = pi.openSession(id);
                    InputStream in = new FileInputStream(apk);
                    OutputStream out = session.openWrite("canoeplay", 0, apk.length());
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                    in.close();
                    out.close();

                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    Intent done = new Intent(ACTION_STATUS).setPackage(act.getPackageName());
                    PendingIntent pending = PendingIntent.getBroadcast(act, id, done, flags);
                    session.commit(pending.getIntentSender());
                    session.close();
                } catch (Exception e) {
                    busy = false;
                    status("error", "Download failed: " + e.getMessage(), 0);
                }
            }
        }).start();
    }

    private void download(String url, File dest) throws Exception {
        HttpURLConnection c = open(url);
        long total = c.getContentLength();
        InputStream in = c.getInputStream();
        OutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[64 * 1024];
        long got = 0;
        int n, lastPct = -1;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            got += n;
            int pct = total > 0 ? (int) (got * 100 / total) : 0;
            if (pct != lastPct) { lastPct = pct; status("downloading", latestTag, pct); }
        }
        out.close();
        in.close();
        c.disconnect();
    }

    private static HttpURLConnection open(String url) throws Exception {
        // follow GitHub's redirects to its download servers by hand (always https)
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", "CanoePlay");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                url = c.getHeaderField("Location");
                c.disconnect();
                continue;
            }
            if (code != 200) throw new Exception("HTTP " + code);
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
