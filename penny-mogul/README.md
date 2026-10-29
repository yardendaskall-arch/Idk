# 🪙 Penny Mogul

An idle / tycoon game for Android. Tap a coin to earn fake money, trade 12 fake stocks that
rise and fall on news and rumours, then build companies that make and sell products.

**Install:** copy [`dist/PennyMogul.apk`](dist/PennyMogul.apk) to your phone and open it
(you may need to allow "Install unknown apps" for your browser / file manager). Android 7.0+.

## The game

| Tab | What you do |
| --- | --- |
| **Earn** | Tap the coin (fast taps build a streak up to ×2). Buy upgrades: Tap Power, Side Hustle (passive income), Broker Perks (lower fees), Lab Grants (faster R&D). Follow the Goals list for cash rewards. |
| **Market** | 12 fake stocks across 7 sectors with live charts (1M / 10M / 1H / 8H, drag to scrub). Headlines move prices: some are real, some are rumours. Dividend stocks pay out every minute. Buy and sell fractional shares. |
| **Portfolio** | Net-worth chart, allocation donut, holdings with P/L, lifetime stats. |
| **Business** | Found companies in 9 industries (Lemonade → Bakery → Coffee → Sneakers → Electronics → Games → Auto → Pharma → Aerospace). Build production lines, **research new products**, set prices (or let auto-pricing do it), buy marketing / efficiency / speed / warehouse upgrades. Random market events change demand and costs. |
| **More** | 16 achievements, stats, sound / haptics settings, reset. |

Your companies keep earning while the app is closed (up to 6 hours of offline progress), and the
game autosaves to the device.

All money is fake. All fun is real.

## Project layout

```
web/                 the whole game (HTML/CSS/JS, no build step) -> bundled as assets/www
  js/data.js         stocks, news, industries/products, upgrades, achievements
  js/engine.js       game logic (no DOM; runs in Node for tests)
  js/charts.js       canvas price chart, sparkline, donut
  js/ui-core.js …    Preact + htm UI (vendored in web/vendor, works offline)
android/             tiny native shell: one full-screen WebView + manifest + icons
tools/build.sh       builds + signs the APK without Gradle or the Android SDK
tools/make_icons.py  regenerates the launcher icons
tests/               engine tests, economy pacing bot, headless-Chromium UI test
dist/PennyMogul.apk  the built app
keystore/            debug signing key (see below)
```

## Build it yourself

```bash
# Debian/Ubuntu packages that provide the Android build tools
sudo apt-get install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23 openjdk-17-jdk-headless
./tools/build.sh                 # -> dist/PennyMogul.apk
```

`build.sh` runs `javac → dx → aapt → zipalign → apksigner` and verifies the result. It does not need
the Android SDK downloads, which is handy in locked-down environments.

## Tests

```bash
node tests/engine.test.js        # 21 engine tests (economy, trading, saves, offline progress, ...)
node tests/balance.js 6          # greedy "optimal player" bot: prints net-worth milestones for 6 hours
node tests/ui-smoke.js [outDir]  # needs Playwright; clicks through every screen at phone size, saves screenshots
```

## Notes

* `keystore/debug.keystore` is a throwaway **debug** key (password `android`), committed on purpose so
  every build is signed the same way and new versions install over old ones without losing your save.
  Do not reuse it for anything you publish.
* Saves live in the WebView's `localStorage`. Clearing the app's storage resets the game.
