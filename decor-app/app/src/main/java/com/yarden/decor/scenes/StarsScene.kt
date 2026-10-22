package com.yarden.decor.scenes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.yarden.decor.scenes.ColorUtil.mix
import kotlin.math.PI
import kotlin.math.sin

/** Twinkling parallax stars with the occasional shooting star. Tap to launch one. */
class StarsScene(palette: Palette, density: Float) : BaseScene(palette, density) {

    private class Star(
        var x: Float, var y: Float, val size: Float,
        val phase: Float, val freq: Float, val layer: Int, val color: Int
    )

    private class Shot {
        var active = false
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var age = 0f
        var life = 1f
    }

    private var stars: List<Star> = emptyList()
    private val shots = Array(MAX_SHOTS) { Shot() }
    private var nextShot = 2f

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val shotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onResize() {
        stars = List(STAR_COUNT) {
            val layer = rnd.nextInt(3)
            Star(
                x = rand(0f, w), y = rand(0f, h),
                size = dp(rand(LAYER_MIN_SIZE[layer], LAYER_MAX_SIZE[layer])),
                phase = rand(0f, 6.28f), freq = rand(0.5f, 2.2f), layer = layer,
                color = mix(Color.WHITE, palette.colors[rnd.nextInt(palette.colors.size)], 0.35f)
            )
        }
        sparklePaint.strokeWidth = dp(0.8f)
    }

    override fun onUpdate(dt: Float) {
        for (s in stars) {
            val v = dp(LAYER_SPEED[s.layer])
            s.x -= v * dt * 0.6f
            s.y += v * dt
            if (s.y > h + 4f) {
                s.y = -4f
                s.x = rand(0f, w)
            }
            if (s.x < -4f) s.x = w + 4f
        }

        nextShot -= dt
        if (nextShot <= 0f) {
            spawnShot(rand(w * 0.35f, w * 1.05f), rand(-h * 0.02f, h * 0.35f))
            nextShot = rand(3f, 8f)
        }
        for (sh in shots) {
            if (!sh.active) continue
            sh.age += dt
            sh.x += sh.vx * dt
            sh.y += sh.vy * dt
            if (sh.age >= sh.life) sh.active = false
        }
    }

    private fun spawnShot(x: Float, y: Float) {
        val sh = shots.firstOrNull { !it.active } ?: return
        sh.active = true
        sh.x = x
        sh.y = y
        sh.vx = -dp(rand(700f, 1000f))
        sh.vy = dp(rand(330f, 520f))
        sh.age = 0f
        sh.life = rand(0.9f, 1.3f)
    }

    override fun onDraw(canvas: Canvas) {
        for (s in stars) {
            val tw = 0.5f + 0.5f * wave(s.freq, s.phase)
            val a = (70f + 185f * tw * LAYER_BRIGHTNESS[s.layer]).toInt().coerceIn(0, 255)
            starPaint.color = s.color
            starPaint.alpha = a
            canvas.drawCircle(s.x, s.y, s.size * (0.75f + 0.5f * tw), starPaint)

            if (s.layer == 2) {
                val len = s.size * 4f * tw
                sparklePaint.color = s.color
                sparklePaint.alpha = (a * 0.6f).toInt()
                canvas.drawLine(s.x - len, s.y, s.x + len, s.y, sparklePaint)
                canvas.drawLine(s.x, s.y - len, s.x, s.y + len, sparklePaint)
            }
        }

        for (sh in shots) {
            if (!sh.active) continue
            val envelope = sin(PI.toFloat() * (sh.age / sh.life))
            for (i in 0 until TAIL_SEGMENTS) {
                val f0 = i / TAIL_SEGMENTS.toFloat()
                val f1 = (i + 1) / TAIL_SEGMENTS.toFloat()
                shotPaint.alpha = (255f * envelope * (1f - f0)).toInt().coerceIn(0, 255)
                shotPaint.strokeWidth = dp(2.2f) * (1f - f0 * 0.8f)
                canvas.drawLine(
                    sh.x - sh.vx * TAIL_TIME * f0, sh.y - sh.vy * TAIL_TIME * f0,
                    sh.x - sh.vx * TAIL_TIME * f1, sh.y - sh.vy * TAIL_TIME * f1,
                    shotPaint
                )
            }
            headPaint.alpha = (255f * envelope).toInt().coerceIn(0, 255)
            canvas.drawCircle(sh.x, sh.y, dp(2.2f), headPaint)
        }
    }

    override fun onTouch(x: Float, y: Float) {
        spawnShot(x, y)
    }

    private companion object {
        const val STAR_COUNT = 130
        const val MAX_SHOTS = 3
        const val TAIL_SEGMENTS = 10
        const val TAIL_TIME = 0.12f

        // Per layer: far, mid, near.
        val LAYER_SPEED = floatArrayOf(3f, 7f, 13f)
        val LAYER_BRIGHTNESS = floatArrayOf(0.55f, 0.8f, 1f)
        val LAYER_MIN_SIZE = floatArrayOf(0.8f, 1.1f, 1.6f)
        val LAYER_MAX_SIZE = floatArrayOf(1.1f, 1.6f, 2.4f)
    }
}
