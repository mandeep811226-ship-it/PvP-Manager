package com.pvpmanager;

import android.webkit.CookieManager;
import android.webkit.WebView;

/**
 * Handles cookie management and session detection for demonicscans.org.
 * Primary session verification is done via AndroidBridge.isSessionVerified().
 * This class provides a basic cookie existence fallback only.
 */
public class CookieHelper {

    private static final String DOMAIN_HTTPS = "https://demonicscans.org";
    private static final String DOMAIN_BARE  = "demonicscans.org";

    // -------------------------------------------------------------------------
    // CORE OPERATIONS
    // -------------------------------------------------------------------------

    public static void flush() {
        CookieManager.getInstance().flush();
    }

    public static void clearAll() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
    }

    public static void configure(WebView webView) {
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
    }

    // -------------------------------------------------------------------------
    // CONNECTION DETECTION (FALLBACK)
    // -------------------------------------------------------------------------

    /**
     * Returns true if a non‑trivial cookie exists for the domain.
     * This is a fallback; the authoritative session state is maintained
     * by AndroidBridge.isSessionVerified().
     */
    public static boolean isConnected() {
        String cookies = getRawCookies();
        if (cookies == null || cookies.trim().isEmpty()) {
            return false;
        }

        String[] pairs = cookies.split(";");
        for (String pair : pairs) {
            pair = pair.trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;

            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();

            if (!key.isEmpty() && value.length() > 5) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // DEBUG / ACCESS
    // -------------------------------------------------------------------------

    public static String getCookiesForDomain() {
        return getRawCookies();
    }

    // -------------------------------------------------------------------------
    // INTERNAL HELPERS
    // -------------------------------------------------------------------------

    private static String getRawCookies() {
        String c1 = CookieManager.getInstance().getCookie(DOMAIN_HTTPS);
        String c2 = CookieManager.getInstance().getCookie(DOMAIN_BARE);

        StringBuilder sb = new StringBuilder();
        if (c1 != null && !c1.trim().isEmpty()) {
            sb.append(c1).append("; ");
        }
        if (c2 != null && !c2.trim().isEmpty()) {
            sb.append(c2);
        }
        return sb.toString().trim();
    }
}
