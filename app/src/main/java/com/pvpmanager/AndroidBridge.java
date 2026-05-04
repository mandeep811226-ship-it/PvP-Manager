package com.pvpmanager;

import android.app.Activity;
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

    private static final String PREFS_NAME = "pvp_manager_prefs";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_DEBUG_LOGS = "debug_logs";
    private static final String KEY_POLL_INTERVAL = "poll_interval_ms";
    private static final String KEY_STRATEGY = "strategy_json";
    private static final String KEY_SESSION_VERIFIED = "session_verified";
    private static final String KEY_SESSION_TIMESTAMP = "session_timestamp";

    // Session is considered fresh for 24 hours; the gameWebView re-verifies on
    // every page load anyway, so this is just a guard against cleared preferences.
    private static final long SESSION_MAX_AGE_MS = 24 * 60 * 60 * 1000L;

    private final Context context;
    private final WebView gameWebView;
    private final WebView uiWebView;
    private final SharedPreferences prefs;
    private final Handler mainHandler;
    private final StringBuilder logBuffer = new StringBuilder();

    public AndroidBridge(Context context, WebView gameWebView, WebView uiWebView) {
        this.context = context;
        this.gameWebView = gameWebView;
        this.uiWebView = uiWebView;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

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
        // Use commit() so changes are visible immediately to the next getState() call
        prefs.edit()
                .putBoolean(KEY_RUNNING, false)
                .remove(KEY_SESSION_VERIFIED)
                .remove(KEY_SESSION_TIMESTAMP)
                .commit();
        CookieHelper.clearAll();
        appendLog("system", "Logged out — cookies cleared");
        notifyUiStateChanged();
    }

    /**
     * Marks the session as connected or disconnected.
     * NOT exposed as @JavascriptInterface — called only from trusted Java code
     * (MainActivity.verifyGameWebViewSession / MainActivity.doConnect).
     */
    public void setConnected(boolean connected) {
        if (connected) {
            prefs.edit()
                    .putBoolean(KEY_SESSION_VERIFIED, true)
                    .putLong(KEY_SESSION_TIMESTAMP, System.currentTimeMillis())
                    .commit();
            appendLog("system", "✅ Connected — session active");
        } else {
            prefs.edit()
                    .remove(KEY_SESSION_VERIFIED)
                    .remove(KEY_SESSION_TIMESTAMP)
                    .commit();
            appendLog("system", "❌ Disconnected — session cleared");
        }
        notifyUiStateChanged();
    }

    public boolean isSessionVerified() {
        boolean verified = prefs.getBoolean(KEY_SESSION_VERIFIED, false);
        if (!verified) return false;
        long ts = prefs.getLong(KEY_SESSION_TIMESTAMP, 0);
        return (System.currentTimeMillis() - ts) < SESSION_MAX_AGE_MS;
    }

    @JavascriptInterface
    public void dumpCookies() {
        appendLog("system", "=== COOKIE DUMP ===");
        CookieManager.getInstance().flush();
        String cmCookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
        int len = cmCookies == null ? 0 : cmCookies.length();
        appendLog("debug", "CookieManager length=" + len + ": " +
                (cmCookies != null ? (len > 200 ? cmCookies.substring(0, 200) + "..." : cmCookies) : "null"));
        if (gameWebView != null) {
            mainHandler.post(() ->
                gameWebView.evaluateJavascript("document.cookie", value ->
                    appendLog("debug", "gameWebView document.cookie: " + value)));
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
            config.put("pollIntervalMs", Integer.parseInt(prefs.getString(KEY_POLL_INTERVAL, "1500")));
            state.put("config", config);

            String strategyJson = prefs.getString(KEY_STRATEGY, "{\"enabled\":false,\"entries\":[]}");
            state.put("strategy", new JSONObject(strategyJson));

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
            Log.e("PvPManager", "getState JSON error: " + e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @JavascriptInterface
    public void refreshLiveState() {
        mainHandler.post(() -> {
            if (gameWebView != null) {
                gameWebView.evaluateJavascript(
                    "(function(){ return JSON.stringify(window.__pvpmState || null); })()",
                    value -> {
                        if (value != null && !value.equals("null")) {
                            try {
                                String unquoted = value;
                                if (unquoted.startsWith("\"") && unquoted.endsWith("\"")) {
                                    unquoted = unquoted.substring(1, unquoted.length() - 1)
                                            .replace("\\\"", "\"")
                                            .replace("\\\\", "\\")
                                            .replace("\\n", "\n");
                                }
                                prefs.edit().putString("cached_live_state", unquoted).apply();
                            } catch (Exception e) {
                                Log.e("PvPManager", "refreshLiveState parse error: " + e.getMessage());
                            }
                        }
                    });
            }
        });
    }

    @JavascriptInterface
    public void setRunning(boolean running) {
        if (running && !isSessionVerified()) {
            appendLog("system", "Cannot start bot — not connected");
            notifyUiStateChanged();
            return;
        }
        prefs.edit().putBoolean(KEY_RUNNING, running).commit();
        String js = "if(window.__pvpmSetRunning) window.__pvpmSetRunning(" + running + ");";
        evaluateInGameWebView(js);
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
            String escaped = obj.toString().replace("'", "\\'");
            evaluateInGameWebView("localStorage.setItem('et_pvp_solo_strategy', '" + escaped + "');");
        } catch (JSONException e) {
            appendLog("system", "Strategy error: " + e.getMessage());
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
            String escaped = obj.toString().replace("'", "\\'");
            evaluateInGameWebView("localStorage.setItem('et_pvp_solo_strategy', '" + escaped + "');");
        } catch (JSONException e) {
            appendLog("system", "saveStrategy error: " + e.getMessage());
        }
    }

    /**
     * BUG FIX: Changed .apply() → .commit() so the preference write is synchronous.
     * With .apply() the next getState() poll (2.5 s later) could read the old value
     * and reset the toggle in the UI before the async write finished.
     * Also calls notifyUiStateChanged() so the UI reflects the new value immediately.
     */
    @JavascriptInterface
    public void setConfigBool(String key, boolean value) {
        if ("debug_logs".equals(key)) {
            prefs.edit().putBoolean(KEY_DEBUG_LOGS, value).commit();
            evaluateInGameWebView("localStorage.setItem('et_pvp_debug_logs', '" + value + "');");
            appendLog("debug", "Debug logs " + (value ? "enabled" : "disabled"));
            notifyUiStateChanged();
        }
    }

    @JavascriptInterface
    public void setConfigValue(String key, String value) {
        if ("poll_interval_ms".equals(key)) {
            prefs.edit().putString(KEY_POLL_INTERVAL, value).commit();
            notifyUiStateChanged();
        } else if ("clear_history".equals(key)) {
            try {
                String cachedLive = prefs.getString("cached_live_state", null);
                if (cachedLive != null) {
                    JSONObject live = new JSONObject(cachedLive);
                    live.put("matchHistory", new JSONArray());
                    prefs.edit().putString("cached_live_state", live.toString()).commit();
                }
            } catch (JSONException e) {
                appendLog("system", "clearHistory error: " + e.getMessage());
            }
        }
    }

    @JavascriptInterface
    public void clearLogs() {
        synchronized (logBuffer) {
            logBuffer.setLength(0);
        }
        appendLog("system", "Logs cleared");
        notifyUiStateChanged();
    }

    @JavascriptInterface
    public void copyLogs() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            String logs;
            synchronized (logBuffer) {
                logs = logBuffer.toString();
            }
            ClipData clip = ClipData.newPlainText("PvP Manager Logs", logs);
            clipboard.setPrimaryClip(clip);
            appendLog("system", "Logs copied to clipboard");
        }
    }

    @JavascriptInterface
    public void openSettings() {
        mainHandler.post(() -> {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
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

    private void openUrl(String url) {
        mainHandler.post(() -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        });
    }

    private void evaluateInGameWebView(String js) {
        mainHandler.post(() -> {
            if (gameWebView != null) {
                gameWebView.evaluateJavascript(js, null);
            }
        });
    }

    public void appendLog(String type, String message) {
        long ts = System.currentTimeMillis();
        String logLine = ts + "|" + type + "|" + message;
        synchronized (logBuffer) {
            logBuffer.append(logLine).append("\n");
            // Trim to the last 400 lines when we exceed 500
            String[] lines = logBuffer.toString().split("\n");
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
        // Logs are picked up on the next poll tick; no need to push immediately
    }

    public void notifyUiStateChanged() {
        mainHandler.post(() -> {
            if (uiWebView != null) {
                uiWebView.evaluateJavascript("if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            }
        });
    }
}
