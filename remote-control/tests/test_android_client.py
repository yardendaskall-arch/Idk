"""
Runs the Android client class (RemoteClient/Discovery - pure Java) on a plain JVM
against the real Python server, then checks what the server received.

Needs a JDK and the org.json jar:
    ORG_JSON_JAR=/path/to/json-20251224.jar python -m unittest tests.test_android_client
(skipped automatically when either is missing)
"""
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
import laptop_remote_server as srv  # noqa: E402

JAR = os.environ.get("ORG_JSON_JAR", "")
JAVAC, JAVA = shutil.which("javac"), shutil.which("java")
PIN = "24681357"


class DroppingServer(srv.Server):
    """Test hook: typing the text "__drop__" makes the server hang up."""

    def _dispatch(self, conn, msg):
        if msg.get("t") == "text" and msg.get("s") == "__drop__":
            return False
        return super()._dispatch(conn, msg)


@unittest.skipUnless(JAVAC and JAVA and os.path.exists(JAR), "needs javac, java and ORG_JSON_JAR")
class TestAndroidClientOnJvm(unittest.TestCase):
    def test_client_against_server(self):
        with tempfile.TemporaryDirectory() as tmp:
            cert, key, fp = srv.load_or_create_cert(tmp)
            backend = srv.DryRunBackend()
            server = DroppingServer(backend, PIN, srv.make_ssl_context(cert, key), port=0,
                                    fail_delay=0.0, guard=srv.AuthGuard(max_fails=100))
            powers = []
            server._power = lambda a: powers.append(a) or True
            port = server.bind("127.0.0.1")
            threading.Thread(target=server.serve_forever, daemon=True).start()
            stop = threading.Event()
            threading.Thread(target=srv.run_discovery, args=(server, stop), daemon=True).start()
            time.sleep(0.3)

            out = os.path.join(tmp, "classes")
            os.makedirs(out)
            sources = [os.path.join(HERE, "..", "android", "src", "app", "laptopremote", n)
                       for n in ("RemoteClient.java", "Discovery.java")]
            sources.append(os.path.join(HERE, "JvmClientTest.java"))
            subprocess.run([JAVAC, "--release", "11", "-cp", JAR, "-d", out] + sources,
                           check=True, capture_output=True)
            proc = subprocess.run(
                [JAVA, "-cp", out + os.pathsep + JAR, "JvmClientTest", "127.0.0.1", str(port), PIN,
                 str(srv.DISCOVERY_PORT)],
                capture_output=True, text=True, timeout=90)
            stop.set()
            print(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            self.assertIn("ALL JVM CHECKS PASSED", proc.stdout)

            # The fingerprint the Java side computed from the TLS handshake == the server's cert.
            self.assertIn("FINGERPRINT " + fp, proc.stdout)

            ev = backend.events
            self.assertEqual(ev[0], ("move_rel", 10, -4))
            self.assertEqual(ev[1], ("move_abs", 0.25, 0.75))
            self.assertEqual(ev[2:6], [("click", "right", 1), ("click", "left", 2),
                                       ("button", "left", True), ("button", "left", False)])
            self.assertEqual(ev[6], ("scroll", 0, 3))
            self.assertEqual(ev[7], ("type_text", "héllo ✓ \U0001F600"))
            # Long text is split in chunks, but nothing is lost and no surrogate pair is cut in half.
            typed = "".join(e[1] for e in ev[8:] if e[0] == "type_text")
            chunks = [e[1] for e in ev[8:] if e[0] == "type_text"]
            self.assertEqual(len(chunks), 3)
            expected = [chr(ord("a") + i % 26) for i in range(3499)]
            expected[1499] = "\U0001F600"
            del expected[1500]
            self.assertEqual(typed, "".join(expected))
            self.assertNotIn("\ud83d", typed)
            rest = [e for e in ev if e[0] in ("key", "media")]
            self.assertEqual(rest, [("key", "c", ("primary",)), ("key", "enter", ()), ("media", "vol_up")])
            self.assertEqual(powers, ["lock"])
            # 5000 queued +1,+2 moves arrive possibly merged, but the total must be exact.
            moves = [e for e in ev if e[0] == "move_rel"][1:]
            self.assertEqual(sum(m[1] for m in moves), 5000)
            self.assertEqual(sum(m[2] for m in moves), 10000)
            server.shutdown()


if __name__ == "__main__":
    unittest.main()
