package com.pvpmanager;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
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
    private AndroidBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enable WebView debugging in debug builds
        WebView.setWebContentsDebuggingEnabled(true);

        // Root layout: full-screen FrameLayout
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // --- GAME WEBVIEW (hidden, runs the game) ---
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

        // --- UI WEBVIEW (fills screen, shows main.html) ---
        uiWebView = new WebView(this);
        uiWebView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        applyWebViewSettings(uiWebView, true);

        // Create and attach the JS bridge
        bridge = new AndroidBridge(this, gameWebView, uiWebView);
        uiWebView.addJavascriptInterface(bridge, "Android");

        uiWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Keep all navigation inside the UI WebView
                return false;
            }
        });
        uiWebView.loadUrl("file:///android_asset/main.html");
        root.addView(uiWebView);

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
            // Wrap in IIFE to avoid polluting global scope
            String wrapped = "(function(){\n" + script + "\n})();";
            view.evaluateJavascript(wrapped, null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If the UI WebView drifted away from main.html (e.g. after LoginActivity
        // closed and briefly caused a "File not found" blank screen), reload it.
        if (uiWebView != null) {
            String url = uiWebView.getUrl();
            if (url == null || !url.equals("file:///android_asset/main.html")) {
                uiWebView.loadUrl("file:///android_asset/main.html");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gameWebView != null) {
            gameWebView.destroy();
        }
        if (uiWebView != null) {
            uiWebView.destroy();
        }
    }
}
