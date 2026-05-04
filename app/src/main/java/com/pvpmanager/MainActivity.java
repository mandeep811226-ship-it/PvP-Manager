package com.pvpmanager;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    public static WebView gameWebView;
    public static WebView uiWebView;

    private WebView loginWebView;
    private FrameLayout loginContainer;

    // Native nav bar shown above the login WebView (BACK · RELOAD · SAVE CONNECT)
    private LinearLayout loginNavBar;
    private TextView btnSaveConnect;

    private AndroidBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView.setWebContentsDebuggingEnabled(true);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ── LAYER 1: GAME WEBVIEW (hidden, zero-size) ──
        gameWebView = new WebView(this);
        gameWebView.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
        applyWebViewSettings(gameWebView, false);
        gameWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectUserScript(view);
                CookieManager.getInstance().flush();
            }
        });
        root.addView(gameWebView);

        // ── LAYER 2: UI WEBVIEW (fills screen, shows main.html) ──
        uiWebView = new WebView(this);
        uiWebView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        applyWebViewSettings(uiWebView, true);

        bridge = new AndroidBridge(this, gameWebView, uiWebView);
        uiWebView.addJavascriptInterface(bridge, "Android");

        uiWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });
        uiWebView.loadUrl("file:///android_asset/main.html");
        root.addView(uiWebView);

        // ── LAYER 3: LOGIN OVERLAY ──
        loginContainer = new FrameLayout(this);
        loginContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        loginContainer.setVisibility(View.INVISIBLE);

        // ── Nav bar: BACK · RELOAD · SAVE CONNECT ──
        loginNavBar = buildLoginNavBar();
        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        navParams.gravity = Gravity.TOP;
        loginContainer.addView(loginNavBar, navParams);

        // ── Login WebView (below the nav bar) ──
        loginWebView = new WebView(this);
        int navHeightPx = (int) (56 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams wvParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        wvParams.topMargin = navHeightPx;
        applyWebViewSettings(loginWebView, false);
        CookieManager.getInstance().setAcceptCookie(true);
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
                // Flush cookies on every page load so they are persisted incrementally.
                // This ensures cookies are not lost if the user navigates away or the
                // WebView is destroyed before we explicitly call flush().
                CookieManager.getInstance().flush();

                if (url == null) return;

                // Make overlay visible once a real page has loaded
                if (loginContainer.getVisibility() != View.VISIBLE
                        && url.startsWith("http")) {
                    loginContainer.setVisibility(View.VISIBLE);
                }

                // Update SAVE CONNECT button label based on current URL
                updateSaveConnectLabel(url);
            }
        });

        loginWebView.loadUrl("about:blank");

        loginContainer.addView(loginWebView, wvParams);
        root.addView(loginContainer);

        setContentView(root);

        // Global cookie settings
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(gameWebView, true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(uiWebView, true);

        // Load game page in background
        gameWebView.loadUrl("https://demonicscans.org/pvp.php");

        // Start foreground service
        Intent serviceIntent = new Intent(this, PvpService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    // -------------------------------------------------------------------------
    // Nav bar builder
    // -------------------------------------------------------------------------

    private LinearLayout buildLoginNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#1a1025"));

        int padH = dp(12);
        int padV = dp(8);
        bar.setPadding(padH, padV, padH, padV);

        // BACK
        TextView back = pillButton("BACK", Color.parseColor("#2d2040"));
        back.setOnClickListener(v -> {
            if (loginWebView != null && loginWebView.canGoBack()) {
                loginWebView.goBack();
            } else {
                closeLoginOverlay();
            }
        });
        LinearLayout.LayoutParams backP = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        backP.setMargins(0, 0, dp(6), 0);
        bar.addView(back, backP);

        // RELOAD
        TextView reload = pillButton("RELOAD", Color.parseColor("#1565C0"));
        reload.setOnClickListener(v -> {
            if (loginWebView != null) loginWebView.reload();
        });
        LinearLayout.LayoutParams reloadP = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        reloadP.setMargins(0, 0, dp(6), 0);
        bar.addView(reload, reloadP);

        // SAVE CONNECT
        btnSaveConnect = pillButton("SAVE CONNECT", Color.parseColor("#00897B"));
        btnSaveConnect.setOnClickListener(v -> saveConnect());
        LinearLayout.LayoutParams saveP = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f);
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

    private int dp(int val) {
        return (int) (val * getResources().getDisplayMetrics().density);
    }

    private void updateSaveConnectLabel(String url) {
        if (btnSaveConnect == null) return;
        boolean looksLoggedIn = url != null
                && url.contains("demonicscans.org")
                && !url.contains("signin")
                && !url.contains("login")
                && !url.contains("register");

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        if (looksLoggedIn) {
            bg.setColor(Color.parseColor("#2E7D32")); // green — ready to save
            btnSaveConnect.setText("SAVE CONNECT \u2713");
        } else {
            bg.setColor(Color.parseColor("#00897B")); // teal — neutral
            btnSaveConnect.setText("SAVE CONNECT");
        }
        btnSaveConnect.setBackground(bg);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Called by AndroidBridge.openLogin().
     * Opens the main site so the user can log in, then press SAVE CONNECT.
     */
    public void showLogin() {
        if (loginWebView == null || loginContainer == null) return;
        loginContainer.setVisibility(View.INVISIBLE);
        // Open main site — NOT signin.php directly — matches the reference APK flow.
        loginWebView.loadUrl("https://demonicscans.org");
        loginWebView.requestFocus();
    }

    /**
     * Called ONLY when the user explicitly presses SAVE CONNECT.
     *
     * Fix: Flush cookies FIRST (synchronously within this call), THEN hide the
     * overlay, THEN navigate loginWebView to about:blank. The original code
     * called destroyLogin() via postDelayed which navigated loginWebView to
     * about:blank BEFORE the flush completed, tearing down the session context
     * and losing cookies. Now the order is strictly: flush → hide → blank →
     * reload game → refresh UI.
     */
    public void saveConnect() {

    // STEP 1: flush cookies while session is alive
    CookieManager.getInstance().flush();

    // STEP 2: VERIFY cookies exist BEFORE doing anything
    String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");

    if (cookies == null || cookies.length() < 10) {
        // ❌ Do NOT close login if session not captured
        return;
    }

    // STEP 3: hide overlay
    if (loginContainer != null) {
        loginContainer.setVisibility(View.INVISIBLE);
    }

    // STEP 4: destroy login WebView AFTER verification
    if (loginWebView != null) {
        loginWebView.loadUrl("about:blank");
    }

    // STEP 5: reload game WebView (IMPORTANT: use reload, not loadUrl)
    if (gameWebView != null) {
        gameWebView.reload();
    }

    // STEP 6: refresh UI after short delay
    new Handler(Looper.getMainLooper()).postDelayed(() -> {
        if (uiWebView != null) {
            uiWebView.evaluateJavascript(
                "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();",
                null
            );
        }
    }, 500);
}

    /** Closes overlay without capturing session (Back / cancel). */
    private void closeLoginOverlay() {
        if (loginContainer == null) return;
        loginContainer.setVisibility(View.INVISIBLE);
        if (loginWebView != null) {
            loginWebView.loadUrl("about:blank");
        }
        // Refresh UI so connection state is re-read (it may already be connected
        // from a prior session).
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null) {
                uiWebView.evaluateJavascript(
                        "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            }
        }, 400);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
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
            e.printStackTrace();
        }
    }

    @Override
    public void onBackPressed() {
        if (loginContainer != null &&
                loginContainer.getVisibility() == View.VISIBLE) {
            if (loginWebView != null && loginWebView.canGoBack()) {
                loginWebView.goBack();
            } else {
                closeLoginOverlay();
            }
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gameWebView != null)  gameWebView.destroy();
        if (uiWebView != null)    uiWebView.destroy();
        if (loginWebView != null) loginWebView.destroy();
    }
}
