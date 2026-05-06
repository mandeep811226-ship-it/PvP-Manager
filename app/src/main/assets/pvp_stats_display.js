// ==UserScript==
// @name         PvP Stats Display
// @namespace    http://tampermonkey.net/
// @version      3.7.0
// @description  Tracks and displays opponent stats during PvP battles, and remembers them across matches. Lives in a collapsible "PvP" group of the EMPIRE settings drawer alongside PvP Manager (which depends on this script's data for strategies). Toggles on every in-game page; gear button opens the Battle Stats panel.
// @author       ogmaend
// @match        https://demonicscans.org/pvp.php*
// @match        https://demonicscans.org/pvp_battle.php*
// @match        https://demonicscans.org/territory_war_battle.php*
// @match        https://demonicscans.org/pvp_style_battle.php*
// @icon         https://www.google.com/s2/favicons?sz=64&domain=demonicscans.org
// @grant        none
// ==/UserScript==
(function () {
  'use strict';

  // ─── Config ──────────────────────────────────────────────────────────────────
  const POLL_INTERVAL_MS = 1500;
const STATE_URL = window.location.href
  .replace('pvp_battle.php', 'pvp_battle_state.php')
  .replace('territory_war_battle.php', 'territory_war_battle_state.php');
const IS_TERRITORY_WAR = window.location.pathname.includes('territory_war_battle');

  // ─── State ───────────────────────────────────────────────────────────────────
  let MATCH_ID = null;
  let WAR_ID   = null;
  let lastProcessedLogId = 0;
  let stats = {};
  const statColors    = {};
  const hasAttackStats = new Set();
  const hasDefendStats = new Set();
  const hasCritChance  = new Set();
  const hasClass       = new Set();

  // ─── Class map ───────────────────────────────────────────────────────────────
  const CLASS_EMOJI = { mage: '🔥', cleric: '✨', warrior: '⚔️', hunter: '🏹' };

  // ─── Memory ──────────────────────────────────────────────────────────────────
  const STORAGE_KEY = 'bls_memory';

  function loadMemory() {
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}'); }
    catch(e) { return {}; }
  }

  function saveMemory(mem) {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(mem)); } catch(e) {}
  }

  // Merges incoming stats for a player into localStorage.
  // Colour comparison always runs. Saves are gated by match ID.
  function mergeIntoMemory(userId, incoming, label = 'memory', lid = null) {
    if (!userId || userId === '0') return;
    const mem     = loadMemory();
    const current = mem[userId] || {};

    const numericFields = ['attackerScore', 'defenderScore', 'baseTargetDmg', 'baseDamageBack', 'critChance'];
    const stringFields  = ['playerClass'];

    if (!statColors[userId]) statColors[userId] = {};
    numericFields.forEach(f => {
      const inc = incoming[f];
      if (inc == null) return;
      statColors[userId][f] = current[f] !== undefined
        ? (inc > current[f] ? '#34d399' : inc < current[f] ? '#fa6666' : '#e2e8f0')
        : '#e2e8f0';
    });

    const currentBattleId = IS_TERRITORY_WAR ? WAR_ID      : MATCH_ID;
    const lastIdKey       = IS_TERRITORY_WAR ? 'lastWarId' : 'lastMatchId';
    const lastBattleId    = current[lastIdKey] ?? null;
    if (lastBattleId !== null && currentBattleId < lastBattleId) return;

    if (incoming.name)            current.name       = incoming.name;
    if (currentBattleId !== null) current[lastIdKey] = currentBattleId;

    const changedParts = [];
    numericFields.forEach(f => {
      const inc = incoming[f];
      if (inc == null) return;
      if (current[f] !== inc) changedParts.push(`${f}: ${current[f] ?? '—'} → ${inc}`);
      current[f] = inc;
    });
    stringFields.forEach(f => {
      const inc = incoming[f];
      if (inc == null) return;
      if (current[f] !== inc) changedParts.push(`${f}: ${current[f] ?? '—'} → ${inc}`);
      current[f] = inc;
    });

    mem[userId] = current;
    saveMemory(mem);

    if (changedParts.length) {
      console.log(`[BLS] Updated ${label} "${current.name ?? userId}" (lid:${lid}): ${changedParts.join(', ')}`);
    }
  }

  function exportMemory() {
    const blob = new Blob([JSON.stringify(loadMemory(), null, 2)], { type: 'application/json' });
    Object.assign(document.createElement('a'), {
      href: URL.createObjectURL(blob),
      download: `bls_memory_${Date.now()}.json`,
    }).click();
  }

  function importMemory(file) {
    const reader = new FileReader();
    reader.onload = e => {
      try {
        const incoming      = JSON.parse(e.target.result);
        const mem           = loadMemory();
        const numericFields = ['attackerScore', 'defenderScore', 'baseTargetDmg', 'baseDamageBack', 'critChance'];
        const stringFields  = ['playerClass'];

        Object.entries(incoming).forEach(([userId, data]) => {
          const current     = mem[userId] || {};
          const importedMatchId = data.lastMatchId ?? null;
          const currentMatchId  = current.lastMatchId ?? null;
          const importedWarId   = data.lastWarId ?? null;
          const currentWarId    = current.lastWarId ?? null;

          const matchOk = importedMatchId === null
            ? currentMatchId === null
            : (currentMatchId === null || importedMatchId >= currentMatchId);
          const warOk = importedWarId === null
            ? currentWarId === null
            : (currentWarId === null || importedWarId >= currentWarId);
          if (!matchOk && !warOk) return;

          if (data.name)                current.name        = data.name;
          if (importedMatchId !== null)  current.lastMatchId = importedMatchId;
          if (importedWarId   !== null)  current.lastWarId   = importedWarId;

          numericFields.forEach(f => { if (data[f] != null) current[f] = data[f]; });
          stringFields.forEach(f  => { if (data[f] != null) current[f] = data[f]; });
          mem[userId] = current;
        });

        saveMemory(mem);
        renderTable();
        console.log('[BLS] Imported', Object.keys(incoming).length, 'players into memory.');
      } catch(err) {
        console.error('[BLS] Import failed:', err);
      }
    };
    reader.readAsText(file);
  }

  // ─── Build calculations ───────────────────────────────────────────────────────
  // ATK build: effective attack + equipment attack + 20% pet attack.
  function computeAttackBuild(f) {
    return f.exchange_attacker_attack_core
         + f.exchange_attacker_equipment_attack
         + Math.round(0.2 * f.exchange_attacker_pet_attack_raw_total);
  }

  // DEF build: 60% effective defense + equipment defense + 20% pet defense.
  function computeDefenseBuild(f) {
    return Math.round(0.6 * f.exchange_defender_defense_core_used)
         + f.exchange_defender_equipment_defense_used
         + Math.round(0.2 * f.exchange_defender_pet_defense_used_total);
  }

  // ─── State fetching ───────────────────────────────────────────────────────────
  async function fetchBattleState(lastLogId = 0) {
    try {
      const sep = STATE_URL.includes('?') ? '&' : '?';
      const url = lastLogId > 0 ? `${STATE_URL}${sep}last_log_id=${lastLogId}` : STATE_URL;
      const resp = await fetch(url);
      if (!resp.ok) return null;
      return await resp.json();
    } catch(e) { return null; }
  }

  // ─── State processing ─────────────────────────────────────────────────────────
  // Stores a player's class and saves to memory. Does not render — rendering
  // happens once at the end of processState.
  function setClass(userId, cls, lid) {
    if (!stats[userId] || hasClass.has(userId)) return;
    stats[userId].playerClass = cls;
    hasClass.add(userId);
    mergeIntoMemory(userId, { playerClass: cls }, 'class', lid);
  }

  function processState(data) {
    if (MATCH_ID === null && data.season_match?.id) {
      MATCH_ID = data.season_match.id;
    }
    if (WAR_ID === null && data.territory_war?.war_id) {
      WAR_ID = data.territory_war.war_id;
    }

    // Initialise players from teams data before processing logs, so stats
    // entries exist regardless of whether the DOM formation has rendered yet.
    if (data.teams) {
      [data.teams.ally, data.teams.enemy].filter(Boolean).forEach(side => {
        Object.values(side.players_by_num || {}).forEach(player => {
          if (player.npc_id !== 0) return;
          const userId = String(player.user_id);
          if (!userId || userId === '0') return;
          if (!stats[userId]) {
            stats[userId] = {
              attackerScore:  null,
              defenderScore:  null,
              baseTargetDmg:  null,
              baseDamageBack: null,
              playerClass:    null,
              critChance:     null,
            };
          }
          const cls = player.role?.toLowerCase();
          if (cls && CLASS_EMOJI[cls]) setClass(userId, cls, null);
        });
      });
    }

    (data.new_logs || []).forEach(log => {
      if (log.id <= lastProcessedLogId) return;
      lastProcessedLogId = Math.max(lastProcessedLogId, log.id);
      if (log.details?.kind !== 'attack') return;
      const f = log.details.formula;
      if (!f) return;

      // Keys are formatted as "side:userId".
      const attackerId = (log.details.actor?.key  ?? '').split(':')[1] ?? '';
      const defenderId = (log.details.target?.key ?? '').split(':')[1] ?? '';

      updateMultiplierDisplay(f.turn_bonus_multiplier ?? null, f.global_damage_multiplier ?? null);

      // Attacker: ATK score, attack build, and crit chance batched into one save.
      if (stats[attackerId]) {
        const incoming = { name: log.details.actor?.name };

        if (!hasAttackStats.has(attackerId)) {
          const as  = f.exchange_attacker_score;
          const btd = computeAttackBuild(f);
          if (as != null && btd != null) {
            stats[attackerId].attackerScore = as;
            stats[attackerId].baseTargetDmg = btd;
            hasAttackStats.add(attackerId);
            incoming.attackerScore = as;
            incoming.baseTargetDmg = btd;
          }
        }

        if (!hasCritChance.has(attackerId)) {
          const cc = f.critical_chance ?? null;
          if (cc !== null) {
            stats[attackerId].critChance = cc;
            hasCritChance.add(attackerId);
            incoming.critChance = cc;
          }
        }

        if (Object.keys(incoming).length > 1) {
          mergeIntoMemory(attackerId, incoming, 'ATK', log.id);
        }
      }

      // Defender: DEF score + defense build.
      if (stats[defenderId] && !hasDefendStats.has(defenderId)) {
        const ds  = f.exchange_defender_score;
        const dbb = computeDefenseBuild(f);
        if (ds != null && dbb != null) {
          stats[defenderId].defenderScore  = ds;
          stats[defenderId].baseDamageBack = dbb;
          hasDefendStats.add(defenderId);
          mergeIntoMemory(defenderId, { name: log.details.target?.name, defenderScore: ds, baseDamageBack: dbb }, 'DEF', log.id);
        }
      }
    });

    // Single render per poll after all teams and logs are processed.
    if (data.teams) updateTeamBar(data.teams, loadMemory());
    renderTable();
  }

  // ─── Team bar ─────────────────────────────────────────────────────────────────
  // Builds the team stats bar and inserts it above #pvp-middle-row if present,
  // otherwise above .topbar. Called once at init.
  function createTeamBar() {
    if (document.getElementById('bls-team-bar')) return;
    const bar = document.createElement('div');
    bar.id = 'bls-team-bar';
    bar.style.display = 'none';
    bar.innerHTML = `
      <div class="bls-team-panel bls-panel-enemy" id="bls-enemy">
        <div class="bls-team-name">Enemy Side</div>
        <div class="bls-hp-wrap"><div class="bls-hp-fill bls-hp-fill-rtl" id="bls-enemy-hp-fill"></div></div>
        <div class="bls-hp-text" id="bls-enemy-hp-text">—</div>
        <div class="bls-team-stats" id="bls-enemy-stats">—</div>
      </div>
      <div class="bls-team-divider"></div>
      <div class="bls-team-panel bls-panel-ally" id="bls-ally">
        <div class="bls-team-name">Allied Side</div>
        <div class="bls-hp-wrap"><div class="bls-hp-fill" id="bls-ally-hp-fill"></div></div>
        <div class="bls-hp-text" id="bls-ally-hp-text">—</div>
        <div class="bls-team-stats" id="bls-ally-stats">—</div>
      </div>
    `;
    const anchor = document.getElementById('pvp-middle-row')
                ?? document.getElementById('bottomTeamCard')
                ?? document.querySelector('.topbar');
    if (anchor) anchor.insertAdjacentElement('beforebegin', bar);
    else document.body.prepend(bar);

    // Keep watching for pvp-middle-row — if it appears later (created by another
    // script), move the bar above it regardless of where it currently sits.
    const obs = new MutationObserver(() => {
      const target = document.getElementById('pvp-middle-row');
      if (!target || target.previousElementSibling?.id === 'bls-team-bar') return;
      target.insertAdjacentElement('beforebegin', bar);
      obs.disconnect();
    });
    obs.observe(document.body, { childList: true, subtree: true });

    // Mirror the page's swap sides button — swaps panel order and flips alignment.
    let sidesSwapped = false;
    document.addEventListener('click', e => {
      if (!e.target.closest('.swapSidesBtn')) return;
      const bar     = document.getElementById('bls-team-bar');
      const ally    = document.getElementById('bls-ally');
      const enemy   = document.getElementById('bls-enemy');
      const divider = bar?.querySelector('.bls-team-divider');
      if (!bar || !ally || !enemy || !divider) return;

      sidesSwapped = !sidesSwapped;
      bar.innerHTML = '';
      bar.append(
        sidesSwapped ? ally  : enemy,
        divider,
        sidesSwapped ? enemy : ally
      );

      // Swap alignment classes.
      ally.classList.toggle('bls-panel-enemy');
      ally.classList.toggle('bls-panel-ally');
      enemy.classList.toggle('bls-panel-ally');
      enemy.classList.toggle('bls-panel-enemy');

      // Swap HP bar direction.
      ally.querySelector('.bls-hp-fill')?.classList.toggle('bls-hp-fill-rtl');
      enemy.querySelector('.bls-hp-fill')?.classList.toggle('bls-hp-fill-rtl');
    });
  }

  // Updates team HP bars and stat totals from the latest teams data.
  // Uses live session stats where available, falling back to memory per player.
  function updateTeamBar(teams, mem) {
    const sides = { ally: teams.ally, enemy: teams.enemy };

    // Count total human players across both teams.
    const totalPlayers = Object.values(sides).reduce((sum, team) => {
      if (!team) return sum;
      return sum + Object.values(team.players_by_num || {}).filter(p => p.npc_id === 0).length;
    }, 0);

    const bar = document.getElementById('bls-team-bar');
    if (!bar) return;
    if (totalPlayers < 3) { bar.style.display = 'none'; return; }
    bar.style.display = 'flex';

    Object.entries(sides).forEach(([side, team]) => {
      if (!team) return;
      const players = Object.values(team.players_by_num || {}).filter(p => p.npc_id === 0);

      // HP totals from endpoint.
      const totalHp    = players.reduce((s, p) => s + (p.hp     ?? 0), 0);
      const totalHpMax = players.reduce((s, p) => s + (p.hp_max ?? 0), 0);
      const hpPct      = totalHpMax > 0 ? (totalHp / totalHpMax) * 100 : 0;

      const fillEl = document.getElementById(`bls-${side}-hp-fill`);
      const textEl = document.getElementById(`bls-${side}-hp-text`);
      if (fillEl) fillEl.style.width = `${hpPct.toFixed(1)}%`;
      if (textEl) textEl.textContent = `${fmt(totalHp)} / ${fmt(totalHpMax)}`;

      // Stat totals: live session first, memory fallback.
      let totalAtk = 0, totalDmg = 0, totalDef = 0, totalRet = 0;
      players.forEach(p => {
        const userId = String(p.user_id);
        const live   = stats[userId] || {};
        const saved  = mem[userId]   || {};
        totalAtk += live.attackerScore  ?? saved.attackerScore  ?? 0;
        totalDmg += live.baseTargetDmg  ?? saved.baseTargetDmg  ?? 0;
        totalDef += live.defenderScore  ?? saved.defenderScore  ?? 0;
        totalRet += live.baseDamageBack ?? saved.baseDamageBack ?? 0;
      });

      const statsEl = document.getElementById(`bls-${side}-stats`);
      if (statsEl) statsEl.innerHTML = `
        <div>
          <span class="bls-ts-label atk">ATK </span><span class="bls-ts-val">${fmt(totalAtk)}</span>
          <span class="bls-ts-label atk">DMG </span><span class="bls-ts-val">${fmt(totalDmg)}</span>
        </div>
        <div>
          <span class="bls-ts-label def">DEF </span><span class="bls-ts-val">${fmt(totalDef)}</span>
          <span class="bls-ts-label def">RET </span><span class="bls-ts-val">${fmt(totalRet)}</span>
        </div>
      `;
    });
  }

  // ─── Polling ──────────────────────────────────────────────────────────────────
  async function poll() {
    const data = await fetchBattleState(lastProcessedLogId);
    if (data) processState(data);
    setTimeout(poll, POLL_INTERVAL_MS);
  }

  // ─── PvE init ────────────────────────────────────────────────────────────────
  // No state endpoint on PvE — initialise stats from DOM slot keys so existing
  // memory values are displayed.
  function initPlayersFromDom() {
    document.querySelectorAll('.pSlot[data-key]').forEach(el => {
      const userId = (el.dataset.key ?? '').split(':')[1] ?? null;
      if (!userId || userId === '0') return;
      if (!stats[userId]) {
        stats[userId] = {
          attackerScore:  null,
          defenderScore:  null,
          baseTargetDmg:  null,
          baseDamageBack: null,
          playerClass:    null,
          critChance:     null,
        };
      }
    });
    renderTable();
  }

  // ─── MutationObserver ────────────────────────────────────────────────────────
  // Injects stat blocks whenever a .uname element is added to a slot.
  // Player initialisation is handled by processState (PvP) or initPlayersFromDom (PvE).
  //
  // IMPORTANT: only set up when the master toggle is on. Otherwise we'd still
  // append empty .bls-stats blocks to every slot (with inline font/padding
  // styles), making slots taller and breaking layout assumptions in
  // companion scripts like Compact PvP that measure slot height.
  function startSlotInjector() {
    new MutationObserver(mutations => {
      mutations.forEach(m => {
        m.addedNodes.forEach(node => {
          if (node.nodeType !== 1) return;
          if (node.matches?.('.uname')) {
            const slot = node.closest('.pSlot[data-key]');
            if (slot) injectSlot(slot);
          }
          node.querySelectorAll?.('.uname').forEach(uname => {
            const slot = uname.closest('.pSlot[data-key]');
            if (slot) injectSlot(slot);
          });
        });
      });
    }).observe(document.body, { childList: true, subtree: true });
  }

  // ─── Rendering ───────────────────────────────────────────────────────────────
  function fmt(val) {
    if (val === null || val === undefined) return '—';
    return val.toLocaleString(undefined, { maximumFractionDigits: 0 });
  }

  // Returns a coloured span for a stat value.
  // Live values use comparison colour; memory fallback is white; missing is grey dash.
  function statVal(liveVal, memVal, colorKey, colors) {
    if (liveVal !== null && liveVal !== undefined)
      return `<span style="color:${colors[colorKey] ?? '#e2e8f0'};">${fmt(liveVal)}</span>`;
    if (memVal !== undefined)
      return `<span style="color:#e2e8f0;">${fmt(memVal)}</span>`;
    return `<span style="color:#475569;">—</span>`;
  }

  // Injects or refreshes the stat block for a single player slot.
  // Accepts an optional pre-loaded memory object to avoid repeated localStorage reads.
  function injectSlot(slot, memCache) {
    const userId = (slot.dataset.key ?? '').split(':')[1] ?? null;
    if (!userId || userId === '0') return;
    const s   = stats[userId];
    const mem = (memCache ?? loadMemory())[userId] || {};
    const uname = slot.querySelector('.uname');
    if (!uname) return;

    let blk = slot.querySelector('.bls-stats');
    if (!blk) {
      blk = document.createElement('div');
      blk.className = 'bls-stats';
      blk.style.cssText = `
        font-size:14px;line-height:1.3;text-align:center;
        margin:2px 0;padding:2px 4px;
        width:100% !important;
        background:rgba(0,0,0,0.25);border-radius:4px;
      `;
      uname.insertAdjacentElement('afterend', blk);
    }

    // Colour the buff box by status type.
    const buff = slot.querySelector('.buff');
    if (buff) {
      const buffText = buff.textContent.toLowerCase();
      if      (buffText.includes('poison'))                              buff.style.background = 'rgba(134, 239, 172, 0.25)';
      else if (buffText.includes('stun'))                                buff.style.background = 'rgba(253, 224, 71,  0.25)';
      else if (buffText.includes('taunt'))                               buff.style.background = 'rgba(248, 113, 113, 0.25)';
      else if (buffText === 'ally' || buffText === 'enemy' || !buffText) buff.style.background = '';
      else                                                               buff.style.background = 'rgba(147, 197, 253, 0.25)';
    }

    const colors = statColors[userId] || {};

    // Crit chance: label in orange above, percentage colour-coded vs memory.
    const critLive = s?.critChance ?? null;
    const critMem  = mem.critChance;
    let critEl = slot.querySelector('.bls-crit');
    if (critLive !== null || critMem !== undefined) {
      if (!critEl) {
        critEl = document.createElement('div');
        critEl.className = 'bls-crit';
        slot.appendChild(critEl);
      }
      const numColor   = critLive !== null ? (colors['critChance'] ?? '#e2e8f0') : '#e2e8f0';
      const numDisplay = ((critLive ?? critMem) * 100).toFixed(1) + '%';
      critEl.innerHTML = `<span style="color:#f59e0b;display:block;line-height:1.2;">Crit:</span>`
                       + `<span style="color:${numColor};display:block;line-height:1.2;">${numDisplay}</span>`;
    } else if (critEl) {
      critEl.remove();
    }

    // Class emoji in the top-right corner of the slot.
    const playerClass = s?.playerClass ?? mem.playerClass ?? null;
    let classEl = slot.querySelector('.bls-class');
    if (playerClass && CLASS_EMOJI[playerClass]) {
      if (!classEl) {
        classEl = document.createElement('div');
        classEl.className = 'bls-class';
        slot.appendChild(classEl);
      }
      classEl.textContent = CLASS_EMOJI[playerClass];
    } else if (classEl) {
      classEl.remove();
    }

    blk.innerHTML = `
      <div>
        <span style="color:#f78839;">ATK</span>&nbsp;${statVal(s?.attackerScore, mem.attackerScore, 'attackerScore', colors)}
        &nbsp;
        <span style="color:#f78839;">DMG</span>&nbsp;${statVal(s?.baseTargetDmg, mem.baseTargetDmg, 'baseTargetDmg', colors)}
      </div>
      <div>
        <span style="color:#a78bfa;">DEF</span>&nbsp;${statVal(s?.defenderScore, mem.defenderScore, 'defenderScore', colors)}
        &nbsp;
        <span style="color:#a78bfa;">RET</span>&nbsp;${statVal(s?.baseDamageBack, mem.baseDamageBack, 'baseDamageBack', colors)}
      </div>
    `;
  }

  // Loads memory once and renders every visible player slot.
  function renderTable() {
    const mem = loadMemory();
    document.querySelectorAll('.pSlot[data-key]').forEach(slot => injectSlot(slot, mem));
  }

  function updateMultiplierDisplay(turnBonus, pacingMultiplier) {
    const turnEl   = document.getElementById('bls-turn-bonus');
    const pacingEl = document.getElementById('bls-pacing');
    if (turnEl)   turnEl.textContent   = turnBonus        !== null ? `x${Number(turnBonus).toFixed(1)}`        : '—';
    if (pacingEl) pacingEl.textContent = pacingMultiplier !== null ? `x${Number(pacingMultiplier).toFixed(1)}` : '—';
  }

  // ─── EMPIRE drawer integration ─────────────────────────────────────────────
  // Injects a "PvP Stats Display" group into the EMPIRE settings drawer on
  // every in-game page. Group contains:
  //   - Master toggle (always shown). Off -> the script does nothing.
  //   - On non-battle pages: a small ⚙️ icon button that toggles the
  //     bls-toolbar's visibility on demand.
  //   - On pvp_battle.php / pvp_style_battle.php: a "Show Battle Stats
  //     controls in PvP" toggle that controls auto-show of the toolbar.
  // Pattern mirrors scripts/EMPIRE_SIDEBAR_INTEGRATION.md - DOM-only, no
  // calls into EMPIRE's JS API (which would be invisible from the
  // page-context world this userscript runs in).

  const BLS_ENABLED_KEY            = 'et_bls_enabled';        // master, default true
  const BLS_SHOW_BATTLE_KEY        = 'et_bls_show_battle';    // pvp_battle auto-show, default true

  const EMPIRE_PVP_GROUP_ID         = 'et-settings-pvp';
  const EMPIRE_PVP_GROUP_TITLE      = 'PvP';
  const EMPIRE_DRAWER_CONTAINER_ID  = 'et-settings-drawer-container';
  const EMPIRE_GROUP_COLLAPSE_KEY   = 'et_drawer_group_pvp_collapsed';
  const BLS_MASTER_OWNED_ATTR      = 'data-bls-master';
  const BLS_BATTLE_OWNED_ATTR      = 'data-bls-battle';
  const BLS_ICON_BTN_ID            = 'bls-empire-icon-btn';
  const DRAWER_OBSERVER_MAX_MS     = 15000;

  // DOM markers EMPIRE injects on every page where its drawer can run.
  // ANY one is enough to confirm presence. If the user disables/uninstalls
  // EMPIRE, none will be present and we fall back to "always-on" semantics
  // (the master toggle has no UI to flip while EMPIRE is gone, so a stored
  // 'false' would otherwise silently strand the user).
  const EMPIRE_PRESENCE_SELECTORS = [
    '#et-native-injected-side-nav-styles',
    '#et-topbar-hp',
    '#et-open-settings-drawer-btn',
    '#et-settings-drawer'
  ];

  function detectEmpireSync() {
    return EMPIRE_PRESENCE_SELECTORS.some((s) => {
      try { return !!document.querySelector(s); } catch (e) { return false; }
    });
  }

  function _readBoolKey(k) {
    try {
      const v = localStorage.getItem(k);
      if (v === null) return true;
      return JSON.parse(v) !== false;
    } catch (e) { return true; }
  }
  function isBlsEnabled() {
    // Asymmetric default: when EMPIRE is absent the master toggle has no
    // UI to flip, so a stored 'false' would silently strand the user.
    // Run unconditionally in that case.
    if (!detectEmpireSync()) return true;
    return _readBoolKey(BLS_ENABLED_KEY);
  }
  function shouldShowInBattle() { return _readBoolKey(BLS_SHOW_BATTLE_KEY); }

  function isInGamePage() {
    try {
      return !!document.querySelector('div.content-area') ||
             document.body.classList.contains('mapMode');
    } catch (e) { return false; }
  }

    function isBattlePagePath() {
        return /\/pvp_battle\.php|\/pvp_style_battle\.php|\/territory_war_battle\.php/.test(window.location.pathname);
    }

  // Both PvP Manager and PvP Stats Display share this group; whichever
  // boots first creates it, the other reuses it. Returns { group, body } -
  // callers should append rows to `body` so they collapse with the group.
  function ensurePvpGroup() {
    let group = document.getElementById(EMPIRE_PVP_GROUP_ID);
    if (group) {
      const existingBody = group.querySelector('.et-pvp-group-body');
      if (existingBody) return { group, body: existingBody };
      return { group, body: group };
    }
    const container = document.getElementById(EMPIRE_DRAWER_CONTAINER_ID);
    if (!container) return null;

    group = document.createElement('div');
    group.className = 'et-settings-group';
    group.id = EMPIRE_PVP_GROUP_ID;

    const titleEl = document.createElement('div');
    titleEl.className = 'et-settings-group-title';
    titleEl.style.cssText = 'cursor:pointer; user-select:none; display:flex; align-items:center; gap:6px;';

    const arrowEl = document.createElement('span');
    arrowEl.className = 'et-pvp-group-arrow';
    arrowEl.style.cssText = 'font-size:10px; line-height:1; opacity:0.7;';

    const titleTxt = document.createElement('span');
    titleTxt.textContent = EMPIRE_PVP_GROUP_TITLE;
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

  // Build a toggle that mimics EMPIRE's createToggle DOM shape so its CSS
  // styles us natively.
  function _buildEtToggle({ ownedAttr, title, desc, checked, onChange }) {
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
    const tEl = document.createElement('span');
    tEl.className = 'et-switch-title';
    tEl.textContent = title;
    textWrap.appendChild(tEl);
    if (desc) {
      const dEl = document.createElement('span');
      dEl.className = 'et-switch-desc';
      dEl.textContent = desc;
      textWrap.appendChild(dEl);
    }

    input.addEventListener('change', () => {
      try { onChange(!!input.checked); } catch (e) {}
    });

    wrap.appendChild(input);
    wrap.appendChild(slider);
    wrap.appendChild(textWrap);
    return wrap;
  }

  function toggleStatsPanel() {
    const panel = document.getElementById('bls-toolbar');
    if (!panel) return;
    panel.style.display = (panel.style.display === 'none') ? '' : 'none';
  }

  // ⚙️ icon button - always shown when master is on, but the click only
  // does anything if the "Show Battle Stats controls in PvP" toggle is
  // also on. This couples the two controls per the user's spec.
  function buildIconButton() {
    const btn = document.createElement('button');
    btn.id = BLS_ICON_BTN_ID;
    btn.type = 'button';
    btn.style.cssText = 'background:transparent; border:0; cursor:pointer; font-size:18px; line-height:1; padding:4px 8px; border-radius:6px; flex:none;';
    btn.textContent = '⚙️';
    const refresh = () => {
      const enabled = shouldShowInBattle();
      btn.disabled = !enabled;
      btn.title = enabled
        ? 'Open Battle Stats'
        : 'Disabled: enable "Show Battle Stats controls in PvP" first';
      btn.setAttribute('aria-label', btn.title);
      btn.style.color   = enabled ? '#aaa' : '#555';
      btn.style.cursor  = enabled ? 'pointer' : 'not-allowed';
      btn.style.opacity = enabled ? '' : '0.5';
    };
    refresh();
    btn.addEventListener('click', () => {
      if (!shouldShowInBattle()) return;
      toggleStatsPanel();
    });
    // Stash the refresh fn so the show-toggle's onChange can re-style us live.
    btn._blsRefresh = refresh;
    return btn;
  }

  // Compose the master toggle row. Always: master toggle on the left,
  // gear button on the right (when master is on).
  function buildMasterRow() {
    const row = document.createElement('div');
    row.style.cssText = 'display:flex; align-items:flex-start; gap:8px; width:100%;';

    const masterToggle = _buildEtToggle({
      ownedAttr: BLS_MASTER_OWNED_ATTR,
      title:    'PvP Stats Display',
      desc:     'Displays and remembers opponent stats.',
      checked:  isBlsEnabled(),
      onChange: (next) => {
        try { localStorage.setItem(BLS_ENABLED_KEY, JSON.stringify(!!next)); } catch (e) {}
        try { window.location.reload(); } catch (e) {}
      }
    });
    masterToggle.style.flex = '1 1 auto';
    masterToggle.style.minWidth = '0';
    row.appendChild(masterToggle);

    if (isBlsEnabled()) {
      row.appendChild(buildIconButton());
    }
    return row;
  }

  function buildBattleShowToggle() {
    return _buildEtToggle({
      ownedAttr: BLS_BATTLE_OWNED_ATTR,
      title:    'Show Battle Stats controls in PvP',
      desc:     '',
      checked:  shouldShowInBattle(),
      onChange: (next) => {
        try { localStorage.setItem(BLS_SHOW_BATTLE_KEY, JSON.stringify(!!next)); } catch (e) {}
        // Live update of the toolbar visibility (only meaningful on
        // pvp_battle.php; harmless elsewhere) and re-style the gear button.
        const panel = document.getElementById('bls-toolbar');
        if (panel && isBattlePagePath()) panel.style.display = next ? '' : 'none';
        if (!next && panel && !isBattlePagePath()) panel.style.display = 'none';
        const btn = document.getElementById(BLS_ICON_BTN_ID);
        if (btn && typeof btn._blsRefresh === 'function') btn._blsRefresh();
      }
    });
  }

  function injectDrawerUI() {
    const ref = ensurePvpGroup();
    if (!ref) return false;
    const { body } = ref;
    // Idempotent: if our master row is already there, no-op.
    if (body.querySelector('[' + BLS_MASTER_OWNED_ATTR + '="1"]')) return true;

    body.appendChild(buildMasterRow());
    // Always show the battle-show toggle when master is on, regardless of
    // page. Its effect only takes hold on pvp_battle.php (auto-show) and
    // it gates the ⚙️ button's clickability everywhere.
    if (isBlsEnabled()) {
      body.appendChild(buildBattleShowToggle());
    }
    return true;
  }

  function startEmpireDrawerWatcher() {
    let settled = false, observer = null;
    const settle = () => {
      if (settled) return;
      settled = true;
      if (observer) { try { observer.disconnect(); } catch (e) {} }
    };
    if (injectDrawerUI()) { settle(); return; }
    observer = new MutationObserver(() => {
      if (!settled && injectDrawerUI()) settle();
    });
    try {
      observer.observe(document.body, { childList: true, subtree: true });
    } catch (e) { settle(); return; }
    setTimeout(settle, DRAWER_OBSERVER_MAX_MS);
  }

  // ─── Toolbar ─────────────────────────────────────────────────────────────────
  function createPanel() {
    const p = document.createElement('div');
    p.id = 'bls-toolbar';
    p.style.cssText = `
      position:fixed;bottom:70px;right:63px;z-index:10001;
      background:#0f172a;border:1px solid #334155;border-radius:9px;
      padding:8px 12px;display:flex;flex-direction:column;gap:7px;align-items:center;
      font-family:monospace;font-size:17px;box-shadow:0 4px 16px rgba(0,0,0,0.5);
    `;
    p.innerHTML = `
      <span style="color:#818cf8;font-weight:700;">Battle Stats</span>
      <div id="bls-multipliers" style="color:#94a3b8;font-size:14px;line-height:1.6;width:100%;display:grid;grid-template-columns:1fr auto;">
        <span>Turn Bonus</span>  <span id="bls-turn-bonus"  style="color:#e2e8f0;text-align:right;">—</span>
        <span>Global Pacing</span><span id="bls-pacing"     style="color:#e2e8f0;text-align:right;">—</span>
      </div>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:7px;">
        <button id="bls-export" style="background:#1d4ed8;color:#fff;border:none;border-radius:7px;padding:4px 9px;cursor:pointer;font-size:14px;font-family:monospace;">Export</button>
        <label style="background:#0f766e;color:#fff;border-radius:7px;padding:4px 9px;cursor:pointer;font-size:14px;text-align:center;">
          Import
          <input type="file" id="bls-import" accept=".json" style="display:none;">
        </label>
        <button id="bls-clear" style="background:#991b1b;color:#fff;border:none;border-radius:7px;padding:4px 9px;cursor:pointer;font-size:14px;font-family:monospace;grid-column:span 2;">Clear Memory</button>
      </div>
    `;
    document.body.appendChild(p);

    document.getElementById('bls-export').onclick = exportMemory;
    document.getElementById('bls-import').onchange = e => {
      const file = e.target.files[0];
      if (file) importMemory(file);
      e.target.value = '';
    };
    document.getElementById('bls-clear').onclick = () => {
      if (confirm('Are you sure you want to clear all memory? This cannot be undone.')) {
        saveMemory({});
        renderTable();
        console.log('[BLS] Memory cleared.');
      }
    };
  }

  // ─── Styles ───────────────────────────────────────────────────────────────────
  function injectStyles() {
    if (document.getElementById('bls-styles')) return;
    const style = document.createElement('style');
    style.id = 'bls-styles';
    style.textContent = `
      /* ── Team bar above .topbar ── */
      #bls-team-bar {
        display: flex;
        gap: 12px;
        padding: 8px 16px;
        background: #0f172a;
        border-bottom: 1px solid #1e293b;
        font-family: monospace;
      }

      .bls-team-panel {
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: 4px;
      }

      .bls-team-divider {
        width: 1px;
        background: #334155;
        align-self: stretch;
        margin: 0 4px;
      }

      /* Enemy panel is right-aligned so stats sit close to the centre gap. */
      .bls-panel-enemy {
        align-items: flex-end;
        text-align: right;
      }

      .bls-team-name {
        font-size: 12px;
        font-weight: 700;
        color: #818cf8;
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }

      .bls-hp-wrap {
        width: 100%;
        height: 8px;
        background: #1e293b;
        border-radius: 4px;
        overflow: hidden;
        display: flex;
      }

      .bls-hp-fill {
        height: 100%;
        background: #34d399;
        border-radius: 4px;
        transition: width 0.4s ease;
      }

      /* Enemy HP bar anchored to the right so it depletes toward the centre. */
      .bls-hp-fill-rtl {
        margin-left: auto;
      }

      .bls-hp-text {
        font-size: 11px;
        color: #94a3b8;
      }

      .bls-team-stats {
        display: flex;
        flex-direction: column;
        gap: 2px;
        font-size: 12px;
        margin-top: 2px;
      }

      .bls-ts-label {
        font-weight: 700;
      }

      .bls-ts-label.atk { color: #f78839; }
      .bls-ts-label.def { color: #a78bfa; }

      .bls-ts-val {
        color: #e2e8f0;
        margin-right: 4px;
      }

      :root {
        --slot-w: 180px;
        --slot-h: 250px;
      }

      #topTeamCard .pSlot,
      #bottomTeamCard .pSlot {
        width: var(--slot-w) !important;
        height: var(--slot-h) !important;
        position: relative !important;
      }

      /* Class emoji — top-right corner of slot. */
      .bls-class {
        position: absolute;
        top: 4px;
        right: 4px;
        font-size: 24px;
        line-height: 1;
        pointer-events: none;
      }

      /* Crit chance — right side, slightly above centre. */
      .bls-crit {
        position: absolute;
        right: 6px;
        top: calc(38% - 36px);
        font-size: 12px;
        font-family: monospace;
        pointer-events: none;
      }

      /* Shift elements to compensate for the stat block height. */
      #topTeamCard .bls-stats,
      #bottomTeamCard .bls-stats {
        transform: translateY(-10px);
      }

      #topTeamCard .buff,
      #bottomTeamCard .buff {
        transform: translateY(-25px);
        font-size: 14px !important;
      }

      #topTeamCard .hpWrap,
      #bottomTeamCard .hpWrap {
        width: 90% !important;
        transform: translateY(-20px);
      }

      #topTeamCard .tokRow,
      #bottomTeamCard .tokRow {
        transform: translateY(10px);
      }

      #topTeamCard .hpBar,
      #bottomTeamCard .hpBar {
        height: 13px !important;
      }

      #topTeamCard .hpFill,
      #bottomTeamCard .hpFill {
        height: 13px !important;
      }

      #topTeamCard .hpText,
      #bottomTeamCard .hpText {
        width: 100% !important;
        font-size: 13px !important;
      }
    `;
    document.head.appendChild(style);
  }

  // ─── Init ─────────────────────────────────────────────────────────────────────
  // Boot routing:
  //   - Drawer UI runs on every in-game page so the master toggle is always
  //     reachable, even when the script is otherwise idle.
  //   - If master is OFF, nothing else runs.
  //   - On pvp_battle.php / pvp_style_battle.php: full battle behavior
  //     (toolbar, team bar, polling). Toolbar visibility is gated by the
  //     "Show Battle Stats controls in PvP" toggle (default on).
  //   - On any other page: toolbar exists hidden; the ⚙️ icon button in the
  //     drawer toggles it. No DOM scrape, no polling, no inline injection.
  if (isInGamePage()) startEmpireDrawerWatcher();

  if (!isBlsEnabled()) {
    // Master OFF - leave only the drawer UI active.
    return;
  }

  injectStyles();
  startSlotInjector();   // gated behind master so we never append .bls-stats when off
  createPanel();   // bls-toolbar DOM exists; visibility per page.

  if (isBattlePagePath()) {
    createTeamBar();
    poll();
    const _p = document.getElementById('bls-toolbar');
    if (_p) _p.style.display = shouldShowInBattle() ? '' : 'none';
  } else {
    // Any non-battle page (pvp.php, town, guild members, etc.):
    // toolbar exists but stays hidden until the icon button is clicked.
    // No DOM scrape (initPlayersFromDom is intentionally not called here).
    const _p = document.getElementById('bls-toolbar');
    if (_p) _p.style.display = 'none';
  }

})();
