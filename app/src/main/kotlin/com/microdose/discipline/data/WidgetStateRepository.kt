package com.microdose.discipline.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "microdose_state")

/**
 * Single source of truth for the dopamine-gate lifecycle. Every mutation is followed by an
 * explicit widget refresh call from the caller (Glance has no built-in reactive binding to
 * DataStore across process restarts), which is what gives us "zero-latency" widget updates
 * instead of relying on the OS's periodic AppWidget update cycle.
 */
class WidgetStateRepository(private val context: Context) {

    private object Keys {
        val GATE_STATE = stringPreferencesKey("gate_state")
        val ACTIVE_TASK_ID = stringPreferencesKey("active_task_id")
        val TASK_STARTED_AT = longPreferencesKey("task_started_at")
        val CHECKOFF_ARMED_AT = longPreferencesKey("checkoff_armed_at")
        val REWARD_SESSION_STARTED_AT = longPreferencesKey("reward_session_started_at")
        val TASK_DURATION_SECONDS = longPreferencesKey("task_duration_seconds")
        val LOCK_DURATION_SECONDS = longPreferencesKey("lock_duration_seconds")
        val SELECTED_PACKAGES = stringSetPreferencesKey("selected_packages")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    val state: Flow<MicroDoseState> = context.dataStore.data.map { prefs ->
        MicroDoseState(
            gateState = prefs[Keys.GATE_STATE]?.let { runCatching { GateState.valueOf(it) }.getOrNull() }
                ?: GateState.LOCKED,
            activeTaskId = prefs[Keys.ACTIVE_TASK_ID] ?: MicroTaskBank.random().id,
            taskStartedAtMillis = prefs[Keys.TASK_STARTED_AT] ?: 0L,
            checkoffArmedAtMillis = prefs[Keys.CHECKOFF_ARMED_AT] ?: 0L,
            rewardSessionStartedAtMillis = prefs[Keys.REWARD_SESSION_STARTED_AT] ?: 0L,
            taskDurationSeconds = (prefs[Keys.TASK_DURATION_SECONDS] ?: 10L).toInt(),
            lockDurationSeconds = (prefs[Keys.LOCK_DURATION_SECONDS] ?: 60L).toInt(),
            selectedPackages = prefs[Keys.SELECTED_PACKAGES] ?: DefaultTargetApps.packageNames(),
        )
    }

    suspend fun current(): MicroDoseState = state.first()

    suspend fun isOnboardingDone(): Boolean = context.dataStore.data.map {
        it[Keys.ONBOARDING_DONE] ?: false
    }.first()

    suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun setSelectedPackages(packages: Set<String>) {
        context.dataStore.edit { it[Keys.SELECTED_PACKAGES] = packages }
    }

    /** LOCKED -> TASK_ACTIVE: a fresh random task starts its 10s window. */
    suspend fun startTask() {
        context.dataStore.edit { prefs ->
            prefs[Keys.GATE_STATE] = GateState.TASK_ACTIVE.name
            prefs[Keys.ACTIVE_TASK_ID] = MicroTaskBank.random().id
            prefs[Keys.TASK_STARTED_AT] = System.currentTimeMillis()
        }
    }

    /** TASK_ACTIVE -> AWAITING_CHECKOFF once the 10s countdown elapses. */
    suspend fun markTaskTimerElapsed() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.GATE_STATE] == GateState.TASK_ACTIVE.name) {
                prefs[Keys.GATE_STATE] = GateState.AWAITING_CHECKOFF.name
            }
        }
    }

    /**
     * First tap "arms" the checkoff; a second deliberate tap within [armWindowMillis] confirms it.
     * This substitutes for a true press-duration "hold" gesture, which Glance/RemoteViews cannot
     * observe (no continuous touch tracking is exposed to app widgets).
     */
    suspend fun onCheckoffTap(armWindowMillis: Long = 3_000L): Boolean {
        var confirmed = false
        context.dataStore.edit { prefs ->
            if (prefs[Keys.GATE_STATE] != GateState.AWAITING_CHECKOFF.name) return@edit
            val armedAt = prefs[Keys.CHECKOFF_ARMED_AT] ?: 0L
            val now = System.currentTimeMillis()
            if (armedAt != 0L && now - armedAt <= armWindowMillis) {
                prefs[Keys.GATE_STATE] = GateState.REWARD_READY.name
                prefs[Keys.CHECKOFF_ARMED_AT] = 0L
                confirmed = true
            } else {
                prefs[Keys.CHECKOFF_ARMED_AT] = now
            }
        }
        return confirmed
    }

    /** REWARD_READY -> IN_REWARD_SESSION when the user launches a target app. */
    suspend fun startRewardSession() {
        context.dataStore.edit { prefs ->
            prefs[Keys.GATE_STATE] = GateState.IN_REWARD_SESSION.name
            prefs[Keys.REWARD_SESSION_STARTED_AT] = System.currentTimeMillis()
        }
    }

    /** IN_REWARD_SESSION -> LOCKED once the 60s hard lock fires. */
    suspend fun resetToLocked() {
        context.dataStore.edit { prefs ->
            prefs[Keys.GATE_STATE] = GateState.LOCKED.name
            prefs[Keys.ACTIVE_TASK_ID] = MicroTaskBank.random().id
            prefs[Keys.TASK_STARTED_AT] = 0L
            prefs[Keys.CHECKOFF_ARMED_AT] = 0L
            prefs[Keys.REWARD_SESSION_STARTED_AT] = 0L
        }
    }

    companion object {
        @Volatile private var instance: WidgetStateRepository? = null

        fun get(context: Context): WidgetStateRepository = instance ?: synchronized(this) {
            instance ?: WidgetStateRepository(context.applicationContext).also { instance = it }
        }
    }
}
