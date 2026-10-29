/* Penny Mogul — main tab screens (Earn, Market, Portfolio, More) and the stock trade sheet. */
(function () {
  'use strict';
  const PM = globalThis.PM;
  const html = PM.html;
  const { useRef, useEffect, useState } = preactHooks;
  const { fmt, ui } = PM;
  const { Icon, AnimNum, Change, StockBadge, Switch, Sheet, Modal } = PM.UI;
  const { PriceChart, Sparkline, Donut } = PM.Charts;

  const STOCK = {};
  PM.STOCKS.forEach((d) => { STOCK[d.sym] = d; });
  const openStock = (sym) => ui.push({ type: 'stock', sym });
  const g = () => PM.game;

  /* ============================================================== EARN */
  const RING = 2 * Math.PI * 46;

  function TapStage() {
    const coin = useRef(null);
    const game = g();
    const cs = game.comboState();
    const frac = cs.live ? Math.min(1, cs.n / 40) : 0;

    const onTap = (e) => {
      PM.sfx.unlock();
      const r = game.tap();
      PM.sfx.tap(r.combo);
      PM.haptic(r.combo > 25 ? 14 : 7);
      const x = e.clientX || window.innerWidth / 2, y = e.clientY || 300;
      PM.fx.floatText(x + (Math.random() * 30 - 15), y - 30, '+' + fmt.money(r.gain, { compact: true }), r.mult > 1.6 ? 'hot' : '');
      PM.fx.burst(x, y, { n: 4 + (r.combo > 20 ? 3 : 0), speed: 5.5 });
      const el = coin.current;
      el.classList.remove('pop'); void el.offsetWidth; el.classList.add('pop');
      ui.render();
      clearTimeout(ui._comboT);
      ui._comboT = setTimeout(ui.render, 1100);
    };

    return html`<div>
      <div class="tap-stage">
        <div class="tap-halo"></div>
        <svg class="combo-ring" viewBox="0 0 100 100">
          <defs><linearGradient id="comboGrad" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFC857"/><stop offset="1" stop-color="#FF6BD6"/></linearGradient></defs>
          <circle class="bg" cx="50" cy="50" r="46"/>
          <circle class="glow" cx="50" cy="50" r="46" stroke-dasharray=${RING} stroke-dashoffset=${RING * (1 - frac)}/>
          <circle class="fg" cx="50" cy="50" r="46" stroke-dasharray=${RING} stroke-dashoffset=${RING * (1 - frac)}/>
        </svg>
        <button class="coin" ref=${coin} onPointerDown=${onTap} aria-label="Tap to earn money"><span class="sym">$</span></button>
      </div>
      <div class="tap-info">
        <b>+${fmt.money(game.tapBase() * cs.mult, { compact: true })}</b><span class="muted bold"> per tap</span>
        <span class=${'streak' + (cs.live && cs.n > 2 ? '' : ' off')}>🔥 ×${cs.mult.toFixed(2)}</span>
      </div>
    </div>`;
  }

  function upgradeText(id, L) {
    const game = g(), v = (l) => game.upgradeValue(id, l);
    switch (id) {
      case 'tap': return 'Tap value ' + fmt.money(v(L)) + ' → ' + fmt.money(v(L + 1));
      case 'hustle': return (L ? fmt.rate(v(L)) : 'Nothing') + ' → ' + fmt.rate(v(L + 1)) + ' passive';
      case 'broker': return 'Trade fee ' + (v(L) * 100).toFixed(2) + '% → ' + (v(L + 1) * 100).toFixed(2) + '%';
      case 'lab': return 'Research time −' + Math.round(v(L) * 100) + '% → −' + Math.round(v(L + 1) * 100) + '%';
      default: return '';
    }
  }

  function UpgradeCard({ u }) {
    const game = g(), s = game.s;
    const L = s.up[u.id];
    const max = L >= u.max;
    const cost = u.cost(L);
    const can = !max && s.cash >= cost;
    const buy = (e) => {
      const r = game.buyUpgrade(u.id);
      if (r.ok) {
        PM.sfx.cash(); PM.haptic(18);
        PM.fx.burst(e.clientX, e.clientY, { n: 12, speed: 6 });
        ui.toast({ icon: u.icon, title: u.name + ' → Lv ' + s.up[u.id], sub: upgradeText(u.id, s.up[u.id]), kind: 'pos', ms: 2200 });
      } else { PM.sfx.error(); PM.haptic(30); }
      ui.render();
    };
    return html`<div class="card upg">
      <div class="upg-ico">${u.icon}</div>
      <div class="grow">
        <div class="bold">${u.name}<span class="lvl">Lv ${L}</span></div>
        <div class="small muted" style="margin-top:2px">${max ? 'Maxed out!' : upgradeText(u.id, L)}</div>
      </div>
      <button class=${'buy' + (can ? ' can' : '') + (max ? ' max' : '')} style=${{ '--p': Math.min(100, (s.cash / cost) * 100) + '%' }} disabled=${max} onClick=${buy}>
        <span class="fillbar"></span>
        <span>${max ? 'MAX' : fmt.money(cost, { compact: true })}</span>
        ${max ? null : html`<small style="position:relative">${can ? 'Upgrade' : Math.floor(Math.min(99, (s.cash / cost) * 100)) + '%'}</small>`}
      </button>
    </div>`;
  }

  function EarnScreen() {
    const game = g(), s = game.s;
    const nw = game.netWorth();
    const series = PM.seriesWindow(s.net, ui.netTf);
    const delta = series.length ? nw - series[0] : 0;
    const goals = PM.ACHIEVEMENTS.filter((a) => !s.ach[a.id]).slice(0, 3);
    const stocksV = game.portfolioValue(), bizV = game.businessValue();

    const goalAction = (a) => {
      if (a.id === 'trade1' || a.id === 'profit' || a.id === 'diverse' || a.id === 'div100' || a.id === 'bigwin') ui.setTab('market');
      else if (a.id === 'founder' || a.id === 'inventor' || a.id === 'tycoon' || a.id === 'empire') ui.setTab('business');
    };

    return html`<div>
      <div class="card hero">
        <div class="row between">
          <div class="hero-label">Net worth</div>
          ${series.length > 3 ? html`<${Change} pct=${series[0] > 1 ? nw / series[0] - 1 : null} abs=${delta} small=${true} />` : null}
        </div>
        <div class="hero-num"><${AnimNum} value=${nw} /></div>
        <div class="muted small bold" style="margin-top:4px">
          💵 ${fmt.money(s.cash, { compact: true })} · 📈 ${fmt.money(stocksV, { compact: true })} · 🏭 ${fmt.money(bizV, { compact: true })}
        </div>
        ${series.length > 3 ? html`<div class="spark"><${PriceChart} data=${series} height=${56} spark=${true} /></div>` : null}
      </div>

      <${TapStage} />

      <div class="sec-title"><span>Goals</span><span class="tiny dim">${Object.keys(s.ach).length}/${PM.ACHIEVEMENTS.length} done</span></div>
      <div class="card" style="padding:4px 16px">
        ${goals.length ? goals.map((a, i) => html`<button key=${a.id} class=${'goal' + (i === 0 ? ' first' : '')} style="width:100%;text-align:left" onClick=${() => goalAction(a)}>
          <div class="goal-ico">${a.icon}</div>
          <div class="grow"><div class="bold">${a.name}</div><div class="small muted">${a.hint}</div></div>
          <span class="pill gold">+${fmt.money(Math.max(a.reward, 90 * Math.max(0, game.incomePerSec())), { compact: true })}</span>
        </button>`) : html`<div class="goal"><div class="goal-ico">🏆</div><div class="grow bold">Every goal complete. Legend.</div></div>`}
      </div>

      <div class="sec-title"><span>Upgrades</span></div>
      ${PM.UPGRADES.map((u) => html`<${UpgradeCard} key=${u.id} u=${u} />`)}
    </div>`;
  }

  /* ============================================================ MARKET */
  const lastPx = {};

  function NewsLine({ n, now, onClick }) {
    const cls = n.dir > 0 ? 'pos' : n.dir < 0 ? 'neg' : '';
    const sec = n.sector && PM.SECTORS[n.sector];
    return html`<button class=${'news-line ' + cls + (now - n.t < 6 ? ' new' : '')} style="width:100%;text-align:left" onClick=${onClick}>
      <div class="news-dot">${n.icon}</div>
      <div class="grow">
        <div class="txt">${n.text}</div>
        <div class="meta">
          <span>${fmt.ago(now - n.t)}</span>
          ${n.sym ? html`<span class="pill acc" style="padding:1px 7px;font-size:11px">${n.sym}</span>` : sec ? html`<span style=${{ color: sec.color }}>${sec.name}</span>` : null}
          ${n.mag === 'huge' ? html`<span class="pill gold" style="padding:1px 7px;font-size:11px">🔥 HUGE</span>` : n.mag === 'big' ? html`<span class="pill warn" style="padding:1px 7px;font-size:11px">BIG</span>` : null}
          ${n.rumor ? html`<span class="pill flat" style="padding:1px 7px;font-size:11px">RUMOR</span>` : null}
        </div>
      </div>
    </button>`;
  }
  const newsTap = (n) => () => { if (n.sym) openStock(n.sym); else if (n.kind === 'biz') ui.setTab('business'); };

  function StockRow({ d, info, pos }) {
    const prev = lastPx[d.sym];
    const dir = prev == null || info.price === prev ? '' : info.price > prev ? 'up' : 'down';
    lastPx[d.sym] = info.price;
    const color = info.pct >= 0 ? PM.COLORS.up : PM.COLORS.down;
    return html`<button class="stock-row" onClick=${() => openStock(d.sym)}>
      <${StockBadge} d=${d} />
      <div class="grow">
        <div class="name ell">${d.sym}${pos ? html`<span class="owned-tag">OWNED</span>` : null}</div>
        <div class="sub ell">${d.name}</div>
      </div>
      <div class="spark"><${Sparkline} data=${info.series.length > 90 ? info.series.slice(-90) : info.series} color=${color} /></div>
      <div class="px">
        <b key=${info.price} class=${dir ? 'flash-' + dir : ''}>${fmt.price(info.price)}</b>
        <${Change} pct=${info.pct} small=${true} />
      </div>
    </button>`;
  }

  function MarketScreen() {
    const game = g(), s = game.s;
    const tf = ui.listTf;
    const idx = PM.seriesWindow(s.index, tf);
    const iNow = idx[idx.length - 1], iPct = idx[0] > 0 ? iNow / idx[0] - 1 : 0;
    let rows = PM.STOCKS.map((d) => ({ d, info: game.stockInfo(d.sym, tf), pos: s.holdings[d.sym] }));
    if (ui.filter === 'owned') rows = rows.filter((r) => r.pos);
    if (ui.filter === 'up') rows = rows.filter((r) => r.info.pct > 0).sort((a, b) => b.info.pct - a.info.pct);
    if (ui.filter === 'down') rows = rows.filter((r) => r.info.pct < 0).sort((a, b) => a.info.pct - b.info.pct);
    const filters = [['all', 'All'], ['owned', 'Owned'], ['up', 'Gainers'], ['down', 'Losers']];

    return html`<div>
      <div class="row between" style="margin:4px 4px 10px">
        <div class="hero-num sm" style="font-size:28px">Market</div>
        <div class="seg" style="width:130px;padding:3px">
          <button class=${tf === '10m' ? 'on' : ''} style="height:32px" onClick=${() => { ui.listTf = '10m'; ui.render(); }}>10M</button>
          <button class=${tf === '1h' ? 'on' : ''} style="height:32px" onClick=${() => { ui.listTf = '1h'; ui.render(); }}>1H</button>
        </div>
      </div>

      <div class="card hero">
        <div class="row between">
          <div><div class="hero-label">Mogul Index</div><div class="hero-num sm"><${AnimNum} value=${iNow} format=${(v) => v.toLocaleString('en-US', { maximumFractionDigits: 1, minimumFractionDigits: 1 })} /></div></div>
          <${Change} pct=${iPct} />
        </div>
        <div class="spark"><${PriceChart} data=${idx} height=${96} spark=${true} /></div>
      </div>

      <div class="sec-title"><span>Breaking news</span><button class="link" onClick=${() => ui.push({ type: 'news' })}>See all</button></div>
      <div class="card" style="padding:4px 14px">
        ${s.news.slice(0, 3).map((n) => html`<${NewsLine} key=${n.id} n=${n} now=${s.t} onClick=${newsTap(n)} />`)}
      </div>

      <div class="sec-title"><span>Stocks</span></div>
      <div class="chips" style="margin-bottom:8px">
        ${filters.map(([k, label]) => html`<button key=${k} class=${'chip' + (ui.filter === k ? ' on' : '')} onClick=${() => { ui.filter = k; ui.render(); }}>${label}</button>`)}
      </div>
      ${rows.length ? rows.map((r) => html`<${StockRow} key=${r.d.sym} d=${r.d} info=${r.info} pos=${r.pos} />`)
        : html`<div class="card empty"><div class="big">🔍</div><div class="bold">Nothing here</div><p>No stocks match this filter right now.</p></div>`}
    </div>`;
  }

  function NewsSheet({ item }) {
    const s = g().s;
    return html`<${Sheet} item=${item}>
      <div class="sheet-head"><div class="grow hero-num sm" style="font-size:24px">Market news</div><button class="x-btn" onClick=${() => ui.pop()}><${Icon} name="x" /></button></div>
      <div class="sheet-scroll">
        <div class="card" style="padding:4px 14px">
          ${s.news.map((n) => html`<${NewsLine} key=${n.id} n=${n} now=${s.t} onClick=${() => { if (n.sym) openStock(n.sym); }} />`)}
        </div>
      </div>
    <//>`;
  }

  /* ========================================================= STOCK SHEET */
  const TFS = [['1m', '1M'], ['10m', '10M'], ['1h', '1H'], ['8h', '8H']];

  function StockSheet({ item }) {
    const game = g(), s = game.s, d = STOCK[item.sym];
    const [tf, setTfState] = useState(ui.stockTf);
    const [mode, setMode] = useState('buy');
    const [amt, setAmt] = useState('');
    const [preset, setPreset] = useState('');
    const [scrub, setScrub] = useState(null);
    const prev = useRef(null);

    const info = game.stockInfo(d.sym, tf);
    const pos = game.position(d.sym);
    const price = info.price;
    const dir = prev.current == null || price === prev.current ? '' : price > prev.current ? 'up' : 'down';
    useEffect(() => { prev.current = price; });

    const shown = scrub ? scrub.value : price;
    const chg = shown - info.first;
    const pct = info.first > 0 ? shown / info.first - 1 : 0;
    const cash = s.cash;
    const amtN = parseFloat(amt) || 0;
    const fee = game.feeRate();
    const sec = PM.SECTORS[d.sector];
    const volLvl = d.vol < 0.0035 ? 1 : d.vol < 0.0065 ? 2 : 3;
    const news = s.news.filter((n) => n.sym === d.sym).slice(0, 3);

    const chips = mode === 'buy'
      ? [['$10', 10], ['$100', 100], ['$1K', 1000], ['25%', cash * 0.25], ['50%', cash * 0.5], ['MAX', cash]]
      : [['25%', pos.value * 0.25], ['50%', pos.value * 0.5], ['75%', pos.value * 0.75], ['ALL', pos.value]];
    const pick = (label, v) => { setPreset(label); setAmt(v > 0 ? String(Math.floor(v * 100) / 100) : ''); PM.haptic(5); };

    const valid = mode === 'buy' ? amtN >= 0.01 && cash >= 0.01 : amtN >= 0.01 && pos.shares > 0;
    const estShares = mode === 'buy' ? (Math.min(amtN, cash) * (1 - fee)) / price : 0;
    const estProceeds = mode === 'sell' ? Math.min(amtN, pos.value) * (1 - fee) : 0;

    const go = (e) => {
      let r, kind = 'pos';
      if (mode === 'buy') {
        r = game.buy(d.sym, preset === 'MAX' ? s.cash : Math.min(amtN, s.cash));
        if (r.ok) {
          PM.sfx.buy();
          ui.toast({ icon: d.icon, title: 'Bought ' + fmt.num(r.shares) + ' ' + d.sym, sub: 'for ' + fmt.money(r.spent) + ' @ ' + fmt.price(price), kind: 'pos', ms: 2400 });
          PM.fx.burst(e.clientX, e.clientY, { n: 14, colors: ['#2DE2A6', '#7CF2C8', '#FFFFFF'], speed: 7 });
        }
      } else {
        r = preset === 'ALL' ? game.sellAll(d.sym) : game.sell(d.sym, Math.min(amtN / price, pos.shares));
        if (r.ok) {
          kind = r.pl >= 0 ? 'pos' : 'neg';
          PM.sfx.sell();
          ui.toast({ icon: d.icon, title: 'Sold ' + d.sym + ' for ' + fmt.money(r.proceeds), sub: (r.pl >= 0 ? 'Profit ' : 'Loss ') + fmt.money(Math.abs(r.pl)) + ' (' + fmt.pct(r.pct) + ')', kind, ms: 2800 });
          PM.fx.burst(e.clientX, e.clientY, r.pl >= 0 ? { n: 18, speed: 8 } : { n: 10, colors: ['#FF5C7C', '#FF9BAE'], speed: 5 });
        }
      }
      if (r.ok) { PM.haptic(22); setAmt(''); setPreset(''); }
      else { PM.sfx.error(); PM.haptic(40); ui.toast({ icon: '⚠️', title: r.msg, kind: 'neg', ms: 1800 }); }
      ui.render();
    };

    return html`<${Sheet} item=${item}>
      <div class="sheet-head">
        <${StockBadge} d=${d} />
        <div class="grow">
          <div class="bold" style="font-size:18px">${d.name}</div>
          <div class="row" style="gap:6px;margin-top:2px"><span class="small muted bold">${d.sym}</span><span class="pill" style=${{ color: sec.color, background: sec.color + '22', padding: '1px 8px', fontSize: '11.5px' }}>${sec.name}</span></div>
        </div>
        <button class="x-btn" onClick=${() => ui.pop()}><${Icon} name="x" /></button>
      </div>
      <div class="sheet-scroll">
        <div class="px-big" key=${scrub ? 's' + scrub.index : 'p' + price}><span class=${dir && !scrub ? 'flash-' + dir : ''}>${fmt.price(shown)}</span></div>
        <div class="row" style="margin-top:6px"><${Change} pct=${pct} abs=${chg} /><span class="tiny dim bold">${scrub ? 'at this point' : 'past ' + TFS.find((t) => t[0] === tf)[1].toLowerCase()}</span></div>

        <div style="margin:10px -18px 0"><${PriceChart} data=${info.series} height=${200} base=${info.first} onScrub=${setScrub} label=${(v) => fmt.price(v)} /></div>
        <div class="tf">${TFS.map(([k, label]) => html`<button key=${k} class=${tf === k ? 'on' : ''} onClick=${() => { ui.stockTf = k; setTfState(k); }}>${label}</button>`)}</div>

        <div class="stats">
          <div class="stat"><div class="k">LOW</div><div class="v">${fmt.price(info.lo)}</div></div>
          <div class="stat"><div class="k">HIGH</div><div class="v">${fmt.price(info.hi)}</div></div>
          <div class="stat"><div class="k">RISK</div><div class="v">${['Low', 'Medium', 'High'][volLvl - 1]}<span class="vol-dots">${[1, 2, 3].map((i) => html`<i class=${i <= volLvl ? 'on' : ''}></i>`)}</span></div></div>
        </div>
        <div class="tiny muted" style="margin:10px 2px 0">${d.blurb}${d.div > 0 ? ' Pays about ' + (d.div * 6000).toFixed(1) + '% per hour in dividends.' : ''}</div>

        ${pos.shares > 0 ? html`<div class="pos-card">
          <div class="row between"><div class="bold">Your position</div><${Change} pct=${pos.pct} abs=${pos.pl} small=${true} /></div>
          <div class="pos-grid">
            <div><div class="k">Shares</div><div class="v">${fmt.num(pos.shares)}</div></div>
            <div><div class="k">Market value</div><div class="v">${fmt.money(pos.value)}</div></div>
            <div><div class="k">Avg cost / share</div><div class="v">${fmt.price(pos.cost / pos.shares)}</div></div>
            <div><div class="k">Total invested</div><div class="v">${fmt.money(pos.cost)}</div></div>
          </div></div>` : html`<div style="height:14px"></div>`}

        <div class=${'seg m-' + mode}>
          <button class=${mode === 'buy' ? 'on' : ''} onClick=${() => { setMode('buy'); setAmt(''); setPreset(''); }}>Buy</button>
          <button class=${mode === 'sell' ? 'on' : ''} onClick=${() => { setMode('sell'); setAmt(''); setPreset(''); }}>Sell</button>
        </div>
        <div class=${'amt-chips' + (mode === 'sell' ? ' four' : '')}>
          ${chips.map(([label, v]) => html`<button key=${label} class=${preset === label ? 'on' : ''} disabled=${mode === 'buy' ? (label[0] === '$' ? cash < v : cash < 0.01) : pos.shares <= 0} onClick=${() => pick(label, v)}>${label}</button>`)}
        </div>
        <div class="input-wrap"><span class="unit">$</span>
          <input class="input" type="text" inputmode="decimal" placeholder=${mode === 'buy' ? 'Amount to invest' : 'Amount to sell'} value=${amt}
            onInput=${(e) => { setPreset(''); setAmt(e.target.value.replace(/[^0-9.]/g, '').replace(/(\..*)\./g, '$1').slice(0, 14)); }} /></div>
        <div class="row between small muted bold" style="margin:10px 2px 14px">
          <span>${mode === 'buy' ? 'Cash: ' + fmt.money(cash) : 'Position: ' + fmt.money(pos.value)}</span>
          <span>${mode === 'buy' ? (amtN >= 0.01 ? '≈ ' + fmt.num(estShares) + ' shares' : 'Fee ' + (fee * 100).toFixed(2) + '%') : (amtN >= 0.01 ? '≈ ' + fmt.money(estProceeds) + ' after fee' : 'Fee ' + (fee * 100).toFixed(2) + '%')}</span>
        </div>
        ${mode === 'buy' && cash < 0.01 ? html`<div class="tip" style="margin-bottom:12px">💡 You are out of cash. Tap the coin on the Earn tab!</div>` : null}
        <button class=${'btn block ' + (mode === 'buy' ? 'btn-up' : 'btn-down')} disabled=${!valid} onClick=${go}>
          ${mode === 'buy' ? 'Buy ' + d.sym + (amtN >= 0.01 ? ' · ' + fmt.money(Math.min(amtN, cash)) : '') : 'Sell ' + d.sym + (amtN >= 0.01 ? ' · ' + fmt.money(Math.min(amtN, pos.value)) : '')}
        </button>

        ${news.length ? html`<div class="sec-title" style="margin-top:22px"><span>Latest on ${d.sym}</span></div>
          <div class="card" style="padding:4px 14px">${news.map((n) => html`<${NewsLine} key=${n.id} n=${n} now=${s.t} />`)}</div>` : null}
      </div>
    <//>`;
  }

  /* ========================================================== PORTFOLIO */
  function PortfolioScreen() {
    const game = g(), s = game.s;
    const nw = game.netWorth();
    const stocksV = game.portfolioValue(), bizV = game.businessValue();
    const series = PM.seriesWindow(s.net, ui.netTf);
    const [scrub, setScrub] = useState(null);
    const shown = scrub ? scrub.value : nw;
    const first = series.length ? series[0] : 0;
    let invested = 0;
    const hold = Object.keys(s.holdings).map((sym) => ({ d: STOCK[sym], pos: game.position(sym), info: game.stockInfo(sym, '10m') }))
      .sort((a, b) => b.pos.value - a.pos.value);
    hold.forEach((h) => { invested += h.pos.cost; });
    const unreal = stocksV - invested;
    const segs = [
      { label: 'Cash', value: s.cash, color: '#22D3EE' },
      { label: 'Stocks', value: stocksV, color: '#7C6CFF' },
      { label: 'Businesses', value: bizV, color: '#FFC857' },
    ];
    const total = segs.reduce((a, x) => a + x.value, 0) || 1;
    const tfs = [['10m', '10M'], ['1h', '1H'], ['8h', '8H']];

    return html`<div>
      <div class="row between" style="margin:4px 4px 10px"><div class="hero-num sm" style="font-size:28px">Portfolio</div></div>
      <div class="card">
        <div class="hero-label">Total net worth</div>
        <div class="hero-num"><${AnimNum} value=${shown} /></div>
        <div class="row" style="margin-top:6px"><${Change} pct=${first > 1 ? shown / first - 1 : null} abs=${shown - first} small=${true} /><span class="tiny dim bold">${scrub ? 'at this point' : 'over this period'}</span></div>
        ${series.length > 3 ? html`<div style="margin:8px -16px 0"><${PriceChart} data=${series} height=${150} onScrub=${setScrub} label=${(v) => fmt.money(v, { compact: true })} /></div>`
          : html`<div class="tiny dim center" style="padding:30px 0">Your net worth chart fills in as you play…</div>`}
        <div class="tf" style="grid-template-columns:repeat(3,1fr);margin-bottom:0">${tfs.map(([k, label]) => html`<button key=${k} class=${ui.netTf === k ? 'on' : ''} onClick=${() => { ui.netTf = k; ui.render(); }}>${label}</button>`)}</div>
      </div>

      <div class="sec-title"><span>Allocation</span></div>
      <div class="card alloc">
        <${Donut} segments=${segs} size=${150}><div><div class="tiny muted bold">Cash</div><div class="bold" style="font-size:15px">${Math.round((s.cash / total) * 100)}%</div></div><//>
        <div class="legend">${segs.map((x) => html`<div class="li"><i style=${{ background: x.color }}></i>${x.label}<span>${fmt.money(x.value, { compact: true })}<div class="tiny dim">${Math.round((x.value / total) * 100)}%</div></span></div>`)}</div>
      </div>

      <div class="sec-title"><span>Holdings</span><span class="tiny dim">${hold.length} stock${hold.length === 1 ? '' : 's'}</span></div>
      ${hold.length ? html`<div>
        <div class="card" style="padding:4px 16px;margin-bottom:12px">
          <div class="kv"><span class="k">Invested</span><b>${fmt.money(invested)}</b></div>
          <div class="kv"><span class="k">Current value</span><b>${fmt.money(stocksV)}</b></div>
          <div class="kv"><span class="k">Unrealized P/L</span><b class=${unreal >= 0 ? 'up' : 'down'}>${fmt.money(unreal, { sign: true })}</b></div>
        </div>
        ${hold.map((h) => html`<button key=${h.d.sym} class="stock-row" onClick=${() => openStock(h.d.sym)}>
          <${StockBadge} d=${h.d} />
          <div class="grow"><div class="name">${h.d.sym}</div><div class="sub">${fmt.num(h.pos.shares)} shares</div></div>
          <div class="px"><b>${fmt.money(h.pos.value)}</b><${Change} pct=${h.pos.pct} small=${true} /></div>
        </button>`)}</div>`
        : html`<div class="card empty"><div class="big">📭</div><div class="bold" style="font-size:17px">No investments yet</div><p>Earn some cash, then put it to work in the market.</p><button class="btn btn-primary" onClick=${() => ui.setTab('market')}>Browse the market</button></div>`}

      <div class="sec-title"><span>Lifetime</span></div>
      <div class="card" style="padding:4px 16px">
        <div class="kv"><span class="k">Realized trading P/L</span><b class=${s.stats.realized >= 0 ? 'up' : 'down'}>${fmt.money(s.stats.realized, { sign: true })}</b></div>
        <div class="kv"><span class="k">Dividends collected</span><b>${fmt.money(s.stats.dividends)}</b></div>
        <div class="kv"><span class="k">Trades made</span><b>${s.stats.trades}</b></div>
      </div>
    </div>`;
  }

  /* ================================================================ MORE */
  function MoreScreen() {
    const game = g(), s = game.s;
    const set = (k, v) => { s.settings[k] = v; game.save(); ui.render(); };
    const done = Object.keys(s.ach).length;
    const rows = [
      ['Time played', fmt.dur((Date.now() - s.stats.startedAt) / 1000)],
      ['Total taps', s.stats.taps.toLocaleString('en-US')],
      ['Earned from taps', fmt.money(s.stats.tapEarned)],
      ['Business revenue', fmt.money(s.stats.bizRevenue)],
      ['Peak net worth', fmt.money(s.stats.peakNet)],
      ['Products invented', s.stats.researched],
    ];
    return html`<div>
      <div class="row between" style="margin:4px 4px 10px"><div class="hero-num sm" style="font-size:28px">More</div></div>
      <div class="card">
        <div class="row between"><div class="bold" style="font-size:17px">Achievements</div><span class="pill gold">${done}/${PM.ACHIEVEMENTS.length}</span></div>
        <div class="ach-grid" style="margin-top:14px">
          ${PM.ACHIEVEMENTS.map((a) => html`<button key=${a.id} class=${'ach' + (s.ach[a.id] ? ' done' : '')}
            onClick=${() => ui.toast({ icon: a.icon, title: a.name, sub: s.ach[a.id] ? a.desc : 'Locked · ' + a.desc, kind: s.ach[a.id] ? 'gold' : '', ms: 2600 })}>${a.icon}</button>`)}
        </div>
      </div>

      <div class="sec-title"><span>Your stats</span></div>
      <div class="card" style="padding:4px 16px">${rows.map(([k, v]) => html`<div class="kv"><span class="k">${k}</span><b>${v}</b></div>`)}</div>

      <div class="sec-title"><span>Settings</span></div>
      <div class="card" style="padding:4px 16px">
        <div class="setting"><span>🔊 Sound effects</span><${Switch} on=${s.settings.sound} onChange=${(v) => { set('sound', v); if (v) PM.sfx.pop(); }} /></div>
        <div class="setting"><span>📳 Haptics</span><${Switch} on=${s.settings.haptics} onChange=${(v) => { set('haptics', v); if (v) PM.haptic(20); }} /></div>
        <button class="setting" style="width:100%" onClick=${() => ui.push({ type: 'intro' })}><span>❓ How to play</span><span class="dim"><${Icon} name="chevron" size=${18} /></span></button>
        <button class="setting down" style="width:100%" onClick=${() => ui.confirm({ emoji: '🗑️', title: 'Reset everything?', danger: true, yes: 'Reset game',
          text: 'This deletes your cash, stocks, companies and achievements. There is no undo.',
          onYes: () => { game.wipe(); ui.stack.length = 0; ui.tab = 'earn'; ui.push({ type: 'intro' }); ui.toast({ icon: '🔄', title: 'Fresh start!', sub: 'Good luck, mogul.', ms: 2200 }); } })}>
          <span>Reset progress</span></button>
      </div>
      <div class="center tiny dim" style="margin:22px 0 4px">Penny Mogul 1.0 · all money is fake, all fun is real</div>
    </div>`;
  }

  /* ============================================================ MODALS */
  function IntroModal({ item }) {
    const steps = [['👆', 'Tap the coin', 'Every tap earns cash. Tap fast to build a streak bonus.'],
      ['📈', 'Trade wild stocks', '12 fake companies that rise and fall on news, trends and rumors.'],
      ['🏭', 'Build companies', 'Open lemonade stands, then factories. Invent products, set prices, expand.']];
    return html`<${Modal} item=${item}>
      <div class="modal-emoji">🪙</div>
      <h2>Penny Mogul</h2>
      <p class="lead">Turn pocket change into a trillion-dollar empire.</p>
      <div class="col gap12" style="margin-bottom:18px">${steps.map(([e, t, d]) => html`<div class="row" style="align-items:flex-start;gap:14px">
        <div class="goal-ico" style="width:44px;height:44px;font-size:22px">${e}</div><div><div class="bold">${t}</div><div class="small muted">${d}</div></div></div>`)}</div>
      <button class="btn block btn-gold" onClick=${() => { PM.sfx.unlock(); ui.pop(); }}>Let's go!</button>
    <//>`;
  }

  function WelcomeModal({ item }) {
    const sm = item.summary;
    const lines = [
      sm.revenue > 0 && ['🏭', 'Business sales', sm.revenue, 'up'],
      sm.dividends > 0 && ['💸', 'Dividends', sm.dividends, 'up'],
      Math.abs(sm.portfolio) > 0.005 && ['📈', 'Portfolio moved', sm.portfolio, sm.portfolio >= 0 ? 'up' : 'down'],
    ].filter(Boolean);
    return html`<${Modal} item=${item}>
      <div class="modal-emoji">👋</div>
      <h2>Welcome back!</h2>
      <p class="lead">You were away for ${fmt.dur(sm.seconds)}${sm.capped ? ' (max offline time)' : ''}.</p>
      <div class="card" style="padding:4px 16px;margin-bottom:16px">
        ${lines.length ? lines.map(([e, k, v, c]) => html`<div class="kv"><span class="k">${e} ${k}</span><b class=${c}>${fmt.money(v, { sign: true })}</b></div>`)
          : html`<div class="kv"><span class="k">Nothing happened. Start a company to earn while away!</span></div>`}
        <div class="kv"><span class="k">💵 Cash change</span><b class=${sm.cash >= 0 ? 'up' : 'down'}>${fmt.money(sm.cash, { sign: true })}</b></div>
      </div>
      <button class="btn block btn-primary" onClick=${() => ui.pop()}>Nice!</button>
    <//>`;
  }

  PM.Screens = { EarnScreen, MarketScreen, PortfolioScreen, MoreScreen, StockSheet, NewsSheet, IntroModal, WelcomeModal };
})();
