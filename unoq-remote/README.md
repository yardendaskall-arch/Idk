# UNO Q Remote

Control an **Arduino UNO Q** from an Android phone or from any web browser:
its pins and LEDs, its Linux system, and a terminal.

```
                         ┌─ port 7000: App Lab app (python/main.py) ──Bridge──▶ MCU sketch  →  pins & LEDs
Phone app / web browser ─┤
                         └─ port 7001: host agent (unoq_agent.py, needs PIN) →  system info, Wi-Fi,
                                                                                 processes, reboot, terminal
```

There are two parts on the board:

| Part | What it does | Why it's separate |
| --- | --- | --- |
| **App Lab app** (`arduino-app/`) | Pins, PWM, analog readings, RGB LEDs, and the web panel on port 7000 | Only App Lab apps can talk to the microcontroller |
| **Host agent** (`host-agent/`) | System info, Wi-Fi, processes, reboot/shutdown, terminal on port 7001 | App Lab apps run inside a sandboxed container, so they can't reach the real Linux system |

## Phone app tabs

- **Pins**: built-in RGB LEDs (LED3, LED4), D0–D13 (IN / OUT / PULL-UP, on/off, live input readings), PWM sliders on D3/D5/D6/D9/D10/D11, live analog A0–A5.
- **System**: hostname, OS, uptime, CPU / RAM / disk use, temperature, IP addresses, Wi-Fi scan and connect, process list with Stop, Reboot, Shut down.
- **Terminal**: run commands on the board's Linux. It keeps track of `cd`, has history (↑ ↓) and shortcut keys.

The web panel has the same three tabs.

---

## 1. App Lab app (pins + web panel)

In Arduino App Lab, create an app and add the **WebUI - HTML** brick. Then replace these files with the ones from `arduino-app/`:

- `python/main.py`
- `sketch/sketch.ino`
- `assets/index.html` (the web panel)

Press **Run**. Opening `http://<board>:7000` now shows the control panel.

## 2. Host agent (system + terminal), installed once

You need a command line on the board's Linux. On a PC that's connected to the board by USB, you can use **adb**:

1. Download Android **SDK Platform-Tools** for your PC from developer.android.com/tools/releases/platform-tools and unzip it.
2. Put `host-agent/unoq_agent.py` and `host-agent/index.html` into that `platform-tools` folder.
3. Open a command prompt in that folder and run:
   ```
   adb devices
   adb push unoq_agent.py index.html /tmp/
   adb shell
   ```
   You're now on the board. Run:
   ```
   python3 /tmp/unoq_agent.py --install
   ```
   If it asks for a password, it's the board password you chose when setting up the UNO Q in App Lab.
4. It prints a **PIN**. Write it down. You need it in the app and the web panel.

The agent starts automatically every time the board boots. It needs nothing from the internet.

- Show the PIN again (inside `adb shell`): `sudo python3 /opt/unoq-agent/unoq_agent.py --pin`
- Remove the agent: `sudo python3 /opt/unoq-agent/unoq_agent.py --uninstall`

## 3. Get the board onto Wi-Fi (if it isn't)

You can do this from the PC over USB using the new web panel:

```
adb forward tcp:7001 tcp:7001
```

Then open **http://localhost:7001** in the PC's browser. Go to **System**, enter the PIN, tap **Scan** under Wi-Fi, and pick your network (home Wi-Fi, or your phone's hotspot). The board doesn't need internet. It only needs to be on the same network as your phone.

## 4. Phone

Install `UnoQRemote.apk`. On the **Pins** tab, tap **Find board**, or type the board's IP and tap **Connect**. Open **System** or **Terminal** and enter the PIN once. The app remembers it.

From a computer on the same network, open `http://<board-ip>:7000` (or `:7001`).

---

## Security

- The pin controls on port 7000 have no password. Anyone on your network can switch pins.
- Everything on port 7001 (system, terminal) needs the 8-digit PIN. A wrong guess is delayed by one second, so the PIN can't be guessed quickly.
- The agent runs as the board's normal user (`arduino`), not root. The installer only lets it reboot, shut down and stop processes without a password. For other `sudo` commands in the terminal, use `echo YOURPASSWORD | sudo -S command`.
- Traffic isn't encrypted. Only use this on networks you trust.

## HTTP API

**Port 7000 (pins).** Every endpoint is a GET with query parameters, for example `http://<board>:7000/api/led?led=3&r=1`.

| Endpoint | Parameters |
| --- | --- |
| `/api/info`, `/api/status` | none |
| `/api/mode` | `pin` (D0–D13, A0–A5), `mode` = `output` / `input` / `input_pullup` |
| `/api/digital/write` | `pin`, `value` 0/1 |
| `/api/digital/read`, `/api/digital/all` | `pin` (read only) |
| `/api/pwm` | `pin` (D3, D5, D6, D9, D10, D11), `value` 0–255 |
| `/api/analog/read`, `/api/analog/all` | `pin` (A0–A5, read only) |
| `/api/led` | `led` 3 or 4, `r`, `g`, `b` 0/1 |

**Port 7001 (agent).** Send the header `X-Pin: <PIN>`. POST bodies are JSON.

| Endpoint | Method | Body / result |
| --- | --- | --- |
| `/api/ping` | GET | no PIN needed |
| `/api/os/info` | GET | hostname, OS, uptime, CPU, memory, disk, temperature, IPs, Wi-Fi |
| `/api/os/processes` | GET | top processes by CPU |
| `/api/os/kill` | POST | `{"pid": 123, "force": false}` |
| `/api/os/wifi/scan` | GET | nearby networks |
| `/api/os/wifi/connect` | POST | `{"ssid": "...", "password": "..."}` |
| `/api/os/power` | POST | `{"action": "reboot"}` or `"shutdown"` |
| `/api/term/exec` | POST | `{"cmd": "ls -la", "cwd": "/home/arduino", "timeout": 60}` returns output, exit code and new cwd |

## Rebuilding the APK

The app is plain Java with no external libraries. It builds without Gradle on Ubuntu/Debian:

```bash
sudo apt install openjdk-17-jdk android-sdk-platform-23 aapt zipalign apksigner dalvik-exchange
./android/build.sh      # writes UnoQRemote.apk
```

`android/debug.keystore` is a throwaway debug key (password `android`). It's committed so that rebuilt APKs install as updates over the old one.
