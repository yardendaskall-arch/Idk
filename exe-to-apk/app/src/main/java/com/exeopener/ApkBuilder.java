package com.exeopener;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Builds an installable wrapper APK around a Windows EXE.
 *
 * Strategy:
 *   1. Open stub_template.apk (pre-built by the :stub-launcher Gradle module and
 *      bundled in this app's assets). It contains a proper binary AndroidManifest.xml,
 *      a compiled classes.dex with the Wine-launcher Activity, and all required
 *      resources — everything Android needs to parse and install the APK.
 *   2. Copy every entry from the template EXCEPT existing META-INF signing entries
 *      (the template's debug signature is no longer valid once we add a new entry).
 *   3. Append assets/payload.exe — the original Windows EXE.
 *   4. Re-sign the resulting ZIP with a fresh V1 (JAR) signature via JarSigner,
 *      so Android accepts the package.
 *
 * The output APK targets SDK 28 (set in the stub-launcher module), which keeps
 * V1-only JAR signing valid on all Android versions including Android 11+.
 */
public class ApkBuilder {

    public interface LogListener {
        void log(String message);
    }

    private final Context context;
    private final InputStream exeStream;
    private final String appName;
    private LogListener logListener;

    public ApkBuilder(Context context, InputStream exeStream, String appName) {
        this.context   = context;
        this.exeStream = exeStream;
        this.appName   = sanitize(appName);
    }

    public void setLogListener(LogListener listener) {
        this.logListener = listener;
    }

    private void log(String msg) {
        if (logListener != null) logListener.log(msg);
    }

    public File build() throws Exception {
        File outDir = context.getExternalFilesDir("apk-output");
        if (outDir == null) outDir = new File(context.getFilesDir(), "apk-output");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();

        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File apkFile = new File(outDir, appName + "_" + ts + ".apk");

        log("Output: " + apkFile.getName());

        // ---- 1. Load all entries from the stub template APK ----
        log("Loading stub template...");
        // Ordered map: entry name → raw bytes (excludes META-INF signing entries)
        Map<String, byte[]> entries = new LinkedHashMap<>();

        try (InputStream assetStream = context.getAssets().open("stub_template.apk");
             ZipInputStream zis = new ZipInputStream(assetStream)) {

            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                String name = ze.getName();
                // Drop the existing debug signature — it becomes invalid once we add the EXE
                if (name.startsWith("META-INF/")) {
                    zis.closeEntry();
                    continue;
                }
                byte[] data = readFully(zis);
                entries.put(name, data);
                log("  template: " + name + " (" + data.length + " B)");
                zis.closeEntry();
            }
        }

        // ---- 2. Add the EXE into assets/ ----
        log("Reading EXE...");
        byte[] exeBytes = readFully(exeStream);
        entries.put("assets/payload.exe", exeBytes);
        log("  EXE size: " + exeBytes.length + " bytes");

        // ---- 3. Sign + write final APK ----
        log("Signing APK (V1 JAR)...");
        try (FileOutputStream fos = new FileOutputStream(apkFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 1 << 16);
             ZipOutputStream zos = new ZipOutputStream(bos)) {

            new JarSigner(entries).sign(zos);
            zos.finish();
        }

        log("Done — " + apkFile.length() + " bytes");
        log("Install with: adb install \"" + apkFile.getAbsolutePath() + "\"");
        return apkFile;
    }

    // ---- helpers ----

    private static byte[] readFully(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "app";
        return s.replaceAll("[^a-z0-9_]", "_").toLowerCase(Locale.US);
    }
}
