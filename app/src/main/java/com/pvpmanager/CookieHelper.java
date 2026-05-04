package com.pvpmanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import android.webkit.WebView;

/**
 * Handles cookie management and session detection for demonicscans.org.
 * Now integrates with the Bridge's session verification flag.
 */
public class CookieHelper {

    private static final String DOMAIN_HTTPS = "https://demonicscans.org";
    private static final String DOMAIN_BARE  = "demonicscans.org";
    private static final String PREFS_NAME = "pvp_manager_prefs";
    private static final String KEY_SESSION_VERIFIED = "session_verified";
    private static final String KEY_SESSION_TIMESTAMP = "session_timestamp";

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
    // SESSION VERIFICATION (bridge flag)
    // -------------------------------------------------------------------------

    private static boolean isBridgeFlagSet() {
        try {
            SharedPreferences prefs = getSharedPreferences();
            if (prefs == null) return false;
            long ts = prefs.getLong(KEY_SESSION_TIMESTAMP, 0);
            boolean verified = prefs.getBoolean(KEY_SESSION_VERIFIED, false);
            return verified && (System.currentTimeMillis() - ts) < 10 * 60 * 1000;
        } catch (Exception e) {
            return false;
        }
    }

    private static SharedPreferences getSharedPreferences() {
        try {
            // We need a context – static helper, assume we can get it from MainActivity.
            // This is a slight hack; the better way is to pass context, but we'll keep it simple.
            // In practice, the bridge flag is checked before this method in AndroidBridge.
            return android.app.ActivityThread.currentApplication()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // CONNECTION DETECTION (FINAL)
    // -------------------------------------------------------------------------

    /**
     * Returns true if a valid session exists.
     * Prefers the bridge's DOM‑verified flag; falls back to cookie length check.
     */
    public static boolean isConnected() {
        // First, check the verified flag from the bridge (most reliable)
        if (isBridgeFlagSet()) {
            return true;
        }

        // Fallback: classic cookie existence check
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
