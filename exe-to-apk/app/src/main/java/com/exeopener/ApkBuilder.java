package com.exeopener;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a wrapper APK (ZIP) around a Windows EXE file.
 *
 * The resulting APK structure:
 *   AndroidManifest.xml   – binary-encoded (stub, pre-encoded bytes below)
 *   assets/payload.exe    – the original EXE
 *   assets/run_wine.sh    – shell script to launch via Wine for Android / Termux
 *   META-INF/MANIFEST.MF  – placeholder (APK is unsigned; system installer will reject it
 *                           unless signed separately with apksigner / jarsigner)
 *   README.txt            – usage instructions
 *
 * NOTE: Android requires APKs to be signed before installation.
 * This builder outputs an *unsigned* APK that must be signed with
 * apksigner (Android SDK) or any compatible tool before it can be installed.
 * The "Install" button in the app attempts installation anyway so you can
 * sign it externally and then install normally.
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
        this.context = context;
        this.exeStream = exeStream;
        this.appName = sanitize(appName);
    }

    public void setLogListener(LogListener listener) {
        this.logListener = listener;
    }

    private void log(String msg) {
        if (logListener != null) logListener.log(msg);
    }

    /**
     * Builds the wrapper APK and returns the output File.
     * Runs on a background thread (caller is responsible).
     */
    public File build() throws IOException {
        File outDir = context.getExternalFilesDir("apk-output");
        if (outDir == null) outDir = new File(context.getFilesDir(), "apk-output");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File apkFile = new File(outDir, appName + "_" + timestamp + ".apk");

        log("Output dir  : " + outDir.getAbsolutePath());
        log("APK filename: " + apkFile.getName());
        log("Building ZIP structure...");

        try (FileOutputStream fos = new FileOutputStream(apkFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ZipOutputStream zos = new ZipOutputStream(bos)) {

            // Default method is DEFLATED — individual entries override as needed

            // 1. AndroidManifest.xml (binary XML stub)
            log("Writing AndroidManifest.xml ...");
            byte[] manifestBytes = buildBinaryManifest(appName);
            writeStoredEntry(zos, "AndroidManifest.xml", manifestBytes);

            // 2. assets/payload.exe
            log("Writing assets/payload.exe ...");
            writeStreamedEntry(zos, "assets/payload.exe", exeStream);

            // 3. assets/run_wine.sh
            log("Writing assets/run_wine.sh ...");
            byte[] scriptBytes = buildWineScript(appName).getBytes(StandardCharsets.UTF_8);
            writeStoredEntry(zos, "assets/run_wine.sh", scriptBytes);

            // 4. assets/README.txt
            log("Writing README ...");
            byte[] readmeBytes = buildReadme(appName).getBytes(StandardCharsets.UTF_8);
            writeStoredEntry(zos, "assets/README.txt", readmeBytes);

            // 5. META-INF/MANIFEST.MF
            log("Writing META-INF/MANIFEST.MF ...");
            byte[] mfBytes = buildManifestMf().getBytes(StandardCharsets.UTF_8);
            writeStoredEntry(zos, "META-INF/MANIFEST.MF", mfBytes);

            zos.finish();
        }

        log("APK written (" + apkFile.length() + " bytes).");
        log("NOTE: This APK is unsigned. Sign it with apksigner before installing.");
        return apkFile;
    }

    // ---- ZIP helpers ----

    private void writeStoredEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(data);

        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(crc.getValue());

        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private void writeStreamedEntry(ZipOutputStream zos, String name, InputStream is) throws IOException {
        // Use DEFLATED so we can stream without loading the whole file into RAM.
        // STORED would require CRC + size upfront, forcing a full in-memory buffer — bad for large EXEs.
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.DEFLATED);
        zos.putNextEntry(entry);
        byte[] buf = new byte[65536]; // 64 KB transfer buffer
        int read;
        while ((read = is.read(buf)) != -1) {
            zos.write(buf, 0, read);
        }
        zos.closeEntry();
    }

    // ---- Content builders ----

    /**
     * Returns a minimal Android binary XML (AXML) for the wrapper manifest.
     *
     * Full AXML encoding is complex; here we produce a well-formed text-XML
     * version which, while not a real binary manifest, serves as documentation
     * and can be re-encoded properly with aapt2 when doing a full build.
     *
     * For a truly installable APK you would need:
     *   1. Proper binary-encoded AXML (Android Binary XML format)
     *   2. APK signing (V1 JAR signature + V2/V3 APK signature)
     *   3. A compiled DEX stub that invokes Wine/Termux
     *
     * This project generates the wrapper structure and stub sources. To produce
     * a fully-installable APK, build this project with Android Studio / Gradle
     * and target the `assembleRelease` task, then sign with apksigner.
     */
    private byte[] buildBinaryManifest(String name) {
        String packageId = "com.exewrapper." + name;
        String xml =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<!-- STUB: re-encode with aapt2 for a real installable APK -->\n" +
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
            "    package=\"" + packageId + "\"\n" +
            "    android:versionCode=\"1\"\n" +
            "    android:versionName=\"1.0\">\n" +
            "\n" +
            "    <uses-sdk android:minSdkVersion=\"24\" android:targetSdkVersion=\"34\" />\n" +
            "\n" +
            "    <application\n" +
            "        android:label=\"" + appName + " (Wine Wrapper)\"\n" +
            "        android:icon=\"@android:drawable/sym_def_app_icon\"\n" +
            "        android:allowBackup=\"false\">\n" +
            "\n" +
            "        <activity\n" +
            "            android:name=\".LauncherActivity\"\n" +
            "            android:exported=\"true\">\n" +
            "            <intent-filter>\n" +
            "                <action android:name=\"android.intent.action.MAIN\" />\n" +
            "                <category android:name=\"android.intent.category.LAUNCHER\" />\n" +
            "            </intent-filter>\n" +
            "        </activity>\n" +
            "\n" +
            "    </application>\n" +
            "</manifest>\n";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private String buildWineScript(String name) {
        return "#!/system/bin/sh\n" +
               "#\n" +
               "# run_wine.sh — launches " + name + ".exe via Wine for Android\n" +
               "#\n" +
               "# This script is extracted by the LauncherActivity stub and executed\n" +
               "# inside the app's data directory.\n" +
               "#\n" +
               "# Requirements on the device:\n" +
               "#   Option A: Wine for Android  (https://www.winehq.org/)\n" +
               "#   Option B: Winlator           (https://github.com/brunodev85/winlator)\n" +
               "#   Option C: Termux + Wine      (pkg install wine)\n" +
               "#\n" +
               "\n" +
               "EXE=\"$(dirname \"$0\")/" + name + ".exe\"\n" +
               "\n" +
               "if command -v wine >/dev/null 2>&1; then\n" +
               "    wine \"$EXE\"\n" +
               "elif [ -f /data/data/com.winlator/files/wine/bin/wine ]; then\n" +
               "    /data/data/com.winlator/files/wine/bin/wine \"$EXE\"\n" +
               "elif [ -f /data/data/org.winehq.wine/files/wine/bin/wine ]; then\n" +
               "    /data/data/org.winehq.wine/files/wine/bin/wine \"$EXE\"\n" +
               "else\n" +
               "    echo \"ERROR: Wine not found. Install Wine for Android or Winlator.\"\n" +
               "    exit 1\n" +
               "fi\n";
    }

    private String buildReadme(String name) {
        return "EXE-to-APK Wrapper\n" +
               "==================\n" +
               "\n" +
               "Wrapped EXE : " + name + ".exe\n" +
               "Built by    : ExeToApk for Android\n" +
               "\n" +
               "CONTENTS\n" +
               "--------\n" +
               "  assets/payload.exe   — the original Windows executable\n" +
               "  assets/run_wine.sh   — shell script to run the EXE via Wine\n" +
               "  AndroidManifest.xml  — stub manifest (needs aapt2 re-encoding)\n" +
               "\n" +
               "HOW TO GET A FULLY INSTALLABLE APK\n" +
               "-----------------------------------\n" +
               "This wrapper APK is a skeleton. To make it installable:\n" +
               "\n" +
               "1. Open this project in Android Studio.\n" +
               "2. Copy payload.exe into app/src/main/assets/\n" +
               "3. Run:  ./gradlew assembleDebug\n" +
               "4. Install:  adb install app/build/outputs/apk/debug/app-debug.apk\n" +
               "\n" +
               "REQUIREMENTS ON THE DEVICE\n" +
               "---------------------------\n" +
               "  • Wine for Android  — to execute x86/x64 Windows EXEs\n" +
               "  • Winlator          — full Wine + DirectX environment for games\n" +
               "\n" +
               "LIMITATIONS\n" +
               "-----------\n" +
               "  • Only x86 and x86-64 EXEs can run via Wine.\n" +
               "  • ARM-native Windows EXEs are not supported by Wine on Android.\n" +
               "  • Windows-specific APIs (DirectX 11/12, .NET 6+) have limited support.\n";
    }

    private String buildManifestMf() {
        return "Manifest-Version: 1.0\n" +
               "Created-By: ExeToApk\n" +
               "\n";
    }

    // ---- Utils ----

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "app";
        return s.replaceAll("[^a-z0-9_]", "_").toLowerCase(Locale.US);
    }
}
