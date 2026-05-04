package com.pvpmanager;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.webkit.CookieManager;
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
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();

                // If the user has reached a page that isn't the login page,
                // they are likely logged in — close this activity.
                if (url != null && !url.contains("login") && !url.contains("register")
                        && url.contains("demonicscans.org")
                        && CookieHelper.isConnected()) {
                    // Also reload the game WebView in MainActivity so it picks up cookies
                    if (MainActivity.gameWebView != null) {
                        MainActivity.gameWebView.post(() ->
                            MainActivity.gameWebView.loadUrl("https://demonicscans.org/pvp.php"));
                    }
                    finish();
                }
            }
        });

        loginWebView.loadUrl("https://demonicscans.org/login.php");
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
