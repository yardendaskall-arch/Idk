#!/usr/bin/env python3
"""
Laptop Remote - server.

Run this on the laptop you want to control. The "Laptop Remote" Android app
connects to it over your Wi-Fi (or any network that can reach the laptop, such
as a VPN like Tailscale) and can then:

  * move the mouse, click, drag and scroll (touchpad)
  * type text and press keys / shortcuts
  * control media and volume
  * lock / sleep / shut down / restart the laptop
  * view the screen and tap on it

Security model
  * All traffic is encrypted with TLS (self-signed certificate, created on
    first run). The app shows you the certificate fingerprint the first time it
    connects so you can check it matches what this window prints.
  * A random 8-digit PIN is required. Wrong guesses are rate limited and the
    sender is locked out after a few failures.
  * There is no remote shell and no file access - only mouse, keyboard, media,
    power and screen view.
  * Nothing you type is ever written to the log.

Needs Python 3.8+.   Install:  pip install -r requirements.txt
Run:                           python laptop_remote_server.py
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import io
import json
import os
import secrets
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time
import zlib

PROTOCOL_VERSION = 1
DEFAULT_PORT = 8765
DISCOVERY_PORT = 8766
DISCOVERY_MAGIC = b"LAPTOP_REMOTE_DISCOVER_V1"

FRAME_JSON = 0
FRAME_JPEG = 1
MAX_PREAUTH_FRAME = 4096          # bytes accepted from a client that has not logged in
MAX_FRAME = 64 * 1024             # bytes accepted from a logged-in client
HANDSHAKE_TIMEOUT = 10            # seconds to finish TLS + login
IDLE_TIMEOUT = 45                 # seconds without any message (app pings every 10s)
MAX_CONNECTIONS = 16
MAX_TEXT_LEN = 2000
MAX_REL_MOVE = 3000
MAX_SCROLL = 100

BUTTONS = ("left", "right", "middle")
MODIFIERS = ("ctrl", "alt", "shift", "win", "primary", "switch")
NAMED_KEYS = frozenset(
    ["enter", "backspace", "tab", "esc", "space", "delete", "insert", "up", "down",
     "left", "right", "home", "end", "page_up", "page_down", "caps_lock",
     "print_screen", "menu", "win"] + ["f%d" % i for i in range(1, 13)]
)
MEDIA_ACTIONS = ("play_pause", "next", "prev", "vol_up", "vol_down", "mute")
POWER_ACTIONS = ("lock", "sleep", "shutdown", "restart")


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def os_name() -> str:
    if sys.platform.startswith("win"):
        return "windows"
    if sys.platform == "darwin":
        return "mac"
    return "linux"


# --------------------------------------------------------------------------
# Config: PIN and TLS certificate (kept in ~/.laptop-remote)
# --------------------------------------------------------------------------

def config_dir(override: str | None = None) -> str:
    path = override or os.environ.get("LAPTOP_REMOTE_HOME") or os.path.join(
        os.path.expanduser("~"), ".laptop-remote")
    os.makedirs(path, exist_ok=True)
    try:
        os.chmod(path, 0o700)
    except OSError:
        pass
    return path


def generate_pin() -> str:
    return "%08d" % secrets.randbelow(10 ** 8)


def normalize_pin(value: object) -> str:
    return "".join(c for c in str(value) if c.isdigit())


def load_or_create_pin(cfg: str, force_new: bool = False) -> str:
    path = os.path.join(cfg, "config.json")
    data: dict = {}
    if not force_new and os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError):
            data = {}
    pin = normalize_pin(data.get("pin", ""))
    if len(pin) != 8:
        pin = generate_pin()
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"pin": pin}, f)
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
        os.replace(tmp, path)
    return pin


def fingerprint_of_pem(cert_path: str) -> str:
    with open(cert_path, "r", encoding="ascii") as f:
        der = ssl.PEM_cert_to_DER_cert(f.read())
    return hashlib.sha256(der).hexdigest()


def format_fingerprint(hex_fp: str) -> str:
    hex_fp = hex_fp.upper()
    groups = [hex_fp[i:i + 4] for i in range(0, len(hex_fp), 4)]
    return " ".join(groups[:8]) + "\n" + " " * 19 + " ".join(groups[8:])


def load_or_create_cert(cfg: str, force_new: bool = False):
    """Returns (cert_path, key_path, sha256_hex_fingerprint)."""
    cert_path = os.path.join(cfg, "cert.pem")
    key_path = os.path.join(cfg, "key.pem")
    if force_new or not (os.path.exists(cert_path) and os.path.exists(key_path)):
        try:
            import datetime
            from cryptography import x509
            from cryptography.hazmat.primitives import hashes, serialization
            from cryptography.hazmat.primitives.asymmetric import ec
            from cryptography.x509.oid import NameOID
        except ImportError:
            sys.exit("The 'cryptography' package is required.\n"
                     "Install it with:  pip install -r requirements.txt")
        key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Laptop Remote")])
        now = datetime.datetime.now(datetime.timezone.utc)
        cert = (
            x509.CertificateBuilder()
            .subject_name(name)
            .issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(key, hashes.SHA256())
        )
        key_pem = key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption())
        # Create the key file private from the start.
        fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(key_pem)
        with open(cert_path, "wb") as f:
            f.write(cert.public_bytes(serialization.Encoding.PEM))
    return cert_path, key_path, fingerprint_of_pem(cert_path)


# --------------------------------------------------------------------------
# Login rate limiting
# --------------------------------------------------------------------------

class AuthGuard:
    """Locks an address out after repeated wrong PINs, plus a global brake."""

    def __init__(self, max_fails: int = 5, lock_seconds: int = 300,
                 global_fails: int = 40, global_window: int = 600):
        self._lock = threading.Lock()
        self._fails: dict = {}      # ip -> [count, locked_until]
        self._recent: list = []     # timestamps of all recent failures
        self.max_fails = max_fails
        self.lock_seconds = lock_seconds
        self.global_fails = global_fails
        self.global_window = global_window
        self._global_until = 0.0

    def locked_for(self, ip: str) -> int:
        now = time.monotonic()
        with self._lock:
            until = max(self._global_until, self._fails.get(ip, [0, 0.0])[1])
            return max(0, int(until - now + 0.999))

    def fail(self, ip: str) -> None:
        now = time.monotonic()
        with self._lock:
            rec = self._fails.setdefault(ip, [0, 0.0])
            rec[0] += 1
            if rec[0] >= self.max_fails:
                rec[0] = 0
                rec[1] = now + self.lock_seconds
            self._recent = [t for t in self._recent if now - t < self.global_window]
            self._recent.append(now)
            if len(self._recent) >= self.global_fails:
                self._global_until = now + self.lock_seconds
                self._recent = []

    def success(self, ip: str) -> None:
        with self._lock:
            self._fails.pop(ip, None)


# --------------------------------------------------------------------------
# Input / screen backends
# --------------------------------------------------------------------------

def _load_screen_libs():
    try:
        import mss  # noqa: F401
        from PIL import Image, ImageDraw  # noqa: F401
        return True
    except ImportError:
        return False


SCREEN_AVAILABLE = _load_screen_libs()


def _resample():
    from PIL import Image
    return getattr(getattr(Image, "Resampling", Image), "BILINEAR")


def _draw_cursor(img, fx: float, fy: float) -> None:
    """Draw a small arrow at the (fractional) pointer position."""
    from PIL import ImageDraw
    if not (0.0 <= fx <= 1.0 and 0.0 <= fy <= 1.0):
        return
    x, y = fx * img.width, fy * img.height
    s = max(10.0, img.width / 90.0)
    pts = [(x, y), (x, y + s * 1.5), (x + s * 0.38, y + s * 1.15),
           (x + s * 0.7, y + s * 1.7), (x + s * 0.95, y + s * 1.55),
           (x + s * 0.62, y + s * 1.05), (x + s * 1.05, y + s * 1.05)]
    d = ImageDraw.Draw(img)
    d.polygon(pts, fill=(255, 255, 255), outline=(0, 0, 0))


def _encode_if_changed(img, quality: int, last_digest):
    digest = zlib.crc32(img.tobytes())
    if digest == last_digest:
        return None, digest
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=quality)
    return buf.getvalue(), digest


class MssGrabber:
    """Screen capture of the primary monitor. Must be used from one thread."""

    def __init__(self, pointer_fn):
        import mss
        self._sct = mss.mss()
        self._mon = self._sct.monitors[1]
        self._pointer_fn = pointer_fn

    def frame(self, max_width: int, quality: int, last_digest):
        from PIL import Image
        shot = self._sct.grab(self._mon)
        img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
        if img.width > max_width:
            h = max(1, round(img.height * max_width / img.width))
            img = img.resize((max_width, h), _resample())
        try:
            px, py = self._pointer_fn()
            _draw_cursor(img, (px - self._mon["left"]) / self._mon["width"],
                         (py - self._mon["top"]) / self._mon["height"])
        except Exception:
            pass
        return _encode_if_changed(img, quality, last_digest)

    def close(self):
        try:
            self._sct.close()
        except Exception:
            pass


class PynputBackend:
    """Real mouse / keyboard control (Windows, macOS, Linux/X11)."""

    def __init__(self):
        from pynput import keyboard, mouse
        self._kb_mod = keyboard
        self._mouse_mod = mouse
        self._mouse = mouse.Controller()
        self._kb = keyboard.Controller()
        self._os = os_name()
        self._lock = threading.Lock()
        self._geom = None
        self._geom_at = 0.0
        self.has_screen = SCREEN_AVAILABLE

    # -- helpers
    def _button(self, name):
        return {"left": self._mouse_mod.Button.left,
                "right": self._mouse_mod.Button.right,
                "middle": self._mouse_mod.Button.middle}[name]

    def _key(self, name):
        if len(name) == 1:
            return name
        K = self._kb_mod.Key
        # "win" = the Windows key / Super / Command, on its own (Start menu etc.)
        key = getattr(K, "cmd" if name == "win" else name, None)
        if key is None:
            raise ValueError("key not supported on this system: " + name)
        return key

    def _mod_key(self, name):
        K = self._kb_mod.Key
        if name == "ctrl":
            return K.ctrl
        if name == "alt":
            return K.alt
        if name == "shift":
            return K.shift
        if name == "win":
            return K.cmd
        if name == "primary":
            return K.cmd if self._os == "mac" else K.ctrl
        if name == "switch":
            return K.cmd if self._os == "mac" else K.alt
        raise ValueError(name)

    def geometry(self):
        now = time.monotonic()
        if self._geom is None or now - self._geom_at > 5:
            try:
                import mss
                with mss.mss() as sct:
                    m = sct.monitors[1]
                    self._geom = (m["left"], m["top"], m["width"], m["height"])
            except Exception:
                if self._geom is None:
                    self._geom = (0, 0, 1920, 1080)
            self._geom_at = now
        return self._geom

    def pointer(self):
        return self._mouse.position

    # -- actions
    def move_rel(self, dx, dy):
        with self._lock:
            self._mouse.move(dx, dy)

    def move_abs(self, fx, fy):
        left, top, w, h = self.geometry()
        with self._lock:
            self._mouse.position = (int(left + fx * (w - 1)), int(top + fy * (h - 1)))

    def button(self, name, down):
        with self._lock:
            if down:
                self._mouse.press(self._button(name))
            else:
                self._mouse.release(self._button(name))

    def click(self, name, count):
        with self._lock:
            self._mouse.click(self._button(name), count)

    def scroll(self, dx, dy):
        # protocol: +dy = scroll down, +dx = scroll right. pynput: +dy = up.
        with self._lock:
            self._mouse.scroll(dx, -dy)

    def type_text(self, text):
        with self._lock:
            for ch in text:
                try:
                    self._kb.type(ch)
                except Exception:
                    # Character this keyboard layout / OS cannot produce.
                    continue

    def key(self, name, mods):
        mod_keys = []
        for m in mods:
            k = self._mod_key(m)
            if k not in mod_keys:
                mod_keys.append(k)
        target = self._key(name)
        with self._lock:
            pressed = []
            try:
                for k in mod_keys:
                    self._kb.press(k)
                    pressed.append(k)
                self._kb.press(target)
                self._kb.release(target)
            finally:
                for k in reversed(pressed):
                    self._kb.release(k)

    def media(self, action):
        K = self._kb_mod.Key
        key = {"play_pause": K.media_play_pause, "next": K.media_next,
               "prev": K.media_previous, "vol_up": K.media_volume_up,
               "vol_down": K.media_volume_down, "mute": K.media_volume_mute}[action]
        with self._lock:
            self._kb.press(key)
            self._kb.release(key)

    def make_grabber(self):
        return MssGrabber(self.pointer)


class DryRunBackend:
    """Does nothing but record events. Used by --dry-run and the tests."""

    def __init__(self, verbose: bool = False):
        self.events: list = []
        self.verbose = verbose
        self.has_screen = SCREEN_AVAILABLE
        self._lock = threading.Lock()

    def _rec(self, *event):
        with self._lock:
            self.events.append(event)
        if self.verbose:
            log("dry-run: " + self._describe(event))

    @staticmethod
    def _describe(event) -> str:
        """Log line for an event. Typed text and single-character keys are never shown."""
        kind = event[0]
        if kind == "type_text":
            return "type_text (%d chars)" % len(event[1])
        if kind == "key":
            name = "<char>" if len(event[1]) == 1 else event[1]
            return "key %s %s" % (name, ",".join(event[2]) or "-")
        return " ".join(str(x) for x in event)

    def move_rel(self, dx, dy): self._rec("move_rel", dx, dy)
    def move_abs(self, fx, fy): self._rec("move_abs", fx, fy)
    def button(self, name, down): self._rec("button", name, down)
    def click(self, name, count): self._rec("click", name, count)
    def scroll(self, dx, dy): self._rec("scroll", dx, dy)
    def type_text(self, text): self._rec("type_text", text)
    def key(self, name, mods): self._rec("key", name, tuple(mods))
    def media(self, action): self._rec("media", action)
    def power(self, action):
        self._rec("power", action)      # dry-run never really locks/sleeps/shuts down
        return True

    def geometry(self): return (0, 0, 1920, 1080)

    def make_grabber(self):
        return _SyntheticGrabber()


class _SyntheticGrabber:
    """Test pattern that changes once per second (dry-run screen stream)."""

    def frame(self, max_width, quality, last_digest):
        from PIL import Image, ImageDraw
        w = min(max_width, 640)
        h = w * 9 // 16
        t = int(time.time())
        img = Image.new("RGB", (w, h), ((t * 37) % 256, 80, 140))
        ImageDraw.Draw(img).text((10, 10), "dry-run %d" % t, fill=(255, 255, 255))
        return _encode_if_changed(img, quality, last_digest)

    def close(self):
        pass


# --------------------------------------------------------------------------
# Power actions
# --------------------------------------------------------------------------

POWER_COMMANDS = {
    "windows": {
        "lock": ["rundll32.exe user32.dll,LockWorkStation"],
        "sleep": ["rundll32.exe powrprof.dll,SetSuspendState 0,1,0"],
        "shutdown": ["shutdown /s /t 0"],
        "restart": ["shutdown /r /t 0"],
    },
    "mac": {
        "lock": [["pmset", "displaysleepnow"]],
        "sleep": [["pmset", "sleepnow"]],
        "shutdown": [["osascript", "-e", 'tell application "System Events" to shut down']],
        "restart": [["osascript", "-e", 'tell application "System Events" to restart']],
    },
    "linux": {
        "lock": [["loginctl", "lock-session"], ["xdg-screensaver", "lock"]],
        "sleep": [["systemctl", "suspend"]],
        "shutdown": [["systemctl", "poweroff"]],
        "restart": [["systemctl", "reboot"]],
    },
}


def run_power_action(action: str) -> bool:
    for cmd in POWER_COMMANDS[os_name()][action]:
        try:
            subprocess.Popen(cmd, shell=isinstance(cmd, str),
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return True
        except OSError:
            continue
    return False


# --------------------------------------------------------------------------
# Wire protocol:  [uint32 BE length][uint8 type][payload]   (length = 1 + len(payload))
# --------------------------------------------------------------------------

class ProtocolError(Exception):
    pass


class FrameReader:
    """Incremental frame parser (tolerates read timeouts mid-frame)."""

    def __init__(self, sock):
        self.sock = sock
        self.buf = bytearray()

    def read(self, max_len: int):
        """Returns (type, payload) or None if no complete frame arrived in time."""
        while True:
            if len(self.buf) >= 4:
                (length,) = struct.unpack_from(">I", self.buf, 0)
                if length < 1 or length > max_len:
                    raise ProtocolError("bad frame length")
                if len(self.buf) >= 4 + length:
                    ftype = self.buf[4]
                    payload = bytes(self.buf[5:4 + length])
                    del self.buf[:4 + length]
                    return ftype, payload
            try:
                chunk = self.sock.recv(65536)
            except (socket.timeout, ssl.SSLWantReadError):
                return None
            if not chunk:
                raise ConnectionError("closed by peer")
            self.buf += chunk


class Conn:
    def __init__(self, sock, addr):
        self.sock = sock
        self.addr = addr
        self.reader = FrameReader(sock)
        self.last_rx = time.monotonic()
        self.streamer = None
        self.screen_error_sent = False
        self.read_timeout = HANDSHAKE_TIMEOUT

    def _send(self, ftype: int, payload: bytes) -> None:
        data = struct.pack(">IB", len(payload) + 1, ftype) + payload
        self.sock.settimeout(10)          # writes may take a while; reads poll quickly
        try:
            self.sock.sendall(data)
        finally:
            self.sock.settimeout(self.read_timeout)

    def send_json(self, obj: dict) -> None:
        self._send(FRAME_JSON, json.dumps(obj, separators=(",", ":")).encode("utf-8"))

    def send_frame(self, jpeg: bytes) -> None:
        self._send(FRAME_JPEG, jpeg)

    def close(self) -> None:
        if self.streamer:
            self.streamer.stop.set()
        try:
            self.sock.close()
        except OSError:
            pass


class ScreenStreamer(threading.Thread):
    """Captures + encodes frames; the connection thread does all socket I/O."""

    def __init__(self, backend, opts: dict):
        super().__init__(daemon=True)
        self.backend = backend
        self.opts = dict(opts)
        self.stop = threading.Event()
        self._slot = None
        self._slot_lock = threading.Lock()
        self._consumed = threading.Event()
        self._consumed.set()
        self.error = None          # set if capture fails; the connection thread tells the phone

    def set_opts(self, opts: dict) -> None:
        self.opts = dict(opts)

    def take(self):
        with self._slot_lock:
            jpeg, self._slot = self._slot, None
        if jpeg is not None:
            self._consumed.set()
        return jpeg

    def run(self):
        grabber = None
        last = None
        try:
            grabber = self.backend.make_grabber()
            while not self.stop.is_set():
                t0 = time.monotonic()
                o = self.opts
                if self._consumed.wait(0.25):
                    jpeg, digest = grabber.frame(o["w"], o["q"], last)
                    if jpeg is not None:
                        last = digest
                        with self._slot_lock:
                            self._slot = jpeg
                        self._consumed.clear()
                self.stop.wait(max(0.0, 1.0 / o["fps"] - (time.monotonic() - t0)))
        except Exception as e:  # capture problems must not take the server down
            log("screen capture stopped: %s: %s" % (type(e).__name__, e))
            self.error = type(e).__name__
        finally:
            if grabber:
                grabber.close()


# --------------------------------------------------------------------------
# Server
# --------------------------------------------------------------------------

def _clamp_int(v, lo, hi) -> int:
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise ValueError("number expected")
    return int(max(lo, min(hi, round(v))))


def _clamp_float(v, lo, hi) -> float:
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise ValueError("number expected")
    return float(max(lo, min(hi, v)))


class Server:
    def __init__(self, backend, pin: str, ssl_ctx: ssl.SSLContext, *,
                 allow_power: bool = True, allow_screen: bool = True,
                 hostname: str | None = None, port: int = DEFAULT_PORT,
                 guard: AuthGuard | None = None, fail_delay: float = 1.0):
        self.backend = backend
        self.pin = pin
        self.ctx = ssl_ctx
        self.allow_power = allow_power
        self.allow_screen = allow_screen and bool(getattr(backend, "has_screen", False))
        self.hostname = hostname or socket.gethostname()
        self.port = port
        self.guard = guard or AuthGuard()
        self.fail_delay = fail_delay
        self._slots = threading.BoundedSemaphore(MAX_CONNECTIONS)
        self._listen = None
        self._stop = threading.Event()

    # -- lifecycle
    def bind(self, host: str = "0.0.0.0") -> int:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((host, self.port))
        s.listen(16)
        s.settimeout(0.5)
        self._listen = s
        self.port = s.getsockname()[1]
        return self.port

    def serve_forever(self) -> None:
        while not self._stop.is_set():
            try:
                raw, addr = self._listen.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            if not self._slots.acquire(blocking=False):
                raw.close()
                continue
            threading.Thread(target=self._handle, args=(raw, addr), daemon=True).start()

    def shutdown(self) -> None:
        self._stop.set()
        if self._listen:
            try:
                self._listen.close()
            except OSError:
                pass

    # -- connection handling
    def _handle(self, raw, addr) -> None:
        ip = addr[0]
        conn = None
        try:
            raw.settimeout(HANDSHAKE_TIMEOUT)
            raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            raw.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            tls = self.ctx.wrap_socket(raw, server_side=True)
            conn = Conn(tls, addr)
            if not self._login(conn):
                return
            conn.read_timeout = 0.05
            tls.settimeout(conn.read_timeout)
            self._session(conn)
        except (OSError, ssl.SSLError, ProtocolError, ConnectionError):
            pass
        except Exception as e:  # keep one bad client from affecting others
            log("connection error from %s: %s" % (ip, type(e).__name__))
        finally:
            if conn:
                conn.close()
            else:
                try:
                    raw.close()
                except OSError:
                    pass
            self._slots.release()

    def _login(self, conn: Conn) -> bool:
        ip = conn.addr[0]
        wait = self.guard.locked_for(ip)
        if wait:
            conn.send_json({"t": "error", "code": "locked", "retry": wait,
                            "msg": "Too many wrong PINs. Try again in %d s." % wait})
            return False
        conn.sock.settimeout(HANDSHAKE_TIMEOUT)
        deadline = time.monotonic() + HANDSHAKE_TIMEOUT
        frame = None
        while frame is None:
            if time.monotonic() > deadline:
                return False
            frame = conn.reader.read(MAX_PREAUTH_FRAME)
        ftype, payload = frame
        try:
            msg = json.loads(payload.decode("utf-8"))
            ok_shape = ftype == FRAME_JSON and isinstance(msg, dict) and msg.get("t") == "hello"
        except ValueError:
            ok_shape = False
        if not ok_shape:
            return False
        if msg.get("v") != PROTOCOL_VERSION:
            conn.send_json({"t": "error", "code": "version",
                            "msg": "App and server versions do not match. Update both."})
            return False
        supplied = normalize_pin(msg.get("pin", "")).encode()
        if not hmac.compare_digest(supplied, self.pin.encode()):
            self.guard.fail(ip)
            log("wrong PIN from %s" % ip)
            time.sleep(self.fail_delay)
            conn.send_json({"t": "error", "code": "auth", "msg": "Wrong PIN."})
            return False
        self.guard.success(ip)
        who = str(msg.get("name", ""))[:40]
        log("connected: %s%s" % (ip, (" (%s)" % who) if who else ""))
        geom = self.backend.geometry()
        conn.send_json({
            "t": "welcome", "v": PROTOCOL_VERSION, "host": self.hostname, "os": os_name(),
            "screen": self.allow_screen, "power": self.allow_power,
            "geom": [geom[2], geom[3]],
        })
        return True

    def _session(self, conn: Conn) -> None:
        while not self._stop.is_set():
            frame = conn.reader.read(MAX_FRAME)
            now = time.monotonic()
            if frame is not None:
                conn.last_rx = now
                ftype, payload = frame
                if ftype == FRAME_JSON:
                    try:
                        msg = json.loads(payload.decode("utf-8"))
                    except ValueError:
                        continue
                    if isinstance(msg, dict) and not self._dispatch(conn, msg):
                        return
            elif now - conn.last_rx > IDLE_TIMEOUT:
                return
            if conn.streamer is not None:
                jpeg = conn.streamer.take()
                if jpeg is not None:
                    conn.send_frame(jpeg)
                elif conn.streamer.error and not conn.screen_error_sent:
                    conn.screen_error_sent = True
                    hint = (" On Linux, screen view needs an X11 session (not Wayland)."
                            if os_name() == "linux" else "")
                    conn.send_json({"t": "error", "code": "screen",
                                    "msg": "The laptop could not capture its screen (%s).%s"
                                           % (conn.streamer.error, hint)})

    # -- message handling. Returns False to end the session.
    def _dispatch(self, conn: Conn, msg: dict) -> bool:
        t = msg.get("t")
        b = self.backend
        try:
            if t == "move":
                b.move_rel(_clamp_int(msg["dx"], -MAX_REL_MOVE, MAX_REL_MOVE),
                           _clamp_int(msg["dy"], -MAX_REL_MOVE, MAX_REL_MOVE))
            elif t == "moveto":
                b.move_abs(_clamp_float(msg["x"], 0.0, 1.0), _clamp_float(msg["y"], 0.0, 1.0))
            elif t == "click":
                btn = msg.get("b", "left")
                if btn not in BUTTONS:
                    raise ValueError("button")
                b.click(btn, _clamp_int(msg.get("n", 1), 1, 3))
            elif t in ("down", "up"):
                btn = msg.get("b", "left")
                if btn not in BUTTONS:
                    raise ValueError("button")
                b.button(btn, t == "down")
            elif t == "scroll":
                b.scroll(_clamp_int(msg.get("dx", 0), -MAX_SCROLL, MAX_SCROLL),
                         _clamp_int(msg.get("dy", 0), -MAX_SCROLL, MAX_SCROLL))
            elif t == "text":
                s = msg["s"]
                if not isinstance(s, str):
                    raise ValueError("text")
                b.type_text(s[:MAX_TEXT_LEN])
            elif t == "key":
                k = msg["k"]
                mods = msg.get("mods", [])
                if not isinstance(k, str) or not (k in NAMED_KEYS or len(k) == 1):
                    raise ValueError("key")
                if not isinstance(mods, list) or any(m not in MODIFIERS for m in mods):
                    raise ValueError("mods")
                b.key(k, mods)
            elif t == "media":
                if msg.get("a") not in MEDIA_ACTIONS:
                    raise ValueError("media")
                b.media(msg["a"])
            elif t == "power":
                action = msg.get("a")
                if action not in POWER_ACTIONS:
                    raise ValueError("power")
                if not self.allow_power:
                    conn.send_json({"t": "error", "code": "power",
                                    "msg": "Power actions are disabled on this laptop."})
                else:
                    log("power action requested: %s" % action)
                    ok = self._power(action)
                    conn.send_json({"t": "ok", "for": "power", "a": action, "done": ok})
            elif t == "screen":
                self._screen(conn, msg)
            elif t == "ping":
                conn.send_json({"t": "pong", "id": msg.get("id") if isinstance(msg.get("id"), int) else 0})
            elif t == "bye":
                return False
        except (KeyError, ValueError, TypeError):
            pass  # malformed message: ignore
        except (OSError, ssl.SSLError):
            raise
        except Exception as e:
            # e.g. missing OS permission for input control. Tell the app, once per kind.
            log("input error (%s): %s" % (t, type(e).__name__))
            conn.send_json({"t": "error", "code": "input",
                            "msg": "The laptop refused the action (%s). Check OS permissions." % type(e).__name__})
        return True

    def _power(self, action: str) -> bool:
        # A backend may take over power handling (the dry-run backend does, so that
        # --dry-run can never shut anything down). Tests also override this method.
        handler = getattr(self.backend, "power", None)
        if handler is not None:
            return bool(handler(action))
        return run_power_action(action)

    def _screen(self, conn: Conn, msg: dict) -> None:
        if not msg.get("on", False):
            if conn.streamer:
                conn.streamer.stop.set()
                conn.streamer = None
            return
        if not self.allow_screen:
            conn.send_json({"t": "error", "code": "screen",
                            "msg": "Screen view is not available on this laptop "
                                   "(install the 'mss' and 'Pillow' packages)."})
            return
        opts = {"w": _clamp_int(msg.get("w", 1280), 320, 3840),
                "q": _clamp_int(msg.get("q", 55), 20, 90),
                "fps": _clamp_int(msg.get("fps", 6), 1, 15)}
        if conn.streamer and conn.streamer.is_alive():
            conn.streamer.set_opts(opts)
        else:
            conn.screen_error_sent = False
            conn.streamer = ScreenStreamer(self.backend, opts)
            conn.streamer.start()


# --------------------------------------------------------------------------
# LAN discovery (UDP): lets the app find the laptop without typing an address.
# --------------------------------------------------------------------------

def run_discovery(server: Server, stop: threading.Event) -> None:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("", DISCOVERY_PORT))
        s.settimeout(1.0)
    except OSError as e:
        log("LAN discovery disabled (%s). You can still enter the address by hand." % e)
        return
    window_start, sent = time.monotonic(), 0
    while not stop.is_set():
        try:
            data, addr = s.recvfrom(256)
        except socket.timeout:
            continue
        except OSError:
            break
        if data.strip() != DISCOVERY_MAGIC:
            continue
        now = time.monotonic()
        if now - window_start > 1.0:
            window_start, sent = now, 0
        if sent >= 10:      # at most 10 replies per second
            continue
        sent += 1
        reply = json.dumps({"app": "laptop-remote", "v": PROTOCOL_VERSION,
                            "name": server.hostname, "port": server.port,
                            "os": os_name()}).encode()
        try:
            s.sendto(reply, addr)
        except OSError:
            pass
    s.close()


def local_addresses() -> list:
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))     # no packet is sent; picks the LAN interface
        ips.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    return [ip for ip in ips if not ip.startswith("127.")]


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------

def make_ssl_context(cert_path: str, key_path: str) -> ssl.SSLContext:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(cert_path, key_path)
    return ctx


def enable_windows_dpi_awareness() -> None:
    if os_name() != "windows":
        return
    try:
        import ctypes
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except Exception:
            ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Laptop Remote server")
    p.add_argument("--port", type=int, default=DEFAULT_PORT, help="TCP port (default %d)" % DEFAULT_PORT)
    p.add_argument("--host", default="0.0.0.0", help="address to listen on (default: all interfaces)")
    p.add_argument("--new-pin", action="store_true", help="generate a new PIN")
    p.add_argument("--new-cert", action="store_true", help="generate a new certificate (the app will ask you to re-confirm it)")
    p.add_argument("--no-power", action="store_true", help="disable lock/sleep/shutdown/restart from the app")
    p.add_argument("--no-screen", action="store_true", help="disable screen viewing")
    p.add_argument("--no-discovery", action="store_true", help="do not answer LAN discovery broadcasts")
    p.add_argument("--dry-run", action="store_true", help="do not control this computer; just log (for testing)")
    p.add_argument("--config-dir", help="where to keep the PIN and certificate")
    args = p.parse_args(argv)

    if sys.version_info < (3, 8):
        sys.exit("Python 3.8 or newer is required.")

    cfg = config_dir(args.config_dir)
    pin = load_or_create_pin(cfg, args.new_pin)
    cert_path, key_path, fp = load_or_create_cert(cfg, args.new_cert)

    if args.dry_run:
        backend = DryRunBackend(verbose=True)
    else:
        enable_windows_dpi_awareness()
        if os_name() == "linux" and not (os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")):
            sys.exit("No graphical session found (DISPLAY is not set). "
                     "Run this from a terminal inside your desktop session.")
        if os_name() == "linux" and os.environ.get("XDG_SESSION_TYPE") == "wayland":
            log("WARNING: you are on Wayland. Mouse/keyboard control and screen view usually only "
                "work on an X11 session (choose 'Xorg' on the login screen).")
        try:
            backend = PynputBackend()
        except Exception as e:
            sys.exit("Could not start input control: %s\nDid you run 'pip install -r requirements.txt'?" % e)
        if os_name() == "mac":
            log("macOS: allow Terminal/Python under System Settings > Privacy & Security > "
                "Accessibility and Screen Recording, or control will silently do nothing.")

    server = Server(backend, pin, make_ssl_context(cert_path, key_path),
                    allow_power=not args.no_power, allow_screen=not args.no_screen, port=args.port)
    try:
        server.bind(args.host)
    except OSError as e:
        sys.exit("Could not listen on port %d: %s" % (args.port, e))

    stop = threading.Event()
    if not args.no_discovery:
        threading.Thread(target=run_discovery, args=(server, stop), daemon=True).start()

    addrs = local_addresses() or ["<this computer's IP address>"]
    bar = "=" * 64
    print(bar)
    print("  Laptop Remote server is running" + ("   [DRY RUN - controls nothing]" if args.dry_run else ""))
    print("")
    print("  In the app, connect to:")
    print("     Address : " + ("   or   ".join(addrs)))
    print("     Port    : %d" % server.port)
    print("     PIN     : %s-%s" % (pin[:4], pin[4:]))
    print("")
    print("  First connection: the app shows a certificate fingerprint.")
    print("  Only tap Trust if it matches this one:")
    print("     Fingerprint : " + format_fingerprint(fp))
    print("")
    print("  Screen view : " + ("on" if server.allow_screen else "off (install mss + Pillow)"))
    print("  Power menu  : " + ("on" if server.allow_power else "off"))
    print("  Press Ctrl+C to stop.")
    print(bar, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping.")
    finally:
        stop.set()
        server.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
