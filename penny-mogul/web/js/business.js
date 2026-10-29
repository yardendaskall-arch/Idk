/* Penny Mogul — Business tab, company founding flow and the company management page. */
(function () {
  'use strict';
  const PM = globalThis.PM;
  const html = PM.html;
  const { useState } = preactHooks;
  const { fmt, ui, BIZ } = PM;
  const { Icon, AnimNum, IndBadge, Switch, Sheet } = PM.UI;
  const g = () => PM.game;

  /* ---------------------------------------------------------- helpers */
  function alerts(co) {
    const out = [];
    const ind = PM.IND[co.ind];
    let cash = false, full = false, idle = false;
    for (const pr of ind.products) {
      const ps = co.prods[pr.id];
      if (!ps.unlocked) continue;
      if (ps.status === 'cash') cash = true;
      if (ps.status === 'full') full = true;
    }
    if (cash) out.push(['warn', '💸 Out of cash for materials']);
    if (full) out.push(['flat', '📦 Warehouse full']);
    return out;
  }
  const researching = (co) => {
    const ind = PM.IND[co.ind];
    const pr = ind.products.find((p) => co.prods[p.id].rd);
    return pr ? { pr, rd: co.prods[pr.id].rd } : null;
  };

  /* ====================================================== BUSINESS TAB */
  function CompanyCard({ co }) {
    const game = g(), s = game.s;
    const ind = PM.IND[co.ind];
    const profit = game.companyProfit(co);
    const rd = researching(co);
    const al = alerts(co);
    return html`<button class="card co-card" style=${{ '--glow': ind.color + '40' }} onClick=${() => ui.push({ type: 'company', id: co.id })}>
      <div class="row">
        <${IndBadge} ind=${ind} />
        <div class="grow"><div class="bold ell" style="font-size:17px">${co.name}</div><div class="small muted ell">${ind.name} · ${co.lines.length} line${co.lines.length === 1 ? '' : 's'}</div></div>
        <span class=${'pill ' + (profit > 0.005 ? 'up' : profit < -0.005 ? 'down' : 'flat')}>${fmt.money(profit, { sign: true, compact: true })}/s</span>
      </div>
      <div class="co-metrics">
        <div><div class="k">Value</div><div class="v">${fmt.money(game.companyValue(co), { compact: true })}</div></div>
        <div><div class="k">Revenue</div><div class="v">${fmt.rate(co.rev)}</div></div>
        <div><div class="k">Costs</div><div class="v">${fmt.rate(co.exp)}</div></div>
      </div>
      ${rd ? html`<div style="margin-top:12px"><div class="row between tiny bold muted" style="margin-bottom:5px"><span><span class="rd-ring"></span>Researching ${rd.pr.name}</span><span>${fmt.dur(rd.rd.left)}</span></div>
        <div class="bar thin"><i style=${{ width: (1 - rd.rd.left / rd.rd.total) * 100 + '%' }}></i></div></div>` : null}
      ${al.length ? html`<div class="row" style="margin-top:10px;flex-wrap:wrap">${al.map(([k, t]) => html`<span class=${'pill ' + k}>${t}</span>`)}</div>` : null}
    </button>`;
  }

  function BusinessScreen() {
    const game = g(), s = game.s;
    const profit = game.bizProfit();
    const owned = new Set(s.companies.map((c) => c.ind));
    const live = s.events.filter((e) => e.until > s.t && (e.ind === 'all' ? s.companies.length > 0 : owned.has(e.ind)));
    return html`<div>
      <div class="row between" style="margin:4px 4px 10px"><div class="hero-num sm" style="font-size:28px">Business</div></div>
      <div class="card hero">
        <div class="hero-label">Profit per second</div>
        <div class="hero-num"><span class=${profit < 0 ? 'down' : ''}><${AnimNum} value=${profit} format=${(v) => fmt.money(v, { compact: true }) + '/s'} /></span></div>
        <div class="muted small bold" style="margin-top:6px">${s.companies.length} ${s.companies.length === 1 ? 'company' : 'companies'} · worth ${fmt.money(game.businessValue(), { compact: true })} · ${fmt.money(s.stats.bizRevenue, { compact: true })} lifetime revenue</div>
      </div>

      ${live.length ? html`<div class="sec-title"><span>Market conditions</span></div>
        <div>${live.map((e) => html`<div key=${e.id} class=${'event-chip ' + (e.mult > 1 === (e.kind === 'demand') ? 'pos' : 'neg')}>
          <span style="font-size:20px">${e.icon}</span><span class="grow">${e.text}</span><span class="tiny dim bold">${fmt.dur(e.until - s.t)}</span></div>`)}</div>` : null}

      <div class="sec-title"><span>Your companies</span></div>
      ${s.companies.map((co) => html`<${CompanyCard} key=${co.id} co=${co} />`)}
      ${s.companies.length === 0 ? html`<div class="card empty" style="margin-bottom:12px"><div class="big">🏭</div><div class="bold" style="font-size:17px">Be your own boss</div>
        <p>Start a company, make products, and earn money even while you are away.</p></div>` : null}
      <button class="add-co" onClick=${() => ui.push({ type: 'found' })}>
        <div class="plus"><${Icon} name="plus" /></div>
        <div class="grow"><div class="bold" style="font-size:16px">Start a new company</div><div class="small muted">Starting from ${fmt.money(PM.INDUSTRIES[0].startup)} · ${PM.INDUSTRIES.length} industries</div></div>
        <span class="dim"><${Icon} name="chevron" size=${20} /></span>
      </button>
    </div>`;
  }

  /* ======================================================== FOUND SHEET */
  function FoundSheet({ item }) {
    const game = g(), s = game.s;
    const [sel, setSel] = useState(null);
    const [name, setName] = useState('');

    const choose = (ind) => {
      if (s.cash < ind.startup) {
        PM.sfx.error(); PM.haptic(30);
        ui.toast({ icon: '🔒', title: 'Need ' + fmt.money(ind.startup - s.cash, { compact: true }) + ' more', sub: ind.name + ' costs ' + fmt.money(ind.startup), kind: 'neg', ms: 1900 });
        return;
      }
      PM.sfx.pop(); setSel(ind); setName(game.defaultName(ind));
    };
    const found = (e) => {
      const r = game.foundCompany(sel.id, name);
      if (!r.ok) { PM.sfx.error(); ui.toast({ icon: '⚠️', title: r.msg, kind: 'neg' }); return; }
      PM.sfx.win(); PM.haptic(30); PM.fx.confetti();
      ui.toast({ icon: sel.icon, title: r.company.name + ' is open for business!', sub: 'Start producing, then expand.', kind: 'gold', ms: 3000 });
      ui.replace(item, { type: 'company', id: r.company.id });
    };

    if (sel) {
      return html`<${Sheet} item=${item}>
        <div class="sheet-head"><button class="x-btn" onClick=${() => setSel(null)}><${Icon} name="back" /></button><div class="grow hero-num sm" style="font-size:22px">Name your company</div><button class="x-btn" onClick=${() => ui.pop()}><${Icon} name="x" /></button></div>
        <div class="sheet-scroll">
          <div class="card row" style="gap:14px"><${IndBadge} ind=${sel} size="lg" /><div class="grow"><div class="bold ell" style="font-size:18px">${name || 'Unnamed'}</div><div class="small muted">${sel.name}</div></div></div>
          <div class="row" style="margin-top:14px;gap:10px">
            <input class="input" type="text" maxlength="24" value=${name} placeholder="Company name" onInput=${(e) => setName(e.target.value)} />
            <button class="x-btn" style="width:54px;height:54px;border-radius:15px" onClick=${() => { PM.sfx.pop(); setName(game.defaultName(sel)); }}><${Icon} name="dice" size=${24} /></button>
          </div>
          <div class="card" style="margin-top:14px;padding:6px 16px">
            <div class="kv"><span class="k">You start with</span><b>1 line · ${sel.products[0].icon} ${sel.products[0].name}</b></div>
            <div class="kv"><span class="k">Unlock later</span><b>${sel.products.slice(1).map((p) => p.icon).join(' ')}</b></div>
            <div class="kv"><span class="k">Startup cost</span><b>${fmt.money(sel.startup)}</b></div>
          </div>
          <button class="btn block btn-gold" style="margin-top:16px" onClick=${found}>Found company · ${fmt.money(sel.startup)}</button>
        </div>
      <//>`;
    }

    const owned = (id) => s.companies.filter((c) => c.ind === id).length;
    return html`<${Sheet} item=${item}>
      <div class="sheet-head"><div class="grow hero-num sm" style="font-size:24px">Start a company</div><button class="x-btn" onClick=${() => ui.pop()}><${Icon} name="x" /></button></div>
      <div class="sheet-scroll">
        <div class="small muted" style="margin:0 2px 12px">Pick an industry. Pricier ones earn far more.</div>
        ${PM.INDUSTRIES.map((ind) => {
          const can = s.cash >= ind.startup;
          return html`<button key=${ind.id} class=${'ind-card' + (can ? '' : ' locked')} onClick=${() => choose(ind)}>
            <${IndBadge} ind=${ind} size="lg" />
            <div class="grow">
              <div class="row between"><div class="bold" style="font-size:16.5px">${ind.name}${owned(ind.id) ? html`<span class="lvl">×${owned(ind.id)}</span>` : null}</div>
                <b class=${can ? 'gold' : 'muted'}>${can ? '' : '🔒 '}${fmt.money(ind.startup, { compact: true })}</b></div>
              <div class="small muted">${ind.desc}</div>
              <div class="ind-prods">${ind.products.map((p) => html`<span title=${p.name}>${p.icon}</span>`)}</div>
              ${can ? null : html`<div class="bar thin" style="margin-top:8px"><i style=${{ width: Math.min(100, (s.cash / ind.startup) * 100) + '%' }}></i></div>`}
            </div>
          </button>`;
        })}
      </div>
    <//>`;
  }

  /* ====================================================== COMPANY PAGE */
  function LinePicker({ item }) {
    const game = g();
    const co = game.findCo(item.id);
    if (!co) return null;
    const ind = PM.IND[co.ind];
    return html`<${Sheet} item=${item}>
      <div class="sheet-head"><div class="grow hero-num sm" style="font-size:22px">Line ${item.idx + 1} makes…</div><button class="x-btn" onClick=${() => ui.pop()}><${Icon} name="x" /></button></div>
      <div class="sheet-scroll">
        ${ind.products.map((pr) => {
          const ps = co.prods[pr.id];
          const on = co.lines[item.idx] === pr.id;
          return html`<button key=${pr.id} class="ind-card" style=${on ? 'border-color:rgba(124,108,255,.7);background:rgba(124,108,255,.12)' : ps.unlocked ? '' : 'opacity:.4'}
            disabled=${!ps.unlocked} onClick=${() => { game.setLineProduct(co.id, item.idx, pr.id); PM.sfx.pop(); PM.haptic(10); ui.pop(); }}>
            <div class="badge sq sm" style="background:rgba(255,255,255,.1)">${pr.icon}</div>
            <div class="grow"><div class="bold">${pr.name}</div>
              <div class="small muted">${ps.unlocked ? fmt.units(pr.rate * (1 + 0.25 * co.up.spd)) + ' ' + pr.unit + '/s · ' + fmt.money(pr.price - pr.cost, { compact: true }) + ' margin' : 'Not researched yet'}</div></div>
            ${on ? html`<span class="up"><${Icon} name="check" /></span>` : ps.unlocked ? null : html`<span class="dim"><${Icon} name="lock" size=${20} /></span>`}
          </button>`;
        })}
      </div>
    <//>`;
  }

  const STATUS = {
    idle:   ['warn', 'No production line is making this. Assign a line to start.'],
    cash:   ['bad', 'Out of cash for materials. Production is stalled!'],
    full:   ['warn', 'Warehouse is full, so production is paused. Lower the price or boost Marketing.'],
    demand: ['info', 'Customers want less than you make. Lower the price or boost Marketing.'],
    supply: ['good', 'You sell everything you make. Add lines or Assembly Speed to earn more.'],
  };

  function ProductCard({ co, pr, ind }) {
    const game = g(), s = game.s;
    const ps = co.prods[pr.id];

    if (!ps.unlocked) {
      const prevOk = pr.tier === 0 || co.prods[ind.products[pr.tier - 1].id].unlocked;
      const busy = ind.products.some((p) => co.prods[p.id].rd);
      const can = prevOk && !busy && s.cash >= pr.rdCost;
      const start = (e) => {
        const r = game.startResearch(co.id, pr.id);
        if (r.ok) { PM.sfx.cash(); PM.haptic(20); ui.toast({ icon: '🔬', title: 'Researching ' + pr.name, sub: 'Ready in ' + fmt.dur(r.time), kind: '', ms: 2400 }); }
        else { PM.sfx.error(); PM.haptic(30); ui.toast({ icon: '⚠️', title: r.msg, kind: 'neg', ms: 1800 }); }
        ui.render();
      };
      return html`<div class="card prod locked-prod">
        <div class="row">
          <div class="badge sq sm" style="background:rgba(255,255,255,.08);filter:grayscale(${ps.rd ? 0 : 0.8})">${pr.icon}</div>
          <div class="grow"><div class="bold">${pr.name}</div>
            <div class="small muted">Sells for ${fmt.money(pr.price, { compact: true })} per ${pr.unit} · ${Math.round((1 - pr.cost / pr.price) * 100)}% margin</div></div>
          ${ps.rd ? null : html`<span class="dim"><${Icon} name="lock" size=${20} /></span>`}
        </div>
        ${ps.rd ? html`<div style="margin-top:12px"><div class="row between tiny bold muted" style="margin-bottom:5px"><span><span class="rd-ring"></span>Researching…</span><span>${fmt.dur(ps.rd.left)} left</span></div>
          <div class="bar"><i style=${{ width: (1 - ps.rd.left / ps.rd.total) * 100 + '%' }}></i></div></div>`
          : html`<button class=${'btn block ' + (can ? 'btn-primary' : 'btn-ghost')} style="margin-top:12px" disabled=${!can} onClick=${start}>
            🔬 Research · ${fmt.money(pr.rdCost, { compact: true })} · ${fmt.dur(game.researchTime(pr))}</button>
            ${!prevOk ? html`<div class="hint info">Research ${ind.products[pr.tier - 1].name} first.</div>` : busy ? html`<div class="hint info">The lab is busy with another product.</div>`
              : s.cash < pr.rdCost ? html`<div class="hint info">Need ${fmt.money(pr.rdCost - s.cash, { compact: true })} more.</div>` : null}`}
      </div>`;
    }

    const info = game.productInfo(co, pr);
    const [hk, hText] = STATUS[info.lines === 0 ? 'idle' : info.status] || STATUS.idle;
    const pctMarket = Math.round(ps.price * 100);
    const fill = ((ps.price - BIZ.priceMin) / (BIZ.priceMax - BIZ.priceMin)) * 100;
    return html`<div class="card prod">
      <div class="row">
        <div class="badge sq sm" style="background:rgba(255,255,255,.1)">${pr.icon}</div>
        <div class="grow"><div class="bold">${pr.name}</div><div class="small muted">${info.lines} line${info.lines === 1 ? '' : 's'} · ${fmt.money(info.margin, { compact: true })} profit per ${pr.unit}</div></div>
        <span class=${'pill ' + (info.profitPerSec > 0.005 ? 'up' : 'flat')}>${fmt.money(info.profitPerSec, { compact: true })}/s</span>
      </div>
      <div class="prod-metrics">
        <div><div class="k">SELLING</div><div class="v">${fmt.units(info.sold)}/s</div></div>
        <div><div class="k">MAKING</div><div class="v">${fmt.units(info.supply)}/s</div></div>
        <div><div class="k">DEMAND</div><div class="v">${fmt.units(info.demand)}/s</div></div>
      </div>
      <div style="margin-top:12px">
        <div class="row between small bold"><span class="muted">Price per ${pr.unit}</span><span>${fmt.money(info.price, { compact: true })} <span class="dim">· ${pctMarket}% of market</span></span></div>
        <input type="range" min=${BIZ.priceMin} max=${BIZ.priceMax} step="0.01" value=${ps.price} style=${{ '--fill': fill + '%' }}
          onInput=${(e) => { game.setPrice(co.id, pr.id, parseFloat(e.target.value)); ui.render(); }} />
        <div class="row between small" style="margin-top:-2px"><span class="muted bold">Auto-price</span><${Switch} on=${ps.auto} onChange=${(v) => { game.setAuto(co.id, pr.id, v); PM.sfx.pop(); ui.render(); }} /></div>
      </div>
      <div style="margin-top:12px"><div class="row between tiny bold muted" style="margin-bottom:5px"><span>📦 Stock</span><span>${fmt.units(ps.inv)} / ${fmt.units(info.cap)}</span></div>
        <div class="bar thin"><i style=${{ width: Math.min(100, (ps.inv / info.cap) * 100) + '%' }}></i></div></div>
      <div class=${'hint ' + hk}>${hText}</div>
    </div>`;
  }

  function CompanyUpgrade({ co, kind }) {
    const game = g(), s = game.s;
    const info = BIZ.upInfo[kind];
    const L = co.up[kind], max = BIZ.maxCompanyUp[kind];
    const done = L >= max;
    const cost = done ? 0 : game.companyUpgradeCost(co, kind);
    const can = !done && s.cash >= cost;
    const buy = (e) => {
      const r = game.upgradeCompany(co.id, kind);
      if (r.ok) { PM.sfx.cash(); PM.haptic(18); PM.fx.burst(e.clientX, e.clientY, { n: 12, speed: 6 }); }
      else { PM.sfx.error(); PM.haptic(30); }
      ui.render();
    };
    return html`<div class="card upg" style="padding:12px 14px">
      <div class="upg-ico" style="width:44px;height:44px;font-size:22px">${info.icon}</div>
      <div class="grow"><div class="bold">${info.name}<span class="lvl">Lv ${L}</span></div><div class="small muted">${info.desc}</div>
        <div class="pips">${Array.from({ length: max }, (_, i) => html`<i class=${i < L ? 'on' : ''}></i>`)}</div></div>
      <button class=${'buy' + (can ? ' can' : '') + (done ? ' max' : '')} style=${{ '--p': (done ? 100 : Math.min(100, (s.cash / cost) * 100)) + '%' }} disabled=${done} onClick=${buy}>
        <span class="fillbar"></span><span>${done ? 'MAX' : fmt.money(cost, { compact: true })}</span></button>
    </div>`;
  }

  function CompanyPage({ item }) {
    const game = g(), s = game.s;
    const co = game.findCo(item.id);
    if (!co) { setTimeout(() => ui.pop(), 0); return null; }
    const ind = PM.IND[co.ind];
    const profit = game.companyProfit(co);
    const lineCost = game.lineCost(co);
    const canLine = co.lines.length < BIZ.maxLines && s.cash >= lineCost;
    const addLine = (e) => {
      const r = game.buildLine(co.id);
      if (r.ok) { PM.sfx.cash(); PM.haptic(20); PM.fx.burst(e.clientX, e.clientY, { n: 12, speed: 6 }); }
      else { PM.sfx.error(); PM.haptic(30); ui.toast({ icon: '⚠️', title: r.msg, kind: 'neg', ms: 1600 }); }
      ui.render();
    };
    const sell = () => ui.confirm({ emoji: '🏷️', title: 'Sell ' + co.name + '?', danger: true, yes: 'Sell for ' + fmt.money(game.companyValue(co), { compact: true }),
      text: 'You will receive ' + fmt.money(game.companyValue(co)) + ' and lose its lines, products and upgrades.',
      onYes: () => { const r = game.sellCompany(co.id); if (r.ok) { PM.sfx.cash(); ui.toast({ icon: '💰', title: 'Sold for ' + fmt.money(r.value), kind: 'gold' }); ui.pop(); } } });

    return html`<div class=${'page' + (item.closing ? ' closing' : '')}>
      <div class="page-top">
        <button class="x-btn" onClick=${() => ui.pop()}><${Icon} name="back" /></button>
        <div class="grow"><div class="bold ell" style="font-size:18px">${co.name}</div><div class="small muted">${ind.icon} ${ind.name}</div></div>
        <span class=${'pill ' + (profit > 0.005 ? 'up' : profit < -0.005 ? 'down' : 'flat')}>${fmt.money(profit, { sign: true, compact: true })}/s</span>
      </div>
      <div class="page-scroll">
        <div class="card hero" style=${{ '--glow': ind.color + '50' }}>
          <div class="row"><${IndBadge} ind=${ind} size="lg" />
            <div class="grow"><div class="hero-label">Company value</div><div class="hero-num sm"><${AnimNum} value=${game.companyValue(co)} format=${(v) => fmt.money(v, { compact: true })} /></div></div></div>
          <div class="co-metrics" style="margin-top:16px">
            <div><div class="k">Revenue</div><div class="v up">${fmt.rate(co.rev)}</div></div>
            <div><div class="k">Costs</div><div class="v down">${fmt.rate(co.exp)}</div></div>
            <div><div class="k">Lifetime profit</div><div class="v">${fmt.money(co.totRev - co.totExp, { compact: true })}</div></div>
          </div>
        </div>

        <div class="sec-title"><span>Production lines</span><span class="tiny dim">${co.lines.length}/${BIZ.maxLines}</span></div>
        <div class="line-grid">
          ${co.lines.map((pid, i) => {
            const pr = ind.products.find((p) => p.id === pid);
            return html`<button key=${i} class="line-card" onClick=${() => ui.push({ type: 'linepick', id: co.id, idx: i })}>
              <div class="li-ico">${pr.icon}</div><div class="grow"><div class="bold small ell">${pr.name}</div><div class="tiny muted">${fmt.units(pr.rate * (1 + 0.25 * co.up.spd))}/s · tap to change</div></div></button>`;
          })}
          ${co.lines.length < BIZ.maxLines ? html`<button class=${'line-card add' + (canLine ? ' can' : '')} onClick=${addLine}>
            <b style="font-size:15px">＋ Build line</b><span class="tiny bold">${fmt.money(lineCost, { compact: true })}</span></button>` : null}
        </div>

        <div class="sec-title"><span>Products</span><span class="tiny dim">${ind.products.filter((p) => co.prods[p.id].unlocked).length}/${ind.products.length} unlocked</span></div>
        ${ind.products.map((pr) => html`<${ProductCard} key=${pr.id} co=${co} pr=${pr} ind=${ind} />`)}

        <div class="sec-title"><span>Upgrades</span></div>
        ${['mkt', 'eff', 'spd', 'wh'].map((k) => html`<${CompanyUpgrade} key=${k} co=${co} kind=${k} />`)}

        <button class="btn btn-danger block" style="margin-top:26px" onClick=${sell}>Sell company · ${fmt.money(game.companyValue(co), { compact: true })}</button>
      </div>
    </div>`;
  }

  PM.Biz = { BusinessScreen, FoundSheet, CompanyPage, LinePicker };
})();
