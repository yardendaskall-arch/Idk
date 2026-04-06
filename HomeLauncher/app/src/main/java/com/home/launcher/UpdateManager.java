package com.home.launcher;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateManager {

    // Raw GitHub URLs — update these if you rename the branch/repo
    static final String APK_URL =
        "https://raw.githubusercontent.com/yardendaskall-arch/Idk/claude/custom-home-ui-app-ueSMq/HomeLauncher/HomeLauncher-debug.apk";
    static final String VERSION_URL =
        "https://raw.githubusercontent.com/yardendaskall-arch/Idk/claude/custom-home-ui-app-ueSMq/HomeLauncher/version.txt";

    static final int CURRENT_VERSION = 3;

    private final Activity activity;
    private final DownloadManager dm;
    private long downloadId = -1;
    private BroadcastReceiver downloadReceiver;

    public UpdateManager(Activity activity) {
        this.activity = activity;
        this.dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    /** Check version.txt on GitHub. Calls onResult(true) if update available, false if up to date, null on error. */
    public interface CheckCallback { void onResult(Boolean updateAvailable, int serverVersion); }

    public void checkForUpdate(final CheckCallback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(VERSION_URL).openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    int code = conn.getResponseCode();
                    if (code != 200) { post(cb, null, -1); return; }
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line = br.readLine();
                    br.close();
                    conn.disconnect();
                    int serverVer = Integer.parseInt(line.trim());
                    post(cb, serverVer > CURRENT_VERSION, serverVer);
                } catch (Exception e) {
                    post(cb, null, -1);
                }
            }
        }).start();
    }

    private void post(final CheckCallback cb, final Boolean result, final int ver) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { cb.onResult(result, ver); }
        });
    }

    /** Download the APK and trigger install when done. */
    public void downloadAndInstall() {
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(APK_URL));
        req.setTitle("Home Launcher Update");
        req.setDescription("Downloading new version...");
        req.setMimeType("application/vnd.android.package-archive");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "HomeLauncher-update.apk");

        downloadId = dm.enqueue(req);
        Toast.makeText(activity, "Downloading update...", Toast.LENGTH_SHORT).show();

        downloadReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;
                try { activity.unregisterReceiver(this); } catch (Exception ignored) {}
                downloadReceiver = null;
                checkDownloadResult(id);
            }
        };
        activity.registerReceiver(downloadReceiver,
            new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private void checkDownloadResult(long id) {
        DownloadManager.Query q = new DownloadManager.Query();
        q.setFilterById(id);
        android.database.Cursor c = dm.query(q);
        if (c == null) { showError(); return; }
        if (c.moveToFirst()) {
            int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                Uri apkUri = dm.getUriForDownloadedFile(id);
                install(apkUri);
            } else {
                showError();
            }
        }
        c.close();
    }

    private void install(Uri apkUri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "Could not open installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showError() {
        Toast.makeText(activity, "Update download failed. Check your connection.", Toast.LENGTH_LONG).show();
    }

    public void cleanup() {
        if (downloadReceiver != null) {
            try { activity.unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
            downloadReceiver = null;
        }
    }
}
