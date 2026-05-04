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

    private static final String PREFS_NAME          = "pvp_manager_prefs";
    private static final String KEY_RUNNING          = "running";
    private static final String KEY_DEBUG_LOGS       = "debug_logs";
    private static final String KEY_POLL_INTERVAL    = "poll_interval_ms";
    private static final String KEY_STRATEGY         = "strategy_json";
    private static final String KEY_SESSION_VERIFIED = "session_verified";
    private static final String KEY_SESSION_TS       = "session_timestamp";

    // 24-hour session window. gameWebView re-verifies on every page load anyway.
    private static final long SESSION_MAX_AGE_MS = 24L * 60 * 60 * 1000;

    private final Context          context;
    private final WebView          gameWebView;
    private final WebView          uiWebView;
    private final SharedPreferences prefs;
    private final Handler          mainHandler;
    private final StringBuilder    logBuffer = new StringBuilder();

    public AndroidBridge(Context context, WebView gameWebView, WebView uiWebView) {
        this.context     = context;
        this.gameWebView = gameWebView;
        this.uiWebView   = uiWebView;
        this.prefs       = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // -------------------------------------------------------------------------
    // SESSION
    // -------------------------------------------------------------------------

    /**
     * Marks the session as active or cleared.
     * Called from trusted Java code only — NOT exposed as @JavascriptInterface
     * to prevent any webpage from faking a connected state.
     */
    public void setConnected(boolean connected) {
        SharedPreferences.Editor ed = prefs.edit();
        if (connected) {
            ed.putBoolean(KEY_SESSION_VERIFIED, true)
              .putLong(KEY_SESSION_TS, System.currentTimeMillis());
        } else {
            ed.remove(KEY_SESSION_VERIFIED).remove(KEY_SESSION_TS);
        }
        ed.commit(); // synchronous so the next getState() sees it immediately
        appendLog("system", connected ? "✅ Connected" : "❌ Disconnected");
        notifyUiStateChanged();
    }

    public boolean isSessionVerified() {
        if (!prefs.getBoolean(KEY_SESSION_VERIFIED, false)) return false;
        long ts = prefs.getLong(KEY_SESSION_TS, 0);
        return (System.currentTimeMillis() - ts) < SESSION_MAX_AGE_MS;
    }

    // -------------------------------------------------------------------------
    // JS INTERFACE — called from uiWebView (main.html)
    // -------------------------------------------------------------------------

    @JavascriptInterface
    public void openLogin() {
        mainHandler.post(() -> {
            if (context instanceof MainActivity) {
                ((MainActivity) context).showLogin();
            }
        });
    }

    @JavascriptInterface
    public void logout() {
        prefs.edit()
             .putBoolean(KEY_RUNNING, false)
             .remove(KEY_SESSION_VERIFIED)
             .remove(KEY_SESSION_TS)
             .commit();
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
        appendLog("debug", "CookieManager len=" + len + ": " +
                (c != null ? (len > 200 ? c.substring(0, 200) + "…" : c) : "null"));
        if (gameWebView != null) {
            mainHandler.post(() ->
                gameWebView.evaluateJavascript("document.cookie", val ->
                    appendLog("debug", "gameWebView doc.cookie: " + val)));
        }
        appendLog("system", "=== END COOKIE DUMP ===");
        notifyUiStateChanged();
    }

    @JavascriptInterface
    public String getState() {
        try {
            JSONObject state = new JSONObject();

            boolean connected = isSessionVerified();
            state.put("connected", connected);

            boolean running = prefs.getBoolean(KEY_RUNNING, false);
            if (running && !connected) {
                prefs.edit().putBoolean(KEY_RUNNING, false).commit();
                running = false;
                appendLog("system", "Session expired — bot stopped");
            }
            state.put("running", running);
            state.put("tab", prefs.getString("active_tab", "battle"));

            JSONObject config = new JSONObject();
            config.put("debugLogs", prefs.getBoolean(KEY_DEBUG_LOGS, false));
            config.put("pollIntervalMs",
                       Integer.parseInt(prefs.getString(KEY_POLL_INTERVAL, "1500")));
            state.put("config", config);

            String stratJson = prefs.getString(KEY_STRATEGY, "{\"enabled\":false,\"entries\":[]}");
            state.put("strategy", new JSONObject(stratJson));

            synchronized (logBuffer) {
                state.put("logs", logBuffer.toString());
            }

            String cachedLive = prefs.getString("cached_live_state", null);
            if (cachedLive != null) {
                JSONObject live = new JSONObject(cachedLive);
                state.put("match",        live.optJSONObject("match")       != null ? live.getJSONObject("match")     : new JSONObject());
                state.put("stats",        live.optJSONObject("stats")       != null ? live.getJSONObject("stats")     : new JSONObject());
                state.put("skillList",    live.optJSONArray("skillList")    != null ? live.getJSONArray("skillList")  : new JSONArray());
                state.put("matchHistory", live.optJSONArray("matchHistory") != null ? live.getJSONArray("matchHistory") : new JSONArray());
            } else {
                state.put("match",        new JSONObject("{\"active\":false}"));
                state.put("stats",        new JSONObject());
                state.put("skillList",    new JSONArray());
                state.put("matchHistory", new JSONArray());
            }

            return state.toString();

        } catch (JSONException e) {
            Log.e("PvPManager", "getState error: " + e.getMessage());
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
                        if (raw.startsWith("\"") && raw.endsWith("\"")) {
                            raw = raw.substring(1, raw.length() - 1)
                                     .replace("\\\"", "\"")
                                     .replace("\\\\", "\\")
                                     .replace("\\n", "\n");
                        }
                        prefs.edit().putString("cached_live_state", raw).apply();
                    } catch (Exception e) {
                        Log.e("PvPManager", "refreshLiveState parse: " + e.getMessage());
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
        evaluateInGameWebView("if(window.__pvpmSetRunning) window.__pvpmSetRunning(" + running + ");");
        appendLog("system", running ? "Bot started" : "Bot stopped");
        notifyUiStateChanged();
    }

    @JavascriptInterface
    public void setStrategyEnabled(boolean enabled) {
        try {
            String raw = prefs.getString(KEY_STRATEGY, "{\"enabled\":false,\"entries\":[]}");
            JSONObject obj = new JSONObject(raw);
            obj.put("enabled", enabled);
            prefs.edit().putString(KEY_STRATEGY, obj.toString()).commit();
            String esc = obj.toString().replace("'", "\\'");
            evaluateInGameWebView("localStorage.setItem('et_pvp_solo_strategy','" + esc + "');");
        } catch (JSONException e) {
            appendLog("error", "Strategy error: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void saveStrategy(String jsonString) {
        try {
            JSONArray entries = new JSONArray(jsonString);
            String raw = prefs.getString(KEY_STRATEGY, "{\"enabled\":false,\"entries\":[]}");
            JSONObject obj = new JSONObject(raw);
            obj.put("entries", entries);
            prefs.edit().putString(KEY_STRATEGY, obj.toString()).commit();
            String esc = obj.toString().replace("'", "\\'");
            evaluateInGameWebView("localStorage.setItem('et_pvp_solo_strategy','" + esc + "');");
        } catch (JSONException e) {
            appendLog("error", "saveStrategy error: " + e.getMessage());
        }
    }

    /**
     * Saves a boolean config value.
     * Uses commit() (synchronous) so getState() called on the very next tick
     * sees the updated value — this eliminates the toggle-flip-back race.
     */
    @JavascriptInterface
    public void setConfigBool(String key, boolean value) {
        if ("debug_logs".equals(key)) {
            boolean saved = prefs.edit().putBoolean(KEY_DEBUG_LOGS, value).commit();
            Log.d("PvPManager", "setConfigBool debug_logs=" + value + " saved=" + saved);
            evaluateInGameWebView(
                "localStorage.setItem('et_pvp_debug_logs','" + value + "');");
            // Do NOT call notifyUiStateChanged here — the JS side already has the
            // correct toggle state; pushing a refresh would race against it.
        }
    }

    @JavascriptInterface
    public void setConfigValue(String key, String value) {
        if ("poll_interval_ms".equals(key)) {
            prefs.edit().putString(KEY_POLL_INTERVAL, value).commit();
        } else if ("clear_history".equals(key)) {
            try {
                String cached = prefs.getString("cached_live_state", null);
                if (cached != null) {
                    JSONObject live = new JSONObject(cached);
                    live.put("matchHistory", new JSONArray());
                    prefs.edit().putString("cached_live_state", live.toString()).commit();
                }
            } catch (JSONException e) {
                appendLog("error", "clearHistory error: " + e.getMessage());
            }
        }
    }

    @JavascriptInterface
    public void clearLogs() {
        synchronized (logBuffer) { logBuffer.setLength(0); }
        appendLog("system", "Logs cleared");
        notifyUiStateChanged();
    }

    @JavascriptInterface
    public void copyLogs() {
        ClipboardManager cb = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) {
            String logs;
            synchronized (logBuffer) { logs = logBuffer.toString(); }
            cb.setPrimaryClip(ClipData.newPlainText("PvP Manager Logs", logs));
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
    public void openProfile(String uid) {
        openUrl("https://demonicscans.org/player.php?id=" + uid);
    }

    @JavascriptInterface
    public void openMatch(String matchId) {
        openUrl("https://demonicscans.org/pvp_battle.php?match_id=" + matchId);
    }

    // -------------------------------------------------------------------------
    // INTERNAL HELPERS
    // -------------------------------------------------------------------------

    private void openUrl(String url) {
        mainHandler.post(() -> {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        });
    }

    private void evaluateInGameWebView(String js) {
        mainHandler.post(() -> {
            if (gameWebView != null) gameWebView.evaluateJavascript(js, null);
        });
    }

    public void appendLog(String type, String message) {
        String line = System.currentTimeMillis() + "|" + type + "|" + message + "\n";
        synchronized (logBuffer) {
            logBuffer.append(line);
            // Keep at most 400 of the most recent lines
            String full = logBuffer.toString();
            String[] lines = full.split("\n");
            if (lines.length > 500) {
                StringBuilder trimmed = new StringBuilder();
                for (int i = lines.length - 400; i < lines.length; i++) {
                    trimmed.append(lines[i]).append("\n");
                }
                logBuffer.setLength(0);
                logBuffer.append(trimmed);
            }
        }
        Log.d("PvPManager", type + ": " + message);
    }

    public void notifyUiStateChanged() {
        mainHandler.post(() -> {
            if (uiWebView != null) {
                uiWebView.evaluateJavascript(
                    "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            }
        });
    }
}
