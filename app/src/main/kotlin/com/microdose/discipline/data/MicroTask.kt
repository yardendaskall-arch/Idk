package com.microdose.discipline.data

data class MicroTask(val id: String, val label: String)

object MicroTaskBank {
    private val tasks = listOf(
        MicroTask("breaths_3", "Take 3 deep breaths"),
        MicroTask("jumping_jacks_5", "Do 5 jumping jacks"),
        MicroTask("water_sips_3", "Drink 3 sips of water"),
        MicroTask("stretch_arms", "Stretch both arms overhead"),
        MicroTask("neck_roll", "Roll your neck slowly, both directions"),
        MicroTask("posture_check", "Sit up straight, shoulders back"),
        MicroTask("gratitude_1", "Think of 1 thing you're grateful for"),
        MicroTask("eyes_rest", "Look at something 20 feet away for 10s"),
        MicroTask("squats_5", "Do 5 bodyweight squats"),
        MicroTask("smile", "Smile and take one slow breath"),
    )

    fun random(): MicroTask = tasks.random()
    fun byId(id: String): MicroTask = tasks.firstOrNull { it.id == id } ?: tasks.first()
}
