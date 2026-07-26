package com.microdose.discipline.widget.actions

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.microdose.discipline.data.WidgetStateRepository
import com.microdose.discipline.service.LockForegroundService
import com.microdose.discipline.widget.MicroDoseWidget

/** REWARD_READY -> IN_REWARD_SESSION: launches the chosen app and arms the 60s hard lock. */
class LaunchTargetAppAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val packageName = parameters[packageKey] ?: return
        val repo = WidgetStateRepository.get(context)
        repo.startRewardSession()
        MicroDoseWidget().updateAll(context)

        context.packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }

        LockForegroundService.start(context, packageName)
    }

    companion object {
        val packageKey = ActionParameters.Key<String>("target_package")
    }
}
