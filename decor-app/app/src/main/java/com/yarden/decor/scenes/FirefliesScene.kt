package com.yarden.decor.scenes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.yarden.decor.scenes.ColorUtil.withAlpha
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Glowing fireflies wandering around. Tap and they gather around your finger. */
class FirefliesScene(palette: Palette, density: Float) : BaseScene(palette, density) {

    private class Firefly(
        var x: Float, var y: Float, var heading: Float,
        val speed: Float, val size: Float,
        val phase: Float, val pulse: Float, val turn: Float, val color: Int
    )

    private var flies: List<Firefly> = emptyList()

    // Unit-radius glow per palette colour; the canvas is scaled to the glow size when drawing.
    private val glows = Array(palette.colors.size) { i ->
        val c = palette.colors[i]
        RadialGradient(
            0f, 0f, 1f,
            intArrayOf(withAlpha(c, 255), withAlpha(c, 90), withAlpha(c, 0)),
            floatArrayOf(0f, 0.25f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private var attractX = 0f
    private var attractY = 0f
    private var attractTime = 0f

    override fun onResize() {
        flies = List(FLY_COUNT) {
            Firefly(
                x = rand(0f, w), y = rand(0f, h), heading = rand(0f, 6.28f),
                speed = dp(rand(10f, 28f)), size = rand(0.7f, 1.4f),
                phase = rand(0f, 6.28f), pulse = rand(0.8f, 2f), turn = rand(0.3f, 1.1f),
                color = rnd.nextInt(palette.colors.size)
            )
        }
    }

    override fun onUpdate(dt: Float) {
        val margin = dp(30f)
        val attracted = attractTime > 0f
        if (attracted) attractTime -= dt

        for (f in flies) {
            f.heading += wave(f.turn, f.phase) * 1.6f * dt
            if (attracted) {
                var diff = atan2(attractY - f.y, attractX - f.x) - f.heading
                while (diff > PI) diff -= 2f * PI.toFloat()
                while (diff < -PI) diff += 2f * PI.toFloat()
                f.heading += diff * 2.5f * dt
            }
            val v = if (attracted) f.speed * 2.5f else f.speed
            f.x += cos(f.heading) * v * dt
            f.y += sin(f.heading) * v * dt

            if (f.x < -margin) f.x = w + margin
            if (f.x > w + margin) f.x = -margin
            if (f.y < -margin) f.y = h + margin
            if (f.y > h + margin) f.y = -margin
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (f in flies) {
            val glow = 0.35f + 0.65f * (0.5f + 0.5f * wave(f.pulse, f.phase))
            val radius = dp(26f) * f.size * (0.7f + 0.3f * glow)

            glowPaint.shader = glows[f.color]
            glowPaint.alpha = (glow * 200f).toInt()
            canvas.save()
            canvas.translate(f.x, f.y)
            canvas.scale(radius, radius)
            canvas.drawCircle(0f, 0f, 1f, glowPaint)
            canvas.restore()

            corePaint.alpha = (glow * 255f).toInt()
            canvas.drawCircle(f.x, f.y, dp(1.8f) * f.size, corePaint)
        }
    }

    override fun onTouch(x: Float, y: Float) {
        attractX = x
        attractY = y
        attractTime = 2.5f
    }

    private companion object {
        const val FLY_COUNT = 34
    }
}
