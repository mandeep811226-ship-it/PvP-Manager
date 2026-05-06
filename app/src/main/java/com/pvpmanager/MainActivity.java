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
import android.provider.Settings;
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

    // ── BLS File Picker ──────────────────────────────────────────────────────
    // Uses the modern Activity Result API (no deprecated onActivityResult needed).
    private ActivityResultLauncher<String[]> blsFilePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);

        notificationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> Log.d("PvPManager", "POST_NOTIFICATIONS granted=" + granted));

        // Register the BLS file picker — opens on demand from requestBlsFilePicker()
        blsFilePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null) return; // user cancelled
                try {
                    // Persist read permission across reboots (best-effort)
                    try {
                        getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {}

                    InputStream is = getContentResolver().openInputStream(uri);
                    if (is != null && bridge != null) {
                        // Run on background thread — reading can be slow for large files
                        new Thread(() -> {
                            bridge.deliverBlsFileContent(is);
                        }).start();
                    }
                } catch (Exception e) {
                    Log.e("PvPManager", "BLS file open failed: " + e.getMessage());
                    if (bridge != null) bridge.appendLog("error", "BLS file open failed: " + e.getMessage());
                }
            });

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
                injectUserScript(view);
                // After page load, sync the stored BLS memory into game WebView localStorage
                // so pvp_manager.js can read bls_memory immediately without waiting for a match
                syncBlsToGameWebView();
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

        gameWebView.loadUrl(PVP_URL);

        // Start foreground service
        Intent svcIntent = new Intent(this, PvpService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent);
        else startService(svcIntent);

        requestRuntimePermissions();

        // Startup cookie validation
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (bridge != null && bridge.isSessionVerified()) {
                String cookies = CookieManager.getInstance()
                        .getCookie("https://demonicscans.org");
                boolean hasCookies = cookies != null && !cookies.trim().isEmpty();
                if (!hasCookies) {
                    bridge.setConnected(false);
                    bridge.appendLog("system",
                        "Startup: session flag was set but no cookies found — reset to disconnected");
                } else {
                    bridge.appendLog("system",
                        "Startup: session resumed, cookies present (" + cookies.length() + " chars)");
                }
            }
        }, 1000);
    }

    // ── BLS File Picker entry point ───────────────────────────────────────────

    /**
     * Called by AndroidBridge.requestBlsImport() (from JS).
     * Launches the system file picker for JSON files.
     * The result is delivered to blsFilePickerLauncher above.
     */
    public void requestBlsFilePicker() {
        try {
            blsFilePickerLauncher.launch(new String[]{"application/json", "*/*"});
        } catch (Exception e) {
            Log.e("PvPManager", "Could not open file picker: " + e.getMessage());
            if (bridge != null) bridge.appendLog("error", "File picker failed: " + e.getMessage());
        }
    }

    /**
     * Syncs the stored BLS memory from SharedPreferences into the game WebView's
     * localStorage so pvp_manager.js always has fresh data.
     * Called after every gameWebView page load.
     */
    private void syncBlsToGameWebView() {
        if (bridge == null || gameWebView == null) return;
        String blsJson = bridge.getBlsMemory();
        if (blsJson == null || blsJson.equals("{}")) return;
        final String escaped = blsJson.replace("\\", "\\\\").replace("'", "\\'");
        new Handler(Looper.getMainLooper()).post(() -> {
            gameWebView.evaluateJavascript(
                "try{localStorage.setItem('bls_memory','" + escaped + "');}catch(e){}", null);
        });
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
        if (bridge != null && bridge.isSessionVerified()) {
            setConnectButtonState(ConnectState.SUCCESS);
        } else {
            setConnectButtonState(ConnectState.IDLE);
        }
        loginContainer.setVisibility(View.VISIBLE);
        loginWebView.requestFocus();
        String cur = loginWebView.getUrl();
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

    public void saveConnect() {
        if (bridge == null || loginWebView == null) {
            Toast.makeText(this, "Not ready — try again", Toast.LENGTH_SHORT).show();
            return;
        }

        bridge.appendLog("system", "SAVE & CONNECT tapped");
        CookieManager.getInstance().flush();

        String currentUrl = loginWebView.getUrl();
        bridge.appendLog("debug", "loginWebView URL: " + currentUrl);

        boolean urlOk = currentUrl != null
                && currentUrl.contains("demonicscans.org")
                && !currentUrl.contains("sign.php")
                && !currentUrl.contains("signin")
                && !currentUrl.contains("login")
                && !currentUrl.contains("register")
                && !currentUrl.equals("about:blank");

        String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
        boolean hasCookies = cookies != null && !cookies.trim().isEmpty();

        bridge.appendLog("debug", "urlOk=" + urlOk + " hasCookies=" + hasCookies
                + " cookieLen=" + (cookies == null ? 0 : cookies.length()));

        if (!urlOk || !hasCookies) {
            setConnectButtonState(ConnectState.FAILURE);
            String reason = !hasCookies ? "No cookies found — please log in first"
                                        : "Please log in on the site, then tap Save & Connect";
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
            bridge.appendLog("warning", "saveConnect failed: urlOk=" + urlOk + " hasCookies=" + hasCookies);
            return;
        }

        bridge.setConnected(true);
        bridge.appendLog("system", "Session saved ✓ cookies=" + cookies.length() + " chars");

        setConnectButtonState(ConnectState.SUCCESS);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            closeLoginOverlay();
            if (uiWebView != null) {
                uiWebView.evaluateJavascript(
                    "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            }
        }, 600);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (gameWebView != null) {
                CookieManager.getInstance().flush();
                bridge.appendLog("debug", "Loading " + PVP_URL + " in gameWebView");
                gameWebView.loadUrl(PVP_URL);
            }
        }, 800);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null) {
                uiWebView.evaluateJavascript(
                    "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            }
        }, 1200);
    }

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
                            "  })('" + finalJson.replace("'", "\\'") + "');";
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

    // ── Overlay helpers ───────────────────────────────────────────────────────

    private void closeLoginOverlay() {
        if (loginContainer == null) return;
        loginContainer.setVisibility(View.INVISIBLE);
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

        StringBuilder combined = new StringBuilder();

        String[] scripts = {
            "pvp_manager.js",
            "pvp_stats_display.js"
        };

        for (String file : scripts) {

            InputStream is = getAssets().open(file);

            java.io.ByteArrayOutputStream baos =
                    new java.io.ByteArrayOutputStream();

            byte[] chunk = new byte[8192];

            int n;

            while ((n = is.read(chunk)) != -1) {
                baos.write(chunk, 0, n);
            }

            is.close();

            combined.append("\n");
            combined.append(
                    baos.toString(StandardCharsets.UTF_8.name())
            );
            combined.append("\n");
        }

        view.evaluateJavascript(
                "(function(){\n" +
                        combined.toString() +
                        "\n})();",
                null
        );

        Log.d("PvPManager", "Injected all scripts successfully");

    } catch (IOException e) {

        Log.e("PvPManager",
                "injectUserScript error: " + e.getMessage());

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
