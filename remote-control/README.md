# Laptop Remote

Control your laptop from your Android phone: **touchpad, keyboard, media keys, lock/sleep/shut down, and a live view of the screen you can tap on.**

It has two parts:

| Part | What it is |
|---|---|
| **`LaptopRemote.apk`** | The Android app (install it on your phone). |
| **`server/`** | A small program that runs on your laptop and receives the commands. |

Your phone and laptop talk directly to each other over your Wi-Fi. Nothing goes through any cloud service.

---

## 1. Install the app on your phone

1. Get `LaptopRemote.apk` onto your phone (download it from this repository in your phone's browser, or send it to yourself).
2. Tap the file. Android will ask to allow installing apps from this source - allow it for that one time.
3. If Google Play Protect says it's an unknown app, choose **Install anyway**. (The app is signed with a debug key because it isn't published on the Play Store - see *Security* below.)

Needs Android 7.0 or newer.

## 2. Start the server on your laptop

1. Install **Python 3.8 or newer** from <https://www.python.org/downloads/> (on Windows, tick **"Add python.exe to PATH"** in the installer).
2. Copy the `server` folder to your laptop and start it:
   * **Windows:** double-click `start-server.bat`
   * **Mac / Linux:** open a terminal in the folder and run `./start-server.sh`

   The first start downloads a few helper packages (needs internet, ~1 minute). After that it prints something like:

   ```
   Address : 192.168.1.23
   Port    : 8765
   PIN     : 4821-5937
   Fingerprint : 1811 2072 ECBD 5653 27AB 8B99 E46D 5EAF
                 E36F 0518 D67C 9BB0 D712 BCC3 BFCD 6660
   ```
   Leave this window open while you want to control the laptop. Close it (or press Ctrl+C) to stop.

3. **Windows** will ask whether to allow Python through the firewall - allow it on **Private networks**.

## 3. Connect

1. Put the phone on the **same Wi-Fi** as the laptop.
2. Open **Laptop Remote**. Tap **Find laptops on this Wi-Fi** and pick yours (or type the *Address* and *Port* by hand).
3. Enter the **PIN** and tap **Connect**.
4. **First time only:** the app shows a fingerprint. Check that it matches the one in the server window, then tap **They match - Trust**. (This proves you're talking to *your* laptop and not someone else on the network.) The app remembers it from then on.

## 4. Use it

| Tab | What you can do |
|---|---|
| **Touchpad** | Drag = move pointer · tap = click · two-finger tap = right-click · two-finger drag = scroll · hold then drag = drag & drop. Left/Middle/Right buttons below can be held. Speed slider and natural-scroll switch. |
| **Keys** | Tap the box and type: everything appears on the laptop live. Ctrl/Alt/Shift/Win toggles, Esc/Tab/Enter/arrows (hold to repeat), F1-F12, and shortcuts (copy, paste, undo, switch app, show desktop...). |
| **Media** | Play/pause, next/previous, volume up/down (hold), mute. |
| **Screen** | See the laptop screen. Tap = move + click, drag = move pointer, hold = right-click, pinch to zoom. Choose Low/Medium/High quality. |
| **Power** | Lock, sleep, restart, shut down (all but Lock ask for confirmation first). |

---

## Platform notes

* **macOS:** the first time, allow your Terminal (or whatever runs the script) under *System Settings → Privacy & Security* in **Accessibility** *and* **Screen Recording**. Without these, mouse/keyboard do nothing and the screen view is black.
* **Linux:** works on **X11** sessions. On Wayland, mouse/keyboard control and screen view are blocked by the system - choose "Ubuntu on Xorg" (or similar) on the login screen.
* **Windows:** works as is. Lock/sleep/restart/shutdown use the standard Windows commands.
* The screen view shows your **primary monitor**.

## Using it away from home

The phone only needs to be able to reach the laptop's address. The safe way to do that from anywhere is a private VPN such as [Tailscale](https://tailscale.com/) on both devices, then use the laptop's Tailscale address in the app. **Don't forward the port on your router** to the internet.

## Security (please read)

* Everything is **encrypted (TLS)**, and the app **pins the laptop's certificate** after you confirm the fingerprint once. If the certificate ever changes, the app warns you instead of connecting.
* A random **8-digit PIN** is required. After 5 wrong tries from the same address it locks out for 5 minutes; wrong guesses are also slowed down.
* The server can only do what's listed above - there is **no remote command line and no file access**. **What you type is never written to any log.**
* Still, **anyone who has your PIN and can reach your laptop can use its mouse and keyboard**, which is the same as sitting at it. Keep the PIN private, and stop the server when you don't need it. `python laptop_remote_server.py --new-pin` makes a new PIN.
* The PIN and certificate are stored in `~/.laptop-remote/` (private to your user). The phone keeps the PIN in the app's private storage; app backups are turned off.
* Server options: `--no-power` (disable shut down/restart/sleep/lock), `--no-screen`, `--no-discovery` (don't answer "find laptops" scans), `--new-pin`, `--new-cert`, `--port N`, `--host ADDR`.
* **About the APK signature:** it's signed with the standard public Android *debug* key (`android/debug.keystore`, password `android`) so that rebuilt versions can update the installed app. That's fine for your own phone, but it is not a Play Store release key - don't publish it as-is.

## Troubleshooting

| Problem | Try |
|---|---|
| "Could not reach the laptop" | Same Wi-Fi? Server window still open? Allow Python in the laptop's firewall (private network). Guest/"AP isolation" Wi-Fi blocks phone↔laptop traffic. |
| "Find laptops" finds nothing | Some routers block scanning. Type the address from the server window by hand. |
| "Wrong PIN" | Use the PIN shown in the *current* server window (it changes with `--new-pin`). |
| Warning that the certificate changed | Expected after `--new-cert` or reinstalling the server. Otherwise someone may be intercepting - don't trust it. |
| Mouse moves but typing/some keys don't work (Mac) | Grant the Accessibility permission (see above). |
| Screen is black (Mac) / error (Linux) | Grant Screen Recording (Mac) / use an X11 session (Linux). |
| Typing shows the wrong characters | The laptop's keyboard layout decides what some symbols produce; unusual characters may be skipped. |

---

## For developers

```
LaptopRemote.apk          prebuilt, installable app
build.sh                  rebuilds the APK (no Gradle / Android Studio needed)
android/                  app source (plain Java, no AndroidX), manifest, icons, debug.keystore
server/                   laptop_remote_server.py + launchers + requirements.txt
tests/                    server, protocol, gesture and UI tests
```

**Protocol** (TCP + TLS): frames of `[uint32 length][uint8 type][payload]`; type 0 is JSON (`hello`, `move`, `moveto`, `click`, `down`/`up`, `scroll`, `text`, `key`, `media`, `power`, `screen`, `ping`, ...), type 1 is a JPEG screen frame. The login happens only *after* the client has checked the certificate fingerprint, so the PIN is never sent to an unverified server. See the top of `laptop_remote_server.py` and `RemoteClient.java`.

**Rebuild the APK** (Debian/Ubuntu): `sudo apt install default-jdk-headless aapt zipalign apksigner dalvik-exchange`, then `./build.sh`. It fetches the Android framework jar from Maven Central the first time (or set `ANDROID_JAR=/path/to/android.jar`, API 31+).

**Tests**

```bash
pip install -r server/requirements.txt
ORG_JSON_JAR=/path/to/json-20251224.jar python -m unittest discover -s tests   # server, protocol, gestures
```

The first group needs only Python. The Java-based ones (client protocol on a JVM, gesture logic) run when a JDK and the `org.json` jar are available. The UI smoke test (`tests/robolectric`) drives the real activities on a simulated Android against the real server in `--dry-run` mode - see `tests/robolectric/README.md`.
