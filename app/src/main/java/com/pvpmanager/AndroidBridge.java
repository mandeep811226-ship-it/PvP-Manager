package com.pvpmanager;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.CookieManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class AndroidBridge {

    private static final String TAG          = "PvPManager";
    private static final String PREFS_NAME   = "pvp_manager_prefs";
    private static final String KEY_RUNNING  = "running";
    private static final String KEY_DEBUG    = "debug_logs";
    private static final String KEY_POLL     = "poll_interval_ms";
    private static final String KEY_STRATEGY = "strategy_json";
    private static final String KEY_SESSION  = "session_verified";

    /**
     * After setConnected(true) the verifySession() method is suppressed for
     * GRACE_MS so the gameWebView can navigate from login → pvp.php without
     * a false "auth-page redirect = disconnect" being triggered on any
     * intermediate page load.
     */
    private static final long GRACE_MS    = 15_000;  // 15 seconds
    private volatile long     connectedAt = 0;

    // FIFO log buffer — 500 entries max, oldest-first
    private static final int LOG_MAX = 500;
    private final java.util.ArrayDeque<String> logLines = new java.util.ArrayDeque<>();

    private final Context           context;
    private final WebView           gameWebView;
    private final WebView           uiWebView;
    private final SharedPreferences prefs;
    private final Handler           mainHandler;

    public AndroidBridge(Context ctx, WebView game, WebView ui) {
        this.context     = ctx;
        this.gameWebView = game;
        this.uiWebView   = ui;
        this.prefs       = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // ── Session ───────────────────────────────────────────────────────────────

    /**
     * Set the connected state. Trusted Java-only — NOT a @JavascriptInterface.
     * Uses synchronous commit() so the next getState() call sees the new value.
     */
    public void setConnected(boolean connected) {
        SharedPreferences.Editor ed = prefs.edit();
        if (connected) {
            connectedAt = System.currentTimeMillis();
            ed.putBoolean(KEY_SESSION, true);
        } else {
            connectedAt = 0;
            ed.remove(KEY_SESSION);
        }
        ed.commit();
        appendLog("system", connected ? "✅ Connected" : "❌ Disconnected");
        notifyUiStateChanged();
    }

    public boolean isSessionVerified() {
        return prefs.getBoolean(KEY_SESSION, false);
    }

    /**
     * True for GRACE_MS milliseconds after the last setConnected(true).
     * While in grace period, verifySession() must not clear the session.
     */
    public boolean inGracePeriod() {
        if (connectedAt == 0) return false;   // never been connected
        return (System.currentTimeMillis() - connectedAt) < GRACE_MS;
    }

    // ── @JavascriptInterface ─────────────────────────────────────────────────

    /**
     * Called by the UI (main.html) when the user taps the Login / Connect button.
     * Opens the login overlay by expanding gameWebView to fullscreen.
     * Matches the friend's working APK exactly: one openLogin() call, no
     * separate "Save & Connect" button needed.
     */
    @JavascriptInterface
    public void openLogin() {
        mainHandler.post(() -> {
            if (context instanceof MainActivity)
                ((MainActivity) context).showLogin();
        });
    }

    @JavascriptInterface
    public void logout() {
        connectedAt = 0;
        prefs.edit().putBoolean(KEY_RUNNING, false).remove(KEY_SESSION).commit();
        CookieHelper.clearAll();
        appendLog("system", "Logged out — cookies cleared");
        notifyUiStateChanged();
    }

    @JavascriptInterface
    public void dumpCookies() {
        appendLog("system", "=== COOKIE DUMP ===");
        CookieManager.getInstance().flush();
        String c = CookieManager.getInstance().getCookie("https://demonicscans.org");
        int len = c == null ? 0 : c.length();
        appendLog("debug", "len=" + len + " | " +
                (c != null ? (len > 200 ? c.substring(0, 200) + "…" : c) : "null"));
        if (gameWebView != null) {
            mainHandler.post(() ->
                gameWebView.evaluateJavascript("document.cookie", v ->
                    appendLog("debug", "doc.cookie=" + v)));
        }
        appendLog("system", "=== END ===");
        notifyUiStateChanged();
    }

    @JavascriptInterface
    public String getState() {
        try {
            JSONObject s = new JSONObject();

            boolean connected = isSessionVerified();
            s.put("connected", connected);

            boolean running = prefs.getBoolean(KEY_RUNNING, false);
            if (running && !connected) {
                prefs.edit().putBoolean(KEY_RUNNING, false).commit();
                running = false;
                appendLog("system", "Session ended — bot stopped");
            }
            s.put("running", running);

            JSONObject cfg = new JSONObject();
            cfg.put("debugLogs",     prefs.getBoolean(KEY_DEBUG, false));
            cfg.put("pollIntervalMs",
                    Integer.parseInt(prefs.getString(KEY_POLL, "1500")));
            s.put("config", cfg);

            s.put("strategy", new JSONObject(
                    prefs.getString(KEY_STRATEGY, "{\"enabled\":false,\"entries\":[]}")));

            StringBuilder sb = new StringBuilder();
            synchronized (logLines) {
                for (String line : logLines) sb.append(line).append("\n");
            }
            s.put("logs", sb.toString());

            String live = prefs.getString("cached_live_state", null);
            if (live != null) {
                JSONObject lv = new JSONObject(live);
                s.put("match",        lv.optJSONObject("match")       != null ? lv.getJSONObject("match")       : new JSONObject());
                s.put("stats",        lv.optJSONObject("stats")       != null ? lv.getJSONObject("stats")       : new JSONObject());
                s.put("skillList",    lv.optJSONArray("skillList")    != null ? lv.getJSONArray("skillList")    : new JSONArray());
                s.put("matchHistory", lv.optJSONArray("matchHistory") != null ? lv.getJSONArray("matchHistory") : new JSONArray());
            } else {
                s.put("match",        new JSONObject("{\"active\":false}"));
                s.put("stats",        new JSONObject());
                s.put("skillList",    new JSONArray());
                s.put("matchHistory", new JSONArray());
            }

            return s.toString();
        } catch (JSONException e) {
            Log.e(TAG, "getState: " + e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @JavascriptInterface
    public void refreshLiveState() {
        mainHandler.post(() -> {
            if (gameWebView == null) return;
            gameWebView.evaluateJavascript(
                    "(function(){ return JSON.stringify(window.__pvpmState || null); })()",
                    value -> {
                        if (value == null || value.equals("null")) return;
                        try {
                            String raw = value;
                            if (raw.startsWith("\"") && raw.endsWith("\""))
                                raw = raw.substring(1, raw.length() - 1)
                                         .replace("\\\"", "\"")
                                         .replace("\\\\", "\\")
                                         .replace("\\n", "\n");
                            prefs.edit().putString("cached_live_state", raw).apply();
                        } catch (Exception e) {
                            Log.e(TAG, "refreshLiveState: " + e.getMessage());
                        }
                    });
        });
    }

    @JavascriptInterface
    public void setRunning(boolean running) {
        if (running && !isSessionVerified()) {
            appendLog("system", "Cannot start — not connected");
            notifyUiStateChanged();
            return;
        }
        prefs.edit().putBoolean(KEY_RUNNING, running).commit();
        evalInGame("if(window.__pvpmSetRunning) window.__pvpmSetRunning(" + running + ");");
        appendLog("system", running ? "Bot started" : "Bot stopped");
        notifyUiStateChanged();
    }

    @JavascriptInterface
    public void setStrategyEnabled(boolean enabled) {
        try {
            JSONObject obj = new JSONObject(
                    prefs.getString(KEY_STRATEGY, "{\"enabled\":false,\"entries\":[]}"));
            obj.put("enabled", enabled);
            prefs.edit().putString(KEY_STRATEGY, obj.toString()).commit();
            evalInGame("localStorage.setItem('et_pvp_solo_strategy','"
                    + obj.toString().replace("'", "\\'") + "');");
        } catch (JSONException e) {
            appendLog("error", "setStrategyEnabled: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void saveStrategy(String json) {
        try {
            JSONArray entries = new JSONArray(json);
            JSONObject obj = new JSONObject(
                    prefs.getString(KEY_STRATEGY, "{\"enabled\":false,\"entries\":[]}"));
            obj.put("entries", entries);
            prefs.edit().putString(KEY_STRATEGY, obj.toString()).commit();
            evalInGame("localStorage.setItem('et_pvp_solo_strategy','"
                    + obj.toString().replace("'", "\\'") + "');");
        } catch (JSONException e) {
            appendLog("error", "saveStrategy: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void setConfigBool(String key, boolean value) {
        if ("debug_logs".equals(key)) {
            prefs.edit().putBoolean(KEY_DEBUG, value).commit();
            evalInGame("localStorage.setItem('et_pvp_debug_logs','" + value + "');");
        }
    }

    @JavascriptInterface
    public void setConfigValue(String key, String value) {
        if ("poll_interval_ms".equals(key)) {
            prefs.edit().putString(KEY_POLL, value).commit();
        } else if ("clear_history".equals(key)) {
            try {
                String cached = prefs.getString("cached_live_state", null);
                if (cached != null) {
                    JSONObject lv = new JSONObject(cached);
                    lv.put("matchHistory", new JSONArray());
                    prefs.edit().putString("cached_live_state", lv.toString()).commit();
                }
            } catch (JSONException e) {
                appendLog("error", "clearHistory: " + e.getMessage());
            }
        }
    }

    @JavascriptInterface
    public void clearLogs() {
        synchronized (logLines) { logLines.clear(); }
        appendLog("system", "Logs cleared");
        notifyUiStateChanged();
    }

    @JavascriptInterface
    public void copyLogs() {
        ClipboardManager cb =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) {
            StringBuilder sb = new StringBuilder();
            synchronized (logLines) {
                for (String l : logLines) sb.append(l).append("\n");
            }
            cb.setPrimaryClip(ClipData.newPlainText("PvP Logs", sb.toString()));
            appendLog("system", "Logs copied to clipboard");
        }
    }

    @JavascriptInterface
    public void openSettings() {
        mainHandler.post(() -> {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        });
    }

    @JavascriptInterface
    public void requestIgnoreBatteryOptimization() {
        mainHandler.post(() -> {
            try {
                context.startActivity(new Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + context.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                appendLog("warning", "Battery opt: " + e.getMessage());
            }
        });
    }

    @JavascriptInterface
    public void openProfile(String uid) {
        openUrl("https://demonicscans.org/player.php?id=" + uid);
    }

    @JavascriptInterface
    public void openMatch(String matchId) {
        openUrl("https://demonicscans.org/pvp_battle.php?match_id=" + matchId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void openUrl(String url) {
        mainHandler.post(() -> context.startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    private void evalInGame(String js) {
        mainHandler.post(() -> {
            if (gameWebView != null) gameWebView.evaluateJavascript(js, null);
        });
    }

    public void appendLog(String type, String msg) {
        String line = System.currentTimeMillis() + "|" + type + "|" + msg;
        synchronized (logLines) {
            logLines.addLast(line);
            while (logLines.size() > LOG_MAX) logLines.removeFirst();
        }
        Log.d(TAG, type + ": " + msg);
    }

    public void notifyUiStateChanged() {
        mainHandler.post(() -> {
            if (uiWebView != null)
                uiWebView.evaluateJavascript(
                        "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
        });
    }
}
