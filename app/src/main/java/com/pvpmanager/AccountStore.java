package com.pvpmanager;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Manages multi-account profiles.
 * Stores globally: active_account_id, accounts list.
 * Each account: playerId, playerName, avatarUrl, level, cookies, lastLogin.
 * BLS memory is NOT stored here — it remains global in pvp_manager_prefs.
 */
public class AccountStore {

    private static final String PREFS_NAME   = "pvp_account_store";
    private static final String KEY_ACCOUNTS = "accounts_json";
    private static final String KEY_ACTIVE   = "active_account_id";

    private final SharedPreferences prefs;

    // ── In-memory account cache (PATCH 2) ─────────────────────────────────────
    /** Parsed account list; null means the cache is cold and must be loaded from prefs. */
    private JSONArray _cachedAccounts = null;
    /** True whenever the prefs backing store may differ from _cachedAccounts. */
    private boolean   _accountsDirty  = true;

    public AccountStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── Account list ─────────────────────────────────────────────────────────

    public synchronized JSONArray getAccounts() {
        if (!_accountsDirty && _cachedAccounts != null) return _cachedAccounts;
        try { _cachedAccounts = new JSONArray(prefs.getString(KEY_ACCOUNTS, "[]")); }
        catch (JSONException e) { _cachedAccounts = new JSONArray(); }
        _accountsDirty = false;
        return _cachedAccounts;
    }

    public JSONObject getAccount(String playerId) {
        if (playerId == null || playerId.isEmpty()) return null;
        JSONArray arr = getAccounts();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject a = arr.getJSONObject(i);
                if (playerId.equals(a.optString("playerId"))) return a;
            } catch (JSONException ignored) {}
        }
        return null;
    }

    /** Save or update an account. Uses playerId as the primary key. */
    public synchronized void saveAccount(JSONObject account) {
        if (account == null) return;
        String pid = account.optString("playerId", "");
        if (pid.isEmpty()) return;

        JSONArray arr = getAccounts();
        boolean found = false;
        for (int i = 0; i < arr.length(); i++) {
            try {
                if (pid.equals(arr.getJSONObject(i).optString("playerId"))) {
                    arr.put(i, account);
                    found = true;
                    break;
                }
            } catch (JSONException ignored) {}
        }
        if (!found) arr.put(account);
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply();
        // Cache is already up-to-date (arr is _cachedAccounts); keep dirty=false.
    }

    /** Remove an account by playerId. Does NOT touch pvp_manager_prefs scoped keys — caller handles that. */
    public synchronized void removeAccount(String playerId) {
        if (playerId == null) return;
        JSONArray arr = getAccounts();
        JSONArray updated = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject a = arr.getJSONObject(i);
                if (!playerId.equals(a.optString("playerId"))) updated.put(a);
            } catch (JSONException ignored) {}
        }
        prefs.edit().putString(KEY_ACCOUNTS, updated.toString()).apply();
        _cachedAccounts = updated;
        _accountsDirty  = false;
    }

    // ── Active account ────────────────────────────────────────────────────────

    public String getActiveAccountId() {
        return prefs.getString(KEY_ACTIVE, null);
    }

    public void setActiveAccountId(String playerId) {
        if (playerId == null) prefs.edit().remove(KEY_ACTIVE).apply();
        else                  prefs.edit().putString(KEY_ACTIVE, playerId).apply();
    }

    public JSONObject getActiveAccount() {
        return getAccount(getActiveAccountId());
    }

    // ── Cookie helpers ────────────────────────────────────────────────────────

    public void updateCookies(String playerId, String cookies) {
        JSONObject acc = getAccount(playerId);
        if (acc == null) {
            acc = new JSONObject();
            try { acc.put("playerId", playerId); acc.put("playerName", "Unknown"); }
            catch (JSONException ignored) {}
        }
        try {
            acc.put("cookies",   cookies != null ? cookies : "");
            acc.put("lastLogin", System.currentTimeMillis());
        } catch (JSONException ignored) {}
        saveAccount(acc);
    }

    // ── Misc ─────────────────────────────────────────────────────────────────

    public synchronized int getAccountCount() { return getAccounts().length(); }

    public synchronized boolean hasAccounts() { return getAccounts().length() > 0; }
}
