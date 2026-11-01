package app.laptopremote;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Client for laptop_remote_server.py.
 *
 * Wire format: [uint32 big-endian length][uint8 type][payload], over TLS.
 * Type 0 = JSON text, type 1 = JPEG screen frame.
 *
 * Security: the server uses a self-signed certificate. We never trust it
 * implicitly: the SHA-256 fingerprint is checked against the one the user
 * confirmed earlier (or confirmed just now) BEFORE the PIN is sent.
 *
 * This class deliberately uses no Android classes so it can be tested on a
 * plain JVM. All send methods are non-blocking and safe to call from the UI
 * thread; a writer thread owns the socket.
 */
public final class RemoteClient {

    public static final int PROTOCOL_VERSION = 1;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int FRAME_JSON = 0;
    private static final int FRAME_JPEG = 1;
    private static final int MAX_INCOMING = 8 * 1024 * 1024;
    private static final int MAX_TEXT_CHUNK = 1500;      // server accepts 2000
    private static final int MAX_QUEUE = 20000;
    // Merged movement stays under the server's per-message limits (3000 px / 100 notches)
    // so a stalled network never causes silently truncated movement.
    private static final int MAX_MERGED_MOVE = 2000;
    private static final int MAX_MERGED_SCROLL = 50;
    private static final int PING_INTERVAL_MS = 10000;
    private static final int READ_TIMEOUT_MS = 35000;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int HANDSHAKE_TIMEOUT_MS = 10000;

    /** Callbacks arrive on background threads. */
    public interface Listener {
        void onFrame(byte[] jpeg);
        /** Non-fatal message from the laptop (e.g. "power" or "screen" problems). */
        void onNotice(String kind, String message);
        /** Connection ended unexpectedly. Not called after close(). */
        void onClosed(String reason);
    }

    /** Asked (on the connecting thread) whether to trust a laptop seen for the first time. */
    public interface TrustPrompt {
        boolean confirm(String fingerprintHex) throws InterruptedException;
    }

    public static final class ServerInfo {
        public String name = "";
        public String os = "";
        public boolean screen;
        public boolean power;
        public int screenWidth, screenHeight;
        public String fingerprint = "";
    }

    /** Connection refused by the laptop or aborted by the user. {@code code} is machine readable. */
    public static class ConnectException extends IOException {
        public final String code;
        public ConnectException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** The laptop presented a different certificate than the one we trusted before. */
    public static final class CertChangedException extends ConnectException {
        public final String actualFingerprint;
        public CertChangedException(String actual) {
            super("cert_changed", "This laptop's security certificate has changed.");
            this.actualFingerprint = actual;
        }
    }

    // ------------------------------------------------------------------
    // Connecting
    // ------------------------------------------------------------------

    public static RemoteClient connect(String host, int port, String pin, String pinnedFingerprint,
                                       TrustPrompt prompt, Listener listener, String deviceName)
            throws IOException, InterruptedException {
        CapturingTrustManager tm = new CapturingTrustManager();
        SSLSocket socket;
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
            socket = (SSLSocket) ctx.getSocketFactory().createSocket();
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("TLS is not available on this phone: " + e.getMessage());
        }
        boolean ok = false;
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            restrictProtocols(socket);
            socket.startHandshake();

            String fp = tm.fingerprint;
            if (fp == null) throw new IOException("The laptop did not present a certificate.");
            if (pinnedFingerprint == null || pinnedFingerprint.length() == 0) {
                if (!prompt.confirm(fp)) {
                    throw new ConnectException("untrusted", "Connection cancelled.");
                }
            } else if (!pinnedFingerprint.equalsIgnoreCase(fp)) {
                throw new CertChangedException(fp);
            }

            // Only now, with the server identity settled, is the PIN sent.
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            JSONObject hello = obj("t", "hello", "v", PROTOCOL_VERSION, "pin", pin,
                    "name", deviceName == null ? "phone" : deviceName);
            writeFrame(out, FRAME_JSON, hello.toString().getBytes(UTF8));

            Frame reply = readFrame(in);
            if (reply.type != FRAME_JSON) throw new IOException("Unexpected reply from the laptop.");
            JSONObject msg = parse(reply.payload);
            String t = msg.optString("t");
            if ("error".equals(t)) {
                throw new ConnectException(msg.optString("code", "error"),
                        msg.optString("msg", "The laptop refused the connection."));
            }
            if (!"welcome".equals(t)) throw new IOException("Unexpected reply from the laptop.");

            ServerInfo info = new ServerInfo();
            info.name = msg.optString("host", host);
            info.os = msg.optString("os", "");
            info.screen = msg.optBoolean("screen", false);
            info.power = msg.optBoolean("power", false);
            info.fingerprint = fp;
            if (msg.optJSONArray("geom") != null && msg.optJSONArray("geom").length() == 2) {
                info.screenWidth = msg.optJSONArray("geom").optInt(0);
                info.screenHeight = msg.optJSONArray("geom").optInt(1);
            }

            socket.setSoTimeout(READ_TIMEOUT_MS);
            RemoteClient client = new RemoteClient(socket, in, out, info, listener);
            ok = true;
            client.start();
            return client;
        } catch (SocketTimeoutException e) {
            throw new IOException("The laptop did not answer in time.");
        } finally {
            if (!ok) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void restrictProtocols(SSLSocket s) {
        List<String> wanted = new ArrayList<String>();
        for (String p : s.getSupportedProtocols()) {
            if ("TLSv1.3".equals(p) || "TLSv1.2".equals(p)) wanted.add(p);
        }
        if (!wanted.isEmpty()) s.setEnabledProtocols(wanted.toArray(new String[0]));
    }

    /** Accepts any certificate at the TLS layer but records its fingerprint; the caller then pins it. */
    private static final class CapturingTrustManager implements X509TrustManager {
        volatile String fingerprint;

        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("not a server");
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("empty certificate chain");
            fingerprint = sha256Hex(chain[0].getEncoded());
        }

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    // ------------------------------------------------------------------
    // Instance
    // ------------------------------------------------------------------

    private static final int K_JSON = 0, K_MOVE = 1, K_SCROLL = 2, K_MOVETO = 3;

    private static final class Out {
        final int kind;
        JSONObject json;
        int a, b;
        double x, y;

        Out(int kind) {
            this.kind = kind;
        }
    }

    private final SSLSocket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final ServerInfo info;
    private final Listener listener;
    private final LinkedList<Out> queue = new LinkedList<Out>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private int pingId = 0;

    private RemoteClient(SSLSocket socket, DataInputStream in, DataOutputStream out,
                         ServerInfo info, Listener listener) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.info = info;
        this.listener = listener;
    }

    private void start() {
        Thread w = new Thread(new Runnable() {
            public void run() {
                writerLoop();
            }
        }, "remote-writer");
        Thread r = new Thread(new Runnable() {
            public void run() {
                readerLoop();
            }
        }, "remote-reader");
        w.setDaemon(true);
        r.setDaemon(true);
        w.start();
        r.start();
    }

    public ServerInfo info() {
        return info;
    }

    public boolean isOpen() {
        return !closed.get();
    }

    /** User-initiated disconnect. Does not call Listener.onClosed. */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Socket teardown can block briefly; never do it on the caller's (UI) thread.
            Thread t = new Thread(new Runnable() {
                public void run() {
                    shutdownSocket();
                }
            }, "remote-close");
            t.setDaemon(true);
            t.start();
        }
    }

    private void fail(String reason) {
        if (closed.compareAndSet(false, true)) {
            shutdownSocket();
            if (listener != null) listener.onClosed(reason);
        }
    }

    private void shutdownSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        synchronized (queue) {
            queue.clear();
            queue.notifyAll();
        }
    }

    // -- sending ---------------------------------------------------------

    /** Relative pointer movement. Consecutive moves still waiting to be sent are merged. */
    public void move(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        synchronized (queue) {
            Out last = queue.peekLast();
            if (last != null && last.kind == K_MOVE
                    && Math.abs(last.a + dx) <= MAX_MERGED_MOVE && Math.abs(last.b + dy) <= MAX_MERGED_MOVE) {
                last.a += dx;
                last.b += dy;
            } else {
                Out o = new Out(K_MOVE);
                o.a = dx;
                o.b = dy;
                enqueueLocked(o);
            }
        }
    }

    /** Absolute pointer position as a fraction (0..1) of the laptop's primary screen. */
    public void moveTo(double x, double y) {
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isInfinite(x) || Double.isInfinite(y)) return;
        synchronized (queue) {
            Out last = queue.peekLast();
            if (last != null && last.kind == K_MOVETO) {
                last.x = x;
                last.y = y;
            } else {
                Out o = new Out(K_MOVETO);
                o.x = x;
                o.y = y;
                enqueueLocked(o);
            }
        }
    }

    /** Positive dy scrolls down, positive dx scrolls right (in wheel notches). */
    public void scroll(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        synchronized (queue) {
            Out last = queue.peekLast();
            if (last != null && last.kind == K_SCROLL
                    && Math.abs(last.a + dx) <= MAX_MERGED_SCROLL && Math.abs(last.b + dy) <= MAX_MERGED_SCROLL) {
                last.a += dx;
                last.b += dy;
            } else {
                Out o = new Out(K_SCROLL);
                o.a = dx;
                o.b = dy;
                enqueueLocked(o);
            }
        }
    }

    public void click(String button, int count) {
        send(obj("t", "click", "b", button, "n", count));
    }

    public void button(String button, boolean down) {
        send(obj("t", down ? "down" : "up", "b", button));
    }

    /** Types text on the laptop (any characters, including newlines). */
    public void text(String s) {
        if (s == null) return;
        int i = 0;
        while (i < s.length()) {
            int end = Math.min(s.length(), i + MAX_TEXT_CHUNK);
            // do not split a surrogate pair
            if (end < s.length() && Character.isHighSurrogate(s.charAt(end - 1))) end--;
            send(obj("t", "text", "s", s.substring(i, end)));
            i = end;
        }
    }

    /** Presses a key with optional modifiers (ctrl, alt, shift, win, primary, switch). */
    public void key(String key, String... mods) {
        JSONObject o = obj("t", "key", "k", key);
        if (mods != null && mods.length > 0) {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String m : mods) arr.put(m);
            put(o, "mods", arr);
        }
        send(o);
    }

    public void media(String action) {
        send(obj("t", "media", "a", action));
    }

    public void power(String action) {
        send(obj("t", "power", "a", action));
    }

    public void screen(boolean on, int width, int quality, int fps) {
        send(obj("t", "screen", "on", on, "w", width, "q", quality, "fps", fps));
    }

    private void send(JSONObject json) {
        Out o = new Out(K_JSON);
        o.json = json;
        synchronized (queue) {
            enqueueLocked(o);
        }
    }

    private void enqueueLocked(Out o) {
        if (closed.get()) return;
        if (queue.size() > MAX_QUEUE) {
            // The network has stalled completely; give up rather than grow forever.
            new Thread(new Runnable() {
                public void run() {
                    fail("The connection to the laptop is too slow.");
                }
            }).start();
            return;
        }
        queue.add(o);
        queue.notifyAll();
    }

    private void writerLoop() {
        try {
            while (true) {
                Out o;
                synchronized (queue) {
                    while (queue.isEmpty() && !closed.get()) {
                        queue.wait(PING_INTERVAL_MS);
                        if (queue.isEmpty() && !closed.get()) {
                            queue.add(jsonOut(obj("t", "ping", "id", ++pingId)));
                        }
                    }
                    if (closed.get()) return;
                    o = queue.poll();
                }
                writeFrame(out, FRAME_JSON, toJson(o).toString().getBytes(UTF8));
            }
        } catch (InterruptedException e) {
            // closing
        } catch (IOException e) {
            if (!closed.get()) fail("Connection lost.");
        }
    }

    private static Out jsonOut(JSONObject j) {
        Out o = new Out(K_JSON);
        o.json = j;
        return o;
    }

    private static JSONObject toJson(Out o) {
        switch (o.kind) {
            case K_MOVE:
                return obj("t", "move", "dx", o.a, "dy", o.b);
            case K_SCROLL:
                return obj("t", "scroll", "dx", o.a, "dy", o.b);
            case K_MOVETO:
                return obj("t", "moveto", "x", o.x, "y", o.y);
            default:
                return o.json;
        }
    }

    // -- receiving -------------------------------------------------------

    private void readerLoop() {
        try {
            while (!closed.get()) {
                Frame f = readFrame(in);
                if (f.type == FRAME_JPEG) {
                    if (listener != null) listener.onFrame(f.payload);
                } else if (f.type == FRAME_JSON) {
                    handle(parse(f.payload));
                }
            }
        } catch (SocketTimeoutException e) {
            fail("The laptop stopped responding.");
        } catch (IOException e) {
            if (!closed.get()) fail("Connection to the laptop was lost.");
        } catch (RuntimeException e) {
            if (!closed.get()) fail("Received something unexpected from the laptop.");
        }
    }

    private void handle(JSONObject m) {
        String t = m.optString("t");
        if ("error".equals(t)) {
            if (listener != null) listener.onNotice(m.optString("code", "error"), m.optString("msg", ""));
        } else if ("ok".equals(t)) {
            if (listener != null && "power".equals(m.optString("for"))) {
                listener.onNotice("power_ok", m.optBoolean("done", true)
                        ? "Command sent to the laptop." : "The laptop could not run that command.");
            }
        }
        // "pong" only serves to keep the read timeout from firing.
    }

    // -- framing ---------------------------------------------------------

    private static final class Frame {
        final int type;
        final byte[] payload;

        Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    private static Frame readFrame(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 1 || len > MAX_INCOMING) throw new IOException("bad frame length " + len);
        int type = in.readUnsignedByte();
        byte[] payload = new byte[len - 1];
        in.readFully(payload);
        return new Frame(type, payload);
    }

    private static void writeFrame(DataOutputStream out, int type, byte[] payload) throws IOException {
        out.writeInt(payload.length + 1);
        out.writeByte(type);
        out.write(payload);
        out.flush();
    }

    // -- helpers ---------------------------------------------------------

    private static JSONObject parse(byte[] payload) throws IOException {
        try {
            return new JSONObject(new String(payload, UTF8));
        } catch (JSONException e) {
            throw new IOException("Malformed message from the laptop.");
        }
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) put(o, (String) kv[i], kv[i + 1]);
        return o;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "ab12cd34..." -> "AB12 CD34 ..." (same layout the server prints). */
    public static String formatFingerprint(String hex) {
        StringBuilder sb = new StringBuilder();
        String h = hex.toUpperCase(java.util.Locale.US);
        for (int i = 0; i < h.length(); i += 4) {
            if (i > 0) sb.append(i % 32 == 0 ? '\n' : ' ');
            sb.append(h, i, Math.min(h.length(), i + 4));
        }
        return sb.toString();
    }
}
