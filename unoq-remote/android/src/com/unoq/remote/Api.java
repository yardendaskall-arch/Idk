package com.unoq.remote;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Tiny blocking HTTP client for the UNO Q Remote API. Call off the UI thread. */
final class Api {
    private Api() {}

    static JSONObject get(String host, int port, String path, int timeoutMs, String... kv) throws Exception {
        StringBuilder url = new StringBuilder("http://").append(host).append(':').append(port).append(path);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            url.append(i == 0 ? '?' : '&')
               .append(URLEncoder.encode(kv[i], "UTF-8")).append('=')
               .append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url.toString()).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(Math.max(timeoutMs, 3000));
        c.setUseCaches(false);
        c.setRequestProperty("X-UnoQ-Client", "android");   // LEDs only accept the phone app
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : readAll(in);
            if (code == 404) throw new Exception("the app on the board is out of date - copy the newest python/main.py and sketch.ino into App Lab and press Run");
            if (code >= 400) throw new Exception("HTTP " + code);
            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("ok", true)) throw new Exception(json.optString("error", "board error"));
            return json;
        } finally {
            c.disconnect();
        }
    }

    static final int AGENT_PORT = 7001;

    /** Thrown when the host agent rejects the PIN. */
    static final class WrongPin extends Exception {
        WrongPin() { super("wrong PIN"); }
    }

    /** Call the host agent (port 7001). body == null means GET, otherwise POST JSON. */
    static JSONObject agent(String host, String path, String pin, JSONObject body, int timeoutMs) throws Exception {
        if (host == null) throw new Exception("enter the board's address on the Pins tab first");
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + AGENT_PORT + path).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(timeoutMs);
        c.setUseCaches(false);
        c.setRequestProperty("X-Pin", pin == null ? "" : pin);
        try {
            if (body != null) {
                byte[] data = body.toString().getBytes("UTF-8");
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(data.length);
                c.getOutputStream().write(data);
            }
            int code = c.getResponseCode();
            if (code == 401) throw new WrongPin();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (code >= 400 && text.isEmpty()) throw new Exception("HTTP " + code);
            JSONObject json = new JSONObject(text);
            if (!json.optBoolean("ok", true)) throw new Exception(json.optString("error", "agent error"));
            return json;
        } catch (java.net.ConnectException e) {
            throw new Exception("can't reach the agent on " + host + ":" + AGENT_PORT + " - is it installed?");
        } finally {
            c.disconnect();
        }
    }

    /** One JPEG picture of the board's desktop plus the real screen size. */
    static final class Frame {
        byte[] jpeg;
        int screenW, screenH;
    }

    static Frame screen(String host, String pin, int width, int quality) throws Exception {
        if (host == null) throw new Exception("enter the board's address on the Pins tab first");
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + AGENT_PORT
                + "/api/screen/frame?w=" + width + "&q=" + quality).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(10000);
        c.setUseCaches(false);
        c.setRequestProperty("X-Pin", pin == null ? "" : pin);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new WrongPin();
            String type = c.getContentType();
            if (type == null || !type.startsWith("image/")) {
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                JSONObject j = new JSONObject(in == null ? "{}" : readAll(in));
                throw new Exception(j.optString("error", "no picture from the board"));
            }
            Frame f = new Frame();
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            f.jpeg = out.toByteArray();
            f.screenW = c.getHeaderFieldInt("X-Screen-W", 0);
            f.screenH = c.getHeaderFieldInt("X-Screen-H", 0);
            return f;
        } catch (java.net.ConnectException e) {
            throw new Exception("can't reach the agent on " + host + ":" + AGENT_PORT + " - is it installed?");
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
