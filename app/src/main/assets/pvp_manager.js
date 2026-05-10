// ==UserScript==
// @name         Empire Tools - PvP Manager
// @namespace    veyra-empire-tools
// @version      1.19.1
// @description  PvP quality-of-life features (background-tab safe, occlusion-safe via silent audio, responsive down to 316px). Adds Solo Strategy: opt-in skill rotation with per-entry conditions, repeat counts, and recycle flag, evaluated on each of your turns when the master toggle is on.
// @author       lmv
// @match        https://demonicscans.org/*
// @grant        none
// ==/UserScript==

(() => {
    'use strict';

    /* =========================================================
        EMPIRE settings-drawer integration

        Adds a master "PvP Manager" toggle to the EMPIRE addon's
        settings drawer when the addon is installed. Pattern follows
        scripts/EMPIRE_SIDEBAR_INTEGRATION.md and the working
        reference in authors/lmv/roster-sort.user.js.

        - Without EMPIRE: feature runs unconditionally on pvp.php.
        - With EMPIRE: feature runs only if the toggle is on (default).
        - The drawer toggle is injected on every in-game page so it
          is reachable wherever the user opens the drawer.
    ========================================================= */
    const MASTER_ENABLED_KEY        = 'et_pvp_manager_enabled';
    const TOGGLE_LABEL              = 'PvP Manager';
    const TOGGLE_DESC               = 'Strategies rely on PvP Stats Display.';
    const DEBUG_LOGS_KEY            = 'et_pvp_debug_logs';
    const DEBUG_TOGGLE_LABEL        = 'PvP Manager debug logs';
    const DEBUG_TOGGLE_DESC         = '';
    const DEBUG_OWNED_ATTR          = 'data-et-pvp-manager-debug-owned';
    const EMPIRE_GROUP_ID           = 'et-settings-pvp';
    const EMPIRE_GROUP_TITLE        = 'PvP';
    const EMPIRE_GROUP_COLLAPSE_KEY = 'et_drawer_group_pvp_collapsed';
    const EMPIRE_DRAWER_CONTAINER_ID = 'et-settings-drawer-container';
    const OWNED_TOGGLE_ATTR         = 'data-et-pvp-manager-userscript-owned';

    const EMPIRE_PRESENCE_SELECTORS = [
        '#et-native-injected-side-nav-styles',
        '#et-topbar-hp',
        '#et-open-settings-drawer-btn',
        '#et-settings-drawer'
    ];
    const EMPIRE_DETECT_MAX_MS   = 500;
    const DRAWER_OBSERVER_MAX_MS = 15000;

    function storageAllowsRun() {
        let raw;
        try { raw = localStorage.getItem(MASTER_ENABLED_KEY); } catch (e) { return true; }
        if (raw === null || raw === undefined) return true;
        try { return JSON.parse(raw) !== false; } catch (e) { return true; }
    }

    // Asymmetric default: when EMPIRE is absent the toggle has no UI to flip,
    // so a stored `false` would silently strand the user. Run unconditionally
    // in that case.
    function shouldRun(empirePresent) {
        return empirePresent ? storageAllowsRun() : true;
    }

    function detectEmpireSync() {
        return EMPIRE_PRESENCE_SELECTORS.some((s) => {
            try { return !!document.querySelector(s); } catch (e) { return false; }
        });
    }

    function detectEmpirePresent(onResult, maxMs = EMPIRE_DETECT_MAX_MS) {
        let settled = false, observer = null;
        const settle = (p) => {
            if (settled) return;
            settled = true;
            if (observer) { try { observer.disconnect(); } catch (e) {} }
            try { onResult(p); } catch (e) {}
        };
        if (detectEmpireSync()) { settle(true); return; }
        observer = new MutationObserver(() => {
            if (!settled && detectEmpireSync()) settle(true);
        });
        try {
            observer.observe(document.documentElement, { childList: true, subtree: true });
        } catch (e) { settle(false); return; }
        setTimeout(() => settle(false), maxMs);
    }

    function isInGamePage() {
        try {
            return !!document.querySelector('div.content-area') ||
                   document.body.classList.contains('mapMode');
        } catch (e) { return false; }
    }

    function isPvpPage() {
        try {
            return /\/pvp\.php(?:$|[?#])/.test(location.pathname + location.search);
        } catch (e) { return false; }
    }

    function _buildEtSwitchLabel({ ownedAttr, title, desc, checked, onChange }) {
        const wrap = document.createElement('label');
        wrap.className = 'et-switch-label';
        wrap.setAttribute(ownedAttr, '1');
        wrap.style.paddingBottom = '8px';

        const input = document.createElement('input');
        input.type = 'checkbox';
        input.checked = !!checked;

        const slider = document.createElement('span');
        slider.className = 'et-slider';

        const textWrap = document.createElement('span');
        textWrap.className = 'et-switch-text';

        const titleEl = document.createElement('span');
        titleEl.className = 'et-switch-title';
        titleEl.textContent = title;
        textWrap.appendChild(titleEl);

        if (desc) {
            const descEl = document.createElement('span');
            descEl.className = 'et-switch-desc';
            descEl.textContent = desc;
            textWrap.appendChild(descEl);
        }

        input.addEventListener('change', () => {
            try { onChange(!!input.checked); } catch (e) {}
        });

        wrap.appendChild(input);
        wrap.appendChild(slider);
        wrap.appendChild(textWrap);
        return wrap;
    }

    function buildOwnedToggle() {
        return _buildEtSwitchLabel({
            ownedAttr: OWNED_TOGGLE_ATTR,
            title:     TOGGLE_LABEL,
            desc:      TOGGLE_DESC,
            checked:   storageAllowsRun(),
            onChange:  (next) => {
                try { localStorage.setItem(MASTER_ENABLED_KEY, JSON.stringify(!!next)); } catch (e) {}
            }
        });
    }

    function isDebugLogsEnabled() {
        try { return localStorage.getItem(DEBUG_LOGS_KEY) === 'true'; } catch (e) { return false; }
    }

    function buildDebugToggle() {
        return _buildEtSwitchLabel({
            ownedAttr: DEBUG_OWNED_ATTR,
            title:     DEBUG_TOGGLE_LABEL,
            desc:      DEBUG_TOGGLE_DESC,
            checked:   isDebugLogsEnabled(),
            onChange:  (next) => {
                try { localStorage.setItem(DEBUG_LOGS_KEY, String(!!next)); } catch (e) {}
            }
        });
    }

    function scrubNativeToggles(group) {
        group.querySelectorAll('.et-switch-label').forEach((lbl) => {
            if (lbl.getAttribute(OWNED_TOGGLE_ATTR) === '1') return;
            if (lbl.getAttribute(DEBUG_OWNED_ATTR) === '1') return;
            const t = lbl.querySelector('.et-switch-title');
            if (t && String(t.textContent || '').trim() === TOGGLE_LABEL) lbl.remove();
        });
    }

    // EMPIRE does not register a "PvP" group natively; create it ourselves
    // (idempotent, shape matches et-settings-drawer.js registerGroup() so
    // the drawer's CSS styles it). Both PvP Manager and ogmaend's PvP
    // Stats Display share this group id - whichever script boots first
    // creates the group + collapse handler, the other reuses it.
    //
    // Returns { group, body } - callers should append their toggles/rows
    // to `body` so they get hidden when the group is collapsed.
    function ensurePvpGroup() {
        let group = document.getElementById(EMPIRE_GROUP_ID);
        if (group) {
            const existingBody = group.querySelector('.et-pvp-group-body');
            if (existingBody) return { group, body: existingBody };
            // Group exists but wasn't created by us (older script version,
            // or another author skipped the body wrapper). Treat the group
            // itself as the body for backward compatibility.
            return { group, body: group };
        }
        const container = document.getElementById(EMPIRE_DRAWER_CONTAINER_ID);
        if (!container) return null;

        group = document.createElement('div');
        group.className = 'et-settings-group';
        group.id = EMPIRE_GROUP_ID;

        const titleEl = document.createElement('div');
        titleEl.className = 'et-settings-group-title';
        titleEl.style.cssText = 'cursor:pointer; user-select:none; display:flex; align-items:center; gap:6px;';

        const arrowEl = document.createElement('span');
        arrowEl.className = 'et-pvp-group-arrow';
        arrowEl.style.cssText = 'font-size:10px; line-height:1; opacity:0.7;';

        const titleTxt = document.createElement('span');
        titleTxt.textContent = EMPIRE_GROUP_TITLE;
        titleTxt.style.flex = '1';

        titleEl.appendChild(arrowEl);
        titleEl.appendChild(titleTxt);

        const body = document.createElement('div');
        body.className = 'et-pvp-group-body';

        let collapsed = false;
        try { collapsed = localStorage.getItem(EMPIRE_GROUP_COLLAPSE_KEY) === 'true'; } catch (e) {}
        const applyCollapsed = (v) => {
            body.style.display = v ? 'none' : '';
            arrowEl.textContent = v ? '▶' : '▼';
        };
        applyCollapsed(collapsed);

        titleEl.addEventListener('click', () => {
            collapsed = !collapsed;
            applyCollapsed(collapsed);
            try { localStorage.setItem(EMPIRE_GROUP_COLLAPSE_KEY, String(collapsed)); } catch (e) {}
        });

        group.appendChild(titleEl);
        group.appendChild(body);
        container.appendChild(group);
        return { group, body };
    }

    function replaceDrawerToggle() {
        const ref = ensurePvpGroup();
        if (!ref) return false;
        const { body } = ref;
        if (body.querySelector('.et-switch-label[' + OWNED_TOGGLE_ATTR + '="1"]')) return true;
        scrubNativeToggles(body);
        body.appendChild(buildOwnedToggle());
        body.appendChild(buildDebugToggle());
        return true;
    }

    function startDrawerWatcher() {
        let settled = false, observer = null;
        const settle = () => {
            if (settled) return; settled = true;
            if (observer) { try { observer.disconnect(); } catch (e) {} }
        };
        if (replaceDrawerToggle()) { settle(); return; }
        observer = new MutationObserver(() => {
            if (!settled && replaceDrawerToggle()) settle();
        });
        try {
            observer.observe(document.body, { childList: true, subtree: true });
        } catch (e) { settle(); return; }
        setTimeout(settle, DRAWER_OBSERVER_MAX_MS);
    }

    function boot() {
        if (isInGamePage()) startDrawerWatcher();
        if (!isPvpPage()) return;
        detectEmpirePresent((present) => {
            if (shouldRun(present)) runFeature();
        });
    }

    // The original PvP Manager body is wrapped in runFeature so the master
    // toggle can fully gate it (Worker spawn, DOM creation, panel mount, all
    // event listeners). Indentation inside is preserved at the original
    // 4-space level rather than re-indented to 8 spaces, to keep the diff
    // minimal and avoid touching multiline template literals.
    function runFeature() {

    /* =========================================================
        ACCOUNT-SCOPED STORAGE
        activePlayerId is resolved ONCE at startup by reading the game
        page DOM (same selector AndroidBridge uses).  All gameplay keys
        are then stored as  baseKey + '_' + activePlayerId  so that
        switching accounts never mixes data.

        GLOBAL keys (never scoped):
          bls_memory, et_pvp_debug_logs, et_pvp_manager_enabled,
          et_pvp_panel_pos, et_pvp_minimized, et_pvp_log_limit,
          et_pvp_persist_logs, et_pvp_history_limit,
          et_pvp_af_coordinator_enabled, et_drawer_group_pvp_collapsed,
          veyra_af_*, MASTER_ENABLED_KEY, DEBUG_LOGS_KEY,
          EMPIRE_GROUP_COLLAPSE_KEY

        PER-ACCOUNT keys (scoped):
          SESSION_KEY, HISTORY_STORE_KEY, STRATEGY_KEY,
          LOG_STORE_KEY, ACTIVE_TAB_KEY, SKILL_IMG_KEY
    ========================================================= */

    /**
     * Extract the active player ID from the game page DOM.
     * Same selector chain AndroidBridge.extractCurrentAccount() uses.
     * Returns a string like "133841", or null if not available.
     */
    function resolveActivePlayerId() {
        try {
            // L1 — scan ALL a[href*="player.php?pid="] links (sidebar, nav, profile widgets)
            const links = document.querySelectorAll('a[href*="player.php?pid="]');
            for (let i = 0; i < links.length; i++) {
                const m = (links[i].getAttribute('href') || '').match(/pid=(\d+)/);
                if (m) return m[1];
            }
            // L2 — current page URL carries the pid (e.g. /player.php?pid=133841)
            const urlM = location.href.match(/player\.php[^#]*[?&]pid=(\d+)/);
            if (urlM) return urlM[1];
            // L3 — any a[href*="player.php"] carrying pid in a different query layout
            const wideLinks = document.querySelectorAll('a[href*="player.php"]');
            for (let i = 0; i < wideLinks.length; i++) {
                const href = wideLinks[i].getAttribute('href') || '';
                const m2 = href.match(/[?&]pid=(\d+)/);
                if (m2) return m2[1];
            }
            return null;
        } catch (e) { return null; }
    }

    /**
     * Extract the player's display name from the page DOM.
     * Returns the name string, or null if not resolvable.
     * Used to enrich the account record saved on first identity extraction.
     */
    function resolveActivePlayerName() {
        try {
            const nameEl = document.querySelector('.small-name')
                || document.querySelector('.user-name')
                || document.querySelector('.profile-name')
                || document.querySelector('.player-name')
                || document.querySelector('h1.name')
                || document.querySelector('h2.name');
            if (nameEl) {
                const t = nameEl.textContent.trim();
                if (t.length > 0) return t;
            }
            // Fallback: use the link text of the profile link if it looks like a name
            const link = document.querySelector('a[href*="player.php?pid="]');
            if (link) {
                const lt = (link.textContent || '').trim();
                if (lt.length > 1 && !lt.startsWith('http')) return lt;
            }
            return null;
        } catch (e) { return null; }
    }

    /**
     * Extract the player's avatar URL from the page DOM.
     * Returns an absolute URL string, or null.
     */
    function resolveActivePlayerAvatar() {
        try {
            const avaEl = document.querySelector('.small-ava img')
                || document.querySelector('.user-avatar img')
                || document.querySelector('.profile-avatar img')
                || document.querySelector('.avatar img')
                || document.querySelector('img[src*="avatars/user_"]');
            if (!avaEl) return null;
            const src = avaEl.getAttribute('src') || '';
            if (!src) return null;
            return src.startsWith('http') ? src : 'https://demonicscans.org/' + src.replace(/^\//, '');
        } catch (e) { return null; }
    }

    /**
     * The active player ID for this page-load.  Resolved once during init.
     * May be null if the page loaded before the DOM had the player link.
     * _accountSwitchWatcher will re-resolve it from DOM or window.__pvpmActivePlayerId
     * and reload all scoped state once identity becomes available.
     */
    let _activePlayerId = resolveActivePlayerId();
    // True when we initialised without a known player ID — triggers a state reload
    // in _accountSwitchWatcher once identity is first resolved.
    let _identityWasNullAtInit = !_activePlayerId;

    /**
     * Return a storage key scoped to the active account.
     * If no player ID is known yet, falls back to the bare base key so
     * nothing is silently dropped (backward-compatible with pre-isolation saves).
     * Example:  scopedKey('et_pvp_session')  →  'et_pvp_session_133841'
     */
    function scopedKey(baseKey) {
        return _activePlayerId ? baseKey + '_' + _activePlayerId : baseKey;
    }

    /**
     * Read a per-account localStorage value with automatic one-time migration.
     * If the scoped key is missing but the bare (legacy) key exists, copies it
     * to the scoped key, then removes the legacy key, to prevent cross-account
     * contamination going forward.
     * Returns the stored string, or null.
     */
    function scopedGet(baseKey) {
        const sk = scopedKey(baseKey);
        if (sk === baseKey) return localStorage.getItem(baseKey); // no player ID yet
        let val = localStorage.getItem(sk);
        if (val === null) {
            // Migration: legacy global key → scoped key (runs at most once per account per key)
            const legacy = localStorage.getItem(baseKey);
            if (legacy !== null) {
                try { localStorage.setItem(sk, legacy); } catch (_) {}
                // Do NOT remove the legacy key here — other accounts that haven't
                // been seen yet might still need it as their migration source.
                // It will be overwritten once those accounts load.
                val = legacy;
            }
        }
        return val;
    }

    /**
     * Write a per-account localStorage value.
     */
    function scopedSet(baseKey, value) {
        try { localStorage.setItem(scopedKey(baseKey), value); } catch (_) {}
    }

    /**
     * Remove a per-account localStorage key (scoped + bare legacy).
     */
    function scopedRemove(baseKey) {
        try {
            localStorage.removeItem(scopedKey(baseKey));
            // Also remove legacy bare key if player ID is known (migration cleanup)
            if (_activePlayerId) localStorage.removeItem(baseKey);
        } catch (_) {}
    }

    /* =========================================================
        CONSTANTS
    ========================================================= */
    const MATCHMAKE_URL    = 'https://demonicscans.org/pvp_matchmake.php';
    const BATTLE_ACT_URL   = 'https://demonicscans.org/pvp_battle_action.php';
    const BATTLE_STATE_URL = 'https://demonicscans.org/pvp_battle_state.php';
    const PVP_URL          = 'https://demonicscans.org/pvp.php';
    const PROFILE_URL      = 'https://demonicscans.org/player.php';

    const POLL_INTERVAL_MS      = 1500;   // ms between polls during active battle
    const IDLE_POLL_INTERVAL_MS = 5000;   // ms between polls when idle / finding match
    const LOG_LIMIT        = 500;    // max entries kept in log

    const PANEL_POS_KEY    = 'et_pvp_panel_pos';
    const UI_MIN_KEY       = 'et_pvp_minimized';
    const LOG_STORE_KEY    = 'et_pvp_log_entries';   // persisted log entries (JSON)
    const LOG_LIMIT_KEY    = 'et_pvp_log_limit';     // user-configured max log count
    const PERSIST_LOG_KEY  = 'et_pvp_persist_logs';  // whether to persist logs across refreshes
    const SESSION_KEY      = 'et_pvp_session';        // persisted W/L session data (JSON)
    const HISTORY_STORE_KEY = 'et_pvp_match_history';  // persisted match history (JSON)
    const MAX_HISTORY       = 5000;                    // rolling cap — oldest entries auto-deleted

    const BASE_URL                = 'https://demonicscans.org';

    // Solo Strategy
    const STRATEGY_KEY            = 'et_pvp_solo_strategy';   // JSON: { enabled, entries[] }
    const ACTIVE_TAB_KEY          = 'et_pvp_active_tab';      // 'battle' | 'strategy' | 'settings'
    const FALLBACK_SKILL_ID       = '0';                       // basic attack (Slash) when no entry matches
    const SKILL_IMG_KEY           = 'et_pvp_skill_images';     // JSON: { [skillId]: imageUrl }

    const BATTLE_INTRO_PAIRS = [
        ['Into the clash', 'Begin!'],
        ['The battle ignites', 'Go!'],
        ['Step into combat', 'Figh
