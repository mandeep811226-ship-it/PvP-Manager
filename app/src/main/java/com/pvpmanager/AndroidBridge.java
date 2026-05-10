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

import android.os.Environment;
import android.widget.Toast;

public class AndroidBridge {

    // ── Global (not per-account) prefs keys ───────────────────────────────────
    private static final String PREFS_NAME          = "pvp_manager_prefs";
    private static final String KEY_DEBUG_LOGS       = "debug_logs";
    private static final String KEY_POLL_INTERVAL    = "poll_interval_ms";
    private static final String KEY_SESSION_VERIFIED = "session_verified";
    private static final String KEY_BLS_MEMORY       = "bls_memory_json"; // GLOBAL — shared across accounts

    // ── Per-account prefs base keys (suffixed with _<playerId>) ──────────────
    private static final String KEY_RUNNING          = "running";
    private static final String KEY_STRATEGY         = "strategy_json";
    private static final String KEY_CACHED_LIVE      = "cached_live_state";

    // ── Log buffer ───────────────────────────────────────────────────────────
    private static final int LOG_MAX_ENTRIES = 500;
    private final java.util.ArrayDeque<String> logLines = new java.util.ArrayDeque<>();

    // ── BLS reverse-sync ─────────────────────────────────────────────────────
    private static final int    BLS_SYNC_INTERVAL_MS = 15_000;
    private volatile     String lastBlsSnapshot      = null;
    /** True while the user is adding a new account — forces getState() to report disconnected. */
    private volatile     boolean addAccountModeActive = false;

    // ── getState() result cache (PATCH 1) ─────────────────────────────────────
    /** Last serialized state string; returned immediately when state hasn't changed. */
    private volatile String  _cachedStateString = null;
    /** Set to true whenever any state mutation occurs; cleared after caching. */
    private volatile boolean _stateDirty        = true;

    private final Runnable blsSyncRunnable = new Runnable() {
        @Override public void run() {
            performBlsSync();
            mainHandler.postDelayed(this, BLS_SYNC_INTERVAL_MS);
        }
    };

    // ── Fields ────────────────────────────────────────────────────────────────
    private final Context           context;
    private final WebView           gameWebView;
    private final WebView           uiWebView;
    private final SharedPreferences prefs;
    private final Handler           mainHandler;
    final         AccountStore      accountStore;

    public AndroidBridge(Context context, WebView gameWebView, WebView uiWebView, AccountStore accountStore) {
        this.context      = context;
        this.gameWebView  = gameWebView;
        this.uiWebView    = uiWebView;
        this.prefs        = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.mainHandler  = new Handler(Looper.getMainLooper());
        this.accountStore = accountStore;
        mainHandler.postDelayed(blsSyncRunnable, BLS_SYNC_INTERVAL_MS);
    }

    // ── Scoped key helpers ────────────────────────────────────────────────────

    /** Returns baseKey_<activeId> if an active account is set, else baseKey (backward compat). */
    private String scopedKey(String baseKey) {
        String id = accountStore.getActiveAccountId();
        return (id != null && !id.isEmpty()) ? baseKey + "_" + id : baseKey;
    }

    boolean getBoolScoped(String baseKey, boolean def) {
        String id = accountStore.getActiveAccountId();
        if (id != null && !id.isEmpty()) {
            String sk = baseKey + "_" + id;
            if (prefs.contains(sk)) return prefs.getBoolean(sk, def);
        }
        return prefs.getBoolean(baseKey, def);
    }

    String getStringScoped(String baseKey, String def) {
        String id = accountStore.getActiveAccountId();
        if (id != null && !id.isEmpty()) {
            String sk = baseKey + "_" + id;
            if (prefs.contains(sk)) return prefs.getString(sk, def);
        }
        return prefs.getString(baseKey, def);
    }

    void putBoolScoped(String baseKey, boolean val) {
        prefs.edit().putBoolean(scopedKey(baseKey), val).apply();
    }

    void putStringScoped(String baseKey, String val) {
        prefs.edit().putString(scopedKey(baseKey), val).apply();
    }

    void removeScoped(String baseKey) {
        String id = accountStore.getActiveAccountId();
        SharedPreferences.Editor ed = prefs.edit();
        if (id != null && !id.isEmpty()) ed.remove(baseKey + "_" + id);
        ed.remove(baseKey);
        ed.apply();
    }

    /** Remove ALL scoped data for a specific account ID. Called on sign-out. */
    public void removeAccountScopedData(String playerId) {
        if (playerId == null || playerId.isEmpty()) return;
        SharedPreferences.Editor ed = prefs.edit();
        ed.remove(KEY_RUNNING   + "_" + playerId);
        ed.remove(KEY_STRATEGY  + "_" + playerId);
        ed.remove(KEY_CACHED_LIVE + "_" + playerId);
        // Remove logs and history scoped to this account
        ed.remove("logs_"    + playerId);
        ed.remove("history_" + playerId);
        ed.apply();
    }

    // ── Keepalive / BLS sync lifecycle ────────────────────────────────────────
    public void startKeepalive() {}

    public void stopKeepalive() {
        mainHandler.removeCallbacks(blsSyncRunnable);
    }

    /** Called by MainActivity to isolate the UI during add-account flow. */
    public void setAddAccountMode(boolean active) {
        _stateDirty = true;
        addAccountModeActive = active;
        notifyUiStateChanged();
    }

    private void performBlsSync() {
        if (gameWebView == null) return;
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
                try { new JSONObject(value); } catch (JSONException e) { return; }
                if (value.equals(lastBlsSnapshot)) return;
                lastBlsSnapshot = value;
                String stored = prefs.getString(KEY_BLS_MEMORY, "{}");
                if (value.equals(stored)) return;
                prefs.edit().putString(KEY_BLS_MEMORY, value).apply();
                Log.d("PvPManager", "blsSync: synced " + value.length() + " bytes (" + try_count_keys(value) + " players)");
            }
        );
    }

    private int try_count_keys(String json) {
        try { return new JSONObject(json).length(); } catch (Exception e) { return -1; }
    }

    // ── Session ───────────────────────────────────────────────────────────────
    public void setConnected(boolean connected) {
        _stateDirty = true;
        SharedPreferences.Editor ed = prefs.edit();
        if (connected) ed.putBoolean(KEY_SESSION_VERIFIED, true);
        else           ed.remove(KEY_SESSION_VERIFIED);
        ed.apply();
        appendLog("system", connected ? "✅ Connected" : "❌ Disconnected");
        notifyUiStateChanged();
    }

    public boolean isSessionVerified() {
        return prefs.getBoolean(KEY_SESSION_VERIFIED, false);
    }

    public boolean inGracePeriod() { return false; }

    // ── Account extraction from game DOM ─────────────────────────────────────

    /**
     * Reads player identity from the game page DOM (side drawer elements):
     * - pid from href="player.php?pid=..."
     * - name from .small-name
     * - avatar from .small-ava img
     * - level from .small-level
     * Saves profile to AccountStore and sets as active account.
     * Safe — never crashes, extraction failure only logs a warning.
     */
    public void extractCurrentAccount(Runnable onDone) {
        if (gameWebView == null) { if (onDone != null) mainHandler.post(onDone); return; }
        mainHandler.post(() ->
            gameWebView.evaluateJavascript(
                "(function(){" +
                "  try {" +
                // L1 — scan ALL a[href*="player.php?pid="] links (sidebar, nav, profile widgets)
                "    var pid=null;" +
                "    var _links=document.querySelectorAll('a[href*=\"player.php?pid=\"]');" +
                "    for(var _li=0;_li<_links.length&&!pid;_li++){" +
                "      var _m=(_links[_li].getAttribute('href')||'').match(/pid=(\\d+)/);" +
                "      if(_m)pid=_m[1];" +
                "    }" +
                // L2 — current page URL carries the pid
                "    if(!pid){var _um=location.href.match(/player\\.php[^#]*[?&]pid=(\\d+)/);if(_um)pid=_um[1];}" +
                // L3 — any a[href*="player.php"] carrying pid in a different query layout
                "    if(!pid){" +
                "      var _wl=document.querySelectorAll('a[href*=\"player.php\"]');" +
                "      for(var _li=0;_li<_wl.length&&!pid;_li++){" +
                "        var _m2=(_wl[_li].getAttribute('href')||'').match(/[?&]pid=(\\d+)/);" +
                "        if(_m2)pid=_m2[1];" +
                "      }" +
                "    }" +
                "    if(!pid) return null;" +
                "    var nameEl=document.querySelector('.small-name')" +
                "      ||document.querySelector('.user-name')" +
                "      ||document.querySelector('.profile-name')" +
                "      ||document.querySelector('.player-name')" +
                "      ||document.querySelector('h1.name')" +
                "      ||document.querySelector('h2.name');" +
                "    var name=nameEl?nameEl.textContent.trim():'';" +
                "    var link=document.querySelector('a[href*=\"player.php?pid=\"]');" +
                "    if(!name||name.length<1){var lt=(link&&link.textContent||'').trim();if(lt&&lt.length>1&&!lt.startsWith('http'))name=lt;}" +
                "    if(!name||name.length<1)name='Unknown';" +
                "    var avaEl=document.querySelector('.small-ava img')" +
                "      ||document.querySelector('.user-avatar img')" +
                "      ||document.querySelector('.profile-avatar img')" +
                "      ||document.querySelector('.avatar img')" +
                "      ||document.querySelector('img[src*=\"avatars/user_\"]');" +
                "    var avaRaw=avaEl?(avaEl.getAttribute('src')||''):'';" +
                "    var ava=avaRaw?(avaRaw.startsWith('http')?avaRaw:'https://demonicscans.org/'+avaRaw.replace(/^\\//,''))  :'';" +
                "    var lvlEl=document.querySelector('.small-level')" +
                "      ||document.querySelector('.user-level')" +
                "      ||document.querySelector('.player-level');" +
                "    var lvlNum=0;" +
                "    if(lvlEl){var lm=lvlEl.textContent.match(/(\\d+)/);if(lm)lvlNum=parseInt(lm[1]);}" +
                "    return JSON.stringify({playerId:pid,playerName:name,avatarUrl:ava,level:lvlNum});" +
                "  }catch(e){return null;}" +
                "})()",
                result -> {
                    if (result == null || result.equals("null")) {
                        appendLog("debug", "extractCurrentAccount: DOM not ready or not logged in");
                        if (onDone != null) mainHandler.post(onDone);
                        return;
                    }
                    try {
                        String json = result;
                        if (json.startsWith("\"") && json.endsWith("\"")) {
                            json = json.substring(1, json.length() - 1)
                                       .replace("\\\"", "\"").replace("\\\\", "\\");
                        }
                        JSONObject info = new JSONObject(json);
                        String pid = info.optString("playerId", "");
                        if (!pid.isEmpty()) {
                            CookieManager.getInstance().flush();
                            String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
                            JSONObject account = new JSONObject();
                            account.put("playerId",    pid);
                            account.put("playerName",  info.optString("playerName", "Unknown"));
                            account.put("avatarUrl",   info.optString("avatarUrl", ""));
                            account.put("level",       info.optInt("level", 0));
                            account.put("cookies",     cookies != null ? cookies : "");
                            account.put("lastLogin",   System.currentTimeMillis());
                            accountStore.saveAccount(account);
                            accountStore.setActiveAccountId(pid);
                            appendLog("system", "Account profile saved: " + info.optString("playerName") + " (ID: " + pid + ")");
                            if (context instanceof MainActivity) {
                                mainHandler.post(() -> ((MainActivity) context).updateAccountChip());
                            }
                        }
                    } catch (JSONException e) {
                        appendLog("warning", "extractCurrentAccount: parse error — " + e.getMessage());
                    }
                    if (onDone != null) mainHandler.post(onDone);
                }
            )
        );
    }

    // ── JS Interface ──────────────────────────────────────────────────────────

    @JavascriptInterface
    public void openLogin() {
        mainHandler.post(() -> {
            if (context instanceof MainActivity) ((MainActivity) context).showLogin();
        });
    }

    @JavascriptInterface
    public void logout() {
        // Stop bot and clear session for active account
        String activeId = accountStore.getActiveAccountId();
        putBoolScoped(KEY_RUNNING, false);
        prefs.edit().remove(KEY_SESSION_VERIFIED).apply();
        if (activeId != null) {
            accountStore.removeAccount(activeId);
            removeAccountScopedData(activeId);
            accountStore.setActiveAccountId(null);
        }
        CookieHelper.clearAll();
        appendLog("system", "Logged out — session and account data cleared");
        if (context instanceof MainActivity) {
            mainHandler.post(() -> ((MainActivity) context).updateAccountChip());
        }
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
        // PATCH 1 — return cached string immediately when nothing has changed
        if (!_stateDirty && _cachedStateString != null) return _cachedStateString;

        try {
            JSONObject state = new JSONObject();

            // While adding a new account, force disconnected so the UI shows the login state
            if (addAccountModeActive) {
                JSONObject cfg = new JSONObject();
                cfg.put("debugLogs", false);
                cfg.put("pollIntervalMs", 2500);
                JSONObject strat = new JSONObject();
                strat.put("enabled", false);
                strat.put("entries", new JSONArray());
                JSONObject matchObj = new JSONObject();
                matchObj.put("active", false);
                JSONObject earlyState = new JSONObject();
                earlyState.put("connected",    false);
                earlyState.put("running",      false);
                earlyState.put("accountCount", accountStore.getAccountCount());
                earlyState.put("config",       cfg);
                earlyState.put("strategy",     strat);
                earlyState.put("logs",         "");
                earlyState.put("match",        matchObj);
                earlyState.put("stats",        new JSONObject());
                earlyState.put("skillList",    new JSONArray());
                earlyState.put("matchHistory", new JSONArray());
                earlyState.put("battleStats",  new JSONObject());
                return earlyState.toString();
            }

            boolean connected = isSessionVerified();
            if (connected) {
                String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
                if (cookies == null || cookies.trim().isEmpty()) {
                    prefs.edit().remove(KEY_SESSION_VERIFIED).apply();
                    connected = false;
                    appendLog("system", "Session expired — cookies missing, marked disconnected");
                }
            }
            state.put("connected", connected);

            boolean running = getBoolScoped(KEY_RUNNING, false);
            if (running && !connected) {
                putBoolScoped(KEY_RUNNING, false);
                running = false;
                appendLog("system", "Session ended — bot stopped");
            }
            state.put("running", running);

            // Active account info for UI display
            JSONObject activeAccount = accountStore.getActiveAccount();
            if (activeAccount != null) {
                JSONObject acctInfo = new JSONObject();
                acctInfo.put("playerName", activeAccount.optString("playerName", "Unknown"));
                acctInfo.put("playerId",   activeAccount.optString("playerId", ""));
                acctInfo.put("avatarUrl",  activeAccount.optString("avatarUrl", ""));
                acctInfo.put("level",      activeAccount.optInt("level", 0));
                state.put("activeAccount", acctInfo);
            }
            state.put("accountCount", accountStore.getAccountCount());

            JSONObject config = new JSONObject();
            config.put("debugLogs", prefs.getBoolean(KEY_DEBUG_LOGS, false));
            int pollMs = 1500;
            try { pollMs = Integer.parseInt(prefs.getString(KEY_POLL_INTERVAL, "1500")); }
            catch (NumberFormatException ignored) {}
            config.put("pollIntervalMs", pollMs);
            state.put("config", config);

            JSONObject strategy = null;
            try {
                String stratJson = getStringScoped(KEY_STRATEGY, null);
                if (stratJson != null) strategy = new JSONObject(stratJson);
            } catch (JSONException ignored) {
                appendLog("warning", "Strategy JSON malformed — resetting");
                removeScoped(KEY_STRATEGY);
            }
            if (strategy == null) strategy = new JSONObject("{\"enabled\":false,\"entries\":[]}");
            state.put("strategy", strategy);

            StringBuilder sb = new StringBuilder();
            synchronized (logLines) {
                for (String line : logLines) sb.append(line).append("\n");
            }

            // PATCH 3 — parse KEY_CACHED_LIVE once; reuse for both gameLogs and match/stats/etc.
            String gameLogs = "";
            boolean liveLoaded = false;
            String cachedLive = getStringScoped(KEY_CACHED_LIVE, null);
            if (cachedLive != null) {
                try {
                    JSONObject live = new JSONObject(cachedLive);
                    // gameLogs section (was a second getStringScoped + parse before)
                    gameLogs = live.optString("gameLogs", "");
                    // match / stats / skill sections
                    state.put("match",        live.optJSONObject("match")       != null ? live.getJSONObject("match")       : new JSONObject());
                    state.put("stats",        live.optJSONObject("stats")       != null ? live.getJSONObject("stats")       : new JSONObject());
                    state.put("skillList",    live.optJSONArray("skillList")    != null ? live.getJSONArray("skillList")    : new JSONArray());
                    state.put("matchHistory", live.optJSONArray("matchHistory") != null ? live.getJSONArray("matchHistory") : new JSONArray());
                    if (live.optJSONObject("battleStats") != null) {
                        state.put("battleStats", live.getJSONObject("battleStats"));
                    }
                    liveLoaded = true;
                } catch (JSONException ignored) {
                    appendLog("warning", "cached_live_state malformed — clearing");
                    removeScoped(KEY_CACHED_LIVE);
                }
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

            if (!liveLoaded) {
                state.put("match",        new JSONObject("{\"active\":false}"));
                state.put("stats",        new JSONObject());
                state.put("skillList",    new JSONArray());
                state.put("matchHistory", new JSONArray());
                state.put("battleStats",  new JSONObject());
            }

            // PATCH 1 — cache the result; clear dirty flag
            _cachedStateString = state.toString();
            _stateDirty        = false;
            return _cachedStateString;

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
                "  try { return s; } catch(e) { return null; }" +
                "})()",
                value -> {
                    if (value == null || value.equals("null")) return;
                    try {
                        new JSONObject(value);
                        putStringScoped(KEY_CACHED_LIVE, value);
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
        _stateDirty = true;
        if (running && !isSessionVerified()) {
            appendLog("system", "Cannot start — not connected");
            notifyUiStateChanged();
            return;
        }
        putBoolScoped(KEY_RUNNING, running);
        evaluateInGameWebView("if(window.__pvpmSetRunning) window.__pvpmSetRunning(" + running + ");");
        appendLog("system", running ? "Bot started" : "Bot stopped");
        notifyUiStateChanged();
    }

    @JavascriptInterface
    public void setStrategyEnabled(boolean enabled) {
        _stateDirty = true;
        try {
            String raw = getStringScoped(KEY_STRATEGY, "{\"enabled\":false,\"entries\":[]}");
            JSONObject obj = new JSONObject(raw);
            obj.put("enabled", enabled);
            putStringScoped(KEY_STRATEGY, obj.toString());
            String esc = obj.toString().replace("\\", "\\\\").replace("'", "\\'");
            evaluateInGameWebView("localStorage.setItem('et_pvp_solo_strategy','" + esc + "');");
        } catch (JSONException e) {
            appendLog("error", "Strategy error: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void saveStrategy(String jsonString) {
        _stateDirty = true;
        try {
            JSONArray entries = new JSONArray(jsonString);
            String raw = getStringScoped(KEY_STRATEGY, "{\"enabled\":false,\"entries\":[]}");
            JSONObject obj = new JSONObject(raw);
            obj.put("entries", entries);
            putStringScoped(KEY_STRATEGY, obj.toString());
            String esc = obj.toString().replace("\\", "\\\\").replace("'", "\\'");
            evaluateInGameWebView("localStorage.setItem('et_pvp_solo_strategy','" + esc + "');");
        } catch (JSONException e) {
            appendLog("error", "saveStrategy error: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void setConfigBool(String key, boolean value) {
        _stateDirty = true;
        if ("debug_logs".equals(key)) {
            prefs.edit().putBoolean(KEY_DEBUG_LOGS, value).apply();
            evaluateInGameWebView("localStorage.setItem('et_pvp_debug_logs','" + value + "');");
        }
    }

    @JavascriptInterface
    public void setConfigValue(String key, String value) {
        _stateDirty = true;
        if ("poll_interval_ms".equals(key)) {
            prefs.edit().putString(KEY_POLL_INTERVAL, value).apply();
        } else if ("clear_history".equals(key)) {
            try {
                String cached = getStringScoped(KEY_CACHED_LIVE, null);
                if (cached != null) {
                    JSONObject live = new JSONObject(cached);
                    live.put("matchHistory", new JSONArray());
                    putStringScoped(KEY_CACHED_LIVE, live.toString());
                }
            } catch (JSONException e) {
                appendLog("error", "clearHistory: " + e.getMessage());
            }
        }
    }

    // ── Multi-account JS Interface ───────────────────────────────────────────

    /** Returns JSON array of all saved accounts (without sensitive cookie data). */
    @JavascriptInterface
    public String getAccountsJson() {
        try {
            JSONArray accounts = accountStore.getAccounts();
            JSONArray safe = new JSONArray();
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject a = accounts.getJSONObject(i);
                JSONObject s = new JSONObject();
                s.put("playerId",    a.optString("playerId", ""));
                s.put("playerName",  a.optString("playerName", "Unknown"));
                s.put("avatarUrl",   a.optString("avatarUrl", ""));
                s.put("level",       a.optInt("level", 0));
                s.put("lastLogin",   a.optLong("lastLogin", 0));
                s.put("hasCookies",  !a.optString("cookies", "").isEmpty());
                safe.put(s);
            }
            return safe.toString();
        } catch (JSONException e) {
            return "[]";
        }
    }

    /** Returns the active account ID, or empty string. */
    @JavascriptInterface
    public String getActiveAccountId() {
        String id = accountStore.getActiveAccountId();
        return id != null ? id : "";
    }

    /** Triggers an account switch. Handles all state save/restore. Called from JS or native. */
    @JavascriptInterface
    public void switchToAccount(String playerId) {
        mainHandler.post(() -> {
            if (context instanceof MainActivity) {
                ((MainActivity) context).switchToAccount(playerId);
            }
        });
    }

    /** Removes an account and all its scoped data. */
    @JavascriptInterface
    public void removeAccountById(String playerId) {
        mainHandler.post(() -> {
            if (context instanceof MainActivity) {
                ((MainActivity) context).removeAccountWithData(playerId);
            }
        });
    }

    /** Sends a surrender action for the current active match via the game WebView. */
    @JavascriptInterface
    public void surrenderMatch() {
        mainHandler.post(() -> {
            if (gameWebView != null) {
                gameWebView.evaluateJavascript(
                    "if(window.__pvpmSurrenderMatch) window.__pvpmSurrenderMatch();", null);
            }
        });
    }

    /** Opens login overlay for adding a new account. */
    @JavascriptInterface
    public void startAddAccount() {
        mainHandler.post(() -> {
            if (context instanceof MainActivity) {
                ((MainActivity) context).startAddAccountFlow();
            }
        });
    }

    // ═══════════════════════════════════════════════════════
    // BLS MEMORY — get / save / import / export (GLOBAL — shared across all accounts)
    // ═══════════════════════════════════════════════════════

    @JavascriptInterface
    public String getBlsMemory() {
        return prefs.getString(KEY_BLS_MEMORY, "{}");
    }

    @JavascriptInterface
    public void saveBlsMemory(String jsonString) {
        try { new JSONObject(jsonString); }
        catch (JSONException e) { appendLog("error", "saveBlsMemory: invalid JSON — not saved"); return; }
        prefs.edit().putString(KEY_BLS_MEMORY, jsonString).apply();
        final String escaped = jsonString.replace("\\", "\\\\").replace("'", "\\'");
        evaluateInGameWebView("try{localStorage.setItem('bls_memory','" + escaped + "');}catch(e){}");
        appendLog("system", "BLS memory saved (" + jsonString.length() + " bytes)");
    }

    @JavascriptInterface
    public void requestBlsImport() {
        mainHandler.post(() -> {
            if (context instanceof MainActivity) ((MainActivity) context).requestBlsFilePicker();
        });
    }

    @JavascriptInterface
    public void exportBlsMemory(String jsonString) {
        mainHandler.post(() -> {
            try {
                // ── Build clean export structure ──────────────────────────────
                JSONObject raw = new JSONObject(jsonString);
                JSONArray exportArr = new JSONArray();
                java.util.Iterator<String> keys = raw.keys();
                while (keys.hasNext()) {
                    String uid = keys.next();
                    JSONObject entry = raw.optJSONObject(uid);
                    if (entry == null) continue;

                    // Normalize lastMatchId — support legacy "lastMatchld" typo
                    String matchId = entry.optString("lastMatchId", null);
                    if (matchId == null || matchId.isEmpty())
                        matchId = entry.optString("lastMatchld", null);

                    JSONObject clean = new JSONObject();
                    clean.put("playerId", uid);
                    String name = entry.optString("name", null);
                    if (name != null && !name.isEmpty()) clean.put("name", name);
                    String cls = entry.optString("playerClass", null);
                    if (cls != null && !cls.isEmpty()) clean.put("class", cls);
                    if (entry.has("hp"))    clean.put("hp",    entry.optInt("hp",    0));
                    if (entry.has("maxHp")) clean.put("maxHp", entry.optInt("maxHp", 0));

                    JSONObject combat = new JSONObject();
                    if (entry.has("attackerScore"))  combat.put("attackerScore",  entry.optDouble("attackerScore",  0));
                    if (entry.has("defenderScore"))  combat.put("defenderScore",  entry.optDouble("defenderScore",  0));
                    if (entry.has("baseTargetDmg"))  combat.put("baseTargetDmg",  entry.optInt("baseTargetDmg",    0));
                    if (entry.has("baseDamageBack"))  combat.put("baseDamageBack", entry.optInt("baseDamageBack",   0));
                    if (entry.has("critChance"))      combat.put("critChance",     entry.optDouble("critChance",    0));
                    if (combat.length() > 0) clean.put("combatStats", combat);

                    if (matchId != null && !matchId.isEmpty()) clean.put("lastMatchId", matchId);
                    exportArr.put(clean);
                }

                String exportJson = exportArr.toString(2);
                String filename   = "bls_memory_" + System.currentTimeMillis() + ".json";

                // ── Write directly to Downloads/PvP Manager/ ─────────────────
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    // Android 10+ — MediaStore (no WRITE_EXTERNAL_STORAGE needed)
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename);
                    cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json");
                    cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                           Environment.DIRECTORY_DOWNLOADS + "/PvP Manager");
                    android.net.Uri uri = context.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("MediaStore insert returned null");
                    try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                        if (os == null) throw new Exception("Could not open output stream");
                        os.write(exportJson.getBytes("UTF-8"));
                    }
                } else {
                    // Android 9 and below — direct file write
                    java.io.File downloadsDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                    java.io.File pvpDir = new java.io.File(downloadsDir, "PvP Manager");
                    if (!pvpDir.exists()) pvpDir.mkdirs();
                    java.io.File file = new java.io.File(pvpDir, filename);
                    try (OutputStream os = new FileOutputStream(file)) {
                        os.write(exportJson.getBytes("UTF-8"));
                    }
                }

                Toast.makeText(context, "BLS exported to Downloads/PvP Manager", Toast.LENGTH_LONG).show();
                appendLog("system", "BLS exported: " + filename + " (" + exportArr.length() + " players)");
            } catch (Exception e) {
                appendLog("error", "BLS export failed: " + e.getMessage());
                Toast.makeText(context, "BLS export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Returns true if a PvP match is currently active, by reading the
     * last-known cached live state from SharedPreferences. Used by
     * switchToAccount() to block mid-battle account switching.
     */
    public boolean isMatchActive() {
        String cached = getStringScoped(KEY_CACHED_LIVE, null);
        if (cached == null || cached.isEmpty()) return false;
        try {
            JSONObject live  = new JSONObject(cached);
            JSONObject match = live.optJSONObject("match");
            return match != null && match.optBoolean("active", false);
        } catch (JSONException e) { return false; }
    }

    public void deliverBlsFileContent(InputStream is) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            String content = sb.toString().trim();
            new JSONObject(content);
            final String escaped = content.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
            mainHandler.post(() -> {
                if (uiWebView != null) {
                    uiWebView.evaluateJavascript(
                        "if(window.__pvpmBlsImportCallback) window.__pvpmBlsImportCallback('" + escaped + "');", null);
                }
            });
        } catch (Exception e) {
            appendLog("error", "BLS file read failed: " + e.getMessage());
        }
    }

    // ── Misc JS Interface ────────────────────────────────────────────────────

    /** Silently clears the in-memory log buffer — called on account switch so logs don't mix. */
    public void clearLogBuffer() {
        synchronized (logLines) { logLines.clear(); }
    }

    @JavascriptInterface
    public void clearLogs() {
        _stateDirty = true;
        // 1. Clear Android-side log buffer
        synchronized (logLines) { logLines.clear(); }

        // 2. Inject into gameWebView so logHistory + gameLogs are wiped in the live runtime
        mainHandler.post(() -> {
            if (gameWebView != null) {
                gameWebView.evaluateJavascript(
                    "if(window.__pvpmClearLogs) window.__pvpmClearLogs();", null);
            }
        });

        // 3. Strip gameLogs from the cached live-state snapshot so the next
        //    getState() call does not re-inject stale logs into the UI.
        String cached = getStringScoped(KEY_CACHED_LIVE, null);
        if (cached != null) {
            try {
                JSONObject live = new JSONObject(cached);
                live.put("gameLogs", "");
                putStringScoped(KEY_CACHED_LIVE, live.toString());
            } catch (JSONException ignored) {}
        }

        appendLog("system", "Logs cleared");
        notifyUiStateChanged();
    }

    @JavascriptInterface
    public void resetSession() {
        _stateDirty = true;
        // 1. Zero the live counters inside gameWebView (runtime + scoped localStorage)
        mainHandler.post(() -> {
            if (gameWebView != null) {
                gameWebView.evaluateJavascript(
                    "if(window.__pvpmResetSession) window.__pvpmResetSession();", null);
            }
        });

        // 2. Clear the scoped SESSION_KEY in SharedPrefs so reset survives restart
        String activeId = accountStore.getActiveAccountId();
        if (activeId != null && !activeId.isEmpty()) {
            prefs.edit().remove("et_pvp_session_" + activeId).apply();
        }
        prefs.edit().remove("et_pvp_session").apply();

        // 3. Clear matchHistory and battleStats in the cached live-state snapshot so the
        //    Android UI reflects the reset on the very next getState() call.
        String cached = getStringScoped(KEY_CACHED_LIVE, null);
        if (cached != null) {
            try {
                JSONObject live = new JSONObject(cached);
                // Clear match history so W/L pill counts drop to zero immediately
                live.put("matchHistory", new JSONArray());
                if (live.has("battleStats")) {
                    JSONObject bs = live.getJSONObject("battleStats");
                    bs.put("wins",    0);
                    bs.put("losses",  0);
                    bs.put("matches", 0);
                    live.put("battleStats", bs);
                }
                putStringScoped(KEY_CACHED_LIVE, live.toString());
            } catch (JSONException ignored) {}
        }

        appendLog("system", "W/L session reset for active account");
        notifyUiStateChanged();
    }

    @JavascriptInterface
    public void copyLogs() {
        ClipboardManager cb = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) {
            StringBuilder sb = new StringBuilder();
            synchronized (logLines) { for (String line : logLines) sb.append(line).append("\n"); }
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
    public void openProfile(String uid) { openUrl("https://demonicscans.org/player.php?pid=" + uid); }

    @JavascriptInterface
    public void openMatch(String matchId) { openUrl("https://demonicscans.org/pvp_battle.php?match_id=" + matchId); }

    // ── Package-private helpers used by MainActivity ─────────────────────────

    void evaluateInGameWebView(String js) {
        mainHandler.post(() -> { if (gameWebView != null) gameWebView.evaluateJavascript(js, null); });
    }

    /** Saves current bot running state for the active account. Called before switching. */
    void saveRunningStateForCurrentAccount() {
        // Already stored via putBoolScoped — nothing extra needed; this is a no-op
        // but kept for clarity in the switch flow.
    }

    /** Restores strategy JSON into gameWebView localStorage for the newly active account. */
    void restoreStrategyToGameWebView() {
        String stratJson = getStringScoped(KEY_STRATEGY, null);
        if (stratJson != null) {
            String esc = stratJson.replace("\\", "\\\\").replace("'", "\\'");
            evaluateInGameWebView("try{localStorage.setItem('et_pvp_solo_strategy','" + esc + "');}catch(e){}");
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void openUrl(String url) {
        mainHandler.post(() -> {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        });
    }

    public void appendLog(String type, String message) {
        _stateDirty = true;
        String line = System.currentTimeMillis() + "|" + type + "|" + message;
        synchronized (logLines) {
            logLines.addLast(line);
            while (logLines.size() > LOG_MAX_ENTRIES) logLines.removeFirst();
        }
        Log.d("PvPManager", type + ": " + message);
    }

    public void notifyUiStateChanged() {
        _stateDirty = true;
        mainHandler.post(() -> {
            if (uiWebView != null) {
                uiWebView.evaluateJavascript("if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            }
        });
    }

    /**
     * Called by pvp_manager.js when the bot stops itself (tokens exhausted,
     * stop-after-match completed, max-error limit hit, etc.).
     * Clears the persisted KEY_RUNNING flag in SharedPrefs so the native
     * Start/Stop button correctly flips from "Stop" back to "Start".
     * Without this, getState() still reads KEY_RUNNING=true from SharedPrefs
     * even though the JS closure has already set running=false.
     */
    @JavascriptInterface
    public void onBotSelfStopped() {
        _stateDirty = true;
        putBoolScoped(KEY_RUNNING, false);
        appendLog("system", "Bot self-stopped — native running flag cleared");
        notifyUiStateChanged();
    }
}
