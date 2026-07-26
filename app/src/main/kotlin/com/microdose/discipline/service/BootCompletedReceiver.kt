package com.microdose.discipline.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.microdose.discipline.data.WidgetStateRepository
import com.microdose.discipline.widget.MicroDoseWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A reboot mid-countdown would otherwise strand the widget in a stale in-progress state. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetStateRepository.get(appContext).resetToLocked()
                MicroDoseWidget().updateAll(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
