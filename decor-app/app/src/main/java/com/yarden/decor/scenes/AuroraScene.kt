package com.yarden.decor.scenes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import com.yarden.decor.scenes.ColorUtil.withAlpha
import kotlin.math.max

/** Big soft glowing colour clouds drifting slowly, with a few twinkling specks on top. */
class AuroraScene(palette: Palette, density: Float) : BaseScene(palette, density) {

    private class Blob(
        // Unit-radius gradient; the canvas is scaled to the blob's real size when drawing.
        val shader: RadialGradient,
        val baseRadius: Float,
        val fx: Float, val fy: Float, val fr: Float,
        val px: Float, val py: Float, val pr: Float,
        val ax: Float, val ay: Float
    )

    private class Speck(
        val x: Float, val y: Float, val size: Float,
        val phase: Float, val freq: Float
    )

    private var blobs: List<Blob> = emptyList()
    private var specks: List<Speck> = emptyList()

    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val speckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onResize() {
        val big = max(w, h)
        blobs = List(BLOB_COUNT) { i ->
            val c = palette.colors[i % palette.colors.size]
            Blob(
                shader = RadialGradient(
                    0f, 0f, 1f,
                    intArrayOf(withAlpha(c, 200), withAlpha(c, 80), withAlpha(c, 0)),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                ),
                baseRadius = big * rand(0.28f, 0.42f),
                fx = rand(0.08f, 0.2f), fy = rand(0.08f, 0.2f), fr = rand(0.1f, 0.25f),
                px = rand(0f, 6.28f), py = rand(0f, 6.28f), pr = rand(0f, 6.28f),
                ax = rand(0.25f, 0.4f), ay = rand(0.25f, 0.4f)
            )
        }
        specks = List(SPECK_COUNT) {
            Speck(
                x = rand(0f, w), y = rand(0f, h), size = dp(rand(0.8f, 1.8f)),
                phase = rand(0f, 6.28f), freq = rand(0.6f, 1.8f)
            )
        }
    }

    override fun onUpdate(dt: Float) {
        // Everything is a function of time; nothing to integrate.
    }

    override fun onDraw(canvas: Canvas) {
        for (b in blobs) {
            val cx = w * (0.5f + b.ax * wave(b.fx, b.px))
            val cy = h * (0.5f + b.ay * wave(b.fy, b.py))
            val r = b.baseRadius * (1f + 0.15f * wave(b.fr, b.pr))
            blobPaint.shader = b.shader
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(r, r)
            canvas.drawCircle(0f, 0f, 1f, blobPaint)
            canvas.restore()
        }
        for (s in specks) {
            val tw = 0.5f + 0.5f * wave(s.freq, s.phase)
            speckPaint.alpha = (30 + 160 * tw).toInt()
            canvas.drawCircle(s.x, s.y, s.size, speckPaint)
        }
    }

    private companion object {
        const val BLOB_COUNT = 5
        const val SPECK_COUNT = 40
    }
}
