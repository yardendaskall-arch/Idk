package com.facemoji.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val buildNumber: Int,
    val versionName: String,
    val apkUrl: String
)

sealed class UpdateResult {
    object UpToDate                       : UpdateResult()
    data class UpdateAvailable(val info: ReleaseInfo) : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}

object UpdateChecker {

    /** Fetch latest GitHub Release and compare to current build. */
    suspend fun check(repo: String, currentBuild: Int): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$repo/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = 12_000
            conn.readTimeout    = 12_000

            val code = conn.responseCode
            if (code == 404) return@withContext UpdateResult.Error("No releases found yet.")
            if (code != 200) return@withContext UpdateResult.Error("Server returned $code.")

            val body    = conn.inputStream.bufferedReader().readText()
            val json    = JSONObject(body)
            val tag     = json.getString("tag_name")          // "build-42"
            val latest  = tag.removePrefix("build-").toIntOrNull()
                ?: return@withContext UpdateResult.Error("Unrecognised tag: $tag")

            if (latest <= currentBuild) return@withContext UpdateResult.UpToDate

            // Find the APK asset URL
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            if (apkUrl == null) return@withContext UpdateResult.Error("Release has no APK attached yet.")

            UpdateResult.UpdateAvailable(
                ReleaseInfo(latest, "1.$latest", apkUrl)
            )
        } catch (e: Exception) {
            UpdateResult.Error("Network error: ${e.message}")
        }
    }

    /**
     * Download the APK to the app's cache directory, reporting progress 0-100.
     * Returns the downloaded [File] on success, null on failure.
     */
    suspend fun download(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dest = File(context.cacheDir, "update.apk")
            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout    = 60_000
            conn.connect()

            val total = conn.contentLengthLong
            val input  = conn.inputStream
            val output = FileOutputStream(dest)
            val buf    = ByteArray(16_384)
            var downloaded = 0L

            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
                downloaded += n
                if (total > 0) onProgress(((downloaded * 100) / total).toInt())
            }
            output.close()
            input.close()
            dest
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Launch the system install prompt for [apkFile].
     * On API 26+ asks for unknown-source permission if not already granted.
     * Returns false if a permission screen was opened (caller should retry).
     */
    fun install(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return false
            }
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
        )
        return true
    }
}
