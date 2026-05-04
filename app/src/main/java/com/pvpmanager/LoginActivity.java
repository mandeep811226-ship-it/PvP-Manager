package com.pvpmanager;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Full-screen WebView that opens the game login page.
 * Once the user logs in, session cookies are captured by CookieManager
 * and shared with all other WebViews automatically.
 */
public class LoginActivity extends Activity {

    private static final String LOGIN_URL = "https://demonicscans.org/login.php";

    private WebView loginWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        loginWebView = new WebView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);

        WebSettings settings = loginWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(loginWebView, true);

        loginWebView.setWebViewClient(new WebViewClient() {

            /**
             * KEY FIX: Handle all URL schemes properly.
             * Without this, any non-http URL (intent://, market://, tel://, etc.)
             * causes "File not found" because WebView tries to load it as a file.
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(view, request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(view, url);
            }

            private boolean handleUrl(WebView view, String url) {
                if (url == null) return false;

                // Let all demonicscans.org URLs load normally inside this WebView
                if (url.startsWith("https://demonicscans.org") ||
                    url.startsWith("http://demonicscans.org")) {
                    return false; // let WebView handle it normally
                }

                // Handle intent:// and app deep links - open externally
                if (url.startsWith("intent://") || url.startsWith("market://") ||
                    url.startsWith("tel:") || url.startsWith("mailto:")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        startActivity(intent);
                    } catch (Exception e) {
                        // Ignore unresolvable intents
                    }
                    return true;
                }

                // Other http/https - open in external browser
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception e) {
                        // Ignore
                    }
                    return true;
                }

                // Block everything else (file://, data://, unknown schemes)
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();

                // If the user has reached a non-login page, they are logged in
                if (url != null && !url.contains("login") && !url.contains("register")
                        && url.contains("demonicscans.org")
                        && CookieHelper.isConnected()) {
                    // Reload the game WebView so it picks up the new cookies
                    if (MainActivity.gameWebView != null) {
                        MainActivity.gameWebView.post(() ->
                            MainActivity.gameWebView.loadUrl("https://demonicscans.org/pvp.php"));
                    }
                    finish();
                }
            }
        });

        loginWebView.loadUrl(LOGIN_URL);
        setContentView(loginWebView, lp);
    }

    @Override
    public void onBackPressed() {
        if (loginWebView.canGoBack()) {
            loginWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (loginWebView != null) {
            loginWebView.destroy();
        }
    }
}
