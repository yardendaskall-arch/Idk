package com.facemoji.app

import android.graphics.*
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs

/**
 * Generates a standard-looking emoji (always yellow, same eyes, only
 * expression/mouth changes) — matching the Apple/Google/Twemoji aesthetic.
 *
 * Expression is resolved from three sources blended together:
 *   1. ML Kit smilingProbability (most reliable when available)
 *   2. MOUTH_BOTTOM drop below mouth corners   (signal A)
 *   3. Mouth-corner height relative to NOSE_BASE (signal B — more stable anchor)
 *   4. Mouth width relative to face width        (signal C)
 * Plus dedicated wink / sleepy checks from eye-open probabilities.
 */
class EmojiGenerator {

    companion object {
        const val SIZE = 512
        private val FACE_YELLOW = Color.parseColor("#FFDB4D")
        private val FACE_SHADOW = Color.parseColor("#E6C135")
        private val OUTLINE     = Color.parseColor("#1F1F1F")
        private val EYE_WHITE   = Color.WHITE
        private val EYE_DARK    = Color.parseColor("#1F1F1F")
        private val LIP_DARK    = Color.parseColor("#6B1515")
        private val TEETH_WHITE = Color.WHITE
        private val TEAR_BLUE   = Color.parseColor("#5BA4CF")
    }

    enum class Expression { BIG_SMILE, SMILE, SLIGHT_SMILE, NEUTRAL, SAD, SLEEPY, WINK }

    // ── Expression resolution ─────────────────────────────────────────────────

    fun resolveExpression(face: Face): Expression {
        val l = face.leftEyeOpenProbability  ?: 1f
        val r = face.rightEyeOpenProbability ?: 1f

        // Both eyes closed → sleepy
        if (l < 0.28f && r < 0.28f) return Expression.SLEEPY

        // One eye closed, other clearly open → wink
        if ((l < 0.28f && r > 0.65f) || (r < 0.28f && l > 0.65f)) return Expression.WINK

        // Composite smile score
        val mlSmile  = face.smilingProbability
        val geoSmile = estimateSmileGeometrically(face)
        val s = if (mlSmile != null) mlSmile * 0.60f + geoSmile * 0.40f else geoSmile

        return when {
            s >= 0.58f -> Expression.BIG_SMILE
            s >= 0.36f -> Expression.SMILE
            s >= 0.18f -> Expression.SLIGHT_SMILE
            s >= 0.09f -> Expression.NEUTRAL
            else       -> Expression.SAD
        }
    }

    /**
     * Three-signal geometric smile estimator.
     *
     * Signal A — MOUTH_BOTTOM drop:
     *   When smiling, corners rise → mb drops further below corners → score rises.
     *   Neutral ≈ 0.05 face-heights; big smile ≈ 0.14.
     *
     * Signal B — corner height vs NOSE_BASE:
     *   Nose doesn't move with expression, so it's a stable anchor.
     *   When smiling, corners lift closer to the nose → score rises.
     *   Neutral dist ≈ 0.11; big smile ≈ 0.05.
     *
     * Signal C — mouth width:
     *   Smiling mouth is wider relative to face width.
     *   Neutral ≈ 0.26 face-widths; big smile ≈ 0.38.
     */
    fun estimateSmileGeometrically(face: Face): Float {
        val ml   = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position   ?: return 0.25f
        val mr   = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position  ?: return 0.25f
        val mb   = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: return 0.25f
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position

        val faceH      = face.boundingBox.height().toFloat().coerceAtLeast(1f)
        val faceW      = face.boundingBox.width().toFloat().coerceAtLeast(1f)
        val cornerMidY = (ml.y + mr.y) / 2f

        // Signal A: mouth-bottom drop (0 = frown, 1 = big smile)
        val dropScore = ((mb.y - cornerMidY) / faceH - 0.03f) / 0.12f

        // Signal B: corner height relative to nose (stable anchor)
        val noseScore = if (nose != null) {
            // cornerMidY - nose.y > 0 (corners are below nose in image coords)
            // This distance shrinks when smiling (corners rise toward nose)
            val dist = (cornerMidY - nose.y) / faceH
            (0.12f - dist) / 0.08f     // dist≈0.12 → neutral, dist≈0.04 → big smile
        } else dropScore               // fall back to signal A if nose missing

        // Signal C: mouth width relative to face width
        val mouthWidth = abs(mr.x - ml.x) / faceW
        val widthScore = (mouthWidth - 0.26f) / 0.14f   // 0.26=neutral, 0.40=big smile

        val composite = dropScore * 0.35f + noseScore * 0.45f + widthScore * 0.20f
        return composite.coerceIn(0f, 1f)
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
        val ow = SIZE * 0.030f

        // Face fill
        paint.style = Paint.Style.FILL
        paint.color = FACE_YELLOW
        canvas.drawCircle(cx, cy, r, paint)

        // Depth shadow
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ow * 2.5f
            color = FACE_SHADOW
            alpha = 120
        }
        canvas.drawArc(cx-r+ow, cy-r+ow, cx+r-ow, cy+r-ow, 30f, 120f, false, shadowPaint)

        // Face outline
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ow
        paint.color = OUTLINE
        canvas.drawCircle(cx, cy, r - ow/2, paint)
        paint.style = Paint.Style.FILL

        val eyeY  = cy - r * 0.12f
        val eyeRx = r * 0.13f
        val eyeRy = r * 0.16f
        val eyeLX = cx - r * 0.31f
        val eyeRX = cx + r * 0.31f

        // Eyes
        when (expr) {
            Expression.SLEEPY -> {
                drawClosedEye(canvas, eyeLX, eyeY, eyeRx, ow, paint)
                drawClosedEye(canvas, eyeRX, eyeY, eyeRx, ow, paint)
            }
            Expression.WINK -> {
                // Right eye open, left eye winked (wink is typically one-sided)
                drawOpenEye(canvas, eyeLX, eyeY, eyeRx, eyeRy, ow, paint)
                drawWinkEye(canvas, eyeRX, eyeY, eyeRx, eyeRy, ow, paint)
            }
            else -> {
                drawOpenEye(canvas, eyeLX, eyeY, eyeRx, eyeRy, ow, paint)
                drawOpenEye(canvas, eyeRX, eyeY, eyeRx, eyeRy, ow, paint)
            }
        }

        drawEyebrows(canvas, cx, cy, r, eyeLX, eyeRX, eyeY, expr, ow, paint)
        drawMouth(canvas, cx, cy, r, expr, ow, paint)

        // Extras
        if (expr == Expression.SAD) {
            drawTear(canvas, eyeRX, eyeY + eyeRy * 0.8f, r, paint)
        }
        if (expr == Expression.BIG_SMILE || expr == Expression.SMILE || expr == Expression.WINK) {
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
        paint.color = EYE_WHITE
        canvas.drawOval(cx-rx, cy-ry, cx+rx, cy+ry, paint)
        val ir = rx * 0.60f
        paint.color = EYE_DARK
        canvas.drawCircle(cx, cy, ir, paint)
        paint.color = EYE_WHITE
        canvas.drawCircle(cx + rx*0.28f, cy - ry*0.28f, rx*0.18f, paint)
        paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow
        canvas.drawOval(cx-rx, cy-ry, cx+rx, cy+ry, paint)
        paint.style = Paint.Style.FILL
    }

    /** Wink eye: top half closed down into a curved line with lashes. */
    private fun drawWinkEye(
        canvas: Canvas, cx: Float, cy: Float,
        rx: Float, ry: Float, ow: Float, paint: Paint
    ) {
        // Draw the bottom half of the eye (still open at bottom)
        paint.color = EYE_WHITE
        val path = Path().apply {
            moveTo(cx - rx, cy)
            quadTo(cx, cy + ry * 0.7f, cx + rx, cy)
            close()
        }
        canvas.drawPath(path, paint)
        // Closed upper lid line — a downward curve (wink crescent)
        paint.color = OUTLINE; paint.style = Paint.Style.STROKE
        paint.strokeWidth = ow * 1.3f; paint.strokeCap = Paint.Cap.ROUND
        val winkPath = Path().apply {
            moveTo(cx - rx, cy)
            quadTo(cx, cy - ry * 0.55f, cx + rx, cy)
        }
        canvas.drawPath(winkPath, paint)
        // Lashes on the closed lid
        val lashCount = 3
        for (i in 0..lashCount) {
            val t = i / lashCount.toFloat()
            val lx = cx - rx + t * (2 * rx)
            val ly = cy - ry * 0.45f * (1f - (2f * t - 1f) * (2f * t - 1f))
            canvas.drawLine(lx, ly, lx, ly - ry * 0.18f, paint)
        }
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawClosedEye(
        canvas: Canvas, cx: Float, cy: Float, rx: Float, ow: Float, paint: Paint
    ) {
        paint.color = OUTLINE; paint.style = Paint.Style.STROKE
        paint.strokeWidth = ow * 1.1f; paint.strokeCap = Paint.Cap.ROUND
        val p = Path().apply { moveTo(cx-rx, cy); quadTo(cx, cy - rx*0.4f, cx+rx, cy) }
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
        val lift   = when (expr) {
            Expression.SAD       -> r * 0.06f
            Expression.BIG_SMILE -> -r * 0.03f
            else                 -> 0f
        }

        paint.color = OUTLINE; paint.style = Paint.Style.STROKE
        paint.strokeWidth = ow * 1.2f; paint.strokeCap = Paint.Cap.ROUND

        val lInner = browY + (if (expr == Expression.SAD) -lift else lift)
        val lOuter = browY + (if (expr == Expression.SAD)  lift else -lift)
        canvas.drawPath(Path().apply {
            moveTo(lx - browHW, lOuter); quadTo(lx, browY, lx + browHW, lInner)
        }, paint)

        val rInner = browY + (if (expr == Expression.SAD) -lift else lift)
        val rOuter = browY + (if (expr == Expression.SAD)  lift else -lift)

        // For wink: raise the right (winking) brow slightly
        val rBrowY = if (expr == Expression.WINK) browY - r * 0.04f else browY
        canvas.drawPath(Path().apply {
            moveTo(rx - browHW, rInner); quadTo(rx, rBrowY, rx + browHW, rOuter)
        }, paint)

        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawMouth(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        expr: Expression, ow: Float, paint: Paint
    ) {
        val mw = r * 0.34f
        val my = cy + r * 0.36f
        paint.strokeCap = Paint.Cap.ROUND

        when (expr) {
            Expression.BIG_SMILE -> {
                val depth = r * 0.28f
                paint.color = LIP_DARK; paint.style = Paint.Style.FILL
                canvas.drawPath(Path().apply {
                    moveTo(cx-mw, my); quadTo(cx, my+depth*1.7f, cx+mw, my)
                    quadTo(cx, my+depth*0.5f, cx-mw, my)
                }, paint)
                paint.color = TEETH_WHITE
                canvas.drawPath(Path().apply {
                    moveTo(cx-mw, my); quadTo(cx, my+depth*0.45f, cx+mw, my)
                    lineTo(cx+mw, my-ow); lineTo(cx-mw, my-ow); close()
                }, paint)
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow
                canvas.drawPath(Path().apply { moveTo(cx-mw, my); quadTo(cx, my+depth, cx+mw, my) }, paint)
            }
            Expression.SMILE -> {
                val depth = r * 0.16f
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow*1.1f
                canvas.drawPath(Path().apply { moveTo(cx-mw, my); quadTo(cx, my+depth, cx+mw, my) }, paint)
            }
            Expression.SLIGHT_SMILE, Expression.WINK -> {
                val depth = r * 0.09f
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow
                canvas.drawPath(Path().apply {
                    moveTo(cx-mw*0.8f, my); quadTo(cx, my+depth, cx+mw*0.8f, my)
                }, paint)
            }
            Expression.NEUTRAL -> {
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow
                canvas.drawLine(cx-mw*0.7f, my, cx+mw*0.7f, my, paint)
            }
            Expression.SAD, Expression.SLEEPY -> {
                val lift = r * 0.10f
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow
                canvas.drawPath(Path().apply {
                    moveTo(cx-mw*0.8f, my); quadTo(cx, my-lift, cx+mw*0.8f, my)
                }, paint)
            }
        }

        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawTear(canvas: Canvas, eyeX: Float, eyeBottomY: Float, r: Float, paint: Paint) {
        paint.color = TEAR_BLUE
        val tx = eyeX - r*0.08f
        canvas.drawPath(Path().apply {
            moveTo(tx, eyeBottomY)
            cubicTo(tx-r*0.06f, eyeBottomY+r*0.10f, tx-r*0.06f, eyeBottomY+r*0.18f, tx, eyeBottomY+r*0.20f)
            cubicTo(tx+r*0.06f, eyeBottomY+r*0.18f, tx+r*0.06f, eyeBottomY+r*0.10f, tx, eyeBottomY)
        }, paint)
    }
}
