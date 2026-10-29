/* Run: node tests/engine.test.js */
'use strict';
const assert = require('assert');
const path = require('path');
require(path.join(__dirname, '../web/js/data.js'));
require(path.join(__dirname, '../web/js/engine.js'));

let passed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log('  ok  ', name); }
  catch (e) { console.error('  FAIL', name, '\n      ', e.stack.split('\n').slice(0, 4).join('\n       ')); process.exitCode = 1; }
}
const near = (a, b, eps, msg) => assert.ok(Math.abs(a - b) <= eps, (msg || '') + ' expected ' + a + ' ≈ ' + b);
const fresh = (seed) => PM.createGame({ seed: seed || 1 });

test('new game has all stocks with pre-built history at every resolution', () => {
  const g = fresh();
  assert.strictEqual(Object.keys(g.s.stocks).length, PM.STOCKS.length);
  for (const d of PM.STOCKS) {
    const ser = g.s.stocks[d.sym].ser;
    assert.ok(ser.a.length >= 600 && ser.b.length >= 300 && ser.c.length >= 200, d.sym + ' history too short');
  }
  assert.strictEqual(g.s.cash, 0);
  assert.ok(g.s.news.length > 0, 'initial news feed is not empty');
});

test('prices stay finite, positive and inside their clamp over 8 hours', () => {
  const g = fresh(7);
  for (let i = 0; i < 8 * 3600; i++) g.tick();
  for (const d of PM.STOCKS) {
    const p = g.s.stocks[d.sym].price;
    assert.ok(isFinite(p) && p >= d.price * 0.12 - 1e-9 && p <= d.price * 8 + 1e-9, d.sym + ' price ' + p);
  }
});

test('market has real movement but is not insane (1-minute / 10-minute moves, averaged over seeds)', () => {
  let m1 = 0, m10 = 0, n = 0;
  for (let seed = 1; seed <= 8; seed++) {
    const g = fresh(seed);
    for (const d of PM.STOCKS) {
      const a = g.s.stocks[d.sym].ser.a;
      m1 += Math.abs(Math.log(a[a.length - 1] / a[a.length - 61]));
      m10 += Math.abs(Math.log(a[a.length - 1] / a[a.length - 600]));
      n++;
    }
  }
  m1 /= n; m10 /= n;
  console.log('       avg |1m move| ' + (m1 * 100).toFixed(2) + '%   avg |10m move| ' + (m10 * 100).toFixed(2) + '%');
  assert.ok(m1 > 0.01 && m1 < 0.05, '1m ' + m1);
  assert.ok(m10 > 0.05 && m10 < 0.22, '10m ' + m10);
});

test('long-run prices stay in a sane band around their base (mean reversion works)', () => {
  const logs = [];
  for (let seed = 1; seed <= 6; seed++) {
    const g = fresh(seed);
    for (const d of PM.STOCKS) logs.push(Math.log(g.s.stocks[d.sym].price / d.price));
  }
  const mean = logs.reduce((a, b) => a + b, 0) / logs.length;
  const sd = Math.sqrt(logs.reduce((a, b) => a + (b - mean) ** 2, 0) / logs.length);
  console.log('       log(price/base) mean ' + mean.toFixed(2) + ' sd ' + sd.toFixed(2));
  assert.ok(Math.abs(mean) < 0.2 && sd < 0.5, 'mean ' + mean + ' sd ' + sd);
});

test('tapping pays out and builds a combo', () => {
  const g = fresh();
  const r1 = g.tap(1000);
  near(r1.gain, 1 * (1 + 1 / 40), 1e-9);
  let last;
  for (let i = 1; i < 60; i++) last = g.tap(1000 + i * 100);
  assert.strictEqual(last.mult, 2); // capped at x2
  assert.strictEqual(g.s.stats.taps, 60);
  const gap = g.tap(1000 + 60 * 100 + 5000); // streak broken
  assert.strictEqual(gap.combo, 1);
});

test('upgrades: cost is charged, level goes up, cannot overspend', () => {
  const g = fresh();
  assert.strictEqual(g.buyUpgrade('hustle').ok, false);
  g.s.cash = 100;
  const c0 = g.upgradeCost('hustle');
  assert.ok(g.buyUpgrade('hustle').ok);
  near(g.s.cash, 100 - c0, 1e-9);
  assert.strictEqual(g.s.up.hustle, 1);
  g.tick();
  assert.ok(g.hustle() > 0);
});

test('stocks: buy then immediate sell loses exactly the two fees', () => {
  const g = fresh();
  g.s.cash = 1000;
  const fee = g.feeRate();
  const b = g.buy('NBLD', 500);
  assert.ok(b.ok);
  near(g.s.cash, 500, 1e-9);
  const sh = g.s.holdings.NBLD.shares;
  near(sh * g.s.stocks.NBLD.price, 500 * (1 - fee), 1e-6);
  const r = g.sellAll('NBLD');
  assert.ok(r.ok);
  near(g.s.cash, 500 + 500 * (1 - fee) * (1 - fee), 1e-6);
  assert.strictEqual(g.s.holdings.NBLD, undefined);
});

test('stocks: cannot buy with no cash or sell what you do not own', () => {
  const g = fresh();
  assert.strictEqual(g.buy('NBLD', 10).ok, false);
  assert.strictEqual(g.sell('NBLD', 1).ok, false);
});

test('stocks: partial sell keeps proportional cost basis', () => {
  const g = fresh();
  g.s.cash = 1000;
  g.buy('QCKL', 1000);
  const h = g.s.holdings.QCKL;
  const cost0 = h.cost, sh0 = h.shares;
  g.sell('QCKL', sh0 / 4);
  near(g.s.holdings.QCKL.cost, cost0 * 0.75, 1e-9);
  near(g.s.holdings.QCKL.shares, sh0 * 0.75, 1e-9);
});

test('broker perks reduce the fee to zero at max level', () => {
  const g = fresh();
  g.s.up.broker = 5;
  assert.strictEqual(g.feeRate(), 0);
});

test('dividends pay holders of dividend stocks over time', () => {
  const g = fresh(2);
  g.s.cash = 10000;
  g.buy('GLDB', 10000);
  const before = g.s.cash;
  let events = 0;
  g.on((t) => { if (t === 'dividend') events++; });
  for (let i = 0; i < 600; i++) g.tick();
  assert.ok(g.s.cash > before, 'cash grew from dividends');
  assert.ok(events >= 9 && events <= 11, 'about one payout per minute, got ' + events);
});

test('companies: need cash; found, produce, sell and turn a profit', () => {
  const g = fresh(5);
  assert.strictEqual(g.foundCompany('lemonade', 'X').ok, false);
  g.s.cash = 100;
  const r = g.foundCompany('lemonade', 'Test Stand');
  assert.ok(r.ok);
  assert.strictEqual(g.s.cash, 0);
  assert.strictEqual(g.s.companies.length, 1);
  g.s.cash = 5; // working capital for materials
  for (let i = 0; i < 300; i++) g.tick();
  const co = g.s.companies[0];
  assert.ok(co.totRev > 0, 'revenue happened');
  assert.ok(co.totRev > co.totExp, 'profitable');
  assert.ok(g.s.cash > 5, 'cash grew');
  assert.ok(g.companyProfit(co) > 0.05, 'profit/s EMA positive: ' + g.companyProfit(co));
});

test('companies: production stalls (no negative cash) when broke', () => {
  const g = fresh(5);
  g.s.cash = 100;
  g.foundCompany('lemonade', 'Broke');
  g.s.cash = 0;
  for (let i = 0; i < 50; i++) { g.tick(); assert.ok(g.s.cash >= -1e-9, 'cash never negative'); }
});

test('companies: research gating, cost, timer and unlock', () => {
  const g = fresh(5);
  g.s.cash = 1e6;
  const co = g.foundCompany('bakery', 'B').company;
  const ind = PM.IND.bakery;
  assert.strictEqual(g.startResearch(co.id, ind.products[2].id).ok, false, 'must research in order');
  const cash0 = g.s.cash;
  const r = g.startResearch(co.id, ind.products[1].id);
  assert.ok(r.ok);
  near(g.s.cash, cash0 - ind.products[1].rdCost, 1e-6);
  assert.strictEqual(g.startResearch(co.id, ind.products[2].id).ok, false, 'lab busy / previous locked');
  for (let i = 0; i < r.time + 2; i++) g.tick();
  assert.ok(co.prods[ind.products[1].id].unlocked);
  assert.strictEqual(g.s.stats.researched, 1);
});

test('companies: lines, upgrades, price controls and selling', () => {
  const g = fresh(5);
  g.s.cash = 1e7;
  const co = g.foundCompany('bakery', 'B').company;
  assert.ok(g.buildLine(co.id).ok);
  assert.strictEqual(co.lines.length, 2);
  for (const k of ['mkt', 'eff', 'spd', 'wh']) assert.ok(g.upgradeCompany(co.id, k).ok, k);
  assert.strictEqual(co.up.mkt, 1);
  const pid = PM.IND.bakery.products[0].id;
  g.setPrice(co.id, pid, 99);
  assert.strictEqual(co.prods[pid].price, PM.BIZ.priceMax);
  assert.strictEqual(co.prods[pid].auto, false);
  g.setAuto(co.id, pid, true);
  assert.ok(co.prods[pid].auto && co.prods[pid].price >= PM.BIZ.priceMin);
  for (let i = 0; i < 100; i++) g.tick();
  const before = g.s.cash;
  const sold = g.sellCompany(co.id);
  assert.ok(sold.ok && g.s.cash > before);
  assert.strictEqual(g.s.companies.length, 0);
});

test('auto-pricing earns at least as much as a naive market-price strategy', () => {
  function run(auto) {
    const g = fresh(11);
    g.s.cash = 1e5;
    const co = g.foundCompany('bakery', 'B').company;
    g.buildLine(co.id); g.buildLine(co.id);
    const pid = PM.IND.bakery.products[0].id;
    co.prods[pid].auto = auto; co.prods[pid].price = 1;
    const start = g.s.cash;
    for (let i = 0; i < 1200; i++) { g.tick(); g.s.cash = Math.max(g.s.cash, 1e5 * 0 + g.s.cash); }
    return g.s.cash - start;
  }
  const a = run(true), m = run(false);
  console.log('       profit auto ' + a.toFixed(0) + '  vs fixed market price ' + m.toFixed(0));
  assert.ok(a >= m * 0.98);
});

test('save/load round trip preserves the world', () => {
  const g = fresh(9);
  g.s.cash = 5000;
  g.buy('PIZZ', 2000);
  g.foundCompany('lemonade', 'RT');
  for (let i = 0; i < 400; i++) g.tick();
  const nw = g.netWorth();
  const json = g.serialize();
  assert.ok(json.length < 900000, 'save size ' + json.length);
  console.log('       save size ' + (json.length / 1024).toFixed(0) + ' KB');
  const g2 = fresh(99);
  assert.ok(g2.load(json));
  near(g2.netWorth(), nw, 1e-6);
  assert.strictEqual(g2.s.companies[0].name, 'RT');
  for (let i = 0; i < 50; i++) g2.tick();
  assert.ok(isFinite(g2.netWorth()));
  assert.strictEqual(fresh().load('{not json'), false);
  assert.strictEqual(fresh().load('{"v":2}'), false);
});

test('offline catch-up runs the world forward and reports a summary', () => {
  const g = fresh(4);
  g.s.cash = 100;
  g.foundCompany('lemonade', 'Away');
  g.s.cash = 20;
  g.buyUpgrade('hustle');
  g.s.cash = 5000; g.buyUpgrade('hustle');
  const t0 = g.s.t;
  const sum = g.catchUp(3600);
  assert.strictEqual(g.s.t, t0 + 3600);
  assert.ok(sum.cash > 0 && sum.revenue > 0);
  const capped = g.catchUp(99 * 3600);
  assert.ok(capped.capped && capped.seconds === 6 * 3600);
  assert.strictEqual(g.catchUp(0), null);
});

test('achievements unlock and fire events', () => {
  const g = fresh();
  const got = [];
  g.on((t, d) => { if (t === 'achievement') got.push(d.a.id); });
  g.tap(1);
  g.checkAchievements();
  assert.ok(got.includes('tap1'));
  const c0 = g.s.cash;
  g.s.cash = 150; g.checkAchievements();
  assert.ok(got.includes('nw100'));
  assert.ok(g.s.cash >= 150 + 20, 'reward was paid');
});

test('business events never stack: at most one live event per kind and industry', () => {
  const g = fresh(21);
  g.s.cash = 1e6; g.foundCompany('lemonade', 'E');
  let worst = 1;
  for (let i = 0; i < 4 * 3600; i++) {
    g.tick();
    const live = g.s.events.filter((e) => e.until > g.s.t);
    const keys = live.map((e) => e.kind + ':' + e.ind);
    assert.strictEqual(new Set(keys).size, keys.length, 'duplicate live event ' + keys);
    worst = Math.max(worst, g.eventMult('cost', 'lemonade'));
  }
  assert.ok(worst <= 1.5, 'cost multiplier bounded, saw ' + worst);
});

test('catch-up of 6 hours is fast enough for a phone', () => {
  const g = fresh(8);
  g.s.cash = 1e9;
  for (const id of ['lemonade', 'bakery', 'coffee']) g.foundCompany(id, id);
  const t = Date.now();
  g.catchUp(6 * 3600);
  const ms = Date.now() - t;
  console.log('       6h catch-up with 3 companies: ' + ms + ' ms (desktop)');
  assert.ok(ms < 4000);
});

console.log('\n' + passed + ' tests passed' + (process.exitCode ? ' (with failures)' : ''));
