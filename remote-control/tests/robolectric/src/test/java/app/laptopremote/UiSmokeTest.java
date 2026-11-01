package app.laptopremote;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.Scheduler;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * Drives the real activities (on a simulated Android) against the real Python server in --dry-run mode:
 * connect flow, fingerprint check, every tab, gestures, power confirmation, and connection loss.
 * Needs JDK 8 + Robolectric 3.8 (see tests/robolectric/README).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, manifest = Config.NONE)
public class UiSmokeTest {

    // ------------------------------------------------------------------ server process

    static Process server;
    static final List<String> log = new ArrayList<String>();
    static String pin, serverFingerprint;
    static int port;
    static File tmp;

    @BeforeClass
    public static void startServer() throws Exception {
        tmp = Files.createTempDirectory("lr-ui").toFile();
        ProcessBuilder pb = new ProcessBuilder(System.getProperty("python.bin"), System.getProperty("server.script"),
                "--dry-run", "--port", "0", "--no-discovery", "--config-dir", tmp.getAbsolutePath());
        pb.redirectErrorStream(true);
        server = pb.start();
        final BufferedReader r = new BufferedReader(new InputStreamReader(server.getInputStream(), "UTF-8"));
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (log) {
                            log.add(line);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        long end = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < end && !logContains("Press Ctrl+C")) Thread.sleep(50);
        assertTrue("server did not start: " + log, logContains("Press Ctrl+C"));
        StringBuilder all = new StringBuilder();
        synchronized (log) {
            for (String l : log) all.append(l).append('\n');
        }
        Matcher m = Pattern.compile("Port\\s*:\\s*(\\d+)").matcher(all);
        assertTrue(m.find());
        port = Integer.parseInt(m.group(1));
        m = Pattern.compile("PIN\\s*:\\s*(\\d{4})-(\\d{4})").matcher(all);
        assertTrue(m.find());
        pin = m.group(1) + m.group(2);
        m = Pattern.compile("Fingerprint : ((?:[0-9A-F]{4} ?){8})\\s*\\n\\s*((?:[0-9A-F]{4} ?){8})").matcher(all);
        assertTrue(m.find());
        serverFingerprint = (m.group(1) + m.group(2)).replaceAll("\\s", "").toLowerCase();
        assertEquals(64, serverFingerprint.length());
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) server.destroy();
    }

    static boolean logContains(String s) {
        synchronized (log) {
            for (String l : log) if (l.contains(s)) return true;
        }
        return false;
    }

    /** Number of dry-run events whose description starts with the given text. */
    static int count(String prefix) {
        int n = 0;
        synchronized (log) {
            for (String l : log) if (l.contains("dry-run: " + prefix)) n++;
        }
        return n;
    }

    static void expectEvents(String prefix, int atLeast) throws Exception {
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            if (count(prefix) >= atLeast) return;
            Thread.sleep(25);
        }
        throw new AssertionError("expected >= " + atLeast + " '" + prefix + "' events, got " + count(prefix)
                + "\n" + tail());
    }

    static String tail() {
        synchronized (log) {
            return log.subList(Math.max(0, log.size() - 25), log.size()).toString();
        }
    }

    // ------------------------------------------------------------------ helpers

    Scheduler ui;

    @Before
    public void setUp() {
        Session.end();
        Session.closeReason = null;
        ui = Robolectric.getForegroundThreadScheduler();
        ui.pause();                              // tasks posted from other threads wait until we pump
    }

    @After
    public void tearDown() {
        Session.end();
    }

    void pump() {
        ui.advanceToLastPostedRunnable();
    }

    void waitFor(String what, BooleanSupplier cond) throws Exception {
        long end = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < end) {
            pump();
            if (cond.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for: " + what + "\n" + tail());
    }

    static <T extends View> List<T> all(View root, Class<T> cls) {
        List<T> out = new ArrayList<T>();
        collect(root, cls, out);
        return out;
    }

    static <T extends View> void collect(View v, Class<T> cls, List<T> out) {
        if (cls.isInstance(v)) out.add(cls.cast(v));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), cls, out);
        }
    }

    static Button button(View root, String text) {
        for (Button b : all(root, Button.class)) if (text.contentEquals(b.getText())) return b;
        throw new AssertionError("no button '" + text + "'");
    }

    static TextView tab(View root, String text) {
        for (TextView t : all(root, TextView.class)) {
            // (the Keys panel also has a plain "Keys" heading; the real tab is the clickable one)
            if (!(t instanceof Button) && !(t instanceof EditText) && text.contentEquals(t.getText())
                    && t.hasOnClickListeners()) return t;
        }
        throw new AssertionError("no tab '" + text + "'");
    }

    static boolean hasText(View root, String contains) {
        for (TextView t : all(root, TextView.class)) if (t.getText().toString().contains(contains)) return true;
        return false;
    }

    static EditText editWithHint(View root, String hint) {
        for (EditText e : all(root, EditText.class)) {
            if (e.getHint() != null && hint.contentEquals(e.getHint())) return e;
        }
        throw new AssertionError("no field with hint '" + hint + "'");
    }

    long clock = 1000;

    MotionEvent ev(long down, long when, int action, float x, float y) {
        return MotionEvent.obtain(down, when, action, x, y, 0);
    }

    /** A press-and-release on a view, as a finger would do it. */
    void tap(View v) {
        long t = clock += 1000;
        v.dispatchTouchEvent(ev(t, t, MotionEvent.ACTION_DOWN, 5, 5));
        v.dispatchTouchEvent(ev(t, t + 60, MotionEvent.ACTION_UP, 5, 5));
    }

    /** Connect a fresh MainActivity to the running server; returns the live ControlActivity. */
    ControlActivity connectAndOpenControls(MainActivity main) throws Exception {
        View root = main.findViewById(android.R.id.content);
        editWithHint(root, "e.g. 192.168.1.23").setText("127.0.0.1");
        editWithHint(root, "8765").setText(String.valueOf(port));
        editWithHint(root, "8 digits").setText(pin.substring(0, 4) + "-" + pin.substring(4));   // dash must be ignored
        button(root, "Connect").performClick();
        waitFor("fingerprint dialog", new BooleanSupplier() {
            public boolean getAsBoolean() {
                AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
                return d != null && d.isShowing();
            }
        });
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        View dlgRoot = dialog.getWindow().getDecorView();
        String shown = null;
        for (TextView t : all(dlgRoot, TextView.class)) {
            String s = t.getText().toString().replaceAll("\\s", "");
            if (s.matches("[0-9A-F]{64}")) shown = s.toLowerCase();
        }
        assertEquals("dialog shows the server's fingerprint", serverFingerprint, shown);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitFor("controls to open", new BooleanSupplier() {
            public boolean getAsBoolean() {
                return Session.client != null && shadowOf(main).peekNextStartedActivity() != null;
            }
        });
        Intent started = shadowOf(main).getNextStartedActivity();
        assertEquals(ControlActivity.class.getName(), started.getComponent().getClassName());
        assertEquals("fingerprint remembered after success", serverFingerprint,
                new Prefs(main).fingerprint("127.0.0.1", port));
        return Robolectric.buildActivity(ControlActivity.class).setup().get();
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void connectScreenValidatesInput() throws Exception {
        MainActivity main = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = main.findViewById(android.R.id.content);
        button(root, "Connect").performClick();
        assertTrue(hasText(root, "Enter the laptop's address"));
        editWithHint(root, "e.g. 192.168.1.23").setText("127.0.0.1");
        editWithHint(root, "8765").setText("99999");
        button(root, "Connect").performClick();
        assertTrue(hasText(root, "port must be a number"));
        editWithHint(root, "8765").setText("8765");
        button(root, "Connect").performClick();
        assertTrue(hasText(root, "Enter the PIN"));
    }

    @Test
    public void wrongPinIsReportedAndFingerprintNotSaved() throws Exception {
        MainActivity main = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = main.findViewById(android.R.id.content);
        editWithHint(root, "e.g. 192.168.1.23").setText("127.0.0.1");
        editWithHint(root, "8765").setText(String.valueOf(port));
        editWithHint(root, "8 digits").setText("00000000");
        button(root, "Connect").performClick();
        waitFor("fingerprint dialog", new BooleanSupplier() {
            public boolean getAsBoolean() {
                AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
                return d != null && d.isShowing();
            }
        });
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitFor("wrong PIN message", new BooleanSupplier() {
            public boolean getAsBoolean() {
                return hasText(root, "Wrong PIN");
            }
        });
        assertNull("nothing is pinned when login fails", new Prefs(main).fingerprint("127.0.0.1", port));
        assertNull(Session.client);
        assertTrue("connect button usable again", button(root, "Connect").isEnabled());
    }

    @Test
    public void changedCertificateOffersToForgetOldOne() throws Exception {
        MainActivity main = Robolectric.buildActivity(MainActivity.class).setup().get();
        Prefs prefs = new Prefs(main);
        prefs.setFingerprint("127.0.0.1", port, "00" + "abababababababababababababababababababababababababababababababab".substring(2));
        View root = main.findViewById(android.R.id.content);
        editWithHint(root, "e.g. 192.168.1.23").setText("127.0.0.1");
        editWithHint(root, "8765").setText(String.valueOf(port));
        editWithHint(root, "8 digits").setText(pin);
        button(root, "Connect").performClick();
        waitFor("certificate warning", new BooleanSupplier() {
            public boolean getAsBoolean() {
                AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
                return d != null && d.isShowing();
            }
        });
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("Laptop's certificate changed", shadowOf(d).getTitle().toString());
        assertNull("no session was opened", Session.client);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        pump();                                   // dialog buttons deliver their action through the UI handler
        assertNull("old fingerprint forgotten", prefs.fingerprint("127.0.0.1", port));
    }

    @Test
    public void fullRemoteSession() throws Exception {
        MainActivity main = Robolectric.buildActivity(MainActivity.class).setup().get();
        ControlActivity c = connectAndOpenControls(main);
        View root = c.findViewById(android.R.id.content);
        assertTrue("header names the laptop", hasText(root, "● "));
        TouchpadView pad = all(root, TouchpadView.class).get(0);
        assertTrue(pad.isShown());

        // ---- touchpad: drag moves, does not click
        int clicksBefore = count("click ");
        long t = clock += 1000;
        pad.dispatchTouchEvent(ev(t, t, MotionEvent.ACTION_DOWN, 100, 100));
        for (int i = 1; i <= 10; i++) {
            pad.dispatchTouchEvent(ev(t, t + i * 16, MotionEvent.ACTION_MOVE, 100 + i * 10, 100 + i * 6));
        }
        pad.dispatchTouchEvent(ev(t, t + 200, MotionEvent.ACTION_UP, 200, 160));
        expectEvents("move_rel", 1);
        assertEquals("a drag is not a click", clicksBefore, count("click "));

        // ---- tap = left click
        t = clock += 1000;
        pad.dispatchTouchEvent(ev(t, t, MotionEvent.ACTION_DOWN, 50, 50));
        pad.dispatchTouchEvent(ev(t, t + 80, MotionEvent.ACTION_UP, 50, 50));
        expectEvents("click left 1", 1);

        // (two-finger gestures cannot be simulated by Robolectric 3.8; they are covered by GestureTest)

        // ---- hold then drag = press, move, release
        t = clock += 1000;
        int downsBefore = count("button left True");
        pad.dispatchTouchEvent(ev(t, t, MotionEvent.ACTION_DOWN, 100, 100));
        ui.advanceBy(450);                         // long-press timer fires
        expectEvents("button left True", downsBefore + 1);
        pad.dispatchTouchEvent(ev(t, t + 500, MotionEvent.ACTION_MOVE, 160, 140));
        pad.dispatchTouchEvent(ev(t, t + 520, MotionEvent.ACTION_MOVE, 220, 180));
        pad.dispatchTouchEvent(ev(t, t + 600, MotionEvent.ACTION_UP, 220, 180));
        expectEvents("button left False", 1);

        // ---- Left / Right buttons press and hold
        int rightDowns = count("button right True");
        View rightBtn = button(root, "Right");
        long tt = clock += 1000;
        rightBtn.dispatchTouchEvent(ev(tt, tt, MotionEvent.ACTION_DOWN, 5, 5));
        expectEvents("button right True", rightDowns + 1);
        rightBtn.dispatchTouchEvent(ev(tt, tt + 50, MotionEvent.ACTION_UP, 5, 5));
        expectEvents("button right False", 1);

        // ---- Keys tab
        tab(root, "Keys").performClick();
        List<EditText> edits = all(root, EditText.class);
        EditText live = null;
        for (EditText e : edits) if ("​".equals(e.getText().toString())) live = e;
        assertNotNull("live typing box present", live);
        assertTrue(live.isShown());
        live.getText().append("h");
        expectEvents("type_text (1 chars)", 1);
        assertEquals("box resets to its invisible placeholder", "​", live.getText().toString());
        live.getText().append("hello world é");
        expectEvents("type_text (13 chars)", 1);
        int backspaces = count("key backspace");
        live.getText().delete(0, 1);                 // what the keyboard does on Backspace
        expectEvents("key backspace", backspaces + 1);
        assertEquals("​", live.getText().toString());
        button(root, "Ctrl").performClick();
        live.getText().append("c");                  // Ctrl armed + 'c' -> Ctrl+C
        expectEvents("key <char> ctrl", 1);
        live.getText().append("x");                  // modifier cleared after one key
        expectEvents("type_text (1 chars)", 2);
        button(root, "Copy").performClick();
        expectEvents("key <char> primary", 1);
        button(root, "Switch app").performClick();
        expectEvents("key tab switch", 1);
        tap(button(root, "Esc"));
        expectEvents("key esc", 1);
        button(root, "Alt").performClick();
        tap(button(root, "Tab"));
        expectEvents("key tab alt", 1);

        // arrow key repeats while held
        View left = button(root, "←");
        int lefts = count("key left");
        long th = clock += 1000;
        left.dispatchTouchEvent(ev(th, th, MotionEvent.ACTION_DOWN, 5, 5));
        ui.advanceBy(380 + 70 * 4 + 5);
        left.dispatchTouchEvent(ev(th, th + 700, MotionEvent.ACTION_UP, 5, 5));
        expectEvents("key left", lefts + 4);
        int afterRelease = count("key left");
        ui.advanceBy(1000);
        Thread.sleep(300);
        assertEquals("repeat stops on release", afterRelease, count("key left"));

        // ---- Media tab
        tab(root, "Media").performClick();
        tap(button(root, "Play / Pause"));
        expectEvents("media play_pause", 1);
        tap(button(root, "Mute"));
        expectEvents("media mute", 1);

        // ---- Screen tab: frames arrive and decode, taps move + click
        tab(root, "Screen").performClick();
        ScreenView screen = all(root, ScreenView.class).get(0);
        final ScreenView sv = screen;
        waitFor("a screen frame", new BooleanSupplier() {
            public boolean getAsBoolean() {
                return sv.hasFrame();
            }
        });
        // Robolectric does not run a real layout pass; give the view a size right before using it.
        screen.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
        screen.layout(0, 0, 400, 300);
        long ts = clock += 1000;
        screen.dispatchTouchEvent(ev(ts, ts, MotionEvent.ACTION_DOWN, 200, 150));
        screen.dispatchTouchEvent(ev(ts, ts + 80, MotionEvent.ACTION_UP, 200, 150));
        expectEvents("move_abs", 1);
        expectEvents("click left 1", 2);
        button(root, "Low").performClick();           // restarts the stream with new settings
        button(root, "Reset zoom").performClick();

        // ---- Power tab: lock is immediate, shutdown asks first
        tab(root, "Power").performClick();
        button(root, "Lock screen").performClick();
        expectEvents("power lock", 1);
        button(root, "Shut down").performClick();
        AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(confirm.isShowing());
        assertTrue(shadowOf(confirm).getMessage().toString().contains("Shut the laptop down"));
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        pump();
        Thread.sleep(300);
        assertEquals("cancelled shutdown is not sent", 0, count("power shutdown"));
        button(root, "Shut down").performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        pump();
        expectEvents("power shutdown", 1);

        // ---- Back asks, then disconnects
        c.onBackPressed();
        AlertDialog bye = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(bye.isShowing());
        assertFalse(c.isFinishing());
        bye.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        pump();
        assertTrue(c.isFinishing());
    }

    @Test
    public void controlsCloseWhenTheLaptopGoesAway() throws Exception {
        // A second server we can kill without affecting the shared one.
        File dir = Files.createTempDirectory("lr-ui2").toFile();
        ProcessBuilder pb = new ProcessBuilder(System.getProperty("python.bin"), System.getProperty("server.script"),
                "--dry-run", "--port", "0", "--no-discovery", "--config-dir", dir.getAbsolutePath());
        pb.redirectErrorStream(true);
        final Process p = pb.start();
        final StringBuilder out = new StringBuilder();
        final BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        Thread th = new Thread(new Runnable() {
            public void run() {
                try {
                    String l;
                    while ((l = r.readLine()) != null) synchronized (out) {
                        out.append(l).append('\n');
                    }
                } catch (Exception ignored) {
                }
            }
        });
        th.setDaemon(true);
        th.start();
        try {
            long end = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < end && !out.toString().contains("Press Ctrl+C")) Thread.sleep(50);
            Matcher mp = Pattern.compile("Port\\s*:\\s*(\\d+)").matcher(out);
            assertTrue(mp.find());
            Matcher mn = Pattern.compile("PIN\\s*:\\s*(\\d{4})-(\\d{4})").matcher(out);
            assertTrue(mn.find());
            int port2 = Integer.parseInt(mp.group(1));

            MainActivity main = Robolectric.buildActivity(MainActivity.class).setup().get();
            View root = main.findViewById(android.R.id.content);
            editWithHint(root, "e.g. 192.168.1.23").setText("127.0.0.1");
            editWithHint(root, "8765").setText(String.valueOf(port2));
            editWithHint(root, "8 digits").setText(mn.group(1) + mn.group(2));
            button(root, "Connect").performClick();
            waitFor("fingerprint dialog", new BooleanSupplier() {
                public boolean getAsBoolean() {
                    AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
                    return d != null && d.isShowing();
                }
            });
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            waitFor("controls", new BooleanSupplier() {
                public boolean getAsBoolean() {
                    return Session.client != null && shadowOf(main).peekNextStartedActivity() != null;
                }
            });
            final ControlActivity c = Robolectric.buildActivity(ControlActivity.class).setup().get();
            assertFalse(c.isFinishing());

            p.destroyForcibly();                      // laptop vanishes
            waitFor("controls to close", new BooleanSupplier() {
                public boolean getAsBoolean() {
                    return c.isFinishing();
                }
            });
            assertNotNull("reason is kept for the connect screen", Session.closeReason);
            main.onResume();
            assertTrue("connect screen explains what happened", hasText(root, "Connection to the laptop was lost")
                    || hasText(root, "stopped responding"));
        } finally {
            p.destroyForcibly();
        }
    }
}
