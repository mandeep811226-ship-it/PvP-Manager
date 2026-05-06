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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.FileOutputStream;

public class AndroidBridge {

    private static final String PREFS_NAME          = "pvp_manager_prefs";
    private static final String KEY_RUNNING          = "running";
    private static final String KEY_DEBUG_LOGS       = "debug_logs";
    private static final String KEY_POLL_INTERVAL    = "poll_interval_ms";
    private static final String KEY_STRATEGY         = "strategy_json";
    private static final String KEY_SESSION_VERIFIED = "session_verified";
    private static final String KEY_BLS_MEMORY       = "bls_memory_json"; // NEW

    // ── Log buffer ───────────────────────────────────────────────────────────
    private static final int LOG_MAX_ENTRIES = 500;
    private final java.util.ArrayDeque<String> logLines = new java.util.ArrayDeque<>();

    // ── BLS reverse-sync (gameWebView localStorage → SharedPreferences) ───────
    // Runs every BLS_SYNC_INTERVAL_MS ms so the history expand panel always
    // reflects the freshest data pvp_manager.js has written during matches.
    private static final int    BLS_SYNC_INTERVAL_MS = 15_000; // 15 s
    private volatile     String lastBlsSnapshot      = null;   // change detection

    private final Runnable blsSyncRunnable = new Runnable() {
        @Override public void run() {
            performBlsSync();
            mainHandler.postDelayed(this, BLS_SYNC_INTERVAL_MS);
        }
    };

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
        // Start BLS reverse-sync after an initial delay so the game WebView has
        // time to load pvp_manager.js and populate localStorage.bls_memory.
        mainHandler.postDelayed(blsSyncRunnable, BLS_SYNC_INTERVAL_MS);
    }

    // ── Keepalive / BLS sync lifecycle ───────────────────────────────────────
    public void startKeepalive() {}

    public void stopKeepalive() {
        mainHandler.removeCallbacks(blsSyncRunnable);
    }

    /**
     * Reads localStorage.bls_memory from the game WebView and writes it into
     * SharedPreferences (KEY_BLS_MEMORY) if the content has changed.
     *
     * This closes the reverse-sync gap: pvp_manager.js writes live combat stats
     * (DMG, RET, CRIT, CLASS, HP) into the game WebView's localStorage during
     * every match. Without this method those updates would never reach
     * Android.getBlsMemory(), so the history expand panel would always show
     * stale or empty values.
     *
     * Safety guarantees:
     *  - Must run on the main thread (enforced by blsSyncRunnable → mainHandler).
     *  - JS evaluates JSON.parse internally, so only valid objects are returned.
     *  - Double-gated with lastBlsSnapshot (in-memory) and SharedPreferences
     *    comparison — zero writes when nothing has changed.
     *  - Empty ({}) and null results are silently ignored.
     *  - Any JSONException from the Java-side validation is also silently ignored.
     */
    private void performBlsSync() {
        if (gameWebView == null) return;
        // The JS returns a parsed object (not a raw string), so evaluateJavascript
        // delivers it as a plain JSON string (no outer quotes, no escaping needed).
        gameWebView.evaluateJavascript(
            "(function(){" +
            "  try {" +
            "    var raw = localStorage.getItem('bls_memory');" +
            "    if (!raw) return null;" +
            "    var obj = JSON.parse(raw);" +
            "    if (!obj || typeof obj !== 'object') return null;" +
            "    if (Object.keys(obj).length === 0) return null;" +
            "    return obj;" +
            "  } catch(e) { return null; }" +
            "})()",
            value -> {
                if (value == null || value.equals("null") || value.trim().isEmpty()) return;

                // Java-side validation: must parse as a JSONObject
                try { new JSONObject(value); } catch (JSONException e) {
                    Log.w("PvPManager", "blsSync: invalid JSON — skipped (" + e.getMessage() + ")");
                    return;
                }

                // Change detection — skip write if identical to last snapshot
                if (value.equals(lastBlsSnapshot)) return;
                lastBlsSnapshot = value;

                // Skip write if SharedPreferences already has this data
                String stored = prefs.getString(KEY_BLS_MEMORY, "{}");
                if (value.equals(stored)) return;

                prefs.edit().putString(KEY_BLS_MEMORY, value).apply();
                Log.d("PvPManager", "blsSync: synced " + value.length() +
                      " bytes → SharedPreferences (" +
                      (try_count_keys(value)) + " players)");
            }
        );
    }

    /** Best-effort player count for the log message — never throws. */
    private int try_count_keys(String json) {
        try { return new JSONObject(json).length(); } catch (Exception e) { return -1; }
    }

    // ── Session ───────────────────────────────────────────────────────────────
    public void setConnected(boolean connected) {
        SharedPreferences.Editor ed = prefs.edit();
        if (connected) {
            ed.putBoolean(KEY_SESSION_VERIFIED, true);
        } else {
            ed.remove(KEY_SESSION_VERIFIED);
        }
        ed.commit();
        appendLog("system", connected ? "✅ Connected" : "❌ Disconnected");
        notifyUiStateChanged();
    }

    public boolean isSessionVerified() {
        return prefs.getBoolean(KEY_SESSION_VERIFIED, false);
    }

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

            String gameLogs = "";
            String cachedForLogs = prefs.getString("cached_live_state", null);
            if (cachedForLogs != null) {
                try {
                    JSONObject liveForLogs = new JSONObject(cachedForLogs);
                    gameLogs = liveForLogs.optString("gameLogs", "");
                } catch (JSONException ignored) {}
            }

            String systemLogs = sb.toString();
            String combinedLogs;
            if (!gameLogs.isEmpty() && !systemLogs.trim().isEmpty()) {
                combinedLogs = gameLogs + "\n" + systemLogs;
            } else if (!gameLogs.isEmpty()) {
                combinedLogs = gameLogs;
            } else {
                combinedLogs = systemLogs;
            }
            state.put("logs", combinedLogs);

            boolean liveLoaded = false;
            String cachedLive = prefs.getString("cached_live_state", null);
            if (cachedLive != null) {
                try {
                    JSONObject live = new JSONObject(cachedLive);
                    state.put("match",        live.optJSONObject("match")        != null ? live.getJSONObject("match")        : new JSONObject());
                    state.put("stats",        live.optJSONObject("stats")        != null ? live.getJSONObject("stats")        : new JSONObject());
                    state.put("skillList",    live.optJSONArray("skillList")     != null ? live.getJSONArray("skillList")     : new JSONArray());
                    state.put("matchHistory", live.optJSONArray("matchHistory")  != null ? live.getJSONArray("matchHistory")  : new JSONArray());
                    // battleStats: ally/enemy BLS snapshot provided by pvp_manager.js
                    if (live.optJSONObject("battleStats") != null) {
                        state.put("battleStats", live.getJSONObject("battleStats"));
                    }
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
                state.put("battleStats",  new JSONObject());
            }

            return state.toString();

        } catch (JSONException e) {
            Log.e("PvPManager", "getState error: " + e.getMessage());
            return "{\"connected\":false,\"running\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @JavascriptInterface
    public void refreshLiveState() {
        mainHandler.post(() -> {
            if (gameWebView == null) return;
            gameWebView.evaluateJavascript(
                "(function(){" +
                "  var s = window.__pvpmState;" +
                "  if (!s || typeof s !== 'object') return null;" +
                "  try { return JSON.parse(JSON.stringify(s)); } catch(e) { return null; }" +
                "})()",
                value -> {
                    if (value == null || value.equals("null")) return;
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

    // ═══════════════════════════════════════════════════════
    // BLS MEMORY — get / save / import / export
    // ═══════════════════════════════════════════════════════

    /**
     * Returns the raw BLS memory JSON string stored in SharedPreferences.
     * Returns "{}" if nothing is stored yet (empty app-built-in state).
     */
    @JavascriptInterface
    public String getBlsMemory() {
        return prefs.getString(KEY_BLS_MEMORY, "{}");
    }

    /**
     * Saves the BLS memory JSON string from the UI into SharedPreferences
     * AND mirrors it to the game WebView's localStorage so pvp_manager.js
     * can read fresh values immediately without waiting for the next match.
     */
    @JavascriptInterface
    public void saveBlsMemory(String jsonString) {
        // Validate JSON before saving
        try {
            new JSONObject(jsonString);
        } catch (JSONException e) {
            appendLog("error", "saveBlsMemory: invalid JSON — not saved");
            return;
        }
        prefs.edit().putString(KEY_BLS_MEMORY, jsonString).commit();
        // Mirror to game WebView localStorage so pvp_manager.js reads it live
        final String escaped = jsonString.replace("\\", "\\\\").replace("'", "\\'");
        evaluateInGameWebView("try{localStorage.setItem('bls_memory','" + escaped + "');}catch(e){}");
        appendLog("system", "BLS memory saved (" + jsonString.length() + " bytes)");
    }

    /**
     * Triggers the Android file picker (READ_EXTERNAL_STORAGE or
     * ACTION_OPEN_DOCUMENT) so the user can select their BLS JSON file.
     * The result is handled by MainActivity.onActivityResult(), which reads
     * the file and calls back into JS via __pvpmBlsImportCallback().
     */
    @JavascriptInterface
    public void requestBlsImport() {
        mainHandler.post(() -> {
            if (context instanceof MainActivity) {
                ((MainActivity) context).requestBlsFilePicker();
            }
        });
    }

    /**
     * Exports the current BLS memory JSON as a downloadable file.
     * Writes to the app's external cache directory and shares via Intent.
     */
    @JavascriptInterface
    public void exportBlsMemory(String jsonString) {
        mainHandler.post(() -> {
            try {
                java.io.File dir  = context.getExternalCacheDir();
                if (dir == null) dir = context.getCacheDir();
                String filename = "bls_memory_" + System.currentTimeMillis() + ".json";
                java.io.File file = new java.io.File(dir, filename);
                try (OutputStream os = new FileOutputStream(file)) {
                    os.write(jsonString.getBytes("UTF-8"));
                }
                // Share via Android's chooser so user can save to Downloads / Drive / etc.
                Uri fileUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    file);
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("application/json");
                shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "BLS Memory Export");
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(shareIntent, "Save BLS Memory");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(chooser);
                appendLog("system", "BLS exported: " + filename);
            } catch (Exception e) {
                appendLog("error", "BLS export failed: " + e.getMessage());
            }
        });
    }

    /**
     * Called by MainActivity after the user picks a file via the file picker.
     * Reads the file and delivers the content to the UI WebView's JS callback.
     */
    public void deliverBlsFileContent(InputStream is) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            String content = sb.toString().trim();

            // Validate
            new JSONObject(content);

            final String escaped = content.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
            mainHandler.post(() -> {
                if (uiWebView != null) {
                    uiWebView.evaluateJavascript(
                        "if(window.__pvpmBlsImportCallback) window.__pvpmBlsImportCallback('" + escaped + "');",
                        null);
                }
            });
        } catch (Exception e) {
            appendLog("error", "BLS file read failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // END BLS MEMORY
    // ═══════════════════════════════════════════════════════

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
        openUrl("https://demonicscans.org/player.php?pid=" + uid);
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
