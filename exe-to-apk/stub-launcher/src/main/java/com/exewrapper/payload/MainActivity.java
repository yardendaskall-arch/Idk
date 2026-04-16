package com.exewrapper.payload;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends Activity {
    private static final String TAG = "ExeWrapper";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        File exeFile = new File(getFilesDir(), "payload.exe");

        try (InputStream in = getAssets().open("payload.exe");
             FileOutputStream out = new FileOutputStream(exeFile)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (IOException e) {
            showDialog("Could not extract EXE: " + e.getMessage());
            return;
        }

        //noinspection ResultOfMethodCallIgnored
        exeFile.setExecutable(true);

        // Try known Wine / Winlator locations
        String[] wines = {
            "/data/data/com.winlator/files/wine/bin/wine64",
            "/data/data/com.winlator/files/wine/bin/wine",
            "/data/data/org.winehq.wine/files/wine/bin/wine",
            "wine"
        };

        for (String wine : wines) {
            if (wine.startsWith("/") && !new File(wine).exists()) continue;
            try {
                new ProcessBuilder(wine, exeFile.getAbsolutePath())
                        .directory(getFilesDir())
                        .redirectErrorStream(true)
                        .start();
                Log.i(TAG, "Launched via: " + wine);
                return;
            } catch (IOException e) {
                Log.w(TAG, "Wine attempt failed (" + wine + "): " + e.getMessage());
            }
        }

        showDialog("Wine for Android not found.\n\n"
                + "To run this Windows EXE install one of:\n"
                + "  • Winlator (recommended for games)\n"
                + "  • Wine for Android\n\n"
                + "Search for them on GitHub.");
    }

    private void showDialog(String msg) {
        new AlertDialog.Builder(this)
                .setTitle("EXE Wrapper — Wine Required")
                .setMessage(msg)
                .setPositiveButton("OK", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }
}
