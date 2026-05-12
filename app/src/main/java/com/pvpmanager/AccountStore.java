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
 *
 * PATCH 3: Thread-safe read-modify-write using a dedicated lock object.
 * PATCH 3: Lightweight in-memory account-list cache — reloaded from
 *          SharedPreferences only when the list is mutated (add/remove/update)
 *          or the active account changes. This eliminates repeated JSON
 *          re-parsing on every getAccounts() call while avoiding stale state.
 */
public class AccountStore {

    private static final String PREFS_NAME   = "pvp_account_store";
    private static final String KEY_ACCOUNTS = "accounts_json";
    private static final String KEY_ACTIVE   = "active_account_id";

    private final SharedPreferences prefs;

    // ── PATCH 3: Synchronization lock & in-memory cache ──────────────────────
    // Single lock object guards all read-modify-write operations so concurrent
    // callers (UI thread + BLS sync thread) cannot corrupt the account array.
    private final Object accountLock = new Object();

    /** Cached parsed account array. Null means "invalid — reload from prefs". */
    private JSONArray  cachedAccounts    = null;
    /** Cached active account ID string (mirrors the prefs value in memory). */
    private String     cachedActiveId    = null;
    /** True after the first load so we don't re-read prefs on every getActiveAccountId(). */
    private boolean    activeIdLoaded    = false;

    public AccountStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── Internal cache helpers ────────────────────────────────────────────────

    /**
     * Returns the cached account array, loading from SharedPreferences if the
     * cache is invalid. Must be called while holding {@code accountLock}.
     */
    private JSONArray getCachedAccounts() {
        if (cachedAccounts == null) {
            try { cachedAccounts = new JSONArray(prefs.getString(KEY_ACCOUNTS, "[]")); }
            catch (JSONException e) { cachedAccounts = new JSONArray(); }
        }
        return cachedAccounts;
    }

    /** Invalidates the in-memory account-list cache. Must hold {@code accountLock}. */
    private void invalidateCache() {
        cachedAccounts = null;
    }

    // ── Account list ─────────────────────────────────────────────────────────

    /**
     * Returns the current account list.
     * Reads from the in-memory cache; falls back to SharedPreferences only when
     * the cache has been invalidated by a write operation.
     */
    public JSONArray getAccounts() {
        synchronized (accountLock) {
            // Return a shallow copy so callers cannot mutate the cached array directly.
            JSONArray source = getCachedAccounts();
            JSONArray copy   = new JSONArray();
            for (int i = 0; i < source.length(); i++) {
                try { copy.put(source.getJSONObject(i)); } catch (JSONException ignored) {}
            }
            return copy;
        }
    }

    public JSONObject getAccount(String playerId) {
        if (playerId == null || playerId.isEmpty()) return null;
        synchronized (accountLock) {
            JSONArray arr = getCachedAccounts();
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject a = arr.getJSONObject(i);
                    if (playerId.equals(a.optString("playerId"))) return a;
                } catch (JSONException ignored) {}
            }
        }
        return null;
    }

    /** Save or update an account. Uses playerId as the primary key. */
    public void saveAccount(JSONObject account) {
        if (account == null) return;
        String pid = account.optString("playerId", "");
        if (pid.isEmpty()) return;

        synchronized (accountLock) {
            JSONArray arr = getCachedAccounts();
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
            // Persist and keep cache valid (arr IS the cached array, already updated).
            prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply();
            // No invalidate needed — we mutated the cached array in-place above.
        }
    }

    /** Remove an account by playerId. Does NOT touch pvp_manager_prefs scoped keys — caller handles that. */
    public void removeAccount(String playerId) {
        if (playerId == null) return;
        synchronized (accountLock) {
            JSONArray arr     = getCachedAccounts();
            JSONArray updated = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject a = arr.getJSONObject(i);
                    if (!playerId.equals(a.optString("playerId"))) updated.put(a);
                } catch (JSONException ignored) {}
            }
            prefs.edit().putString(KEY_ACCOUNTS, updated.toString()).apply();
            // Replace cache with the filtered array.
            cachedAccounts = updated;
        }
    }

    // ── Active account ────────────────────────────────────────────────────────

    public String getActiveAccountId() {
        synchronized (accountLock) {
            if (!activeIdLoaded) {
                cachedActiveId = prefs.getString(KEY_ACTIVE, null);
                activeIdLoaded = true;
            }
            return cachedActiveId;
        }
    }

    public void setActiveAccountId(String playerId) {
        synchronized (accountLock) {
            if (playerId == null) prefs.edit().remove(KEY_ACTIVE).apply();
            else                  prefs.edit().putString(KEY_ACTIVE, playerId).apply();
            cachedActiveId = playerId;
            activeIdLoaded = true;
        }
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

    public int getAccountCount() {
        synchronized (accountLock) { return getCachedAccounts().length(); }
    }

    public boolean hasAccounts() {
        synchronized (accountLock) { return getCachedAccounts().length() > 0; }
    }
}
