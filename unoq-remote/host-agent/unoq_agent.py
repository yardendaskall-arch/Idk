#!/usr/bin/env python3
"""UNO Q Remote - host agent.

Runs on the UNO Q's real Linux system (not inside an App Lab container) and gives
the UNO Q Remote phone app and web panel:
  * system info (CPU, memory, disk, temperature, network)
  * Wi-Fi scan / connect
  * process list / kill
  * reboot / shut down
  * a command terminal

Every request except /api/ping needs the PIN (header "X-Pin").

Install once on the board (from `adb shell`, SSH or a keyboard):
    python3 unoq_agent.py --install
Show the PIN again:   sudo python3 /opt/unoq-agent/unoq_agent.py --pin
Remove:               sudo python3 /opt/unoq-agent/unoq_agent.py --uninstall

Only the Python standard library is used, so it works without internet.
"""

import json
import os
import pwd
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

VERSION = 2
PORT = int(os.environ.get("UNOQ_AGENT_PORT", "7001"))
INSTALL_DIR = "/opt/unoq-agent"
CONF_DIR = "/etc/unoq-agent"
PIN_FILE = os.path.join(CONF_DIR, "pin")
UNIT_FILE = "/etc/systemd/system/unoq-agent.service"
SUDOERS_FILE = "/etc/sudoers.d/90-unoq-agent"
HERE = os.path.dirname(os.path.abspath(__file__))
MAX_OUTPUT = 256 * 1024

# ----------------------------------------------------------------------------- helpers


def run(args, timeout=15):
    """Run a command, return (exit_code, combined_output)."""
    try:
        r = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           stdin=subprocess.DEVNULL, timeout=timeout)
        return r.returncode, r.stdout.decode("utf-8", "replace")
    except FileNotFoundError:
        return 127, f"{args[0]}: command not found"
    except subprocess.TimeoutExpired:
        return 124, "timed out"


def read(path, default=""):
    try:
        with open(path) as f:
            return f.read()
    except OSError:
        return default


def privileged(args):
    """Prefix with sudo -n when not root (the installer allows these commands)."""
    return args if os.geteuid() == 0 else ["sudo", "-n"] + args


# ----------------------------------------------------------------------------- auth

_pin = None
_fail_lock = threading.Lock()


def load_pin():
    global _pin
    pin = os.environ.get("UNOQ_AGENT_PIN") or read(PIN_FILE).strip()
    if not pin:
        pin = "".join(secrets.choice("0123456789") for _ in range(8))
        try:
            os.makedirs(CONF_DIR, exist_ok=True)
            with open(PIN_FILE, "w") as f:
                f.write(pin + "\n")
            os.chmod(PIN_FILE, 0o600)
        except OSError:
            print("warning: could not save PIN; it changes on every start", file=sys.stderr)
    _pin = pin
    return pin


def pin_ok(given):
    if given and secrets.compare_digest(given.encode(), _pin.encode()):
        return True
    with _fail_lock:          # at most one wrong guess per second, board-wide
        time.sleep(1.0)
    return False


# ----------------------------------------------------------------------------- system info

_cpu_prev = None
_cpu_lock = threading.Lock()


def _cpu_times():
    parts = read("/proc/stat").splitlines()[0].split()[1:]
    vals = [int(x) for x in parts]
    idle = vals[3] + (vals[4] if len(vals) > 4 else 0)
    return sum(vals), idle


def cpu_percent():
    global _cpu_prev
    with _cpu_lock:
        if _cpu_prev is None:
            _cpu_prev = _cpu_times()
            time.sleep(0.25)
        total, idle = _cpu_times()
        pt, pi = _cpu_prev
        _cpu_prev = (total, idle)
    dt = total - pt
    return round(100.0 * (1 - (idle - pi) / dt), 1) if dt > 0 else 0.0


def meminfo():
    m = {}
    for line in read("/proc/meminfo").splitlines():
        k, _, v = line.partition(":")
        m[k] = int(v.split()[0]) * 1024 if v.strip() else 0
    total = m.get("MemTotal", 0)
    avail = m.get("MemAvailable", m.get("MemFree", 0))
    return {"total": total, "used": total - avail}


def temperature():
    temps = []
    base = "/sys/class/thermal"
    if os.path.isdir(base):
        for z in os.listdir(base):
            t = read(f"{base}/{z}/temp").strip()
            if t.lstrip("-").isdigit():
                temps.append(int(t) / 1000.0)
    return round(max(temps), 1) if temps else None


def addresses():
    out = []
    code, text = run(["ip", "-4", "-o", "addr", "show"])
    if code == 0:
        for line in text.splitlines():
            p = line.split()
            if len(p) >= 4 and p[1] != "lo" and not p[1].startswith(("docker", "br-", "veth")):
                out.append({"iface": p[1], "ip": p[3].split("/")[0]})
    return out


def nm_split(line):
    return [f.replace("\\:", ":").replace("\\\\", "\\") for f in re.split(r"(?<!\\):", line)]


def wifi_current():
    code, text = run(["nmcli", "-t", "-f", "ACTIVE,SSID", "dev", "wifi"], timeout=8)
    if code == 0:
        for line in text.splitlines():
            f = nm_split(line)
            if len(f) >= 2 and f[0] == "yes":
                return f[1]
    return None


def os_info():
    rel = {}
    for line in read("/etc/os-release").splitlines():
        k, _, v = line.partition("=")
        rel[k] = v.strip('"')
    up = float((read("/proc/uptime") or "0").split()[0])
    du = shutil.disk_usage("/")
    return {
        "hostname": socket.gethostname(),
        "os": rel.get("PRETTY_NAME", "Linux"),
        "kernel": os.uname().release,
        "uptime": int(up),
        "load": list(os.getloadavg()),
        "cpus": os.cpu_count(),
        "cpu": cpu_percent(),
        "mem": meminfo(),
        "disk": {"total": du.total, "used": du.used},
        "temp": temperature(),
        "ips": addresses(),
        "wifi": wifi_current(),
        "user": pwd.getpwuid(os.geteuid()).pw_name,
        "agent": VERSION,
    }


def processes():
    code, text = run(["ps", "-eo", "pid=,user=,pcpu=,pmem=,comm=", "--sort=-pcpu"])
    if code != 0:
        raise RuntimeError(text.strip() or "ps failed")
    procs = []
    for line in text.splitlines()[:60]:
        p = line.split(None, 4)
        if len(p) == 5:
            procs.append({"pid": int(p[0]), "user": p[1], "cpu": float(p[2]),
                          "mem": float(p[3]), "name": p[4]})
    return procs


def kill(pid, force=False):
    sig = signal.SIGKILL if force else signal.SIGTERM
    try:
        os.kill(pid, sig)
        return "killed"
    except PermissionError:
        code, text = run(privileged(["kill", "-9" if force else "-15", str(pid)]))
        if code != 0:
            raise RuntimeError(text.strip() or "permission denied")
        return "killed (sudo)"
    except ProcessLookupError:
        raise RuntimeError(f"no process {pid}")


def wifi_scan():
    code, text = run(["nmcli", "-t", "-f", "IN-USE,SSID,SIGNAL,SECURITY", "dev", "wifi",
                      "list", "--rescan", "yes"], timeout=25)
    if code != 0:
        raise RuntimeError(text.strip() or "nmcli failed")
    best = {}
    for line in text.splitlines():
        f = nm_split(line)
        if len(f) < 4 or not f[1]:
            continue
        net = {"ssid": f[1], "signal": int(f[2] or 0), "secure": f[3] not in ("", "--"),
               "active": f[0] == "*"}
        old = best.get(net["ssid"])
        if old is None or net["signal"] > old["signal"] or net["active"]:
            net["active"] = net["active"] or (old or {}).get("active", False)
            best[net["ssid"]] = net
    return sorted(best.values(), key=lambda n: (-n["active"], -n["signal"]))


def wifi_connect(ssid, password):
    args = ["nmcli", "dev", "wifi", "connect", ssid]
    if password:
        args += ["password", password]
    code, text = run(args, timeout=60)
    if code != 0:
        raise RuntimeError(text.strip() or "could not connect")
    return text.strip()


def power(action):
    cmd = {"reboot": ["systemctl", "reboot"], "shutdown": ["systemctl", "poweroff"]}.get(action)
    if not cmd:
        raise RuntimeError("action must be reboot or shutdown")

    def later():
        time.sleep(1.0)
        run(privileged(cmd))

    threading.Thread(target=later, daemon=True).start()
    return action


# ----------------------------------------------------------------------------- remote display

SCREEN_HELP = "Remote display needs: sudo apt install python3-pil xdotool"
_screen_size = (0, 0)


def find_display():
    """Point DISPLAY / XAUTHORITY at the desktop (Xfce on X11)."""
    if not os.environ.get("DISPLAY"):
        socks = sorted(os.listdir("/tmp/.X11-unix")) if os.path.isdir("/tmp/.X11-unix") else []
        os.environ["DISPLAY"] = ":" + socks[0][1:] if socks else ":0"
    if not os.environ.get("XAUTHORITY"):
        for cand in (os.path.expanduser("~/.Xauthority"), f"/run/user/{os.geteuid()}/gdm/Xauthority"):
            if os.path.exists(cand):
                os.environ["XAUTHORITY"] = cand
                break


def screen_frame(width, quality):
    global _screen_size
    try:
        from PIL import Image, ImageGrab
    except ImportError:
        raise RuntimeError(SCREEN_HELP)
    find_display()
    try:
        img = ImageGrab.grab(xdisplay=os.environ["DISPLAY"])
    except Exception as e:  # noqa: BLE001
        raise RuntimeError(f"can't capture the desktop on {os.environ['DISPLAY']} ({e}). "
                           "Is someone logged in to the desktop?")
    _screen_size = img.size
    if width and width < img.width:
        img = img.resize((width, max(1, img.height * width // img.width)), Image.BILINEAR)
    import io
    buf = io.BytesIO()
    img.convert("RGB").save(buf, "JPEG", quality=quality)
    return buf.getvalue()


KEY_RE = re.compile(r"^[A-Za-z0-9_+]{1,40}$")


def xdo(*args):
    find_display()
    code, text = run(["xdotool"] + [str(a) for a in args], timeout=10)
    if code == 127:
        raise RuntimeError(SCREEN_HELP)
    if code != 0:
        raise RuntimeError(text.strip() or "xdotool failed")


def screen_input(a):
    kind = a.get("type")
    x, y = int(a.get("x", 0)), int(a.get("y", 0))
    button = {"left": 1, "middle": 2, "right": 3}.get(a.get("button", "left"), 1)
    if kind == "move":
        xdo("mousemove", x, y)
    elif kind in ("down", "up"):
        xdo("mousemove", x, y, "mousedown" if kind == "down" else "mouseup", button)
    elif kind == "click":
        xdo("mousemove", x, y, "click", "--repeat", max(1, min(3, int(a.get("count", 1)))), button)
    elif kind == "scroll":
        steps = max(-20, min(20, int(a.get("dy", 0))))
        if steps:
            xdo("mousemove", x, y, "click", "--repeat", abs(steps), 4 if steps < 0 else 5)
    elif kind == "key":
        keys = str(a.get("keys", ""))
        if not KEY_RE.match(keys):
            raise RuntimeError("bad key name")
        xdo("key", "--clearmodifiers", keys)
    elif kind == "type":
        text = str(a.get("text", ""))[:2000]
        if text:
            xdo("type", "--delay", "8", "--", text)
    else:
        raise RuntimeError("unknown input type")
    return kind


# ----------------------------------------------------------------------------- terminal

MARK = "__UNOQ_CWD__"


def term_exec(cmd, cwd, timeout):
    home = os.path.expanduser("~")
    if not cwd or not os.path.isdir(cwd):
        cwd = home
    script = f'{cmd}\n__unoq_rc=$?\nprintf "\\n{MARK}%s" "$PWD"\nexit $__unoq_rc\n'
    env = dict(os.environ, TERM="dumb", PAGER="cat", SYSTEMD_PAGER="", GIT_PAGER="cat")
    p = subprocess.Popen(["bash", "-c", script], cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
    timed_out = False
    try:
        out, _ = p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        try:
            os.killpg(p.pid, signal.SIGKILL)
        except OSError:
            pass
        out, _ = p.communicate()
    text = out.decode("utf-8", "replace")
    new_cwd = cwd
    i = text.rfind(MARK)
    if i >= 0:
        new_cwd = text[i + len(MARK):].strip() or cwd
        text = text[:i]
        if text.endswith("\n"):
            text = text[:-1]
    if len(text) > MAX_OUTPUT:
        text = "…(output truncated)…\n" + text[-MAX_OUTPUT:]
    if timed_out:
        text += f"\n[stopped after {timeout}s - interactive programs are not supported]"
    return {"output": text, "code": p.returncode, "cwd": new_cwd,
            "prompt": f"{pwd.getpwuid(os.geteuid()).pw_name}@{socket.gethostname()}"}


# ----------------------------------------------------------------------------- HTTP


class Handler(BaseHTTPRequestHandler):
    server_version = "unoq-agent"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, obj=None, body=None, ctype="application/json"):
        data = body if body is not None else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "X-Pin, Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self):
        self._send(204, body=b"")

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def _handle(self, method):
        url = urlparse(self.path)
        path = url.path.rstrip("/") or "/"
        q = {k: v[-1] for k, v in parse_qs(url.query).items()}
        body = {}
        if method == "POST":
            n = int(self.headers.get("Content-Length") or 0)
            if n:
                try:
                    body = json.loads(self.rfile.read(min(n, 1 << 20)) or b"{}")
                except ValueError:
                    return self._send(400, {"ok": False, "error": "bad JSON"})
        args = {**q, **body}

        if path in ("/", "/index.html"):
            page = os.path.join(HERE, "index.html")
            if os.path.exists(page):
                with open(page, "rb") as f:
                    return self._send(200, body=f.read(), ctype="text/html; charset=utf-8")
            return self._send(200, body=b"UNO Q Remote agent is running.", ctype="text/plain")
        if path == "/api/ping":
            return self._send(200, {"ok": True, "app": "unoq-agent", "version": VERSION})
        if not path.startswith("/api/"):
            return self._send(404, {"ok": False, "error": "not found"})
        if not pin_ok(self.headers.get("X-Pin") or args.get("pin")):
            return self._send(401, {"ok": False, "error": "wrong PIN"})

        if path == "/api/screen/frame":
            try:
                width = max(160, min(3840, int(args.get("w") or 1280)))
                quality = max(20, min(90, int(args.get("q") or 60)))
                jpg = screen_frame(width, quality)
            except Exception as e:  # noqa: BLE001
                return self._send(200, {"ok": False, "error": str(e)})
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(jpg)))
            self.send_header("X-Screen-W", str(_screen_size[0]))
            self.send_header("X-Screen-H", str(_screen_size[1]))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Expose-Headers", "X-Screen-W, X-Screen-H")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(jpg)
            return

        try:
            if path == "/api/auth":
                res = {}
            elif path == "/api/os/info":
                res = os_info()
            elif path == "/api/os/processes":
                res = {"processes": processes()}
            elif path == "/api/os/kill" and method == "POST":
                res = {"result": kill(int(args["pid"]), bool(args.get("force")))}
            elif path == "/api/os/wifi/scan":
                res = {"networks": wifi_scan()}
            elif path == "/api/os/wifi/connect" and method == "POST":
                res = {"result": wifi_connect(str(args["ssid"]), str(args.get("password") or ""))}
            elif path == "/api/os/power" and method == "POST":
                res = {"result": power(str(args.get("action")))}
            elif path == "/api/screen/input" and method == "POST":
                res = {"result": screen_input(args)}
            elif path == "/api/term/exec" and method == "POST":
                timeout = max(1, min(300, int(args.get("timeout") or 30)))
                res = term_exec(str(args.get("cmd", "")), args.get("cwd"), timeout)
            else:
                return self._send(404, {"ok": False, "error": "not found"})
            return self._send(200, {"ok": True, **res})
        except KeyError as e:
            return self._send(400, {"ok": False, "error": f"missing {e.args[0]}"})
        except Exception as e:  # noqa: BLE001 - report every failure to the client
            return self._send(200, {"ok": False, "error": str(e)})


def serve():
    pin = load_pin()
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    srv.daemon_threads = True
    print(f"UNO Q Remote agent v{VERSION} on port {PORT} (PIN in {PIN_FILE})", flush=True)
    if os.environ.get("UNOQ_AGENT_SHOW_PIN"):
        print(f"PIN: {pin}", flush=True)
    srv.serve_forever()


# ----------------------------------------------------------------------------- install


def install():
    if os.geteuid() != 0:
        os.execvp("sudo", ["sudo", sys.executable, os.path.abspath(__file__), "--install"])
    user = None
    for find in (lambda: pwd.getpwnam("arduino"), lambda: pwd.getpwuid(1000),
                 lambda: pwd.getpwnam(os.environ.get("SUDO_USER") or "root")):
        try:
            user = find()
            break
        except KeyError:
            continue
    os.makedirs(INSTALL_DIR, exist_ok=True)
    target = os.path.join(INSTALL_DIR, "unoq_agent.py")
    if os.path.abspath(__file__) != target:
        shutil.copy(os.path.abspath(__file__), target)
    page = os.path.join(HERE, "index.html")
    if os.path.exists(page) and HERE != INSTALL_DIR:
        shutil.copy(page, os.path.join(INSTALL_DIR, "index.html"))
    os.chmod(target, 0o755)

    print("Installing remote-display helpers (python3-pil, xdotool)…")
    code, text = run(["apt-get", "install", "-y", "python3-pil", "xdotool"], timeout=600)
    if code != 0:
        print("note: couldn't install them now (no internet?). Remote display will ask for them later.")

    os.makedirs(CONF_DIR, exist_ok=True)
    pin = load_pin()
    os.chown(PIN_FILE, user.pw_uid, user.pw_gid)
    os.chmod(PIN_FILE, 0o600)

    allowed = [shutil.which(c) for c in ("systemctl", "kill")]
    allowed = [a for a in allowed if a]
    rule = (f"{user.pw_name} ALL=(root) NOPASSWD: "
            f"{allowed[0]} reboot, {allowed[0]} poweroff, {allowed[1]}\n") if len(allowed) == 2 else ""
    if rule:
        tmp = SUDOERS_FILE + ".tmp"
        with open(tmp, "w") as f:
            f.write(rule)
        os.chmod(tmp, 0o440)
        if run(["visudo", "-cf", tmp])[0] in (0, 127):
            os.replace(tmp, SUDOERS_FILE)
        else:
            os.remove(tmp)
            print("note: skipped sudo rule; reboot/shutdown from the app may not work")

    with open(UNIT_FILE, "w") as f:
        f.write(f"""[Unit]
Description=UNO Q Remote host agent
After=network-online.target
Wants=network-online.target

[Service]
User={user.pw_name}
Environment=HOME={user.pw_dir}
WorkingDirectory={user.pw_dir}
ExecStart={sys.executable} {target}
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
""")
    run(["systemctl", "daemon-reload"])
    code, text = run(["systemctl", "enable", "--now", "unoq-agent.service"], timeout=30)
    run(["systemctl", "restart", "unoq-agent.service"], timeout=30)
    print(text.strip())
    print("\n========================================")
    print(" UNO Q Remote agent installed")
    print(f" Port: {PORT}    PIN: {pin}")
    for a in addresses():
        print(f" Address: {a['ip']}  ({a['iface']})")
    print("========================================")


def uninstall():
    if os.geteuid() != 0:
        os.execvp("sudo", ["sudo", sys.executable, os.path.abspath(__file__), "--uninstall"])
    run(["systemctl", "disable", "--now", "unoq-agent.service"], timeout=30)
    for p in (UNIT_FILE, SUDOERS_FILE, PIN_FILE):
        if os.path.exists(p):
            os.remove(p)
    shutil.rmtree(INSTALL_DIR, ignore_errors=True)
    run(["systemctl", "daemon-reload"])
    print("UNO Q Remote agent removed.")


if __name__ == "__main__":
    if "--install" in sys.argv:
        install()
    elif "--uninstall" in sys.argv:
        uninstall()
    elif "--pin" in sys.argv:
        print(read(PIN_FILE).strip() or "no PIN yet (agent not installed?)")
    else:
        serve()
