package com.microdose.discipline

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MicroDoseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                LOCK_TIMER_CHANNEL_ID,
                getString(R.string.notif_channel_lock),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val LOCK_TIMER_CHANNEL_ID = "lock_timer_channel"
    }
}
