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
    let historyLimit = parseInt(localStorage.getItem(HISTORY_LIMIT_KEY), 10) || HISTORY_LIMIT;
    let autoFarmCoordinatorEnabled = localStorage.getItem(AF_COORD_ENABLED_KEY) !== 'false'; // default true

    // Solo Strategy state
    let currentTab     = (() => {
        const v = localStorage.getItem(ACTIVE_TAB_KEY);
        return (v === 'strategy' || v === 'settings') ? v : 'battle';
    })();
    let strategyData   = loadStrategy();      // { enabled, entries: [...] }
    let skillList      = [];                  // [{ id: string, label: string }] from pvp.php Solo PVP Attack Pattern
    let strategyRuntime = null;               // per-match runtime for executor

    // Match history - each entry: { ts, matchId, matchUrl, result, enemy: { name, uid, level, role, rank, points, party, avatarUrl }, pointsBefore, pointsAfter, pointsDelta }
    let matchHistory = [];

    // Per-match capture: populated when match starts, consumed when match ends
    let pendingMatchCapture = null;

    // Displayed stats (read from page DOM / refreshed after each match)
    let statRank   = '-';
    let statTier   = '-';
    let statPoints = '-';
    let statSouls  = '-';
    let statTokens = '-';

    // Current match player names (for log tokenization)
    let allyName  = null;   // e.g. "[EMP] lmv, The Emperor Who Ships"
    let enemyName = null;
    let allyUid   = null;
    let enemyUid  = null;

    // Win/loss detected from battle log messages (set during match polling)
    // 'win' | 'loss' | null
    let logDetectedResult = null;

    // HP bar state
    let hpBarActive   = false;  // true while a match is in progress
    let allyHp        = 0;
    let allyHpMax     = 0;
    let enemyHp       = 0;
    let enemyHpMax    = 0;
    let hpBarAllyName = '';
    let hpBarEnemyName = '';
    let hpBarAllyUid = '';
    let hpBarEnemyUid = '';
    let hpBarAllyAvatarUrl = '';
    let hpBarEnemyAvatarUrl = '';
    // Live battle stat cache — populated during an active match from formula
    // fields in each poll response; reset to null at the start of every match.
    // Android reads these via buildAndroidState() → battleStats.
    let liveStat = {
        myAtk: null, myDef: null, myDmg: null, myRet: null, myCls: null, myCrit: null,
        enAtk: null, enDef: null, enDmg: null, enRet: null, enCls: null, enCrit: null,
        turnBonus: null, globalPacing: null
    };

    // Log history - each entry: { plain, html, color }
    // plain = plain-text for clipboard, html = rich HTML for display, color = CSS color
    let logHistory = [];

    /* =========================================================
        HELPERS
    ========================================================= */
    function now() {
        return new Date().toLocaleTimeString('en-GB', { hour12: false, timeZone: 'Asia/Kolkata' });
    }

    /**
     * Unthrottled sleep using a Web Worker timer.
     * Browsers throttle setTimeout in background tabs to ≥1 s (or even longer).
     * A Web Worker's timers are NOT subject to the same throttling, so we
     * delegate all timing to a tiny inline worker.  The main-thread Promise
     * resolves when the worker posts back, keeping polls and delays accurate
     * even when the tab is hidden / in the background.
     */
    const _timerWorker = (() => {
        const blob = new Blob([`
            self.onmessage = function(e) {
                const id = e.data.id;
                const ms = e.data.ms;
                setTimeout(function() { self.postMessage({ id: id }); }, ms);
            };
        `], { type: 'application/javascript' });
        const w = new Worker(URL.createObjectURL(blob));
        const _pending = new Map();
        let _nextId = 0;
        w.onmessage = function(e) {
            const cb = _pending.get(e.data.id);
            if (cb) { _pending.delete(e.data.id); cb(); }
        };
        return {
            sleep(ms) {
                return new Promise(resolve => {
                    const id = _nextId++;
                    _pending.set(id, resolve);
                    w.postMessage({ id, ms });
                });
            }
        };
    })();

    function sleep(ms) {
        return _timerWorker.sleep(ms);
    }

    /**
     * Silent-audio keepalive.
     *
     * Browsers throttle the entire renderer process when the window is occluded
     * (e.g. another fullscreen/maximized window covers it), even if the tab
     * itself isn't "hidden". The Web Worker timer above bypasses background-tab
     * throttling but NOT window-occlusion throttling.
     *
     * Tabs/windows that are playing audio are exempt from these throttles.
     * We play a sine wave at a sub-audible amplitude (1e-4 gain) routed through
     * the WebAudio graph. Pure-silent (0 gain) is sometimes detected and
     * ignored, so we use a tiny non-zero value. At this amplitude it is
     * effectively inaudible on any normal output.
     *
     * Started when automation begins, stopped when it ends.
     */
    const _keepAlive = (() => {
        let ctx = null;
        let osc = null;
        let gain = null;
        let active = false;

        function start() {
            if (active) return;
            try {
                const AC = window.AudioContext || window.webkitAudioContext;
                if (!AC) return;
                if (!ctx) ctx = new AC();
                // Resume in case the context was created in a suspended state
                // (autoplay policy). Page interaction (the Start click) satisfies
                // the gesture requirement.
                if (ctx.state === 'suspended') ctx.resume().catch(() => {});

                gain = ctx.createGain();
                gain.gain.value = 0.0001; // ~ -80 dB, inaudible

                osc = ctx.createOscillator();
                osc.type = 'sine';
                osc.frequency.value = 20; // sub-audible-ish low freq
                osc.connect(gain).connect(ctx.destination);
                osc.start();
                active = true;
            } catch (e) {
                // Non-fatal: keepalive is best-effort.
                console.warn('[PvP Manager] keepalive start failed:', e);
            }
        }

        function stop() {
            if (!active) return;
            try {
                if (osc) { try { osc.stop(); } catch (_) {} osc.disconnect(); osc = null; }
                if (gain) { gain.disconnect(); gain = null; }
                // Keep ctx around for reuse; suspend to release the audio device.
                if (ctx && ctx.state === 'running') ctx.suspend().catch(() => {});
            } catch (e) {
                console.warn('[PvP Manager] keepalive stop failed:', e);
            }
            active = false;
        }

        return { start, stop };
    })();

    function escapeHtml(str) {
        return String(str ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function formatBannerText(text) {
        const nbsp = '\u00A0';
        return String(text ?? '')
            .toUpperCase()
            .split(' ')
            .map(word => word.split('').join(nbsp))
            .join(nbsp + nbsp);
    }

    function randomBattleIntroPair() {
        return BATTLE_INTRO_PAIRS[Math.floor(Math.random() * BATTLE_INTRO_PAIRS.length)];
    }

    /**
     * Replace ally/enemy full names in a plain-text log string with "You" / "Opponent".
     * Longest name is replaced first to avoid partial matches.
     */
    function tokenizePlain(text) {
        if (!allyName || !enemyName) return text;
        // Replace longer name first to avoid substring collisions
        const pairs = [[allyName, 'You'], [enemyName, 'Opponent']]
            .sort((a, b) => b[0].length - a[0].length);
        for (const [name, label] of pairs) {
            text = text.split(name).join(label);
        }
        return text;
    }

    /**
     * Replace ally/enemy full names in an already-escaped HTML log string
     * with styled, clickable labels linking to their profiles.
     */
    function tokenizeHtml(escaped) {
        if (!allyName || !enemyName) return escaped;
        const allyEsc  = escapeHtml(allyName);
        const enemyEsc = escapeHtml(enemyName);

        // First-mention tags: bold blue / red with profile link
        const allyPrimary  = `<a href="${PROFILE_URL}?pid=${encodeURIComponent(allyUid)}" target="_blank" `
                           + `title="${allyEsc}" style="color:#42a5f5; text-decoration:none; font-weight:bold;">You</a>`;
        const enemyPrimary = `<a href="${PROFILE_URL}?pid=${encodeURIComponent(enemyUid)}" target="_blank" `
                           + `title="${enemyEsc}" style="color:#ef5350; text-decoration:none; font-weight:bold;">Opponent</a>`;

        // Subsequent-mention tags: subtle muted gray, still links but blend in - #888
        const allyMuted  = `<a href="${PROFILE_URL}?pid=${encodeURIComponent(allyUid)}" target="_blank" `
                         + `title="${allyEsc}" style="color:#025c83; text-decoration:none;">You</a>`;
        const enemyMuted = `<a href="${PROFILE_URL}?pid=${encodeURIComponent(enemyUid)}" target="_blank" `
                         + `title="${enemyEsc}" style="color:#025c83; text-decoration:none;">Opponent</a>`;

        const firstAllyIndex  = escaped.indexOf(allyEsc);
        const firstEnemyIndex = escaped.indexOf(enemyEsc);
        const highlightAlly = firstAllyIndex !== -1
            && (firstEnemyIndex === -1 || firstAllyIndex < firstEnemyIndex);
        const highlightEnemy = firstEnemyIndex !== -1
            && (firstAllyIndex === -1 || firstEnemyIndex < firstAllyIndex);

        // Replace longer name first; only the earliest tokenized name in the line gets primary color
        const pairs = [
            [allyEsc, highlightAlly ? allyPrimary : allyMuted, allyMuted],
            [enemyEsc, highlightEnemy ? enemyPrimary : enemyMuted, enemyMuted]
        ].sort((a, b) => b[0].length - a[0].length);

        for (const [name, primary, muted] of pairs) {
            const parts = escaped.split(name);
            if (parts.length > 1) {
                escaped = parts[0] + primary + parts.slice(1).join(muted);
            }
        }
        return escaped;
    }

    // ── Fetch with AbortController timeout ────────────────────────────────────
    // ROOT CAUSE FIX (RC-1A): pvpPost / pvpGetJson / fetchPageHtml previously
    // called bare fetch() with no timeout.  On Android WebView a stalled or
    // dropped TCP connection will never resolve AND never reject, so the whole
    // poll loop hangs forever and the bot silently stops.
    //
    // pvpFetch wraps every outbound request with an AbortController that fires
    // after FETCH_TIMEOUT_MS.  The resulting AbortError propagates normally —
    // each caller's existing catch block logs it and the cycle/poll loop
    // continues exactly as it does today for any other network error.
    const FETCH_TIMEOUT_MS = 28_000; // 28 s — generous but finite

    function pvpFetch(url, opts) {
        const ctrl = new AbortController();
        const tid  = setTimeout(() => ctrl.abort(), FETCH_TIMEOUT_MS);
        return fetch(url, Object.assign({}, opts, { signal: ctrl.signal }))
            .finally(() => clearTimeout(tid));
    }

    async function pvpPost(url, body) {
        const res = await pvpFetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: new URLSearchParams(body)
        });
        return res.json();
    }

    async function pvpGetJson(url) {
        const res = await pvpFetch(url, {
            credentials: 'same-origin',
            headers: { 'X-Requested-With': 'XMLHttpRequest' }
        });
        return res.json();
    }

    async function fetchPageHtml(url) {
        const res = await pvpFetch(url, { credentials: 'same-origin' });
        return res.text();
    }

    /* =========================================================
        STATS - read from pvp.php DOM
    ========================================================= */

    /**
     * Searches .banner-pill and .info-pill elements for a label like "Solo Rank:"
     * and returns the text of the sibling <span>.
     */
    function parsePillValue(doc, labelPrefix) {
        const pills = doc.querySelectorAll('.banner-pill, .info-pill');
        for (const pill of pills) {
            const strong = pill.querySelector('strong');
            if (strong && strong.textContent.trim().startsWith(labelPrefix)) {
                const span = pill.querySelector('span');
                return span ? span.textContent.trim() : '-';
            }
        }
        return '-';
    }

    function readStatsFromDoc(doc) {
        statRank   = parsePillValue(doc, 'Solo Rank');
        statTier   = parsePillValue(doc, 'Solo Tier');
        statPoints = parsePillValue(doc, 'Solo Points');
        statSouls  = parsePillValue(doc, 'Player Souls');
        statTokens = parsePillValue(doc, 'Tokens');
    }

    async function refreshStats() {
        try {
            const html = await fetchPageHtml(PVP_URL);
            const doc  = new DOMParser().parseFromString(html, 'text/html');
            readStatsFromDoc(doc);
        } catch (e) {
            console.warn('[PvP Manager] Could not refresh stats:', e);
        }
    }

    /* =========================================================
        LOG BOX  (persistent DOM node - never recreated)
    ========================================================= */
    const logBox = document.createElement('div');
    logBox.style.cssText = `
        margin-top: 10px;
        padding: 8px;
        background: rgba(15,15,15,0.97);
        border-radius: 1em;
        height: 310px;
        overflow-y: auto;
        font-size: 11px;
        color: #ddd;
        border: 1px solid #444;
        line-height: 1.5;
    `;

    /* =========================================================
        LOG AUTO-SCROLL - flag-based, survives content reflows
    ========================================================= */
    /**
     * _logPinned: when true, every new log / content change pins the log
     * box to the bottom.  It starts true and only flips to false when the
     * *user* scrolls upward (detected by the 'scroll' event listener
     * below).  It flips back to true when the user scrolls near the
     * bottom again.  This avoids the old bug where content-height changes
     * (typewriter reflow, renderGUI detach/reattach, HP bar flash, log
     * trimming) would make a point-in-time "am I near the bottom?" check
     * return false and permanently lose auto-scroll.
     */
    let _logPinned = true;
    /** Guard so we can distinguish our own programmatic scrolls from user scrolls. */
    let _programmaticScroll = false;

    logBox.addEventListener('scroll', () => {
        if (_programmaticScroll) return;          // ignore our own scrollTop writes
        const gap = logBox.scrollHeight - logBox.scrollTop - logBox.clientHeight;
        _logPinned = gap <= 40;
    }, { passive: true });

    /** Returns true if the log box is scrolled to (or near) the bottom. */
    function isLogNearBottom() {
        return _logPinned;
    }

    /** Scroll log box to the bottom if pinned. */
    function smartScrollLog() {
        if (_logPinned) {
            _programmaticScroll = true;
            logBox.scrollTop = logBox.scrollHeight;
            _programmaticScroll = false;
        }
    }

    /* =========================================================
        HP BARS  (persistent DOM node - never recreated)
    ========================================================= */
    const hpBarContainer = document.createElement('div');
    hpBarContainer.style.cssText = `
        margin-top: 10px;
        transition: opacity 0.4s ease;
    `;
    hpBarContainer.innerHTML = `
        <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:3px; font-size:10px; font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;">
            <div style="display:flex; align-items:center; gap:5px; max-width:45%; overflow:hidden;">
                <a id="pvpm-hp-ally-avatar-link" href="#" target="_blank" style="text-decoration:none; display:none; flex-shrink:0;">
                    <img id="pvpm-hp-ally-avatar" src="" alt="" style="width:20px; height:20px; border-radius:50%; object-fit:cover; border:1px solid #555; vertical-align:middle;">
                </a>
                <div id="pvpm-hp-ally-avatar-placeholder" style="width:20px; height:20px; border-radius:50%; background:#222; border:1px solid #333; flex-shrink:0;"></div>
                <span id="pvpm-hp-ally-name" style="color:#555; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;"></span>
            </div>
            <span style="color:#333; font-size:9px;">HP</span>
            <div style="display:flex; align-items:center; gap:5px; max-width:45%; overflow:hidden; justify-content:flex-end;">
                <span id="pvpm-hp-enemy-name" style="color:#555; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; text-align:right;"></span>
                <a id="pvpm-hp-enemy-avatar-link" href="#" target="_blank" style="text-decoration:none; display:none; flex-shrink:0;">
                    <img id="pvpm-hp-enemy-avatar" src="" alt="" style="width:20px; height:20px; border-radius:50%; object-fit:cover; border:1px solid #555; vertical-align:middle;">
                </a>
                <div id="pvpm-hp-enemy-avatar-placeholder" style="width:20px; height:20px; border-radius:50%; background:#222; border:1px solid #333; flex-shrink:0;"></div>
            </div>
        </div>
        <div id="pvpm-hp-bar-track" style="display:flex; height:14px; border-radius:7px; overflow:hidden; background:#1a1a1a; border:1px solid #333; position:relative;">
            <!-- Ally bar: grows from center toward left -->
            <div style="flex:1; display:flex; justify-content:flex-end; position:relative;">
                <div id="pvpm-hp-ally-bar" style="
                    width: 0%;
                    height: 100%;
                    background: #2a2a2a;
                    border-radius: 7px 0 0 7px;
                    transition: width 0.6s ease, background 0.6s ease, box-shadow 0.6s ease;
                "></div>
            </div>
            <!-- Center divider -->
            <div style="width:2px; background:#333; flex-shrink:0; z-index:1;"></div>
            <!-- Enemy bar: grows from center toward right -->
            <div style="flex:1; display:flex; justify-content:flex-start; position:relative;">
                <div id="pvpm-hp-enemy-bar" style="
                    width: 0%;
                    height: 100%;
                    background: #2a2a2a;
                    border-radius: 0 7px 7px 0;
                    transition: width 0.6s ease, background 0.6s ease, box-shadow 0.6s ease;
                "></div>
            </div>
        </div>
        <div style="display:flex; justify-content:space-between; margin-top:2px; font-size:9px; font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;">
            <span id="pvpm-hp-ally-text" style="color:#555;"></span>
            <span id="pvpm-hp-enemy-text" style="color:#555;"></span>
        </div>
    `;

    /** Activate the HP bar visuals (colorize from greyed-out state) */
    function showHpBars() {
        // Bar track is always visible; just ensure container is shown
        hpBarContainer.style.opacity = '1';
    }

    /** Transition HP bars to greyed-out inactive state (no collapse) */
    function greyOutHpBars() {
        const allyBar   = document.getElementById('pvpm-hp-ally-bar');
        const enemyBar  = document.getElementById('pvpm-hp-enemy-bar');
        const allyText  = document.getElementById('pvpm-hp-ally-text');
        const enemyText = document.getElementById('pvpm-hp-enemy-text');
        const allyNameEl  = document.getElementById('pvpm-hp-ally-name');
        const enemyNameEl = document.getElementById('pvpm-hp-enemy-name');

        if (allyBar) {
            allyBar.style.width = '0%';
            allyBar.style.background = '#2a2a2a';
            allyBar.style.boxShadow = 'none';
        }
        if (enemyBar) {
            enemyBar.style.width = '0%';
            enemyBar.style.background = '#2a2a2a';
            enemyBar.style.boxShadow = 'none';
        }
        if (allyText)  { allyText.textContent  = ''; allyText.style.color  = '#555'; }
        if (enemyText) { enemyText.textContent = ''; enemyText.style.color = '#555'; }
        if (allyNameEl)  { allyNameEl.textContent  = ''; allyNameEl.style.color  = '#555'; }
        if (enemyNameEl) { enemyNameEl.textContent = ''; enemyNameEl.style.color = '#555'; }

        // Hide avatars, show placeholders
        const allyAvatarLink = document.getElementById('pvpm-hp-ally-avatar-link');
        const enemyAvatarLink = document.getElementById('pvpm-hp-enemy-avatar-link');
        const allyPlaceholder = document.getElementById('pvpm-hp-ally-avatar-placeholder');
        const enemyPlaceholder = document.getElementById('pvpm-hp-enemy-avatar-placeholder');
        if (allyAvatarLink)    allyAvatarLink.style.display = 'none';
        if (enemyAvatarLink)   enemyAvatarLink.style.display = 'none';
        if (allyPlaceholder)   allyPlaceholder.style.display = '';
        if (enemyPlaceholder)  enemyPlaceholder.style.display = '';
    }

    /** Flash the HP bar track, then call a callback */
    function flashHpBars(callback) {
        const track = document.getElementById('pvpm-hp-bar-track');
        if (!track) { if (callback) callback(); return; }
        track.style.animation = 'pvpm-hp-flash 0.8s ease';
        track.addEventListener('animationend', function handler() {
            track.removeEventListener('animationend', handler);
            track.style.animation = '';
            if (callback) callback();
        });
    }

    /** Set up avatar elements for a match */
    function setHpBarAvatars(allyUidVal, enemyUidVal, allyAvatarUrl, enemyAvatarUrl) {
        hpBarAllyUid = allyUidVal || '';
        hpBarEnemyUid = enemyUidVal || '';
        hpBarAllyAvatarUrl = allyAvatarUrl || '';
        hpBarEnemyAvatarUrl = enemyAvatarUrl || '';

        const allyLink = document.getElementById('pvpm-hp-ally-avatar-link');
        const allyImg  = document.getElementById('pvpm-hp-ally-avatar');
        const allyPlaceholder = document.getElementById('pvpm-hp-ally-avatar-placeholder');
        const enemyLink = document.getElementById('pvpm-hp-enemy-avatar-link');
        const enemyImg  = document.getElementById('pvpm-hp-enemy-avatar');
        const enemyPlaceholder = document.getElementById('pvpm-hp-enemy-avatar-placeholder');

        if (allyAvatarUrl && allyLink && allyImg) {
            allyLink.href = `${PROFILE_URL}?pid=${encodeURIComponent(allyUidVal)}`;
            allyImg.src = allyAvatarUrl;
            allyLink.style.display = '';
            if (allyPlaceholder) allyPlaceholder.style.display = 'none';
        } else if (allyUidVal && allyLink) {
            // No avatar but we have a UID - show placeholder as a link
            if (allyPlaceholder) {
                allyPlaceholder.style.display = '';
                allyPlaceholder.style.cursor = 'pointer';
                allyPlaceholder.onclick = () => window.open(`${PROFILE_URL}?pid=${encodeURIComponent(allyUidVal)}`, '_blank');
            }
            allyLink.style.display = 'none';
        }

        if (enemyAvatarUrl && enemyLink && enemyImg) {
            enemyLink.href = `${PROFILE_URL}?pid=${encodeURIComponent(enemyUidVal)}`;
            enemyImg.src = enemyAvatarUrl;
            enemyLink.style.display = '';
            if (enemyPlaceholder) enemyPlaceholder.style.display = 'none';
        } else if (enemyUidVal && enemyLink) {
            if (enemyPlaceholder) {
                enemyPlaceholder.style.display = '';
                enemyPlaceholder.style.cursor = 'pointer';
                enemyPlaceholder.onclick = () => window.open(`${PROFILE_URL}?pid=${encodeURIComponent(enemyUidVal)}`, '_blank');
            }
            enemyLink.style.display = 'none';
        }
    }

    /** Start HP bars for a new match: flash, then show at 0%, then animate to current HP */
    function initHpBars(state) {
        const ally  = state.teams?.ally?.players_by_num?.['1'];
        const enemy = state.teams?.enemy?.players_by_num?.['1'];
        if (!ally || !enemy) return;

        allyHpMax  = ally.hp_max  || 1;
        allyHp     = ally.hp      ?? allyHpMax;
        enemyHpMax = enemy.hp_max || 1;
        enemyHp    = enemy.hp     ?? enemyHpMax;

        // Short display names (strip guild tags for brevity)
        hpBarAllyName  = (ally.username  || 'You').replace(/^\[.*?\]\s*/, '');
        hpBarEnemyName = (enemy.username || 'Opponent').replace(/^\[.*?\]\s*/, '');

        hpBarActive = true;

        // Set names with active colors
        const allyNameEl  = document.getElementById('pvpm-hp-ally-name');
        const enemyNameEl = document.getElementById('pvpm-hp-enemy-name');
        if (allyNameEl)  { allyNameEl.textContent = hpBarAllyName;  allyNameEl.style.color = '#81c784'; }
        if (enemyNameEl) { enemyNameEl.textContent = hpBarEnemyName; enemyNameEl.style.color = '#e57373'; }

        // Set text colors to active
        const allyText  = document.getElementById('pvpm-hp-ally-text');
        const enemyText = document.getElementById('pvpm-hp-enemy-text');
        if (allyText)  allyText.style.color  = '#81c784';
        if (enemyText) enemyText.style.color = '#e57373';

        // Start at 0%
        updateHpBarDom(0, 0);
        showHpBars();

        // Flash to signal match start, then fill bars
        flashHpBars(() => {
            setTimeout(() => {
                updateHpBarDom(allyHp / allyHpMax, enemyHp / enemyHpMax);
            }, 80);
        });
    }

    /** Update HP bars from a poll/state response */
    function updateHpFromState(state) {
        if (!hpBarActive) return;
        const ally  = state.teams?.ally?.players_by_num?.['1'];
        const enemy = state.teams?.enemy?.players_by_num?.['1'];

        if (ally) {
            allyHp    = ally.hp ?? allyHp;
            allyHpMax = ally.hp_max || allyHpMax;
        }
        if (enemy) {
            enemyHp    = enemy.hp ?? enemyHp;
            enemyHpMax = enemy.hp_max || enemyHpMax;
        }

        const allyPct  = Math.max(0, Math.min(1, allyHp  / (allyHpMax  || 1)));
        const enemyPct = Math.max(0, Math.min(1, enemyHp / (enemyHpMax || 1)));
        updateHpBarDom(allyPct, enemyPct);
    }

    /** Low-level DOM update for the HP bar widths and text */
    function updateHpBarDom(allyPct, enemyPct) {
        const allyBar  = document.getElementById('pvpm-hp-ally-bar');
        const enemyBar = document.getElementById('pvpm-hp-enemy-bar');
        const allyText  = document.getElementById('pvpm-hp-ally-text');
        const enemyText = document.getElementById('pvpm-hp-enemy-text');

        if (allyBar)   allyBar.style.width  = (allyPct  * 100).toFixed(1) + '%';
        if (enemyBar)  enemyBar.style.width  = (enemyPct * 100).toFixed(1) + '%';

        // Color shifts: green → yellow → red as HP drops
        if (allyBar) {
            if (allyPct > 0.5)       { allyBar.style.background = 'linear-gradient(90deg, #388e3c, #66bb6a)'; allyBar.style.boxShadow = 'inset 0 1px 0 rgba(255,255,255,0.15), 0 0 6px rgba(102,187,106,0.3)'; }
            else if (allyPct > 0.25) { allyBar.style.background = 'linear-gradient(90deg, #f9a825, #fdd835)'; allyBar.style.boxShadow = 'inset 0 1px 0 rgba(255,255,255,0.15), 0 0 6px rgba(253,216,53,0.3)'; }
            else                     { allyBar.style.background = 'linear-gradient(90deg, #c62828, #ef5350)'; allyBar.style.boxShadow = 'inset 0 1px 0 rgba(255,255,255,0.15), 0 0 6px rgba(239,83,80,0.3)'; }
        }
        // Enemy stays red-ish but shifts toward darker as they drop
        if (enemyBar) {
            if (enemyPct > 0.5)       { enemyBar.style.background = 'linear-gradient(90deg, #ef5350, #c62828)'; enemyBar.style.boxShadow = 'inset 0 1px 0 rgba(255,255,255,0.15), 0 0 6px rgba(239,83,80,0.3)'; }
            else if (enemyPct > 0.25) { enemyBar.style.background = 'linear-gradient(90deg, #e65100, #ff6d00)'; enemyBar.style.boxShadow = 'inset 0 1px 0 rgba(255,255,255,0.15), 0 0 6px rgba(255,109,0,0.3)'; }
            else                      { enemyBar.style.background = 'linear-gradient(90deg, #b71c1c, #d32f2f)'; enemyBar.style.boxShadow = 'inset 0 1px 0 rgba(255,255,255,0.15), 0 0 6px rgba(211,47,47,0.3)'; }
        }

        if (allyText)  allyText.textContent  = allyHpMax  ? `${Math.max(0, allyHp).toLocaleString()} / ${allyHpMax.toLocaleString()}` : '';
        if (enemyText) enemyText.textContent = enemyHpMax ? `${Math.max(0, enemyHp).toLocaleString()} / ${enemyHpMax.toLocaleString()}` : '';
    }

    /** Reset HP bars when a match ends - flash, then animate to greyed-out */
    function resetHpBars() {
        hpBarActive = false;
        allyHp = allyHpMax = enemyHp = enemyHpMax = 0;

        // Flash to signal match end, then grey out
        flashHpBars(() => {
            greyOutHpBars();
        });
    }

    /**
     * Add a script-generated log line (status messages, errors, etc.)
     * @param {string} plainText  - plain-text version stored in history
     * @param {object} [opts]
     * @param {string} [opts.html]   - rich HTML to render instead of plainText
     * @param {string} [opts.color]  - CSS color for the line
     */
    function addLog(plainText, { html = null, color = null, noTimestamp = false } = {}) {
        const t = now();
        const display = html !== null ? html : escapeHtml(plainText);
        const entry = noTimestamp
            ? { plain: plainText, html: display, color: color || null }
            : { plain: `[${t}] ${plainText}`, html: `<span style="color:#555">[${t}]</span> ${display}`, color: color || null };
        logHistory.push(entry);
        if (logHistory.length > logLimit) {
            logHistory.shift();
            if (logBox.firstChild) logBox.removeChild(logBox.firstChild);
        }

        logBox.appendChild(renderLogDiv(entry));
        smartScrollLog();

        persistLogs();
    }

    /** Create a DOM div from a log entry object. */
    function renderLogDiv(entry) {
        const div = document.createElement('div');
        div.style.cssText = 'border-bottom:1px solid #222; padding:2px 0;';
        if (entry.color) div.style.color = entry.color;
        div.innerHTML = entry.html;
        return div;
    }

    /**
     * Add a log entry with a typewriter reveal animation.
     * The plain text is stored immediately in logHistory for persistence,
     * but the DOM element reveals characters incrementally over `durationMs`.
     * Returns a Promise that resolves when the animation finishes.
     */
    function addLogTypewriter(plainText, { html = null, color = null, noTimestamp = false } = {}, durationMs = 400) {
        // Build the full entry (same as addLog) and push to history immediately
        const t = now();
        const display = html !== null ? html : escapeHtml(plainText);
        const entry = noTimestamp
            ? { plain: plainText, html: display, color: color || null }
            : { plain: `[${t}] ${plainText}`, html: `<span style="color:#555">[${t}]</span> ${display}`, color: color || null };
        logHistory.push(entry);
        if (logHistory.length > logLimit) {
            logHistory.shift();
            if (logBox.firstChild) logBox.removeChild(logBox.firstChild);
        }
        persistLogs();

        // Create the DOM div but start with hidden content
        const div = document.createElement('div');
        div.style.cssText = 'border-bottom:1px solid #222; padding:2px 0;';
        if (entry.color) div.style.color = entry.color;

        // We use a wrapper span whose content we'll reveal character by character.
        // To animate the *visible text* while preserving HTML tags, we use a
        // clip-path approach: render the full HTML but reveal via max-width on
        // an inline-block wrapper.
        const wrapper = document.createElement('span');
        wrapper.style.cssText = 'display:inline-block; overflow:hidden; white-space:nowrap; max-width:0; vertical-align:bottom; transition:none;';
        wrapper.innerHTML = entry.html;
        div.appendChild(wrapper);
        logBox.appendChild(div);
        smartScrollLog();

        return new Promise(resolve => {
            // Measure the natural width of the fully rendered content
            wrapper.style.maxWidth = 'none';
            wrapper.style.whiteSpace = 'nowrap';
            const fullWidth = wrapper.scrollWidth;
            wrapper.style.maxWidth = '0px';

            // Snapshot whether we should auto-scroll BEFORE the animation starts.
            // _logPinned is authoritative; we read it once and use it throughout.
            const _pinToBottom = _logPinned;

            // Animate using requestAnimationFrame when visible, falling back
            // to setTimeout (via the unthrottled worker) when the tab is hidden.
            // This ensures the typewriter completes promptly in background tabs
            // instead of freezing until the user returns.
            const startTime = performance.now();
            function scheduleNext(fn) {
                if (document.hidden) {
                    // Background: use worker-based timer (~16ms frame interval)
                    _timerWorker.sleep(16).then(() => fn(performance.now()));
                } else {
                    requestAnimationFrame(fn);
                }
            }
            function tick(now) {
                const elapsed = now - startTime;
                const progress = Math.min(elapsed / durationMs, 1);
                wrapper.style.maxWidth = (fullWidth * progress) + 'px';
                if (_pinToBottom) {
                    _programmaticScroll = true;
                    logBox.scrollTop = logBox.scrollHeight;
                    _programmaticScroll = false;
                }
                if (progress < 1) {
                    scheduleNext(tick);
                } else {
                    // Animation done - remove inline constraints so text wraps normally
                    wrapper.style.maxWidth = 'none';
                    wrapper.style.whiteSpace = '';
                    wrapper.style.overflow = '';
                    wrapper.style.display = '';
                    // Force scroll after reflow - the style changes above can
                    // increase scrollHeight, so we must scroll unconditionally
                    // based on our pre-animation snapshot.
                    if (_pinToBottom) {
                        _programmaticScroll = true;
                        logBox.scrollTop = logBox.scrollHeight;
                        _programmaticScroll = false;
                    }
                    scheduleNext(() => {
                        if (_pinToBottom) {
                            _programmaticScroll = true;
                            logBox.scrollTop = logBox.scrollHeight;
                            _programmaticScroll = false;
                        }
                    });
                    resolve();
                }
            }
            // Kick off on next frame so the browser registers the max-width:0 state
            scheduleNext(tick);
        });
    }

    /**
     * Append a battle log entry returned by the server.
     * These are appended directly without a full GUI re-render.
     */
    function appendBattleLog(entry) {
        const content  = entry.content  || '';
        // Suppress noisy filler lines
        if (content.includes('runs out of time and lets the battle AI act')) return;
        if (content.includes('takes the turn')) return;

        // Detect win/loss/tie from room-result messages
        if (content.includes('wins the room')) {
            if (content.includes('allied')) {
                logDetectedResult = 'win';
            } else if (content.includes('enemy')) {
                logDetectedResult = 'loss';
            }
        }
        // Tie detection - "Both sides collapse" means defender wins; counts as loss
        if (content.includes('Both sides collapse')) {
            logDetectedResult = 'tie';
        }

        const datetime = entry.datetime || '';
        // datetime format: "2026-04-03 07:01:04" - extract time part
        const timeStr  = datetime.length >= 19 ? datetime.slice(11, 19) : now();

        const richHtml = `<span style="color:#444">[${timeStr}]</span> ${tokenizeHtml(escapeHtml(content))}`;
        const logEntry = {
            plain: `[${timeStr}] ${tokenizePlain(content)}`,
            html:  richHtml,
            color: '#bbb'
        };
        logHistory.push(logEntry);
        if (logHistory.length > logLimit) {
            logHistory.shift();
            if (logBox.firstChild) logBox.removeChild(logBox.firstChild);
        }

        const div = document.createElement('div');
        div.style.cssText = 'border-bottom:1px solid #1a1a1a; padding:2px 0; color:#bbb;';
        if (entry.id) div.dataset.logId = entry.id;
        div.innerHTML = richHtml;
        logBox.appendChild(div);
        // Caller scrolls after processing all new entries

        persistLogs();
    }

    function clearLogs() {
        logHistory = [];
        _programmaticScroll = true;
        logBox.innerHTML = '';
        _programmaticScroll = false;
        _logPinned = true;   // after clearing, pin to bottom
        persistLogs();
    }

    /* -- Log persistence helpers -- */
    function persistLogs() {
        if (!persistLogsEnabled) return;
        try {
            localStorage.setItem(LOG_STORE_KEY, JSON.stringify(logHistory));
        } catch (_) {}
    }

    function loadPersistedLogs() {
        if (!persistLogsEnabled) return;
        try {
            const raw = localStorage.getItem(LOG_STORE_KEY);
            if (!raw) return;
            const entries = JSON.parse(raw);
            if (!Array.isArray(entries) || entries.length === 0) return;

            // Trim to current limit (keep newest)
            const trimmed = entries.length > logLimit ? entries.slice(-logLimit) : entries;
            logHistory = trimmed;

            for (const entry of trimmed) {
                // Support legacy plain-string entries from older versions
                if (typeof entry === 'string') {
                    const div = document.createElement('div');
                    div.style.cssText = 'border-bottom:1px solid #222; padding:2px 0; color:#bbb;';
                    div.innerHTML = escapeHtml(entry);
                    logBox.appendChild(div);
                } else {
                    logBox.appendChild(renderLogDiv(entry));
                }
            }
            _programmaticScroll = true;
            logBox.scrollTop = logBox.scrollHeight;
            _programmaticScroll = false;
            if (entries.length > logLimit) persistLogs();
        } catch (_) {}
    }

    /* -- Match history persistence helpers -- */
    function persistHistory() {
        try {
            localStorage.setItem(HISTORY_STORE_KEY, JSON.stringify(matchHistory));
        } catch (_) {}
    }

    function loadPersistedHistory() {
        try {
            const raw = localStorage.getItem(HISTORY_STORE_KEY);
            if (!raw) return;
            const entries = JSON.parse(raw);
            if (!Array.isArray(entries)) return;
            matchHistory = entries.length > historyLimit ? entries.slice(-historyLimit) : entries;
            if (entries.length > historyLimit) persistHistory();
        } catch (_) {}
    }

    function addHistoryEntry(entry) {
        // Compute pointsDelta so Android UI can display +/- correctly
        if (entry.pointsBefore != null && entry.pointsAfter != null) {
            entry.pointsDelta = entry.pointsAfter - entry.pointsBefore;
        } else {
            entry.pointsDelta = 0;
        }
        matchHistory.push(entry);
        if (matchHistory.length > historyLimit) matchHistory.shift();
        persistHistory();
    }

    /* -- Match history modal -- */
    let historyModalEl = null;

    function openHistoryModal() {
        if (historyModalEl) { historyModalEl.remove(); historyModalEl = null; }

        const overlay = document.createElement('div');
        overlay.style.cssText = `
            position:fixed; inset:0; background:rgba(0,0,0,0.7); z-index:20000;
            display:flex; align-items:center; justify-content:center;
        `;
        overlay.addEventListener('click', e => { if (e.target === overlay) closeHistoryModal(); });

        const modal = document.createElement('div');
        modal.style.cssText = `
            background:#1c1c1c; border:1px solid #444; border-radius:1.2em; padding:18px;
            width:720px; max-width:92vw; max-height:80vh; display:flex; flex-direction:column;
            font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; font-size:12px; color:#ddd; box-shadow:0 0 30px rgba(0,0,0,0.8);
        `;

        // Header
        const header = document.createElement('div');
        header.style.cssText = 'display:flex; align-items:center; justify-content:space-between; margin-bottom:12px; padding-bottom:8px; border-bottom:1px solid #333;';
        header.innerHTML = `
            <span style="font-size:14px; font-weight:bold; color:#2196f3;">📊 Match History <span style="color:#666; font-weight:normal; font-size:11px;">(${matchHistory.length} match${matchHistory.length !== 1 ? 'es' : ''})</span></span>
            <div style="display:flex; gap:12px; align-items:center;">
                <span id="pvpm-hist-clear" style="cursor:pointer; font-size:11px; color:#777; text-decoration:underline;">Clear History</span>
                <span id="pvpm-hist-close" style="cursor:pointer; font-size:18px; color:#666; font-weight:bold; line-height:1;">×</span>
            </div>
        `;
        modal.appendChild(header);

        // List container
        const listContainer = document.createElement('div');
        listContainer.style.cssText = 'overflow-y:auto; flex:1; min-height:0;';

        if (matchHistory.length === 0) {
            listContainer.innerHTML = '<div style="text-align:center; color:#555; padding:40px 0;">No matches recorded yet.</div>';
        } else {
            // Build list efficiently: create a document fragment, iterate newest-first
            const frag = document.createDocumentFragment();
            for (let i = matchHistory.length - 1; i >= 0; i--) {
                frag.appendChild(renderHistoryRow(matchHistory[i], i));
            }
            listContainer.appendChild(frag);
        }

        modal.appendChild(listContainer);
        overlay.appendChild(modal);
        document.body.appendChild(overlay);
        historyModalEl = overlay;

        // Handlers
        modal.querySelector('#pvpm-hist-close').onclick = closeHistoryModal;
        modal.querySelector('#pvpm-hist-clear').onclick = () => {
            if (!confirm('Clear all match history?')) return;
            matchHistory = [];
            persistHistory();
            closeHistoryModal();
        };

        // ESC to close
        overlay._keyHandler = e => { if (e.key === 'Escape') { closeHistoryModal(); e.preventDefault(); } };
        document.addEventListener('keydown', overlay._keyHandler);
    }

    function closeHistoryModal() {
        if (!historyModalEl) return;
        if (historyModalEl._keyHandler) document.removeEventListener('keydown', historyModalEl._keyHandler);
        historyModalEl.remove();
        historyModalEl = null;
    }

    function renderHistoryRow(entry, _idx) {
        const row = document.createElement('div');
        const isWin = entry.result === 'win';
        const isTie = entry.result === 'tie';
        const isLoss = entry.result === 'loss' || isTie;
        const resultColor = isWin ? '#66bb6a' : isLoss ? '#ef5350' : '#888';
        const resultLabel = isWin ? 'W' : isLoss ? 'L' : '?';
        const borderColor = isWin ? '#2e7d32' : isLoss ? '#b71c1c' : '#444';

        row.style.cssText = `
            display:flex; align-items:center; gap:10px; padding:8px 10px;
            border-bottom:1px solid #222; border-left:3px solid ${borderColor};
        `;
        row.addEventListener('mouseenter', () => { row.style.background = 'rgba(255,255,255,0.03)'; });
        row.addEventListener('mouseleave', () => { row.style.background = ''; });

        const e = entry.enemy || {};
        const profileHref = e.uid ? `${PROFILE_URL}?pid=${encodeURIComponent(e.uid)}` : '#';

        // Timestamp
        const ts = entry.ts ? new Date(entry.ts).toLocaleString('en-GB', { day:'2-digit', month:'short', hour:'2-digit', minute:'2-digit', hour12:false, timeZone:'Asia/Kolkata' }) : '-';

        // Points delta display
        let pointsStr = '';
        if (entry.pointsBefore != null && entry.pointsAfter != null) {
            const delta = entry.pointsAfter - entry.pointsBefore;
            const sign = delta >= 0 ? '+' : '';
            const deltaColor = delta >= 0 ? '#66bb6a' : '#ef5350';
            pointsStr = `<span style="color:#888;">${escapeHtml(String(entry.pointsBefore))}</span>`
                       + `<span style="color:#666;"> → </span>`
                       + `<span style="color:#ccc;">${escapeHtml(String(entry.pointsAfter))}</span>`
                       + ` <span style="color:${deltaColor}; font-weight:bold;">(${sign}${delta})</span>`;
        }

        // Enemy info line
        const avatarPart = e.avatarUrl
            ? `<a href="${profileHref}" target="_blank" style="text-decoration:none;"><img src="${escapeHtml(e.avatarUrl)}" alt="" style="width:24px; height:24px; border-radius:50%; object-fit:cover; border:1px solid #555; flex-shrink:0;"></a>`
            : `<a href="${profileHref}" target="_blank" style="text-decoration:none;"><div style="width:24px; height:24px; border-radius:50%; background:#333; border:1px solid #555; flex-shrink:0;"></div></a>`;
        const namePart = `<a href="${profileHref}" target="_blank" style="color:#90caf9; text-decoration:none; font-weight:bold;">${escapeHtml(e.name || 'Unknown')}</a>`;
        const tieTag = isTie ? ' <span style="color:#ef9a9a; font-size:10px; font-weight:bold;">(TIE)</span>' : '';
        const lvParts = [];
        if (e.level) lvParts.push(`Lv ${escapeHtml(e.level)}`);
        if (e.role)  lvParts.push(escapeHtml(e.role));
        const lvPart = lvParts.length ? ` <span style="color:#888;">(${lvParts.join(' ')})</span>` : '';

        const detailParts = [];
        if (e.rank)   detailParts.push(`<span style="color:#b0bec5;">${escapeHtml(e.rank)}</span>`);
        if (e.points != null) detailParts.push(`<span style="color:#b0bec5;">${escapeHtml(String(e.points))} pts</span>`);
        if (e.party)  detailParts.push(`<span style="color:#ce93d8;">Party: ${escapeHtml(e.party)}</span>`);
        const detailPart = detailParts.length
            ? ` <span style="color:#444;">·</span> ` + detailParts.join(` <span style="color:#444;">·</span> `)
            : '';

        // Match link
        const matchLink = entry.matchUrl
            ? `<a href="${escapeHtml(entry.matchUrl)}" target="_blank" style="color:#81d4fa; text-decoration:none; font-size:10px;" title="View match">#${escapeHtml(String(entry.matchId || ''))}</a>`
            : '';

        row.innerHTML = `
            <div style="flex-shrink:0; width:22px; text-align:center; font-weight:bold; font-size:13px; color:${resultColor};">${resultLabel}</div>
            ${avatarPart}
            <div style="flex:1; min-width:0; overflow:hidden;">
                <div style="display:flex; align-items:center; gap:6px; flex-wrap:wrap;">
                    ${namePart}${tieTag}${lvPart}${detailPart}
                </div>
                <div style="display:flex; align-items:center; gap:8px; margin-top:3px; font-size:11px;">
                    <span style="color:#555;">${ts}</span>
                    ${matchLink}
                    ${pointsStr ? `<span style="color:#444;">·</span> ${pointsStr}` : ''}
                </div>
            </div>
        `;
        return row;
    }

    /* =========================================================
        GUI
    ========================================================= */
    const EXPANDED_W  = 580;
    const COLLAPSED_W = 90;

    const STATUS_COLORS = {
        IDLE:    '#f44336',		//9e9e9e
        RUNNING: '#4caf50',
        BATTLE:  '#2196f3',
    };

    let currentStatus = 'IDLE';
    let _initialRender = true;

    const gui = document.createElement('div');
    gui.classList.add('pvpm-root');
    gui.style.cssText = `
        position: fixed;
        top: 0; left: 0;
        width: min(${EXPANDED_W}px, calc(100vw - 16px));
        min-width: 300px;
        max-height: calc(100vh - 24px);
        background: rgba(28,28,28,0.97);
        color: #fff;
        font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        font-size: 13px;
        padding: 0;
        border-radius: 1.5em;
        box-shadow: 0 0 18px rgba(0,0,0,0.75);
        z-index: 10001;
        box-sizing: border-box;
        display: flex;
        flex-direction: column;
        overflow: hidden;
    `;
    document.body.appendChild(gui);

    /* -- Pulse animation for graceful-stop button -- */
    {
        const style = document.createElement('style');
        style.textContent = `
            @keyframes pvpm-pulse {
                0%, 100% { opacity: 1; }
                50%      { opacity: 0.65; }
            }
            @keyframes pvpm-hp-flash {
                0%   { opacity: 1; }
                15%  { opacity: 0.3; }
                30%  { opacity: 1; }
                45%  { opacity: 0.3; }
                60%  { opacity: 1; }
                75%  { opacity: 0.3; }
                100% { opacity: 1; }
            }
            @keyframes pvpm-intro-burst {
                0%   { text-shadow: 0 0 0px #ffb74d, 0 0 0px #ff9800; color: #ffb74d; }
                20%  { text-shadow: 0 0 12px #ffb74d, 0 0 25px #ff9800, 0 0 40px #ff6d00; color: #fff8e1; }
                50%  { text-shadow: 0 0 6px #ffb74d, 0 0 14px #ff9800; color: #ffe0b2; }
                100% { text-shadow: 0 0 0px transparent; color: #ffb74d; }
            }

            /* ---------- Responsive layout (down to 316px) ---------- */
            .pvpm-root .pvpm-header > div:first-child {
                flex-wrap: wrap;
                gap: 6px 10px;
            }
            .pvpm-root .pvpm-header > div:first-child > div:last-child {
                flex-wrap: wrap;
                gap: 8px 12px !important;
            }
            .pvpm-root .pvpm-stats-row {
                flex-wrap: wrap;
                justify-content: center;
                row-gap: 4px;
            }
            .pvpm-root .pvpm-settings-row {
                flex-wrap: wrap;
                row-gap: 6px;
            }
            /* Narrow viewport: tighten paddings, hide separators that look bad when wrapped */
            @media (max-width: 480px) {
                .pvpm-root {
                    padding: 10px !important;
                    border-radius: 1.1em !important;
                    font-size: 12px !important;
                }
                .pvpm-root .pvpm-header > div:first-child > div:first-child {
                    font-size: 13px !important;
                }
                .pvpm-root .pvpm-stats-row {
                    padding: 6px 8px !important;
                    gap: 6px !important;
                }
                .pvpm-root .pvpm-stats-sep { display: none !important; }
            }
        `;
        document.head.appendChild(style);
    }

    /* -- Position persistence -- */
    function savePos() {
        try {
            const r = gui.getBoundingClientRect();
            localStorage.setItem(PANEL_POS_KEY, JSON.stringify({ right: r.right, top: r.top }));
        } catch (_) {}
    }

    function clampToViewport() {
        const rect = gui.getBoundingClientRect();
        const vw = window.innerWidth, vh = window.innerHeight, m = 8;
        let left = parseFloat(gui.style.left) || 0;
        let top  = parseFloat(gui.style.top)  || 0;
        if (left + rect.width  > vw - m) left = vw - rect.width  - m;
        if (left < m)                    left = m;
        if (top  + rect.height > vh - m) top  = vh - rect.height - m;
        if (top < m)                     top  = m;
        gui.style.left = left + 'px';
        gui.style.top  = top  + 'px';
    }

    /* -- Drag support -- */
    function makeDraggable(el) {
        if (el._dragAbort) el._dragAbort.abort();
        const ac  = new AbortController();
        el._dragAbort = ac;
        const sig = { signal: ac.signal };

        const header = el.querySelector('.pvpm-header');
        if (!header) return;

        let dragging = false, thresholdMet = false;
        let startX, startY, origLeft, origTop;
        const THRESHOLD = 5;

        function ensureAbsPos() {
            if (el._dragPositioned) return;
            const r = el.getBoundingClientRect();
            el.style.transform = 'none';
            el.style.left = r.left + 'px';
            el.style.top  = r.top  + 'px';
            el._dragPositioned = true;
        }

        function onStart(clientX, clientY, target) {
            if (target && target.closest && target.closest('button, input, select, textarea, a, label')) return;
            ensureAbsPos();
            startX   = clientX;
            startY   = clientY;
            origLeft = parseInt(el.style.left, 10) || 0;
            origTop  = parseInt(el.style.top,  10) || 0;
            dragging = true;
            thresholdMet = false;
        }

        function onMove(clientX, clientY, ev) {
            if (!dragging) return;
            const dx = clientX - startX, dy = clientY - startY;
            if (!thresholdMet) {
                if (Math.abs(dx) < THRESHOLD && Math.abs(dy) < THRESHOLD) return;
                thresholdMet = true;
            }
            const vw = window.innerWidth, vh = window.innerHeight;
            const r  = el.getBoundingClientRect();
            const x  = Math.max(8, Math.min(origLeft + dx, vw - r.width  - 8));
            const y  = Math.max(8, Math.min(origTop  + dy, vh - r.height - 8));
            el.style.left = x + 'px';
            el.style.top  = y + 'px';
            if (ev.cancelable) ev.preventDefault();
        }

        function onEnd() {
            if (!dragging) return;
            if (thresholdMet) { el._dragEndedAt = Date.now(); savePos(); }
            dragging = false;
        }

        header.addEventListener('mousedown', e => {
            onStart(e.clientX, e.clientY, e.target);
        }, sig);
        document.addEventListener('mousemove', e => onMove(e.clientX, e.clientY, e), sig);
        document.addEventListener('mouseup', onEnd, sig);

        header.addEventListener('touchstart', e => {
            if (e.touches.length !== 1) return;
            const t = e.touches[0];
            onStart(t.clientX, t.clientY, e.target);
        }, { signal: ac.signal, passive: true });
        document.addEventListener('touchmove', e => {
            if (!dragging || e.touches.length !== 1) return;
            const t = e.touches[0];
            onMove(t.clientX, t.clientY, e);
        }, { signal: ac.signal, passive: false });
        document.addEventListener('touchend', onEnd, sig);
        document.addEventListener('touchcancel', onEnd, sig);
    }

    /* -- Lightweight in-place stat + status update -- */
    function updateStatsUI() {
        const ids = { rank: statRank, tier: statTier, points: statPoints, souls: statSouls, tokens: statTokens };
        for (const [key, val] of Object.entries(ids)) {
            const el = document.getElementById(`pvpm-${key}`);
            if (el) el.textContent = val;
        }
    }

    function updateStatusUI() {
        const el = document.getElementById('pvpm-status');
        if (!el) return;
        el.textContent = currentStatus;
        el.style.color = STATUS_COLORS[currentStatus] || '#fff';
    }

    /* =========================================================
        SOLO STRATEGY  (data model, scraper, executor)
    ========================================================= */

    // Function declaration (not const arrow) so loadStrategy() can call it
    // from the runFeature-level `let strategyData = loadStrategy()` initializer
    // higher in the file - function declarations hoist, const does not.
    function STRATEGY_DEFAULT() {
        return {
            enabled: false,
            entries: [
                { skillId: FALLBACK_SKILL_ID, condition: { type: 'always' }, countTarget: 'forever', recycle: true }
            ]
        };
    }

    function normalizeStrategyEntry(e) {
        if (!e || typeof e !== 'object') return null;
        const skillId = String(e.skillId ?? '');
        const condRaw = (e.condition && typeof e.condition === 'object') ? e.condition : { type: 'always' };
        // Migrate legacy 'tokens_gte' (fixed >= operator) to the new 'tokens'
        // shape (operator-aware). Saved without op? Default to '>=' which
        // matches the legacy behavior exactly.
        const rawType = condRaw.type === 'tokens_gte' ? 'tokens' : String(condRaw.type || 'always');
        const condition = {
            type:  rawType,
            op:    typeof condRaw.op === 'string' ? condRaw.op : '>=',
            value: Number.isFinite(Number(condRaw.value)) ? Number(condRaw.value) : 0
        };
        const countTarget = (e.countTarget === 'forever' || e.countTarget === 'until_fail')
            ? e.countTarget
            : Math.max(1, parseInt(e.countTarget, 10) || 1);
        const recycle = e.recycle !== false;
        // Force-quota only meaningfully applies to fixed-N entries (forever
        // and until_fail have no quota to fulfill). Persist what the user
        // set; the executor gates by countTarget at runtime.
        const forceQuota = e.forceQuota === true;
        return { skillId, condition, countTarget, recycle, forceQuota };
    }

    function loadStrategy() {
        try {
            const raw = localStorage.getItem(STRATEGY_KEY);
            if (!raw) return STRATEGY_DEFAULT();
            const parsed = JSON.parse(raw);
            if (!parsed || typeof parsed !== 'object') return STRATEGY_DEFAULT();
            return {
                enabled: !!parsed.enabled,
                entries: Array.isArray(parsed.entries) ? parsed.entries.map(normalizeStrategyEntry).filter(Boolean) : []
            };
        } catch (e) {
            return STRATEGY_DEFAULT();
        }
    }

    function saveStrategy() {
        try { localStorage.setItem(STRATEGY_KEY, JSON.stringify(strategyData)); } catch (e) {}
    }

    /**
     * Scrape skill IDs + names from the Solo PVP Attack Pattern dropdowns
     * on the live pvp.php DOM. All dropdowns share the same option list,
     * so we only read the first. Skips the empty "no skill selected" option.
     */
    function scrapeSkillList() {
        if (skillList.length) return skillList;
        try {
            const sel = document.querySelector('select[name="pattern_skill_id[]"]');
            if (!sel) return [];
            skillList = Array.from(sel.querySelectorAll('option'))
                .filter(o => o.value !== '')
                .map(o => ({ id: String(o.value), label: (o.textContent || '').trim() }));
        } catch (e) { skillList = []; }
        return skillList;
    }

    function skillLabelFor(id) {
        const s = scrapeSkillList().find(x => x.id === String(id));
        return s ? s.label : `Skill #${id}`;
    }

    // The dropdown labels look like "Power Slash · Cost 9 · Attack" - just
    // the name (before the first ` · `) is what the user wants to see in
    // the strategy log line.
    function skillNameFor(id) {
        const label = skillLabelFor(id);
        return String(label).split(' · ')[0];
    }

    /**
     * Shortform summary of a condition that just evaluated. Used in the
     * "Strategy → #N · <status>: ..." log line so the user can see at a
     * glance which numbers tipped the entry over the threshold.
     */
    function formatConditionStatus(condition, ctx) {
        const c = condition || { type: 'always' };
        const op = c.op || '>=';
        const v  = Number(c.value) || 0;
        const fmt = (n) => (n == null) ? '?' : (Number.isInteger(n) ? String(n) : String(n));
        switch (c.type) {
            case 'always':
                return 'Always';
            case 'target_hp_pct': {
                const pct = ctx.enemyHpMax ? Math.round((ctx.enemyHp / ctx.enemyHpMax) * 100) : '?';
                return `tgt HP ${pct}% ${op} ${v}%`;
            }
            case 'self_hp_pct': {
                const pct = ctx.selfHpMax ? Math.round((ctx.selfHp / ctx.selfHpMax) * 100) : '?';
                return `self HP ${pct}% ${op} ${v}%`;
            }
            case 'tokens':
                return `tkn ${fmt(ctx.selfTokens)} ${op} ${v}`;
            case 'atk_vs_def': {
                const a = ctx.advCache && typeof ctx.advCache.myAtk    === 'number' ? ctx.advCache.myAtk    : null;
                const d = ctx.advCache && typeof ctx.advCache.theirDef === 'number' ? ctx.advCache.theirDef : null;
                if (a == null || d == null) return `atk ${op} def`;
                return `atk ${fmt(a)} ${op} def ${fmt(d)}`;
            }
            case 'def_vs_atk': {
                const a = ctx.advCache && typeof ctx.advCache.myDef    === 'number' ? ctx.advCache.myDef    : null;
                const d = ctx.advCache && typeof ctx.advCache.theirAtk === 'number' ? ctx.advCache.theirAtk : null;
                if (a == null || d == null) return `def ${op} atk`;
                return `def ${fmt(a)} ${op} atk ${fmt(d)}`;
            }
            case 'preceding_failed':
                return 'default (no prior matched)';
            default:
                return String(c.type);
        }
    }

    /**
     * Load the persisted { [skillId]: imageUrl } map. Populated by
     * cacheSkillImages() during matches; used by the strategy editor to
     * show skill thumbnails.
     */
    function loadSkillImageMap() {
        try {
            const raw = localStorage.getItem(SKILL_IMG_KEY);
            if (!raw) return {};
            const m = JSON.parse(raw);
            return (m && typeof m === 'object') ? m : {};
        } catch (e) { return {}; }
    }

    /**
     * Walk state.me.skills (each skill has an `icon` or `picture` field;
     * shape mirrors the pvp_battle.php client which renders these via
     * <img class="skillIco" src="${skill.icon || skill.picture || ...}">)
     * and persist a { skillId: absoluteUrl } map. Resolves relative paths
     * against the demonicscans origin so the URL works regardless of
     * which page the strategy editor is rendered on.
     */
    function cacheSkillImages(state) {
        try {
            const skills = state && state.me && state.me.skills;
            if (!Array.isArray(skills) || !skills.length) return false;
            const map = loadSkillImageMap();
            let changed = false;
            for (const s of skills) {
                if (!s || s.id == null) continue;
                const id = String(s.id);
                const raw = s.icon || s.picture;
                if (!raw) continue;
                let abs;
                if (/^https?:\/\//i.test(raw)) {
                    abs = raw;
                } else if (raw.startsWith('/')) {
                    abs = 'https://demonicscans.org' + raw;
                } else {
                    abs = 'https://demonicscans.org/' + raw;
                }
                if (map[id] !== abs) { map[id] = abs; changed = true; }
            }
            if (changed) localStorage.setItem(SKILL_IMG_KEY, JSON.stringify(map));
            return changed;
        } catch (e) { return false; }
    }

    /**
     * Pull the most-recent exchange's attacker/defender scores from a poll's
     * new_logs (not the live state - the formula scores live in log entries).
     * Returns { atk, def, adv } or null if no exchange has happened yet.
     */
    function lastExchangeFormula(state) {
        try {
            const logs = state?.new_logs || [];
            for (let i = logs.length - 1; i >= 0; i--) {
                const f = logs[i]?.details?.formula;
                if (f && (typeof f.exchange_attacker_score === 'number' || typeof f.exchange_defender_score === 'number')) {
                    return {
                        atk: Number(f.exchange_attacker_score) || 0,
                        def: Number(f.exchange_defender_score) || 0,
                        adv: Number(f.exchange_advantage_score) || 0
                    };
                }
            }
        } catch (e) {}
        return null;
    }

    function evalCondition(cond, ctx, idx, entries) {
        const c = cond || { type: 'always' };
        const cmp = (a, b, op) => {
            switch (op) {
                case '>':  return a >  b;
                case '>=': return a >= b;
                case '<':  return a <  b;
                case '<=': return a <= b;
                case '==': return a === b;
                default:   return false;
            }
        };
        switch (c.type) {
            case 'always': return true;
            case 'target_hp_pct': {
                if (!ctx.enemyHpMax) return false;
                return cmp((ctx.enemyHp / ctx.enemyHpMax) * 100, Number(c.value) || 0, c.op || '>');
            }
            case 'self_hp_pct': {
                if (!ctx.selfHpMax) return false;
                return cmp((ctx.selfHp / ctx.selfHpMax) * 100, Number(c.value) || 0, c.op || '>');
            }
            case 'tokens':
                return cmp(Number(ctx.selfTokens), Number(c.value) || 0, c.op || '>=');
            case 'atk_vs_def': {
                const a = ctx.advCache && ctx.advCache.myAtk;
                const d = ctx.advCache && ctx.advCache.theirDef;
                // Missing data: condition fails -> next entry tried.
                // Treat 0 as a real value (not missing) since defenses can be 0.
                if (a == null || d == null) return false;
                return cmp(a, d, c.op || '>=');
            }
            case 'def_vs_atk': {
                const a = ctx.advCache && ctx.advCache.myDef;
                const d = ctx.advCache && ctx.advCache.theirAtk;
                if (a == null || d == null) return false;
                return cmp(a, d, c.op || '>=');
            }
            case 'preceding_failed': {
                // Default-strategy entry: passes only if every entry at a
                // lower index in the list has a condition that evaluates
                // false right now. Indifferent to ptr / rotation - the
                // semantics are about list order.
                if (!Array.isArray(entries) || typeof idx !== 'number' || idx <= 0) return true;
                for (let i = 0; i < idx; i++) {
                    const prev = entries[i];
                    if (!prev) continue;
                    if (evalCondition(prev.condition, ctx, i, entries)) return false;
                }
                return true;
            }
            default: return true;
        }
    }

    function newStrategyRuntime() {
        return {
            ptr: 0,
            usesRemaining: null,
            exhausted: new Set()
        };
    }

    /**
     * Read player Advantage Scores live from ogmaend's pvp-stats-display
     * localStorage cache (`bls_memory`). Returns
     * { myAtk, myDef, theirAtk, theirDef } - any field is null if the
     * cache is missing, malformed, or doesn't have a value for that
     * player+role combination.
     *
     * We don't keep our own cache: ogmaend's script already maintains
     * one with proper invalidation. Reading live each turn is cheap
     * (one JSON.parse) and means we always see the freshest values
     * including any updates ogmaend writes mid-match from another tab.
     */
    function readBlsScores(allyUid, enemyUid) {
        const out = { myAtk: null, myDef: null, theirAtk: null, theirDef: null };
        let mem;
        try {
            const raw = localStorage.getItem('bls_memory');
            if (!raw) return out;
            mem = JSON.parse(raw);
            if (!mem || typeof mem !== 'object') return out;
        } catch (e) { return out; }

        const mine   = allyUid  != null ? mem[String(allyUid)]  : null;
        const theirs = enemyUid != null ? mem[String(enemyUid)] : null;

        if (mine) {
            if (typeof mine.attackerScore === 'number') out.myAtk = mine.attackerScore;
            if (typeof mine.defenderScore === 'number') out.myDef = mine.defenderScore;
        }
        if (theirs) {
            if (typeof theirs.attackerScore === 'number') out.theirAtk = theirs.attackerScore;
            if (typeof theirs.defenderScore === 'number') out.theirDef = theirs.defenderScore;
        }
        return out;
    }

    /**
     * Compute a player's effective ATK build from a formula object.
     * Ported verbatim from pvp_stats_display.js computeAttackBuild().
     */
    function computeAttackBuild(f) {
        return (f.exchange_attacker_attack_core || 0)
             + (f.exchange_attacker_equipment_attack || 0)
             + Math.round(0.2 * (f.exchange_attacker_pet_attack_raw_total || 0));
    }

    /**
     * Compute a player's effective DEF build from a formula object.
     * Ported verbatim from pvp_stats_display.js computeDefenseBuild().
     */
    function computeDefenseBuild(f) {
        return Math.round(0.6 * (f.exchange_defender_defense_core_used || 0))
             + (f.exchange_defender_equipment_defense_used || 0)
             + Math.round(0.2 * (f.exchange_defender_pet_defense_used_total || 0));
    }

    /**
     * Write actor-attributed exchange scores into ogmaend's `bls_memory`
     * cache as they appear in match logs. Same key/shape ogmaend uses, so
     * its UI on pvp_battle.php sees our updates too. This fills the cache
     * for opponents we encounter only via pvp-manager automation -
     * ogmaend's own poll loop only runs on pvp_battle.php, so without this
     * step `bls_memory` would never be populated for opponents we fight
     * exclusively through automation.
     *
     * Also updates the in-memory liveStat object so buildAndroidState()
     * can export a complete battleStats block to the Android UI even when
     * pvp_stats_display.js is inactive (which it always is on pvp.php).
     */
    function writeBlsFromLogs(newLogs, matchId) {
        if (!Array.isArray(newLogs) || !newLogs.length) return;
        let mem;
        try {
            const raw = localStorage.getItem('bls_memory');
            mem = raw ? JSON.parse(raw) : {};
            if (!mem || typeof mem !== 'object') mem = {};
        } catch (e) { mem = {}; }

        const matchIdNum = Number(matchId) || null;
        const myUid  = allyUid  ? String(allyUid)  : null;
        const enUid  = enemyUid ? String(enemyUid) : null;
        let changed = false;

        for (const log of newLogs) {
            const f = log && log.details && log.details.formula;
            if (!f) continue;
            const actor  = log.details.actor  || {};
            const target = log.details.target || {};
            const actorUid  = String(actor.key  || '').split(':')[1] || '';
            const targetUid = String(target.key || '').split(':')[1] || '';
            if (!actorUid && !targetUid) continue;

            // Raw scores
            const atk = (typeof f.exchange_attacker_score === 'number') ? f.exchange_attacker_score : null;
            const def = (typeof f.exchange_defender_score === 'number') ? f.exchange_defender_score : null;

            // Derived build stats (DMG = attacker's ATK build, RET = defender's DEF build)
            const btd = (f.exchange_attacker_attack_core        != null) ? computeAttackBuild(f)  : null;
            const dbb = (f.exchange_defender_defense_core_used  != null) ? computeDefenseBuild(f) : null;
            const cc  = (typeof f.critical_chance === 'number')          ? f.critical_chance      : null;

            // Per-match multipliers — overwrite with latest value each log entry
            const tb = (typeof f.turn_bonus_multiplier    === 'number') ? f.turn_bonus_multiplier    : null;
            const gp = (typeof f.global_damage_multiplier === 'number') ? f.global_damage_multiplier : null;
            if (tb !== null) liveStat.turnBonus    = tb;
            if (gp !== null) liveStat.globalPacing = gp;

            // Update liveStat for the attacker side
            if (myUid && actorUid === myUid) {
                if (atk !== null) liveStat.myAtk  = atk;
                if (btd !== null) liveStat.myDmg  = btd;
                if (cc  !== null) liveStat.myCrit = cc;
            } else if (enUid && actorUid === enUid) {
                if (atk !== null) liveStat.enAtk  = atk;
                if (btd !== null) liveStat.enDmg  = btd;
                if (cc  !== null) liveStat.enCrit = cc;
            }

            // Update liveStat for the defender side
            if (myUid && targetUid === myUid) {
                if (def !== null) liveStat.myDef = def;
                if (dbb !== null) liveStat.myRet = dbb;
            } else if (enUid && targetUid === enUid) {
                if (def !== null) liveStat.enDef = def;
                if (dbb !== null) liveStat.enRet = dbb;
            }

            // ── Write to bls_memory (attacker record) ──────────────────────
            if (actorUid) {
                const cur = mem[actorUid] || {};
                const patch = {};
                if (atk !== null && cur.attackerScore !== atk) patch.attackerScore = atk;
                if (btd !== null && cur.baseTargetDmg !== btd) patch.baseTargetDmg = btd;
                if (cc  !== null && cur.critChance    !== cc)  patch.critChance    = cc;
                if (Object.keys(patch).length) {
                    mem[actorUid] = Object.assign({}, cur, patch, {
                        name:        actor.name || cur.name || null,
                        lastMatchId: matchIdNum != null ? `pvp:${matchIdNum}` : (cur.lastMatchId || null)
                    });
                    changed = true;
                }
            }

            // ── Write to bls_memory (defender record) ──────────────────────
            if (targetUid) {
                const cur = mem[targetUid] || {};
                const patch = {};
                if (def !== null && cur.defenderScore  !== def) patch.defenderScore  = def;
                if (dbb !== null && cur.baseDamageBack !== dbb) patch.baseDamageBack = dbb;
                if (Object.keys(patch).length) {
                    mem[targetUid] = Object.assign({}, cur, patch, {
                        name:        target.name || cur.name || null,
                        lastMatchId: matchIdNum != null ? `pvp:${matchIdNum}` : (cur.lastMatchId || null)
                    });
                    changed = true;
                }
            }
        }

        if (changed) {
            try { localStorage.setItem('bls_memory', JSON.stringify(mem)); } catch (e) {}
        }
    }

    /**
     * One-shot diagnostic at the start of a strategy match: logs (a) what
     * the bls_memory cache contains for our two players and (b) the keys
     * present in bls_memory so we can spot mismatches between the user_id
     * shape we use and the shape ogmaend stores. Goes to the console only -
     * does not pollute the in-panel battle log.
     */
    function logBlsDiagnostic(allyUid, enemyUid) {
        try {
            const raw = localStorage.getItem('bls_memory');
            if (!raw) {
                console.warn('[PvP Manager] bls_memory not found in localStorage. ogmaend\'s pvp-stats-display has either never run on this origin or no opponent has been observed yet.');
                return;
            }
            const mem = JSON.parse(raw) || {};
            const allKeys = Object.keys(mem);
            const sampleKeys = allKeys.slice(0, 6);
            const mineKey   = String(allyUid);
            const theirsKey = String(enemyUid);
            const mine      = mem[mineKey];
            const theirs    = mem[theirsKey];
            console.log('[PvP Manager] bls_memory diagnostic:', {
                totalPlayersCached: allKeys.length,
                sampleKeys,
                allyUid, mineKey, mineRecord:   mine   ? { attackerScore: mine.attackerScore,   defenderScore: mine.defenderScore,   name: mine.name } : null,
                enemyUid, theirsKey, theirsRecord: theirs ? { attackerScore: theirs.attackerScore, defenderScore: theirs.defenderScore, name: theirs.name } : null
            });
            if (!mine)   console.warn(`[PvP Manager] bls_memory has no record for ally uid "${mineKey}". ogmaend won't update bls_memory unless its script is running on pvp_battle.php - if you automate matches without that page open in another tab, bls_memory stays stale.`);
            if (!theirs) console.warn(`[PvP Manager] bls_memory has no record for enemy uid "${theirsKey}". You may not have fought this opponent before, or ogmaend wasn't running when you did.`);
        } catch (e) {
            console.warn('[PvP Manager] bls_memory diagnostic failed:', e);
        }
    }

    function buildTargetKey(targetType, ctxRefs) {
        const { selfUnit, enemyUnit, allies, myUid } = ctxRefs;
        switch (targetType) {
            case 'enemy':
                if (enemyUnit?.user_id) return `enemy:${enemyUnit.user_id}`;
                return null;
            case 'self':
                if (myUid) return `self:${myUid}`;
                if (selfUnit?.user_id) return `self:${selfUnit.user_id}`;
                return null;
            case 'ally_alive': {
                const u = Object.values(allies || {}).find(a => a && a.alive);
                return u?.user_id ? `ally:${u.user_id}` : null;
            }
            case 'ally_dead': {
                const u = Object.values(allies || {}).find(a => a && !a.alive);
                return u?.user_id ? `ally:${u.user_id}` : null;
            }
            default:
                if (enemyUnit?.user_id) return `enemy:${enemyUnit.user_id}`;
                return null;
        }
    }

    /**
     * Walk the strategy entries from runtime.ptr forward (with wrap), find the
     * first entry whose skill is usable (in our skills list, enough tokens) AND
     * whose condition passes. Mutates runtime to advance the counter / pointer.
     * Returns { skillId, targetKey } or null if nothing matched (caller should
     * fall back to the basic attack).
     */
    function pickStrategyAction(state, runtime) {
        const entries = strategyData.entries || [];
        if (!entries.length) return null;

        const me      = state?.me || {};
        const myUid   = me.user_id || allyUid;
        const allies  = state?.teams?.ally?.players_by_num || {};
        const foes    = state?.teams?.enemy?.players_by_num || {};
        const enemyUnit = Object.values(foes).find(u => u && u.alive) || Object.values(foes)[0] || null;
        const selfUnit  = Object.values(allies).find(u => u && u.user_id === myUid) || Object.values(allies)[0] || null;

        const ctx = {
            selfHp:      Number(selfUnit?.hp ?? 0),
            selfHpMax:   Number(selfUnit?.hp_max ?? 0),
            selfTokens:  Number(me.tokens ?? selfUnit?.tokens ?? 0),
            enemyHp:     Number(enemyUnit?.hp ?? 0),
            enemyHpMax:  Number(enemyUnit?.hp_max ?? 0),
            // Read Advantage Scores live from ogmaend's bls_memory cache.
            // No local copy; we trust the source of truth.
            advCache: readBlsScores(allyUid, enemyUid)
        };
        const ctxRefs = { selfUnit, enemyUnit, allies, myUid };
        const mySkills = Array.isArray(me.skills) ? me.skills : [];

        const N = entries.length;
        // Capture the starting ptr once so advancing runtime.ptr mid-loop
        // (for until_fail entries) does not shift later iteration indices.
        const startPtr = runtime.ptr;
        // Trace per-entry decisions for console.debug visibility.
        const _trace = [];
        for (let step = 0; step < N; step++) {
            const idx = (startPtr + step) % N;
            if (runtime.exhausted.has(idx)) { _trace.push(`#${idx + 1} skip: exhausted`); continue; }
            const entry = entries[idx];
            if (!entry) { _trace.push(`#${idx + 1} skip: no entry`); continue; }

            const skillObj = mySkills.find(s => String(s.id) === String(entry.skillId));
            const cost = Number(skillObj?.cost ?? 0);
            const tKey = skillObj ? buildTargetKey(skillObj.target, ctxRefs) : null;
            const targetable = !!skillObj && !!tKey;
            const condOk = targetable && evalCondition(entry.condition, ctx, idx, entries);
            const tokensOk = cost <= Number(ctx.selfTokens || 0);

            // Force-quota stick: only at the current ptr (step=0), only on
            // fixed-N entries (forever/until_fail have no quota to fulfill).
            // When the condition is met but we lack tokens, fire a basic
            // attack (Slash) to build tokens and keep the pointer parked
            // here so the entry's quota will be fulfilled across turns.
            const isFixedQuota = entry.forceQuota === true
                && entry.countTarget !== 'forever'
                && entry.countTarget !== 'until_fail';
            if (step === 0 && isFixedQuota && targetable && condOk && !tokensOk) {
                const enemyKey = enemyUnit?.user_id ? `enemy:${enemyUnit.user_id}` : null;
                if (enemyKey) {
                    // Don't touch runtime.usesRemaining - we haven't used the
                    // entry's skill yet. ptr is already startPtr === idx.
                    _trace.push(`#${idx + 1} force-quota fill (Slash)`);
                    if (isDebugLogsEnabled()) console.log('[PvP Manager] strategy decision:', _trace, ctx);
                    return {
                        skillId:        FALLBACK_SKILL_ID,
                        targetKey:      enemyKey,
                        chosenIdx:      idx,
                        note:           'force-quota fill',
                        conditionStatus: formatConditionStatus(entry.condition, ctx)
                    };
                }
                // No enemy target available - fall through to normal skip.
            }

            const usable = targetable && condOk && tokensOk;
            if (!usable) {
                // Build a brief reason string for the trace.
                let why = !skillObj ? 'skill not in your skills list'
                        : !tKey     ? 'no valid target'
                        : !condOk   ? `condition (${entry.condition.type}) failed`
                        : !tokensOk ? `insufficient tokens (need ${cost}, have ${ctx.selfTokens})`
                                    : 'unusable';
                _trace.push(`#${idx + 1} skip: ${why}`);
                // until_fail entries advance the pointer the moment they
                // can't fire. Without this, ptr would stay parked on an
                // entry whose condition stopped holding.
                if (step === 0 && entry.countTarget === 'until_fail') {
                    if (!entry.recycle) runtime.exhausted.add(idx);
                    runtime.ptr = (idx + 1) % N;
                    runtime.usesRemaining = null;
                }
                continue;
            }

            // Advance pointer to here (in case we skipped some) and (re)init counter
            if (runtime.ptr !== idx || runtime.usesRemaining === null) {
                runtime.ptr = idx;
                const ct = entry.countTarget;
                runtime.usesRemaining = (ct === 'forever' || ct === 'until_fail')
                    ? Infinity
                    : Number(ct) || 1;
            }

            // Consume one use
            if (runtime.usesRemaining !== Infinity) runtime.usesRemaining -= 1;
            if (runtime.usesRemaining <= 0) {
                if (!entry.recycle) runtime.exhausted.add(idx);
                runtime.ptr = (idx + 1) % N;
                runtime.usesRemaining = null;
            }

            _trace.push(`#${idx + 1} matched`);
            if (isDebugLogsEnabled()) console.log('[PvP Manager] strategy decision:', _trace, ctx);
            return {
                skillId:        String(entry.skillId),
                targetKey:      tKey,
                chosenIdx:      idx,
                note:           'matched',
                conditionStatus: formatConditionStatus(entry.condition, ctx)
            };
        }
        if (isDebugLogsEnabled()) console.log('[PvP Manager] strategy decision (no entry matched, fallback to Slash):', _trace, ctx);
        return null;
    }

    /**
     * POST a use_skill action with our chosen skill + target.
     */
    async function postUseSkill(matchId, sinceLogId, skillId, targetKey) {
        return pvpPost(BATTLE_ACT_URL, {
            match_id:     matchId,
            since_log_id: sinceLogId,
            action:       'use_skill',
            skill_id:     skillId,
            target_key:   targetKey
        });
    }

    /* ---- Strategy tab: entry-row HTML (built per render) ---- */

    const COND_TYPE_LABELS = {
        always:            'Always',
        target_hp_pct:     "Target's HP %",
        self_hp_pct:       'Self HP %',
        tokens:            'Tokens',
        atk_vs_def:        'Own Atk vs Enemy Def (Adv. Score)',
        def_vs_atk:        'Own Def vs Enemy Atk (Adv. Score)',
        preceding_failed:  'When all preceding conditions fail (default strategy)'
    };
    const COND_OP_LABELS = { '>': '&gt;', '>=': '&ge;', '<': '&lt;', '<=': '&le;', '==': '=' };

    function strategyEntryHtml(entry, idx, total, skillImgMap) {
        const skills = scrapeSkillList();
        const skillOpts = skills.length
            ? skills.map(s =>
                `<option value="${escapeHtml(s.id)}"${String(s.id) === String(entry.skillId) ? ' selected' : ''}>${escapeHtml(s.label)}</option>`
              ).join('')
            : `<option value="${escapeHtml(entry.skillId)}" selected>Skill #${escapeHtml(entry.skillId)} (skill list not scraped)</option>`;

        const condTypeOpts = Object.entries(COND_TYPE_LABELS).map(([v, l]) =>
            `<option value="${v}"${entry.condition.type === v ? ' selected' : ''}>${l}</option>`
        ).join('');

        const opOpts = Object.entries(COND_OP_LABELS).map(([v, l]) =>
            `<option value="${v}"${entry.condition.op === v ? ' selected' : ''}>${l}</option>`
        ).join('');

        const t = entry.condition.type;
        const showOp    = ['target_hp_pct', 'self_hp_pct', 'tokens', 'atk_vs_def', 'def_vs_atk'].includes(t);
        const showValue = ['target_hp_pct', 'self_hp_pct', 'tokens'].includes(t);
        const valueSuffix = (t === 'target_hp_pct' || t === 'self_hp_pct') ? '%' : '';

        const inputCss   = `padding:3px 6px; background:#111; color:#ddd; border:1px solid #444; border-radius:4px; font-size:11px; font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;`;
        const btnCss     = `padding:2px 7px; cursor:pointer; border-radius:4px; font-size:12px; line-height:1; font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;`;

        const countMode = entry.countTarget === 'forever' ? 'forever'
                        : entry.countTarget === 'until_fail' ? 'until_fail'
                        : 'fixed';
        const countNum  = (countMode === 'fixed') ? Number(entry.countTarget) || 1 : 1;
        const countOpts = [
            ['fixed',      'N time(s)'],
            ['forever',    'forever'],
            ['until_fail', 'until condition fails']
        ].map(([v, l]) => `<option value="${v}"${countMode === v ? ' selected' : ''}>${l}</option>`).join('');

        // Each visual line is its own non-wrapping flex row. The page's
        // global stylesheet appears to give selects/inputs a wide default,
        // which - combined with flex-wrap:wrap - made every input balloon
        // to its own line. Explicit widths + min-width:0 + box-sizing on
        // flex children keep them sharing rows.
        const rowCss = 'display:flex; align-items:center; gap:5px; margin-top:4px;';
        const fillCss = `${inputCss} flex:1; min-width:0; box-sizing:border-box;`;
        const opCss   = `${inputCss} width:62px; box-sizing:border-box;`;
        const valCss  = `${inputCss} width:74px; box-sizing:border-box;`;
        const cntCss  = `${inputCss} width:58px; box-sizing:border-box;`;
        const labelCss = 'color:#888; font-size:11px; min-width:34px;';

        const skillImgUrl = (skillImgMap || {})[String(entry.skillId)] || null;
        const iconBoxCss = 'width:28px; height:28px; flex:none; border-radius:6px; border:1px solid #333; background:#1a1a1a; box-sizing:border-box; overflow:hidden;';
        const iconHtml = skillImgUrl
            ? `<img src="${escapeHtml(skillImgUrl)}" alt="" style="${iconBoxCss} object-fit:cover;">`
            : `<div style="${iconBoxCss}" title="Skill icon will appear after your first match"></div>`;

        return `
            <div class="pvpm-strategy-entry" data-idx="${idx}"
                 style="background:#0e0e0e; border:1px solid #2a2a2a; border-radius:6px; padding:7px 9px; margin-bottom:5px;">
                <div style="${rowCss} margin-top:0;">
                    <span style="color:#666; font-weight:bold; font-size:11px; min-width:24px;">#${idx + 1}</span>
                    ${iconHtml}
                    <select class="pvpm-strat-skill" data-idx="${idx}" style="${fillCss}">
                        ${skillOpts}
                    </select>
                    <button class="pvpm-strat-up"   data-idx="${idx}" title="Move up"
                            ${idx === 0 ? 'disabled' : ''}
                            style="${btnCss} background:#222; color:#aaa; border:1px solid #333; ${idx === 0 ? 'opacity:0.3;' : ''}">↑</button>
                    <button class="pvpm-strat-down" data-idx="${idx}" title="Move down"
                            ${idx === total - 1 ? 'disabled' : ''}
                            style="${btnCss} background:#222; color:#aaa; border:1px solid #333; ${idx === total - 1 ? 'opacity:0.3;' : ''}">↓</button>
                    <button class="pvpm-strat-del"  data-idx="${idx}" title="Delete"
                            style="${btnCss} background:#3a1313; color:#ef9a9a; border:1px solid #5a1c1c;">&times;</button>
                </div>
                <div style="${rowCss}">
                    <span style="${labelCss}">When</span>
                    <select class="pvpm-strat-cond-type" data-idx="${idx}" style="${fillCss}">${condTypeOpts}</select>
                </div>
                ${(showOp || showValue) ? `
                <div style="${rowCss}">
                    <span style="${labelCss}"></span>
                    ${showOp    ? `<select class="pvpm-strat-cond-op"  data-idx="${idx}" style="${opCss}">${opOpts}</select>` : ''}
                    ${showValue ? `<input  class="pvpm-strat-cond-val" data-idx="${idx}" type="number" value="${entry.condition.value}" style="${valCss}">` : ''}
                    ${valueSuffix ? `<span style="color:#666; font-size:11px;">${valueSuffix}</span>` : ''}
                </div>` : ''}
                <div style="${rowCss}">
                    <span style="${labelCss}">Use</span>
                    ${countMode === 'fixed'
                        ? `<input class="pvpm-strat-count" data-idx="${idx}" type="number" min="1" value="${countNum}" style="${cntCss}">`
                        : ''}
                    <select class="pvpm-strat-count-mode" data-idx="${idx}" style="${fillCss}">${countOpts}</select>
                </div>
                <label style="${rowCss} cursor:pointer;">
                    <span style="${labelCss}"></span>
                    <input class="pvpm-strat-recycle" data-idx="${idx}" type="checkbox" ${entry.recycle ? 'checked' : ''}
                           style="cursor:pointer; accent-color:#2e7d32;">
                    <span style="color:#888; font-size:11px;">Use again on next pass</span>
                </label>
                ${countMode === 'fixed' ? `
                <label style="${rowCss} cursor:pointer;" title="If enabled and the condition is met but you do not have enough tokens, the bot stays on this skill and uses Slash to build tokens, then resumes - until the count is fulfilled.">
                    <span style="${labelCss}"></span>
                    <input class="pvpm-strat-force" data-idx="${idx}" type="checkbox" ${entry.forceQuota ? 'checked' : ''}
                           style="cursor:pointer; accent-color:#2e7d32;">
                    <span style="color:#888; font-size:11px;">Force use quota (fill with Slashes when low on tokens)</span>
                </label>` : ''}
            </div>
        `;
    }

    function tabBtnHtml(id, label) {
        const active = currentTab === id;
        return `<div class="pvpm-tab" data-tab="${id}"
                     style="padding:6px 13px; cursor:pointer; font-size:12px; font-weight:bold;
                            color:${active ? '#fff' : '#888'};
                            background:${active ? '#1565c0' : '#181818'};
                            border:1px solid ${active ? '#1976d2' : '#2a2a2a'};
                            border-bottom:none;
                            border-radius:6px 6px 0 0;
                            user-select:none;">${label}</div>`;
    }

    function renderGUI() {
        const _skip     = _initialRender;
        _initialRender  = false;

        const prevRect  = gui.getBoundingClientRect();
        const prevRight = prevRect.right;
        const prevTop   = prevRect.top;
        const statusColor = STATUS_COLORS[currentStatus] || '#fff';

        /* ---- Minimised (pill) state ---- */
        if (minimized) {
            gui.style.width   = COLLAPSED_W + 'px';
            gui.style.padding = '6px 10px';
            gui.innerHTML = `
                <div class="pvpm-header" id="pvpm-mini"
                     style="cursor:pointer; display:flex; justify-content:space-between; align-items:center;
                            font-size:13px; font-weight:bold; color:${statusColor}; white-space:nowrap;">
                    <span>● PvP</span>
                    <span style="color:#fff; font-size:16px;">＋</span>
                </div>
            `;
            gui.querySelector('#pvpm-mini').addEventListener('click', e => {
                if (gui._dragEndedAt && Date.now() - gui._dragEndedAt < 300) return;
                minimized = false;
                localStorage.setItem(UI_MIN_KEY, 'false');
                renderGUI();
            });
            makeDraggable(gui);
            requestAnimationFrame(() => {
                if (!_skip) { gui.style.left = (prevRight - gui.getBoundingClientRect().width) + 'px'; gui.style.top = prevTop + 'px'; savePos(); }
                clampToViewport();
            });
            return;
        }

        /* ---- Expanded state ---- */
        gui.style.width   = `min(${EXPANDED_W}px, calc(100vw - 16px))`;
        // Padding is handled per-section now (header has its own padding,
        // body has its own padding) so the body can scroll without the
        // header scrolling with it.
        gui.style.padding = '0';

        // Capture logBox scroll state BEFORE innerHTML wipe detaches it.
        // _logPinned already tracks intent; _savedLogScroll is a fallback for
        // the case where the user is manually scrolled to a specific position.
        const _savedLogScroll = logBox.scrollTop;
        const _wasAtBottom    = _logPinned;

        // Suppress scroll events during the detach/reattach cycle.
        // When innerHTML wipes the parent, logBox gets detached and the browser
        // may reset scrollTop to 0 and fire a scroll event, which would
        // incorrectly set _logPinned = false.
        _programmaticScroll = true;

        const actionButtonHtml = running
            ? (stopAfterMatch
                ? `<button id="pvpm-stop"
                           style="flex:1; padding:9px; border-radius:8px; font-weight:bold; cursor:pointer;
                                  background:#e65100; color:#fff; border:1px solid #ff9800; font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; font-size:12px;
                                  animation: pvpm-pulse 1.5s ease-in-out infinite;">
                       ⏳ Stopping after match - press to stop now
                   </button>`
                : `<button id="pvpm-stop"
                           style="flex:1; padding:9px; border-radius:8px; font-weight:bold; cursor:pointer;
                                  background:#b71c1c; color:#fff; border:1px solid #f44336; font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; font-size:13px;">
                       🛑 STOP
                   </button>`)
            : `<button id="pvpm-start"
                       style="flex:1; padding:9px; border-radius:8px; font-weight:bold; cursor:pointer;
                              background:#1565c0; color:#fff; border:1px solid #1976d2; font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; font-size:13px;">
                   ▶ Start Solo Match
               </button>`;

        const battleTabBody = `
            <!-- Stats row -->
            <div class="pvpm-stats-row" style="display:flex; justify-content:space-between; align-items:center;
                        background:#181818; padding:8px 14px; border-radius:8px; margin-bottom:10px; font-size:12px; gap:8px;">
                <div title="Tokens">⏳️ <span id="pvpm-tokens" style="color:#EDEFF6;">${statTokens}</span> <span style="color:#EDEFF6;">tkn</span></div>
                <div class="pvpm-stats-sep" style="color:#333;">|</div>
                <div title="Player Souls">💠️ <span id="pvpm-souls" style="color:#EDEFF6;">${statSouls}</span></div>
                <div class="pvpm-stats-sep" style="color:#333;">|</div>
                <div title="Solo Rank">🏆 <span id="pvpm-rank" style="color:#ffd54f; font-weight:bold;">${statRank}</span> <span id="pvpm-tier" style="color:#EDEFF6;">${statTier}</span></div>
                <div class="pvpm-stats-sep" style="color:#333;">|</div>
                <div title="Solo Points">⭐ <span id="pvpm-points" style="color:#EDEFF6;">${statPoints}</span> <span style="color:#EDEFF6;">pts</span></div>
            </div>

            <!-- Action button -->
            <div style="display:flex; gap:6px; margin-bottom:4px;">
                ${actionButtonHtml}
            </div>
        `;

        const skillImgMap = loadSkillImageMap();
        const strategyTabBody = `
            <div style="background:#181818; border:1px solid #333; border-radius:8px; padding:10px 12px; margin-bottom:4px;">
                <div style="display:flex; align-items:center; gap:8px; margin-bottom:6px;">
                    <input id="pvpm-strategy-enabled" type="checkbox" ${strategyData.enabled ? 'checked' : ''}
                           style="cursor:pointer; accent-color:#2e7d32; flex-shrink:0;">
                    <label for="pvpm-strategy-enabled" style="color:#ddd; cursor:pointer; font-weight:bold; font-size:12px;">Use a Solo Strategy</label>
                    <button id="pvpm-strategy-info-btn" type="button" title="What does this do?"
                            aria-expanded="false" aria-controls="pvpm-strategy-info"
                            style="background:transparent; border:0; color:#888; cursor:pointer; font-size:14px; line-height:1; padding:2px 4px; border-radius:50%;">ℹ️</button>
                </div>
                <div id="pvpm-strategy-info" hidden
                     style="color:#888; font-size:11px; margin:0 0 10px 24px; padding:6px 8px; background:#111; border-left:2px solid #2a2a2a; border-radius:3px;">
                    When ON, the bot picks skills from the list below during your turns instead of using server auto-resolve. Entries are tried in order; skipped when their condition fails or when you do not have enough tokens; the list wraps when exhausted; basic attack (Slash) is the fallback when nothing matches.
                </div>
                <div style="border-bottom:1px solid #2a2a2a; margin-bottom:10px;"></div>
                <div id="pvpm-strategy-entries"
                     style="max-height: 50vh; overflow-y: auto; padding-right: 4px;">
                    ${(strategyData.entries || []).map((e, i) => strategyEntryHtml(e, i, strategyData.entries.length, skillImgMap)).join('')}
                    ${(!strategyData.entries || !strategyData.entries.length)
                        ? `<div style="color:#666; font-size:11px; padding:8px 0;">No entries yet. Click "Add entry" below to start building your strategy.</div>`
                        : ''}
                </div>
                <button id="pvpm-strategy-add"
                        style="margin-top:6px; padding:6px 12px; border-radius:6px; cursor:pointer;
                               background:#1565c0; color:#fff; border:1px solid #1976d2; font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; font-size:11px;">
                    + Add entry
                </button>
            </div>
        `;

        const settingsTabBody = `
            <div id="pvpm-settings-panel"
                 style="background:#181818; border:1px solid #333; border-radius:8px; padding:10px 14px; margin-bottom:4px;">
                <div class="pvpm-settings-row" style="display:flex; align-items:center; gap:10px; font-size:12px; margin-bottom:8px;">
                    <label style="color:#999; white-space:nowrap;">Max log entries:</label>
                    <input id="pvpm-log-limit-input" type="number" min="10" max="2000" value="${logLimit}"
                           style="width:80px; padding:4px 8px; border-radius:6px; border:1px solid #444;
                                  background:#111; color:#ddd; font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; font-size:12px;">
                    <button id="pvpm-log-limit-save"
                            style="padding:4px 10px; border-radius:6px; cursor:pointer;
                                   background:#2e7d32; color:#fff; border:1px solid #388e3c; font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; font-size:11px;">
                        Save
                    </button>
                    <span id="pvpm-log-limit-msg" style="color:#66bb6a; font-size:11px;"></span>
                </div>
                <div class="pvpm-settings-row" style="display:flex; align-items:center; gap:10px; font-size:12px;">
                    <label for="pvpm-persist-toggle" style="color:#999; white-space:nowrap; cursor:pointer;">Persist logs:</label>
                    <input id="pvpm-persist-toggle" type="checkbox" ${persistLogsEnabled ? 'checked' : ''}
                           style="cursor:pointer; accent-color:#2e7d32;">
                    <span style="color:#666; font-size:11px;">Keep logs across page refreshes (very heavy!)</span>
                </div>
                <div class="pvpm-settings-row" style="display:flex; align-items:center; gap:10px; font-size:12px; margin-top:8px;">
                    <label style="color:#999; white-space:nowrap;">Max match history entries:</label>
                    <input id="pvpm-history-limit-input" type="number" min="10" max="10000" value="${historyLimit}"
                           style="width:80px; padding:4px 8px; border-radius:6px; border:1px solid #444;
                                  background:#111; color:#ddd; font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; font-size:12px;">
                    <button id="pvpm-history-limit-save"
                            style="padding:4px 10px; border-radius:6px; cursor:pointer;
                                   background:#2e7d32; color:#fff; border:1px solid #388e3c; font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; font-size:11px;">
                        Save
                    </button>
                    <span id="pvpm-history-limit-msg" style="color:#66bb6a; font-size:11px;"></span>
                </div>
                <div class="pvpm-settings-row" style="display:flex; align-items:flex-start; gap:10px; font-size:12px; margin-top:8px;">
                    <input id="pvpm-af-coord-toggle" type="checkbox" ${autoFarmCoordinatorEnabled ? 'checked' : ''}
                           style="cursor:pointer; accent-color:#2e7d32; margin-top:2px; flex-shrink:0;">
                    <div>
                        <label for="pvpm-af-coord-toggle" style="color:#999; cursor:pointer;">Coordinate Auto Farm with PvE / Dungeon Manager</label>
                        <div style="color:#555; font-size:11px; margin-top:2px;">Recommended. Turn Auto Farm OFF during runs and restore it after, cooperating with PvE Manager and Dungeon Manager if they're open in other tabs. Turn this OFF only if you want PvP Manager to ignore Auto Farm entirely - in that case you must manage it yourself and concurrent runs from other scripts may conflict.</div>
                    </div>
                </div>
            </div>
        `;

        gui.innerHTML = `
            <!-- Header (drag handle). Sibling of pvpm-body so it stays put while the body scrolls. -->
            <div class="pvpm-header" style="flex:none; padding:14px 14px 8px 14px; background:#1c1c1c; border-bottom:1px solid #2a2a2a; cursor:grab;">
                <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:5px;">
                    <div style="font-size:14px; font-weight:bold; color:#2196f3; letter-spacing:0.5px;">⚔️ Empire Tools - PvP Manager</div>
                    <div style="display:flex; gap:14px; align-items:center;">
                        <span id="pvpm-history" style="cursor:pointer; font-size:13px;" title="Match History">📊</span>
                        <span id="pvpm-copylog" style="cursor:pointer; font-size:11px; color:#777; text-decoration:underline;">Copy Log</span>
                        <span id="pvpm-clearlog" style="cursor:pointer; font-size:11px; color:#777; text-decoration:underline;">Clear Log</span>
                        <span id="pvpm-resetsession" style="cursor:pointer; font-size:11px; color:#777; text-decoration:underline;">Reset W/L</span>
                        <span id="pvpm-minimize" style="cursor:pointer; font-size:20px; font-weight:bold; color:#666; line-height:1;">×</span>
                    </div>
                </div>
                <div style="font-size:12px; color:#777;">
                    <strong style="color:#999;">Status:</strong>
                    <span id="pvpm-status" style="font-weight:bold; color:${statusColor};">${currentStatus}</span>
                    ${matchCount > 0 ? `&nbsp;·&nbsp;<span style="color:#777;">Matches this session: <strong style="color:#ccc;">${matchCount}</strong></span>&nbsp;·&nbsp;<span style="color:#777;">W/L: <strong style="color:#66bb6a;">${winCount}W</strong>/<strong style="color:#ef5350;">${lossCount}L</strong>${matchCount > 0 ? ` <span style="color:#aaa;">(${Math.round((winCount / matchCount) * 100)}%)</span>` : ''}</span>` : ''}
                </div>
            </div>

            <!-- Scrollable body. Sibling of the header so the header (drag handle) stays put. -->
            <div class="pvpm-body" style="flex:1 1 auto; min-height:0; overflow-y:auto; padding:9px 14px 14px 14px;">
                <!-- Tab strip -->
                <div class="pvpm-tabs" style="display:flex; gap:3px; padding:0 4px; margin-bottom:0;">
                    ${tabBtnHtml('battle',   '⚔️ Battle')}
                    ${tabBtnHtml('strategy', '🎯 Strategy')}
                    ${tabBtnHtml('settings', '⚙️ Settings')}
                </div>
                <div style="border-top:1px solid #1976d2; margin-bottom:10px;"></div>

                <!-- Tab body -->
                ${currentTab === 'battle'   ? battleTabBody   : ''}
                ${currentTab === 'strategy' ? strategyTabBody : ''}
                ${currentTab === 'settings' ? settingsTabBody : ''}
            </div>
        `;

        // Re-attach the persistent HP bar container and logBox INSIDE the
        // scrollable body so they live below the tab content (and scroll
        // with it if the body overflows). They must NOT be siblings of the
        // header or they would render outside the rounded-corner clip.
        const _body = gui.querySelector('.pvpm-body');
        _body.appendChild(hpBarContainer);
        _body.appendChild(logBox);
        // Restore scroll position that was captured before the innerHTML wipe.
        // If the user was pinned to bottom, re-pin; otherwise restore exact position.
        // _programmaticScroll was set to true before the innerHTML wipe to suppress
        // spurious scroll events from detach/reattach; we keep it true through the
        // restore and only release it after the rAF fires.
        const _restoreScroll = () => {
            _programmaticScroll = true;
            if (_wasAtBottom) {
                logBox.scrollTop = logBox.scrollHeight;
            } else {
                logBox.scrollTop = _savedLogScroll;
            }
        };
        _restoreScroll();
        requestAnimationFrame(() => {
            _restoreScroll();
            _programmaticScroll = false;
        });

        /* -- Tab strip (always present) -- */
        gui.querySelectorAll('.pvpm-tab').forEach(el => {
            el.addEventListener('click', () => {
                const next = el.getAttribute('data-tab');
                if (!next || next === currentTab) return;
                currentTab = next;
                try { localStorage.setItem(ACTIVE_TAB_KEY, currentTab); } catch (e) {}
                renderGUI();
            });
        });

        /* -- Header buttons (always present) -- */
        document.getElementById('pvpm-minimize').onclick = () => {
            minimized = true;
            localStorage.setItem(UI_MIN_KEY, 'true');
            renderGUI();
        };
        document.getElementById('pvpm-clearlog').onclick = clearLogs;
        document.getElementById('pvpm-history').onclick = openHistoryModal;
        document.getElementById('pvpm-resetsession').onclick = () => {
            resetSession();
            addLog('Session W/L reset.', { color: '#aaa' });
        };

        /* -- Battle tab: Start / Stop -- */
        if (currentTab === 'battle') {
            if (running) {
                document.getElementById('pvpm-stop').onclick = () => {
                    if (!stopAfterMatch) {
                        stopAfterMatch = true;
                        addLog('Stop requested - will stop after current match finishes.', { color: '#ffb74d' });
                        renderGUI();
                    } else {
                        stopFlag = true;
                        addLog('Immediate stop requested - halting now.', { color: '#ef9a9a' });
                    }
                };
            } else {
                document.getElementById('pvpm-start').onclick = () => runAutomation();
            }
        }

        /* -- Strategy tab: master toggle + per-entry editors -- */
        if (currentTab === 'strategy') {
            const masterEl = document.getElementById('pvpm-strategy-enabled');
            if (masterEl) masterEl.onchange = (e) => {
                strategyData.enabled = !!e.target.checked;
                saveStrategy();
                addLog(strategyData.enabled
                    ? 'Solo Strategy ENABLED - bot will pick skills from your strategy on each turn.'
                    : 'Solo Strategy DISABLED - server auto-resolve will be used as before.',
                    { color: strategyData.enabled ? '#81d4fa' : '#aaa' });
            };

            const wireInfoToggle = (btnId, boxId) => {
                const btn = document.getElementById(btnId);
                const box = document.getElementById(boxId);
                if (!btn || !box) return;
                btn.onclick = () => {
                    const isOpen = !box.hasAttribute('hidden');
                    if (isOpen) {
                        box.setAttribute('hidden', '');
                        btn.setAttribute('aria-expanded', 'false');
                    } else {
                        box.removeAttribute('hidden');
                        btn.setAttribute('aria-expanded', 'true');
                    }
                };
            };
            wireInfoToggle('pvpm-strategy-info-btn', 'pvpm-strategy-info');

            const addEl = document.getElementById('pvpm-strategy-add');
            if (addEl) addEl.onclick = () => {
                const skills = scrapeSkillList();
                const defaultId = skills.length ? skills[0].id : FALLBACK_SKILL_ID;
                strategyData.entries.push({
                    skillId: defaultId,
                    condition: { type: 'always', op: '>=', value: 0 },
                    countTarget: 1,
                    recycle: true
                });
                saveStrategy();
                renderGUI();
            };

            gui.querySelectorAll('.pvpm-strat-skill').forEach(el => {
                el.onchange = (e) => {
                    const i = parseInt(el.getAttribute('data-idx'), 10);
                    if (!strategyData.entries[i]) return;
                    strategyData.entries[i].skillId = String(e.target.value);
                    saveStrategy();
                    renderGUI();  // refresh the row so the skill icon updates
                };
            });
            gui.querySelectorAll('.pvpm-strat-cond-type').forEach(el => {
                el.onchange = (e) => {
                    const i = parseInt(el.getAttribute('data-idx'), 10);
                    if (!strategyData.entries[i]) return;
                    strategyData.entries[i].condition.type = String(e.target.value);
                    // Default the operator/value sensibly when switching types
                    if (!strategyData.entries[i].condition.op)    strategyData.entries[i].condition.op = '>=';
                    if (!Number.isFinite(strategyData.entries[i].condition.value)) strategyData.entries[i].condition.value = 0;
                    saveStrategy();
                    renderGUI();
                };
            });
            gui.querySelectorAll('.pvpm-strat-cond-op').forEach(el => {
                el.onchange = (e) => {
                    const i = parseInt(el.getAttribute('data-idx'), 10);
                    if (!strategyData.entries[i]) return;
                    strategyData.entries[i].condition.op = String(e.target.value);
                    saveStrategy();
                };
            });
            gui.querySelectorAll('.pvpm-strat-cond-val').forEach(el => {
                el.onchange = (e) => {
                    const i = parseInt(el.getAttribute('data-idx'), 10);
                    if (!strategyData.entries[i]) return;
                    strategyData.entries[i].condition.value = Number(e.target.value) || 0;
                    saveStrategy();
                };
            });
            gui.querySelectorAll('.pvpm-strat-count-mode').forEach(el => {
                el.onchange = (e) => {
                    const i = parseInt(el.getAttribute('data-idx'), 10);
                    if (!strategyData.entries[i]) return;
                    const mode = String(e.target.value);
                    const prev = strategyData.entries[i].countTarget;
                    if (mode === 'forever' || mode === 'until_fail') {
                        strategyData.entries[i].countTarget = mode;
                    } else {
                        // 'fixed' - restore the previous numeric count if there was one,
                        // otherwise default to 1.
                        strategyData.entries[i].countTarget = (typeof prev === 'number' && prev > 0) ? prev : 1;
                    }
                    saveStrategy();
                    renderGUI();
                };
            });
            gui.querySelectorAll('.pvpm-strat-count').forEach(el => {
                el.onchange = (e) => {
                    const i = parseInt(el.getAttribute('data-idx'), 10);
                    if (!strategyData.entries[i]) return;
                    const n = parseInt(e.target.value, 10);
                    strategyData.entries[i].countTarget = (Number.isFinite(n) && n > 0) ? n : 1;
                    saveStrategy();
                    // No re-render needed: still in 'fixed' mode, only the number changed.
                };
            });
            gui.querySelectorAll('.pvpm-strat-recycle').forEach(el => {
                el.onchange = (e) => {
                    const i = parseInt(el.getAttribute('data-idx'), 10);
                    if (!strategyData.entries[i]) return;
                    strategyData.entries[i].recycle = !!e.target.checked;
                    saveStrategy();
                };
            });
            gui.querySelectorAll('.pvpm-strat-force').forEach(el => {
                el.onchange = (e) => {
                    const i = parseInt(el.getAttribute('data-idx'), 10);
                    if (!strategyData.entries[i]) return;
                    strategyData.entries[i].forceQuota = !!e.target.checked;
                    saveStrategy();
                };
            });
            gui.querySelectorAll('.pvpm-strat-up').forEach(el => {
                el.onclick = () => {
                    const i = parseInt(el.getAttribute('data-idx'), 10);
                    if (i <= 0 || !strategyData.entries[i]) return;
                    const tmp = strategyData.entries[i - 1];
                    strategyData.entries[i - 1] = strategyData.entries[i];
                    strategyData.entries[i] = tmp;
                    saveStrategy();
                    renderGUI();
                };
            });
            gui.querySelectorAll('.pvpm-strat-down').forEach(el => {
                el.onclick = () => {
                    const i = parseInt(el.getAttribute('data-idx'), 10);
                    if (i < 0 || i >= strategyData.entries.length - 1) return;
                    const tmp = strategyData.entries[i + 1];
                    strategyData.entries[i + 1] = strategyData.entries[i];
                    strategyData.entries[i] = tmp;
                    saveStrategy();
                    renderGUI();
                };
            });
            gui.querySelectorAll('.pvpm-strat-del').forEach(el => {
                el.onclick = () => {
                    const i = parseInt(el.getAttribute('data-idx'), 10);
                    if (!strategyData.entries[i]) return;
                    strategyData.entries.splice(i, 1);
                    saveStrategy();
                    renderGUI();
                };
            });
        }

        /* -- Settings tab handlers -- */
        if (currentTab === 'settings') {
            document.getElementById('pvpm-log-limit-save').onclick = () => {
                const inp = document.getElementById('pvpm-log-limit-input');
                const msg = document.getElementById('pvpm-log-limit-msg');
                const val = parseInt(inp.value, 10);
                if (isNaN(val) || val < 10 || val > 2000) {
                    if (msg) { msg.style.color = '#ef5350'; msg.textContent = 'Enter 10-2000'; }
                    return;
                }
                logLimit = val;
                localStorage.setItem(LOG_LIMIT_KEY, String(logLimit));

                // Trim existing logs if over new limit
                _programmaticScroll = true;
                while (logHistory.length > logLimit) {
                    logHistory.shift();
                    if (logBox.firstChild) logBox.removeChild(logBox.firstChild);
                }
                _programmaticScroll = false;
                persistLogs();

                if (msg) { msg.style.color = '#66bb6a'; msg.textContent = 'Saved!'; setTimeout(() => { if (msg) msg.textContent = ''; }, 1500); }
            };

            document.getElementById('pvpm-persist-toggle').onchange = (e) => {
                persistLogsEnabled = e.target.checked;
                localStorage.setItem(PERSIST_LOG_KEY, String(persistLogsEnabled));
                if (!persistLogsEnabled) {
                    localStorage.removeItem(LOG_STORE_KEY);
                } else {
                    persistLogs();
                }
            };

            document.getElementById('pvpm-history-limit-save').onclick = () => {
                const inp = document.getElementById('pvpm-history-limit-input');
                const msg = document.getElementById('pvpm-history-limit-msg');
                const val = parseInt(inp.value, 10);
                if (isNaN(val) || val < 10 || val > 10000) {
                    if (msg) { msg.style.color = '#ef5350'; msg.textContent = 'Enter 10-10000'; }
                    return;
                }
                historyLimit = val;
                localStorage.setItem(HISTORY_LIMIT_KEY, String(historyLimit));
                // Trim existing history if over new limit
                while (matchHistory.length > historyLimit) matchHistory.shift();
                persistHistory();
                if (msg) { msg.style.color = '#66bb6a'; msg.textContent = 'Saved!'; setTimeout(() => { if (msg) msg.textContent = ''; }, 1500); }
            };

            document.getElementById('pvpm-af-coord-toggle').onchange = (e) => {
                autoFarmCoordinatorEnabled = e.target.checked;
                localStorage.setItem(AF_COORD_ENABLED_KEY, String(autoFarmCoordinatorEnabled));
            };
        }

        document.getElementById('pvpm-copylog').onclick = () => {
            const plainText = logHistory.map(e => typeof e === 'string' ? e : e.plain).join('\n');

            // Build rich HTML for clipboard: each log line becomes a styled <div>
            // so hyperlinks + colors survive into Discord, Word, etc.
            // Strip <img> tags (and their wrapping <a> if the <a> only contains the <img>)
            // so profile pictures don't render at full size and consume entire pages in documents.
            const stripImgs = html => html
                .replace(/<a\b[^>]*>\s*<img\b[^>]*>\s*<\/a>/gi, '')
                .replace(/<img\b[^>]*>/gi, '');
            const richLines = logHistory.map(e => {
                if (typeof e === 'string') return `<div style="font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;font-size:11px;color:#ddd;">${escapeHtml(e)}</div>`;
                const style = `font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;font-size:11px;${e.color ? 'color:' + e.color + ';' : 'color:#ddd;'}`;
                return `<div style="${style}">${stripImgs(e.html)}</div>`;
            });
            const richHtml = richLines.join('\n');

            // Try the modern ClipboardItem API (text/html + text/plain).
            // Rich-text-aware apps (Discord, Word, Notepad++) get styled HTML with links;
            // plain-text targets (.txt, terminal) get the plain fallback automatically.
            if (typeof ClipboardItem !== 'undefined') {
                const htmlBlob  = new Blob([richHtml],  { type: 'text/html' });
                const plainBlob = new Blob([plainText], { type: 'text/plain' });
                navigator.clipboard.write([new ClipboardItem({
                    'text/html':  htmlBlob,
                    'text/plain': plainBlob
                })]).catch(() => {
                    // Fallback: plain text only
                    navigator.clipboard.writeText(plainText).catch(() => {});
                });
            } else {
                // Legacy fallback for browsers without ClipboardItem
                navigator.clipboard.writeText(plainText).catch(() => {
                    const ta = document.createElement('textarea');
                    ta.value = plainText;
                    ta.style.cssText = 'position:fixed;left:-9999px';
                    document.body.appendChild(ta);
                    ta.select();
                    document.execCommand('copy');
                    document.body.removeChild(ta);
                });
            }

            const btn = document.getElementById('pvpm-copylog');
            if (btn) { btn.textContent = 'Copied!'; setTimeout(() => { if (btn) btn.textContent = 'Copy Log'; }, 1500); }
        };

        makeDraggable(gui);

        requestAnimationFrame(() => {
            if (!_skip) { gui.style.left = (prevRight - gui.getBoundingClientRect().width) + 'px'; gui.style.top = prevTop + 'px'; savePos(); }
            clampToViewport();
        });
    }

    /* =========================================================
        AUTOMATION CORE
    ========================================================= */

    /**
     * POST to pvp_matchmake.php and extract the resulting match_id.
     * Returns the numeric match ID, or null on failure.
     */
    async function findSoloMatch() {
        let data;
        try {
            data = await pvpPost(MATCHMAKE_URL, { ladder: 'solo' });
        } catch (e) {
            addLog(`ERROR: Matchmake request failed - ${e.message}`, { color: '#ef5350' });
            return null;
        }

        // Check for server-reported errors
        if (data && data.status && data.status !== 'success') {
            const msg = data.message || data.status;
            addLog(`ERROR: Matchmake failed - ${msg}`, { color: '#ef5350' });
            return null;
        }

        // Try extracting match_id from the redirect URL in the response
        if (data && data.redirect) {
            const m = String(data.redirect).match(/match_id=(\d+)/i);
            if (m) return parseInt(m[1], 10);
        }

        // Fallback: re-fetch pvp.php and scan for any pvp_battle.php?match_id= link
        try {
            const html = await fetchPageHtml(PVP_URL);
            const m = html.match(/pvp_battle\.php\?match_id=(\d+)/i);
            if (m) return parseInt(m[1], 10);
        } catch (e) {
            addLog(`ERROR: Could not scrape match ID - ${e.message}`, { color: '#ef5350' });
        }

        addLog('ERROR: Match found but could not determine match ID.', { color: '#ef5350' });
        return null;
    }

    /**
     * Enable auto-play for a match.
     * Also returns the full initial battle state (teams, logs, etc.).
     */
    async function setAutoPlay(matchId, sinceLogId = 0) {
        return pvpPost(BATTLE_ACT_URL, {
            match_id:     matchId,
            since_log_id: sinceLogId,
            action:       'set_solo_control_mode',
            control_mode: 'auto'
        });
    }

    /**
     * UNUSED. The server rejects control_mode='manual' with
     * "Unsupported solo control mode." The Solo Strategy executor
     * therefore skips the mode POST entirely and relies on the
     * default (manual) state of newly-matchmade matches. Left
     * defined in case a future server revision recognizes 'manual'.
     */
    // eslint-disable-next-line no-unused-vars
    async function setManualPlay(matchId, sinceLogId = 0) {
        return pvpPost(BATTLE_ACT_URL, {
            match_id:     matchId,
            since_log_id: sinceLogId,
            action:       'set_solo_control_mode',
            control_mode: 'manual'
        });
    }

    /**
     * Poll pvp_battle_state.php for new logs and current match state.
     */
    async function pollMatchState(matchId, sinceLogId) {
        const url = `${BATTLE_STATE_URL}?match_id=${encodeURIComponent(matchId)}&since_log_id=${encodeURIComponent(sinceLogId)}`;
        return pvpGetJson(url);
    }

    /* =========================================================
        PLAYER PROFILE FETCHING
    ========================================================= */

    /**
     * Fetch player.php?pid=<uid> and parse relevant PvP fields.
     * Returns an object with: level, pvpRank, pvpPoints, pvpParty
     * All fields default to null if not found.
     */
    async function fetchPlayerProfile(uid) {
        const result = { level: null, pvpRank: null, pvpPoints: null, pvpParty: null, avatarUrl: null };
        try {
            const html = await fetchPageHtml(`${PROFILE_URL}?pid=${encodeURIComponent(uid)}`);
            const doc  = new DOMParser().parseFromString(html, 'text/html');

            // Avatar - <div class="avatar"><img src="uploads/avatars/user_XXXX.jpg"></div>
            const avatarImg = doc.querySelector('.avatar img');
            if (avatarImg) {
                const src = avatarImg.getAttribute('src');
                if (src) {
                    // Resolve relative URLs against the site origin
                    result.avatarUrl = src.startsWith('http') ? src : `https://demonicscans.org/${src.replace(/^\//, '')}`;
                }
            }

            // Level - <div class="muted">Level 3,627</div>
            for (const el of doc.querySelectorAll('.muted')) {
                const m = el.textContent.trim().match(/^Level ([\d,]+)$/);
                if (m) { result.level = m[1]; break; }
            }

            // Profile pills - <div class="profile-pill"><strong>PvP Rank:</strong> <span>Novice</span></div>
            for (const pill of doc.querySelectorAll('.profile-pill')) {
                const strong = pill.querySelector('strong');
                const span   = pill.querySelector('span');
                if (!strong || !span) continue;
                const label = strong.textContent.trim();
                const val   = span.textContent.trim();
                if (label === 'PvP Rank:')   result.pvpRank   = val;
                if (label === 'PvP Points:') result.pvpPoints = val;
                if (label === 'PvP Party:')  result.pvpParty  = val;
            }
        } catch (e) {
            console.warn('[PvP Manager] fetchPlayerProfile failed for uid', uid, e);
        }
        return result;
    }

    /**
     * Build a compact rank/points string from a profile result.
     * e.g. "Novice 310 pts"
     */
    function formatRankStr(profile) {
        const pts  = profile.pvpPoints;
        const rank = profile.pvpRank;
        if (pts && rank)  return `${rank} ${pts} pts`;
        if (pts)          return `${pts} pts`;
        if (rank)         return rank;
        return null;
    }

    /**
     * Build a hyperlinked, detail-rich player label for the log.
     * Format: [GUILD] Name (Lv X,XXX Role) · Rank XXX pts · Party: Name
     */
    function buildPlayerHtml(player, profile) {
        const uid  = player.user_id || '';
        const name = player.username || `User ${uid}`;
        const role = player.role     || '';
        const href = `${PROFILE_URL}?pid=${encodeURIComponent(uid)}`;

        // Avatar thumbnail (linked to profile)
        const avatarPart = profile?.avatarUrl
            ? `<a href="${href}" target="_blank" style="text-decoration:none;"><img src="${escapeHtml(profile.avatarUrl)}" alt="" style="width:20px; height:20px; border-radius:50%; vertical-align:middle; margin-right:5px; object-fit:cover; border:1px solid #555;"></a>`
            : '';

        const link = `<a href="${href}" target="_blank" style="color:#90caf9; text-decoration:none;">${escapeHtml(name)}</a>`;

        // "(Lv X,XXX Role)" combined
        const lvRole = [];
        if (profile?.level) lvRole.push(`Lv ${escapeHtml(profile.level)}`);
        if (role) lvRole.push(escapeHtml(role));
        const lvRolePart = lvRole.length ? ` <span style="color:#888;">(${lvRole.join(' ')})</span>` : '';

        const extras = [];
        const rankStr = profile ? formatRankStr(profile) : null;
        if (rankStr)           extras.push(`<span style="color:#b0bec5;">${escapeHtml(rankStr)}</span>`);
        if (profile?.pvpParty) extras.push(`<span style="color:#ce93d8;">Party: ${escapeHtml(profile.pvpParty)}</span>`);

        const extraPart = extras.length ? ` <span style="color:#444;">·</span> ` + extras.join(` <span style="color:#444;">·</span> `) : '';
        return `${avatarPart}${link}${lvRolePart}${extraPart}`;
    }

    function buildPlayerPlain(player, profile) {
        const name = player.username || `User ${player.user_id}`;
        const role = player.role     || '';

        // "(Lv X,XXX Role)"
        const lvRole = [];
        if (profile?.level) lvRole.push(`Lv ${profile.level}`);
        if (role) lvRole.push(role);
        const lvRolePart = lvRole.length ? ` (${lvRole.join(' ')})` : '';

        const extras = [];
        const rankStr = profile ? formatRankStr(profile) : null;
        if (rankStr)           extras.push(rankStr);
        if (profile?.pvpParty) extras.push(`Party: ${profile.pvpParty}`);
        return `${name}${lvRolePart}${extras.length ? ' · ' + extras.join(' · ') : ''}`;
    }

		/** Fetch both player profiles in parallel and log the match intro as 5 animated lines.
		 *  The entire intro sequence completes within ~2 seconds. */
		async function logMatchPlayers(state) {
				const ally  = state.teams?.ally?.players_by_num?.['1'];
				const enemy = state.teams?.enemy?.players_by_num?.['1'];
				if (!ally || !enemy) return;

				// Fetch both profiles concurrently
				const [allyProfile, enemyProfile] = await Promise.all([
						fetchPlayerProfile(ally.user_id),
						fetchPlayerProfile(enemy.user_id)
				]);

				// Populate HP bar avatars now that we have profile data
				setHpBarAvatars(
					ally.user_id, enemy.user_id,
					allyProfile?.avatarUrl || null,
					enemyProfile?.avatarUrl || null
				);

				// Capture data for match history (consumed when match ends)
				pendingMatchCapture = {
					enemy: {
						name: enemy.username || `User ${enemy.user_id}`,
						uid: enemy.user_id || null,
						level: enemyProfile?.level || null,
						role: enemy.role || null,
						rank: enemyProfile?.pvpRank || null,
						points: enemyProfile?.pvpPoints || null,
						party: enemyProfile?.pvpParty || null,
						avatarUrl: enemyProfile?.avatarUrl || null,
					},
					pointsBefore: statPoints !== '-' ? parseInt(String(statPoints).replace(/,/g, ''), 10) || null : null,
				};

				const [introLineTop, introLineBottom] = randomBattleIntroPair();

				// ── Animated intro sequence (~2s total) ──
				// Line 1: top intro banner  (0ms-350ms reveal, then 50ms pause)
				await addLogTypewriter(introLineTop, {
						html: `<span style="color:#ffb74d; font-weight:bold; letter-spacing:1px;">── ${escapeHtml(formatBannerText(introLineTop))} ──</span>`,
						color: '#ffb74d', noTimestamp: true
				}, 350);
				await sleep(50);

				// Line 2: ally player info  (400ms-700ms reveal, then 50ms pause)
				const allyPlain = buildPlayerPlain(ally, allyProfile);
				const allyHtml  = `<span style="color:#42a5f5;">${buildPlayerHtml(ally, allyProfile)}</span>`;
				await addLogTypewriter(allyPlain, { html: allyHtml, color: '#e3f2fd', noTimestamp: true }, 300);
				await sleep(50);

				// Line 3: VERSUS banner     (750ms-1100ms reveal, then 100ms pause)
				await addLogTypewriter('── V E R S U S ──', {
						html: '<span style="color:#ffb74d; font-weight:bold; letter-spacing:2px;">── V E R S U S ──</span>',
						color: '#ffb74d', noTimestamp: true
				}, 350);
				await sleep(100);

				// Line 4: enemy player info (1200ms-1500ms reveal, then 50ms pause)
				const enemyPlain = buildPlayerPlain(enemy, enemyProfile);
				const enemyHtml  = `<span style="color:#ef5350;">${buildPlayerHtml(enemy, enemyProfile)}</span>`;
				await addLogTypewriter(enemyPlain, { html: enemyHtml, color: '#e3f2fd', noTimestamp: true }, 300);
				await sleep(50);

				// Line 5: bottom intro banner (1550ms-1900ms reveal, then burst flash)
				await addLogTypewriter(introLineBottom, {
						html: `<span style="color:#ffb74d; font-weight:bold; letter-spacing:1px;">── ${escapeHtml(formatBannerText(introLineBottom))} ──</span>`,
						color: '#ffb74d', noTimestamp: true
				}, 350);
				// Apply a burst/glow flash to the final line - purely cosmetic CSS
				// animation, adds no await time to the sequence.
				{
					const lastDiv = logBox.lastElementChild;
					if (lastDiv) {
						const span = lastDiv.querySelector('span');
						if (span) {
							span.style.animation = 'pvpm-intro-burst 0.5s ease-out';
							span.addEventListener('animationend', function handler() {
								span.removeEventListener('animationend', handler);
								span.style.animation = '';
							});
						}
					}
				}
		}

    /* =========================================================
        AUTOMATION LOOP
    ========================================================= */

    async function runWithAutoFarmGuard(innerRunFn) {
        if (!autoFarmCoordinatorEnabled) {
            await innerRunFn();
            return;
        }

        const runId = `pvp-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

        let acquired = null;
        try { acquired = await afAcquire(runId); }
        catch (e) { addLog(`⚠️ Auto Farm coordinator error (acquire): ${e.message}`); }

        if (acquired) {
            if (acquired.wasEmpty && acquired.originalWasOn === true) {
                addLog('⏸️ Auto Farm was ON - turned OFF for run (will restore after).');
            } else if (acquired.wasEmpty && acquired.originalWasOn === null) {
                addLog('⚠️ Could not determine Auto Farm state - proceeding.');
            } else if (!acquired.wasEmpty) {
                addLog('🔗 Auto Farm already managed by another run - joined coordinator.');
            }
        }

        const hbHandle = setInterval(() => { afHeartbeat(runId).catch(() => {}); }, AF_HEARTBEAT_MS);

        try {
            await innerRunFn();
        } finally {
            clearInterval(hbHandle);
            try {
                const released = await afRelease(runId);
                if (released && released.restored) {
                    if (released.ok) addLog('▶️ Auto Farm restored to ON.');
                    else             addLog('⚠️ Failed to restore Auto Farm to ON - please re-enable manually.');
                }
            } catch (e) {
                addLog(`⚠️ Auto Farm coordinator error (release): ${e.message}`);
            }
        }
    }

    async function runAutomation() {
        await runWithAutoFarmGuard(async () => {
            if (running) return;
            running        = true;
            stopFlag       = false;
            stopAfterMatch = false;
            currentStatus = 'RUNNING';
            renderGUI();
            addLog('PvP run started.', { color: '#aaa' });
            _keepAlive.start();

            try {
                // ── Self-healing scheduler ───────────────────────────────────
                // _cycleErrors: consecutive exceptions thrown by runOneCycle.
                //   - Reset to 0 on any clean cycle (true or retry sentinel).
                //   - If it reaches 5 the bot stops to avoid a runaway loop.
                // _lastCycleTs: wall-clock timestamp updated at each cycle start.
                //   Used by the watchdog below.
                let _cycleErrors = 0;
                let _lastCycleTs = Date.now();

                // Watchdog: every 90 s, if automation is still marked running but
                // no cycle has completed for > 2 min, log a warning so the user
                // can see the bot is stalled.  The fetch timeout (pvpFetch,
                // FETCH_TIMEOUT_MS) should prevent the common hang, but the
                // watchdog gives an extra safety net with visibility.
                const _wdHandle = setInterval(function () {
                    if (!running) return;
                    const idleMs = Date.now() - _lastCycleTs;
                    if (idleMs > 120_000) {
                        addLog(
                            '🔍 Watchdog: no cycle activity for ' +
                            Math.round(idleMs / 1000) + 's — possible stall detected.',
                            { color: '#ff7043' }
                        );
                    }
                }, 90_000);

                while (!stopFlag) {
                    _lastCycleTs = Date.now();
                    let _cycleResult;
                    try {
                        _cycleResult = await runOneCycle();
                        // runOneCycle completed cleanly — reset error counter
                        _cycleErrors = 0;
                    } catch (e) {
                        // Unexpected exception thrown by runOneCycle (not a handled
                        // network error, which runOneCycle catches internally).
                        _cycleErrors++;
                        addLog(
                            '⚠️ Cycle exception (' + _cycleErrors + '/5): ' +
                            e.message + ' — recovering in 10s',
                            { color: '#ef5350' }
                        );
                        console.error('[PvP Manager] cycle exception', e);
                        if (_cycleErrors >= 5) {
                            addLog('❌ 5 consecutive cycle exceptions — stopping.', { color: '#ef5350' });
                            break;
                        }
                        await sleep(10_000);
                        continue; // try next cycle
                    }

                    if (_cycleResult === 'retry') {
                        // Transient error (network blip, matchmake fail, etc.)
                        // Log already emitted by runOneCycle — just wait and retry.
                        addLog('🔄 Scheduler: retrying in 10s...', { color: '#ffb74d' });
                        await sleep(10_000);
                        continue;
                    }

                    if (_cycleResult !== true) {
                        // Intentional stop (false): no tokens, stopAfterMatch, stopFlag
                        break;
                    }
                    // _cycleResult === true → keep looping
                }

                clearInterval(_wdHandle);
            } catch (e) {
                addLog(`FATAL: ${e.message}`, { color: '#ef5350' });
                console.error('[PvP Manager]', e);
            }

            running        = false;
            stopFlag       = false;
            stopAfterMatch = false;
            currentStatus  = 'IDLE';
            renderGUI();
            addLog('Run stopped.', { color: '#aaa' });
            _keepAlive.stop();
        });
    }

    /**
     * Run one full match cycle (find → auto play → poll until end → check tokens).
     * Returns true to continue, false to stop the outer loop.
     */
    async function runOneCycle() {
        /* ── 1. Find / enter match ─────────────────────────────── */
        addLog('Finding solo match...');
        const matchId = await findSoloMatch();
        // Return 'retry' (not false) so the outer scheduler loop waits 10 s and
        // tries again rather than stopping the bot on a transient network error.
        if (!matchId) { addLog('⚠️ Could not obtain match ID — retrying in 10s', { color: '#ffb74d' }); return 'retry'; }
        currentMatchId = matchId;
        const matchUrl  = `https://demonicscans.org/pvp_battle.php?match_id=${matchId}`;
        const matchHtml = `Match found - ID <a href="${matchUrl}" target="_blank" style="color:#81d4fa; text-decoration:underline;">${matchId}</a>.`;
        addLog(`Match found - ID ${matchId}.`, { html: matchHtml, color: '#81d4fa' });

        currentStatus = 'BATTLE';
        updateStatusUI();

        /* ── 2. Enable control mode & capture initial state ────── */
        // When strategy is on we want manual control. The server has no
        // explicit "manual" mode (set_solo_control_mode rejects it with
        // "Unsupported solo control mode."), but newly-matchmade matches
        // default to manual play - so we just skip the mode POST and grab
        // the initial state via pollMatchState (a GET).
        const strategyOn = !!(strategyData && strategyData.enabled && strategyData.entries && strategyData.entries.length);
        if (strategyOn) {
            strategyRuntime = newStrategyRuntime();
            addLog('Solo Strategy enabled - bot will pick skills from your strategy on each turn.', { color: '#81d4fa' });
        }
        let state;
        try {
            state = strategyOn
                ? await pollMatchState(matchId, 0)
                : await setAutoPlay(matchId, 0);
        } catch (e) {
            addLog(`ERROR: Could not ${strategyOn ? 'fetch initial state' : 'send auto play'} - ${e.message} — retrying in 10s`, { color: '#ef5350' });
            return 'retry';
        }

        if (!state || state.ok === false) {
            const err = state?.error || 'no details';
            addLog(`ERROR: ${strategyOn ? 'Initial state fetch' : 'Auto play'} rejected - ${err} — retrying in 10s`, { color: '#ef5350' });
            return 'retry';
        }

        /* ── 3. Capture player names for log tokenization ────── */
        // Reset live stat cache for this match so stale data from a prior
        // match is never shown.
        liveStat = {
            myAtk: null, myDef: null, myDmg: null, myRet: null, myCls: null, myCrit: null,
            enAtk: null, enDef: null, enDmg: null, enRet: null, enCls: null, enCrit: null,
            turnBonus: null, globalPacing: null
        };
        {
            const a = state.teams?.ally?.players_by_num?.['1'];
            const e = state.teams?.enemy?.players_by_num?.['1'];
            if (a) { allyName  = a.username || null; allyUid  = a.user_id || null; }
            if (e) { enemyName = e.username || null; enemyUid = e.user_id || null; }
            // Capture class from the initial state — role is stable for the
            // whole match so one read here is enough.
            if (a && a.role) liveStat.myCls = a.role.toLowerCase();
            if (e && e.role) liveStat.enCls = e.role.toLowerCase();
            // Also persist class into bls_memory so history expand panels
            // can show CLASS for players we have fought before.
            try {
                const blsRaw = localStorage.getItem('bls_memory');
                const blsMem = blsRaw ? JSON.parse(blsRaw) : {};
                let blsChanged = false;
                if (a && a.user_id && a.role) {
                    const uid = String(a.user_id);
                    const cur = blsMem[uid] || {};
                    if (cur.playerClass !== a.role.toLowerCase()) {
                        blsMem[uid] = Object.assign({}, cur, { playerClass: a.role.toLowerCase(), name: a.username || cur.name || null });
                        blsChanged = true;
                    }
                }
                if (e && e.user_id && e.role) {
                    const uid = String(e.user_id);
                    const cur = blsMem[uid] || {};
                    if (cur.playerClass !== e.role.toLowerCase()) {
                        blsMem[uid] = Object.assign({}, cur, { playerClass: e.role.toLowerCase(), name: e.username || cur.name || null });
                        blsChanged = true;
                    }
                }
                if (blsChanged) localStorage.setItem('bls_memory', JSON.stringify(blsMem));
            } catch (_) {}
        }

        // Cache skill icons from state.me.skills so the strategy editor can
        // show thumbnails. Same data the in-game battle UI uses to render
        // .skillCard buttons. Runs whether or not strategy is enabled - the
        // cache benefits anyone using the strategy editor.
        cacheSkillImages(state);

        // One-time diagnostic: when strategy is on, log the bls_memory
        // contents for our two players so the user can verify the
        // Advantage Score conditions will have data to evaluate against.
        // Output goes to the browser console (not the in-panel log).
        if (strategyOn && isDebugLogsEnabled()) logBlsDiagnostic(allyUid, enemyUid);

        /* ── 4. Log player info with animated intro ────────────── */
        // Await the animated intro sequence (~2s) so it plays before battle logs appear.
        await logMatchPlayers(state);

        /* ── 4b. Initialize HP bars ────────────────────────────── */
        initHpBars(state);

        /* ── 5. Process logs from the auto play response ──────── */
        let lastLogId = Number(state.last_log_id ?? 0);
        if (Array.isArray(state.new_logs) && state.new_logs.length) {
            state.new_logs.forEach(e => appendBattleLog(e));
            smartScrollLog();
            // Write actor-attributed exchange scores into bls_memory so future
            // matches against the same opponents see fresh data without
            // requiring ogmaend to be running on pvp_battle.php.
            writeBlsFromLogs(state.new_logs, matchId);
        }
        updateHpFromState(state);

        let matchEnded = !!(state.match?.ended);

        /* ── 6. Poll until match ends ──────────────────────────── */
        let lastPoll = null;
        // For strategy mode: track the lastLogId at which we last POSTed a
        // turn action so we don't double-post within the same turn while
        // waiting for the server to advance.
        let lastActedLogId = -1;
        while (!matchEnded && !stopFlag) {
            await sleep(POLL_INTERVAL_MS);

            let poll;
            try {
                poll = await pollMatchState(matchId, lastLogId);
            } catch (e) {
                addLog(`WARN: Poll error - ${e.message}`, { color: '#ffb74d' });
                continue;
            }

            if (!poll || poll.ok === false) {
                addLog('WARN: Bad poll response, retrying...', { color: '#ffb74d' });
                continue;
            }

            lastPoll = poll;

            // Update HP bars from poll data
            updateHpFromState(poll);

            // Skill list / icons are stable across a match in normal play but
            // we re-cache defensively. The internal change-check skips writes
            // when nothing differs.
            cacheSkillImages(poll);

            if (Array.isArray(poll.new_logs) && poll.new_logs.length) {
                poll.new_logs.forEach(e => appendBattleLog(e));
                smartScrollLog();
                writeBlsFromLogs(poll.new_logs, matchId);
            }

            lastLogId  = Number(poll.last_log_id ?? lastLogId);
            matchEnded = !!(poll.match?.ended);

            // Strategy executor: when it's our turn and we haven't already
            // acted at this log id, evaluate the strategy and POST use_skill.
            if (strategyOn && !matchEnded && allyUid && poll.turn?.user_id &&
                String(poll.turn.user_id) === String(allyUid) &&
                lastLogId !== lastActedLogId) {
                try {
                    const action = pickStrategyAction(poll, strategyRuntime);
                    let skillId, targetKey, label;
                    if (action) {
                        skillId   = action.skillId;
                        targetKey = action.targetKey;
                        const _idxNum   = (typeof action.chosenIdx === 'number') ? (action.chosenIdx + 1) : null;
                        const _idxTag   = _idxNum != null ? `#${_idxNum}` : '#?';
                        const _statusTag = action.conditionStatus ? ` · ${action.conditionStatus}` : '';
                        const _noteTag  = (action.note && action.note !== 'matched') ? ` [${action.note}]` : '';
                        label     = `${_idxTag}${_statusTag}${_noteTag}: ${skillNameFor(skillId)}`;
                    } else if (enemyUid) {
                        skillId   = FALLBACK_SKILL_ID;
                        targetKey = `enemy:${enemyUid}`;
                        label     = `fallback: ${skillNameFor(FALLBACK_SKILL_ID)}`;
                        // One-time-per-match hint: if no entry uses the
                        // 'preceding_failed' (default-strategy) condition,
                        // tell the user we're using the built-in Slash
                        // fallback so they know they could configure one.
                        if (!strategyRuntime._defaultHintLogged) {
                            const hasDefault = (strategyData.entries || []).some(e => e && e.condition && e.condition.type === 'preceding_failed');
                            if (!hasDefault) {
                                addLog('Strategy: no default entry configured (no "When all preceding conditions fail" condition) - using built-in Slash fallback.', { color: '#ffb74d' });
                            }
                            strategyRuntime._defaultHintLogged = true;
                        }
                    } else {
                        addLog('Strategy: no enemy target available, skipping turn.', { color: '#ffb74d' });
                        skillId = null;
                    }
                    if (skillId !== null) {
                        // Render target_key with the opponent/self/ally name
                        // instead of the raw UID so the log reads naturally.
                        const [_tSide, _tUid] = String(targetKey || '').split(':');
                        let prettyTarget = targetKey;
                        if (_tSide === 'enemy' && enemyName)      prettyTarget = `enemy: ${enemyName}`;
                        else if (_tSide === 'self'  && allyName)  prettyTarget = `self: ${allyName}`;
                        else if (_tSide === 'ally'  && allyName && _tUid === String(allyUid || '')) prettyTarget = `ally: ${allyName}`;
                        addLog(`Strategy → ${label} on ${prettyTarget}`, { color: '#a5d6a7' });
                        const resp = await postUseSkill(matchId, lastLogId, skillId, targetKey);
                        lastActedLogId = lastLogId;
                        if (resp && resp.ok === false) {
                            addLog(`Strategy POST rejected - ${resp.error || 'no details'}`, { color: '#ef5350' });
                        }
                    }
                } catch (e) {
                    addLog(`Strategy POST failed - ${e.message}`, { color: '#ef5350' });
                }
            }
        }

        if (stopFlag) {
            const stopUrl  = `https://demonicscans.org/pvp_battle.php?match_id=${matchId}`;
            const stopHtml = `Match still in progress - <a href="${stopUrl}" target="_blank" style="color:#81d4fa; text-decoration:underline;">Match #${matchId}</a>`;
            addLog(`Match still in progress - Match #${matchId}`, { html: stopHtml, color: '#ffb74d' });
            currentMatchId = null;
            strategyRuntime = null;
            allyName = enemyName = allyUid = enemyUid = null;
            logDetectedResult = null;
            pendingMatchCapture = null;
            resetHpBars();
            return false;
        }

        /* ── 7. Match ended ────────────────────────────────────── */
        // ROOT CAUSE FIX (Problem 2): resetHpBars() zeroes the module-level
        // enemyHp / enemyHpMax variables.  addHistoryEntry() further below reads
        // those same variables, so without this snapshot it always writes null.
        // Capture final values NOW, before the reset, then pass snapshots to
        // addHistoryEntry() instead of the (by then zeroed) live variables.
        const _snapEnemyHp    = enemyHp;
        const _snapEnemyHpMax = enemyHpMax;
        resetHpBars();
        matchCount++;
        const endedMatchId = matchId;  // capture before clearing
        currentMatchId = null;
        allyName = enemyName = allyUid = enemyUid = null;

        // Detect win/loss - prefer log-based detection ("allied/enemy ... wins the room"),
        // fall back to server match state fields
        const lastMatch = lastPoll?.match || state?.match || null;
        let wonMatch, lostMatch, tiedMatch;
        if (logDetectedResult === 'win') {
            wonMatch = true; lostMatch = false; tiedMatch = false;
        } else if (logDetectedResult === 'tie') {
            wonMatch = false; lostMatch = false; tiedMatch = true;
        } else if (logDetectedResult === 'loss') {
            wonMatch = false; lostMatch = true; tiedMatch = false;
        } else {
            wonMatch  = lastMatch && (lastMatch.winner === 'ally' || lastMatch.winning_team === 'ally');
            lostMatch = lastMatch && (lastMatch.winner === 'enemy' || lastMatch.winning_team === 'enemy');
            tiedMatch = false;
        }
        logDetectedResult = null;  // reset for next match

        const endMatchUrl = `https://demonicscans.org/pvp_battle.php?match_id=${endedMatchId}`;
        const matchLink   = `<a href="${endMatchUrl}" target="_blank" style="color:inherit; text-decoration:underline; text-decoration-style:dotted;">Match #${matchCount}</a>`;
        if (wonMatch) {
            winCount++;
            addLog(`─── Match #${matchCount} ended - Victory! ───`, {
                html: `─── ${matchLink} ended - Victory! ───`, color: '#66bb6a' });
        } else if (tiedMatch) {
            lossCount++;
            addLog(`─── Match #${matchCount} ended - Tie (Defeat) ───`, {
                html: `─── ${matchLink} ended - Tie (Defeat) ───`, color: '#ef5350' });
        } else if (lostMatch) {
            lossCount++;
            addLog(`─── Match #${matchCount} ended - Defeat ───`, {
                html: `─── ${matchLink} ended - Defeat ───`, color: '#ef5350' });
        } else {
            // Could not determine result; count as loss to be safe
            lossCount++;
            addLog(`─── Match #${matchCount} ended ───`, {
                html: `─── ${matchLink} ended ───`, color: '#546e7a' });
        }
        saveSession();
        renderGUI();

        /* ── 8. Refresh stats & decide whether to continue ─────── */
        await refreshStats();
        updateStatsUI();

        // Record match history entry
        {
            const pointsAfter = statPoints !== '-' ? parseInt(String(statPoints).replace(/,/g, ''), 10) || null : null;
            const result = wonMatch ? 'win' : tiedMatch ? 'tie' : lostMatch ? 'loss' : 'unknown';
            const capture = pendingMatchCapture || {};
            addHistoryEntry({
                ts: Date.now(),
                matchId: endedMatchId,
                matchUrl: endMatchUrl,
                result,
                enemy: capture.enemy || { name: 'Unknown', uid: null, level: null, role: null, rank: null, points: null, party: null },
                pointsBefore: capture.pointsBefore ?? null,
                pointsAfter,
                // Final HP snapshot — uses pre-reset values (_snapEnemyHp/Max)
                // because resetHpBars() has already zeroed the live variables by
                // this point. allyHp/allyHpMax are also zeroed but are not shown
                // in the history expand panel so they remain for completeness.
                allyHp:     allyHp     || null,
                allyHpMax:  allyHpMax  || null,
                enemyHp:    _snapEnemyHp    || null,
                enemyHpMax: _snapEnemyHpMax || null,
            });
            pendingMatchCapture = null;
        }

        const tokensLeft = parseInt(statTokens, 10);

        if (isNaN(tokensLeft) || tokensLeft <= 0) {
            addLog('No tokens remaining. Stopping.', { color: '#ffb74d' });
            return false;
        }

        if (stopAfterMatch) {
            addLog('Stopped after match as requested.', { color: '#ffb74d' });
            return false;
        }

        addLog(`${tokensLeft} token(s) remaining - starting next match in 1s...`, { color: '#a5d6a7' });
        currentStatus = 'RUNNING';
        updateStatusUI();
        await sleep(1000);
        return true;
    }

    /* =========================================================
        INIT
    ========================================================= */

    // Read current stats from the live page DOM (no extra request)
    readStatsFromDoc(document);

    // Load persisted logs from previous sessions
    loadPersistedLogs();

    // Load persisted match history
    loadPersistedHistory();

    // Initial render
    renderGUI();
    addLog('👑 PvP Manager UI Loaded.', { color: '#2196f3' });

    // Restore saved panel position (needs the element to be laid out first)
    gui._dragPositioned = true;
    requestAnimationFrame(() => {
        try {
            const saved = JSON.parse(localStorage.getItem(PANEL_POS_KEY) || 'null');
            if (saved?.right != null && saved?.top != null) {
                const r = gui.getBoundingClientRect();
                gui.style.left = (saved.right - r.width) + 'px';
                gui.style.top  = saved.top + 'px';
            } else {
                // Default: top-right corner
                const vw = window.innerWidth;
                const r  = gui.getBoundingClientRect();
                gui.style.left = Math.max(8, vw - r.width - 15) + 'px';
                gui.style.top  = '90px';
            }
        } catch (_) {
            const vw = window.innerWidth;
            const r  = gui.getBoundingClientRect();
            gui.style.left = Math.max(8, vw - r.width - 15) + 'px';
            gui.style.top  = '90px';
        }
        clampToViewport();
    });

    window.addEventListener('resize', clampToViewport);

    // ESC to collapse
    document.addEventListener('keydown', e => {
        if (e.key !== 'Escape' || minimized) return;
        minimized = true;
        localStorage.setItem(UI_MIN_KEY, 'true');
        renderGUI();
        e.preventDefault();
    });

    // =========================================================
    // ANDROID BRIDGE — state exposure + remote control
    // =========================================================
    // Android's AndroidBridge.refreshLiveState() reads window.__pvpmState.
    // Android's AndroidBridge.setRunning() calls window.__pvpmSetRunning().
    // Neither existed before — all closure variables were invisible to Android.
    // This block wires them up WITHOUT touching any other code.

    /** Build the current state snapshot for Android to cache. */
    function buildAndroidState() {
        try {
            return {
                stats: {
                    rank:   statRank   !== '-' ? statRank   : null,
                    tier:   statTier   !== '-' ? statTier   : null,
                    points: statPoints !== '-' ? statPoints : null,
                    souls:  statSouls  !== '-' ? statSouls  : null,
                    tokens: statTokens !== '-' ? statTokens : null
                },
                match: {
                    active:          hpBarActive,
                    allyName:        hpBarAllyName  || null,
                    enemyName:       hpBarEnemyName || null,
                    allyHp:          allyHp,
                    allyHpMax:       allyHpMax,
                    enemyHp:         enemyHp,
                    enemyHpMax:      enemyHpMax,
                    allyAvatarUrl:   hpBarAllyAvatarUrl  || null,
                    enemyAvatarUrl:  hpBarEnemyAvatarUrl || null
                },
                skillList:    (typeof scrapeSkillList === 'function' ? scrapeSkillList() : skillList) || [],
                matchHistory: matchHistory || [],
                gameLogs: (function() {
                    try {
                        var lines = [];
                        var entries = logHistory || [];
                        var base = Date.now();
                        for (var i = 0; i < entries.length; i++) {
                            var e = entries[i];
                            var plain = typeof e === 'string' ? e : (e.plain || '');
                            var color = (e && e.color) ? e.color : '';
                            var type = 'info';
                            if (color) {
                                var c = color.toLowerCase();
                                if (c === '#66bb6a' || c === '#4caf50' || c === '#a5d6a7' || c === '#81c784') type = 'win';
                                else if (c === '#ef5350' || c === '#ef9a9a') type = 'loss';
                                else if (c === '#ffb74d' || c === '#fb923c') type = 'warning';
                                else if (c === '#81d4fa' || c === '#2196f3' || c === '#42a5f5') type = 'info';
                                else if (c === '#aaa' || c === '#546e7a') type = 'system';
                            }
                            lines.push((base - entries.length + i) + '|' + type + '|' + (color || '') + '|' + plain);
                        }
                        return lines.join('\n');
                    } catch(ex) { return ''; }
                })(),
                battleStats: (function() {
                    // Assemble the live battle stats for the Android overlay.
                    // liveStat holds values updated on every log entry during a
                    // match; bls_memory provides fallback for between-match reads.
                    try {
                        var myUid = allyUid  ? String(allyUid)  : null;
                        var enUid = enemyUid ? String(enemyUid) : null;
                        var blsMem = {};
                        try {
                            var blsRaw = localStorage.getItem('bls_memory');
                            blsMem = blsRaw ? JSON.parse(blsRaw) : {};
                        } catch (_) {}
                        var myMem = (myUid && blsMem[myUid]) ? blsMem[myUid] : {};
                        var enMem = (enUid && blsMem[enUid]) ? blsMem[enUid] : {};
                        return {
                            myAtk:  liveStat.myAtk  != null ? liveStat.myAtk  : (myMem.attackerScore  != null ? myMem.attackerScore  : null),
                            myDef:  liveStat.myDef  != null ? liveStat.myDef  : (myMem.defenderScore  != null ? myMem.defenderScore  : null),
                            myDmg:  liveStat.myDmg  != null ? liveStat.myDmg  : (myMem.baseTargetDmg  != null ? myMem.baseTargetDmg  : null),
                            myRet:  liveStat.myRet  != null ? liveStat.myRet  : (myMem.baseDamageBack != null ? myMem.baseDamageBack : null),
                            myCls:  liveStat.myCls  != null ? liveStat.myCls  : (myMem.playerClass    != null ? myMem.playerClass    : null),
                            myCrit: liveStat.myCrit != null ? liveStat.myCrit : (myMem.critChance     != null ? myMem.critChance     : null),
                            enAtk:  liveStat.enAtk  != null ? liveStat.enAtk  : (enMem.attackerScore  != null ? enMem.attackerScore  : null),
                            enDef:  liveStat.enDef  != null ? liveStat.enDef  : (enMem.defenderScore  != null ? enMem.defenderScore  : null),
                            enDmg:  liveStat.enDmg  != null ? liveStat.enDmg  : (enMem.baseTargetDmg  != null ? enMem.baseTargetDmg  : null),
                            enRet:  liveStat.enRet  != null ? liveStat.enRet  : (enMem.baseDamageBack != null ? enMem.baseDamageBack : null),
                            enCls:  liveStat.enCls  != null ? liveStat.enCls  : (enMem.playerClass    != null ? enMem.playerClass    : null),
                            enCrit: liveStat.enCrit != null ? liveStat.enCrit : (enMem.critChance     != null ? enMem.critChance     : null),
                            turnBonus:    liveStat.turnBonus    != null ? liveStat.turnBonus    : null,
                            globalPacing: liveStat.globalPacing != null ? liveStat.globalPacing : null
                        };
                    } catch (_) { return {}; }
                })()
            };
        } catch (e) {
            return { stats: {}, match: { active: false }, skillList: [], matchHistory: [] };
        }
    }

    /** Expose state to Android — called on every tick by AndroidBridge.refreshLiveState(). */
    window.__pvpmState = buildAndroidState();

    /** Remote control: Android.setRunning(true/false) calls this. */
    window.__pvpmSetRunning = function(shouldRun) {
        try {
            if (shouldRun) {
                if (!running) runAutomation();
            } else {
                // Graceful stop: request stop after current match (first press),
                // then hard stop (second press) — matches the existing UI behaviour.
                if (running && !stopAfterMatch) {
                    stopAfterMatch = true;
                } else if (running) {
                    stopFlag = true;
                }
            }
            window.__pvpmState = buildAndroidState();
        } catch (e) {}
    };

    // Refresh __pvpmState on a fast interval so Android always reads fresh data.
    // 800ms is well under Android's 1500ms poll — no extra network calls.
    setInterval(function() {
        try { window.__pvpmState = buildAndroidState(); } catch (e) {}
    }, 800);

    } // end runFeature

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot);
    } else {
        boot();
    }
})();

