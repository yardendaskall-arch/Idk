package com.yarden.decor.scenes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.hypot

/** Translucent bubbles rising and swaying. Tap one to pop it. */
class BubblesScene(palette: Palette, density: Float) : BaseScene(palette, density) {

    private class Bubble {
        var x = 0f
        var y = 0f
        var r = 0f
        var vy = 0f
        var sway = 0f
        var phase = 0f
        var color = 0

        // 0 while floating; runs 0..1 while popping.
        var pop = 0f
    }

    private val bubbles = Array(BUBBLE_COUNT) { Bubble() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onResize() {
        for (b in bubbles) respawn(b, anywhere = true)
        ring.strokeWidth = dp(1.5f)
    }

    private fun respawn(b: Bubble, anywhere: Boolean) {
        b.r = dp(rand(10f, 44f))
        b.x = rand(0f, w)
        b.y = if (anywhere) rand(0f, h) else h + b.r + rand(0f, h * 0.2f)
        // Bigger bubbles rise a little faster.
        b.vy = dp(rand(14f, 30f)) + b.r * 0.6f
        b.sway = dp(rand(6f, 22f))
        b.phase = rand(0f, 6.28f)
        b.color = rnd.nextInt(palette.colors.size)
        b.pop = 0f
    }

    override fun onUpdate(dt: Float) {
        for (b in bubbles) {
            if (b.pop > 0f) {
                b.pop += dt * 4f
                if (b.pop >= 1f) respawn(b, anywhere = false)
                continue
            }
            b.y -= b.vy * dt
            if (b.y + b.r < 0f) respawn(b, anywhere = false)
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (b in bubbles) {
            val popping = b.pop > 0f
            val fade = if (popping) 1f - b.pop else 1f
            val scale = if (popping) 1f + b.pop * 0.5f else 1f
            val cx = b.x + b.sway * wave(0.7f, b.phase)
            val radius = b.r * scale
            val c = palette.colors[b.color]

            fill.color = c
            fill.alpha = (fade * 45f).toInt()
            canvas.drawCircle(cx, b.y, radius, fill)

            ring.color = c
            ring.alpha = (fade * 170f).toInt()
            canvas.drawCircle(cx, b.y, radius, ring)

            shine.alpha = (fade * 150f).toInt()
            canvas.drawCircle(cx - radius * 0.35f, b.y - radius * 0.35f, radius * 0.16f, shine)
        }
    }

    override fun onTouch(x: Float, y: Float) {
        for (b in bubbles) {
            if (b.pop > 0f) continue
            val cx = b.x + b.sway * wave(0.7f, b.phase)
            if (hypot(cx - x, b.y - y) < b.r + dp(28f)) b.pop = 0.001f
        }
    }

    private companion object {
        const val BUBBLE_COUNT = 26
    }
}
