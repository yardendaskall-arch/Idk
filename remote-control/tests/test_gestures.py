"""Runs the touchpad gesture unit tests (plain Java, no Android) if a JDK is available."""
import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
JAVAC, JAVA = shutil.which("javac"), shutil.which("java")


@unittest.skipUnless(JAVAC and JAVA, "needs a JDK")
class TestGestures(unittest.TestCase):
    def test_gesture_logic(self):
        with tempfile.TemporaryDirectory() as out:
            sources = [
                os.path.join(HERE, "..", "android", "src", "app", "laptopremote", "TouchpadGestures.java"),
                os.path.join(HERE, "app", "laptopremote", "GestureTest.java"),
            ]
            subprocess.run([JAVAC, "--release", "8", "-Xlint:-options", "-d", out] + sources,
                           check=True, capture_output=True)
            proc = subprocess.run([JAVA, "-cp", out, "app.laptopremote.GestureTest"],
                                  capture_output=True, text=True, timeout=60)
            print(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            self.assertIn("ALL GESTURE CHECKS PASSED", proc.stdout)


if __name__ == "__main__":
    unittest.main()
