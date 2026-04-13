package com.facemoji.app

import android.graphics.*
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs

/**
 * Generates a standard-looking emoji.
 *
 * When called from the main app (generate(face, src)):
 *   — samples the user's actual skin colour from the photo
 *   — emoji-ifies it (boosts saturation, keeps it clean/cartoon)
 *   — applies the detected expression
 *
 * When called from the keyboard (generateForExpression(expr)):
 *   — defaults to classic emoji yellow
 *
 * Expression detection blends three geometric signals + ML Kit:
 *   A) MOUTH_BOTTOM drop below corners
 *   B) Corner height vs NOSE_BASE (stable anchor)
 *   C) Mouth width / face width
 */
class EmojiGenerator {

    companion object {
        const val SIZE = 512
        val FACE_YELLOW  = Color.parseColor("#FFDB4D")   // default for keyboard
        val FACE_SHADOW  = Color.parseColor("#E6C135")
        private val OUTLINE      = Color.parseColor("#1F1F1F")
        private val EYE_WHITE    = Color.WHITE
        private val EYE_DARK     = Color.parseColor("#1F1F1F")
        private val LIP_DARK     = Color.parseColor("#6B1515")
        private val TEETH_WHITE  = Color.WHITE
        private val TEAR_BLUE    = Color.parseColor("#5BA4CF")
    }

    enum class Expression { BIG_SMILE, SMILE, SLIGHT_SMILE, NEUTRAL, SAD, SLEEPY, WINK }

    // ── Expression resolution ─────────────────────────────────────────────────

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

        return (dropScore * 0.35f + noseScore * 0.45f + widthScore * 0.20f).coerceIn(0f, 1f)
    }

    // ── Skin colour sampling ──────────────────────────────────────────────────

    /**
     * Samples the user's skin colour from [bitmap] using the face bounding box
     * and cheek landmarks, then returns an "emoji-ified" version:
     * same hue as the real skin, saturation boosted, brightness cleaned up.
     */
    fun sampleSkinColor(face: Face, bitmap: Bitmap): Int {
        val box   = face.boundingBox
        val bmpW  = bitmap.width
        val bmpH  = bitmap.height

        // Sample regions: prefer ML Kit cheek landmarks; fall back to geometric guesses
        val sampleCenters = mutableListOf<PointF>()

        face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position?.let  { sampleCenters += it }
        face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position?.let { sampleCenters += it }

        if (sampleCenters.isEmpty()) {
            // Geometric fallback: cheek spots + forehead
            val cx = box.exactCenterX()
            val cy = box.exactCenterY()
            val hw = box.width()  / 2f
            val hh = box.height() / 2f
            sampleCenters += PointF(cx - hw * 0.45f, cy + hh * 0.05f)  // left cheek
            sampleCenters += PointF(cx + hw * 0.45f, cy + hh * 0.05f)  // right cheek
            sampleCenters += PointF(cx, cy - hh * 0.30f)                // forehead
        }

        // Average pixels in a small patch around each sample centre
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
        val patchRadius = (box.width() * 0.06f).toInt().coerceAtLeast(4)

        for (pt in sampleCenters) {
            val px = pt.x.toInt()
            val py = pt.y.toInt()
            for (dx in -patchRadius..patchRadius step 2) {
                for (dy in -patchRadius..patchRadius step 2) {
                    val sx = (px + dx).coerceIn(0, bmpW - 1)
                    val sy = (py + dy).coerceIn(0, bmpH - 1)
                    val pixel = bitmap.getPixel(sx, sy)
                    rSum += Color.red(pixel)
                    gSum += Color.green(pixel)
                    bSum += Color.blue(pixel)
                    count++
                }
            }
        }

        if (count == 0) return FACE_YELLOW

        return emojiifyColor(
            (rSum / count).toInt(),
            (gSum / count).toInt(),
            (bSum / count).toInt()
        )
    }

    /**
     * Converts a raw sampled RGB colour into a clean, cartoon emoji tone:
     * — keeps the hue (so brown stays brown, olive stays olive, etc.)
     * — boosts saturation so it reads as a colour rather than grey/muted
     * — normalises brightness so the face is always well-lit / clean
     */
    private fun emojiifyColor(r: Int, g: Int, b: Int): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        // Boost saturation toward a cartoon range (0.40–0.85)
        hsv[1] = (hsv[1] + 0.18f).coerceIn(0.40f, 0.85f)
        // Keep brightness in a clean emoji range (0.72–1.00)
        hsv[2] = (hsv[2] * 1.10f + 0.05f).coerceIn(0.72f, 1.00f)
        return Color.HSVToColor(255, hsv)
    }

    /** Derive a slightly darker shade of [faceColor] for the depth shadow. */
    private fun shadowColor(faceColor: Int): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV(Color.red(faceColor), Color.green(faceColor), Color.blue(faceColor), hsv)
        hsv[2] *= 0.82f
        hsv[1] = (hsv[1] * 1.10f).coerceAtMost(1f)
        return Color.HSVToColor(255, hsv)
    }

    // ── Public generation entry points ────────────────────────────────────────

    /**
     * Main-app entry point: samples the user's skin colour from [src],
     * then draws a standard-shaped emoji with that colour + detected expression.
     */
    fun generate(face: Face, src: Bitmap): Bitmap {
        val skinColor = sampleSkinColor(face, src)
        return generateForExpression(resolveExpression(face), skinColor)
    }

    /** Keyboard entry point: always uses classic emoji yellow. */
    fun generateForExpression(expr: Expression): Bitmap =
        generateForExpression(expr, FACE_YELLOW)

    /** Core drawing function — colour is supplied by the caller. */
    fun generateForExpression(expr: Expression, faceColor: Int): Bitmap {
        val bmp    = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG)

        val cx = SIZE / 2f
        val cy = SIZE / 2f
        val r  = SIZE * 0.42f
        val ow = SIZE * 0.030f

        // Face fill
        paint.style = Paint.Style.FILL
        paint.color = faceColor
        canvas.drawCircle(cx, cy, r, paint)

        // Depth shadow (auto-derived from face colour)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ow * 2.5f
            color = shadowColor(faceColor)
            alpha = 130
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

        when (expr) {
            Expression.SLEEPY -> {
                drawClosedEye(canvas, eyeLX, eyeY, eyeRx, ow, paint)
                drawClosedEye(canvas, eyeRX, eyeY, eyeRx, ow, paint)
            }
            Expression.WINK -> {
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

        if (expr == Expression.SAD) {
            drawTear(canvas, eyeRX, eyeY + eyeRy * 0.8f, r, paint)
        }
        if (expr == Expression.BIG_SMILE || expr == Expression.SMILE || expr == Expression.WINK) {
            // Blush tinted toward the face colour for cohesion
            val blushHsv = FloatArray(3)
            Color.RGBToHSV(Color.red(faceColor), Color.green(faceColor), Color.blue(faceColor), blushHsv)
            blushHsv[1] = (blushHsv[1] + 0.20f).coerceAtMost(1f)
            blushHsv[2] = (blushHsv[2] * 0.85f)
            paint.color = Color.argb(60, Color.red(Color.HSVToColor(blushHsv)),
                Color.green(Color.HSVToColor(blushHsv)), Color.blue(Color.HSVToColor(blushHsv)))
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
        paint.color = EYE_DARK
        canvas.drawCircle(cx, cy, rx * 0.60f, paint)
        paint.color = EYE_WHITE
        canvas.drawCircle(cx + rx*0.28f, cy - ry*0.28f, rx*0.18f, paint)
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
            moveTo(cx - rx, cy); quadTo(cx, cy + ry*0.7f, cx + rx, cy); close()
        }, paint)
        paint.color = OUTLINE; paint.style = Paint.Style.STROKE
        paint.strokeWidth = ow * 1.3f; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(Path().apply {
            moveTo(cx - rx, cy); quadTo(cx, cy - ry*0.55f, cx + rx, cy)
        }, paint)
        for (i in 0..3) {
            val t  = i / 3f
            val lx = cx - rx + t * 2 * rx
            val ly = cy - ry * 0.45f * (1f - (2f*t - 1f) * (2f*t - 1f))
            canvas.drawLine(lx, ly, lx, ly - ry*0.18f, paint)
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
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        lx: Float, rx: Float, eyeY: Float,
        expr: Expression, ow: Float, paint: Paint
    ) {
        val browHW = r * 0.16f
        val browY  = eyeY - r * 0.20f
        val lift   = when (expr) {
            Expression.SAD       ->  r * 0.06f
            Expression.BIG_SMILE -> -r * 0.03f
            else                 ->  0f
        }
        paint.color = OUTLINE; paint.style = Paint.Style.STROKE
        paint.strokeWidth = ow * 1.2f; paint.strokeCap = Paint.Cap.ROUND

        val lInner = browY + (if (expr == Expression.SAD) -lift else lift)
        val lOuter = browY + (if (expr == Expression.SAD)  lift else -lift)
        canvas.drawPath(Path().apply {
            moveTo(lx - browHW, lOuter); quadTo(lx, browY, lx + browHW, lInner)
        }, paint)

        val rBrowY = if (expr == Expression.WINK) browY - r * 0.04f else browY
        val rInner = browY + (if (expr == Expression.SAD) -lift else lift)
        val rOuter = browY + (if (expr == Expression.SAD)  lift else -lift)
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
