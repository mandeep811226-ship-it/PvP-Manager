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

    // Accessed by PvpService to stop the bot from the notification
    public static WebView gameWebView;
    public static WebView uiWebView;

    private WebView loginWebView;
    private FrameLayout loginContainer;
    private LinearLayout loginNavBar;
    private TextView btnSaveConnect;
    private AndroidBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ---------- Game WebView (hidden 0x0 – runs bot logic) ----------
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
                verifyGameWebViewSession(url);
                injectUserScript(view);
            }
        });
        root.addView(gameWebView);

        // ---------- UI WebView (the visible app UI) ----------
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

        // ---------- Login overlay ----------
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
                // Flush so CookieManager is up-to-date for SAVE CONNECT
                CookieManager.getInstance().flush();
                if (bridge != null) bridge.appendLog("debug", "loginWebView page: " + url);
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

        btnSaveConnect = pillButton("SAVE & CONNECT", Color.parseColor("#00897B"));
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
                && !url.contains("signin") && !url.contains("login")
                && !url.contains("register") && !url.equals("about:blank");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        if (looksLoggedIn) {
            bg.setColor(Color.parseColor("#2E7D32"));
            btnSaveConnect.setText("SAVE & CONNECT ✓");
        } else {
            bg.setColor(Color.parseColor("#00897B"));
            btnSaveConnect.setText("SAVE & CONNECT");
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

    /**
     * Called when the user taps "SAVE & CONNECT".
     *
     * KEY INSIGHT: All WebViews in the same app share the same CookieManager.
     * When the user logs in via loginWebView, those cookies are automatically
     * available in gameWebView — no manual copying is needed. We just need to
     * confirm the user actually logged in (via URL or CookieManager) and then
     * close the overlay and reload.
     */
    public void saveConnect() {
        if (bridge == null) {
            Toast.makeText(this, "Bridge not ready", Toast.LENGTH_LONG).show();
            return;
        }

        if (loginWebView == null) {
            Toast.makeText(this, "Login overlay not ready", Toast.LENGTH_LONG).show();
            return;
        }

        bridge.appendLog("system", "SAVE & CONNECT tapped");

        // Flush the cookie store to disk before we inspect it
        CookieManager.getInstance().flush();

        String currentUrl = loginWebView.getUrl();
        bridge.appendLog("debug", "loginWebView URL: " + currentUrl);

        // Check 1: URL-based — the most reliable signal.
        // If the loginWebView is on the site and NOT on a login/register page,
        // the user is very likely logged in.
        boolean urlConfirmsLogin = currentUrl != null
                && currentUrl.contains("demonicscans.org")
                && !currentUrl.contains("signin")
                && !currentUrl.contains("login")
                && !currentUrl.contains("register")
                && !currentUrl.equals("about:blank");

        // Check 2: Cookie-based — CookieManager holds all cookies including httpOnly ones.
        // The site must have set SOME cookies if the user is logged in.
        String cmCookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
        boolean cookiesPresent = cmCookies != null && cmCookies.trim().length() > 0;

        bridge.appendLog("debug", "urlConfirmsLogin=" + urlConfirmsLogin
                + " cookiesPresent=" + cookiesPresent
                + " cookieLen=" + (cmCookies == null ? 0 : cmCookies.length()));

        if (!urlConfirmsLogin && !cookiesPresent) {
            bridge.appendLog("warning", "Cannot confirm login — URL and cookies both negative");
            Toast.makeText(this, "Please log in to the site first, then tap Save & Connect", Toast.LENGTH_LONG).show();
            return;
        }

        // Close the overlay immediately so the user gets feedback
        loginContainer.setVisibility(View.INVISIBLE);
        loginWebView.loadUrl("about:blank");

        // Mark as connected right away — the gameWebView reload below will
        // re-verify and only clear this if it lands on an explicit auth page
        bridge.setConnected(true);
        bridge.appendLog("system", "Session saved — reloading game WebView");

        // Reload gameWebView: because cookies are shared, it will now load
        // as a logged-in user, and verifyGameWebViewSession will confirm it
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (gameWebView != null) gameWebView.reload();
        }, 600);

        // Trigger a UI refresh so stats and connection dot update
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null) {
                uiWebView.evaluateJavascript("if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            }
        }, 1400);
    }

    /**
     * Called every time gameWebView finishes loading a page.
     * This is the authoritative session check — if we detect the user is on
     * an explicit login/auth page, we clear the session. Otherwise we either
     * confirm it (logged-in indicators found) or leave the existing state alone.
     */
    private void verifyGameWebViewSession(String finalUrl) {
        if (gameWebView == null || bridge == null) return;

        // Strong negative evidence: the game WebView was redirected to a login page.
        // This is the ONLY case where we aggressively clear the session.
        boolean onAuthPage = finalUrl != null && (
                finalUrl.contains("/signin") || finalUrl.contains("/login") ||
                finalUrl.contains("/register") || finalUrl.contains("/signup"));

        if (onAuthPage) {
            bridge.appendLog("warning", "Redirected to auth page — clearing session");
            bridge.setConnected(false);
            return;
        }

        // If not on an auth page, check whether we can confirm the session positively.
        // We use CookieManager as the primary signal (works for httpOnly cookies too).
        CookieManager.getInstance().flush();
        String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
        boolean hasCookies = cookies != null && cookies.trim().length() > 0;

        boolean urlOk = finalUrl != null && finalUrl.contains("demonicscans.org");

        if (urlOk && hasCookies) {
            if (!bridge.isSessionVerified()) {
                // Only log if this is a state change — avoid spamming
                bridge.appendLog("system", "✅ Session confirmed via cookies");
                bridge.setConnected(true);
            }
            // If already verified, silently keep it — no need to log every reload
            return;
        }

        // URL is on the site but no cookies — unusual but not necessarily an error.
        // Leave whatever state we had.
        bridge.appendLog("debug", "Verify: urlOk=" + urlOk + " hasCookies=" + hasCookies
                + " — keeping existing state");
    }

    private void closeLoginOverlay() {
        if (loginContainer == null) return;
        loginContainer.setVisibility(View.INVISIBLE);
        if (loginWebView != null) loginWebView.loadUrl("about:blank");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null) uiWebView.evaluateJavascript("if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
        }, 300);
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
        if (gameWebView != null) { gameWebView.destroy(); gameWebView = null; }
        if (uiWebView != null) { uiWebView.destroy(); uiWebView = null; }
        if (loginWebView != null) { loginWebView.destroy(); loginWebView = null; }
    }
}
