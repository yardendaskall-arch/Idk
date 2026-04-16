package com.exeopener;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatus;
    private TextView tvExeInfo;
    private TextView tvLog;
    private Button btnPickExe;
    private Button btnConvert;
    private Button btnInstall;
    private ProgressBar progressBar;
    private LinearLayout layoutResult;

    private Uri selectedExeUri;
    private File generatedApk;

    private final ActivityResultLauncher<Intent> filePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                handleExeUri(result.getData().getData());
            }
        });

    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
            boolean allGranted = true;
            for (Boolean g : granted.values()) {
                if (!g) { allGranted = false; break; }
            }
            if (allGranted) {
                openFilePicker();
            } else {
                Toast.makeText(this, "Storage permission needed to read EXE files", Toast.LENGTH_LONG).show();
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus    = findViewById(R.id.tv_status);
        tvExeInfo   = findViewById(R.id.tv_exe_info);
        tvLog       = findViewById(R.id.tv_log);
        btnPickExe  = findViewById(R.id.btn_pick_exe);
        btnConvert  = findViewById(R.id.btn_convert);
        btnInstall  = findViewById(R.id.btn_install);
        progressBar = findViewById(R.id.progress_bar);
        layoutResult = findViewById(R.id.layout_result);

        btnConvert.setEnabled(false);
        btnInstall.setEnabled(false);
        layoutResult.setVisibility(View.GONE);

        btnPickExe.setOnClickListener(v -> requestPermissionsAndPick());
        btnConvert.setOnClickListener(v -> startConversion());
        btnInstall.setOnClickListener(v -> installGeneratedApk());

        // Handle incoming VIEW intent (opened from file manager)
        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            handleExeUri(intent.getData());
        }
    }

    private void requestPermissionsAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("To read EXE files, please grant 'All Files Access' permission.")
                    .setPositiveButton("Grant", (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            } else {
                openFilePicker();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                });
            } else {
                openFilePicker();
            }
        } else {
            openFilePicker();
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/octet-stream",
            "application/x-msdownload",
            "application/exe",
            "application/x-exe",
            "*/*"
        });
        filePickerLauncher.launch(Intent.createChooser(intent, "Select EXE file"));
    }

    private void handleExeUri(Uri uri) {
        selectedExeUri = uri;
        generatedApk = null;
        btnInstall.setEnabled(false);
        layoutResult.setVisibility(View.VISIBLE);
        tvLog.setText("");

        log("Loading EXE: " + uri.getLastPathSegment());
        tvStatus.setText("EXE loaded — ready to convert");

        new Thread(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                ExeAnalyzer analyzer = new ExeAnalyzer(is);
                ExeAnalyzer.ExeInfo info = analyzer.analyze();
                runOnUiThread(() -> {
                    tvExeInfo.setText(info.toDisplayString());
                    btnConvert.setEnabled(true);
                    log("PE analysis complete.");
                    log("Architecture : " + info.architecture);
                    log("Subsystem    : " + info.subsystem);
                    log("File size    : " + info.fileSize + " bytes");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvExeInfo.setText("Could not parse PE headers: " + e.getMessage());
                    // Still allow conversion attempt
                    btnConvert.setEnabled(true);
                    log("Warning: PE parse error — " + e.getMessage());
                });
            }
        }).start();
    }

    private void startConversion() {
        if (selectedExeUri == null) return;

        btnConvert.setEnabled(false);
        btnPickExe.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Converting...");
        log("Starting APK wrapper build...");

        new Thread(() -> {
            try (InputStream exeStream = getContentResolver().openInputStream(selectedExeUri)) {
                String baseName = getBaseFileName(selectedExeUri);
                ApkBuilder builder = new ApkBuilder(this, exeStream, baseName);
                builder.setLogListener(msg -> runOnUiThread(() -> log(msg)));
                generatedApk = builder.build();

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnPickExe.setEnabled(true);
                    tvStatus.setText("Done! APK created: " + generatedApk.getName());
                    log("Output APK: " + generatedApk.getAbsolutePath());
                    log("Size: " + generatedApk.length() + " bytes");
                    btnInstall.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnPickExe.setEnabled(true);
                    btnConvert.setEnabled(true);
                    tvStatus.setText("Conversion failed: " + e.getMessage());
                    log("ERROR: " + e.getMessage());
                });
            }
        }).start();
    }

    private void installGeneratedApk() {
        if (generatedApk == null || !generatedApk.exists()) {
            Toast.makeText(this, "APK file not found", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri apkUri = FileProvider.getUriForFile(this,
            getPackageName() + ".fileprovider", generatedApk);

        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(installIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String getBaseFileName(Uri uri) {
        String path = uri.getLastPathSegment();
        if (path == null) return "wrapped_exe";
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        int dot = path.lastIndexOf('.');
        if (dot >= 0) path = path.substring(0, dot);
        // Sanitize for use as package name fragment
        return path.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }

    private void log(String message) {
        String current = tvLog.getText().toString();
        tvLog.setText(current.isEmpty() ? message : current + "\n" + message);
    }
}
