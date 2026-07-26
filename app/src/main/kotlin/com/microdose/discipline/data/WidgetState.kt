package com.microdose.discipline.data

/** Lifecycle of the dopamine gate, persisted so widget redraws are always instant + correct. */
enum class GateState {
    LOCKED,
    TASK_ACTIVE,
    AWAITING_CHECKOFF,
    REWARD_READY,
    IN_REWARD_SESSION,
}

data class MicroDoseState(
    val gateState: GateState = GateState.LOCKED,
    val activeTaskId: String = MicroTaskBank.random().id,
    val taskStartedAtMillis: Long = 0L,
    val checkoffArmedAtMillis: Long = 0L,
    val rewardSessionStartedAtMillis: Long = 0L,
    val taskDurationSeconds: Int = 10,
    val lockDurationSeconds: Int = 60,
    val selectedPackages: Set<String> = DefaultTargetApps.packageNames(),
)
