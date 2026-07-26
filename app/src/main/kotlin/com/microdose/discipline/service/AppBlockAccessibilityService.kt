package com.microdose.discipline.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import com.microdose.discipline.data.GateState
import com.microdose.discipline.data.WidgetStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches foreground-app changes so a blacklisted app opened directly (not via the widget's
 * reward flow) gets bounced home immediately, and provides the forceful redirect used once the
 * 60 second hard lock expires while the user is still inside the target app.
 */
class AppBlockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repo: WidgetStateRepository

    @Volatile private var isRewardSessionActive = false
    @Volatile private var blacklistedPackages: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        repo = WidgetStateRepository.get(applicationContext)

        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }

        scope.launch {
            repo.state.collect { state ->
                isRewardSessionActive = state.gateState == GateState.IN_REWARD_SESSION
                blacklistedPackages = state.selectedPackages
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (packageName !in blacklistedPackages) return
        if (isRewardSessionActive) return // legitimately unlocked right now

        forceHome()
        OverlayBlocker.show(applicationContext)
    }

    fun forceHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        if (instance === this) instance = null
    }

    companion object {
        @Volatile private var instance: AppBlockAccessibilityService? = null

        /** Best-effort forceful redirect; falls back to a plain "go home" intent in the caller. */
        fun requestForceHomeIfAvailable(): Boolean {
            instance?.forceHome() ?: return false
            return true
        }
    }
}
