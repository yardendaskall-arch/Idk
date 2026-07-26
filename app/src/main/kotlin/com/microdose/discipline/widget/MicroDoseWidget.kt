package com.microdose.discipline.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.microdose.discipline.R
import com.microdose.discipline.data.DefaultTargetApps
import com.microdose.discipline.data.GateState
import com.microdose.discipline.data.MicroDoseState
import com.microdose.discipline.data.MicroTaskBank
import com.microdose.discipline.data.WidgetStateRepository
import com.microdose.discipline.widget.actions.CheckoffAction
import com.microdose.discipline.widget.actions.LaunchTargetAppAction
import com.microdose.discipline.widget.actions.StartTaskAction
import kotlinx.coroutines.flow.first

/** Mirrors res/values/colors.xml — Glance's resource-backed ColorProvider is library-internal. */
private object WidgetColors {
    val primary = ColorProvider(Color(0xFF2E7D32))
    val onPrimary = ColorProvider(Color(0xFFFFFFFF))
    val secondary = ColorProvider(Color(0xFFFF7043))
    val surface = ColorProvider(Color(0xFFFFFFFF))
    val locked = ColorProvider(Color(0xFF9E9E9E))
    val reward = ColorProvider(Color(0xFFFF7043))
}

class MicroDoseWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = WidgetStateRepository.get(context)
        val state = repo.state.first()

        provideContent {
            GateContent(state)
        }
    }

    @Composable
    private fun GateContent(state: MicroDoseState) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetColors.surface)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state.gateState) {
                GateState.LOCKED -> LockedContent(state)
                GateState.TASK_ACTIVE -> TaskActiveContent(state)
                GateState.AWAITING_CHECKOFF -> AwaitingCheckoffContent()
                GateState.REWARD_READY -> RewardReadyContent(state)
                GateState.IN_REWARD_SESSION -> InSessionContent()
            }
        }
    }

    @Composable
    private fun LockedContent(state: MicroDoseState) {
        val task = MicroTaskBank.byId(state.activeTaskId)
        Text(
            text = "🔒 " + LocalContext.current.getString(R.string.widget_locked),
            style = TextStyle(fontWeight = FontWeight.Bold, color = WidgetColors.locked),
        )
        Spacer(modifier = GlanceModifier.height(6.dp))
        Text(text = task.label, style = TextStyle(fontWeight = FontWeight.Medium))
        Spacer(modifier = GlanceModifier.height(8.dp))
        Text(
            text = LocalContext.current.getString(R.string.widget_start_task),
            style = TextStyle(color = WidgetColors.onPrimary),
            modifier = GlanceModifier
                .background(WidgetColors.primary)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable(actionRunCallback<StartTaskAction>()),
        )
    }

    @Composable
    private fun TaskActiveContent(state: MicroDoseState) {
        val elapsedSeconds = ((System.currentTimeMillis() - state.taskStartedAtMillis) / 1000).toInt()
        val remaining = (state.taskDurationSeconds - elapsedSeconds).coerceIn(0, state.taskDurationSeconds)
        val task = MicroTaskBank.byId(state.activeTaskId)
        Text(text = task.label, style = TextStyle(fontWeight = FontWeight.Bold))
        Spacer(modifier = GlanceModifier.height(8.dp))
        Text(
            text = "$remaining",
            style = TextStyle(fontWeight = FontWeight.Bold, color = WidgetColors.primary),
        )
        Text(text = "seconds left — stay with it", style = TextStyle(color = WidgetColors.locked))
    }

    @Composable
    private fun AwaitingCheckoffContent() {
        Text(
            text = "✅ Task time complete",
            style = TextStyle(fontWeight = FontWeight.Bold),
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        Text(
            text = LocalContext.current.getString(R.string.widget_tap_to_confirm),
            style = TextStyle(color = WidgetColors.onPrimary),
            modifier = GlanceModifier
                .background(WidgetColors.secondary)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable(actionRunCallback<CheckoffAction>()),
        )
        Text(
            text = "(tap twice — confirms you actually did it)",
            style = TextStyle(color = WidgetColors.locked),
        )
    }

    @Composable
    private fun RewardReadyContent(state: MicroDoseState) {
        Text(
            text = LocalContext.current.getString(R.string.widget_reward_active),
            style = TextStyle(fontWeight = FontWeight.Bold, color = WidgetColors.reward),
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        Row {
            DefaultTargetApps.ALL
                .filter { it.packageName in state.selectedPackages }
                .forEach { app ->
                    Text(
                        text = app.displayName,
                        style = TextStyle(color = WidgetColors.onPrimary),
                        modifier = GlanceModifier
                            .padding(horizontal = 4.dp)
                            .background(WidgetColors.primary)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .clickable(
                                actionRunCallback<LaunchTargetAppAction>(
                                    actionParametersOf(LaunchTargetAppAction.packageKey to app.packageName),
                                ),
                            ),
                    )
                    Spacer(modifier = GlanceModifier.width(4.dp))
                }
        }
    }

    @Composable
    private fun InSessionContent() {
        Text(
            text = "▶️ In your reward minute",
            style = TextStyle(fontWeight = FontWeight.Bold, color = WidgetColors.reward),
        )
        Spacer(modifier = GlanceModifier.height(6.dp))
        Text(
            text = LocalContext.current.getString(R.string.widget_in_session),
            style = TextStyle(color = WidgetColors.locked),
        )
    }
}

