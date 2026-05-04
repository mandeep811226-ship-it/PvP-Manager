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

    // Cache for cookies captured during onPageFinished (reliable)
    private volatile String lastCapturedCookies = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);

        CookieManager.getInstance().setAcceptCookie(true);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ---------- Game WebView ----------
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

                // Use final URL plus a DOM check to set connected status
                verifyGameWebViewSession(url);
                injectUserScript(view);
            }
        });
        root.addView(gameWebView);

        // ---------- UI WebView ----------
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

        // ---------- Login Overlay ----------
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
                CookieManager.getInstance().flush();
                String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
                if (cookies != null && cookies.length() >= 10) {
                    lastCapturedCookies = cookies;
                    Log.d("PvPManager", "Cookies captured: length=" + cookies.length());
                    if (bridge != null) bridge.appendLog("debug", "Cookies captured length=" + cookies.length());
                }
                // Also try to capture again after 2 seconds (post-load XHR cookies)
                delayedCookieCapture();
                if (bridge != null) bridge.appendLog("debug", "loginWebView loaded: " + url);
                updateSaveConnectLabel(url);
            }
        });

        loginWebView.loadUrl("about:blank");
        loginContainer.addView(loginWebView, wvParams);
        root.addView(loginContainer);
        setContentView(root);

        gameWebView.loadUrl("https://demonicscans.org/pvp.php");

        Intent serviceIntent = new Intent(this, PvpService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void delayedCookieCapture() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (loginWebView == null) return;
            CookieManager.getInstance().flush();
            String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
            if (cookies != null && cookies.length() >= 10) {
                lastCapturedCookies = cookies;
                Log.d("PvPManager", "Delayed cookie capture OK: " + cookies.length());
                if (bridge != null) bridge.appendLog("debug", "Delayed capture: " + cookies.length());
            } else {
                Log.d("PvPManager", "Delayed capture: still no cookies");
            }
        }, 2000);
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
        btnSaveConnect.setOnClickListener(v -> saveConnect());
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
            btnSaveConnect.setText("SAVE CONNECT ✓");
        } else {
            bg.setColor(Color.parseColor("#00897B"));
            btnSaveConnect.setText("SAVE CONNECT");
        }
        btnSaveConnect.setBackground(bg);
    }

    public void showLogin() {
        if (loginWebView == null || loginContainer == null) return;
        loginContainer.setVisibility(View.VISIBLE);
        loginWebView.requestFocus();
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
            Toast.makeText(this, "Bridge not ready", Toast.LENGTH_LONG).show();
            return;
        }

        bridge.appendLog("system", "SAVE CONNECT clicked");

        // 1. Use cached cookies if available, else flush+read
        String cookies = lastCapturedCookies;
        if (cookies == null || cookies.length() < 10) {
            CookieManager.getInstance().flush();
            cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
        }

        // 2. Also sync from loginWebView's document.cookie (bypass HttpOnly)
        if (loginWebView != null) {
            loginWebView.evaluateJavascript("document.cookie", value -> {
                if (value != null && !value.equals("null") && value.length() > 5) {
                    String[] pairs = value.split(";");
                    for (String pair : pairs) {
                        String[] kv = pair.trim().split("=", 2);
                        if (kv.length == 2) {
                            CookieManager.getInstance().setCookie("https://demonicscans.org", kv[0] + "=" + kv[1]);
                        }
                    }
                    CookieManager.getInstance().flush();
                    bridge.appendLog("debug", "Manually synced " + pairs.length + " cookies from document.cookie");
                    // Re-read after sync
                    cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
                }
            });
        }

        int cookieLen = (cookies == null ? 0 : cookies.length());
        bridge.appendLog("debug", "Final cookie length: " + cookieLen);

        if (cookieLen < 10) {
            bridge.appendLog("error", "No valid session cookies (len=" + cookieLen + "). Login may have failed.");
            Toast.makeText(this, "No valid cookies – please login again", Toast.LENGTH_LONG).show();
            return;
        }

        // 3. Close overlay
        loginContainer.setVisibility(View.INVISIBLE);
        if (loginWebView != null) loginWebView.loadUrl("about:blank");
        lastCapturedCookies = null;

        // 4. Reload game WebView with a delay (allow cookies to settle)
        if (gameWebView != null) {
            bridge.appendLog("system", "Reloading game WebView in 800ms");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                gameWebView.reload();
            }, 800);
        }

        // 5. Refresh UI after a short delay – verification will happen inside onPageFinished
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null) {
                uiWebView.evaluateJavascript("if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            }
        }, 1500);
    }

    /**
     * Robust session verification:
     * - URL-based: if we are on a page that is not login/register and under demonicscans.org, trust.
     * - DOM-based: checks for user-specific elements (tokens pill, player name, etc.)
     * Called from gameWebView.onPageFinished.
     */
    private void verifyGameWebViewSession(String finalUrl) {
        if (gameWebView == null || bridge == null) return;

        boolean urlOk = finalUrl != null
                && finalUrl.contains("demonicscans.org")
                && !finalUrl.contains("signin")
                && !finalUrl.contains("login")
                && !finalUrl.contains("register")
                && !finalUrl.contains("signup");

        // DOM check: presence of tokens or user name element
        String js = "(function() { " +
                "  var tokensSpan = document.getElementById('stat-tokens'); " +
                "  var userSpan = document.querySelector('.player-name, .username, .profile-name, .user-name'); " +
                "  return JSON.stringify({ tokens: !!tokensSpan, user: !!userSpan }); " +
                "})();";

        gameWebView.evaluateJavascript(js, result -> {
            boolean domOk = result != null && (result.contains("\"tokens\":true") || result.contains("\"user\":true"));
            bridge.appendLog("debug", "Verify: urlOk=" + urlOk + ", domOk=" + domOk);

            if (domOk || urlOk) {
                bridge.appendLog("system", "✅ Session verified (" + (domOk ? "DOM" : "URL") + ")");
                bridge.setConnected(true);
            } else {
                bridge.appendLog("warning", "❌ Session verification failed");
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
