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

    // last sent state for stick / hat so we only send changes
    private final int[] lastAxis = new int[24];

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
            analogCore = "n64".equals(core) || "nds".equals(core);
            inGame = playing;
        }

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

    private void press(int button, boolean down) {
        js("window.canoeInput&&canoeInput(" + button + "," + (down ? 1 : 0) + ")");
    }

    private void pressAnalog(int slot, int value) {
        js("window.canoeInput&&canoeInput(" + slot + "," + value + ")");
    }

    /** Controller layout: buttons are matched by position, like RetroArch. */
    private static int mapButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return B;      // bottom face button
            case KeyEvent.KEYCODE_BUTTON_B: return A;      // right face button
            case KeyEvent.KEYCODE_BUTTON_X: return Y;      // left face button
            case KeyEvent.KEYCODE_BUTTON_Y: return X;      // top face button
            case KeyEvent.KEYCODE_BUTTON_L1: return L;
            case KeyEvent.KEYCODE_BUTTON_R1: return R;
            case KeyEvent.KEYCODE_BUTTON_L2: return L2;
            case KeyEvent.KEYCODE_BUTTON_R2: return R2;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return R3;
            case KeyEvent.KEYCODE_BUTTON_START: return START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return SELECT;
            case KeyEvent.KEYCODE_DPAD_UP: return UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER: return A;   // remote OK button
            default: return -1;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = e.getAction() == KeyEvent.ACTION_UP;

        if (menuOpen) return super.dispatchKeyEvent(e);

        if (inGame) {
            // Back on the remote, or the Home/Guide button on a controller, opens the pause menu
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_MODE
                    || code == KeyEvent.KEYCODE_MENU) {
                if (up) showPauseMenu();
                return true;
            }
            int btn = mapButton(code);
            if (btn >= 0) {
                if (e.getRepeatCount() == 0 && (down || up)) press(btn, down);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        // Library screen: controller A / remote OK = select, controller B / remote Back = back
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
        if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
            // jump a page in the library
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

        if (!inGame) {
            // let the stick / hat move around the library
            int key = -1;
            if (hatX > 0.5f || lx > 0.6f) key = KeyEvent.KEYCODE_DPAD_RIGHT;
            else if (hatX < -0.5f || lx < -0.6f) key = KeyEvent.KEYCODE_DPAD_LEFT;
            else if (hatY > 0.5f || ly > 0.6f) key = KeyEvent.KEYCODE_DPAD_DOWN;
            else if (hatY < -0.5f || ly < -0.6f) key = KeyEvent.KEYCODE_DPAD_UP;
            if (key != lastAxis[0]) {
                lastAxis[0] = key;
                if (key != -1) {
                    web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
                    web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
                }
            }
            return true;
        }
        if (menuOpen) return true;

        // D-pad from the hat (and from the left stick on systems without analog sticks)
        boolean useStickAsDpad = !analogCore;
        sendDigital(UP, hatY < -0.5f || (useStickAsDpad && ly < -0.5f));
        sendDigital(DOWN, hatY > 0.5f || (useStickAsDpad && ly > 0.5f));
        sendDigital(LEFT, hatX < -0.5f || (useStickAsDpad && lx < -0.5f));
        sendDigital(RIGHT, hatX > 0.5f || (useStickAsDpad && lx > 0.5f));
        sendDigital(L2, lt > 0.5f);
        sendDigital(R2, rt > 0.5f);

        if (analogCore) {
            sendAnalog(LS_RIGHT, lx > 0 ? lx : 0);
            sendAnalog(LS_LEFT, lx < 0 ? -lx : 0);
            sendAnalog(LS_DOWN, ly > 0 ? ly : 0);
            sendAnalog(LS_UP, ly < 0 ? -ly : 0);
            sendAnalog(RS_RIGHT, rx > 0 ? rx : 0);
            sendAnalog(RS_LEFT, rx < 0 ? -rx : 0);
            sendAnalog(RS_DOWN, ry > 0 ? ry : 0);
            sendAnalog(RS_UP, ry < 0 ? -ry : 0);
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

    // separate memory for hat/stick-driven digital presses (slots 0..15 in lastAxis reused per button)
    private final boolean[] axisPressed = new boolean[16];

    private void sendDigital(int button, boolean pressed) {
        if (axisPressed[button] != pressed) {
            axisPressed[button] = pressed;
            press(button, pressed);
        }
    }

    private void sendAnalog(int slot, float amount) {
        int v = Math.round(Math.min(1f, amount) * 32767);
        if (Math.abs(v - lastAxis[slot]) > 600 || (v == 0 && lastAxis[slot] != 0)) {
            lastAxis[slot] = v;
            pressAnalog(slot, v);
        }
    }

    private void releaseAll() {
        for (int b = 0; b < 16; b++) { press(b, false); axisPressed[b] = false; }
        for (int a = LS_RIGHT; a <= RS_UP; a++) { pressAnalog(a, 0); lastAxis[a] = 0; }
    }

    private void showPauseMenu() {
        menuOpen = true;
        releaseAll();
        js("window.canoePause && canoePause(true)");
        final String[] items = {"Resume", "Save game (quick save)", "Load quick save", "Picture: change look", "Restart game", "Quit to library"};
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Paused")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 1: js("canoeQuickSave()"); break;
                            case 2: js("canoeQuickLoad()"); break;
                            case 3: js("canoeNextPicture()"); break;
                            case 4: js("canoeRestart()"); break;
                            case 5: js("canoeQuit()"); break;
                            default: break;
                        }
                    }
                })
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                menuOpen = false;
                js("window.canoePause && canoePause(false)");
            }
        });
        dialog.show();
    }
}
