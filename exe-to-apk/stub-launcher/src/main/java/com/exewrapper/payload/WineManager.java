package com.exewrapper.payload;

import android.content.Context;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads, extracts, and runs a self-contained Wine + Box64 runtime.
 *
 * On first run it fetches the latest Winlator release from GitHub, picks the
 * wine-aarch64 and box64 archives, streams them to the app's private files dir,
 * and extracts them.  Subsequent launches skip straight to execution.
 *
 * Architecture used:
 *   Box64 (x86_64 → ARM64 translator) + Wine ARM64 → runs most Windows EXEs
 */
public class WineManager {

    private static final String TAG = "WineManager";

    // Winlator GitHub API — returns the latest release JSON including asset download URLs.
    // Winlator bundles pre-built Wine + Box64 + Box86 for Android.
    private static final String RELEASES_API =
            "https://api.github.com/repos/brunodev85/winlator/releases/latest";

    public interface Progress {
        /** pct = 0-100, or -1 for indeterminate */
        void update(String message, int pct);
    }

    // ── State queries ────────────────────────────────────────────────────────

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
        return new File(runtimeDir(ctx), "bin/wine");
    }

    private static File box64Exe(Context ctx) {
        return new File(runtimeDir(ctx), "bin/box64");
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    public static void setup(Context ctx, Progress p) throws Exception {
        p.update("Fetching latest Winlator release…", -1);
        String json = httpGet(RELEASES_API);
        JSONObject release = new JSONObject(json);
        JSONArray assets = release.getJSONArray("assets");

        String wineUrl = null, box64Url = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.getString("name").toLowerCase();
            String url  = a.getString("browser_download_url");
            if (name.contains("wine") && (name.contains("aarch64") || name.contains("arm64"))) {
                wineUrl = url;
            } else if (name.contains("box64") && !name.contains("box86")) {
                box64Url = url;
            }
        }
        if (wineUrl == null) throw new IOException("Wine archive not found in Winlator release");

        File rtDir = runtimeDir(ctx);

        // Download + extract Box64 first (smaller)
        if (box64Url != null) {
            p.update("Downloading Box64…", 0);
            File tmp = new File(ctx.getCacheDir(), "box64.dl");
            streamDownload(box64Url, tmp, pct -> p.update("Box64: " + pct + "%", pct / 4));
            p.update("Extracting Box64…", 25);
            extract(tmp, rtDir);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }

        // Download + extract Wine (larger)
        p.update("Downloading Wine runtime…", 25);
        File tmp = new File(ctx.getCacheDir(), "wine.dl");
        streamDownload(wineUrl, tmp, pct -> p.update("Wine: " + pct + "%", 25 + pct * 3 / 4));
        p.update("Extracting Wine runtime…", 90);
        extract(tmp, rtDir);
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();

        // Make everything executable
        setExecutable(rtDir);
        p.update("Wine runtime ready.", 100);
    }

    // ── Launch ───────────────────────────────────────────────────────────────

    public static Process launch(Context ctx, File exeFile) throws Exception {
        File rtDir  = runtimeDir(ctx);
        File wine   = wineExe(ctx);
        File box64  = box64Exe(ctx);
        File prefix = new File(ctx.getFilesDir(), "wineprefix");
        //noinspection ResultOfMethodCallIgnored
        prefix.mkdirs();

        String lib = rtDir.getAbsolutePath() + "/lib"
                + ":" + rtDir.getAbsolutePath() + "/lib/wine/x86_64-unix"
                + ":" + rtDir.getAbsolutePath() + "/lib/wine/i386-unix";

        List<String> cmd = new ArrayList<>();
        if (box64.exists()) cmd.add(box64.getAbsolutePath());
        cmd.add(wine.getAbsolutePath());
        cmd.add(exeFile.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(ctx.getFilesDir());
        pb.redirectErrorStream(true);

        java.util.Map<String, String> env = pb.environment();
        env.put("WINEPREFIX",           prefix.getAbsolutePath());
        env.put("WINELOADER",           wine.getAbsolutePath());
        env.put("WINEDLLPATH",          rtDir + "/lib/wine");
        env.put("LD_LIBRARY_PATH",      lib);
        env.put("BOX64_PATH",           rtDir + "/bin");
        env.put("BOX64_LD_LIBRARY_PATH", lib);
        env.put("DISPLAY",              ":0");

        return pb.start();
    }

    // ── Extraction ───────────────────────────────────────────────────────────

    private static void extract(File archive, File dest) throws Exception {
        String n = archive.getName().toLowerCase();
        if (n.endsWith(".zip")) {
            extractZip(archive, dest);
        } else {
            extractTarXz(archive, dest); // .tar.xz, .tar.gz caught by commons-compress
        }
    }

    private static void extractZip(File archive, File dest) throws IOException {
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
    }

    private static void extractTarXz(File archive, File dest) throws Exception {
        try (FileInputStream fis      = new FileInputStream(archive);
             BufferedInputStream bis  = new BufferedInputStream(fis);
             XZCompressorInputStream xz = new XZCompressorInputStream(bis);
             TarArchiveInputStream tar  = new TarArchiveInputStream(xz)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                File out = new File(dest, entry.getName());
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    out.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        pipe(tar, fos);
                    }
                }
            }
        }
    }

    // ── Network ──────────────────────────────────────────────────────────────

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

    private static void streamDownload(String url, File dest, Progress p) throws Exception {
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
                if (total > 0) p.update("", (int)(done * 100 / total));
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "ExeToApk/1.0");
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        c.setInstanceFollowRedirects(true);
        // GitHub API requires Accept header
        c.setRequestProperty("Accept", "application/vnd.github+json");
        return c;
    }

    // ── Misc ─────────────────────────────────────────────────────────────────

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
