package com.microdose.discipline.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import com.microdose.discipline.MicroDoseApplication
import com.microdose.discipline.R
import com.microdose.discipline.data.WidgetStateRepository
import com.microdose.discipline.widget.MicroDoseWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * High-accuracy wall-clock 60 second countdown for the reward window. Runs as a foreground
 * service so Android's battery optimizer / Doze cannot kill it mid-countdown. When time
 * expires it resets gate state to LOCKED and forcefully redirects off the target app.
 */
class LockForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetPackage = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)
        startForeground(NOTIFICATION_ID, buildNotification(remainingSeconds = 60))

        val repo = WidgetStateRepository.get(applicationContext)
        val widget = MicroDoseWidget()

        scope.launch {
            val totalSeconds = repo.current().lockDurationSeconds
            for (secondsLeft in totalSeconds - 1 downTo 0) {
                delay(1_000L)
                updateNotification(secondsLeft)
            }

            repo.resetToLocked()
            widget.updateAll(applicationContext)
            enforceBlock(targetPackage)
            stopSelf(startId)
        }
        return START_STICKY
    }

    private fun enforceBlock(targetPackage: String?) {
        val handledByAccessibility = AppBlockAccessibilityService.requestForceHomeIfAvailable()
        if (!handledByAccessibility) {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(homeIntent)
        }
        OverlayBlocker.show(applicationContext)
    }

    private fun buildNotification(remainingSeconds: Int): Notification =
        NotificationCompat.Builder(this, MicroDoseApplication.LOCK_TIMER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.notif_lock_title))
            .setContentText(getString(R.string.notif_lock_text, remainingSeconds))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(remainingSeconds: Int) {
        val manager = ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(remainingSeconds))
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_TARGET_PACKAGE = "target_package"

        fun start(context: Context, targetPackage: String) {
            val intent = Intent(context, LockForegroundService::class.java)
                .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
