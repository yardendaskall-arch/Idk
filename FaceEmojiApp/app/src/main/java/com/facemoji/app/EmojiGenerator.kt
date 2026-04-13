package com.facemoji.app

import android.graphics.*
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs

/**
 * Generates a standard-style emoji whose expression tracks the real face.
 * Appearance is always classic emoji yellow with fixed eye/face positions.
 * Only the mouth shape and eyebrow tilt change per detected expression.
 */
class EmojiGenerator {

    companion object {
        const val SIZE          = 512
        val FACE_YELLOW         = Color.parseColor("#FFDB4D")
        private val OUTLINE     = Color.parseColor("#1F1F1F")
        private val EYE_WHITE   = Color.WHITE
        private val EYE_DARK    = Color.parseColor("#1F1F1F")
        private val LIP_DARK    = Color.parseColor("#6B1515")
        private val TEETH_WHITE = Color.WHITE
        private val TEAR_BLUE   = Color.parseColor("#5BA4CF")
    }

    enum class Expression { BIG_SMILE, SMILE, SLIGHT_SMILE, NEUTRAL, SAD, SLEEPY, WINK }

    // ── Expression resolution ──────────────────────────────────────────────────

    fun resolveExpression(face: Face): Expression {
        val l = face.leftEyeOpenProbability  ?: 1f
        val r = face.rightEyeOpenProbability ?: 1f
        if (l < 0.28f && r < 0.28f) return Expression.SLEEPY
        if ((l < 0.28f && r > 0.65f) || (r < 0.28f && l > 0.65f)) return Expression.WINK

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

    fun estimateSmileGeometrically(face: Face): Float {
        val ml   = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position   ?: return 0.25f
        val mr   = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position  ?: return 0.25f
        val mb   = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: return 0.25f
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val faceH      = face.boundingBox.height().toFloat().coerceAtLeast(1f)
        val faceW      = face.boundingBox.width().toFloat().coerceAtLeast(1f)
        val cornerMidY = (ml.y + mr.y) / 2f
        val dropScore  = ((mb.y - cornerMidY) / faceH - 0.03f) / 0.12f
        val noseScore  = if (nose != null) (0.12f - (cornerMidY - nose.y) / faceH) / 0.08f else dropScore
        val widthScore = (abs(mr.x - ml.x) / faceW - 0.26f) / 0.14f
        return (dropScore*0.35f + noseScore*0.45f + widthScore*0.20f).coerceIn(0f, 1f)
    }

    // ── Public entry points ────────────────────────────────────────────────────

    /** Main-app: detect expression from the real face, draw standard yellow emoji. */
    fun generate(face: Face, @Suppress("UNUSED_PARAMETER") src: Bitmap): Bitmap =
        generateForExpression(resolveExpression(face))

    /** Keyboard / direct: draw standard yellow emoji for a given expression. */
    fun generateForExpression(expr: Expression): Bitmap = drawEmoji(expr)

    /** Keyboard overload kept for compatibility — color parameter is ignored. */
    fun generateForExpression(expr: Expression, @Suppress("UNUSED_PARAMETER") faceColor: Int): Bitmap =
        drawEmoji(expr)

    // ── Core renderer ──────────────────────────────────────────────────────────

    private fun drawEmoji(expr: Expression): Bitmap {
        val bmp    = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx     = SIZE / 2f;  val cy = SIZE / 2f
        val r      = SIZE * 0.42f
        val ow     = SIZE * 0.030f

        // Fixed feature positions — same for every expression
        val eyeLX   = cx - r * 0.31f;  val eyeRX = cx + r * 0.31f
        val eyeY    = cy - r * 0.12f
        val eyeHW   = r * 0.13f;       val eyeHH = r * 0.16f
        val mouthY  = cy + r * 0.36f
        val mouthHW = r * 0.34f

        // Face fill — always classic emoji yellow circle
        paint.style = Paint.Style.FILL; paint.color = FACE_YELLOW
        canvas.drawCircle(cx, cy, r, paint)

        // Face outline
        paint.style = Paint.Style.STROKE; paint.strokeWidth = ow; paint.color = OUTLINE
        canvas.drawCircle(cx, cy, r - ow / 2f, paint)
        paint.style = Paint.Style.FILL

        // Eyes — shape changes only for SLEEPY and WINK
        when (expr) {
            Expression.SLEEPY -> {
                drawClosedEye(canvas, eyeLX, eyeY, eyeHW, ow, paint)
                drawClosedEye(canvas, eyeRX, eyeY, eyeHW, ow, paint)
            }
            Expression.WINK -> {
                drawOpenEye(canvas, eyeLX, eyeY, eyeHW, eyeHH, ow, paint)
                drawWinkEye(canvas, eyeRX, eyeY, eyeHW, eyeHH, ow, paint)
            }
            else -> {
                drawOpenEye(canvas, eyeLX, eyeY, eyeHW, eyeHH, ow, paint)
                drawOpenEye(canvas, eyeRX, eyeY, eyeHW, eyeHH, ow, paint)
            }
        }

        // Eyebrows — tilt changes for SAD; slightly lowered for BIG_SMILE
        val browHW = r * 0.14f
        val browY  = eyeY - eyeHH * 1.6f
        drawEyebrows(canvas, eyeLX, eyeRX, browY, browHW, expr, ow, paint)

        // Mouth — shape is the only thing that changes per expression
        drawMouth(canvas, cx, mouthY, mouthHW, expr, ow, paint)

        // Tear drop for SAD
        if (expr == Expression.SAD)
            drawTear(canvas, eyeRX, eyeY + eyeHH * 0.8f, r, paint)

        // Blush spots for happy expressions
        if (expr in listOf(Expression.BIG_SMILE, Expression.SMILE, Expression.WINK)) {
            paint.color = Color.argb(55, 255, 100, 100)
            val br      = r * 0.14f
            val blushY  = (eyeY + mouthY) / 2f
            val blushOff = r * 0.38f
            canvas.drawOval(cx - blushOff - br, blushY - br*0.6f, cx - blushOff + br, blushY + br*0.6f, paint)
            canvas.drawOval(cx + blushOff - br, blushY - br*0.6f, cx + blushOff + br, blushY + br*0.6f, paint)
        }

        return bmp
    }

    // ── Feature drawers ────────────────────────────────────────────────────────

    private fun drawOpenEye(
        canvas: Canvas, cx: Float, cy: Float,
        rx: Float, ry: Float, ow: Float, paint: Paint
    ) {
        paint.color = EYE_WHITE
        canvas.drawOval(cx-rx, cy-ry, cx+rx, cy+ry, paint)
        paint.color = EYE_DARK
        canvas.drawCircle(cx, cy, rx * 0.60f, paint)
        paint.color = EYE_WHITE
        canvas.drawCircle(cx + rx*0.28f, cy - ry*0.28f, rx * 0.18f, paint)
        paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow
        canvas.drawOval(cx-rx, cy-ry, cx+rx, cy+ry, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawWinkEye(
        canvas: Canvas, cx: Float, cy: Float,
        rx: Float, ry: Float, ow: Float, paint: Paint
    ) {
        paint.color = EYE_WHITE
        canvas.drawPath(Path().apply {
            moveTo(cx-rx, cy); quadTo(cx, cy+ry*0.7f, cx+rx, cy); close()
        }, paint)
        paint.color = OUTLINE; paint.style = Paint.Style.STROKE
        paint.strokeWidth = ow * 1.3f; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(Path().apply { moveTo(cx-rx, cy); quadTo(cx, cy-ry*0.55f, cx+rx, cy) }, paint)
        for (i in 0..3) {
            val t  = i / 3f
            val lx = cx - rx + t * 2 * rx
            val ly = cy - ry * 0.45f * (1f - (2f*t - 1f) * (2f*t - 1f))
            canvas.drawLine(lx, ly, lx, ly - ry * 0.18f, paint)
        }
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawClosedEye(
        canvas: Canvas, cx: Float, cy: Float, rx: Float, ow: Float, paint: Paint
    ) {
        paint.color = OUTLINE; paint.style = Paint.Style.STROKE
        paint.strokeWidth = ow * 1.1f; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(Path().apply { moveTo(cx-rx, cy); quadTo(cx, cy-rx*0.4f, cx+rx, cy) }, paint)
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawEyebrows(
        canvas: Canvas, lx: Float, rx: Float, browY: Float,
        browHW: Float, expr: Expression, ow: Float, paint: Paint
    ) {
        val lift = when (expr) {
            Expression.SAD       ->  browHW * 0.40f
            Expression.BIG_SMILE -> -browHW * 0.18f
            else                 ->  0f
        }
        paint.color = OUTLINE; paint.style = Paint.Style.STROKE
        paint.strokeWidth = ow * 1.2f; paint.strokeCap = Paint.Cap.ROUND

        val lInner = browY + (if (expr == Expression.SAD) -lift else lift)
        val lOuter = browY + (if (expr == Expression.SAD)  lift else -lift)
        canvas.drawPath(Path().apply {
            moveTo(lx - browHW, lOuter); quadTo(lx, browY, lx + browHW, lInner)
        }, paint)

        val rBrowY = if (expr == Expression.WINK) browY - browHW * 0.25f else browY
        val rInner = browY + (if (expr == Expression.SAD) -lift else lift)
        val rOuter = browY + (if (expr == Expression.SAD)  lift else -lift)
        canvas.drawPath(Path().apply {
            moveTo(rx - browHW, rInner); quadTo(rx, rBrowY, rx + browHW, rOuter)
        }, paint)

        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawMouth(
        canvas: Canvas, cx: Float, my: Float, mw: Float,
        expr: Expression, ow: Float, paint: Paint
    ) {
        paint.strokeCap = Paint.Cap.ROUND
        when (expr) {
            Expression.BIG_SMILE -> {
                val depth = mw * 0.82f
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
                val depth = mw * 0.47f
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow * 1.1f
                canvas.drawPath(Path().apply { moveTo(cx-mw, my); quadTo(cx, my+depth, cx+mw, my) }, paint)
            }
            Expression.SLIGHT_SMILE, Expression.WINK -> {
                val depth = mw * 0.26f
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow
                canvas.drawPath(Path().apply {
                    moveTo(cx-mw*0.8f, my); quadTo(cx, my+depth, cx+mw*0.8f, my)
                }, paint)
            }
            Expression.NEUTRAL -> {
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow
                canvas.drawLine(cx - mw*0.70f, my, cx + mw*0.70f, my, paint)
            }
            Expression.SAD, Expression.SLEEPY -> {
                val lift = mw * 0.29f
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
        val tx = eyeX - r * 0.08f
        canvas.drawPath(Path().apply {
            moveTo(tx, eyeBottomY)
            cubicTo(tx-r*0.06f, eyeBottomY+r*0.10f, tx-r*0.06f, eyeBottomY+r*0.18f, tx, eyeBottomY+r*0.20f)
            cubicTo(tx+r*0.06f, eyeBottomY+r*0.18f, tx+r*0.06f, eyeBottomY+r*0.10f, tx, eyeBottomY)
        }, paint)
    }
}
