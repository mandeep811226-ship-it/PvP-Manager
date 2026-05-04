package com.pvpmanager;

import android.content.Intent;
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

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    public static WebView gameWebView;
    public static WebView uiWebView;

    private WebView loginWebView;
    private FrameLayout loginContainer;
    private LinearLayout loginNavBar;
    private TextView btnSaveConnect;
    private AndroidBridge bridge;

    // FIX #4: Cache cookies captured during the last loginWebView page-finish so that
    // saveConnect() can read them synchronously without a race against flush().
    private volatile String lastCapturedCookies = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);

        // Global cookie manager setup (must be done before any WebView is created)
        CookieManager.getInstance().setAcceptCookie(true);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ── Game WebView (hidden, drives bot logic) ──────────────────────────
        gameWebView = new WebView(this);
        gameWebView.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
        applyWebViewSettings(gameWebView, false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(gameWebView, true);

        gameWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return false; }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) { return false; }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                if (bridge != null) bridge.appendLog("debug", "gameWebView loaded: " + url);

                // FIX #3: Use final URL to detect auth instead of fragile DOM selectors.
                // If the game page loaded (not redirected to a sign-in page), the user is authenticated.
                if (url != null && url.contains("demonicscans.org")
                        && !url.contains("signin") && !url.contains("login")
                        && !url.contains("register") && !url.contains("signup")) {
                    // Confirm with a lightweight DOM check; fall back to URL-based trust.
                    verifyGameWebViewSession(url);
                } else if (url != null && (url.contains("signin") || url.contains("login"))) {
                    if (bridge != null) {
                        bridge.appendLog("system", "Game WebView redirected to login — session invalid");
                        bridge.setConnected(false);
                    }
                }

                injectUserScript(view);
            }
        });
        root.addView(gameWebView);

        // ── UI WebView (the control panel) ───────────────────────────────────
        uiWebView = new WebView(this);
        uiWebView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        applyWebViewSettings(uiWebView, true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(uiWebView, true);

        bridge = new AndroidBridge(this, gameWebView, uiWebView);
        uiWebView.addJavascriptInterface(bridge, "Android");
        uiWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return false; }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) { return false; }
        });
        uiWebView.loadUrl("file:///android_asset/main.html");
        root.addView(uiWebView);

        // ── Login overlay ─────────────────────────────────────────────────────
        loginContainer = new FrameLayout(this);
        loginContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        loginContainer.setVisibility(View.INVISIBLE);

        loginNavBar = buildLoginNavBar();
        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        navParams.gravity = Gravity.TOP;
        loginContainer.addView(loginNavBar, navParams);

        loginWebView = new WebView(this);
        int navHeightPx = (int) (56 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams wvParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        wvParams.topMargin = navHeightPx;
        applyWebViewSettings(loginWebView, false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(loginWebView, true);

        loginWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleLoginUrl(request.getUrl().toString());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleLoginUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // FIX #4: Flush and cache cookies immediately after each page load.
                // By the time onPageFinished fires, the HTTP response (including Set-Cookie
                // headers) has been fully processed by the WebView engine, so flush() here
                // is reliable. We cache the result so saveConnect() never races the engine.
                CookieManager.getInstance().flush();
                String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
                if (cookies != null && cookies.length() >= 10) {
                    lastCapturedCookies = cookies;
                    Log.d("PvPManager", "Cookies cached in onPageFinished, length=" + cookies.length());
                }

                if (bridge != null) bridge.appendLog("debug", "loginWebView loaded: " + url);
                updateSaveConnectLabel(url);
            }
        });

        loginWebView.loadUrl("about:blank");
        loginContainer.addView(loginWebView, wvParams);
        root.addView(loginContainer);
        setContentView(root);

        // Load the game page after everything is wired up
        gameWebView.loadUrl("https://demonicscans.org/pvp.php");

        Intent serviceIntent = new Intent(this, PvpService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private LinearLayout buildLoginNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#1a1025"));
        int padH = dp(12), padV = dp(8);
        bar.setPadding(padH, padV, padH, padV);

        TextView back = pillButton("BACK", Color.parseColor("#2d2040"));
        back.setOnClickListener(v -> {
            if (loginWebView != null && loginWebView.canGoBack()) loginWebView.goBack();
            else closeLoginOverlay();
        });
        LinearLayout.LayoutParams backP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        backP.setMargins(0, 0, dp(6), 0);
        bar.addView(back, backP);

        TextView reload = pillButton("RELOAD", Color.parseColor("#1565C0"));
        reload.setOnClickListener(v -> { if (loginWebView != null) loginWebView.reload(); });
        LinearLayout.LayoutParams reloadP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        reloadP.setMargins(0, 0, dp(6), 0);
        bar.addView(reload, reloadP);

        btnSaveConnect = pillButton("SAVE CONNECT", Color.parseColor("#00897B"));
        btnSaveConnect.setOnClickListener(v -> {
            Log.d("PvPManager", "SAVE CONNECT button clicked");
            saveConnect();
        });
        LinearLayout.LayoutParams saveP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f);
        bar.addView(btnSaveConnect, saveP);

        return bar;
    }

    private TextView pillButton(String text, int bgColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(24));
        tv.setBackground(bg);
        return tv;
    }

    private int dp(int val) { return (int) (val * getResources().getDisplayMetrics().density); }

    private void updateSaveConnectLabel(String url) {
        if (btnSaveConnect == null) return;
        boolean looksLoggedIn = url != null && url.contains("demonicscans.org")
                && !url.contains("signin") && !url.contains("login") && !url.contains("register");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        if (looksLoggedIn) {
            bg.setColor(Color.parseColor("#2E7D32"));
            btnSaveConnect.setText("SAVE CONNECT \u2713");
        } else {
            bg.setColor(Color.parseColor("#00897B"));
            btnSaveConnect.setText("SAVE CONNECT");
        }
        btnSaveConnect.setBackground(bg);
    }

    public void showLogin() {
        if (loginWebView == null || loginContainer == null) return;

        // FIX #1: The overlay was set to INVISIBLE before loadUrl(), hiding it during
        // loading. Show it FIRST so the user sees the page as it loads (no white screen).
        loginContainer.setVisibility(View.VISIBLE);
        loginWebView.requestFocus();

        // Only navigate if the WebView is blank; avoids re-loading a page in progress.
        String currentUrl = loginWebView.getUrl();
        if (currentUrl == null || currentUrl.equals("about:blank") || currentUrl.isEmpty()) {
            loginWebView.loadUrl("https://demonicscans.org");
        }

        if (bridge != null) bridge.appendLog("system", "Login overlay opened");
    }

    public void saveConnect() {
        Toast.makeText(this, "SAVE CONNECT pressed", Toast.LENGTH_SHORT).show();
        Log.d("PvPManager", "saveConnect() started");

        if (bridge == null) {
            Log.e("PvPManager", "bridge is null");
            Toast.makeText(this, "Bridge not ready", Toast.LENGTH_LONG).show();
            return;
        }

        bridge.appendLog("system", "SAVE CONNECT clicked");

        // FIX #4: Prefer the cookies captured during the last onPageFinished (reliable),
        // but also do a live flush+read as a secondary attempt.
        String cookies = lastCapturedCookies;
        if (cookies == null || cookies.length() < 10) {
            CookieManager.getInstance().flush();
            cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
        }

        int cookieLen = (cookies == null ? 0 : cookies.length());
        bridge.appendLog("debug", "Cookies at SAVE CONNECT, length=" + cookieLen);
        Log.d("PvPManager", "Cookies length: " + cookieLen);

        if (cookieLen < 10) {
            bridge.appendLog("error", "No valid session cookies captured (length=" + cookieLen + "). " +
                    "Make sure you have completed login before pressing SAVE CONNECT.");
            Toast.makeText(this, "No valid cookies — please finish logging in first", Toast.LENGTH_LONG).show();
            return;
        }

        // Close the login overlay
        loginContainer.setVisibility(View.INVISIBLE);
        if (loginWebView != null) loginWebView.loadUrl("about:blank");
        lastCapturedCookies = null; // consumed

        bridge.appendLog("system", "Cookies captured. Reloading game WebView...");

        // FIX #3: Give the game WebView more time (full page load + auth redirect).
        // The gameWebView.onPageFinished handler now does the verification; we just reload.
        if (gameWebView != null) {
            gameWebView.reload();
        }

        // Refresh the UI after a short delay; the real connected state will be set by
        // onPageFinished → verifyGameWebViewSession() when the page finishes loading.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null) {
                uiWebView.evaluateJavascript("if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            }
        }, 500);
    }

    /**
     * FIX #3: Session verification rewritten.
     * - Tries a DOM check against several candidate selectors.
     * - If DOM check is inconclusive, falls back to URL-based trust: if the game page
     *   loaded without being redirected to a sign-in URL, the session is valid.
     * - Never called at sub-second intervals (only from onPageFinished).
     */
    private void verifyGameWebViewSession(String finalUrl) {
        if (gameWebView == null || bridge == null) return;

        // URL-based fallback: if the page is the game page (not a login redirect), trust it.
        boolean urlLooksAuthenticated = finalUrl != null
                && finalUrl.contains("demonicscans.org")
                && !finalUrl.contains("signin")
                && !finalUrl.contains("login")
                && !finalUrl.contains("register")
                && !finalUrl.contains("signup");

        // Broad DOM selector set — covers common authentication indicator patterns.
        String js = "(function() { " +
                "  var selectors = [" +
                "    'a[href*=\"logout\"]'," +
                "    'a[href*=\"signout\"]'," +
                "    'a[href*=\"log-out\"]'," +
                "    'a[href*=\"sign-out\"]'," +
                "    '.user-dropdown'," +
                "    '.player-name'," +
                "    '.profile-link'," +
                "    '.username'," +
                "    '.user-menu'," +
                "    '.nav-user'," +
                "    '[class*=\"user\"][class*=\"avatar\"]'," +
                "    '[class*=\"logged\"]'," +
                "    '#user-panel'" +
                "  ];" +
                "  for (var i = 0; i < selectors.length; i++) {" +
                "    try { if (document.querySelector(selectors[i])) return 'dom_pass'; } catch(e) {}" +
                "  }" +
                "  return 'dom_fail';" +
                "})();";

        gameWebView.evaluateJavascript(js, result -> {
            boolean domPass = result != null && result.contains("dom_pass");
            bridge.appendLog("debug", "Session check: dom=" + (domPass ? "pass" : "fail")
                    + ", url=" + (urlLooksAuthenticated ? "authenticated" : "redirect-to-login"));

            if (domPass) {
                bridge.appendLog("system", "Session verified via DOM");
                bridge.setConnected(true);
            } else if (urlLooksAuthenticated) {
                // DOM selectors may not match this site's markup, but the URL tells us
                // we didn't get bounced to a login page — trust that.
                bridge.appendLog("system", "Session verified via URL (DOM selectors not matched — may need updating)");
                bridge.setConnected(true);
            } else {
                bridge.appendLog("warning", "Session verification failed — redirected to login page");
                bridge.setConnected(false);
            }
        });
    }

    private void closeLoginOverlay() {
        if (loginContainer == null) return;
        loginContainer.setVisibility(View.INVISIBLE);
        if (loginWebView != null) loginWebView.loadUrl("about:blank");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null) uiWebView.evaluateJavascript("if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
        }, 400);
    }

    private boolean handleLoginUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("https://") || url.startsWith("http://")) return false;
        if (url.startsWith("intent://") || url.startsWith("market://")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                startActivity(intent);
            } catch (Exception ignored) {}
            return true;
        }
        if (url.startsWith("tel:") || url.startsWith("mailto:")) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {}
            return true;
        }
        return true;
    }

    private void applyWebViewSettings(WebView webView, boolean allowFileAccess) {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccessFromFileURLs(allowFileAccess);
        s.setAllowUniversalAccessFromFileURLs(allowFileAccess);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
    }

    public void injectUserScript(WebView view) {
        try {
            InputStream is = getAssets().open("pvp_manager.js");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            String script = new String(buffer, StandardCharsets.UTF_8);
            view.evaluateJavascript("(function(){\n" + script + "\n})();", null);
        } catch (IOException e) {
            Log.e("PvPManager", "injectUserScript failed: " + e.getMessage());
        }
    }

    @Override
    public void onBackPressed() {
        if (loginContainer != null && loginContainer.getVisibility() == View.VISIBLE) {
            if (loginWebView != null && loginWebView.canGoBack()) loginWebView.goBack();
            else closeLoginOverlay();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gameWebView != null) gameWebView.destroy();
        if (uiWebView != null) uiWebView.destroy();
        if (loginWebView != null) loginWebView.destroy();
    }
}
