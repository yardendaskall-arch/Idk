# Micro-Dose Discipline

A widget-only Android "dopamine gate": complete a 10-second healthy micro-task
inside a home-screen widget to unlock exactly 1 minute of a blacklisted
entertainment app (TikTok, YouTube, Instagram, …), then get forcefully locked
out again until the next micro-dose.

## Architecture

- **`MainActivity`** — onboarding only: requests `SYSTEM_ALERT_WINDOW`, usage
  access, the accessibility service, and notification permission; lets the
  user pick which apps count as the reward. The app has no other UI.
- **`widget/MicroDoseWidget`** (Jetpack Glance) — renders the gate:
  `LOCKED → TASK_ACTIVE → AWAITING_CHECKOFF → REWARD_READY → IN_REWARD_SESSION → LOCKED`.
  State lives in `data/WidgetStateRepository` (DataStore) and every mutation
  is followed by an explicit `GlanceAppWidget.updateAll()` call, so the
  widget redraws instantly instead of waiting on the OS's periodic update
  cycle.
- **`service/TaskCountdownService`** — short-lived (≤10s) service that ticks
  the micro-task countdown once per second.
- **`service/LockForegroundService`** — genuine foreground service running
  the wall-clock 60 second reward countdown; survives Doze/App Standby.
  When it expires it resets the gate to `LOCKED` and forces the user home.
- **`service/AppBlockAccessibilityService`** — watches foreground-app
  changes so re-opening a blacklisted app directly (bypassing the widget)
  gets bounced home immediately, and provides the forceful redirect used
  when the 60s lock fires while the user is still inside the target app.
- **`service/OverlayBlocker`** — brief `SYSTEM_ALERT_WINDOW` banner
  reinforcing the "time's up" redirect.

### A platform honesty note on "Hold to Confirm"

Jetpack Glance / `RemoteViews` widgets have no API for press-duration or
long-press gestures — only discrete click callbacks. `CheckoffAction`
therefore implements the "active engagement" requirement as a **two-tap
confirm**: the first tap arms a 3-second confirmation window, and only a
second deliberate tap inside that window advances the gate. This gets the
same anti-mindless-tapping property as a true hold gesture within what the
widget surface can actually observe.

## Building

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## CI

`.github/workflows/build-apk.yml` builds a debug APK on every push to any
branch other than `main`/`master` and uploads it as a workflow artifact.
