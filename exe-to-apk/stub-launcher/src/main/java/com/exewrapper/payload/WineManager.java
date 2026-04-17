package com.exewrapper.payload;

import android.content.Context;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads and extracts the Winlator runtime, then launches Windows EXEs via Wine + Box64.
 *
 * Winlator's architecture (discovered from source):
 *   - box64  : ARM64 native binary compiled against Android Bionic  → no glibc needed
 *   - wine   : x86_64 Linux binary (glibc)  → runs inside box64's x86_64 emulator
 *   - libs   : x86_64 glibc + wine libs at  lib/x86_64-linux-gnu/  inside the rootfs
 *
 * Both are bundled inside the Winlator APK as  assets/rootfs.tzst  (tar + Zstandard).
 * On first run we download that APK, chain-extract rootfs.tzst in-memory, and unpack it.
 */
public class WineManager {

    private static final String TAG = "WineManager";

    // Fetch the 5 most-recent releases to get the Winlator APK download URL.
    private static final String RELEASES_LIST_API =
            "https://api.github.com/repos/brunodev85/winlator/releases?per_page=5";

    // Entry name of the Linux rootfs inside the Winlator APK.
    private static final String ROOTFS_ASSET = "assets/rootfs.tzst";

    // Paths *inside* the extracted rootfs (relative to runtimeDir):
    //   box64 → ARM64 native, speaks Android Bionic
    //   wine  → x86_64 Linux ELF, emulated by box64
    private static final String BOX64_REL = "usr/local/bin/box64";
    private static final String WINE_REL  = "opt/wine/bin/wine";

    public interface Progress {
        /** pct = 0-100, or -1 for indeterminate */
        void update(String message, int pct);
    }

    private interface DownloadCb {
        void onPct(int pct);
    }

    // ── State queries ─────────────────────────────────────────────────────────

    public static boolean isReady(Context ctx) {
        File wine = wineExe(ctx);
        return wine.exists() && wine.canExecute();
    }

    public static File runtimeDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "wine_rt");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File wineExe(Context ctx) {
        return new File(runtimeDir(ctx), WINE_REL);
    }

    private static File box64Exe(Context ctx) {
        return new File(runtimeDir(ctx), BOX64_REL);
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    public static void setup(Context ctx, Progress p) throws Exception {
        p.update("Finding Winlator release…", -1);
        String json = httpGet(RELEASES_LIST_API);
        JSONArray releases = new JSONArray(json);

        String apkUrl = null;
        outer:
        for (int r = 0; r < releases.length(); r++) {
            JSONArray assets = releases.getJSONObject(r).getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.getString("name").toLowerCase();
                if (name.endsWith(".apk") && name.contains("winlator")) {
                    apkUrl = a.getString("browser_download_url");
                    break outer;
                }
            }
        }
        if (apkUrl == null) throw new IOException("Winlator APK not found in releases");

        // Download APK (contains assets/rootfs.tzst with wine + box64 + x86_64 libs).
        p.update("Downloading Winlator (first-time only — may take several minutes)…", 0);
        File apkTmp = new File(ctx.getCacheDir(), "winlator.apk");
        try {
            streamDownload(apkUrl, apkTmp,
                    (int pct) -> p.update("Downloading Winlator: " + pct + "%", pct * 7 / 10));

            // Chain-extract:  APK (ZIP) → rootfs.tzst entry → Zstd decomp → TAR
            p.update("Installing Wine runtime…", 70);
            extractRootfsFromApk(apkTmp, runtimeDir(ctx), p);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            apkTmp.delete();
        }

        new File(runtimeDir(ctx), "tmp").mkdirs();
        setExecutable(runtimeDir(ctx));
        p.update("Wine runtime ready.", 100);
    }

    /**
     * Opens the APK as a ZIP, finds assets/rootfs.tzst, then pipes it through
     * ZstdCompressorInputStream → TarArchiveInputStream directly — no intermediate file.
     */
    private static void extractRootfsFromApk(File apk, File dest, Progress p) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(apk), 131072))) {
            ZipEntry ze;
            while ((ze = zip.getNextEntry()) != null) {
                if (ROOTFS_ASSET.equals(ze.getName())) {
                    p.update("Unpacking Wine rootfs (this takes a while)…", 75);
                    // Shield prevents ZstdCompressorInputStream from closing the ZipInputStream.
                    InputStream shielded = new FilterInputStream(zip) {
                        @Override public void close() { /* intentionally empty */ }
                    };
                    try (ZstdCompressorInputStream zstd = new ZstdCompressorInputStream(shielded);
                         TarArchiveInputStream tar = new TarArchiveInputStream(zstd)) {
                        extractTar(tar, dest);
                    }
                    p.update("Extraction complete.", 95);
                    return;
                }
                zip.closeEntry();
            }
        }
        throw new IOException(ROOTFS_ASSET + " not found inside Winlator APK.\n"
                + "The APK structure may have changed.");
    }

    // ── Launch ────────────────────────────────────────────────────────────────

    public static Process launch(Context ctx, File exeFile) throws Exception {
        File rt     = runtimeDir(ctx);
        File box64  = box64Exe(ctx);
        File wine   = wineExe(ctx);
        File prefix = new File(ctx.getFilesDir(), "wineprefix");
        //noinspection ResultOfMethodCallIgnored
        prefix.mkdirs();

        List<String> cmd = new ArrayList<>();
        // box64 is ARM64 native (Bionic) — it emulates wine which is x86_64 glibc
        if (box64.exists()) cmd.add(box64.getAbsolutePath());
        cmd.add(wine.getAbsolutePath());
        cmd.add(exeFile.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(ctx.getFilesDir());
        pb.redirectErrorStream(true);

        java.util.Map<String, String> env = pb.environment();
        env.put("HOME",    rt + "/root");
        env.put("TMPDIR",  rt + "/tmp");
        env.put("DISPLAY", ":0");
        env.put("PATH",
                rt + "/" + WINE_REL.replace("/bin/wine", "/bin") + ":"
                + rt + "/usr/local/bin:" + rt + "/usr/bin");

        env.put("WINEPREFIX",  prefix.getAbsolutePath());
        env.put("WINELOADER",  wine.getAbsolutePath());
        env.put("WINEDLLPATH", rt + "/opt/wine/lib/wine");

        // ARM64 native libs (for box64 itself and any native helpers)
        env.put("LD_LIBRARY_PATH", rt + "/lib:" + rt + "/lib/aarch64-linux-gnu");

        // x86_64 glibc + wine libs — box64 maps these into the emulated x86_64 process
        env.put("BOX64_LD_LIBRARY_PATH",
                rt + "/lib/x86_64-linux-gnu:" + rt + "/opt/wine/lib");

        env.put("BOX64_PATH",     rt + "/usr/local/bin");
        env.put("BOX64_DYNAREC",  "1");
        env.put("BOX64_LOG",      "0");
        env.put("BOX64_NOBANNER", "1");

        return pb.start();
    }

    // ── Extraction helpers ────────────────────────────────────────────────────

    private static void extractTar(TarArchiveInputStream tar, File dest) throws Exception {
        TarArchiveEntry entry;
        while ((entry = tar.getNextTarEntry()) != null) {
            File out = new File(dest, entry.getName());
            if (entry.isSymbolicLink()) {
                out.getParentFile().mkdirs();
                try {
                    java.nio.file.Files.deleteIfExists(out.toPath());
                    java.nio.file.Files.createSymbolicLink(
                            out.toPath(),
                            java.nio.file.Paths.get(entry.getLinkName()));
                } catch (Exception ignored) {
                    Log.w(TAG, "Symlink skipped: " + entry.getName());
                }
            } else if (entry.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                out.mkdirs();
            } else if (tar.canReadEntryData(entry)) {
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    pipe(tar, fos);
                }
            }
        }
    }

    private static void extract(File archive, File dest) throws Exception {
        String n = archive.getName().toLowerCase();
        if (n.endsWith(".zip")) {
            try (ZipInputStream zis = new ZipInputStream(
                    new BufferedInputStream(new FileInputStream(archive)))) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    File out = new File(dest, ze.getName());
                    if (ze.isDirectory()) {
                        //noinspection ResultOfMethodCallIgnored
                        out.mkdirs();
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        out.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            pipe(zis, fos);
                        }
                    }
                    zis.closeEntry();
                }
            }
        } else if (n.endsWith(".tzst") || n.endsWith(".tar.zst")) {
            try (FileInputStream fis = new FileInputStream(archive);
                 ZstdCompressorInputStream zstd = new ZstdCompressorInputStream(
                         new BufferedInputStream(fis));
                 TarArchiveInputStream tar = new TarArchiveInputStream(zstd)) {
                extractTar(tar, dest);
            }
        } else {
            try (FileInputStream fis = new FileInputStream(archive);
                 XZCompressorInputStream xz = new XZCompressorInputStream(
                         new BufferedInputStream(fis));
                 TarArchiveInputStream tar = new TarArchiveInputStream(xz)) {
                extractTar(tar, dest);
            }
        }
    }

    // ── Network ───────────────────────────────────────────────────────────────

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pipe(in, baos);
            return baos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void streamDownload(String url, File dest, DownloadCb cb) throws Exception {
        HttpURLConnection c = open(url);
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            long done = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) cb.onPct((int) (done * 100 / total));
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "ExeToApk/1.0");
        c.setConnectTimeout(30_000);
        c.setReadTimeout(120_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        return c;
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    private static void pipe(InputStream in, java.io.OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    private static void setExecutable(File f) {
        if (f == null || !f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.setExecutable(true);
        //noinspection ResultOfMethodCallIgnored
        f.setReadable(true);
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) setExecutable(c);
        }
    }
}
