package com.canoe.play;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.graphics.Bitmap;

import java.io.IOException;

public class MainActivity extends Activity {

    static final int PORT = 8080;
    static final String HOME = "http://127.0.0.1:" + PORT + "/";

    // EmulatorJS button numbers (RetroPad layout)
    static final int B = 0, Y = 1, SELECT = 2, START = 3, UP = 4, DOWN = 5, LEFT = 6, RIGHT = 7,
            A = 8, X = 9, L = 10, R = 11, L2 = 12, R2 = 13, L3 = 14, R3 = 15;
    // Analog stick slots (left stick: right, left, down, up; right stick likewise)
    static final int LS_RIGHT = 16, LS_LEFT = 17, LS_DOWN = 18, LS_UP = 19,
            RS_RIGHT = 20, RS_LEFT = 21, RS_DOWN = 22, RS_UP = 23;

    private WebView web;
    private GameServer server;
    private Updater updater;
    private volatile boolean inGame = false;
    private volatile boolean analogCore = false;
    private boolean menuOpen = false;

    static final int MAX_PLAYERS = 4;
    // last sent state per player for sticks / hat / triggers, so only changes are sent
    private final int[][] lastAxis = new int[MAX_PLAYERS][24];
    private final boolean[][] axisPressed = new boolean[MAX_PLAYERS][16];
    private final boolean[] fastOn = new boolean[MAX_PLAYERS];
    private final boolean[] rewindOn = new boolean[MAX_PLAYERS];
    private int libraryAxisKey = -1;
    // controller layout for the game being played: Android key code -> emulator button
    private volatile android.util.SparseIntArray activeMap = new android.util.SparseIntArray();
    private volatile boolean capturing = false;
    private int captureDown = -1; // only count a button pressed after the Controls screen asked
    private boolean chainingMenu = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        RomLibrary library = new RomLibrary(this);
        server = new GameServer(this, PORT, library);
        try {
            server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (IOException e) {
            // already running from a previous instance; carry on
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0e1b30"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Present as a desktop browser so the emulator doesn't draw a touch-screen gamepad over the game
        String ua = s.getUserAgentString();
        if (ua != null) s.setUserAgentString(ua.replace("Android", "Linux TV").replace(" Mobile", "") + " CanoePlay/1.0");
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        web.addJavascriptInterface(new Bridge(), "Canoe");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                inGame = false; // the game page switches this back on once a game starts
            }
        });
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);
        updater = new Updater(this, web);
        web.requestFocus();
        web.loadUrl(HOME);

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (!inGame) js("window.canoeReload && canoeReload()");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (inGame) js("window.canoeSave && canoeSave()");
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        if (updater != null) updater.release();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Called from the web pages. */
    class Bridge {
        @JavascriptInterface
        public void setInGame(boolean playing, String core) {
            analogCore = "n64".equals(core) || "nds".equals(core) || "psx".equals(core);
            if (playing) {
                activeMap = loadMapping(core);
                runOnUiThread(new Runnable() { @Override public void run() { playerOrder.clear(); } });
            }
            inGame = playing;
        }

        /** Controller layout for a system, as JSON {"keycode": emulatorButton}. */
        @JavascriptInterface
        public String getMapping(String core) {
            return prefs().getString("map_" + core, defaultMapping(core).toString());
        }

        @JavascriptInterface
        public void setMapping(String core, String json) {
            prefs().edit().putString("map_" + core, json).apply();
        }

        @JavascriptInterface
        public void resetMapping(String core) {
            prefs().edit().remove("map_" + core).apply();
        }

        /** The next controller button pressed is reported to the page instead of acting. */
        @JavascriptInterface
        public void captureButton() { captureDown = -1; capturing = true; }

        @JavascriptInterface
        public void cancelCapture() { capturing = false; }

        @JavascriptInterface
        public void checkForUpdate(boolean quiet) { updater.check(quiet); }

        @JavascriptInterface
        public void installUpdate() {
            runOnUiThread(new Runnable() { @Override public void run() { updater.install(); } });
        }

        @JavascriptInterface
        public String appVersion() { return updater.installedVersion(); }
    }

    private void js(String code) {
        web.evaluateJavascript(code, null);
    }

    private void press(int player, int button, boolean down) {
        js("window.canoeInput&&canoeInput(" + player + "," + button + "," + (down ? 1 : 0) + ")");
    }

    private void pressAnalog(int player, int slot, int value) {
        js("window.canoeInput&&canoeInput(" + player + "," + slot + "," + value + ")");
    }

    // game controllers in the order they were first used in this game
    private final java.util.ArrayList<Integer> playerOrder = new java.util.ArrayList<>();

    /**
     * The first controller you press in a game is player 1, the next one player 2, and so on.
     * (Android's own controller numbers can't be trusted: on the Shield the remote can take
     * number 1, which made a PS5 controller player 2.) The TV remote always counts as player 1.
     */
    private int playerFor(InputDevice dev) {
        if (dev == null) return 0;
        int src = dev.getSources();
        boolean pad = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (!pad) return 0;
        int id = dev.getId();
        int i = playerOrder.indexOf(id);
        if (i < 0) {
            if (playerOrder.size() >= MAX_PLAYERS) return 0;
            playerOrder.add(id);
            i = playerOrder.size() - 1;
        }
        return i;
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("controls", MODE_PRIVATE);
    }

    /** Default layout: buttons matched by position, like RetroArch. */
    static org.json.JSONObject defaultMapping(String core) {
        boolean analog = "n64".equals(core) || "nds".equals(core) || "psx".equals(core);
        int[][] pairs = {
                {KeyEvent.KEYCODE_BUTTON_A, B},      // bottom face button (✕)
                {KeyEvent.KEYCODE_BUTTON_B, A},      // right face button (○)
                {KeyEvent.KEYCODE_BUTTON_X, Y},      // left face button (▢)
                {KeyEvent.KEYCODE_BUTTON_Y, X},      // top face button (△)
                {KeyEvent.KEYCODE_BUTTON_L1, L},
                {KeyEvent.KEYCODE_BUTTON_R1, R},
                {KeyEvent.KEYCODE_BUTTON_THUMBL, L3},
                {KeyEvent.KEYCODE_BUTTON_THUMBR, R3},
                {KeyEvent.KEYCODE_BUTTON_START, START},
                {KeyEvent.KEYCODE_BUTTON_SELECT, SELECT},
                {KeyEvent.KEYCODE_DPAD_UP, UP},
                {KeyEvent.KEYCODE_DPAD_DOWN, DOWN},
                {KeyEvent.KEYCODE_DPAD_LEFT, LEFT},
                {KeyEvent.KEYCODE_DPAD_RIGHT, RIGHT},
                {KeyEvent.KEYCODE_DPAD_CENTER, A},   // remote OK button
        };
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            for (int[] p : pairs) o.put(String.valueOf(p[0]), p[1]);
            if (analog) { // on N64 / DS the triggers are game buttons, not fast-forward / rewind
                o.put(String.valueOf(KeyEvent.KEYCODE_BUTTON_L2), L2);
                o.put(String.valueOf(KeyEvent.KEYCODE_BUTTON_R2), R2);
            }
        } catch (Exception ignored) {}
        return o;
    }

    private android.util.SparseIntArray loadMapping(String core) {
        android.util.SparseIntArray m = new android.util.SparseIntArray();
        try {
            org.json.JSONObject o = new org.json.JSONObject(
                    prefs().getString("map_" + core, defaultMapping(core).toString()));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(Integer.parseInt(k), o.getInt(k));
            }
        } catch (Exception e) {
            return loadMappingDefault(core);
        }
        return m;
    }

    private android.util.SparseIntArray loadMappingDefault(String core) {
        android.util.SparseIntArray m = new android.util.SparseIntArray();
        org.json.JSONObject o = defaultMapping(core);
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            m.put(Integer.parseInt(k), o.optInt(k));
        }
        return m;
    }

    private int mapButton(int keyCode) {
        return activeMap.get(keyCode, -1);
    }

    private void setFast(int player, boolean on) {
        if (fastOn[player] == on) return;
        fastOn[player] = on;
        js("window.canoeFast&&canoeFast(" + (on ? 1 : 0) + ")");
    }

    private void setRewind(int player, boolean on) {
        if (rewindOn[player] == on) return;
        rewindOn[player] = on;
        js("window.canoeRewind&&canoeRewind(" + (on ? 1 : 0) + ")");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = e.getAction() == KeyEvent.ACTION_UP;

        if (menuOpen) return super.dispatchKeyEvent(e);

        if (capturing && !inGame) {
            // Controls screen is waiting for a button
            if (down && e.getRepeatCount() == 0) captureDown = code;
            if (up && code == captureDown) {
                capturing = false;
                captureDown = -1;
                js("window.canoeCaptured&&canoeCaptured(" + (code == KeyEvent.KEYCODE_BACK ? -1 : code) + ")");
            }
            return true;
        }

        if (inGame) {
            // Back on the remote, or the Home/PS button on a controller, opens the pause menu
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_MODE
                    || code == KeyEvent.KEYCODE_MENU) {
                if (up) showPauseMenu();
                return true;
            }
            int player = playerFor(e.getDevice());
            // Triggers: hold R2 to fast-forward, hold L2 to rewind (systems without L2/R2 buttons)
            if ((code == KeyEvent.KEYCODE_BUTTON_L2 || code == KeyEvent.KEYCODE_BUTTON_R2) && mapButton(code) < 0) {
                if (e.getRepeatCount() == 0 && (down || up)) {
                    if (code == KeyEvent.KEYCODE_BUTTON_R2) setFast(player, down); else setRewind(player, down);
                }
                return true;
            }
            int btn = mapButton(code);
            if (btn >= 0) {
                if (e.getRepeatCount() == 0 && (down || up)) press(player, btn, down);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        // Library screen: ✕ / OK = select, ○ / Back = back, △ / ▢ / Menu = favourite
        if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_BUTTON_START) {
            web.dispatchKeyEvent(new KeyEvent(e.getAction(), KeyEvent.KEYCODE_ENTER));
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
            if (up) {
                web.evaluateJavascript("window.canoeBack ? canoeBack() : false", new ValueCallback<String>() {
                    @Override public void onReceiveValue(String handled) {
                        if ("false".equals(handled)) finish();
                    }
                });
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_X || code == KeyEvent.KEYCODE_BUTTON_Y || code == KeyEvent.KEYCODE_MENU) {
            if (up) js("window.canoeOptions&&canoeOptions()");
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
            // jump a few rows in the library
            web.dispatchKeyEvent(new KeyEvent(e.getAction(),
                    code == KeyEvent.KEYCODE_BUTTON_L1 ? KeyEvent.KEYCODE_PAGE_UP : KeyEvent.KEYCODE_PAGE_DOWN));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        boolean joystick = (ev.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (ev.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (!joystick || ev.getAction() != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(ev);

        float hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
        float[] left = deadzone(ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y));
        float[] right = deadzone(ev.getAxisValue(MotionEvent.AXIS_Z), ev.getAxisValue(MotionEvent.AXIS_RZ));
        float lx = left[0], ly = left[1], rx = right[0], ry = right[1];
        float lt = Math.max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS));

        if (capturing && !inGame && (lt > 0.6f || rt > 0.6f)) {
            capturing = false;
            js("window.canoeCaptured&&canoeCaptured(" + (lt > 0.6f ? KeyEvent.KEYCODE_BUTTON_L2 : KeyEvent.KEYCODE_BUTTON_R2) + ")");
            return true;
        }
        if (!inGame) {
            // let the stick / hat move around the library
            int key = -1;
            if (hatX > 0.5f || lx > 0.6f) key = KeyEvent.KEYCODE_DPAD_RIGHT;
            else if (hatX < -0.5f || lx < -0.6f) key = KeyEvent.KEYCODE_DPAD_LEFT;
            else if (hatY > 0.5f || ly > 0.6f) key = KeyEvent.KEYCODE_DPAD_DOWN;
            else if (hatY < -0.5f || ly < -0.6f) key = KeyEvent.KEYCODE_DPAD_UP;
            if (key != libraryAxisKey) {
                libraryAxisKey = key;
                if (key != -1) {
                    web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
                    web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
                }
            }
            return true;
        }
        if (menuOpen) return true;

        int p = playerFor(ev.getDevice());
        // D-pad from the hat (and from the left stick on systems without analog sticks)
        boolean useStickAsDpad = !analogCore;
        sendDigital(p, UP, hatY < -0.5f || (useStickAsDpad && ly < -0.5f));
        sendDigital(p, DOWN, hatY > 0.5f || (useStickAsDpad && ly > 0.5f));
        sendDigital(p, LEFT, hatX < -0.5f || (useStickAsDpad && lx < -0.5f));
        sendDigital(p, RIGHT, hatX > 0.5f || (useStickAsDpad && lx > 0.5f));

        // analog triggers: act like the L2 / R2 buttons if they're mapped, otherwise rewind / fast-forward
        int l2 = mapButton(KeyEvent.KEYCODE_BUTTON_L2), r2 = mapButton(KeyEvent.KEYCODE_BUTTON_R2);
        if (l2 >= 0) sendDigital(p, l2, lt > 0.5f); else setRewind(p, lt > 0.5f);
        if (r2 >= 0) sendDigital(p, r2, rt > 0.5f); else setFast(p, rt > 0.5f);

        if (analogCore) {
            sendAnalog(p, LS_RIGHT, lx > 0 ? lx : 0);
            sendAnalog(p, LS_LEFT, lx < 0 ? -lx : 0);
            sendAnalog(p, LS_DOWN, ly > 0 ? ly : 0);
            sendAnalog(p, LS_UP, ly < 0 ? -ly : 0);
            sendAnalog(p, RS_RIGHT, rx > 0 ? rx : 0);
            sendAnalog(p, RS_LEFT, rx < 0 ? -rx : 0);
            sendAnalog(p, RS_DOWN, ry > 0 ? ry : 0);
            sendAnalog(p, RS_UP, ry < 0 ? -ry : 0);
        }
        return true;
    }

    /**
     * Ignores small stick movement so a worn (drifting) stick doesn't move on its own.
     * Uses a round dead zone and rescales the rest so full tilt still reaches 100%.
     */
    static final float DEADZONE = 0.30f;

    private static float[] deadzone(float x, float y) {
        float mag = (float) Math.sqrt(x * x + y * y);
        if (mag < DEADZONE) return new float[]{0f, 0f};
        float scaled = Math.min(1f, (mag - DEADZONE) / (1f - DEADZONE));
        return new float[]{x / mag * scaled, y / mag * scaled};
    }

    private void sendDigital(int p, int button, boolean pressed) {
        if (axisPressed[p][button] != pressed) {
            axisPressed[p][button] = pressed;
            press(p, button, pressed);
        }
    }

    private void sendAnalog(int p, int slot, float amount) {
        int v = Math.round(Math.min(1f, amount) * 32767);
        if (Math.abs(v - lastAxis[p][slot]) > 600 || (v == 0 && lastAxis[p][slot] != 0)) {
            lastAxis[p][slot] = v;
            pressAnalog(p, slot, v);
        }
    }

    private void releaseAll() {
        for (int p = 0; p < MAX_PLAYERS; p++) {
            for (int b = 0; b < 16; b++) { press(p, b, false); axisPressed[p][b] = false; }
            for (int a = LS_RIGHT; a <= RS_UP; a++) { pressAnalog(p, a, 0); lastAxis[p][a] = 0; }
            setFast(p, false);
            setRewind(p, false);
        }
    }

    private AlertDialog menuDialog(String title, String[] items, DialogInterface.OnClickListener onPick) {
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(items, onPick)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                if (chainingMenu) { chainingMenu = false; return; } // another menu is opening
                menuOpen = false;
                js("window.canoePause && canoePause(false)");
            }
        });
        return dialog;
    }

    private void showPauseMenu() {
        menuOpen = true;
        releaseAll();
        js("window.canoePause && canoePause(true)");
        final String[] items = {"Resume", "Save game\u2026", "Load game\u2026", "Picture: change look",
                "Restart game", "Quit to library"};
        menuDialog("Paused", items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                switch (which) {
                    case 1: chainingMenu = true; showSlotMenu(true); break;
                    case 2: chainingMenu = true; showSlotMenu(false); break;
                    case 3: js("canoeNextPicture()"); break;
                    case 4: js("canoeRestart()"); break;
                    case 5: js("canoeQuit()"); break;
                    default: break;
                }
            }
        }).show();
    }

    private void showSlotMenu(final boolean save) {
        menuOpen = true;
        final String[] slots = {"Slot 1", "Slot 2", "Slot 3"};
        menuDialog(save ? "Save to which slot?" : "Load which slot?", slots, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                js((save ? "canoeSaveSlot(" : "canoeLoadSlot(") + (which + 1) + ")");
            }
        }).show();
    }
}
