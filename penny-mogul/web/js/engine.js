/* Penny Mogul — game engine. Pure logic, no DOM: runs in the WebView and in Node tests.
 *
 *   const g = PM.createGame({ seed });   // new game (or g.load(json))
 *   g.tick();                            // advance the world by one second
 *   g.on((type, data) => ...);           // 'news' | 'dividend' | 'achievement' | 'rd'
 */
(function () {
  'use strict';
  const PM = (globalThis.PM = globalThis.PM || {});
  const BIZ = PM.BIZ;

  const SAVE_KEY = 'pennymogul.save.v1';
  const WARMUP_TICKS = 14400;      // pre-simulated market history for a new game (4h)
  const MAX_OFFLINE = 6 * 3600;    // offline catch-up cap (seconds)
  const COMBO_WINDOW = 650;        // ms between taps that keeps a streak alive
  const VS = 0.55;                 // global market volatility scale

  // ---- price history: three resolutions so every chart timeframe has data
  const SERIES_SPEC = { a: [1, 600], b: [10, 360], c: [60, 480] };
  PM.TF = { '1m': { k: 'a', n: 60 }, '10m': { k: 'a', n: 600 }, '1h': { k: 'b', n: 360 }, '8h': { k: 'c', n: 480 } };

  function mkSeries(v) { return { a: [v], b: [v], c: [v] }; }
  function pushCap(arr, v, max) {
    arr.push(v);
    if (arr.length > max * 1.25) arr.splice(0, arr.length - max);
  }
  function pushSeries(sr, v, t, prec) {
    const r = prec === 4 ? Math.round(v * 1e4) / 1e4 : Math.round(v * 100) / 100;
    pushCap(sr.a, r, 600);
    if (t % 10 === 0) pushCap(sr.b, r, 360);
    if (t % 60 === 0) pushCap(sr.c, r, 480);
  }
  function trimSeries(sr) {
    for (const k in SERIES_SPEC) if (sr[k].length > SERIES_SPEC[k][1]) sr[k] = sr[k].slice(-SERIES_SPEC[k][1]);
  }
  PM.seriesWindow = function (sr, tf) {
    const spec = PM.TF[tf] || PM.TF['10m'];
    const a = sr[spec.k];
    return a.length > spec.n ? a.slice(-spec.n) : a;
  };

  function mulberry32(a) {
    return function () {
      a |= 0; a = (a + 0x6D2B79F5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  const clamp = (x, lo, hi) => Math.max(lo, Math.min(hi, x));

  PM.createGame = function (opts) {
    opts = opts || {};
    let rng = mulberry32(opts.seed != null ? opts.seed : (Date.now() ^ 0x9e3779b9) >>> 0);
    let spare = null;
    function gauss() {
      if (spare !== null) { const v = spare; spare = null; return v; }
      let u = 0;
      while (u === 0) u = rng();
      const v = rng();
      const m = Math.sqrt(-2 * Math.log(u));
      spare = m * Math.sin(2 * Math.PI * v);
      return m * Math.cos(2 * Math.PI * v);
    }
    const pick = (arr) => arr[Math.floor(rng() * arr.length)];

    const listeners = [];
    let silent = false;
    function emit(type, data) { if (!silent) for (const f of listeners) f(type, data); }

    const stockDef = {};
    PM.STOCKS.forEach((d, i) => { d.idx = i; stockDef[d.sym] = d; });
    const combo = { n: 0, last: 0 };

    /* ------------------------------------------------------------- state */
    function newState() {
      const stocks = {};
      PM.STOCKS.forEach((d) => {
        stocks[d.sym] = { price: d.price, trend: 0, trendLeft: 20 + Math.floor(rng() * 200), imp: [], ser: mkSeries(d.price) };
      });
      const sec = {};
      Object.keys(PM.SECTORS).forEach((k) => { sec[k] = { d: 0, r: 0 }; });
      return {
        v: 1, t: 0, cash: 0,
        up: { tap: 0, hustle: 0, broker: 0, lab: 0 },
        stocks, mkt: { m: 0, sec },
        index: mkSeries(1000), net: mkSeries(0),
        holdings: {}, companies: [], news: [], events: [], ach: {},
        stats: { taps: 0, tapEarned: 0, trades: 0, realized: 0, bestTrade: 0, bestPct: 0, dividends: 0, researched: 0, bizRevenue: 0, peakNet: 0, startedAt: Date.now() },
        nextId: 1, newsId: 1, lastSeen: Date.now(),
        settings: { sound: true, haptics: true },
      };
    }

    let s = newState();

    function warmup() {
      silent = true;
      for (let i = 0; i < WARMUP_TICKS; i++) { s.t++; stepWorld(); }
      silent = false;
      s.news = s.news.slice(0, 8);
      s.events = [];
      s.net = mkSeries(0);
    }

    /* ------------------------------------------------------------ market */
    function tickMarket() {
      const mk = s.mkt;
      mk.m += -0.01 * mk.m + 0.00006 * VS * gauss();
      const mret = 0.000012 + mk.m + 0.0007 * VS * gauss();
      for (const k in mk.sec) {
        const x = mk.sec[k];
        x.d += -0.012 * x.d + 0.00007 * VS * gauss();
        x.r = x.d + 0.0009 * VS * gauss();
      }
      let idx = 0;
      for (const d of PM.STOCKS) {
        const st = s.stocks[d.sym];
        if (--st.trendLeft <= 0) {
          st.trendLeft = 90 + Math.floor(rng() * 320);
          st.trend = rng() < 0.3 ? 0 : d.vol * VS * 0.06 * gauss();
        }
        let r = d.beta * mret + mk.sec[d.sector].r + d.vol * VS * gauss() + st.trend;
        if (st.imp.length) {
          for (let i = st.imp.length - 1; i >= 0; i--) {
            const im = st.imp[i];
            r += im.per;
            if (--im.left <= 0) st.imp.splice(i, 1);
          }
        }
        r -= 0.0002 * Math.log(st.price / d.price); // pull back toward the long-run level (half-life ~1h)
        st.price = clamp(st.price * Math.exp(r), d.price * 0.12, d.price * 8);
        pushSeries(st.ser, st.price, s.t, 4);
        idx += st.price / d.price;
      }
      pushSeries(s.index, (1000 * idx) / PM.STOCKS.length, s.t, 2);
    }

    // Markets price news in fast: ~60% of the move lands within 3 seconds, the rest drifts in.
    // (A slower reaction would let players farm headlines for free money.)
    function impulse(sym, total, dur) {
      const st = s.stocks[sym];
      st.imp.push({ per: (total * 0.6) / 3, left: 3 });
      st.imp.push({ per: (total * 0.4) / dur, left: dur });
    }

    /* -------------------------------------------------------------- news */
    function pickMag() {
      const x = rng();
      if (x < 0.58) return ['small', 0.008 + rng() * 0.017];
      if (x < 0.9) return ['med', 0.025 + rng() * 0.035];
      return ['big', 0.06 + rng() * 0.06];
    }
    const fill = (tpl, d) => tpl.replace(/\{n\}/g, d.name).replace(/\{t\}/g, d.sym).replace(/\{p\}/g, () => pick(d.prods));

    function makeNews() {
      const r = rng();
      let n;
      if (r < 0.62) n = stockNews();
      else if (r < 0.80) n = sectorNews();
      else if (r < 0.88) n = marketNews();
      else n = bizNews();
      n.id = s.newsId++;
      n.t = s.t;
      s.news.unshift(n);
      if (s.news.length > 40) s.news.length = 40;
      emit('news', n);
    }

    function stockNews() {
      const d = pick(PM.STOCKS);
      const dir = rng() < 0.5 ? 1 : -1;
      if (rng() < 0.01) { // very rare moonshot / crash
        const size = 0.25 + rng() * 0.25;
        impulse(d.sym, dir * size, 20 + Math.floor(rng() * 20));
        return { kind: 'stock', sym: d.sym, sector: d.sector, dir, mag: 'huge', rumor: false, icon: d.icon,
          text: fill(pick(dir > 0 ? PM.NEWS.moon : PM.NEWS.crash), d) };
      }
      const [mag, size] = pickMag();
      let text = fill(pick(dir > 0 ? PM.NEWS.stockPos : PM.NEWS.stockNeg), d);
      let real = dir;
      let rumor = false;
      if (mag !== 'big' && rng() < 0.22) {
        rumor = true;
        text = 'Rumor: ' + text;
        if (rng() < 0.4) real = -dir; // rumors can be wrong
      }
      impulse(d.sym, real * size, 10 + Math.floor(rng() * 16));
      return { kind: 'stock', sym: d.sym, sector: d.sector, dir, mag, rumor, text, icon: d.icon };
    }

    function sectorNews() {
      const sec = pick(Object.keys(PM.SECTORS));
      const dir = rng() < 0.5 ? 1 : -1;
      const size = 0.012 + rng() * 0.03;
      const text = pick(dir > 0 ? PM.NEWS.sectorPos[sec] : PM.NEWS.sectorNeg[sec]);
      for (const d of PM.STOCKS) if (d.sector === sec) impulse(d.sym, dir * size, 14 + Math.floor(rng() * 10));
      return { kind: 'sector', sector: sec, dir, mag: size > 0.03 ? 'med' : 'small', text, icon: dir > 0 ? '📈' : '📉' };
    }

    function marketNews() {
      const dir = rng() < 0.5 ? 1 : -1;
      const size = 0.01 + rng() * 0.03;
      const text = pick(dir > 0 ? PM.NEWS.marketPos : PM.NEWS.marketNeg);
      for (const d of PM.STOCKS) impulse(d.sym, dir * size * d.beta, 20);
      return { kind: 'market', dir, mag: size > 0.03 ? 'big' : 'med', text, icon: dir > 0 ? '🌅' : '⛈️' };
    }

    function bizNews() {
      const owned = s.companies.map((c) => c.ind);
      const indId = owned.length && rng() < 0.7 ? pick(owned) : pick(PM.INDUSTRIES).id;
      const ind = PM.IND[indId];
      const kinds = ['surge', 'slump', 'costup', 'costdown'];
      const k = pick(kinds);
      const dur = 120 + Math.floor(rng() * 120);
      const pctTxt = (m) => (m >= 1 ? '+' : '−') + Math.round(Math.abs(m - 1) * 100) + '%';
      let ev, text, dir, icon = ind.icon;
      if (k === 'surge') {
        const m = 1.4 + rng() * 0.5;
        ev = { kind: 'demand', ind: indId, mult: m };
        text = ind.short + ' fever! Customer demand ' + pctTxt(m);
        dir = 1;
      } else if (k === 'slump') {
        const m = 0.6 + rng() * 0.2;
        ev = { kind: 'demand', ind: indId, mult: m };
        text = 'Shoppers cut back on ' + ind.short.toLowerCase() + ': demand ' + pctTxt(m);
        dir = -1;
      } else if (k === 'costup') {
        const m = 1.2 + rng() * 0.25;
        ev = { kind: 'cost', ind: 'all', mult: m };
        text = 'Supply crunch: material costs ' + pctTxt(m);
        dir = -1; icon = '📦';
      } else {
        const m = 0.7 + rng() * 0.1;
        ev = { kind: 'cost', ind: 'all', mult: m };
        text = 'Bulk discount on raw materials: costs ' + pctTxt(m);
        dir = 1; icon = '🏷️';
      }
      ev.until = s.t + dur;
      ev.text = text;
      ev.icon = icon;
      ev.id = s.newsId;
      // one live event per (kind, industry): a new one replaces the old so effects never stack up
      s.events = s.events.filter((x) => x.until > s.t && !(x.kind === ev.kind && x.ind === ev.ind));
      s.events.push(ev);
      return { kind: 'biz', ind: indId, dir, mag: 'med', text, icon, dur };
    }

    function stepWorld() {
      tickMarket();
      if (rng() < 1 / 24) makeNews();
    }

    /* --------------------------------------------------------- companies */
    function eventMult(kind, ind) {
      let m = 1;
      for (const e of s.events) if (e.kind === kind && e.until > s.t && (e.ind === 'all' || e.ind === ind)) m *= e.mult;
      return m;
    }
    const lineCount = (co, pid) => co.lines.reduce((n, l) => n + (l === pid ? 1 : 0), 0);
    const brandMult = (co) => 1 + 0.3 * co.up.mkt;
    const speedMult = (co) => 1 + 0.25 * co.up.spd;
    const effMult = (co) => Math.max(0.2, 1 - 0.07 * co.up.eff);
    const capacity = (co, pr) => Math.ceil(pr.demand * 90) * (1 + co.up.wh);

    function unitCost(co, pr) { return pr.cost * effMult(co) * eventMult('cost', co.ind); }
    function demandAtMarket(co, pr) { return pr.demand * brandMult(co) * eventMult('demand', co.ind); }

    function suggestPrice(co, pr) {
      const E = BIZ.elasticity;
      const supply = lineCount(co, pr.id) * pr.rate * speedMult(co);
      const D1 = demandAtMarket(co, pr);
      const c = unitCost(co, pr) / pr.price;
      const ps = supply > 0 ? Math.pow(D1 / supply, 1 / E) : 1; // price where demand == supply
      const pstar = (E * c) / (E - 1);                           // unconstrained profit-max price
      const m = clamp(Math.max(ps, pstar), Math.max(BIZ.priceMin, c * 1.05), BIZ.priceMax);
      return Math.round(m * 100) / 100;
    }

    function tickCompany(co) {
      const ind = PM.IND[co.ind];
      const spd = speedMult(co);
      const brand = brandMult(co);
      let rev = 0, exp = 0;
      for (const pr of ind.products) {
        const ps = co.prods[pr.id];
        if (!ps.unlocked) {
          if (ps.rd && --ps.rd.left <= 0) {
            ps.unlocked = true; ps.rd = null; s.stats.researched++;
            emit('rd', { co: co.id, name: co.name, product: pr });
          }
          continue;
        }
        const supply = lineCount(co, pr.id) * pr.rate * spd;
        const uc = unitCost(co, pr);
        const cap = capacity(co, pr);
        const room = Math.max(0, cap - ps.inv);
        let make = Math.min(supply, room);
        const wanted = make;
        if (make > 0 && uc > 0 && make * uc > s.cash) make = Math.max(0, s.cash / uc);
        if (make > 0) {
          const spent = make * uc;
          s.cash -= spent; exp += spent; ps.inv += make;
        }
        ps.wob = clamp(ps.wob - 0.05 * ps.wob + 0.02 * gauss(), -0.3, 0.3);
        const price = pr.price * ps.price;
        const demand = demandAtMarket(co, pr) * (1 + ps.wob) * Math.pow(1 / ps.price, BIZ.elasticity);
        const sold = Math.min(ps.inv, demand);
        ps.inv -= sold;
        const income = sold * price;
        s.cash += income; rev += income;
        ps.supply = supply; ps.demand = demand; ps.sold = sold; ps.soldTotal += sold;
        if (supply <= 0) ps.status = 'idle';
        else if (make < wanted * 0.98) ps.status = 'cash';
        else if (ps.inv >= cap * 0.98) ps.status = 'full';
        else if (demand < supply * 0.98) ps.status = 'demand';
        else ps.status = 'supply';
        if (ps.auto && s.t % 5 === 0) ps.price = suggestPrice(co, pr);
      }
      co.rev += (rev - co.rev) * 0.1;
      co.exp += (exp - co.exp) * 0.1;
      co.totRev += rev; co.totExp += exp;
      s.stats.bizRevenue += rev;
    }

    function companyValue(co) { return co.invested * 0.75 + Math.max(0, co.rev - co.exp) * 600; }
    const companyProfit = (co) => co.rev - co.exp;

    /* ---------------------------------------------------------- the tick */
    function tick() {
      s.t++;
      stepWorld();
      // dividends (staggered per stock)
      for (const sym in s.holdings) {
        const d = stockDef[sym];
        if (d.div > 0 && (s.t + d.idx * 5) % 60 === 0) {
          const amt = s.holdings[sym].shares * s.stocks[sym].price * d.div;
          s.cash += amt; s.stats.dividends += amt;
          emit('dividend', { sym, amt });
        }
      }
      for (const co of s.companies) tickCompany(co);
      const h = hustle();
      if (h > 0) s.cash += h;
      const nw = netWorth();
      pushSeries(s.net, nw, s.t, 2);
      if (nw > s.stats.peakNet) s.stats.peakNet = nw;
      if (s.t % 3 === 0) checkAchievements();
    }

    function checkAchievements() {
      for (const a of PM.ACHIEVEMENTS) {
        if (!s.ach[a.id] && a.test(s, api)) {
          s.ach[a.id] = s.t;
          const reward = Math.max(a.reward || 0, 90 * Math.max(0, incomePerSec()));
          s.cash += reward;
          emit('achievement', { a, reward });
        }
      }
    }

    /* ----------------------------------------------------- derived values */
    // Effect of a personal upgrade at a given level (the UI uses this for "now -> next" previews).
    function upgradeValue(id, level) {
      switch (id) {
        case 'tap': return 1 + 0.6 * level;                                   // $ per tap (before streak/income bonus)
        case 'hustle': return level > 0 ? 0.5 * Math.pow(1.5, level - 1) : 0;  // $ per second
        case 'broker': return 0.0035 * (1 - 0.2 * level);                      // fee rate per trade
        case 'lab': return 0.12 * level;                                       // research time reduction
        default: return 0;
      }
    }
    const hustle = () => upgradeValue('hustle', s.up.hustle);
    const bizProfit = () => s.companies.reduce((a, c) => a + companyProfit(c), 0);
    const incomePerSec = () => hustle() + bizProfit();
    const tapBase = () => upgradeValue('tap', s.up.tap) + 0.1 * Math.max(0, incomePerSec());
    const feeRate = () => upgradeValue('broker', s.up.broker);
    function portfolioValue() {
      let v = 0;
      for (const sym in s.holdings) v += s.holdings[sym].shares * s.stocks[sym].price;
      return v;
    }
    const businessValue = () => s.companies.reduce((a, c) => a + companyValue(c), 0);
    const netWorth = () => s.cash + portfolioValue() + businessValue();

    /* ------------------------------------------------------------ actions */
    const fail = (msg) => ({ ok: false, msg });
    const ok = (extra) => Object.assign({ ok: true }, extra);

    function tap(nowMs) {
      nowMs = nowMs == null ? Date.now() : nowMs;
      combo.n = nowMs - combo.last < COMBO_WINDOW ? combo.n + 1 : 1;
      combo.last = nowMs;
      const mult = 1 + Math.min(combo.n, 40) / 40;
      const gain = tapBase() * mult;
      s.cash += gain; s.stats.taps++; s.stats.tapEarned += gain;
      return { gain, mult, combo: combo.n };
    }
    function comboState(nowMs) {
      nowMs = nowMs == null ? Date.now() : nowMs;
      const live = nowMs - combo.last < COMBO_WINDOW * 1.6;
      return { n: live ? combo.n : 0, mult: live ? 1 + Math.min(combo.n, 40) / 40 : 1, live };
    }

    function upgradeCost(id) {
      const u = PM.UPGRADES.find((x) => x.id === id);
      return u.cost(s.up[id]);
    }
    function buyUpgrade(id) {
      const u = PM.UPGRADES.find((x) => x.id === id);
      if (!u) return fail('Unknown upgrade');
      if (s.up[id] >= u.max) return fail('Maxed out');
      const cost = u.cost(s.up[id]);
      if (s.cash < cost) return fail('Not enough cash');
      s.cash -= cost; s.up[id]++;
      return ok({ cost });
    }

    function buy(sym, amount) {
      const st = s.stocks[sym];
      if (!st) return fail('Unknown stock');
      amount = Math.min(amount, s.cash);
      if (!(amount >= 0.01)) return fail('Not enough cash');
      const fee = amount * feeRate();
      const shares = (amount - fee) / st.price;
      s.cash -= amount;
      const h = s.holdings[sym] || (s.holdings[sym] = { shares: 0, cost: 0 });
      h.shares += shares; h.cost += amount;
      s.stats.trades++;
      return ok({ shares, spent: amount, fee });
    }

    function sell(sym, shares) {
      const h = s.holdings[sym];
      if (!h || h.shares <= 0) return fail('You do not own this stock');
      shares = Math.min(shares, h.shares);
      if (!(shares > 0)) return fail('Nothing to sell');
      const st = s.stocks[sym];
      const gross = shares * st.price;
      const fee = gross * feeRate();
      const proceeds = gross - fee;
      const frac = shares / h.shares;
      const basis = h.cost * frac;
      const pl = proceeds - basis;
      s.cash += proceeds;
      h.shares -= shares; h.cost -= basis;
      if (h.shares < 1e-9) delete s.holdings[sym];
      s.stats.trades++; s.stats.realized += pl;
      if (pl > s.stats.bestTrade) s.stats.bestTrade = pl;
      if (basis > 0) s.stats.bestPct = Math.max(s.stats.bestPct, pl / basis);
      return ok({ proceeds, fee, pl, pct: basis > 0 ? pl / basis : 0 });
    }
    const sellAll = (sym) => (s.holdings[sym] ? sell(sym, s.holdings[sym].shares) : fail('You do not own this stock'));

    function position(sym) {
      const h = s.holdings[sym];
      const price = s.stocks[sym].price;
      if (!h) return { shares: 0, cost: 0, value: 0, pl: 0, pct: 0 };
      const value = h.shares * price;
      return { shares: h.shares, cost: h.cost, value, pl: value - h.cost, pct: h.cost > 0 ? value / h.cost - 1 : 0 };
    }

    /* -- companies -- */
    function findCo(id) { return s.companies.find((c) => c.id === id); }

    function foundCompany(indId, name) {
      const ind = PM.IND[indId];
      if (!ind) return fail('Unknown industry');
      if (s.companies.length >= 12) return fail('You already run 12 companies!');
      if (s.cash < ind.startup) return fail('Not enough cash');
      name = String(name || '').trim().slice(0, 24) || defaultName(ind);
      s.cash -= ind.startup;
      const prods = {};
      ind.products.forEach((pr, k) => {
        prods[pr.id] = { unlocked: k === 0, rd: null, inv: 0, price: 1, auto: true, wob: 0, supply: 0, demand: 0, sold: 0, soldTotal: 0, status: 'idle' };
      });
      const co = {
        id: s.nextId++, name, ind: indId, founded: s.t, lines: [ind.products[0].id], prods,
        up: { mkt: 0, eff: 0, spd: 0, wh: 0 }, invested: ind.startup, rev: 0, exp: 0, totRev: 0, totExp: 0,
      };
      s.companies.push(co);
      return ok({ company: co });
    }
    function defaultName(ind) { return pick(ind.names) + ' ' + pick(ind.suffix); }

    function lineCost(co) { return BIZ.lineCost(PM.IND[co.ind].startup, co.lines.length + 1); }
    function buildLine(cid) {
      const co = findCo(cid);
      if (!co) return fail('No such company');
      if (co.lines.length >= BIZ.maxLines) return fail('Max production lines reached');
      const cost = lineCost(co);
      if (s.cash < cost) return fail('Not enough cash');
      const ind = PM.IND[co.ind];
      // new line goes to the unlocked product with the most unmet demand
      let best = null, bestGap = -Infinity;
      for (const pr of ind.products) {
        if (!co.prods[pr.id].unlocked) continue;
        const gap = demandAtMarket(co, pr) - lineCount(co, pr.id) * pr.rate * speedMult(co);
        const score = gap * pr.price * (1 - pr.cost / pr.price);
        if (score > bestGap) { bestGap = score; best = pr; }
      }
      s.cash -= cost; co.invested += cost;
      co.lines.push(best.id);
      return ok({ cost });
    }
    function setLineProduct(cid, idx, pid) {
      const co = findCo(cid);
      if (!co || !co.prods[pid] || !co.prods[pid].unlocked || idx < 0 || idx >= co.lines.length) return fail('Cannot assign');
      co.lines[idx] = pid;
      return ok();
    }

    function researchTime(pr) { return Math.max(5, Math.round(pr.rdTime * (1 - upgradeValue('lab', s.up.lab)))); }
    function startResearch(cid, pid) {
      const co = findCo(cid);
      if (!co) return fail('No such company');
      const ind = PM.IND[co.ind];
      const pr = ind.products.find((p) => p.id === pid);
      const ps = co.prods[pid];
      if (!pr || ps.unlocked || ps.rd) return fail('Cannot research that');
      if (pr.tier > 0 && !co.prods[ind.products[pr.tier - 1].id].unlocked) return fail('Research the previous product first');
      if (ind.products.some((p) => co.prods[p.id].rd)) return fail('The lab is busy');
      if (s.cash < pr.rdCost) return fail('Not enough cash');
      s.cash -= pr.rdCost; co.invested += pr.rdCost;
      const total = researchTime(pr);
      ps.rd = { left: total, total };
      return ok({ time: total });
    }

    function setPrice(cid, pid, mult) {
      const co = findCo(cid);
      if (!co || !co.prods[pid]) return fail('No such product');
      co.prods[pid].price = clamp(Math.round(mult * 100) / 100, BIZ.priceMin, BIZ.priceMax);
      co.prods[pid].auto = false;
      return ok();
    }
    function setAuto(cid, pid, on) {
      const co = findCo(cid);
      if (!co || !co.prods[pid]) return fail('No such product');
      co.prods[pid].auto = !!on;
      if (on) co.prods[pid].price = suggestPrice(co, PM.IND[co.ind].products.find((p) => p.id === pid));
      return ok();
    }

    function companyUpgradeCost(co, kind) { return BIZ.upCost[kind](PM.IND[co.ind].startup, co.up[kind]); }
    function upgradeCompany(cid, kind) {
      const co = findCo(cid);
      if (!co || !BIZ.upCost[kind]) return fail('No such upgrade');
      if (co.up[kind] >= BIZ.maxCompanyUp[kind]) return fail('Maxed out');
      const cost = companyUpgradeCost(co, kind);
      if (s.cash < cost) return fail('Not enough cash');
      s.cash -= cost; co.invested += cost; co.up[kind]++;
      return ok({ cost });
    }

    function sellCompany(cid) {
      const co = findCo(cid);
      if (!co) return fail('No such company');
      const v = companyValue(co);
      s.cash += v;
      s.companies.splice(s.companies.indexOf(co), 1);
      return ok({ value: v });
    }

    /* ---------------------------------------------------------- save/load */
    function serialize() {
      s.lastSeen = Date.now();
      for (const sym in s.stocks) trimSeries(s.stocks[sym].ser);
      trimSeries(s.index); trimSeries(s.net);
      return JSON.stringify(s);
    }
    function load(json) {
      let o;
      try { o = typeof json === 'string' ? JSON.parse(json) : json; } catch (e) { return false; }
      if (!o || o.v !== 1 || !o.stocks || typeof o.cash !== 'number') return false;
      const base = newState();
      // merge so saves from older versions pick up new stocks/fields
      for (const sym in base.stocks) if (!o.stocks[sym]) o.stocks[sym] = base.stocks[sym];
      for (const k in base.up) if (o.up[k] == null) o.up[k] = 0;
      for (const k in base.stats) if (o.stats[k] == null) o.stats[k] = base.stats[k];
      o.settings = Object.assign({}, base.settings, o.settings);
      for (const sec in base.mkt.sec) if (!o.mkt.sec[sec]) o.mkt.sec[sec] = { d: 0, r: 0 };
      o.events = o.events || []; o.news = o.news || []; o.ach = o.ach || {};
      s = o;
      return true;
    }
    function reset() {
      s = newState();
      warmup();
    }

    /* ---------------------------------------------------------- catch-up */
    function catchUp(seconds) {
      seconds = Math.min(Math.floor(seconds), MAX_OFFLINE);
      if (!(seconds > 0)) return null;
      const before = { cash: s.cash, nw: netWorth(), port: portfolioValue(), div: s.stats.dividends, rev: s.stats.bizRevenue };
      const achBefore = Object.keys(s.ach).length;
      silent = true;
      for (let i = 0; i < seconds; i++) tick();
      silent = false;
      checkAchievements();
      return {
        seconds, capped: seconds >= MAX_OFFLINE,
        cash: s.cash - before.cash, netWorth: netWorth() - before.nw, portfolio: portfolioValue() - before.port,
        dividends: s.stats.dividends - before.div, revenue: s.stats.bizRevenue - before.rev,
        achievements: Object.keys(s.ach).length - achBefore,
      };
    }

    /* ------------------------------------------------------- view helpers */
    function stockInfo(sym, tf) {
      const st = s.stocks[sym];
      const w = PM.seriesWindow(st.ser, tf);
      const first = w[0], last = st.price;
      let hi = -Infinity, lo = Infinity;
      for (const v of w) { if (v > hi) hi = v; if (v < lo) lo = v; }
      return { series: w, price: last, first, change: last - first, pct: first > 0 ? last / first - 1 : 0, hi: Math.max(hi, last), lo: Math.min(lo, last) };
    }
    function productInfo(co, pr) {
      const ps = co.prods[pr.id];
      const price = pr.price * ps.price;
      const uc = unitCost(co, pr);
      return {
        ps, price, unitCost: uc, margin: price - uc, supply: ps.supply, demand: ps.demand, sold: ps.sold,
        lines: lineCount(co, pr.id), cap: capacity(co, pr), status: ps.status, suggested: suggestPrice(co, pr),
        profitPerSec: ps.sold * (price - uc),
      };
    }

    const api = {
      get s() { return s; },
      on(f) { listeners.push(f); return () => listeners.splice(listeners.indexOf(f), 1); },
      tick, catchUp, tap, comboState,
      buyUpgrade, upgradeCost, upgradeValue, buy, sell, sellAll, position,
      foundCompany, defaultName, buildLine, lineCost, setLineProduct, startResearch, researchTime, setPrice, setAuto,
      upgradeCompany, companyUpgradeCost, sellCompany, findCo,
      netWorth, portfolioValue, businessValue, companyValue, companyProfit, bizProfit, incomePerSec, hustle, tapBase, feeRate,
      stockInfo, productInfo, suggestPrice, eventMult, checkAchievements,
      serialize, load, reset,
      save() { try { localStorage.setItem(SAVE_KEY, serialize()); return true; } catch (e) { return false; } },
      loadSaved() { try { const j = localStorage.getItem(SAVE_KEY); return j ? load(j) : false; } catch (e) { return false; } },
      wipe() { try { localStorage.removeItem(SAVE_KEY); } catch (e) { /* ignore */ } reset(); },
    };
    if (!opts.noWarmup) warmup(); // must run after every const above is initialised
    return api;
  };
})();
