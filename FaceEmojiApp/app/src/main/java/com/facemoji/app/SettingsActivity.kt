package com.facemoji.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.facemoji.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var downloadedApk: File? = null
    private var pendingInstall = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Toolbar back button
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Current version
        binding.tvVersion.text = "Version ${BuildConfig.VERSION_NAME}  (build ${BuildConfig.BUILD_NUMBER})"

        binding.btnCheck.setOnClickListener       { checkForUpdates() }
        binding.btnDownload.setOnClickListener    { downloadAndInstall() }
        binding.btnOpenKeyboardSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        // If launched with auto_check extra, start checking immediately
        if (intent.getBooleanExtra("auto_check", false)) checkForUpdates()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        // If we sent the user away to grant install permission, retry
        if (pendingInstall) {
            pendingInstall = false
            downloadedApk?.let { tryInstall(it) }
        }
    }

    // ── Update check ──────────────────────────────────────────────────────────

    private fun checkForUpdates() {
        setStatus("Checking for updates…")
        binding.btnCheck.isEnabled  = false
        binding.btnDownload.visibility = View.GONE
        binding.progressBar.visibility = View.GONE

        lifecycleScope.launch {
            val result = UpdateChecker.check(BuildConfig.GITHUB_REPO, BuildConfig.BUILD_NUMBER)
            binding.btnCheck.isEnabled = true

            when (result) {
                is UpdateResult.UpToDate -> {
                    setStatus("You're up to date!  (build ${BuildConfig.BUILD_NUMBER})")
                    saveLastCheckTime()
                }
                is UpdateResult.UpdateAvailable -> {
                    val info = result.info
                    setStatus("Update available: build ${info.buildNumber}  →  tap below to install")
                    binding.btnDownload.visibility = View.VISIBLE
                    downloadedApk = null
                    saveLastCheckTime()
                }
                is UpdateResult.Error -> {
                    setStatus("Could not check: ${result.message}")
                }
            }
        }
    }

    // ── Download & install ────────────────────────────────────────────────────

    private fun downloadAndInstall() {
        // If we already downloaded the APK, just install it
        downloadedApk?.let { tryInstall(it); return }

        lifecycleScope.launch {
            // Re-fetch release info to get the download URL
            val result = UpdateChecker.check(BuildConfig.GITHUB_REPO, BuildConfig.BUILD_NUMBER)
            if (result !is UpdateResult.UpdateAvailable) {
                setStatus("Could not get download URL. Try checking again.")
                return@launch
            }

            binding.btnDownload.isEnabled  = false
            binding.btnCheck.isEnabled     = false
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.progress   = 0
            setStatus("Downloading…  0%")

            val file = UpdateChecker.download(this@SettingsActivity, result.info.apkUrl) { pct ->
                runOnUiThread {
                    binding.progressBar.progress = pct
                    setStatus("Downloading…  $pct%")
                }
            }

            binding.progressBar.visibility = View.GONE
            binding.btnCheck.isEnabled     = true
            binding.btnDownload.isEnabled  = true

            if (file == null) {
                setStatus("Download failed — check your connection and try again.")
                return@launch
            }

            downloadedApk = file
            setStatus("Download complete. Installing…")
            tryInstall(file)
        }
    }

    private fun tryInstall(apkFile: File) {
        val launched = UpdateChecker.install(this, apkFile)
        if (!launched) {
            // User was sent to grant install-unknown-apps permission; resume on return
            pendingInstall = true
            Toast.makeText(this, "Allow this app to install APKs, then come back.", Toast.LENGTH_LONG).show()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun setStatus(msg: String) {
        binding.tvUpdateStatus.text = msg
    }

    private fun saveLastCheckTime() {
        getSharedPreferences("update_prefs", MODE_PRIVATE)
            .edit()
            .putLong("last_check_ms", System.currentTimeMillis())
            .apply()
    }
}
