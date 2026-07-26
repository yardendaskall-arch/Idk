package com.microdose.discipline.widget.actions

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.microdose.discipline.data.WidgetStateRepository
import com.microdose.discipline.widget.MicroDoseWidget

/**
 * Requires two deliberate taps within a short window before AWAITING_CHECKOFF -> REWARD_READY.
 * This is the "active engagement" check standing in for a true hold gesture (Glance/RemoteViews
 * widgets cannot observe press duration, only discrete click events).
 */
class CheckoffAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetStateRepository.get(context).onCheckoffTap()
        MicroDoseWidget().updateAll(context)
    }
}
