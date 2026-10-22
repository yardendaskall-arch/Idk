package com.yarden.decor.scenes

import android.graphics.Color

enum class Palette(val label: String, val background: Int, val colors: IntArray) {
    SUNSET(
        "Sunset", 0xFF1B0B2E.toInt(),
        intArrayOf(0xFFFF6B6B.toInt(), 0xFFFFA94D.toInt(), 0xFFFF5EA8.toInt(), 0xFF9B5DE5.toInt())
    ),
    OCEAN(
        "Ocean", 0xFF04122B.toInt(),
        intArrayOf(0xFF00D4FF.toInt(), 0xFF4D7CFF.toInt(), 0xFF00F5C8.toInt(), 0xFF7B61FF.toInt())
    ),
    FOREST(
        "Forest", 0xFF06180F.toInt(),
        intArrayOf(0xFF3DDC84.toInt(), 0xFFB6F36A.toInt(), 0xFF1FB5A0.toInt(), 0xFFF4E04D.toInt())
    ),
    CANDY(
        "Candy", 0xFF1E1033.toInt(),
        intArrayOf(0xFFFF8FD8.toInt(), 0xFF8FD3FF.toInt(), 0xFFC4A1FF.toInt(), 0xFFFFE08F.toInt())
    )
}

object ColorUtil {
    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    fun mix(a: Int, b: Int, t: Float): Int {
        val r = Color.red(a) + ((Color.red(b) - Color.red(a)) * t).toInt()
        val g = Color.green(a) + ((Color.green(b) - Color.green(a)) * t).toInt()
        val bl = Color.blue(a) + ((Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }
}
