/* Pacing check: a greedy "pick the best payback" bot plays the economy.
 * It taps ~3x/s while "active" and buys whatever returns its cost fastest.
 * Run: node tests/balance.js [hoursOfPlay]
 * This is a sanity check on progression speed, not a strategy guide.            */
'use strict';
const path = require('path');
require(path.join(__dirname, '../web/js/data.js'));
require(path.join(__dirname, '../web/js/engine.js'));

const HOURS = +(process.argv[2] || 3);
const TAPS_PER_SEC = 3;
const g = PM.createGame({ seed: 42 });
const s = g.s;

const fmt = (n) => {
  const u = ['', 'K', 'M', 'B', 'T', 'Qa'];
  let i = 0;
  while (Math.abs(n) >= 1000 && i < u.length - 1) { n /= 1000; i++; }
  return (i ? n.toFixed(1) : n.toFixed(0)) + u[i];
};
const mark = [1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12];
const hit = {};

function candidates() {
  const out = [];
  const inc = g.incomePerSec();
  // personal upgrades
  const tapGain = 0.6 * TAPS_PER_SEC * 1.7;
  out.push({ cost: g.upgradeCost('tap'), gain: tapGain, do: () => g.buyUpgrade('tap'), name: 'tap' });
  const h = s.up.hustle;
  out.push({ cost: g.upgradeCost('hustle'), gain: 0.5 * Math.pow(1.5, h) - (h > 0 ? 0.5 * Math.pow(1.5, h - 1) : 0), do: () => g.buyUpgrade('hustle'), name: 'hustle' });
  // next industry
  for (const ind of PM.INDUSTRIES) {
    const owned = s.companies.filter((c) => c.ind === ind.id).length;
    if (owned === 0) out.push({ cost: ind.startup, gain: (ind.startup / 330) * 0.8, do: () => g.foundCompany(ind.id, ind.name), name: 'found ' + ind.id });
  }
  for (const co of s.companies) {
    const ind = PM.IND[co.ind];
    const S = ind.startup;
    // research the next tier
    const next = ind.products.find((p) => !co.prods[p.id].unlocked);
    const busy = ind.products.some((p) => co.prods[p.id].rd);
    if (next && !busy && co.prods[ind.products[next.tier - 1].id].unlocked) {
      out.push({ cost: next.rdCost, gain: (S / 330) * (PM.IND[co.ind].products[next.tier].rate * (next.price - next.cost) / ((S / 330) * 1) - 0) * 0.6, do: () => g.startResearch(co.id, next.id), name: 'R&D ' + next.id });
    }
    // lines
    if (co.lines.length < PM.BIZ.maxLines) {
      let bestGain = 0;
      for (const pr of ind.products) {
        if (!co.prods[pr.id].unlocked) continue;
        const pi = g.productInfo(co, pr);
        const gap = Math.max(0, g.productInfo(co, pr).demand - pi.supply);
        bestGain = Math.max(bestGain, Math.min(gap, pr.rate) * pi.margin);
      }
      out.push({ cost: g.lineCost(co), gain: bestGain, do: () => g.buildLine(co.id), name: 'line ' + co.ind });
    }
    // marketing (+30% demand on products that are demand-limited)
    let mk = 0, ef = 0;
    for (const pr of ind.products) {
      if (!co.prods[pr.id].unlocked) continue;
      const pi = g.productInfo(co, pr);
      const surplus = Math.max(0, pi.supply - pi.demand);
      mk += Math.min(surplus, pi.demand * 0.3 / (1 + 0.3 * co.up.mkt)) * pi.margin;
      ef += 0.07 * pr.cost * pi.sold;
    }
    if (co.up.mkt < PM.BIZ.maxCompanyUp.mkt) out.push({ cost: g.companyUpgradeCost(co, 'mkt'), gain: mk, do: () => g.upgradeCompany(co.id, 'mkt'), name: 'mkt ' + co.ind });
    if (co.up.eff < PM.BIZ.maxCompanyUp.eff) out.push({ cost: g.companyUpgradeCost(co, 'eff'), gain: ef, do: () => g.upgradeCompany(co.id, 'eff'), name: 'eff ' + co.ind });
    if (co.up.spd < PM.BIZ.maxCompanyUp.spd) {
      let sp = 0;
      for (const pr of ind.products) {
        if (!co.prods[pr.id].unlocked) continue;
        const pi = g.productInfo(co, pr);
        sp += Math.min(Math.max(0, pi.demand - pi.supply), pi.supply * 0.25) * pi.margin;
      }
      out.push({ cost: g.companyUpgradeCost(co, 'spd'), gain: sp, do: () => g.upgradeCompany(co.id, 'spd'), name: 'spd ' + co.ind });
    }
  }
  return out.filter((c) => c.gain > 0 && isFinite(c.cost));
}

let log = [];
let nowMs = 0;
const end = HOURS * 3600;
for (let t = 1; t <= end; t++) {
  for (let i = 0; i < TAPS_PER_SEC; i++) { nowMs += 1000 / TAPS_PER_SEC; g.tap(nowMs); }
  g.tick();
  if (t % 2 === 0) {
    for (let guard = 0; guard < 20; guard++) {
      const all = candidates();
      const pay = (c) => c.cost / c.gain;
      const aff = all.filter((c) => c.cost <= s.cash).sort((a, b) => pay(a) - pay(b))[0];
      const best = all.sort((a, b) => pay(a) - pay(b) || a.cost - b.cost)[0];
      if (!best) break;
      // wait for a clearly better item only if we can afford it soon; otherwise take the best affordable one
      const soon = best.cost - s.cash < Math.max(1, g.incomePerSec()) * 120;
      const c = aff && !(best !== aff && pay(best) < 0.7 * pay(aff) && soon) ? aff : null;
      if (!c) break;
      const r = c.do();
      if (!r.ok) break;
      if (log.length < 400) log.push([t, c.name, c.cost]);
    }
  }
  const nw = g.netWorth();
  for (const m of mark) if (!hit[m] && nw >= m) hit[m] = t;
}

console.log('Net worth milestones (active play, ' + TAPS_PER_SEC + ' taps/s, greedy purchases):');
for (const m of mark) console.log('  $' + fmt(m).padEnd(6), hit[m] ? (hit[m] / 60).toFixed(1).padStart(7) + ' min' : '      -');
console.log('After ' + HOURS + 'h: cash $' + fmt(s.cash) + ', net worth $' + fmt(g.netWorth()) + ', income/s $' + fmt(g.incomePerSec()) + ', companies ' + s.companies.length);
console.log('First 25 purchases:'); log.slice(0, 25).forEach(([t, n, c]) => console.log('  ' + String(t).padStart(5) + 's  ' + n.padEnd(16) + '$' + fmt(c)));
for (const co of s.companies) console.log('  ' + co.ind.padEnd(12) + 'lines ' + co.lines.length + ' mkt ' + co.up.mkt + ' eff ' + co.up.eff + ' spd ' + co.up.spd + ' profit/s $' + fmt(g.companyProfit(co)) + ' products ' + Object.keys(co.prods).filter((k) => co.prods[k].unlocked).length);
