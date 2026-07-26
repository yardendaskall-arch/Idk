package com.microdose.discipline

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.microdose.discipline.data.DefaultTargetApps
import com.microdose.discipline.data.WidgetStateRepository
import com.microdose.discipline.onboarding.OnboardingScreen
import com.microdose.discipline.onboarding.PermissionRowState
import com.microdose.discipline.onboarding.PermissionUtils
import com.microdose.discipline.ui.theme.MicroDoseTheme
import kotlinx.coroutines.launch

/**
 * Onboarding / permissions / target-app configuration ONLY — the actual gate lives in the
 * home-screen widget (see [com.microdose.discipline.widget.MicroDoseWidget]).
 */
class MainActivity : ComponentActivity() {

    private lateinit var repo: WidgetStateRepository

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshTrigger++ }

    private var refreshTrigger by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = WidgetStateRepository.get(applicationContext)

        setContent {
            MicroDoseTheme {
                var selectedPackages by remember { mutableStateOf(DefaultTargetApps.packageNames()) }

                LaunchedEffect(refreshTrigger) {
                    selectedPackages = repo.current().selectedPackages
                }

                val permissions = remember(refreshTrigger) {
                    listOf(
                        PermissionRowState(
                            title = getString(R.string.perm_overlay_title),
                            description = getString(R.string.perm_overlay_desc),
                            granted = PermissionUtils.hasOverlayPermission(this@MainActivity),
                            onGrantClick = ::requestOverlayPermission,
                        ),
                        PermissionRowState(
                            title = getString(R.string.perm_usage_title),
                            description = getString(R.string.perm_usage_desc),
                            granted = PermissionUtils.hasUsageStatsPermission(this@MainActivity),
                            onGrantClick = ::requestUsageStatsPermission,
                        ),
                        PermissionRowState(
                            title = getString(R.string.perm_accessibility_title),
                            description = getString(R.string.perm_accessibility_desc),
                            granted = PermissionUtils.hasAccessibilityPermission(this@MainActivity),
                            onGrantClick = ::requestAccessibilityPermission,
                        ),
                        PermissionRowState(
                            title = getString(R.string.perm_notifications_title),
                            description = getString(R.string.perm_notifications_desc),
                            granted = PermissionUtils.hasNotificationPermission(this@MainActivity),
                            onGrantClick = ::requestNotificationPermission,
                        ),
                    )
                }

                OnboardingScreen(
                    permissions = permissions,
                    targetApps = DefaultTargetApps.ALL,
                    selectedPackages = selectedPackages,
                    onToggleTargetApp = { packageName, checked ->
                        selectedPackages = if (checked) {
                            selectedPackages + packageName
                        } else {
                            selectedPackages - packageName
                        }
                        lifecycleScope.launch { repo.setSelectedPackages(selectedPackages) }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTrigger++
        lifecycleScope.launch { repo.setOnboardingDone(true) }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    private fun requestUsageStatsPermission() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun requestAccessibilityPermission() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
