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
     * We check for a non-empty cookie string for the game domain that contains
     * a plausible session / user identifier key.
     */
    public static boolean isConnected() {
        String cookies = CookieManager.getInstance().getCookie(GAME_DOMAIN);
        if (cookies == null || cookies.trim().isEmpty()) {
            return false;
        }
        // The site sets a cookie that contains the user's ID or session token.
        // Accept any of the common session cookie names.
        return cookies.contains("PHPSESSID") ||
               cookies.contains("user_id") ||
               cookies.contains("uid") ||
               cookies.contains("session");
    }

    /**
     * Returns the raw cookie string for the game domain (for debugging).
     */
    public static String getCookiesForDomain() {
        String cookies = CookieManager.getInstance().getCookie(GAME_DOMAIN);
        return cookies != null ? cookies : "";
    }
}
