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
        CONSTANTS
    ========================================================= */
    const MATCHMAKE_URL    = 'https://demonicscans.org/pvp_matchmake.php';
    const BATTLE_ACT_URL   = 'https://demonicscans.org/pvp_battle_action.php';
    const BATTLE_STATE_URL = 'https://demonicscans.org/pvp_battle_state.php';
    const PVP_URL          = 'https://demonicscans.org/pvp.php';
    const PROFILE_URL      = 'https://demonicscans.org/player.php';

    const POLL_INTERVAL_MS = 1500;   // ms between battle state polls
    const LOG_LIMIT        = 500;    // max entries kept in log

    const PANEL_POS_KEY    = 'et_pvp_panel_pos';
    const UI_MIN_KEY       = 'et_pvp_minimized';
    const LOG_STORE_KEY    = 'et_pvp_log_entries';   // persisted log entries (JSON)
    const LOG_LIMIT_KEY    = 'et_pvp_log_limit';     // user-configured max log count
    const PERSIST_LOG_KEY  = 'et_pvp_persist_logs';  // whether to persist logs across refreshes
    const SESSION_KEY      = 'et_pvp_session';        // persisted W/L session data (JSON)
    const HISTORY_STORE_KEY = 'et_pvp_match_history';  // persisted match history (JSON)
    const HISTORY_LIMIT_KEY = 'et_pvp_history_limit';  // user-configured max history entries
    const HISTORY_LIMIT     = 200;                     // default max match history entries

    const BASE_URL                = 'https://demonicscans.org';
    const AF_COORD_ENABLED_KEY    = 'et_pvp_af_coordinator_enabled';

    // Solo Strategy
    const STRATEGY_KEY            = 'et_pvp_solo_strategy';   // JSON: { enabled, entries[] }
    const ACTIVE_TAB_KEY          = 'et_pvp_active_tab';      // 'battle' | 'strategy' | 'settings'
    const FALLBACK_SKILL_ID       = '0';                       // basic attack (Slash) when no entry matches
    const SKILL_IMG_KEY           = 'et_pvp_skill_images';     // JSON: { [skillId]: imageUrl }

    const BATTLE_INTRO_PAIRS = [
        ['Into the clash', 'Begin!'],
        ['The battle ignites', 'Go!'],
        ['Step into combat', 'Fight!'],
        ['The moment has come', 'Engage!'],
        ['Steel yourself', 'Begin!'],
        ['The arena calls', 'Move out!'],
        ['To the heart of battle', 'Go!'],
        ['The clash begins', 'Press on!'],
        ['Face your foe', 'Commence!'],
        ['The contest is at hand', 'Begin!'],

        ['Destiny meets steel', 'Begin!'],
        ['The next trial awaits', 'Go!'],
        ['The air burns with conflict', 'Fight!'],
        ['A storm of wills approaches', 'Engage!'],
        ['Victory waits for one', 'Begin!'],
        ['The hour of combat is here', 'Go!'],
        ['Pride enters the arena', 'Fight!'],
        ['Only one path remains', 'Forward!'],
        ['The decisive clash is here', 'Fight!'],
        ['The trial is before you', 'Endure!'],

        ['By imperial will', 'Advance!'],
        ['The Emperor\'s judgment falls', 'Submit!'],
        ['Guards to formation', 'Engage!'],
        ['Imperial might stands ready', 'Prove it!'],
        ['Stand ready', 'Crush opposition!'],
        ['The Crown commands', 'Advance!'],
        ['In the Emperor\'s name', 'Fight!'],
        ['Hold nothing back', 'Commence!'],
        ['Order stands before resistance', 'Prevail!'],
        ['Forward under imperial banner', 'Advance!'],

        ['Your strength is needed', 'Show it!'],
        ['The way stands closed', 'Break through!'],
        ['Mercy is forfeit', 'Begin!'],
        ['The battlefield awaits', 'To battle!'],
        ['The order is given', 'Engage!'],
        ['Your aim will decide this', 'Strike true!'],
        ['The signal is given', 'Fight!'],
        ['The path is open', 'Advance!'],
        ['The clash is before you', 'Begin!'],
        ['The line will not move itself', 'Press forward!']
    ];

    /* ======================
       SHARED AUTO-FARM COORDINATOR
       Keep identical across PvE Manager, Dungeon Manager, and PvP Manager
       so all scripts agree on state across tabs via reference counting.
    ====================== */
    const AF_KEY_ACTIVE   = 'veyra_af_active_runs';     // { [runId]: lastSeenMs }
    const AF_KEY_ORIGINAL = 'veyra_af_original_state';  // '1' | '0'
    const AF_LOCK_NAME    = 'veyra_af_coordinator';
    const AF_STALE_MS     = 15 * 60 * 1000;
    const AF_HEARTBEAT_MS = 60 * 1000;

    function afReadMap() {
        try { return JSON.parse(localStorage.getItem(AF_KEY_ACTIVE) || '{}') || {}; }
        catch { return {}; }
    }
    function afWriteMap(m) {
        if (!m || Object.keys(m).length === 0) localStorage.removeItem(AF_KEY_ACTIVE);
        else localStorage.setItem(AF_KEY_ACTIVE, JSON.stringify(m));
    }
    function afPrune(m) {
        const now = Date.now();
        for (const k of Object.keys(m)) if (now - (m[k] || 0) > AF_STALE_MS) delete m[k];
        return m;
    }
    async function afWithLock(fn) {
        if (navigator.locks && navigator.locks.request) {
            return navigator.locks.request(AF_LOCK_NAME, { mode: 'exclusive' }, fn);
        }
        await new Promise(r => setTimeout(r, Math.random() * 50));
        return fn();
    }
    async function afAcquire(runId) {
        return afWithLock(async () => {
            const map = afPrune(afReadMap());
            const wasEmpty = Object.keys(map).length === 0;
            let originalWasOn = null;
            if (wasEmpty) {
                const isOn = await fetchAutoFarmEnabled();
                originalWasOn = isOn;
                localStorage.setItem(AF_KEY_ORIGINAL, isOn === true ? '1' : '0');
                if (isOn === true) await setAutoFarmEnabled(false);
            } else {
                originalWasOn = localStorage.getItem(AF_KEY_ORIGINAL) === '1';
            }
            map[runId] = Date.now();
            afWriteMap(map);
            return { wasEmpty, originalWasOn };
        });
    }
    async function afHeartbeat(runId) {
        return afWithLock(async () => {
            const map = afPrune(afReadMap());
            if (map[runId]) { map[runId] = Date.now(); afWriteMap(map); }
        });
    }
    async function afRelease(runId) {
        return afWithLock(async () => {
            const map = afPrune(afReadMap());
            delete map[runId];
            afWriteMap(map);
            if (Object.keys(map).length === 0) {
                const orig = localStorage.getItem(AF_KEY_ORIGINAL) === '1';
                localStorage.removeItem(AF_KEY_ORIGINAL);
                if (orig) {
                    const ok = await setAutoFarmEnabled(true);
                    return { restored: true, ok };
                }
                return { restored: false, ok: true };
            }
            return { restored: false, ok: true };
        });
    }

    async function fetchAutoFarmEnabled() {
        try {
            const r = await fetch(`${BASE_URL}/auto_farm_status.php?_=${Date.now()}`, { credentials: 'same-origin', cache: 'no-store' });
            if (!r.ok) return null;
            const j = await r.json();
            if (!j || !j.ok || !j.settings) return null;
            return Number(j.settings.IS_ENABLED || 0) === 1;
        } catch { return null; }
    }
    async function setAutoFarmEnabled(enabled) {
        try {
            const body = new URLSearchParams({ action: 'toggle', enabled: enabled ? '1' : '0' });
            const r = await fetch(`${BASE_URL}/auto_farm_actions.php`, {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
                body: body.toString(),
            });
            return r.ok;
        } catch { return false; }
    }

    /* =========================================================
        STATE
    ========================================================= */
    let running        = false;
    let stopFlag       = false;
    let stopAfterMatch = false;
    let minimized  = localStorage.getItem(UI_MIN_KEY) === 'true';
    let matchCount = 0;
    let winCount   = 0;
    let lossCount  = 0;

    // Load persisted session
    try {
        const sess = JSON.parse(localStorage.getItem(SESSION_KEY) || 'null');
        if (sess) { matchCount = sess.m || 0; winCount = sess.w || 0; lossCount = sess.l || 0; }
    } catch (_) {}

    function saveSession() {
        try { localStorage.setItem(SESSION_KEY, JSON.stringify({ m: matchCount, w: winCount, l: lossCount })); } catch (_) {}
    }
    function resetSession() {
        matchCount = 0; winCount = 0; lossCount = 0;
        localStorage.removeItem(SESSION_KEY);
        renderGUI();
    }
    let currentMatchId = null;
    let logLimit   = Math.min(parseInt(localStorage.getItem(LOG_LIMIT_KEY), 10) || LOG_LIMIT, 2000);
    let persistLogsEnabled = localStorage.getItem(PERSIST_LOG_KEY) !== 'false'; // default true
    let his
