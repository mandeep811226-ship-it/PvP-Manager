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

public class MainActivity extends AppCompatActivity {

    public static WebView gameWebView;
    public static WebView uiWebView;

    private WebView       loginWebView;
    private FrameLayout   loginContainer;
    private TextView      btnSaveConnect;
    private AndroidBridge bridge;

    // -------------------------------------------------------------------------
    // FIX (Problem 5): Runtime permission launcher for POST_NOTIFICATIONS
    // -------------------------------------------------------------------------
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);

        // Register notification permission launcher before any UI setup
        notificationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                if (!granted) {
                    Log.w("PvPManager", "Notification permission denied");
                }
            });

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ---------- Game WebView (hidden 0x0 — runs bot logic) ----------
        gameWebView = new WebView(this);
        gameWebView.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
        applyWebViewSettings(gameWebView, false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(gameWebView, true);

        gameWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) { return false; }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) { return false; }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                if (bridge != null) bridge.appendLog("debug", "gameWebView: " + url);
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
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) { return false; }
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

        LinearLayout navBar = buildLoginNavBar();
        FrameLayout.LayoutParams navP = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        navP.gravity = Gravity.TOP;
        loginContainer.addView(navBar, navP);

        loginWebView = new WebView(this);
        int navH = (int) (56 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams wvP = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        wvP.topMargin = navH;
        applyWebViewSettings(loginWebView, false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(loginWebView, true);

        loginWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
                return handleLoginUrl(r.getUrl().toString());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleLoginUrl(url);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                updateSaveConnectLabel(url);
            }
        });

        loginWebView.loadUrl("about:blank");
        loginContainer.addView(loginWebView, wvP);
        root.addView(loginContainer);
        setContentView(root);

        gameWebView.loadUrl("https://demonicscans.org/pvp.php");

        // Start foreground service
        Intent svcIntent = new Intent(this, PvpService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent);
        } else {
            startService(svcIntent);
        }

        // FIX (Problem 5): Request runtime permissions after UI is ready
        requestRuntimePermissions();
    }

    // -------------------------------------------------------------------------
    // FIX (Problem 5): Runtime permission requests
    // -------------------------------------------------------------------------
    private void requestRuntimePermissions() {
        // 1. POST_NOTIFICATIONS — Android 13+ (API 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        // 2. Battery optimisation — prompt to disable so the service survives
        //    Do this with a short delay so the app UI is fully visible first
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent i = new Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            } catch (Exception e) {
                Log.w("PvPManager", "Battery opt request failed: " + e.getMessage());
            }
        }, 3000);
    }

    // -------------------------------------------------------------------------
    // LOGIN OVERLAY
    // -------------------------------------------------------------------------

    private LinearLayout buildLoginNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#1a1025"));
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));

        // BACK always returns to the app UI — no webview history navigation
        TextView back = pillButton("← BACK TO APP", Color.parseColor("#2d2040"));
        back.setOnClickListener(v -> closeLoginOverlay());
        LinearLayout.LayoutParams backP = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        backP.setMargins(0, 0, dp(6), 0);
        bar.addView(back, backP);

        TextView reload = pillButton("⟳ RELOAD", Color.parseColor("#1565C0"));
        reload.setOnClickListener(v -> { if (loginWebView != null) loginWebView.reload(); });
        LinearLayout.LayoutParams reloadP = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f);
        reloadP.setMargins(0, 0, dp(6), 0);
        bar.addView(reload, reloadP);

        btnSaveConnect = pillButton("SAVE & CONNECT", Color.parseColor("#00897B"));
        btnSaveConnect.setOnClickListener(v -> saveConnect());
        bar.addView(btnSaveConnect,
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f));

        return bar;
    }

    /** Called by the loginWebView's onPageFinished — resets the button if user navigates away. */
    private void updateSaveConnectLabel(String url) {
        if (btnSaveConnect == null) return;
        // Only reset to default if we are NOT already in the "Connected" state,
        // so a page navigation after a successful connect doesn't wipe the green button.
        if (bridge != null && bridge.isSessionVerified()) return;
        setConnectButtonState(false);
    }

    private void setConnectButtonState(boolean connected) {
        if (btnSaveConnect == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        if (connected) {
            bg.setColor(Color.parseColor("#2E7D32"));
            btnSaveConnect.setText("✓ CONNECTED");
            btnSaveConnect.setEnabled(false);   // prevent double-tap
        } else {
            bg.setColor(Color.parseColor("#00897B"));
            btnSaveConnect.setText("SAVE & CONNECT");
            btnSaveConnect.setEnabled(true);
        }
        btnSaveConnect.setBackground(bg);
    }

    public void showLogin() {
        if (loginWebView == null || loginContainer == null) return;
        loginContainer.setVisibility(View.VISIBLE);
        loginWebView.requestFocus();
        String cur = loginWebView.getUrl();
        if (cur == null || cur.equals("about:blank") || cur.isEmpty()) {
            loginWebView.loadUrl("https://demonicscans.org");
        }
        if (bridge != null) bridge.appendLog("system", "Login overlay opened");
    }

    // -------------------------------------------------------------------------
    // Save & Connect — stays on the overlay, updates button to ✓ CONNECTED.
    // User taps "← BACK TO APP" manually when ready.
    // -------------------------------------------------------------------------
    public void saveConnect() {
        if (bridge == null || loginWebView == null) {
            Toast.makeText(this, "Not ready — try again", Toast.LENGTH_SHORT).show();
            return;
        }

        bridge.appendLog("system", "SAVE & CONNECT tapped");
        CookieManager.getInstance().flush();

        String currentUrl = loginWebView.getUrl();

        // PRIMARY CHECK: URL on the site and not on a login/auth page
        boolean urlOk = currentUrl != null
                && currentUrl.contains("demonicscans.org")
                && !currentUrl.contains("signin")
                && !currentUrl.contains("login")
                && !currentUrl.contains("register")
                && !currentUrl.equals("about:blank");

        // SECONDARY CHECK: any cookies present for the domain
        String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
        boolean hasCookies = cookies != null && cookies.trim().length() > 0;

        bridge.appendLog("debug", "saveConnect: urlOk=" + urlOk
                + " hasCookies=" + hasCookies
                + " cookieLen=" + (cookies == null ? 0 : cookies.length()));

        if (!urlOk && !hasCookies) {
            // Not logged in — show failure on the button and a toast
            Toast.makeText(this,
                "Please log in on the site first, then tap Save & Connect",
                Toast.LENGTH_LONG).show();
            // Flash the button red briefly so the user sees the failure
            if (btnSaveConnect != null) {
                GradientDrawable errBg = new GradientDrawable();
                errBg.setCornerRadius(dp(24));
                errBg.setColor(Color.parseColor("#B71C1C"));
                btnSaveConnect.setBackground(errBg);
                btnSaveConnect.setText("✗ NOT LOGGED IN");
                new Handler(Looper.getMainLooper()).postDelayed(
                    () -> setConnectButtonState(false), 2500);
            }
            return;
        }

        // ── SUCCESS ─────────────────────────────────────────────────────────
        // 1. Mark session as connected (synchronous commit)
        bridge.setConnected(true);
        bridge.appendLog("system", "Session saved ✓ — tap '← BACK TO APP' to return");

        // 2. Update the button to show success — overlay stays open
        setConnectButtonState(true);

        // 3. Reload the hidden gameWebView in the background
        if (gameWebView != null) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (gameWebView != null) gameWebView.reload();
            }, 400);
        }

        // 4. Refresh the app UI in the background so it's ready when user returns
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null) {
                uiWebView.evaluateJavascript(
                    "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            }
        }, 1500);
    }

    // -------------------------------------------------------------------------
    // FIX (Problem 4): Session verification — URL-primary, no timer
    // Only clears session on strong evidence (explicit redirect to auth page).
    // -------------------------------------------------------------------------
    private void verifyGameWebViewSession(String finalUrl) {
        if (gameWebView == null || bridge == null) return;

        // STRONG NEGATIVE: explicit redirect to an auth/registration page
        boolean onAuthPage = finalUrl != null && (
                finalUrl.contains("/signin") || finalUrl.contains("/login") ||
                finalUrl.contains("/register") || finalUrl.contains("/signup"));

        if (onAuthPage) {
            bridge.appendLog("warning", "Redirected to auth page — session cleared");
            bridge.setConnected(false);
            return;
        }

        // PRIMARY POSITIVE: URL on demonicscans.org, not on an auth page,
        //                   AND cookies exist (confirms the browser is carrying a session)
        boolean urlOnSite = finalUrl != null && finalUrl.contains("demonicscans.org");
        CookieManager.getInstance().flush();
        String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
        boolean hasCookies = cookies != null && cookies.trim().length() > 0;

        if (urlOnSite && hasCookies) {
            if (!bridge.isSessionVerified()) {
                bridge.appendLog("system", "✅ Session confirmed");
                bridge.setConnected(true);
            }
            return;
        }

        // SECONDARY POSITIVE: check for PvP UI elements in the DOM
        if (urlOnSite) {
            String js = "(function(){" +
                "try{" +
                "  var hasPvpEl = !!(document.getElementById('stat-tokens') ||" +
                "    document.querySelector('[id*=\"pvp\"], [class*=\"pvp\"],' +" +
                "      '[id*=\"player\"], [id*=\"user-info\"]'));" +
                "  var hasTokens = !!(document.getElementById('user-tokens') ||" +
                "    document.querySelector('[class*=\"token\"]'));" +
                "  return JSON.stringify({pvp: hasPvpEl, tokens: hasTokens});" +
                "}catch(e){return JSON.stringify({err:e.message});}" +
                "})();";
            gameWebView.evaluateJavascript(js, result -> {
                if (bridge == null) return;
                boolean domPositive = result != null &&
                        (result.contains("\"pvp\":true") || result.contains("\"tokens\":true"));
                if (domPositive && !bridge.isSessionVerified()) {
                    bridge.appendLog("system", "✅ Session confirmed via DOM");
                    bridge.setConnected(true);
                } else if (!domPositive) {
                    bridge.appendLog("debug", "Session uncertain — no strong signal");
                    // Do NOT clear session on uncertainty — only auth-page redirect does that
                }
            });
        }
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private void closeLoginOverlay() {
        if (loginContainer == null) return;
        loginContainer.setVisibility(View.INVISIBLE);
        if (loginWebView != null) loginWebView.loadUrl("about:blank");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null)
                uiWebView.evaluateJavascript(
                    "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
        }, 300);
    }

    private boolean handleLoginUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("https://") || url.startsWith("http://")) return false;
        if (url.startsWith("intent://") || url.startsWith("market://")) {
            try { startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME)); }
            catch (Exception ignored) {}
            return true;
        }
        if (url.startsWith("tel:") || url.startsWith("mailto:")) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Exception ignored) {}
            return true;
        }
        return true;
    }

    private void applyWebViewSettings(WebView wv, boolean fileAccess) {
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

    public void injectUserScript(WebView view) {
        try {
            InputStream is = getAssets().open("pvp_manager.js");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            String script = new String(buf, StandardCharsets.UTF_8);
            view.evaluateJavascript("(function(){\n" + script + "\n})();", null);
        } catch (IOException e) {
            Log.e("PvPManager", "injectUserScript: " + e.getMessage());
        }
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

    @Override
    public void onBackPressed() {
        // If the login overlay is open, the hardware back button always returns to the app UI
        // (same behaviour as the "← BACK TO APP" button in the nav bar)
        if (loginContainer != null && loginContainer.getVisibility() == View.VISIBLE) {
            closeLoginOverlay();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gameWebView  != null) { gameWebView.destroy();  gameWebView  = null; }
        if (uiWebView    != null) { uiWebView.destroy();    uiWebView    = null; }
        if (loginWebView != null) { loginWebView.destroy(); loginWebView = null; }
    }
}
