package com.pvpmanager;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * MainActivity — PvP Manager
 *
 * KEY ARCHITECTURAL FIX (v6):
 *
 * Previous versions (v1-v5) used TWO WebViews:
 *   loginWebView  → user logs in here
 *   gameWebView   → bot runs here (hidden, 0x0)
 *
 * Cookie sharing between two WebViews was unreliable — even though
 * CookieManager is a singleton, timing and PHPSESSID scoping caused
 * pvp.php to redirect back to /signin after "connecting".
 *
 * The Expo/React-Native version that works uses ONE WebView:
 *   - User logs in on demonicscans.org in the WebView
 *   - SAME WebView then navigates to pvp.php
 *   - No cookie transfer needed — it was always the same WebView
 *
 * We replicate that here:
 *   gameWebView is shown fullscreen during login (becomes the login browser)
 *   After Save & Connect, the SAME gameWebView navigates to pvp.php
 *   Then gameWebView shrinks back to 0x0 and runs the bot invisibly
 *
 * Session detection uses PHPSESSID — same check as Expo's SessionManager.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG      = "PvPManager";
    private static final String PVP_URL  = "https://demonicscans.org/pvp.php";
    private static final String HOME_URL = "https://demonicscans.org";

    public static WebView gameWebView;  // shared with PvpService
    public static WebView uiWebView;

    private FrameLayout   root;
    private FrameLayout   loginOverlay;   // nav bar only; gameWebView IS the login page
    private TextView      btnSaveConnect;
    private AndroidBridge bridge;

    private boolean loginMode = false;   // true while overlay is open

    private ActivityResultLauncher<String> notifPermLauncher;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);

        notifPermLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> Log.d(TAG, "POST_NOTIFICATIONS granted=" + granted));

        root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ── 1. gameWebView ────────────────────────────────────────────────────
        // Normally hidden (0×0). During login it expands to full screen so the
        // user can browse demonicscans.org and log in. After login it shrinks
        // back and runs pvp.php invisibly. ONE WebView — no cookie transfer.
        gameWebView = new WebView(this);
        gameWebView.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
        configureWebView(gameWebView);

        gameWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) { return false; }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                if (bridge != null) bridge.appendLog("debug", "gameWebView: " + url);

                if (loginMode) {
                    // In login mode: hint on the button based on current URL + cookies
                    updateConnectButtonHint(url);
                } else {
                    // Normal mode: verify session and inject bot script
                    verifySession(url);
                    injectScript(view);
                }
            }
        });
        root.addView(gameWebView);

        // ── 2. uiWebView ──────────────────────────────────────────────────────
        uiWebView = new WebView(this);
        uiWebView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        configureWebView(uiWebView);
        uiWebView.getSettings().setAllowFileAccessFromFileURLs(true);
        uiWebView.getSettings().setAllowUniversalAccessFromFileURLs(true);

        bridge = new AndroidBridge(this, gameWebView, uiWebView);
        uiWebView.addJavascriptInterface(bridge, "Android");
        uiWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) { return false; }
        });
        uiWebView.loadUrl("file:///android_asset/main.html");
        root.addView(uiWebView);

        // ── 3. Login overlay nav bar ──────────────────────────────────────────
        // This is just the nav bar (BACK / RELOAD / SAVE & CONNECT buttons).
        // The actual "login page" is the gameWebView shown fullscreen beneath it.
        loginOverlay = new FrameLayout(this);
        loginOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        loginOverlay.setVisibility(View.GONE);
        loginOverlay.addView(buildNavBar());
        root.addView(loginOverlay);

        setContentView(root);

        // Load pvp.php at startup. If PHPSESSID is already in CookieManager
        // (user previously logged in), verifySession() will auto-connect.
        gameWebView.loadUrl(PVP_URL);

        Intent svc = new Intent(this, PvpService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);

        requestRuntimePermissions();
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    startActivity(new Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
                }
            } catch (Exception e) {
                Log.w(TAG, "Battery opt: " + e.getMessage());
            }
        }, 3000);
    }

    // ── Login nav bar ─────────────────────────────────────────────────────────

    private LinearLayout buildNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#1a1025"));
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));

        // ← BACK TO APP — always closes the overlay
        TextView back = pill("← BACK TO APP", "#2d2040");
        back.setOnClickListener(v -> closeLogin());
        LinearLayout.LayoutParams backP = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        backP.setMargins(0, 0, dp(6), 0);
        bar.addView(back, backP);

        // ⟳ RELOAD
        TextView reload = pill("⟳ RELOAD", "#1565C0");
        reload.setOnClickListener(v -> { if (gameWebView != null) gameWebView.reload(); });
        LinearLayout.LayoutParams reloadP = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f);
        reloadP.setMargins(0, 0, dp(6), 0);
        bar.addView(reload, reloadP);

        // SAVE & CONNECT
        btnSaveConnect = pill("SAVE & CONNECT", "#00897B");
        btnSaveConnect.setOnClickListener(v -> saveConnect());
        bar.addView(btnSaveConnect,
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f));

        return bar;
    }

    // ── Show login (called by AndroidBridge.openLogin()) ──────────────────────

    /**
     * Opens the login overlay by expanding gameWebView to full screen.
     *
     * This replicates the Expo app's single-WebView approach:
     *   - gameWebView shows demonicscans.org (login page)
     *   - After login, SAME gameWebView navigates to pvp.php
     *   - No second WebView → no cookie-sharing problem
     */
    public void showLogin() {
        if (loginMode) return;
        loginMode = true;

        setBtnState(BtnState.IDLE);

        // Expand gameWebView to fill the screen (it becomes the login browser)
        gameWebView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        gameWebView.bringToFront();
        loginOverlay.bringToFront();   // nav bar on top of gameWebView
        loginOverlay.setVisibility(View.VISIBLE);
        gameWebView.requestFocus();

        // Navigate to homepage so user can log in, unless already there
        String cur = gameWebView.getUrl();
        boolean alreadyOnSiteNotPvp = cur != null
                && cur.contains("demonicscans.org")
                && !cur.contains("pvp.php")
                && !cur.contains("signin")
                && !cur.contains("login");
        if (!alreadyOnSiteNotPvp) {
            gameWebView.loadUrl(HOME_URL);
        } else {
            updateConnectButtonHint(cur);
        }

        if (bridge != null) bridge.appendLog("system", "Login overlay opened");
    }

    // ── Save & Connect ────────────────────────────────────────────────────────

    /**
     * User tapped Save & Connect.
     *
     * At this point gameWebView is showing demonicscans.org. We check for
     * PHPSESSID — the same check as Expo's SessionManager.isLoggedIn().
     *
     * On success: navigate the SAME gameWebView to pvp.php.
     * The WebView already holds the PHPSESSID — server recognises the session.
     * Overlay stays open; user taps "← BACK TO APP" when ready.
     */
    public void saveConnect() {
        if (bridge == null) return;

        CookieManager.getInstance().flush();
        String url     = gameWebView.getUrl();
        String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");

        bridge.appendLog("debug", "saveConnect url=" + url);
        bridge.appendLog("debug", "cookies=" + (cookies == null ? "null"
                : cookies.substring(0, Math.min(160, cookies.length()))));

        // Primary: PHPSESSID or equivalent session cookie (Expo's exact check)
        boolean hasSession = cookies != null && (
                cookies.contains("PHPSESSID") ||
                cookies.contains("user_id")   ||
                cookies.contains("auth")      ||
                cookies.contains("session"));

        // Secondary: URL on-site and not on an auth page
        boolean urlOk = url != null
                && url.contains("demonicscans.org")
                && !url.contains("signin")
                && !url.contains("login")
                && !url.contains("register")
                && !url.equals("about:blank");

        if (!hasSession && !urlOk) {
            setBtnState(BtnState.FAILURE);
            Toast.makeText(this,
                "Please log in on the site first, then tap Save & Connect",
                Toast.LENGTH_LONG).show();
            return;
        }

        // ── Success ───────────────────────────────────────────────────────────
        bridge.setConnected(true);     // sets prefs + starts 12s grace period
        setBtnState(BtnState.SUCCESS); // button shows ✓ CONNECTED, overlay stays open

        bridge.appendLog("system",
            "Session confirmed (PHPSESSID present) — navigating gameWebView to pvp.php…");

        // Navigate the SAME gameWebView (which just logged in) to pvp.php.
        // The PHPSESSID cookie is already in this WebView's jar — the server
        // will recognise the session without any cookie copying.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (gameWebView != null) {
                loginMode = false;   // switch to normal mode BEFORE the load
                                     // so onPageFinished calls verifySession()
                gameWebView.loadUrl(PVP_URL);
            }
        }, 300);

        // Refresh the UI immediately so the status shows Connected
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null)
                uiWebView.evaluateJavascript(
                    "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
        }, 600);
    }

    // ── Close overlay ─────────────────────────────────────────────────────────

    private void closeLogin() {
        loginMode = false;

        // Shrink gameWebView back to hidden (0×0)
        gameWebView.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
        loginOverlay.setVisibility(View.GONE);
        uiWebView.requestFocus();
        setBtnState(BtnState.IDLE);

        // Refresh UI
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null)
                uiWebView.evaluateJavascript(
                    "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
        }, 200);
    }

    // ── Button state ──────────────────────────────────────────────────────────

    private enum BtnState { IDLE, SUCCESS, FAILURE }

    private void setBtnState(BtnState state) {
        if (btnSaveConnect == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        switch (state) {
            case SUCCESS:
                bg.setColor(Color.parseColor("#2E7D32"));
                btnSaveConnect.setText("✓ CONNECTED");
                btnSaveConnect.setEnabled(false);
                break;
            case FAILURE:
                bg.setColor(Color.parseColor("#B71C1C"));
                btnSaveConnect.setText("✗ NOT LOGGED IN");
                btnSaveConnect.setEnabled(true);
                new Handler(Looper.getMainLooper()).postDelayed(
                    () -> setBtnState(BtnState.IDLE), 2500);
                break;
            default: // IDLE
                bg.setColor(Color.parseColor("#00897B"));
                btnSaveConnect.setText("SAVE & CONNECT");
                btnSaveConnect.setEnabled(true);
                break;
        }
        btnSaveConnect.setBackground(bg);
    }

    /**
     * Called from onPageFinished while in login mode.
     * Updates the button to hint that the site looks authenticated —
     * does NOT change the actual session state.
     */
    private void updateConnectButtonHint(String url) {
        if (bridge != null && bridge.isSessionVerified()) return;
        boolean looksLoggedIn = url != null
                && url.contains("demonicscans.org")
                && !url.contains("signin")
                && !url.contains("login")
                && !url.contains("register")
                && !url.equals("about:blank");

        if (!looksLoggedIn) { setBtnState(BtnState.IDLE); return; }

        CookieManager.getInstance().flush();
        String c = CookieManager.getInstance().getCookie("https://demonicscans.org");
        boolean hasPHPSESSID = c != null && (
                c.contains("PHPSESSID") || c.contains("user_id") ||
                c.contains("auth")      || c.contains("session"));

        if (hasPHPSESSID && btnSaveConnect != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#1B5E20"));
            bg.setCornerRadius(dp(24));
            btnSaveConnect.setBackground(bg);
            btnSaveConnect.setText("TAP TO CONNECT ✓");
            btnSaveConnect.setEnabled(true);
        } else {
            setBtnState(BtnState.IDLE);
        }
    }

    // ── Session verification (normal / non-login mode) ────────────────────────

    /**
     * Called from gameWebView.onPageFinished when NOT in login mode.
     *
     * Matches Expo's SessionManager.isLoggedIn() logic exactly:
     *   PHPSESSID present on demonicscans.org → Connected
     *   Redirected to /signin /login /register → Disconnected
     *   Grace period active (12s after saveConnect) → do nothing
     */
    private void verifySession(String url) {
        if (bridge == null) return;

        // During grace period leave the session alone — gameWebView is still
        // navigating from the login page to pvp.php
        if (bridge.inGracePeriod()) {
            bridge.appendLog("debug", "Grace period active — skip verify: " + url);
            return;
        }

        // Explicit auth-page redirect = definite sign-out
        boolean onAuthPage = url != null && (
                url.contains("/signin") || url.contains("/login") ||
                url.contains("/register") || url.contains("/signup"));
        if (onAuthPage) {
            if (bridge.isSessionVerified()) {
                bridge.appendLog("warning", "Auth redirect — clearing session: " + url);
                bridge.setConnected(false);
            }
            return;
        }

        // Positive: on-site + PHPSESSID (Expo's isLoggedIn() check)
        boolean onSite = url != null && url.contains("demonicscans.org");
        if (onSite) {
            CookieManager.getInstance().flush();
            String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
            boolean hasSession = cookies != null && (
                    cookies.contains("PHPSESSID") ||
                    cookies.contains("user_id")   ||
                    cookies.contains("auth")      ||
                    cookies.contains("session"));

            if (hasSession && !bridge.isSessionVerified()) {
                bridge.appendLog("system", "✅ PHPSESSID found — auto-connected");
                bridge.setConnected(true);
            } else if (!hasSession && bridge.isSessionVerified()) {
                bridge.appendLog("debug", "Session cookie gone — disconnected");
                bridge.setConnected(false);
            }
        }
    }

    // ── Script injection ──────────────────────────────────────────────────────

    public void injectScript(WebView view) {
        try {
            InputStream is = getAssets().open("pvp_manager.js");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            view.evaluateJavascript(
                "(function(){\n" + new String(buf, StandardCharsets.UTF_8) + "\n})();", null);
        } catch (IOException e) {
            Log.e(TAG, "injectScript: " + e.getMessage());
        }
    }

    // ── WebView configuration ─────────────────────────────────────────────────

    private void configureWebView(WebView wv) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    private TextView pill(String text, String hexColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(hexColor));
        bg.setCornerRadius(dp(24));
        tv.setBackground(bg);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (loginMode) { closeLogin(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gameWebView != null) { gameWebView.destroy(); gameWebView = null; }
        if (uiWebView   != null) { uiWebView.destroy();   uiWebView   = null; }
    }
}
