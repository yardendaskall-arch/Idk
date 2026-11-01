"""Integration tests for the laptop server (run: python -m unittest discover tests)."""
import json
import os
import socket
import ssl
import struct
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
import laptop_remote_server as srv  # noqa: E402

PIN = "12345678"


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return buf


class Client:
    def __init__(self, port, host="127.0.0.1"):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        raw = socket.create_connection((host, port), timeout=5)
        raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.sock = ctx.wrap_socket(raw)
        self.sock.settimeout(5)
        self.buf = bytearray()
        der = self.sock.getpeercert(binary_form=True)
        import hashlib
        self.fingerprint = hashlib.sha256(der).hexdigest()

    def send_raw(self, ftype, payload):
        self.sock.sendall(struct.pack(">IB", len(payload) + 1, ftype) + payload)

    def send(self, obj):
        self.send_raw(srv.FRAME_JSON, json.dumps(obj).encode())

    def _fill(self, n):
        # Resumable: a read timeout keeps what has already arrived.
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("closed")
            self.buf += chunk

    def recv(self):
        self._fill(4)
        (length,) = struct.unpack(">I", bytes(self.buf[:4]))
        self._fill(4 + length)
        body = bytes(self.buf[4:4 + length])
        del self.buf[:4 + length]
        ftype, payload = body[0], body[1:]
        if ftype == srv.FRAME_JSON:
            return ftype, json.loads(payload)
        return ftype, payload

    def login(self, pin=PIN):
        self.send({"t": "hello", "v": 1, "pin": pin, "name": "test"})
        return self.recv()[1]

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


class ServerTestCase(unittest.TestCase):
    allow_power = True

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cert, key, cls.fp = srv.load_or_create_cert(cls.tmp.name)
        cls.ctx = srv.make_ssl_context(cert, key)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        self.backend = srv.DryRunBackend()
        self.server = srv.Server(self.backend, PIN, self.ctx, port=0,
                                 allow_power=self.allow_power, fail_delay=0.0,
                                 guard=srv.AuthGuard(max_fails=3, lock_seconds=60))
        self.power_calls = []
        self.server._power = lambda action: self.power_calls.append(action) or True
        self.port = self.server.bind("127.0.0.1")
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.clients = []

    def tearDown(self):
        for c in self.clients:
            c.close()
        self.server.shutdown()

    def client(self):
        c = Client(self.port)
        self.clients.append(c)
        return c

    def logged_in(self):
        c = self.client()
        w = c.login()
        self.assertEqual(w["t"], "welcome", w)
        return c

    def wait_events(self, n, timeout=3.0):
        end = time.time() + timeout
        while time.time() < end:
            if len(self.backend.events) >= n:
                return list(self.backend.events)
            time.sleep(0.01)
        self.fail("expected %d events, got %r" % (n, self.backend.events))

    def roundtrip(self, c):
        """Ping/pong barrier: everything sent before it has been dispatched."""
        c.send({"t": "ping", "id": 7})
        ftype, msg = c.recv()
        self.assertEqual((ftype, msg["t"], msg["id"]), (srv.FRAME_JSON, "pong", 7))


class TestAuth(ServerTestCase):
    def test_fingerprint_matches_cert(self):
        self.assertEqual(self.client().fingerprint, self.fp)

    def test_good_pin_and_welcome_fields(self):
        w = self.client().login()
        self.assertEqual(w["t"], "welcome")
        self.assertEqual(w["v"], 1)
        self.assertIn(w["os"], ("windows", "mac", "linux"))
        self.assertTrue(w["power"])
        self.assertEqual(w["geom"], [1920, 1080])

    def test_pin_with_dash_and_spaces_accepted(self):
        self.assertEqual(self.client().login("1234-5678")["t"], "welcome")

    def test_wrong_pin_rejected_then_closed(self):
        c = self.client()
        r = c.login("00000000")
        self.assertEqual((r["t"], r["code"]), ("error", "auth"))
        with self.assertRaises((ConnectionError, ssl.SSLError, OSError)):
            c.recv()

    def test_lockout_after_repeated_failures(self):
        for _ in range(3):
            self.assertEqual(self.client().login("99999999")["code"], "auth")
        r = self.client().login(PIN)      # even the right PIN is refused while locked
        self.assertEqual((r["t"], r["code"]), ("error", "locked"))
        self.assertGreater(r["retry"], 0)

    def test_success_resets_failure_count(self):
        for _ in range(2):
            self.client().login("99999999")
        self.assertEqual(self.client().login(PIN)["t"], "welcome")
        for _ in range(2):
            self.client().login("99999999")
        self.assertEqual(self.client().login(PIN)["t"], "welcome")

    def test_wrong_protocol_version(self):
        c = self.client()
        c.send({"t": "hello", "v": 99, "pin": PIN})
        self.assertEqual(c.recv()[1]["code"], "version")

    def test_commands_before_login_are_not_executed(self):
        c = self.client()
        c.send({"t": "move", "dx": 5, "dy": 5})
        c.send({"t": "text", "s": "hello"})
        time.sleep(0.3)
        self.assertEqual(self.backend.events, [])

    def test_oversized_preauth_frame_dropped(self):
        c = self.client()
        c.send_raw(srv.FRAME_JSON, b"x" * 10000)
        with self.assertRaises((ConnectionError, ssl.SSLError, OSError)):
            c.recv()

    def test_garbage_preauth_dropped(self):
        c = self.client()
        c.sock.sendall(b"GET / HTTP/1.1\r\n\r\n")
        with self.assertRaises((ConnectionError, ssl.SSLError, OSError)):
            c.recv()

    def test_plain_tcp_does_not_crash_server(self):
        s = socket.create_connection(("127.0.0.1", self.port), timeout=2)
        s.sendall(b"not tls at all")
        s.close()
        self.assertEqual(self.client().login()["t"], "welcome")


class TestCommands(ServerTestCase):
    def test_mouse_commands(self):
        c = self.logged_in()
        c.send({"t": "move", "dx": 10, "dy": -4})
        c.send({"t": "moveto", "x": 0.25, "y": 0.75})
        c.send({"t": "click", "b": "right"})
        c.send({"t": "click", "b": "left", "n": 2})
        c.send({"t": "down", "b": "left"})
        c.send({"t": "up", "b": "left"})
        c.send({"t": "scroll", "dx": 0, "dy": 3})
        self.roundtrip(c)
        self.assertEqual(self.backend.events, [
            ("move_rel", 10, -4), ("move_abs", 0.25, 0.75), ("click", "right", 1),
            ("click", "left", 2), ("button", "left", True), ("button", "left", False),
            ("scroll", 0, 3)])

    def test_values_are_clamped(self):
        c = self.logged_in()
        c.send({"t": "move", "dx": 10 ** 9, "dy": -10 ** 9})
        c.send({"t": "moveto", "x": 5, "y": -5})
        c.send({"t": "scroll", "dy": 10 ** 6})
        c.send({"t": "click", "n": 99})
        self.roundtrip(c)
        self.assertEqual(self.backend.events, [
            ("move_rel", 3000, -3000), ("move_abs", 1.0, 0.0), ("scroll", 0, 100),
            ("click", "left", 3)])

    def test_text_and_unicode(self):
        c = self.logged_in()
        c.send({"t": "text", "s": "héllo wörld ✓ 日本"})
        c.send({"t": "text", "s": "x" * 5000})
        self.roundtrip(c)
        self.assertEqual(self.backend.events[0], ("type_text", "héllo wörld ✓ 日本"))
        self.assertEqual(len(self.backend.events[1][1]), srv.MAX_TEXT_LEN)

    def test_keys_and_modifiers(self):
        c = self.logged_in()
        c.send({"t": "key", "k": "enter"})
        c.send({"t": "key", "k": "c", "mods": ["primary"]})
        c.send({"t": "key", "k": "tab", "mods": ["switch", "shift"]})
        c.send({"t": "key", "k": "f5"})
        self.roundtrip(c)
        self.assertEqual(self.backend.events, [
            ("key", "enter", ()), ("key", "c", ("primary",)),
            ("key", "tab", ("switch", "shift")), ("key", "f5", ())])

    def test_invalid_input_is_ignored_and_session_survives(self):
        c = self.logged_in()
        bad = [
            {"t": "key", "k": "rm -rf"}, {"t": "key", "k": "enter", "mods": ["root"]},
            {"t": "key", "k": "enter", "mods": "ctrl"}, {"t": "key"},
            {"t": "move", "dx": "a", "dy": 1}, {"t": "move"}, {"t": "moveto", "x": None, "y": 1},
            {"t": "click", "b": "side"}, {"t": "media", "a": "format_disk"},
            {"t": "text", "s": 5}, {"t": "power", "a": "explode"}, {"t": "nonsense"},
            {"t": "move", "dx": True, "dy": 1}, {"t": None}, {},
        ]
        for m in bad:
            c.send(m)
        c.send_raw(srv.FRAME_JSON, b"{not json")
        c.send_raw(srv.FRAME_JSON, b"[1,2,3]")
        c.send_raw(srv.FRAME_JPEG, b"binary from client is ignored")
        self.roundtrip(c)
        self.assertEqual(self.backend.events, [])
        self.assertEqual(self.power_calls, [])

    def test_media(self):
        c = self.logged_in()
        for a in srv.MEDIA_ACTIONS:
            c.send({"t": "media", "a": a})
        self.roundtrip(c)
        self.assertEqual([e[1] for e in self.backend.events], list(srv.MEDIA_ACTIONS))

    def test_power_actions_call_handler(self):
        c = self.logged_in()
        c.send({"t": "power", "a": "lock"})
        r = c.recv()[1]
        self.assertEqual((r["t"], r["a"], r["done"]), ("ok", "lock", True))
        self.assertEqual(self.power_calls, ["lock"])

    def test_oversized_frame_after_login_closes_session(self):
        c = self.logged_in()
        with self.assertRaises((ConnectionError, ssl.SSLError, OSError)):
            # The server hangs up as soon as it reads the bad length, which may
            # surface as a reset while we are still sending.
            c.send_raw(srv.FRAME_JSON, b" " * (srv.MAX_FRAME + 10))
            for _ in range(5):
                c.recv()

    def test_bye_closes(self):
        c = self.logged_in()
        c.send({"t": "bye"})
        with self.assertRaises((ConnectionError, ssl.SSLError, OSError)):
            c.recv()

    def test_two_clients_at_once(self):
        a, b = self.logged_in(), self.logged_in()
        a.send({"t": "move", "dx": 1, "dy": 0})
        b.send({"t": "move", "dx": 2, "dy": 0})
        self.roundtrip(a)
        self.roundtrip(b)
        self.assertEqual(sorted(e[1] for e in self.backend.events), [1, 2])


class TestDryRunPowerIsSafe(unittest.TestCase):
    def test_dry_run_backend_never_executes_power_commands(self):
        backend = srv.DryRunBackend()
        server = srv.Server(backend, PIN, ssl_ctx=None, port=0)
        called = []
        original = srv.run_power_action
        srv.run_power_action = lambda a: called.append(a) or True
        try:
            self.assertTrue(server._power("shutdown"))
        finally:
            srv.run_power_action = original
        self.assertEqual(called, [])
        self.assertEqual(backend.events, [("power", "shutdown")])


class TestPowerDisabled(ServerTestCase):
    allow_power = False

    def test_power_refused(self):
        c = self.logged_in()
        c.send({"t": "power", "a": "shutdown"})
        r = c.recv()[1]
        self.assertEqual((r["t"], r["code"]), ("error", "power"))
        self.assertEqual(self.power_calls, [])


@unittest.skipUnless(srv.SCREEN_AVAILABLE, "mss/Pillow not installed")
class TestScreen(ServerTestCase):
    def read_frames(self, c, n, timeout=8):
        frames, end = [], time.time() + timeout
        while len(frames) < n and time.time() < end:
            ftype, payload = c.recv()
            if ftype == srv.FRAME_JPEG:
                frames.append(payload)
        return frames

    def test_stream_start_options_and_stop(self):
        c = self.logged_in()
        c.send({"t": "screen", "on": True, "w": 400, "q": 40, "fps": 10})
        frames = self.read_frames(c, 2)       # test pattern changes every second
        self.assertEqual(len(frames), 2)
        for f in frames:
            self.assertEqual(f[:3], b"\xff\xd8\xff")           # JPEG magic
        from PIL import Image
        import io
        self.assertLessEqual(Image.open(io.BytesIO(frames[0])).width, 400)
        c.send({"t": "screen", "on": False})
        time.sleep(0.5)
        c.sock.settimeout(1.5)
        # Drain anything in flight; then nothing more should arrive.
        try:
            while True:
                c.recv()
        except (socket.timeout, TimeoutError):
            pass
        self.roundtrip_after_drain(c)

    def roundtrip_after_drain(self, c):
        c.sock.settimeout(5)
        c.send({"t": "ping", "id": 7})
        self.assertEqual(c.recv()[1]["id"], 7)

    def test_input_stays_responsive_while_streaming(self):
        c = self.logged_in()
        c.send({"t": "screen", "on": True, "w": 1280, "q": 60, "fps": 15})
        got_frames = []
        stop = threading.Event()

        def reader():
            # Poll with a short timeout so this thread is guaranteed to be gone
            # before the socket is closed (closing a socket another thread is
            # blocked on lets the fd number be reused by the next test's socket).
            c.sock.settimeout(0.2)
            while not stop.is_set():
                try:
                    ftype, payload = c.recv()
                except (socket.timeout, TimeoutError):
                    continue
                except Exception:
                    return
                if ftype == srv.FRAME_JPEG:
                    got_frames.append(len(payload))
        t = threading.Thread(target=reader, daemon=True)
        t.start()
        n = 3000
        t0 = time.time()
        for i in range(n):
            c.send({"t": "move", "dx": 1, "dy": 1})
            if i % 500 == 0:
                time.sleep(0.2)
        deadline = time.time() + 10
        while len(self.backend.events) < n and time.time() < deadline:
            time.sleep(0.05)
        deadline = time.time() + 5
        while not got_frames and time.time() < deadline:
            time.sleep(0.05)
        stop.set()
        t.join(5)
        self.assertFalse(t.is_alive())
        self.assertEqual(len(self.backend.events), n)
        self.assertGreaterEqual(len(got_frames), 1)

    def test_screen_refused_when_disabled(self):
        self.server.allow_screen = False
        c = self.logged_in()
        c.send({"t": "screen", "on": True})
        self.assertEqual(c.recv()[1]["code"], "screen")

    def test_disconnect_stops_streamer(self):
        c = self.logged_in()
        c.send({"t": "screen", "on": True, "fps": 15})
        self.read_frames(c, 1)
        c.close()
        time.sleep(1.0)
        self.assertEqual(threading.active_count() <= 30, True)


@unittest.skipUnless(srv.SCREEN_AVAILABLE, "mss/Pillow not installed")
class TestScreenCaptureFailure(ServerTestCase):
    def test_capture_failure_is_reported_to_the_phone(self):
        class Broken(srv.DryRunBackend):
            def make_grabber(self):
                raise RuntimeError("no display")
        self.server.backend = Broken()
        c = self.logged_in()
        c.send({"t": "screen", "on": True, "fps": 10})
        msg = c.recv()[1]
        self.assertEqual((msg["t"], msg["code"]), ("error", "screen"))
        self.assertIn("could not capture", msg["msg"])
        self.roundtrip(c)                     # session stays usable
        c.send({"t": "move", "dx": 1, "dy": 1})
        self.roundtrip(c)


class TestDiscovery(ServerTestCase):
    def test_discovery_reply(self):
        stop = threading.Event()
        # The real discovery port may be taken on a dev machine; skip if so.
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            probe.bind(("", srv.DISCOVERY_PORT))
        except OSError:
            self.skipTest("discovery port busy")
        probe.close()
        t = threading.Thread(target=srv.run_discovery, args=(self.server, stop), daemon=True)
        t.start()
        time.sleep(0.3)
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(2)
        s.sendto(srv.DISCOVERY_MAGIC, ("127.0.0.1", srv.DISCOVERY_PORT))
        data, _ = s.recvfrom(1024)
        info = json.loads(data)
        self.assertEqual((info["app"], info["port"]), ("laptop-remote", self.port))
        self.assertNotIn("pin", data.decode().lower())
        s.sendto(b"hello?", ("127.0.0.1", srv.DISCOVERY_PORT))
        s.settimeout(0.5)
        with self.assertRaises(socket.timeout):
            s.recvfrom(1024)
        s.close()
        stop.set()


class TestConfig(unittest.TestCase):
    def test_pin_persists_and_regenerates(self):
        with tempfile.TemporaryDirectory() as d:
            p1 = srv.load_or_create_pin(d)
            self.assertRegex(p1, r"^\d{8}$")
            self.assertEqual(srv.load_or_create_pin(d), p1)
            self.assertNotEqual(srv.load_or_create_pin(d, force_new=True), p1)

    def test_cert_persists_and_key_is_private(self):
        with tempfile.TemporaryDirectory() as d:
            _, key, fp1 = srv.load_or_create_cert(d)
            _, _, fp2 = srv.load_or_create_cert(d)
            self.assertEqual(fp1, fp2)
            if os.name == "posix":
                self.assertEqual(os.stat(key).st_mode & 0o077, 0)
            _, _, fp3 = srv.load_or_create_cert(d, force_new=True)
            self.assertNotEqual(fp1, fp3)

    def test_fingerprint_format(self):
        out = srv.format_fingerprint("ab" * 32)
        self.assertEqual(out.replace("\n", " ").split().count("ABAB"), 16)


if __name__ == "__main__":
    unittest.main()
