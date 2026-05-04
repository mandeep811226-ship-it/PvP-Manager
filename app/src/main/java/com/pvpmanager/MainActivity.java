package com.pvpmanager;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

    // Exposed as package-level so AndroidBridge can reference them
    public static WebView gameWebView;
    public static WebView uiWebView;

    // Login overlay WebView — lives inside MainActivity, never a separate Activity
    private WebView loginWebView;
    private FrameLayout loginContainer;

    private AndroidBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView.setWebContentsDebuggingEnabled(true);

        // Root layout: full-screen FrameLayout that stacks all layers
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ── LAYER 1: GAME WEBVIEW (hidden, zero-size, runs game logic) ──
        gameWebView = new WebView(this);
        gameWebView.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
        applyWebViewSettings(gameWebView, false);
        gameWebView.setWebViewClient(new WebViewClient() {
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
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false; // keep all navigation inside the UI WebView
            }
        });
        uiWebView.loadUrl("file:///android_asset/main.html");
        root.addView(uiWebView);

        // ── LAYER 3: LOGIN OVERLAY (hidden by default, shown on demand) ──
        loginContainer = new FrameLayout(this);
        loginContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        loginContainer.setVisibility(View.GONE); // hidden until needed

        loginWebView = new WebView(this);
        loginWebView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        applyWebViewSettings(loginWebView, false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(loginWebView, true);

        loginWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleLoginUrl(view, request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleLoginUrl(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();

                // Detect successful login: navigated away from login/register pages
                if (url != null
                        && !url.contains("login")
                        && !url.contains("register")
                        && url.contains("demonicscans.org")) {

                    // Give cookies a moment to settle, then close overlay
                    loginWebView.postDelayed(() -> destroyLogin(), 300);
                }
            }
        });

        loginContainer.addView(loginWebView);
        root.addView(loginContainer);

        setContentView(root);

        // Enable cookies globally
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(gameWebView, true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(uiWebView, true);

        // Load the game page in the background WebView
        gameWebView.loadUrl("https://demonicscans.org/pvp.php");

        // Start the foreground service to keep WebViews alive
        Intent serviceIntent = new Intent(this, PvpService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    /**
     * Shows the login WebView overlay. Called from AndroidBridge.openLogin().
     * Runs on the main thread only.
     */
    public void showLogin() {
        if (loginContainer == null || loginWebView == null) return;
        loginWebView.loadUrl("https://demonicscans.org/login.php");
        loginContainer.setVisibility(View.VISIBLE);
        loginWebView.requestFocus();
    }

    /**
     * Hides and resets the login overlay. Called after successful login
     * or when the user presses Back while on the login screen.
     */
    public void destroyLogin() {
        if (loginContainer == null) return;
        loginContainer.setVisibility(View.GONE);
        loginWebView.loadUrl("about:blank"); // release any held page
        CookieManager.getInstance().flush();

        // Reload game WebView so it picks up the fresh session cookies
        if (gameWebView != null) {
            gameWebView.loadUrl("https://demonicscans.org/pvp.php");
        }

        // Notify the UI to refresh login state (LED, button label, etc.)
        if (uiWebView != null) {
            uiWebView.evaluateJavascript(
                    "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
        }
    }

    /**
     * Handle URL navigation inside the login WebView.
     * Keep demonicscans.org pages inside; open everything else externally.
     */
    private boolean handleLoginUrl(WebView view, String url) {
        if (url == null) return false;

        if (url.startsWith("https://demonicscans.org") ||
                url.startsWith("http://demonicscans.org")) {
            return false; // let WebView handle it normally
        }

        if (url.startsWith("intent://") || url.startsWith("market://") ||
                url.startsWith("tel:") || url.startsWith("mailto:")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                startActivity(intent);
            } catch (Exception e) {
                // ignore unresolvable intents
            }
            return true;
        }

        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                // ignore
            }
            return true;
        }

        return true; // block file://, data://, unknown schemes
    }

    private void applyWebViewSettings(WebView webView, boolean allowFileAccess) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccessFromFileURLs(allowFileAccess);
        settings.setAllowUniversalAccessFromFileURLs(allowFileAccess);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
    }

    /**
     * Reads pvp_manager.js from assets and injects it into the given WebView.
     */
    public void injectUserScript(WebView view) {
        try {
            InputStream is = getAssets().open("pvp_manager.js");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            String script = new String(buffer, StandardCharsets.UTF_8);
            String wrapped = "(function(){\n" + script + "\n})();";
            view.evaluateJavascript(wrapped, null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Handle back button: close login overlay first if it is visible.
     */
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
        if (gameWebView != null) gameWebView.destroy();
        if (uiWebView != null)   uiWebView.destroy();
        if (loginWebView != null) loginWebView.destroy();
    }
}
