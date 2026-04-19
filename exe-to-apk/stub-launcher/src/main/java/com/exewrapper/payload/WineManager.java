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
 * Winlator's architecture (from source):
 *   box64  – ARM64 native binary, compiled against Android Bionic (no glibc needed).
 *   wine   – x86_64 Linux binary (glibc), emulated by box64's x86_64 translator.
 *   libs   – x86_64 glibc + wine libs live at  lib/x86_64-linux-gnu/  inside the rootfs.
 *
 * Everything is bundled in the Winlator APK as  assets/rootfs.tzst  (tar + Zstandard).
 * On first run we stream the APK directly: HTTP → ZipInputStream → ZstdCompressorInputStream
 * → TarArchiveInputStream → files on disk.  No 200–400 MB temp copy is written.
 */
public class WineManager {

    private static final String TAG = "WineManager";

    private static final String RELEASES_LIST_API =
            "https://api.github.com/repos/brunodev85/winlator/releases?per_page=5";

    // box64 is not bundled in the Winlator APK — it's a separately downloadable component.
    private static final String BOX64_INDEX_URL =
            "https://raw.githubusercontent.com/brunodev85/winlator/main/installable_components/box64/index.txt";
    private static final String BOX64_BASE_URL =
            "https://raw.githubusercontent.com/brunodev85/winlator/main/installable_components/box64/";

    // Entry name of the Linux rootfs inside the Winlator APK ZIP.
    private static final String ROOTFS_ASSET = "assets/rootfs.tzst";

    // Paths inside the extracted rootfs (relative to runtimeDir):
    private static final String BOX64_REL    = "usr/local/bin/box64";
    private static final String WINE_REL     = "opt/wine/bin/wine";
    // Winlator ships box64 as a Bionic-linked ARM64 native library in the APK.
    // We extract it here so it runs directly on Android without glibc/proot.
    private static final String BOX64_BIONIC = "box64";

    public interface Progress {
        /** pct = 0-100, or -1 for indeterminate */
        void update(String message, int pct);
    }

    private interface DownloadCb {
        void onPct(int pct);
    }

    // ── State queries ─────────────────────────────────────────────────────────

    public static boolean isReady(Context ctx) {
        File wine  = wineExe(ctx);
        File box64 = box64Exe(ctx);
        return wine.exists() && wine.canExecute()
                && box64.exists() && box64.canExecute();
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
        File rt = runtimeDir(ctx);
        // Prefer Bionic-linked box64 extracted from Winlator APK native libs.
        File bionic = new File(rt, BOX64_BIONIC);
        if (bionic.exists()) return bionic;
        // Search common rootfs paths (in case rootfs ships a Bionic-linked box64).
        for (String rel : new String[]{"usr/local/bin/box64", "usr/bin/box64", "bin/box64"}) {
            File f = new File(rt, rel);
            if (f.exists()) return f;
        }
        return new File(rt, BOX64_REL);
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
        if (apkUrl == null) throw new IOException("Winlator APK not found in any release");

        // Stream APK → ZIP → Zstd → TAR without writing the APK to disk.
        // This avoids needing 200-400 MB of temporary storage for the APK itself.
        p.update("Connecting to Winlator download…", 0);
        HttpURLConnection conn = open(apkUrl);
        long apkSize = conn.getContentLengthLong();

        boolean foundRootfs = false;
        boolean foundBox64  = false;
        List<String> arm64Libs = new ArrayList<>();
        try (InputStream http = conn.getInputStream()) {
            CountingInputStream counting = new CountingInputStream(http, apkSize,
                    pct -> p.update("Downloading: " + pct + "%", pct * 7 / 10));

            try (ZipInputStream zip = new ZipInputStream(
                    new BufferedInputStream(counting, 131072))) {
                ZipEntry ze;
                while ((ze = zip.getNextEntry()) != null) {
                    String name = ze.getName();
                    if (!foundRootfs && (name.equals(ROOTFS_ASSET) || name.endsWith("/rootfs.tzst"))) {
                        foundRootfs = true;
                        p.update("Installing Wine runtime (may take several minutes)…", 70);
                        // Shield prevents ZstdCompressorInputStream from closing the ZipInputStream.
                        InputStream shielded = new FilterInputStream(zip) {
                            @Override public void close() { /* intentionally empty */ }
                        };
                        try (ZstdCompressorInputStream zstd =
                                     new ZstdCompressorInputStream(shielded);
                             TarArchiveInputStream tar =
                                     new TarArchiveInputStream(zstd)) {
                            extractTar(tar, runtimeDir(ctx));
                        }
                        // Do NOT break — continue scanning for libbox64.so below.
                    } else if (!foundBox64 && name.toLowerCase().contains("box64")
                            && (name.contains("arm64") || name.contains("lib/"))) {
                        // Winlator packages box64 as a Bionic-linked ARM64 "library".
                        // Accept any arm64 entry whose name contains "box64".
                        foundBox64 = true;
                        p.update("Extracting box64 (" + name + ")…", 85);
                        File box64Out = new File(runtimeDir(ctx), BOX64_BIONIC);
                        //noinspection ResultOfMethodCallIgnored
                        box64Out.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(box64Out)) {
                            pipe(zip, fos);
                        }
                        //noinspection ResultOfMethodCallIgnored
                        box64Out.setExecutable(true);
                    } else {
                        // Collect ARM64 lib names for diagnostics.
                        if (name.contains("arm64") || name.startsWith("lib/")) {
                            arm64Libs.add(name.contains("/")
                                    ? name.substring(name.lastIndexOf('/') + 1)
                                    : name);
                        }
                        zip.closeEntry();
                    }
                    if (foundRootfs && foundBox64) break;
                }
            }
        } finally {
            conn.disconnect();
        }

        if (!foundRootfs) {
            throw new IOException(ROOTFS_ASSET
                    + " not found inside Winlator APK.\n"
                    + "The APK structure may have changed in a newer release.");
        }

        File wine = wineExe(ctx);
        if (!wine.exists()) {
            throw new IOException(
                    "Wine binary not found after extraction.\n"
                    + "Expected path: " + wine.getPath() + "\n"
                    + "The rootfs structure may differ from what was expected.");
        }

        //noinspection ResultOfMethodCallIgnored
        new File(runtimeDir(ctx), "tmp").mkdirs();
        setExecutable(runtimeDir(ctx));

        // box64 not in APK — check rootfs, then download it as a separate component.
        if (!foundBox64 && box64Exe(ctx).exists()) foundBox64 = true;

        if (!foundBox64) {
            p.update("Downloading box64 component…", 88);
            String index = httpGet(BOX64_INDEX_URL);
            String[] lines = index.trim().split("\\s+");
            String filename = lines[lines.length - 1].trim();
            p.update("Downloading " + filename + "…", 90);
            HttpURLConnection box64Conn = open(BOX64_BASE_URL + filename);
            try (InputStream box64Http = box64Conn.getInputStream();
                 ZstdCompressorInputStream zstd =
                         new ZstdCompressorInputStream(new BufferedInputStream(box64Http, 65536));
                 TarArchiveInputStream tar = new TarArchiveInputStream(zstd)) {
                extractTar(tar, runtimeDir(ctx));
            } finally {
                box64Conn.disconnect();
            }
            setExecutable(runtimeDir(ctx));
            foundBox64 = box64Exe(ctx).exists();
        }

        if (!foundBox64) {
            throw new IOException("box64 could not be found or downloaded.");
        }

        p.update("Wine runtime ready.", 100);
    }

    // ── Launch ────────────────────────────────────────────────────────────────

    public static Process launch(Context ctx, File exeFile) throws Exception {
        File rt    = runtimeDir(ctx);
        File box64 = box64Exe(ctx);
        File wine  = wineExe(ctx);
        File prefix = new File(ctx.getFilesDir(), "wineprefix");
        //noinspection ResultOfMethodCallIgnored
        prefix.mkdirs();

        // Clear Winlator's box64 rc files that hardcode Winlator-specific paths.
        // box64 loads /etc/box64.box64rc (system) before $HOME/.box64rc (user), and
        // BOX64_RCFILE only overrides the user rc, not the system one.
        // Both files in the Winlator rootfs contain TMPDIR=/data/data/com.winlator/...
        // which overrides our TMPDIR for every x86_64 process running under box64.
        for (String rcRel : new String[]{"etc/box64.box64rc", "root/.box64rc"}) {
            File rcFile = new File(rt, rcRel);
            if (rcFile.exists()) {
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(rcFile)) {
                    // truncate to empty — no overrides
                } catch (IOException ignored) {}
            }
        }

        // box64 is a Linux ARM64 glibc-linked binary (PT_INTERP=/lib/ld-linux-aarch64.so.1).
        // Android's kernel can't find that interpreter, so we invoke glibc's ld.so directly
        // — ld.so itself has no PT_INTERP and the kernel can exec it natively.
        // The rootfs may have /lib as a symlink to usr/lib (merged-usr Debian layout), so
        // we search recursively rather than assuming a single hardcoded path.
        File ldso = findLdso(rt);
        String arm64LibPath = ldso != null ? ldso.getParentFile().getAbsolutePath() : "";

        // box64 stores argv[0] as its own path for re-exec (core.c: box64path = argv[0]).
        // When wine64 calls execv(WINELOADER), box64 does execve(box64path, ...) — a real
        // kernel execve. The kernel fails because box64 is glibc-linked (PT_INTERP not found).
        // Fix: create a shell script wrapper and pass it as argv[0] via ld.so --argv0.
        // On re-exec, box64 execs the wrapper (a plain shell script the kernel can run);
        // the wrapper calls ld.so again, restoring the full chain transparently.
        File wrapper = null;
        if (ldso != null) {
            // Build extra arm64 lib paths (merged-usr layout may put libs under usr/lib/)
            File usrLibArm64 = new File(rt, "usr/lib/aarch64-linux-gnu");
            String fullArm64 = arm64LibPath
                    + (usrLibArm64.exists() ? ":" + usrLibArm64.getAbsolutePath() : "");
            wrapper = new File(ctx.getFilesDir(), "box64-wrap.sh");
            String script = "#!/system/bin/sh\n"
                    + "exec " + ldso.getAbsolutePath()
                    + " --library-path " + fullArm64
                    + " --argv0 \"$0\""
                    + " " + box64.getAbsolutePath()
                    + " \"$@\"\n";
            try (java.io.FileOutputStream ws = new java.io.FileOutputStream(wrapper)) {
                ws.write(script.getBytes("UTF-8"));
            }
            //noinspection ResultOfMethodCallIgnored
            wrapper.setExecutable(true);
        }

        List<String> cmd = new ArrayList<>();
        if (box64.exists()) {
            if (ldso != null && wrapper != null) {
                cmd.add(ldso.getAbsolutePath());
                cmd.add("--library-path");
                cmd.add(arm64LibPath);
                cmd.add("--argv0");
                cmd.add(wrapper.getAbsolutePath());
            }
            cmd.add(box64.getAbsolutePath());
        }
        // Run wine64 (x86_64 ELF) directly so box64 emulates it and intercepts Wine's
        // internal exec calls. Running the 'wine' shell script would cause native /bin/sh
        // to exec wine64 (x86_64) which the kernel rejects (ENOEXEC).
        File wine64 = new File(rt, "opt/wine/bin/wine64");
        File wineToRun = wine64.exists() ? wine64 : wine;
        cmd.add(wineToRun.getAbsolutePath());
        cmd.add(exeFile.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(ctx.getFilesDir());
        pb.redirectErrorStream(true);

        java.util.Map<String, String> env = pb.environment();
        env.put("HOME",    rt + "/root");
        env.put("TMPDIR",  rt + "/tmp");
        env.put("DISPLAY", ":0");
        env.put("PATH",
                rt + "/opt/wine/bin:" + rt + "/usr/local/bin:" + rt + "/usr/bin");

        env.put("WINEPREFIX",  prefix.getAbsolutePath());
        // WINELOADER must be wine64 (x86_64 ELF) — not the shell script, not the preloader.
        // The preloader does memory tricks that box64 can't accommodate; skipping it forces
        // wine64 to exec WINELOADER (wine64 itself), which box64 handles as a standard
        // in-process re-exec via the wrapper chain above.
        env.put("WINELOADER", wineToRun.getAbsolutePath());
        env.put("WINEDLLPATH", rt + "/opt/wine/lib/wine");

        // Do NOT set LD_LIBRARY_PATH: box64 is ARM64 Bionic and finds Android system
        // libs automatically. Linux rootfs glibc paths contain linker scripts (bad ELF
        // magic "/* G") that crash Android's linker when /bin/sh loads wine's shell wrapper.

        // x86_64 glibc + wine libs for box64 to satisfy Wine's dynamic deps.
        // Include both merged-usr (usr/lib/x86_64-linux-gnu) and legacy (/lib/x86_64-linux-gnu).
        env.put("BOX64_LD_LIBRARY_PATH",
                rt + "/lib/x86_64-linux-gnu:" + rt + "/usr/lib/x86_64-linux-gnu:"
                + rt + "/opt/wine/lib:" + rt + "/opt/wine/lib/wine");

        env.put("BOX64_PATH",     rt + "/usr/local/bin");
        env.put("BOX64_DYNAREC",  "1");
        env.put("BOX64_LOG",      "0");
        env.put("BOX64_NOBANNER", "1");
        // Prevent box64 from loading wine_rt/root/.box64rc which contains Winlator's
        // hardcoded paths (e.g. TMPDIR=/data/data/com.winlator/files/rootfs/tmp).
        // Without this, wineserver tries to mkdir at Winlator's path instead of ours.
        env.put("BOX64_RCFILE", "/dev/null");

        try {
            return pb.start();
        } catch (IOException e) {
            throw new IOException(
                    "exec failed: " + String.join(" ", cmd) + "\n"
                    + "ldso: " + (ldso != null ? ldso.getAbsolutePath() : "not found")
                    + " | wrapper: " + (wrapper != null ? wrapper.getAbsolutePath() : "none")
                    + " | box64: " + box64.exists()
                    + "\n" + e.getMessage(), e);
        }
    }

    // ── Extraction ────────────────────────────────────────────────────────────

    private static void extractTar(TarArchiveInputStream tar, File dest) throws Exception {
        TarArchiveEntry entry;
        while ((entry = tar.getNextTarEntry()) != null) {
            File out = new File(dest, entry.getName());
            if (entry.isSymbolicLink()) {
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                try {
                    String linkTarget = entry.getLinkName();
                    // Absolute symlink targets (e.g. /usr/local/bin/box64-real) are stored
                    // as-is in Linux rootfs tarballs, but they need to be relative inside
                    // the extracted tree. Convert: count directory depth of the symlink
                    // and prepend that many "../" to make the target relative.
                    if (linkTarget.startsWith("/")) {
                        String n = entry.getName();
                        if (n.startsWith("./")) n = n.substring(2);
                        int depth = 0;
                        for (int i = 0; i < n.length(); i++) if (n.charAt(i) == '/') depth++;
                        StringBuilder rel = new StringBuilder();
                        for (int i = 0; i < depth; i++) rel.append("../");
                        linkTarget = rel.append(linkTarget.substring(1)).toString();
                    }
                    java.nio.file.Files.deleteIfExists(out.toPath());
                    java.nio.file.Files.createSymbolicLink(
                            out.toPath(),
                            java.nio.file.Paths.get(linkTarget));
                } catch (Exception ignored) {
                    Log.w(TAG, "symlink skipped: " + entry.getName());
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

    // Generic extractor kept for any future use (box64 .tzst etc.)
    @SuppressWarnings("unused")
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
                 ZstdCompressorInputStream zstd =
                         new ZstdCompressorInputStream(new BufferedInputStream(fis));
                 TarArchiveInputStream tar = new TarArchiveInputStream(zstd)) {
                extractTar(tar, dest);
            }
        } else {
            try (FileInputStream fis = new FileInputStream(archive);
                 XZCompressorInputStream xz =
                         new XZCompressorInputStream(new BufferedInputStream(fis));
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

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "ExeToApk/1.0");
        c.setConnectTimeout(30_000);
        c.setReadTimeout(120_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        return c;
    }

    // ── Inner helpers ─────────────────────────────────────────────────────────

    /** Counts bytes flowing through a stream and fires a progress callback. */
    private static final class CountingInputStream extends FilterInputStream {
        private final long      total;
        private final DownloadCb cb;
        private       long      count   = 0;
        private       int       lastPct = -1;

        CountingInputStream(InputStream in, long total, DownloadCb cb) {
            super(in);
            this.total = total;
            this.cb    = cb;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) tick(n);
            return n;
        }

        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0) tick(1);
            return b;
        }

        private void tick(int n) {
            if (total <= 0) return;
            count += n;
            int pct = (int) (count * 100 / total);
            if (pct != lastPct) { lastPct = pct; cb.onPct(pct); }
        }
    }

    private static void pipe(InputStream in, java.io.OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    /** Recursively search wine_rt for the ARM64 glibc dynamic linker. */
    private static File findLdso(File root) {
        java.util.Deque<File> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            File dir = stack.pop();
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File f : children) {
                String name = f.getName();
                if (name.equals("ld-linux-aarch64.so.1") && !f.isDirectory()) return f;
                // Also accept the versioned name (ld-2.xx.so) as fallback
                if (name.matches("ld-[0-9]+\\.[0-9]+\\.so") && !f.isDirectory()) {
                    // prefer ld-linux-aarch64.so.1 but remember this as a candidate
                    // check if the symlink name exists next to it
                    File sym = new File(f.getParentFile(), "ld-linux-aarch64.so.1");
                    if (!sym.exists()) return f; // only use versioned name if symlink missing
                }
                if (f.isDirectory() && !name.equals("proc") && !name.equals("sys")
                        && !name.equals("dev")) {
                    stack.push(f);
                }
            }
        }
        return null;
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
