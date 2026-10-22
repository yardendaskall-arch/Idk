# Decor

A decoration-only Android app: an animated live wallpaper for your home and lock screen.
Nothing in it does anything useful, on purpose.

- **Scenes:** Aurora, Bubbles, Starry night, Fireflies
- **Colors:** Sunset, Ocean, Forest, Candy
- **Speed:** calm to lively
- **Tap the screen** for ripples, popping bubbles, shooting stars, or fireflies gathering at your finger

Targets Android 16 (API 36), runs on Android 11 and up. No permissions, no network, no ads.

## Get the APK

Every push that touches `decor-app/` builds an APK in GitHub Actions
(workflow **Build Decor APK**). Open the run, download the `decor-debug-apk` artifact,
and unzip it to get `app-debug.apk`.

## Install on your phone

1. Copy `app-debug.apk` to the phone and open it from **My Files**.
2. If asked, allow installs from that app (Settings > Apps > Special access > Install unknown apps).
3. Open **Decor**, pick a scene and colors, tap **Set as wallpaper**, and confirm.

## Build it yourself

Open `decor-app/` in Android Studio, or run `./gradlew assembleDebug` with the Android SDK
installed. The APK lands in `app/build/outputs/apk/debug/`.
