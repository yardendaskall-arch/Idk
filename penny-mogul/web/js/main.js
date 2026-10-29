/* Penny Mogul — app shell, event wiring, game loop and persistence. */
(function () {
  'use strict';
  const PM = globalThis.PM;
  const html = PM.html;
  const { useState } = preactHooks;
  const { fmt, ui } = PM;
  const { Icon, ToastLayer, ConfirmModal } = PM.UI;
  const S = PM.Screens, B = PM.Biz;

  /* ----------------------------------------------------------- game boot */
  const SAVE_KEY = 'pennymogul.save.v1';
  let hasSave = false;
  try { hasSave = !!localStorage.getItem(SAVE_KEY); } catch (e) { /* storage blocked: play without saving */ }

  const game = (PM.game = PM.createGame({ noWarmup: hasSave }));
  let welcome = null;
  if (hasSave) {
    if (game.loadSaved()) {
      const away = (Date.now() - game.s.lastSeen) / 1000;
      if (away >= 90) welcome = game.catchUp(away);
      else if (away >= 1) game.catchUp(away);
    } else {
      game.reset();
      hasSave = false;
    }
  }

  /* --------------------------------------------------------- engine events */
  // dividends arrive per stock every minute; show one summary toast at most every 20s
  let divAcc = 0, divTimer = 0, lastDivToast = 0;
  const divSyms = new Set();
  function flushDividends() {
    clearTimeout(divTimer); divTimer = 0;
    if (divAcc <= 0) return;
    lastDivToast = Date.now();
    ui.toast({ icon: '💸', title: '+' + fmt.money(divAcc) + ' in dividends', sub: Array.from(divSyms).join(' · '), kind: 'gold', ms: 2400 });
    PM.sfx.cash();
    divAcc = 0; divSyms.clear();
  }

  game.on((type, d) => {
    const s = game.s;
    if (type === 'news') {
      if (ui.tab !== 'market') ui.unreadNews = true;
      const holds = d.sym && s.holdings[d.sym];
      const ownsInd = d.kind === 'biz' && s.companies.some((c) => c.ind === d.ind);
      // news is always in the feed; only interrupt for things that matter, and never over an open sheet/page
      if (!ui.stack.length && (holds || ownsInd || d.mag === 'big' || d.mag === 'huge')) {
        ui.toast({
          icon: d.icon, title: d.text, kind: d.dir > 0 ? 'pos' : d.dir < 0 ? 'neg' : '',
          sub: holds ? 'You own ' + d.sym + ' · tap to trade' : d.sym ? d.sym + ' · tap to view' : ownsInd ? 'Affects your business' : undefined,
          ms: d.mag === 'huge' ? 5200 : 4200,
          onTap: d.sym ? () => ui.push({ type: 'stock', sym: d.sym }) : d.kind === 'biz' ? () => ui.setTab('business') : null,
        });
        if (d.mag === 'huge') PM.haptic(40);
      }
    } else if (type === 'dividend') {
      divAcc += d.amt; divSyms.add(d.sym);
      const wait = 20000 - (Date.now() - lastDivToast);
      if (wait <= 0) flushDividends();
      else if (!divTimer) divTimer = setTimeout(flushDividends, wait);
    } else if (type === 'achievement') {
      ui.toast({ icon: d.a.icon, title: 'Goal complete: ' + d.a.name, sub: 'Reward +' + fmt.money(d.reward, { compact: true }), kind: 'gold', ms: 4200 });
      PM.sfx.win(); PM.haptic(60);
      if (d.a.id !== 'tap1') PM.fx.confetti();
    } else if (type === 'rd') {
      ui.toast({ icon: d.product.icon, title: 'New product: ' + d.product.name, sub: d.name + ' can now make it', kind: 'gold', ms: 4200 });
      PM.sfx.win(); PM.haptic(50); PM.fx.confetti();
    }
    ui.render();
  });

  /* ------------------------------------------------------------------ UI */
  const TABS = [['earn', 'Earn', 'coin'], ['market', 'Market', 'chart'], ['portfolio', 'Portfolio', 'pie'], ['business', 'Business', 'factory'], ['more', 'More', 'grid']];

  const RANKS = [[0, 'Flat broke'], [10, 'Pocket change'], [100, 'Hustler'], [1e3, 'Side-gig hero'], [1e4, 'Entrepreneur'], [1e5, 'Business owner'],
    [1e6, 'Millionaire'], [1e7, 'Tycoon'], [1e8, 'Magnate'], [1e9, 'Billionaire'], [1e10, 'Mogul'], [1e11, 'Empire builder'], [1e12, 'Trillionaire']];
  function rankTitle(nw) {
    let r = RANKS[0][1];
    for (const [min, name] of RANKS) if (nw >= min) r = name;
    return r;
  }

  function TopBar() {
    const inc = game.incomePerSec();
    return html`<div class="topbar">
      <div class="brand"><div class="brand-logo">🪙</div><div><div>Penny Mogul</div><small>${rankTitle(game.netWorth())}</small></div></div>
      <div class="cash-chip"><b><${PM.UI.AnimNum} value=${game.s.cash} /></b><span class=${inc > 0.0001 ? '' : 'zero'}>${inc > 0.0001 ? '+' + fmt.money(inc, { compact: true }) + '/s' : 'tap to earn'}</span></div>
    </div>`;
  }

  function BottomNav() {
    const idx = TABS.findIndex((t) => t[0] === ui.tab);
    return html`<nav class="nav" style=${{ '--i': idx }}>
      <div class="nav-ind"></div>
      ${TABS.map(([k, label, icon]) => html`<button key=${k} class=${'nav-item' + (ui.tab === k ? ' on' : '')} onClick=${() => ui.setTab(k)} aria-label=${label}>
        <${Icon} name=${icon} /><span>${label}</span>${k === 'market' && ui.unreadNews && ui.tab !== 'market' ? html`<i class="nav-dot"></i>` : null}</button>`)}
    </nav>`;
  }

  function Screen() {
    switch (ui.tab) {
      case 'market': return html`<${S.MarketScreen} />`;
      case 'portfolio': return html`<${S.PortfolioScreen} />`;
      case 'business': return html`<${B.BusinessScreen} />`;
      case 'more': return html`<${S.MoreScreen} />`;
      default: return html`<${S.EarnScreen} />`;
    }
  }

  let kseq = 0;
  function Overlay({ item }) {
    switch (item.type) {
      case 'stock': return html`<${S.StockSheet} item=${item} />`;
      case 'news': return html`<${S.NewsSheet} item=${item} />`;
      case 'found': return html`<${B.FoundSheet} item=${item} />`;
      case 'company': return html`<${B.CompanyPage} item=${item} />`;
      case 'linepick': return html`<${B.LinePicker} item=${item} />`;
      case 'confirm': return html`<${ConfirmModal} item=${item} />`;
      case 'intro': return html`<${S.IntroModal} item=${item} />`;
      case 'welcome': return html`<${S.WelcomeModal} item=${item} />`;
      default: return null;
    }
  }

  class Boundary extends preact.Component {
    componentDidCatch(err) { this.setState({ err }); try { console.error(err); } catch (e) { /* ignore */ } }
    render(props, state) {
      if (!state.err) return props.children;
      return html`<div class="modal"><div class="modal-emoji">🛠️</div><h2>Something broke</h2>
        <p class="lead">Your progress is saved. Reload to continue.</p>
        <button class="btn block btn-primary" onClick=${() => location.reload()}>Reload</button></div>`;
    }
  }

  function App() {
    const [, set] = useState(0);
    ui.render = () => set((n) => n + 1);
    return html`<${Boundary}><div class="app">
      <${TopBar} />
      <main class="main" key=${ui.tab}><${Screen} /></main>
      <${BottomNav} />
      ${ui.stack.map((it) => html`<${Overlay} key=${it.k || (it.k = ++kseq)} item=${it} />`)}
      <${ToastLayer} />
    </div><//>`;
  }

  preact.render(html`<${App} />`, document.getElementById('app'));

  if (!hasSave) { ui.push({ type: 'intro' }); game.save(); }
  else if (welcome && welcome.seconds >= 90) ui.push({ type: 'welcome', summary: welcome });

  /* ---------------------------------------------------------- game loop */
  let last = Date.now();
  function advance() {
    const now = Date.now();
    if (now < last) { last = now; return; } // clock moved backwards
    const n = Math.floor((now - last) / 1000);
    if (n <= 0) return;
    last += n * 1000;
    if (n <= 3) {
      for (let i = 0; i < n; i++) game.tick();
    } else {
      const sum = game.catchUp(n);
      if (sum && n >= 90 && !ui.stack.some((x) => x.type === 'welcome')) ui.push({ type: 'welcome', summary: sum });
    }
    ui.render();
  }
  setInterval(advance, 250);
  setInterval(() => game.save(), 6000);

  // Native shell hooks (see MainActivity) + browser lifecycle
  window.__pmPause = () => { game.save(); return true; };
  window.__pmResume = () => { advance(); return true; };
  document.addEventListener('visibilitychange', () => { if (document.hidden) game.save(); else advance(); });
  window.addEventListener('pagehide', () => game.save());
  window.addEventListener('pointerdown', () => PM.sfx.unlock(), { once: true, passive: true });
})();
