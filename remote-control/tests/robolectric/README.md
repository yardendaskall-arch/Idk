# UI smoke test (Robolectric)

Runs the app's real `MainActivity` / `ControlActivity` on a simulated Android 8.1 and drives them with touch
events, button clicks and typing against the **real Python server** started in `--dry-run` mode (it records
what it receives instead of moving your mouse or shutting anything down).

It covers: input validation, wrong PIN, the first-connection fingerprint dialog (compared with the one the
server prints), the "certificate changed" warning, every tab, touchpad drag/tap/hold-drag, live typing,
modifier and shortcut keys, key auto-repeat, media keys, screen frames and taps, power confirmation, back /
disconnect, and the laptop going away.

Two-finger gestures can't be simulated by this Robolectric version; they are covered by the plain-JVM
`tests/app/laptopremote/GestureTest.java` instead.

## Running it

Robolectric 3.8 is used because it has no AndroidX dependency, but it needs a **JDK 8** to run the tests
(Gradle itself can use a newer JDK).

```bash
sudo apt install openjdk-8-jdk-headless          # or any JDK 8; point JDK8_HOME at it
pip install -r ../../server/requirements.txt     # (in a venv) so the server can run
export ANDROID_JAR=/path/to/android.jar          # API 31+, used for compiling only
export PY_BIN=/path/to/that/venv/bin/python
gradle test
```

Dependencies come from Maven Central (Maven Central rate-limits now and then; just re-run).
