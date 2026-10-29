/* UI smoke test in headless Chromium at phone size.
 *   node tests/ui-smoke.js [outDir]
 * Clicks through every screen, fails on any console/page error, writes screenshots to outDir. */
'use strict';
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const OUT = process.argv[2] || path.join(__dirname, '../build/shots');
fs.mkdirSync(OUT, { recursive: true });
const URL = 'file://' + path.join(__dirname, '../web/index.html');

const errors = [];
let step = 0;
const log = (m) => console.log('  ' + m);

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 412, height: 892 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message + '\n' + (e.stack || '').split('\n').slice(0, 4).join('\n')));

  const shot = async (name) => { await page.waitForTimeout(450); await page.screenshot({ path: path.join(OUT, String(++step).padStart(2, '0') + '-' + name + '.png') }); };
  const click = (sel, opts) => page.locator(sel).first().click(opts);
  const tapAt = async (sel) => { const b = await page.locator(sel).first().boundingBox(); await page.touchscreen.tap(b.x + b.width / 2, b.y + b.height / 2); };
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const tick = (n) => ev((k) => { for (let i = 0; i < k; i++) PM.game.tick(); PM.ui.render(); }, n);

  await page.goto(URL);
  await page.waitForSelector('.app');
  await shot('intro');
  await click('text=Let\'s go!');
  await page.waitForTimeout(400);

  // --- Earn: tap the coin
  for (let i = 0; i < 12; i++) { await tapAt('.coin'); await page.waitForTimeout(60); }
  await shot('earn-tapping');
  const cash0 = await ev(() => PM.game.s.cash);
  if (!(cash0 > 12)) errors.push('tapping did not earn cash: ' + cash0);
  log('cash after 12 taps: $' + cash0.toFixed(2));

  // give cash and buy upgrades
  await ev(() => { PM.game.s.cash = 3000; PM.ui.render(); });
  await page.evaluate(() => document.querySelector('.main').scrollTo(0, 420));
  await shot('earn-upgrades');
  await tapAt('.upg .buy.can');
  { const b = await page.locator('.upg').nth(1).locator('.buy').boundingBox(); await page.touchscreen.tap(b.x + b.width / 2, b.y + b.height / 2); }
  await page.waitForTimeout(200);

  // --- Market
  await click('.nav-item:nth-child(3)'); // market (index 1 + nav-ind div first)
  await page.waitForTimeout(300);
  await shot('market');
  await click('.stock-row');
  await page.waitForTimeout(500);
  await shot('stock-sheet');
  await click('.sheet .amt-chips button:nth-child(2)'); // $100
  await shot('stock-buy-chosen');
  await click('.sheet .btn-up');
  await page.waitForTimeout(400);
  await shot('stock-bought');
  const holds = await ev(() => Object.keys(PM.game.s.holdings));
  if (holds.length !== 1) errors.push('buy did not create a holding');
  await ev(() => { for (let i = 0; i < 90; i++) PM.game.tick(); PM.ui.render(); });
  // scrub the chart
  const cb = await page.locator('.chart-wrap').first().boundingBox();
  await page.mouse.move(cb.x + cb.width * 0.6, cb.y + cb.height * 0.5);
  await page.mouse.down(); await page.mouse.move(cb.x + cb.width * 0.4, cb.y + cb.height * 0.5, { steps: 4 });
  await shot('stock-scrub');
  await page.mouse.up();
  await click('.sheet .seg button:nth-child(2)'); // sell
  await click('.sheet .amt-chips button:last-child'); // ALL
  await click('.sheet .btn-down');
  await page.waitForTimeout(400);
  await shot('stock-sold');
  await click('.sheet .x-btn');
  await page.waitForTimeout(400);

  // --- Portfolio (buy a few first)
  await ev(() => { const g = PM.game; g.s.cash = 20000; for (const s of ['NBLD', 'GLDB', 'PIZZ', 'MEOW']) g.buy(s, 2500); for (let i = 0; i < 120; i++) g.tick(); PM.ui.render(); });
  await click('.nav-item:nth-child(4)');
  await page.waitForTimeout(400);
  await shot('portfolio');
  await page.evaluate(() => document.querySelector('.main').scrollTo(0, 700));
  await shot('portfolio-holdings');

  // --- Business
  await ev(() => { PM.game.s.cash = 5e6; PM.ui.render(); });
  await click('.nav-item:nth-child(5)');
  await page.waitForTimeout(300);
  await shot('business-empty');
  await click('.add-co');
  await page.waitForTimeout(500);
  await shot('found-list');
  await click('.ind-card:nth-child(3)'); // bakery (1st child is the muted blurb)
  await page.waitForTimeout(300);
  await shot('found-name');
  await click('.btn-gold');
  await page.waitForTimeout(700);
  await shot('company-page');
  await ev(() => { const g = PM.game; g.s.cash = 5e6; const co = g.s.companies[0]; for (let i = 0; i < 120; i++) g.tick(); PM.ui.render(); });
  await page.waitForTimeout(300);
  await shot('company-running');
  await page.evaluate(() => document.querySelector('.page-scroll').scrollTo(0, 650));
  await shot('company-products');
  await ev(() => { PM.game.s.cash = 5e6; PM.ui.render(); });
  await click('.prod .btn-primary'); // research
  await page.waitForTimeout(300);
  await page.evaluate(() => document.querySelector('.page-scroll').scrollTo(0, 1500));
  await shot('company-upgrades');
  await click('.line-card.add', { force: true }).catch(() => {});
  // back out
  await ev(() => window.__pmBack());
  await page.waitForTimeout(400);
  await shot('business-with-company');

  // --- extra states: combo ring, news sheet, line picker, confirm modal
  await click('.nav-item:nth-child(2)');
  await page.waitForTimeout(300);
  await ev(() => { const t0 = Date.now(); for (let i = 0; i < 38; i++) PM.game.tap(); PM.ui.render(); });
  await shot('earn-combo');
  await click('.nav-item:nth-child(3)');
  await click('text=See all');
  await page.waitForTimeout(500);
  await shot('news-sheet');
  await click('.sheet .x-btn');
  await page.waitForTimeout(350);
  await click('.nav-item:nth-child(5)');
  await page.waitForTimeout(300);
  await click('.co-card');
  await page.waitForTimeout(500);
  await click('.line-card:not(.add)');
  await page.waitForTimeout(500);
  await shot('line-picker');
  await click('.sheet .x-btn');
  await page.waitForTimeout(350);
  await page.evaluate(() => document.querySelector('.page-scroll').scrollTo(0, 99999));
  await click('.btn-danger');
  await page.waitForTimeout(500);
  await shot('confirm-sell');
  await click('text=Cancel');
  await page.waitForTimeout(350);
  await ev(() => window.__pmBack());
  await page.waitForTimeout(350);

  // --- More
  await click('.nav-item:nth-child(6)');
  await page.waitForTimeout(300);
  await shot('more');

  // back button semantics
  const handled = await ev(() => window.__pmBack());
  const handled2 = await ev(() => window.__pmBack());
  log('back handled (non-earn tab): ' + handled + ', at earn root: ' + handled2);
  if (handled !== true || handled2 !== false) errors.push('back button semantics wrong');

  // --- persistence
  await ev(() => PM.game.save());
  const nw = await ev(() => PM.game.netWorth());
  await page.reload();
  await page.waitForSelector('.app');
  await page.waitForTimeout(500);
  const nw2 = await ev(() => PM.game.netWorth());
  log('net worth before/after reload: ' + nw.toFixed(2) + ' / ' + nw2.toFixed(2));
  if (Math.abs(nw2 - nw) / Math.max(1, nw) > 0.05) errors.push('save/load mismatch ' + nw + ' vs ' + nw2);
  const nCo = await ev(() => PM.game.s.companies.length);
  if (nCo !== 1) errors.push('company lost on reload');

  // --- offline progress: pretend we were away for 2 hours
  const cashBefore = await ev(() => PM.game.s.cash);
  await ev(() => {
    PM.game.save();
    const o = JSON.parse(localStorage.getItem('pennymogul.save.v1'));
    o.lastSeen = Date.now() - 2 * 3600 * 1000;
    localStorage.setItem('pennymogul.save.v1', JSON.stringify(o));
    PM.game.save = () => true; // the app re-saves on pagehide; keep our doctored timestamp
  });
  await page.reload();
  await page.waitForSelector('.app');
  await page.waitForTimeout(700);
  const cashAfter = await ev(() => PM.game.s.cash);
  const hasWelcome = await page.locator('.modal h2').first().textContent().catch(() => '');
  log('offline: cash ' + cashBefore.toFixed(0) + ' -> ' + cashAfter.toFixed(0) + ', modal "' + hasWelcome + '"');
  if (!/Welcome back/.test(hasWelcome)) errors.push('welcome-back modal did not show');
  if (!(cashAfter > cashBefore)) errors.push('no offline earnings');
  await shot('welcome-back');

  await browser.close();
  if (errors.length) { console.error('\nFAILED:\n' + errors.join('\n')); process.exit(1); }
  console.log('\nUI smoke test passed. Screenshots in ' + OUT);
})().catch((e) => { console.error('test crashed:', e); process.exit(2); });
