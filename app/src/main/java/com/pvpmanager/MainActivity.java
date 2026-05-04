package com.pvpmanager;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    public static WebView gameWebView;
    public static WebView uiWebView;

    private WebView loginWebView;
    private FrameLayout loginContainer;

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
        // Use INVISIBLE (NOT GONE). A View.GONE WebView fails to render
        // on many Android versions — causes "File not found" immediately.
        loginContainer = new FrameLayout(this);
        loginContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        loginContainer.setVisibility(View.INVISIBLE);

        loginWebView = new WebView(this);
        loginWebView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
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
                CookieManager.getInstance().flush();

                if (url == null) return;

                // Make overlay visible once the login page has actually loaded
                if (loginContainer.getVisibility() != View.VISIBLE
                        && url.startsWith("http")) {
                    loginContainer.setVisibility(View.VISIBLE);
                }

                // Detect successful login: navigated away from login/register pages
                // The site redirects to bookmarks after login — any demonicscans.org
                // page that is NOT the login/register page means login succeeded.
                if (!url.equals("about:blank")
                        && !url.contains("signin")
                        && !url.contains("login")
                        && !url.contains("register")
                        && url.contains("demonicscans.org")) {
                    // Use 1200ms delay to give CookieManager time to persist cookies
                    loginWebView.postDelayed(() -> destroyLogin(), 1200);
                }
            }
        });

        // Pre-warm the WebView with about:blank so it initialises its
        // network stack before we ask it to load a real page.
        loginWebView.loadUrl("about:blank");

        loginContainer.addView(loginWebView);
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

    /**
     * Called by AndroidBridge.openLogin() on the main thread.
     * Navigates the login WebView to the login page.
     * The overlay becomes VISIBLE only after onPageFinished fires,
     * so the user never sees a blank flash or an error page.
     */
    public void showLogin() {
        if (loginWebView == null || loginContainer == null) return;
        loginContainer.setVisibility(View.INVISIBLE); // page will show once loaded
        loginWebView.loadUrl("https://demonicscans.org/signin.php");
        loginWebView.requestFocus();
    }

    /**
     * Hides the login overlay and reloads the game session.
     * We wait 800 ms after flushing cookies so the CookieManager has time
     * to persist the session cookies before gameWebView reloads pvp.php,
     * and before the UI queries isConnected().
     */
    public void destroyLogin() {
        if (loginContainer == null) return;
        loginContainer.setVisibility(View.INVISIBLE);
        loginWebView.loadUrl("about:blank");

        // Flush cookies first, then wait before doing anything else
        CookieManager.getInstance().flush();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // Second flush to make sure cookies are fully persisted
            CookieManager.getInstance().flush();

            if (gameWebView != null) {
                gameWebView.loadUrl("https://demonicscans.org/pvp.php");
            }

            // Wait another 600 ms for the game page to start loading,
            // then tell the UI to refresh (so isConnected() sees the cookies)
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (uiWebView != null) {
                    uiWebView.evaluateJavascript(
                            "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
                }
            }, 600);
        }, 800);
    }

    /**
     * URL policy for the login WebView.
     *
     * CRITICAL: Return false (let WebView load it) for ALL http/https URLs.
     * Never restrict to demonicscans.org only — the login flow can pass
     * through redirect chains on other domains (CDN, OAuth, etc.).
     * Returning true for any redirect in that chain causes ERR_FILE_NOT_FOUND.
     *
     * Only intercept non-http schemes that WebView cannot handle natively.
     */
    private boolean handleLoginUrl(String url) {
        if (url == null) return false;

        // All http and https — always allow through
        if (url.startsWith("https://") || url.startsWith("http://")) {
            return false;
        }

        // intent:// and market:// — resolve via Android
        if (url.startsWith("intent://") || url.startsWith("market://")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                startActivity(intent);
            } catch (Exception ignored) {}
            return true;
        }

        // tel:, mailto: — open with system apps
        if (url.startsWith("tel:") || url.startsWith("mailto:")) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {}
            return true;
        }

        // Block everything else: file://, data://, javascript:, etc.
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
            if (loginWebView.canGoBack()) {
                loginWebView.goBack();
            } else {
                destroyLogin();
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
