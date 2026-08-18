package com.sheintrain;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class SupabaseClient {

    private static final String BASE = "https://iehgfqwpybsemuczvnni.supabase.co";
    private static final String KEY  = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImllaGdmcXdweWJzZW11Y3p2bm5pIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODcwNzU1MDMsImV4cCI6MjEwMjY1MTUwM30.KArt1-BwA0vb7Aul2hhsGD1tFKl9JFFU2VtNRs0eOFY";

    public interface Callback      { void onResult(boolean ok, String error); }
    public interface FetchCallback { void onResult(List<String> items, String error); }

    public static void insertSuggestion(final String itemName, final Callback cb) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    URL url = new URL(BASE + "/rest/v1/suggestions");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setRequestMethod("POST");
                    c.setRequestProperty("apikey", KEY);
                    c.setRequestProperty("Authorization", "Bearer " + KEY);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("Prefer", "return=minimal");
                    c.setDoOutput(true);
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);

                    JSONObject body = new JSONObject();
                    body.put("item_name", itemName);
                    byte[] data = body.toString().getBytes("UTF-8");
                    c.getOutputStream().write(data);

                    int code = c.getResponseCode();
                    cb.onResult(code >= 200 && code < 300, code >= 200 && code < 300 ? null : "HTTP " + code);
                } catch (Exception e) {
                    cb.onResult(false, e.getMessage());
                }
            }
        }).start();
    }

    public static void fetchSuggestions(final FetchCallback cb) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    URL url = new URL(BASE + "/rest/v1/suggestions?select=item_name,created_at&order=created_at.desc");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setRequestMethod("GET");
                    c.setRequestProperty("apikey", KEY);
                    c.setRequestProperty("Authorization", "Bearer " + KEY);
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);

                    int code = c.getResponseCode();
                    if (code != 200) { cb.onResult(null, "HTTP " + code); return; }

                    BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);

                    JSONArray arr = new JSONArray(sb.toString());
                    List<String> items = new ArrayList<String>();
                    for (int i = 0; i < arr.length(); i++) {
                        items.add(arr.getJSONObject(i).getString("item_name"));
                    }
                    cb.onResult(items, null);
                } catch (Exception e) {
                    cb.onResult(null, e.getMessage());
                }
            }
        }).start();
    }
}
