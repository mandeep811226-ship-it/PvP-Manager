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

    // ── Log buffer ───────────────────────────────────────────────────────────
    private static final int LOG_MAX_ENTRIES = 500;
    private final java.util.ArrayDeque<String> logLines = new java.util.ArrayDeque<>();

    private final Context           context;
    private final WebView           gameWebView;
    private final WebView           uiWebView;
    private final SharedPreferences prefs;
    private final Handler           mainHandler;

    public AndroidBridge(Context context, WebView gameWebView, WebView uiWebView) {
        this.context     = context;
        this.gameWebView = gameWebView;
        this.uiWebView   = uiWebView;
        this.prefs       = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // ── Keepalive stubs (kept so MainActivity compiles without changes) ───────
    public void startKeepalive() {}
    public void stopKeepalive()  {}

    // ── Session ───────────────────────────────────────────────────────────────

    /**
     * Marks the session active or cleared.
     * NOT a @JavascriptInterface — only trusted Java code may call this.
     */
    public void setConnected(boolean connected) {
        SharedPreferences.Editor ed = prefs.edit();
        if (connected) {
            ed.putBoolean(KEY_SESSION_VERIFIED, true);
        } else {
            ed.remove(KEY_SESSION_VERIFIED);
        }
        ed.commit(); // synchronous so next getState() reads updated value
        appendLog("system", connected ? "✅ Connected" : "❌ Disconnected");
        notifyUiStateChanged();
    }

    public boolean isSessionVerified() {
        return prefs.getBoolean(KEY_SESSION_VERIFIED, false);
    }

    /** Grace period removed — was causing false disconnects on app restart. */
    public boolean inGracePeriod() {
        return false;
    }

    // ── JS Interface ─────────────────────────────────────────────────────────

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

            // Cookie-backed session validation: if the flag says connected but cookies
            // are gone (e.g. app was killed and session cookies were in-memory only),
            // the server session is dead — auto-clear so the UI shows the real state.
            boolean connected = isSessionVerified();
            if (connected) {
                String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
                if (cookies == null || cookies.trim().isEmpty()) {
                    prefs.edit().remove(KEY_SESSION_VERIFIED).commit();
                    connected = false;
                    appendLog("system", "Session expired — cookies missing, marked disconnected");
                }
            }
            state.put("connected", connected);

            boolean running = prefs.getBoolean(KEY_RUNNING, false);
            if (running && !connected) {
                prefs.edit().putBoolean(KEY_RUNNING, false).commit();
                running = false;
                appendLog("system", "Session ended — bot stopped");
            }
            state.put("running", running);

            JSONObject config = new JSONObject();
            config.put("debugLogs", prefs.getBoolean(KEY_DEBUG_LOGS, false));
            int pollMs = 1500;
            try { pollMs = Integer.parseInt(prefs.getString(KEY_POLL_INTERVAL, "1500")); }
            catch (NumberFormatException ignored) {}
            config.put("pollIntervalMs", pollMs);
            state.put("config", config);

            // Guard strategy JSON — a malformed value must NOT crash the entire getState().
            JSONObject strategy = null;
            try {
                String stratJson = prefs.getString(KEY_STRATEGY, null);
                if (stratJson != null) strategy = new JSONObject(stratJson);
            } catch (JSONException ignored) {
                appendLog("warning", "Strategy JSON malformed — resetting");
                prefs.edit().remove(KEY_STRATEGY).commit();
            }
            if (strategy == null) strategy = new JSONObject("{\"enabled\":false,\"entries\":[]}");
            state.put("strategy", strategy);

            StringBuilder sb = new StringBuilder();
            synchronized (logLines) {
                for (String line : logLines) sb.append(line).append("\n");
            }
            state.put("logs", sb.toString());

            // Guard cached_live_state JSON — a malformed value must NOT crash getState().
            boolean liveLoaded = false;
            String cachedLive = prefs.getString("cached_live_state", null);
            if (cachedLive != null) {
                try {
                    JSONObject live = new JSONObject(cachedLive);
                    state.put("match",        live.optJSONObject("match")       != null ? live.getJSONObject("match")       : new JSONObject());
                    state.put("stats",        live.optJSONObject("stats")       != null ? live.getJSONObject("stats")       : new JSONObject());
                    state.put("skillList",    live.optJSONArray("skillList")    != null ? live.getJSONArray("skillList")    : new JSONArray());
                    state.put("matchHistory", live.optJSONArray("matchHistory") != null ? live.getJSONArray("matchHistory") : new JSONArray());
                    liveLoaded = true;
                } catch (JSONException ignored) {
                    appendLog("warning", "cached_live_state malformed — clearing");
                    prefs.edit().remove("cached_live_state").commit();
                }
            }
            if (!liveLoaded) {
                state.put("match",        new JSONObject("{\"active\":false}"));
                state.put("stats",        new JSONObject());
                state.put("skillList",    new JSONArray());
                state.put("matchHistory", new JSONArray());
            }

            return state.toString();

        } catch (JSONException e) {
            Log.e("PvPManager", "getState error: " + e.getMessage());
            // Always return a valid JSON with connected so tick() never shows a false Disconnected.
            return "{\"connected\":false,\"running\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @JavascriptInterface
    public void refreshLiveState() {
        mainHandler.post(() -> {
            if (gameWebView == null) return;
            // FIX: Return the raw JS object — evaluateJavascript serialises it
            // to valid JSON once. JSON.stringify() in JS caused double-encoding.
            // Guard: only proceed when the bridge hook is installed and is a real object.
            gameWebView.evaluateJavascript(
                "(function(){" +
                "  var s = window.__pvpmState;" +
                "  if (!s || typeof s !== 'object') return null;" +
                "  try { return JSON.parse(JSON.stringify(s)); } catch(e) { return null; }" +
                "})()",
                value -> {
                    if (value == null || value.equals("null")) return;
                    // value is already valid JSON — validate before saving.
                    try {
                        new JSONObject(value);
                        prefs.edit().putString("cached_live_state", value).apply();
                    } catch (JSONException e) {
                        Log.e("PvPManager", "refreshLiveState: invalid JSON: " + e.getMessage());
                    } catch (Exception e) {
                        Log.e("PvPManager", "refreshLiveState: " + e.getMessage());
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

    @JavascriptInterface
    public void setConfigBool(String key, boolean value) {
        if ("debug_logs".equals(key)) {
            prefs.edit().putBoolean(KEY_DEBUG_LOGS, value).commit();
            evaluateInGameWebView(
                "localStorage.setItem('et_pvp_debug_logs','" + value + "');");
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
        ClipboardManager cb = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) {
            StringBuilder sb = new StringBuilder();
            synchronized (logLines) {
                for (String line : logLines) sb.append(line).append("\n");
            }
            cb.setPrimaryClip(ClipData.newPlainText("PvP Manager Logs", sb.toString()));
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
                Intent i = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + context.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            } catch (Exception e) {
                appendLog("warning", "Battery opt intent failed: " + e.getMessage());
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
        // Store as "unixMs|type|message" — the UI's renderLogs() parses parts[0]
        // as a Unix-ms timestamp and converts it to HH:MM:SS via tsToHMS().
        String line = System.currentTimeMillis() + "|" + type + "|" + message;
        synchronized (logLines) {
            logLines.addLast(line);
            while (logLines.size() > LOG_MAX_ENTRIES) logLines.removeFirst();
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
