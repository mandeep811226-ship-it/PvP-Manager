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

    // PvP page the bot always navigates to
    private static final String PVP_URL = "https://demonicscans.org/pvp.php";

    public static WebView gameWebView;
    public static WebView uiWebView;

    private WebView     loginWebView;
    private FrameLayout loginContainer;
    private TextView    btnSaveConnect;
    private AndroidBridge bridge;

    // Runtime permission launcher for POST_NOTIFICATIONS (Android 13+)
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);

        notificationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> Log.d("PvPManager", "POST_NOTIFICATIONS granted=" + granted));

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ── Hidden game WebView (0×0 — runs bot logic) ───────────────────────
        gameWebView = new WebView(this);
        gameWebView.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
        applyWebViewSettings(gameWebView, false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(gameWebView, true);

        gameWebView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url)          { return false; }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                if (bridge != null) bridge.appendLog("debug", "gameWebView finished: " + url);
                verifyGameWebViewSession(url);
                injectUserScript(view);
            }
        });
        root.addView(gameWebView);

        // ── Visible UI WebView ────────────────────────────────────────────────
        uiWebView = new WebView(this);
        uiWebView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        applyWebViewSettings(uiWebView, true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(uiWebView, true);

        bridge = new AndroidBridge(this, gameWebView, uiWebView);
        bridge.startKeepalive();
        uiWebView.addJavascriptInterface(bridge, "Android");
        uiWebView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url)          { return false; }
        });
        uiWebView.loadUrl("file:///android_asset/main.html");
        root.addView(uiWebView);

        // ── Login Overlay ─────────────────────────────────────────────────────
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
        int navH = dp(56);
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
                // FIX: Only reset to IDLE if not connected.
                // If the user is already connected and the loginWebView navigates
                // (e.g. they browse around), we must NOT flip the button back to
                // SAVE & CONNECT — it should stay green (CONNECTED/SUCCESS).
                if (bridge != null) {
                    if (bridge.isSessionVerified()) {
                        setConnectButtonState(ConnectState.SUCCESS);
                    } else {
                        setConnectButtonState(ConnectState.IDLE);
                    }
                }
            }
        });
        loginWebView.loadUrl("about:blank");
        loginContainer.addView(loginWebView, wvP);
        root.addView(loginContainer);
        setContentView(root);

        // Load the game page immediately so cookies are attempted on first run
        gameWebView.loadUrl(PVP_URL);

        // Start foreground service
        Intent svcIntent = new Intent(this, PvpService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent);
        else startService(svcIntent);

        requestRuntimePermissions();
    }

    // ── Runtime permissions ───────────────────────────────────────────────────

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
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

    // ── Login overlay nav bar ─────────────────────────────────────────────────

    private enum ConnectState { IDLE, SUCCESS, FAILURE }

    private LinearLayout buildLoginNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#1a1025"));
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));

        // BACK always closes the overlay and returns to the app UI
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

    private void setConnectButtonState(ConnectState state) {
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
                // Auto-reset after 2.5s
                new Handler(Looper.getMainLooper()).postDelayed(
                    () -> setConnectButtonState(ConnectState.IDLE), 2500);
                break;
            default: // IDLE
                bg.setColor(Color.parseColor("#00897B"));
                btnSaveConnect.setText("SAVE & CONNECT");
                btnSaveConnect.setEnabled(true);
                break;
        }
        btnSaveConnect.setBackground(bg);
    }

    public void showLogin() {
        if (loginWebView == null || loginContainer == null) return;
        // FIX: Only reset the button to IDLE when NOT already connected.
        // Previously this was unconditional — so if the user tapped "Login"
        // a second time (e.g. main UI showed Disconnected due to a race), the
        // button would flip back to "SAVE & CONNECT" even though the session
        // was still valid in prefs. The user would have to tap Save & Connect
        // again for no reason.
        // If already connected: show the green CONNECTED button immediately.
        if (bridge != null && bridge.isSessionVerified()) {
            setConnectButtonState(ConnectState.SUCCESS);
        } else {
            setConnectButtonState(ConnectState.IDLE);
        }
        loginContainer.setVisibility(View.VISIBLE);
        loginWebView.requestFocus();
        String cur = loginWebView.getUrl();
        // Load the site if blank/null or if currently on an auth page
        // FIX: The real sign-in page is /sign.php — not /signin or /login.
        // All four auth-page checks in this file must include sign.php.
        boolean onAuthPage = cur != null && (
                cur.contains("/sign.php") || cur.contains("/signin") ||
                cur.contains("/login")    || cur.contains("/register") ||
                cur.equals("about:blank") || cur.isEmpty());
        if (cur == null || cur.isEmpty() || onAuthPage) {
            loginWebView.loadUrl("https://demonicscans.org");
        }
        if (bridge != null) bridge.appendLog("system", "Login overlay opened");
    }

    // ── Save & Connect ────────────────────────────────────────────────────────
    //
    // New flow:
    //  1. Verify the loginWebView URL + cookies
    //  2. Mark connected (prefs, grace period)
    //  3. Copy localStorage from loginWebView → gameWebView (for token-based auth)
    //  4. Load PVP_URL directly in gameWebView (NOT reload — reload would replay
    //     whatever redirect URL it's currently sitting on)
    //  5. Button shows ✓ CONNECTED — overlay stays open
    //  6. User taps "← BACK TO APP" when ready
    // ─────────────────────────────────────────────────────────────────────────

    public void saveConnect() {
        if (bridge == null || loginWebView == null) {
            Toast.makeText(this, "Not ready — try again", Toast.LENGTH_SHORT).show();
            return;
        }

        bridge.appendLog("system", "SAVE & CONNECT tapped");
        CookieManager.getInstance().flush();

        String currentUrl = loginWebView.getUrl();
        bridge.appendLog("debug", "loginWebView URL: " + currentUrl);

        // Primary check — URL is on the site and NOT on an auth page.
        // FIX: The real auth page is /sign.php. The original code only checked
        // for "signin" and "login" substrings, so a redirect to sign.php was
        // treated as a valid logged-in page, causing urlOk=true on a sign-in URL.
        boolean urlOk = currentUrl != null
                && currentUrl.contains("demonicscans.org")
                && !currentUrl.contains("sign.php")
                && !currentUrl.contains("signin")
                && !currentUrl.contains("login")
                && !currentUrl.contains("register")
                && !currentUrl.equals("about:blank");

        // Secondary check — any cookies set for the domain
        String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
        boolean hasCookies = cookies != null && !cookies.trim().isEmpty();

        bridge.appendLog("debug", "urlOk=" + urlOk + " hasCookies=" + hasCookies
                + " cookieLen=" + (cookies == null ? 0 : cookies.length()));

        if (!urlOk && !hasCookies) {
            // Hard failure — not on site or no cookies at all
            setConnectButtonState(ConnectState.FAILURE);
            Toast.makeText(this,
                "Please log in on the site first, then tap Save & Connect",
                Toast.LENGTH_LONG).show();
            return;
        }

        // ── SUCCESS PATH ─────────────────────────────────────────────────────

        // 1. Mark session connected (also starts the grace period inside AndroidBridge)
        bridge.setConnected(true);
        bridge.appendLog("system", "Session saved ✓ — syncing to game WebView…");

        // 2. Show the green connected button; overlay stays open
        setConnectButtonState(ConnectState.SUCCESS);

        // 3. Copy localStorage from loginWebView → gameWebView.
        //    Modern sites may store auth tokens in localStorage rather than (or in
        //    addition to) HTTP cookies. Since each WebView has its own JS context,
        //    we must copy them explicitly.
        syncLocalStorageToGameWebView(() -> {
            // 4. Navigate gameWebView directly to PVP_URL (not reload — the current
            //    URL might be a login-redirect and reload() would replay that redirect).
            //    Add a short delay so CookieManager.flush() fully propagates before
            //    gameWebView makes its first request (avoids race where cookies aren't
            //    sent on the very first request and the site redirects to /sign.php).
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (gameWebView != null) {
                    CookieManager.getInstance().flush();
                    bridge.appendLog("debug", "Loading " + PVP_URL + " in gameWebView");
                    gameWebView.loadUrl(PVP_URL);
                }
            }, 800);
        });

        // 5. Refresh the app UI in the background so it shows Connected immediately
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null) {
                uiWebView.evaluateJavascript(
                    "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            }
        }, 500);
    }

    /**
     * Reads all localStorage keys/values from loginWebView and injects them
     * into gameWebView. Calls {@code onDone} on the main thread when finished.
     * Safe to call even if gameWebView hasn't loaded a page yet — it just
     * stores them for when the next page loads from the same origin.
     */
    private void syncLocalStorageToGameWebView(Runnable onDone) {
        if (loginWebView == null) { if (onDone != null) onDone.run(); return; }

        loginWebView.evaluateJavascript(
            "(function(){" +
            "  try {" +
            "    var out = {};" +
            "    for (var i = 0; i < localStorage.length; i++) {" +
            "      var k = localStorage.key(i);" +
            "      out[k] = localStorage.getItem(k);" +
            "    }" +
            "    return JSON.stringify(out);" +
            "  } catch(e) { return '{}'; }" +
            "})()",
            raw -> {
                if (raw == null || raw.equals("null") || raw.equals("\"{}\"") || raw.equals("{}")) {
                    bridge.appendLog("debug", "localStorage empty or unreadable");
                    if (onDone != null) new Handler(Looper.getMainLooper()).post(onDone);
                    return;
                }

                // raw is a JSON-encoded string (double-quoted). Unwrap it.
                String json = raw;
                if (json.startsWith("\"") && json.endsWith("\"")) {
                    json = json.substring(1, json.length() - 1)
                               .replace("\\\"", "\"")
                               .replace("\\\\", "\\")
                               .replace("\\/",  "/");
                }

                bridge.appendLog("debug", "Syncing localStorage (" + json.length() + " chars) → gameWebView");

                final String finalJson = json;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (gameWebView != null) {
                        String injectJs =
                            "(function(raw){" +
                            "  try {" +
                            "    var obj = JSON.parse(raw);" +
                            "    for (var k in obj) {" +
                            "      if (obj.hasOwnProperty(k)) localStorage.setItem(k, obj[k]);" +
                            "    }" +
                            "  } catch(e) {}" +
                            "})('" + finalJson.replace("'", "\\'") + "');";
                        gameWebView.evaluateJavascript(injectJs, v2 -> {
                            if (bridge != null)
                                bridge.appendLog("debug", "localStorage sync done");
                            if (onDone != null) onDone.run();
                        });
                    } else {
                        if (onDone != null) onDone.run();
                    }
                });
            });
    }

    // ── Session verification ──────────────────────────────────────────────────
    //
    // Called every time gameWebView finishes loading a page.
    //
    // Rules:
    //  • ONLY call setConnected(false) on an explicit auth-page redirect.
    //  • NEVER clear the session during the grace period after saveConnect().
    //  • Positive confirmation (on-site + cookies) auto-sets Connected if not
    //    already set (handles cold-start when the user was previously logged in).
    // ─────────────────────────────────────────────────────────────────────────

    private void verifyGameWebViewSession(String url) {
        if (gameWebView == null || bridge == null) return;

        // ── Skip non-site pages entirely ─────────────────────────────────────
        // about:blank, chrome://, data:, etc. are not meaningful for session checks.
        if (url == null || url.isEmpty() || url.startsWith("about:") ||
                url.startsWith("data:") || url.startsWith("chrome:")) {
            return;
        }

        // ── Within grace period — don't touch the session ────────────────────
        if (bridge.inGracePeriod()) {
            bridge.appendLog("debug", "Grace period active — skipping session verify for: " + url);
            return;
        }

        // ── Explicit auth-page redirect = definite sign-out ──────────────────
        // FIX: demonicscans.org uses /sign.php as its login page, not /signin
        // or /login. Without this, a redirect to sign.php was not recognised as
        // a logout event and the session stayed falsely "Connected".
        boolean onAuthPage = url.contains("/sign.php") || url.contains("/signin") ||
                url.contains("/login") || url.contains("/register") || url.contains("/signup");

        if (onAuthPage) {
            bridge.appendLog("warning", "Redirected to auth page — clearing session: " + url);
            bridge.setConnected(false);
            return;
        }

        // ── Positive: on-site + cookies → connected ───────────────────────────
        boolean onSite = url.contains("demonicscans.org");
        CookieManager.getInstance().flush();
        String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
        boolean hasCookies = cookies != null && !cookies.trim().isEmpty();

        if (onSite && hasCookies) {
            if (!bridge.isSessionVerified()) {
                bridge.appendLog("system", "✅ Session confirmed via URL + cookies");
                bridge.setConnected(true);
            }
            return;
        }

        // ── On-site but no cookies yet — check DOM (don't clear session!) ────
        // NOTE: We NEVER call setConnected(false) here. Cookies may simply not
        // have been flushed to CookieManager yet (timing issue). Only an explicit
        // redirect to an auth page should cause a disconnect.
        if (onSite) {
            String js =
                "(function(){" +
                "  try {" +
                "    var loggedIn =" +
                "      !!document.querySelector('[class*=\"user\"], [id*=\"user\"]," +
                "          [class*=\"profile\"], [class*=\"avatar\"]," +
                "          [data-user], [id*=\"account\"]') ||" +
                "      !!document.querySelector('a[href*=\"pvp\"], a[href*=\"profile\"]');" +
                "    return loggedIn ? '1' : '0';" +
                "  } catch(e) { return '0'; }" +
                "})();";
            gameWebView.evaluateJavascript(js, result -> {
                if (bridge == null) return;
                if ("\"1\"".equals(result) && !bridge.isSessionVerified()) {
                    bridge.appendLog("system", "✅ Session confirmed via DOM");
                    bridge.setConnected(true);
                } else {
                    // Session uncertain — do NOT disconnect. Cookies may just not
                    // be readable yet from this context.
                    bridge.appendLog("debug", "Session uncertain (no cookies, dom=" + result + ") — keeping current state");
                }
            });
        }
        // If not on-site at all (some random URL): ignore silently.
    }

    // ── Overlay helpers ───────────────────────────────────────────────────────

    private void closeLoginOverlay() {
        if (loginContainer == null) return;
        loginContainer.setVisibility(View.INVISIBLE);
        // FIX: Only reset the button to IDLE when NOT already connected.
        // Previously this was unconditional — if the user tapped "← BACK TO APP"
        // after a successful "Save & Connect", the button was reset to IDLE and
        // the 200ms-delayed UI refresh could briefly race against the poll timer,
        // sometimes showing "Disconnected" for one tick before recovering.
        // We also call __pvpmUiRefresh immediately (no delay) so the connected
        // state is painted before the overlay finishes hiding.
        if (bridge == null || !bridge.isSessionVerified()) {
            setConnectButtonState(ConnectState.IDLE);
        }
        if (uiWebView != null) {
            uiWebView.evaluateJavascript(
                "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
        }
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

    // ── WebView settings ──────────────────────────────────────────────────────

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
            // FIX: InputStream.available() is unreliable for asset streams and may
            // return fewer bytes than the file length. A single read() also does not
            // guarantee filling the buffer. Use a ByteArrayOutputStream loop to read
            // the entire file regardless of how many chunks the OS delivers.
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) != -1) {
                baos.write(chunk, 0, n);
            }
            is.close();
            String script = baos.toString(StandardCharsets.UTF_8.name());
            view.evaluateJavascript("(function(){\n" + script + "\n})();", null);
        } catch (IOException e) {
            Log.e("PvPManager", "injectUserScript: " + e.getMessage());
        }
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

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
        // Hardware back key when overlay is open → always close overlay
        if (loginContainer != null && loginContainer.getVisibility() == View.VISIBLE) {
            closeLoginOverlay();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bridge != null) bridge.stopKeepalive();
        if (gameWebView  != null) { gameWebView.destroy();  gameWebView  = null; }
        if (uiWebView    != null) { uiWebView.destroy();    uiWebView    = null; }
        if (loginWebView != null) { loginWebView.destroy(); loginWebView = null; }
    }
}
