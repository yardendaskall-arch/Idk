package com.exeopener;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
        // Temp file for EXE — avoids loading it into RAM
        File exeTemp = new File(context.getCacheDir(), "payload_tmp.exe");

        try {
            log("Output: " + apkFile.getName());

            // ---- 1. Load template entries (small — DEX + manifest + resources) ----
            log("Loading stub template...");
            Map<String, byte[]> entries = new LinkedHashMap<>();
            try (InputStream asset = context.getAssets().open("stub_template.apk");
                 ZipInputStream zis = new ZipInputStream(asset)) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    String name = ze.getName();
                    if (!name.startsWith("META-INF/")) {
                        byte[] data = readFully(zis);
                        entries.put(name, data);
                        log("  template: " + name + " (" + data.length + " B)");
                    }
                    zis.closeEntry();
                }
            }

            // ---- 2. Stream EXE to temp file, computing SHA-256 on the fly ----
            log("Buffering EXE to disk...");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long exeSize;
            try (DigestOutputStream dos =
                         new DigestOutputStream(new FileOutputStream(exeTemp), md)) {
                byte[] buf = new byte[65536];
                int n;
                long count = 0;
                while ((n = exeStream.read(buf)) != -1) {
                    dos.write(buf, 0, n);
                    count += n;
                }
                exeSize = count;
            }
            byte[] exeDigest = md.digest();
            log("  EXE size: " + exeSize + " bytes");

            // ---- 3. Sign + write final APK ----
            log("Signing APK (V1 JAR)...");
            try (FileOutputStream fos = new FileOutputStream(apkFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, 1 << 16);
                 ZipOutputStream zos = new ZipOutputStream(bos)) {

                new JarSigner(entries, exeTemp, exeDigest).sign(zos);
                zos.finish();
            }

            log("Done — " + apkFile.length() + " bytes");
            return apkFile;

        } finally {
            //noinspection ResultOfMethodCallIgnored
            exeTemp.delete();
        }
    }

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
