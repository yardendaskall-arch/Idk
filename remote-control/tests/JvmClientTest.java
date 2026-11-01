import app.laptopremote.Discovery;
import app.laptopremote.RemoteClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exercises the Android client class (pure Java) against the real Python server.
 * Driven by tests/test_android_client.py:  java JvmClientTest <host> <port> <pin> <discoveryPort>
 */
public class JvmClientTest {

    static int failures = 0;

    static void check(boolean cond, String what) {
        if (cond) {
            System.out.println("ok    " + what);
        } else {
            failures++;
            System.out.println("FAIL  " + what);
        }
    }

    static final class Rec implements RemoteClient.Listener {
        final AtomicInteger frames = new AtomicInteger();
        final AtomicReference<byte[]> lastFrame = new AtomicReference<byte[]>();
        final AtomicReference<String> notice = new AtomicReference<String>();
        final AtomicReference<String> closed = new AtomicReference<String>();
        final CountDownLatch gotFrame = new CountDownLatch(1);
        final CountDownLatch gotClosed = new CountDownLatch(1);
        final CountDownLatch gotNotice = new CountDownLatch(1);

        public void onFrame(byte[] jpeg) {
            lastFrame.set(jpeg);
            frames.incrementAndGet();
            gotFrame.countDown();
        }

        public void onNotice(String kind, String message) {
            notice.set(kind + ":" + message);
            gotNotice.countDown();
        }

        public void onClosed(String reason) {
            closed.set(reason);
            gotClosed.countDown();
        }
    }

    static final class Trust implements RemoteClient.TrustPrompt {
        final boolean answer;
        final AtomicReference<String> asked = new AtomicReference<String>();

        Trust(boolean answer) {
            this.answer = answer;
        }

        public boolean confirm(String fp) {
            asked.set(fp);
            return answer;
        }
    }

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String pin = args[2];
        int discoveryPort = Integer.parseInt(args[3]);

        // --- wrong PIN
        try {
            RemoteClient.connect(host, port, "00000000", null, new Trust(true), new Rec(), "t");
            check(false, "wrong PIN is rejected");
        } catch (RemoteClient.ConnectException e) {
            check("auth".equals(e.code), "wrong PIN is rejected (code=" + e.code + ")");
        }

        // --- user refuses to trust the fingerprint
        Trust no = new Trust(false);
        try {
            RemoteClient.connect(host, port, pin, null, no, new Rec(), "t");
            check(false, "refusing the fingerprint aborts the connection");
        } catch (RemoteClient.ConnectException e) {
            check("untrusted".equals(e.code) && no.asked.get() != null, "refusing the fingerprint aborts the connection");
        }

        // --- a pinned fingerprint that does not match is a hard error, and the PIN is not sent
        try {
            RemoteClient.connect(host, port, pin, "00" + "ab".repeat(31), new Trust(true), new Rec(), "t");
            check(false, "changed certificate is detected");
        } catch (RemoteClient.CertChangedException e) {
            check(e.actualFingerprint.length() == 64, "changed certificate is detected");
        }

        // --- good login with first-time trust
        Trust yes = new Trust(true);
        Rec rec = new Rec();
        RemoteClient c = RemoteClient.connect(host, port, pin, null, yes, rec, "JVM Test Phone");
        check(yes.asked.get() != null && yes.asked.get().length() == 64, "first connection asks to trust a 64-hex fingerprint");
        String fp = yes.asked.get();
        check(c.info().name.length() > 0 && c.info().os.length() > 0, "welcome info: " + c.info().name + "/" + c.info().os);
        check(c.info().screenWidth == 1920 && c.info().screenHeight == 1080, "welcome geometry");
        System.out.println("FINGERPRINT " + fp);
        System.out.println("FORMATTED " + RemoteClient.formatFingerprint(fp).replace('\n', '|'));

        // --- second connection with the pinned fingerprint does not prompt
        Trust unused = new Trust(false);
        RemoteClient c2 = RemoteClient.connect(host, port, pin, fp.toUpperCase(), unused, new Rec(), "second");
        check(unused.asked.get() == null, "pinned fingerprint connects without prompting");
        c2.close();

        // --- commands
        c.move(10, -4);
        c.moveTo(0.25, 0.75);
        c.moveTo(Double.NaN, 0.5);                 // ignored
        c.click("right", 1);
        c.click("left", 2);
        c.button("left", true);
        c.button("left", false);
        c.scroll(0, 3);
        c.text("héllo ✓ 😀");  // accented, check mark, emoji (surrogate pair)
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 3499; i++) big.append((char) ('a' + i % 26));
        big.setCharAt(1499, '\ud83d');             // a surrogate pair straddling a chunk boundary
        big.setCharAt(1500, '\ude00');
        c.text(big.toString());
        c.key("c", "primary");
        c.key("enter");
        c.media("vol_up");
        c.power("lock");
        System.out.println("sent commands");

        // --- screen streaming
        c.screen(true, 400, 50, 10);
        check(rec.gotFrame.await(10, TimeUnit.SECONDS), "received a screen frame");
        byte[] f = rec.lastFrame.get();
        check(f != null && f.length > 3 && (f[0] & 0xff) == 0xff && (f[1] & 0xff) == 0xd8, "frame is a JPEG");
        c.screen(false, 400, 50, 10);

        // --- power result notice
        check(rec.gotNotice.await(5, TimeUnit.SECONDS) && rec.notice.get().startsWith("power_ok"), "power ack surfaced as notice: " + rec.notice.get());

        // --- many moves coalesce but never lose distance
        for (int i = 0; i < 5000; i++) c.move(1, 2);

        Thread.sleep(800);

        // --- server hangs up -> onClosed
        c.text("__drop__");
        check(rec.gotClosed.await(5, TimeUnit.SECONDS), "onClosed is called when the laptop drops us: " + rec.closed.get());
        check(!c.isOpen(), "client reports closed");

        // --- user close does not call onClosed
        Rec rec3 = new Rec();
        RemoteClient c3 = RemoteClient.connect(host, port, pin, fp, new Trust(false), rec3, "third");
        c3.close();
        check(!rec3.gotClosed.await(1, TimeUnit.SECONDS), "close() does not call onClosed");
        c3.move(1, 1);                              // must not throw after close

        // --- not a TLS server at all
        final ServerSocket plain = new ServerSocket(0);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Socket s = plain.accept();
                    OutputStream o = s.getOutputStream();
                    o.write("HTTP/1.1 200 OK\r\n\r\nhello".getBytes());
                    o.flush();
                    Thread.sleep(500);
                    s.close();
                } catch (Exception ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        try {
            RemoteClient.connect("127.0.0.1", plain.getLocalPort(), pin, null, new Trust(true), new Rec(), "t");
            check(false, "non-TLS server fails cleanly");
        } catch (IOException e) {
            check(!(e instanceof RemoteClient.ConnectException), "non-TLS server fails cleanly (" + e.getMessage() + ")");
        }
        plain.close();

        // --- nothing listening
        try {
            RemoteClient.connect("127.0.0.1", 1, pin, null, new Trust(true), new Rec(), "t");
            check(false, "closed port fails cleanly");
        } catch (IOException e) {
            check(true, "closed port fails cleanly (" + e.getMessage() + ")");
        }

        // --- discovery
        List<Discovery.Found> found = Discovery.scan(1500, "127.0.0.1", discoveryPort);
        check(found.size() == 1 && found.get(0).port == port && found.get(0).host.equals("127.0.0.1"),
                "discovery finds the server (" + found.size() + " result)");

        System.out.println(failures == 0 ? "ALL JVM CHECKS PASSED" : failures + " JVM CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
