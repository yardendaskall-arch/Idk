package com.microdose.discipline.widget.actions

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.microdose.discipline.data.WidgetStateRepository
import com.microdose.discipline.service.TaskCountdownService
import com.microdose.discipline.widget.MicroDoseWidget

/** LOCKED -> TASK_ACTIVE: begins the 10 second micro-task window. */
class StartTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repo = WidgetStateRepository.get(context)
        repo.startTask()
        MicroDoseWidget().updateAll(context)
        context.startService(Intent(context, TaskCountdownService::class.java))
    }
}
