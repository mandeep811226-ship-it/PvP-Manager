package com.pvpmanager;

import android.webkit.CookieManager;
import android.webkit.WebView;

/**
 * Syncs CookieManager across all WebViews and provides helper methods
 * for session state detection.
 */
public class CookieHelper {

    private static final String GAME_DOMAIN = "https://demonicscans.org";

    /**
     * Flush all cookie changes to persistent storage.
     */
    public static void flush() {
        CookieManager.getInstance().flush();
    }

    /**
     * Clear all cookies from the CookieManager (logout).
     */
    public static void clearAll() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
    }

    /**
     * Enable third-party cookies on a WebView and ensure cookie acceptance is on.
     */
    public static void configure(WebView webView) {
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
    }

    /**
     * Returns true if the user appears to be logged in.
     * Checks both "https://demonicscans.org" and "demonicscans.org" because
     * Android's CookieManager may store cookies under either key depending on
     * which WebView set them first.
     */
    public static boolean isConnected() {
        // Check both domain formats — CookieManager is inconsistent
        String cookies1 = CookieManager.getInstance().getCookie("https://demonicscans.org");
        String cookies2 = CookieManager.getInstance().getCookie("demonicscans.org");

        String cookies = "";
        if (cookies1 != null) cookies += cookies1;
        if (cookies2 != null) cookies += cookies2;

        if (cookies.trim().isEmpty()) {
            return false;
        }
        // Accept any common session cookie name
        return cookies.contains("PHPSESSID") ||
               cookies.contains("user_id") ||
               cookies.contains("uid") ||
               cookies.contains("session") ||
               cookies.contains("remember") ||
               cookies.contains("auth");
    }

    /**
     * Returns the raw cookie string for the game domain (for debugging).
     */
    public static String getCookiesForDomain() {
        String cookies = CookieManager.getInstance().getCookie(GAME_DOMAIN);
        return cookies != null ? cookies : "";
    }
}
