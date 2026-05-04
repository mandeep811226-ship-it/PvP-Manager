package com.pvpmanager;

import android.webkit.CookieManager;
import android.webkit.WebView;

/**
 * Syncs CookieManager across all WebViews and provides helper methods
 * for session state detection.
 */
public class CookieHelper {

    private static final String GAME_DOMAIN_HTTPS = "https://demonicscans.org";
    private static final String GAME_DOMAIN_BARE  = "demonicscans.org";

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
     *
     * FIX: The original implementation checked for a hardcoded list of cookie
     * names (PHPSESSID, user_id, uid, session, remember, auth). If demonicscans.org
     * uses a different session cookie name — which is very likely for a custom PHP
     * app — every one of those checks fails and isConnected() always returns false,
     * even when the user is genuinely logged in.
     *
     * New strategy (layered, most-specific first):
     *
     *   1. Check known cookie names for demonicscans specifically (uid, user_id,
     *      PHPSESSID, remember_token, auth_token, logged_in, session_id, sid).
     *   2. Fall back: if the domain has ANY non-trivial cookie string (i.e. the
     *      raw cookie header is not empty/blank after combining both domain formats),
     *      treat the user as connected. A bare domain with cookies means the server
     *      set *something* — far better than false-negatives from a strict name list.
     *
     * This makes connection detection robust regardless of what cookie name the
     * site actually uses, while still being secure (requires the cookie to be for
     * the correct domain).
     */
    public static boolean isConnected() {
        String cookies = getRawCookies();
        if (cookies.trim().isEmpty()) {
            return false;
        }

        // Layer 1: check well-known session cookie names (case-sensitive as set by
        // the browser; PHP defaults vary). Cast a wide net to cover the site's
        // actual cookie name.
        if (cookies.contains("PHPSESSID")      ||
            cookies.contains("phpsessid")      ||
            cookies.contains("user_id")        ||
            cookies.contains("uid")            ||
            cookies.contains("session_id")     ||
            cookies.contains("sid=")           ||
            cookies.contains("remember_token") ||
            cookies.contains("auth_token")     ||
            cookies.contains("logged_in")      ||
            cookies.contains("remember")       ||
            cookies.contains("auth")           ||
            cookies.contains("session")) {
            return true;
        }

        // Layer 2: fallback — if the site set ANY cookie on this domain that has
        // a non-trivial value (key=value format), consider the user connected.
        // This handles unknown/custom cookie names used by demonicscans.org.
        // An unauthenticated visit typically yields no cookies or only a bare
        // analytics cookie; a logged-in session always produces a session cookie.
        return looksLikeSessionCookie(cookies);
    }

    /**
     * Returns the raw cookie string for the game domain (for debugging).
     */
    public static String getCookiesForDomain() {
        return getRawCookies();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Retrieves and concatenates cookies from both domain formats.
     * CookieManager is inconsistent: it may store under "https://domain" or
     * "domain" depending on which WebView set them first.
     */
    private static String getRawCookies() {
        String c1 = CookieManager.getInstance().getCookie(GAME_DOMAIN_HTTPS);
        String c2 = CookieManager.getInstance().getCookie(GAME_DOMAIN_BARE);

        StringBuilder sb = new StringBuilder();
        if (c1 != null && !c1.trim().isEmpty()) sb.append(c1).append("; ");
        if (c2 != null && !c2.trim().isEmpty()) sb.append(c2);
        return sb.toString().trim();
    }

    /**
     * Returns true if the cookie string contains at least one key=value pair
     * whose value is non-empty and non-trivially short (≥4 chars).
     * This filters out bare tracking pixels / empty cookies while catching any
     * real session token regardless of key name.
     */
    private static boolean looksLikeSessionCookie(String cookies) {
        if (cookies == null || cookies.isEmpty()) return false;
        String[] pairs = cookies.split(";");
        for (String pair : pairs) {
            pair = pair.trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;                    // no key
            String value = pair.substring(eq + 1).trim();
            if (value.length() >= 4) return true;     // has a real value
        }
        return false;
    }
}
