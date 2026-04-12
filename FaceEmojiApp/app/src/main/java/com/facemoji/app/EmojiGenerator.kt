package com.facemoji.app

import android.graphics.*
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark

/**
 * Generates a standard-looking emoji (always yellow, same eyes, only
 * expression/mouth changes) — matching the Apple/Google/Twemoji aesthetic.
 *
 * Expression is driven by smilingProbability + geometric fallback.
 * No skin tone, no hair, no presets — just a clean readable emoji.
 */
class EmojiGenerator {

    companion object {
        const val SIZE = 512
        // Colours that match the real standard emoji set
        private val FACE_YELLOW = Color.parseColor("#FFDB4D")
        private val FACE_SHADOW = Color.parseColor("#E6C135")   // slightly darker for depth ring
        private val OUTLINE     = Color.parseColor("#1F1F1F")
        private val EYE_WHITE   = Color.WHITE
        private val EYE_DARK    = Color.parseColor("#1F1F1F")
        private val LIP_DARK    = Color.parseColor("#6B1515")
        private val TEETH_WHITE = Color.WHITE
        private val TEAR_BLUE   = Color.parseColor("#5BA4CF")
    }

    enum class Expression { BIG_SMILE, SMILE, SLIGHT_SMILE, NEUTRAL, SAD, SLEEPY }

    fun resolveExpression(face: Face): Expression {
        val s = face.smilingProbability ?: estimateSmileGeometrically(face)
        val l = face.leftEyeOpenProbability  ?: 1f
        val r = face.rightEyeOpenProbability ?: 1f
        return when {
            l < 0.30f && r < 0.30f -> Expression.SLEEPY
            s >= 0.70f             -> Expression.BIG_SMILE
            s >= 0.45f             -> Expression.SMILE
            s >= 0.22f             -> Expression.SLIGHT_SMILE
            s >= 0.10f             -> Expression.NEUTRAL
            else                   -> Expression.SAD
        }
    }

    /** Generate a 512×512 emoji bitmap for the given expression. */
    fun generate(face: Face, @Suppress("UNUSED_PARAMETER") src: android.graphics.Bitmap): Bitmap =
        generateForExpression(resolveExpression(face))

    /** Generate directly from an expression enum (used by the keyboard). */
    fun generateForExpression(expr: Expression): Bitmap {
        val bmp    = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG)

        val cx = SIZE / 2f
        val cy = SIZE / 2f
        val r  = SIZE * 0.42f
        val ow = SIZE * 0.030f   // outline stroke width

        // ── Face fill ────────────────────────────────────────────────────────
        paint.style = Paint.Style.FILL
        paint.color = FACE_YELLOW
        canvas.drawCircle(cx, cy, r, paint)

        // Subtle bottom-edge shadow to give depth like real emojis
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ow * 2.5f
            color = FACE_SHADOW
            alpha = 120
        }
        canvas.drawArc(cx - r + ow, cy - r + ow, cx + r - ow, cy + r - ow, 30f, 120f, false, shadowPaint)

        // ── Face outline ─────────────────────────────────────────────────────
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ow
        paint.color = OUTLINE
        canvas.drawCircle(cx, cy, r - ow / 2, paint)
        paint.style = Paint.Style.FILL

        // ── Standard eyes (same for all expressions) ─────────────────────────
        val eyeY   = cy - r * 0.12f
        val eyeRx  = r * 0.13f
        val eyeRy  = r * 0.16f
        val eyeLX  = cx - r * 0.31f
        val eyeRX  = cx + r * 0.31f

        if (expr == Expression.SLEEPY) {
            // Closed crescent eyes
            drawClosedEye(canvas, eyeLX, eyeY, eyeRx, ow, paint)
            drawClosedEye(canvas, eyeRX, eyeY, eyeRx, ow, paint)
        } else {
            drawOpenEye(canvas, eyeLX, eyeY, eyeRx, eyeRy, ow, paint)
            drawOpenEye(canvas, eyeRX, eyeY, eyeRx, eyeRy, ow, paint)
        }

        // ── Eyebrows ──────────────────────────────────────────────────────────
        drawEyebrows(canvas, cx, cy, r, eyeLX, eyeRX, eyeY, expr, ow, paint)

        // ── Mouth ─────────────────────────────────────────────────────────────
        drawMouth(canvas, cx, cy, r, expr, ow, paint)

        // ── Extras ────────────────────────────────────────────────────────────
        if (expr == Expression.SAD) drawTear(canvas, eyeRX, eyeY + eyeRy * 0.8f, r, paint)
        if (expr == Expression.BIG_SMILE || expr == Expression.SMILE) {
            // Subtle blush
            paint.color = Color.argb(55, 255, 90, 110)
            val blushR = r * 0.15f
            canvas.drawOval(eyeLX - r*0.34f - blushR, eyeY + r*0.30f - blushR*0.6f,
                            eyeLX - r*0.34f + blushR, eyeY + r*0.30f + blushR*0.6f, paint)
            canvas.drawOval(eyeRX + r*0.34f - blushR, eyeY + r*0.30f - blushR*0.6f,
                            eyeRX + r*0.34f + blushR, eyeY + r*0.30f + blushR*0.6f, paint)
        }

        return bmp
    }

    // ── Feature drawers ───────────────────────────────────────────────────────

    private fun drawOpenEye(
        canvas: Canvas, cx: Float, cy: Float,
        rx: Float, ry: Float, ow: Float, paint: Paint
    ) {
        // White
        paint.color = EYE_WHITE
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)
        // Iris + pupil (dark oval, standard emoji style)
        val ir = rx * 0.60f
        paint.color = EYE_DARK
        canvas.drawCircle(cx, cy, ir, paint)
        // Specular highlight
        paint.color = EYE_WHITE
        canvas.drawCircle(cx + rx * 0.28f, cy - ry * 0.28f, rx * 0.18f, paint)
        // Outline
        paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawClosedEye(
        canvas: Canvas, cx: Float, cy: Float, rx: Float, ow: Float, paint: Paint
    ) {
        paint.color = OUTLINE; paint.style = Paint.Style.STROKE
        paint.strokeWidth = ow * 1.1f; paint.strokeCap = Paint.Cap.ROUND
        val p = Path().apply { moveTo(cx - rx, cy); quadTo(cx, cy - rx * 0.4f, cx + rx, cy) }
        canvas.drawPath(p, paint)
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawEyebrows(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        lx: Float, rx: Float, eyeY: Float,
        expr: Expression, ow: Float, paint: Paint
    ) {
        val browHW = r * 0.16f
        val browY  = eyeY - r * 0.20f
        val lift   = when (expr) { Expression.SAD -> r * 0.06f; Expression.BIG_SMILE -> -r * 0.03f; else -> 0f }

        paint.color = OUTLINE; paint.style = Paint.Style.STROKE
        paint.strokeWidth = ow * 1.2f; paint.strokeCap = Paint.Cap.ROUND

        // Left brow (in image space: sad = inner corner UP = lower y)
        val lInner = browY + (if (expr == Expression.SAD) -lift else lift)
        val lOuter = browY + (if (expr == Expression.SAD) lift else -lift)
        canvas.drawPath(Path().apply {
            moveTo(lx - browHW, lOuter); quadTo(lx, browY, lx + browHW, lInner)
        }, paint)
        // Right brow
        val rInner = browY + (if (expr == Expression.SAD) -lift else lift)
        val rOuter = browY + (if (expr == Expression.SAD) lift else -lift)
        canvas.drawPath(Path().apply {
            moveTo(rx - browHW, rInner); quadTo(rx, browY, rx + browHW, rOuter)
        }, paint)

        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawMouth(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        expr: Expression, ow: Float, paint: Paint
    ) {
        val mw   = r * 0.34f   // half mouth width
        val my   = cy + r * 0.36f
        paint.strokeCap = Paint.Cap.ROUND

        when (expr) {
            Expression.BIG_SMILE -> {
                val depth = r * 0.28f
                // Dark cavity
                paint.color = LIP_DARK; paint.style = Paint.Style.FILL
                canvas.drawPath(Path().apply {
                    moveTo(cx - mw, my); quadTo(cx, my + depth * 1.7f, cx + mw, my)
                    quadTo(cx, my + depth * 0.5f, cx - mw, my)
                }, paint)
                // Teeth
                paint.color = TEETH_WHITE
                canvas.drawPath(Path().apply {
                    moveTo(cx - mw, my); quadTo(cx, my + depth * 0.45f, cx + mw, my)
                    lineTo(cx + mw, my - ow); lineTo(cx - mw, my - ow); close()
                }, paint)
                // Smile line
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow
                canvas.drawPath(Path().apply { moveTo(cx - mw, my); quadTo(cx, my + depth, cx + mw, my) }, paint)
            }
            Expression.SMILE -> {
                val depth = r * 0.16f
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow * 1.1f
                canvas.drawPath(Path().apply { moveTo(cx - mw, my); quadTo(cx, my + depth, cx + mw, my) }, paint)
            }
            Expression.SLIGHT_SMILE -> {
                val depth = r * 0.08f
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow * 1.0f
                canvas.drawPath(Path().apply { moveTo(cx - mw * 0.8f, my); quadTo(cx, my + depth, cx + mw * 0.8f, my) }, paint)
            }
            Expression.NEUTRAL -> {
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow * 1.0f
                canvas.drawLine(cx - mw * 0.7f, my, cx + mw * 0.7f, my, paint)
            }
            Expression.SAD, Expression.SLEEPY -> {
                val lift = r * 0.10f
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow * 1.0f
                canvas.drawPath(Path().apply { moveTo(cx - mw * 0.8f, my); quadTo(cx, my - lift, cx + mw * 0.8f, my) }, paint)
            }
        }

        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawTear(canvas: Canvas, eyeX: Float, eyeBottomY: Float, r: Float, paint: Paint) {
        paint.color = TEAR_BLUE
        val tx = eyeX - r * 0.08f
        val tearPath = Path().apply {
            moveTo(tx, eyeBottomY)
            cubicTo(tx - r * 0.06f, eyeBottomY + r * 0.10f,
                    tx - r * 0.06f, eyeBottomY + r * 0.18f,
                    tx,             eyeBottomY + r * 0.20f)
            cubicTo(tx + r * 0.06f, eyeBottomY + r * 0.18f,
                    tx + r * 0.06f, eyeBottomY + r * 0.10f,
                    tx,             eyeBottomY)
        }
        canvas.drawPath(tearPath, paint)
    }

    // ── Geometric smile estimation ────────────────────────────────────────────

    fun estimateSmileGeometrically(face: Face): Float {
        val ml = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position  ?: return 0.20f
        val mr = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return 0.20f
        val mb = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: return 0.20f
        val faceH = face.boundingBox.height().toFloat().coerceAtLeast(1f)
        val drop  = (mb.y - (ml.y + mr.y) / 2f) / faceH
        return ((drop - 0.03f) / 0.13f).coerceIn(0f, 1f)
    }
}
