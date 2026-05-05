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
 * MainActivity — PvP Manager (v7 — single-WebView architecture)
 *
 * ROOT CAUSE OF THE "DISCONNECTED" BUG (v1-v6):
 *   Two WebViews were used: loginWebView (user logs in) + gameWebView (bot).
 *   Cookie transfer between two WebViews is unreliable — even though
 *   CookieManager is a singleton, race conditions and session-redirect
 *   timing caused pvp.php to redirect back to /signin after "connecting",
 *   and subsequent verifySession() calls then cleared the session.
 *
 * THE FIX (matches friend's working APK architecture exactly):
 *   ONE WebView (gameWebView) is used for BOTH login AND game.
 *   - In "login mode": gameWebView expands fullscreen so the user can browse
 *     demonicscans.org and log in on the real site.
 *   - Session is AUTOMATICALLY detected when PHPSESSID cookie appears for
 *     demonicscans.org after a non-auth page loads — NO "Save & Connect"
 *     button required (user just logs in and the app detects it).
 *   - After auto-detect: gameWebView navigates to pvp.php on the SAME instance.
 *     It already has the PHPSESSID in its own cookie jar — no transfer needed.
 *   - A manual "CONNECT NOW" button is available as a fallback.
 *
 * Session check mirrors the working Expo app's SessionManager.isLoggedIn():
 *   Positive:  PHPSESSID (or user_id / auth / session) cookie present.
 *   Negative:  gameWebView URL is an explicit auth page (/signin /login etc).
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG      = "PvPManager";
    private static final String PVP_URL  = "https://demonicscans.org/pvp.php";
    private static final String HOME_URL = "https://demonicscans.org";

    /** Shared with PvpService */
    public static WebView gameWebView;
    public static WebView uiWebView;

    private FrameLayout   root;
    private FrameLayout   loginOverlay;    // nav bar only
    private TextView      btnConnect;      // manual connect fallback
    private AndroidBridge bridge;

    /** True while the login overlay is showing (gameWebView is fullscreen) */
    private boolean loginMode = false;

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
        // Normally 0×0 (hidden). During login it expands to fullscreen so the
        // user can browse and log in. After login it shrinks back and runs
        // the bot on pvp.php invisibly. ONE WebView — no cookie transfer issue.
        gameWebView = new WebView(this);
        gameWebView.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
        applyWebViewSettings(gameWebView, false);

        gameWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) { return false; }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                if (bridge != null) bridge.appendLog("debug", "page: " + url);

                if (loginMode) {
                    // In login mode — check if user has just logged in
                    autoDetectLogin(url);
                } else {
                    // Normal bot mode — verify session is still alive
                    verifySession(url);
                    injectBotScript(view);
                }
            }
        });
        root.addView(gameWebView);

        // ── 2. uiWebView ──────────────────────────────────────────────────────
        uiWebView = new WebView(this);
        uiWebView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        applyWebViewSettings(uiWebView, true);

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
        // Just the nav bar — gameWebView IS the login browser shown fullscreen.
        loginOverlay = new FrameLayout(this);
        loginOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        loginOverlay.setVisibility(View.GONE);
        loginOverlay.addView(buildNavBar());
        root.addView(loginOverlay);

        setContentView(root);

        // Load pvp.php at startup. If the user has a saved PHPSESSID cookie
        // from a previous session, verifySession() will auto-connect.
        // If they don't, it redirects to /signin (expected — Disconnected state).
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

    // ── Nav bar ───────────────────────────────────────────────────────────────

    private LinearLayout buildNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#1a1025"));
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));

        // ← BACK TO APP
        TextView back = pill("← BACK TO APP", "#2d2040");
        back.setOnClickListener(v -> closeLogin());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bp.setMargins(0, 0, dp(6), 0);
        bar.addView(back, bp);

        // ⟳ RELOAD
        TextView reload = pill("⟳ RELOAD", "#1565C0");
        reload.setOnClickListener(v -> { if (gameWebView != null) gameWebView.reload(); });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f);
        rp.setMargins(0, 0, dp(6), 0);
        bar.addView(reload, rp);

        // CONNECT NOW (manual fallback — auto-detect handles most cases)
        btnConnect = pill("CONNECT NOW", "#00897B");
        btnConnect.setOnClickListener(v -> manualConnect());
        bar.addView(btnConnect,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f));

        return bar;
    }

    // ── Open login (called by AndroidBridge.openLogin() via @JavascriptInterface)

    /**
     * Expands gameWebView to fullscreen so the user can log in.
     *
     * This is the same pattern as the friend's working APK:
     *   Android.openLogin() → show WebView → user logs in → auto-detect
     *
     * Unlike v1-v6 there is NO separate loginWebView. The gameWebView IS
     * the login browser. After login the SAME WebView navigates to pvp.php.
     */
    public void showLogin() {
        if (loginMode) return;
        loginMode = true;
        setBtnState(State.IDLE);

        // Expand gameWebView to fill screen
        gameWebView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        gameWebView.bringToFront();
        loginOverlay.bringToFront();   // nav bar on top
        loginOverlay.setVisibility(View.VISIBLE);
        gameWebView.requestFocus();

        // Navigate to homepage if not already on a useful site page
        String cur = gameWebView.getUrl();
        boolean usable = cur != null
                && cur.contains("demonicscans.org")
                && !cur.contains("pvp.php")
                && !cur.contains("pvp_battle");
        if (!usable) {
            gameWebView.loadUrl(HOME_URL);
        } else {
            // Already on a site page — check if we can auto-detect immediately
            autoDetectLogin(cur);
        }

        if (bridge != null) bridge.appendLog("system", "Login overlay opened");
    }

    // ── Auto-detect login (called from onPageFinished while in login mode) ──

    /**
     * Called every time gameWebView finishes loading a page while in login mode.
     *
     * Detection logic (matches Expo SessionManager.isLoggedIn() exactly):
     *   1. URL is on demonicscans.org and NOT an auth page (signin/login/register)
     *   2. PHPSESSID cookie is present for demonicscans.org
     *
     * When both conditions are true: auto-connect (no button tap needed).
     * This is how the friend's working APK behaves.
     */
    private void autoDetectLogin(String url) {
        if (bridge == null) return;
        if (bridge.isSessionVerified()) {
            // Already connected — update button hint and return
            setBtnState(State.SUCCESS);
            return;
        }

        boolean onSite = url != null && url.contains("demonicscans.org");
        boolean onAuthPage = url != null && (
                url.contains("/signin") || url.contains("/login") ||
                url.contains("/register") || url.contains("/signup"));

        if (!onSite || onAuthPage) {
            setBtnState(State.IDLE);
            return;
        }

        // On a non-auth site page — check for PHPSESSID
        CookieManager.getInstance().flush();
        String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
        boolean hasPHPSESSID = hasPHPSESSID(cookies);

        if (hasPHPSESSID) {
            // ── AUTO-CONNECT ──────────────────────────────────────────────────
            // User is logged in on the SAME WebView. Navigate to pvp.php.
            // The PHPSESSID is already in this WebView's cookie jar — server
            // will see the session. No cookie transfer between WebViews needed.
            bridge.appendLog("system", "✅ PHPSESSID detected — auto-connecting…");
            bridge.setConnected(true);   // prefs + grace period
            setBtnState(State.SUCCESS);

            loginMode = false;   // switch to normal mode BEFORE the navigation
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (gameWebView != null) gameWebView.loadUrl(PVP_URL);
            }, 200);

            // Refresh the UI
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (uiWebView != null)
                    uiWebView.evaluateJavascript(
                            "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
                // Close overlay after brief delay so user sees "✓ CONNECTED"
                new Handler(Looper.getMainLooper()).postDelayed(this::closeLogin, 1200);
            }, 400);
        } else {
            // Not yet logged in — show hint on button
            setBtnState(State.IDLE);
        }
    }

    // ── Manual connect (CONNECT NOW button fallback) ──────────────────────────

    /**
     * Manual fallback if auto-detect didn't fire.
     * Same check as autoDetectLogin but triggered by the button.
     */
    public void manualConnect() {
        if (bridge == null) return;
        CookieManager.getInstance().flush();

        String url     = gameWebView.getUrl();
        String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");

        bridge.appendLog("debug", "manualConnect: url=" + url);
        bridge.appendLog("debug", "cookies=" + (cookies == null ? "null"
                : cookies.substring(0, Math.min(160, cookies.length()))));

        boolean hasPHPSESSID = hasPHPSESSID(cookies);
        boolean urlOk = url != null
                && url.contains("demonicscans.org")
                && !url.contains("signin")
                && !url.contains("login")
                && !url.contains("register")
                && !url.equals("about:blank");

        if (!hasPHPSESSID && !urlOk) {
            setBtnState(State.FAILURE);
            Toast.makeText(this,
                    "Please log in on the site first",
                    Toast.LENGTH_LONG).show();
            return;
        }

        bridge.appendLog("system", "Manual connect — navigating to pvp.php…");
        bridge.setConnected(true);
        setBtnState(State.SUCCESS);

        loginMode = false;   // switch to normal mode before navigation
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (gameWebView != null) gameWebView.loadUrl(PVP_URL);
        }, 200);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null)
                uiWebView.evaluateJavascript(
                        "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            new Handler(Looper.getMainLooper()).postDelayed(this::closeLogin, 1200);
        }, 400);
    }

    // ── Close login overlay ───────────────────────────────────────────────────

    private void closeLogin() {
        loginMode = false;
        gameWebView.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
        loginOverlay.setVisibility(View.GONE);
        uiWebView.requestFocus();
        setBtnState(State.IDLE);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null)
                uiWebView.evaluateJavascript(
                        "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
        }, 200);
    }

    // ── Session verification (normal / non-login mode only) ───────────────────

    /**
     * Called from gameWebView.onPageFinished when NOT in login mode.
     *
     * Mirrors Expo's SessionManager.isLoggedIn():
     *   PHPSESSID present on demonicscans.org → Connected
     *   Explicit auth-page redirect          → Disconnected
     *   Grace period active                  → skip (don't touch session)
     */
    private void verifySession(String url) {
        if (bridge == null) return;

        // Grace period: setConnected(true) was just called — wait for pvp.php to settle
        if (bridge.inGracePeriod()) {
            bridge.appendLog("debug", "Grace period — skip verify: " + url);
            return;
        }

        boolean onAuthPage = url != null && (
                url.contains("/signin") || url.contains("/login") ||
                url.contains("/register") || url.contains("/signup"));
        if (onAuthPage) {
            if (bridge.isSessionVerified()) {
                bridge.appendLog("warning", "Auth redirect — session cleared: " + url);
                bridge.setConnected(false);
            }
            return;
        }

        boolean onSite = url != null && url.contains("demonicscans.org");
        if (onSite) {
            CookieManager.getInstance().flush();
            String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
            boolean hasSession = hasPHPSESSID(cookies);

            if (hasSession && !bridge.isSessionVerified()) {
                bridge.appendLog("system", "✅ Auto-connected from saved PHPSESSID");
                bridge.setConnected(true);
            } else if (!hasSession && bridge.isSessionVerified()) {
                bridge.appendLog("debug", "Session cookie gone — disconnected");
                bridge.setConnected(false);
            }
        }
    }

    // ── Cookie check ──────────────────────────────────────────────────────────

    /** PHPSESSID or equivalent session marker — same check as Expo's isLoggedIn() */
    private boolean hasPHPSESSID(String cookies) {
        if (cookies == null || cookies.trim().isEmpty()) return false;
        return cookies.contains("PHPSESSID")
                || cookies.contains("user_id")
                || cookies.contains("auth")
                || cookies.contains("session");
    }

    // ── Bot script injection ──────────────────────────────────────────────────

    public void injectBotScript(WebView view) {
        try {
            InputStream is = getAssets().open("pvp_manager.js");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            view.evaluateJavascript(
                    "(function(){\n" + new String(buf, StandardCharsets.UTF_8) + "\n})();", null);
        } catch (IOException e) {
            Log.e(TAG, "injectBotScript: " + e.getMessage());
        }
    }

    // ── Button state ──────────────────────────────────────────────────────────

    private enum State { IDLE, SUCCESS, FAILURE }

    private void setBtnState(State s) {
        if (btnConnect == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        switch (s) {
            case SUCCESS:
                bg.setColor(Color.parseColor("#2E7D32"));
                btnConnect.setText("✓ CONNECTED");
                btnConnect.setEnabled(false);
                break;
            case FAILURE:
                bg.setColor(Color.parseColor("#B71C1C"));
                btnConnect.setText("✗ NOT LOGGED IN");
                btnConnect.setEnabled(true);
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> setBtnState(State.IDLE), 2500);
                break;
            default:
                bg.setColor(Color.parseColor("#00897B"));
                btnConnect.setText("CONNECT NOW");
                btnConnect.setEnabled(true);
                break;
        }
        btnConnect.setBackground(bg);
    }

    // ── WebView settings ──────────────────────────────────────────────────────

    private void applyWebViewSettings(WebView wv, boolean fileAccess) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccessFromFileURLs(fileAccess);
        s.setAllowUniversalAccessFromFileURLs(fileAccess);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

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
