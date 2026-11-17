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
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : readAll(in);
            if (code >= 400) throw new Exception("HTTP " + code);
            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("ok", true)) throw new Exception(json.optString("error", "board error"));
            return json;
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
