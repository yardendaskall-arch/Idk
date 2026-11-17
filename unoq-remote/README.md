# UNO Q Remote

An Android app that controls an **Arduino UNO Q** over Wi-Fi.

```
Phone (UnoQRemote.apk) ──HTTP :7000──▶ UNO Q Linux side (python/main.py) ──Bridge──▶ STM32 MCU (sketch.ino)
```

## What you can control

- **Built-in RGB LEDs** LED3 and LED4 (turn red, green and blue on or off)
- **Digital pins D0–D13**: set each one to output, input, or input with pull-up; switch outputs HIGH/LOW; read inputs live
- **PWM** on D3, D5, D6, D9, D10, D11 with sliders (0–255)
- **Analog inputs A0–A5**, read live

## 1. Put the app on the board

1. Open **Arduino App Lab** and connect to your UNO Q.
2. Create a new app called `UNO Q Remote`.
3. Copy the files from `arduino-app/` into it:
   - `app.yaml` (adds the **WebUI - HTML** brick, which runs the web server on port 7000)
   - `python/main.py`
   - `sketch/sketch.ino` and `sketch/sketch.yaml`
4. Press **Run**. Make sure the board is connected to the **same Wi-Fi network** as your phone.

## 2. Install the APK on your phone

1. Copy `UnoQRemote.apk` to your phone, or download it from GitHub.
2. Open it. Android will ask you to allow installs from that source. Allow it, then install.
   (Play Protect may warn about an unknown app. Choose *Install anyway*.)

## 3. Connect

- Tap **Find board** to scan your Wi-Fi network for the UNO Q, or
- type the board's IP address (App Lab shows it, and so does your router), keep port `7000`, and tap **Connect**.

The app remembers the address for next time.

## The HTTP API

You can also call it from a browser or script, for example `http://<board-ip>:7000/api/led?led=3&r=1`.

| Endpoint | Parameters |
| --- | --- |
| `/api/info` | none |
| `/api/status` | none |
| `/api/mode` | `pin` (D0–D13, A0–A5), `mode` = `output` / `input` / `input_pullup` |
| `/api/digital/write` | `pin`, `value` 0/1 |
| `/api/digital/read` | `pin` |
| `/api/digital/all` | none |
| `/api/pwm` | `pin` (D3, D5, D6, D9, D10, D11), `value` 0–255 |
| `/api/analog/read` | `pin` (A0–A5) |
| `/api/analog/all` | none |
| `/api/led` | `led` 3 or 4, `r`, `g`, `b` 0/1 |

Every response is JSON with `"ok": true` or `"ok": false, "error": "..."`.

## Rebuilding the APK

The app is plain Java with no external libraries. It builds without Gradle on Ubuntu/Debian:

```bash
sudo apt install openjdk-17-jdk android-sdk-platform-23 aapt zipalign apksigner dalvik-exchange
./android/build.sh      # writes UnoQRemote.apk
```

`android/debug.keystore` is a throwaway debug key (password `android`). It's committed so that rebuilt APKs install as updates over the old one.
