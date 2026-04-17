package com.exewrapper.payload;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class MainActivity extends Activity {

    private TextView   tvTitle;
    private TextView   tvStatus;
    private ProgressBar progressBar;
    private Button     btnRetry;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvTitle     = findViewById(R.id.tv_title);
        tvStatus    = findViewById(R.id.tv_status);
        progressBar = findViewById(R.id.progress_bar);
        btnRetry    = findViewById(R.id.btn_retry);

        btnRetry.setVisibility(View.GONE);
        btnRetry.setOnClickListener(v -> go());

        go();
    }

    private void go() {
        btnRetry.setVisibility(View.GONE);
        if (WineManager.isReady(this)) {
            runExe();
        } else {
            downloadWine();
        }
    }

    // ── Wine download ─────────────────────────────────────────────────────────

    private void downloadWine() {
        tvTitle.setText("First-time setup");
        setStatus("Preparing Wine runtime…", -1);

        new Thread(() -> {
            try {
                WineManager.setup(this, (msg, pct) -> ui.post(() -> {
                    tvStatus.setText(msg);
                    if (pct < 0) {
                        progressBar.setIndeterminate(true);
                    } else {
                        progressBar.setIndeterminate(false);
                        progressBar.setProgress(pct);
                    }
                }));
                ui.post(this::runExe);
            } catch (Throwable t) {
                ui.post(() -> {
                    setStatus("Setup failed:\n" + t.getMessage(), -1);
                    progressBar.setVisibility(View.GONE);
                    btnRetry.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    // ── EXE execution ─────────────────────────────────────────────────────────

    private void runExe() {
        tvTitle.setText("Launching…");
        setStatus("Extracting EXE…", -1);

        File exeFile = new File(getFilesDir(), "payload.exe");
        try (InputStream in  = getAssets().open("payload.exe");
             FileOutputStream out = new FileOutputStream(exeFile)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (IOException e) {
            setStatus("Extraction failed: " + e.getMessage(), -1);
            progressBar.setVisibility(View.GONE);
            btnRetry.setVisibility(View.VISIBLE);
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        exeFile.setExecutable(true);

        setStatus("Starting via Wine…", -1);

        new Thread(() -> {
            try {
                Process proc = WineManager.launch(this, exeFile);

                // Drain stdout/stderr so the process doesn't block
                new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(proc.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            // optionally show last line in UI
                        }
                    } catch (IOException ignored) {}
                }).start();

                ui.post(() -> {
                    tvTitle.setText("Running");
                    setStatus("Wine is running your EXE.\nThis window can stay in the background.", -1);
                    progressBar.setVisibility(View.GONE);
                });

                int code = proc.waitFor();
                ui.post(() -> setStatus("EXE finished (exit " + code + ").", -1));

            } catch (Exception e) {
                ui.post(() -> {
                    setStatus("Launch failed:\n" + e.getMessage(), -1);
                    progressBar.setVisibility(View.GONE);
                    btnRetry.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void setStatus(String msg, int pct) {
        tvStatus.setText(msg);
        progressBar.setVisibility(View.VISIBLE);
        if (pct < 0) {
            progressBar.setIndeterminate(true);
        } else {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(pct);
        }
    }
}
