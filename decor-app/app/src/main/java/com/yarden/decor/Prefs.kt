package com.yarden.decor

import android.content.Context
import android.content.SharedPreferences
import com.yarden.decor.scenes.Palette
import com.yarden.decor.scenes.SceneType

/** Settings shared by the app screen and the wallpaper service (same process). */
object Prefs {
    private const val NAME = "decor"

    const val KEY_SCENE = "scene"
    const val KEY_PALETTE = "palette"
    const val KEY_SPEED = "speed"

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun scene(p: SharedPreferences): SceneType =
        SceneType.entries.firstOrNull { it.name == p.getString(KEY_SCENE, null) } ?: SceneType.AURORA

    fun palette(p: SharedPreferences): Palette =
        Palette.entries.firstOrNull { it.name == p.getString(KEY_PALETTE, null) } ?: Palette.SUNSET

    /** Animation speed in percent: 50 (calm) to 200 (lively). */
    fun speedPercent(p: SharedPreferences): Int = p.getInt(KEY_SPEED, 100).coerceIn(50, 200)
}
