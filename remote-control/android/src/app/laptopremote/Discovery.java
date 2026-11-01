package app.laptopremote;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds laptops running the server on the local network (UDP broadcast). Pure Java. */
public final class Discovery {

    public static final int PORT = 8766;
    private static final byte[] MAGIC = "LAPTOP_REMOTE_DISCOVER_V1".getBytes(Charset.forName("US-ASCII"));

    public static final class Found {
        public final String host;
        public final int port;
        public final String name;
        public final String os;

        Found(String host, int port, String name, String os) {
            this.host = host;
            this.port = port;
            this.name = name;
            this.os = os;
        }
    }

    private Discovery() {
    }

    public static List<Found> scan(int timeoutMs) throws IOException {
        return scan(timeoutMs, "255.255.255.255", PORT);
    }

    /** Sends the probe to {@code target} (a broadcast address) and collects replies for timeoutMs. */
    public static List<Found> scan(int timeoutMs, String target, int port) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setBroadcast(true);
            socket.setSoTimeout(250);
            InetAddress addr = InetAddress.getByName(target);
            DatagramPacket probe = new DatagramPacket(MAGIC, MAGIC.length, addr, port);
            Map<String, Found> found = new LinkedHashMap<String, Found>();
            long end = System.currentTimeMillis() + timeoutMs;
            long nextProbe = 0;
            byte[] buf = new byte[1024];
            while (System.currentTimeMillis() < end) {
                long now = System.currentTimeMillis();
                if (now >= nextProbe) {          // UDP can drop packets: probe a few times
                    socket.send(probe);
                    nextProbe = now + 600;
                }
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(reply);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                try {
                    JSONObject j = new JSONObject(new String(reply.getData(), 0, reply.getLength(), "UTF-8"));
                    if (!"laptop-remote".equals(j.optString("app"))) continue;
                    int p = j.optInt("port", 0);
                    if (p < 1 || p > 65535) continue;
                    String host = reply.getAddress().getHostAddress();
                    found.put(host + ":" + p, new Found(host, p, j.optString("name", host), j.optString("os", "")));
                } catch (JSONException e) {
                    // not ours
                }
            }
            return new ArrayList<Found>(found.values());
        } finally {
            socket.close();
        }
    }
}
