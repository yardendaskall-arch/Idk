package com.microdose.discipline.data

data class TargetApp(val displayName: String, val packageName: String)

object DefaultTargetApps {
    val ALL = listOf(
        TargetApp("TikTok", "com.zhiliaoapp.musically"),
        TargetApp("YouTube", "com.google.android.youtube"),
        TargetApp("Instagram", "com.instagram.android"),
    )

    fun packageNames(): Set<String> = ALL.map { it.packageName }.toSet()
}
