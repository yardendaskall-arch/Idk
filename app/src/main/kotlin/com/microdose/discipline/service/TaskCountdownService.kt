package com.microdose.discipline.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.glance.appwidget.updateAll
import com.microdose.discipline.data.WidgetStateRepository
import com.microdose.discipline.widget.MicroDoseWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drives the widget's 10-second micro-task countdown. Deliberately not a foreground service:
 * it is short-lived (<=10s), started directly from a widget button click, which Android exempts
 * from background-start restrictions.
 */
class TaskCountdownService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = WidgetStateRepository.get(applicationContext)
        val widget = MicroDoseWidget()

        scope.launch {
            val durationSeconds = repo.current().taskDurationSeconds
            repeat(durationSeconds) {
                delay(1_000L)
                widget.updateAll(applicationContext)
            }
            repo.markTaskTimerElapsed()
            widget.updateAll(applicationContext)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
