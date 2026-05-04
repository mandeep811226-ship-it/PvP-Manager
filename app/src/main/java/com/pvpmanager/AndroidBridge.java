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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * JavaScript bridge object exposed as "Android" to main.html.
 * All methods annotated with @JavascriptInterface are callable from JS.
 */
public class AndroidBridge {

    private static final String PREFS_NAME = "pvp_manager_prefs";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_DEBUG_LOGS = "debug_logs";
    private static final String KEY_POLL_INTERVAL = "poll_interval_ms";
    private static final String KEY_STRATEGY = "strategy_json";

    private final Context context;
    private final WebView gameWebView;
    private final WebView uiWebView;
    private final SharedPreferences prefs;
    private final Handler mainHandler;

    // In-memory log buffer
    private final StringBuilder logBuffer = new StringBuilder();

    public AndroidBridge(Context context, WebView gameWebView, WebView uiWebView) {
        this.context = context;
        this.gameWebView = gameWebView;
        this.uiWebView = uiWebView;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // -------------------------------------------------------------------------
    // LOGIN / LOGOUT
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
        // Stop the bot before clearing the session.
        prefs.edit().putBoolean(KEY_RUNNING, false).apply();
        CookieHelper.clearAll();
        appendLog("system", "Logged out — cookies cleared.");
        notifyUiStateChanged();
    }

    // -------------------------------------------------------------------------
    // STATE
    // -------------------------------------------------------------------------

    /**
     * Called by the JS poll loop. Returns a JSON snapshot of the full app state.
     *
     * FIX: The "connected" field is derived from CookieHelper.isConnected(), which
     * previously used a narrow hardcoded cookie-name allowlist that could miss the
     * actual session cookie. CookieHelper has been updated to use a layered
     * detection strategy that falls back to checking for any non-trivial cookie
     * value — see CookieHelper.java for details.
     */
    @JavascriptInterface
    public String getState() {
        try {
            JSONObject state = new JSONObject();

            // Connected / running
            boolean connected = CookieHelper.isConnected();
            state.put("connected", connected);

            // If session expired (e.g. server cleared it), stop the bot automatically.
            boolean running = prefs.getBoolean(KEY_RUNNING, false);
            if (running && !connected) {
                prefs.edit().putBoolean(KEY_RUNNING, false).apply();
                running = false;
                appendLog("system", "Session expired — bot stopped automatically.");
            }
            state.put("running", running);

            // Tab
            state.put("tab", prefs.getString("active_tab", "battle"));

            // Config
            JSONObject config = new JSONObject();
            config.put("debugLogs", prefs.getBoolean(KEY_DEBUG_LOGS, false));
            config.put("pollIntervalMs", Integer.parseInt(
                    prefs.getString(KEY_POLL_INTERVAL, "1500")));
            state.put("config", config);

            // Strategy
            String strategyJson = prefs.getString(KEY_STRATEGY,
                    "{\"enabled\":false,\"entries\":[]}");
            state.put("strategy", new JSONObject(strategyJson));

            // Logs
            state.put("logs", logBuffer.toString());

            // Live match state (from cached window.__pvpmState snapshot)
            String cachedLive = prefs.getString("cached_live_state", null);
            if (cachedLive != null) {
                JSONObject live = new JSONObject(cachedLive);
                state.put("match", live.optJSONObject("match") != null
                        ? live.getJSONObject("match") : new JSONObject());
                state.put("stats", live.optJSONObject("stats") != null
                        ? live.getJSONObject("stats") : new JSONObject());
                state.put("skillList", live.optJSONArray("skillList") != null
                        ? live.getJSONArray("skillList") : new JSONArray());
                state.put("matchHistory", live.optJSONArray("matchHistory") != null
                        ? live.getJSONArray("matchHistory") : new JSONArray());
            } else {
                state.put("match", new JSONObject("{\"active\":false}"));
                state.put("stats", new JSONObject());
                state.put("skillList", new JSONArray());
                state.put("matchHistory", new JSONArray());
            }

            return state.toString();
        } catch (JSONException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Called by the UI poll loop to refresh live state from the game WebView.
     */
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
                                    // ignore parse errors
                                }
                            }
                        });
            }
        });
    }

    // -------------------------------------------------------------------------
    // BOT CONTROL
    // -------------------------------------------------------------------------

    @JavascriptInterface
    public void setRunning(boolean running) {
        // Guard: do not allow starting the bot without an active session.
        if (running && !CookieHelper.isConnected()) {
            appendLog("system", "Cannot start bot — not connected.");
            notifyUiStateChanged();
            return;
        }
        prefs.edit().putBoolean(KEY_RUNNING, running).apply();
        String js = "if(window.__pvpmSetRunning) window.__pvpmSetRunning(" + running + ");";
        evaluateInGameWebView(js);
        appendLog("system", running ? "Bot started." : "Bot stopped.");
        notifyUiStateChanged();
    }

    // -------------------------------------------------------------------------
    // STRATEGY
    // -------------------------------------------------------------------------

    @JavascriptInterface
    public void setStrategyEnabled(boolean enabled) {
        try {
            String raw = prefs.getString(KEY_STRATEGY, "{\"enabled\":false,\"entries\":[]}");
            JSONObject obj = new JSONObject(raw);
            obj.put("enabled", enabled);
            prefs.edit().putString(KEY_STRATEGY, obj.toString()).apply();
            String escaped = obj.toString().replace("'", "\\'");
            evaluateInGameWebView(
                "localStorage.setItem('et_pvp_solo_strategy', '" + escaped + "');"
            );
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
            prefs.edit().putString(KEY_STRATEGY, obj.toString()).apply();
            String escaped = obj.toString().replace("'", "\\'");
            evaluateInGameWebView(
                "localStorage.setItem('et_pvp_solo_strategy', '" + escaped + "');"
            );
        } catch (JSONException e) {
            appendLog("system", "saveStrategy error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // CONFIG
    // -------------------------------------------------------------------------

    @JavascriptInterface
    public void setConfigBool(String key, boolean value) {
        if ("debug_logs".equals(key)) {
            prefs.edit().putBoolean(KEY_DEBUG_LOGS, value).apply();
            evaluateInGameWebView(
                "localStorage.setItem('et_pvp_debug_logs', '" + value + "');"
            );
        }
    }

    @JavascriptInterface
    public void setConfigValue(String key, String value) {
        if ("poll_interval_ms".equals(key)) {
            prefs.edit().putString(KEY_POLL_INTERVAL, value).apply();
        } else if ("clear_history".equals(key)) {
            try {
                String cachedLive = prefs.getString("cached_live_state", null);
                if (cachedLive != null) {
                    JSONObject live = new JSONObject(cachedLive);
                    live.put("matchHistory", new JSONArray());
                    prefs.edit().putString("cached_live_state", live.toString()).apply();
                }
            } catch (JSONException e) {
                appendLog("system", "clearHistory error: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // LOGS
    // -------------------------------------------------------------------------

    @JavascriptInterface
    public void clearLogs() {
        logBuffer.setLength(0);
    }

    @JavascriptInterface
    public void copyLogs() {
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("PvP Manager Logs", logBuffer.toString());
            clipboard.setPrimaryClip(clip);
        }
    }

    // -------------------------------------------------------------------------
    // NAVIGATION / SYSTEM
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

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
        logBuffer.append(ts).append("|").append(type).append("|").append(message).append("\n");
        String s = logBuffer.toString();
        String[] lines = s.split("\n");
        if (lines.length > 500) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = lines.length - 400; i < lines.length; i++) {
                trimmed.append(lines[i]).append("\n");
            }
            logBuffer.setLength(0);
            logBuffer.append(trimmed);
        }
    }

    private void notifyUiStateChanged() {
        mainHandler.post(() -> {
            if (uiWebView != null) {
                uiWebView.evaluateJavascript(
                        "if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            }
        });
    }
}
