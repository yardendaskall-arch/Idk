package com.facemoji.app

import android.graphics.*
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.pow

/**
 * Draws a custom emoji in the flat, bold style of standard Unicode emojis
 * (Apple / Google / WhatsApp aesthetic).
 *
 * Personalisation comes from:
 *  • Fitzpatrick skin tone  — detected from cheek pixels
 *  • Hair colour            — detected from above the bounding box
 *  • Eye openness           — from leftEyeOpen / rightEyeOpen probability
 *  • Expression             — from smilingProbability + mouth landmarks
 *  • Blush marks            — fade in when smiling > 50 %
 */
class EmojiGenerator {

    companion object {
        private const val SIZE = 512
        private val OUTLINE = Color.parseColor("#1A1A1A")
        private val WHITE   = Color.WHITE
        private val IRIS    = Color.parseColor("#3D6BBF")
        private val PUPIL   = Color.parseColor("#1A1A1A")
        private val LIP     = Color.parseColor("#7A1818")
        private val BLUSH   = Color.parseColor("#FF6B8A")
    }

    fun generate(face: Face, src: Bitmap): Bitmap {
        val out    = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG)

        val bounds = face.boundingBox
        val faceCX = bounds.exactCenterX()
        val faceCY = bounds.exactCenterY()
        val faceW  = bounds.width().toFloat()

        val r  = SIZE * 0.42f
        val cx = SIZE / 2f
        val cy = SIZE / 2f

        // Convert a face landmark's image position → emoji canvas position
        fun lm(type: Int): PointF? {
            val lm = face.getLandmark(type) ?: return null
            val nx = (lm.position.x - faceCX) / faceW
            val ny = (lm.position.y - faceCY) / faceW
            return PointF(cx + nx * SIZE * 0.80f, cy + ny * SIZE * 0.80f)
        }

        // Use ML Kit's probability when available; fall back to geometric estimation
        // from mouth landmark positions so expression is never just a hard-coded default.
        val smiling   = face.smilingProbability     ?: estimateSmileGeometrically(face)
        val leftOpen  = face.leftEyeOpenProbability  ?: 1f
        val rightOpen = face.rightEyeOpenProbability ?: 1f

        val leftEyePt  = lm(FaceLandmark.LEFT_EYE)   ?: PointF(cx - r * 0.34f, cy - r * 0.08f)
        val rightEyePt = lm(FaceLandmark.RIGHT_EYE)  ?: PointF(cx + r * 0.34f, cy - r * 0.08f)
        val nosePt     = lm(FaceLandmark.NOSE_BASE)
        val mouthL     = lm(FaceLandmark.MOUTH_LEFT)
        val mouthR     = lm(FaceLandmark.MOUTH_RIGHT)
        val mouthB     = lm(FaceLandmark.MOUTH_BOTTOM)
        val cheekL     = lm(FaceLandmark.LEFT_CHEEK)
        val cheekR     = lm(FaceLandmark.RIGHT_CHEEK)

        // Raw detected colours — cartoonified (saturation boost) but NOT snapped to a preset
        val skinColor = cartoonifySkin(extractAvg(src, bounds, cheekL, cheekR, cx, cy, r))
        val hairColor = cartoonifyHair(extractHair(src, bounds))
        val outlineW  = SIZE * 0.030f   // thick black outline — key to emoji look

        // ── 1. White base disc (gives clean edge to the yellow) ──────────────
        paint.color = WHITE; paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, r + outlineW / 2, paint)

        // ── 2. Face fill ──────────────────────────────────────────────────────
        paint.color = skinColor
        canvas.drawCircle(cx, cy, r, paint)

        // ── 3. Black face outline ─────────────────────────────────────────────
        paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = outlineW
        canvas.drawCircle(cx, cy, r - outlineW / 2, paint)
        paint.style = Paint.Style.FILL

        // ── 4. Hair cap ────────────────────────────────────────────────────────
        drawHairCap(canvas, cx, cy, r, hairColor, outlineW, paint)

        // ── 5. Eyebrows ────────────────────────────────────────────────────────
        drawEyebrow(canvas, leftEyePt,  r, smiling, isLeft = true,  hairColor, outlineW, paint)
        drawEyebrow(canvas, rightEyePt, r, smiling, isLeft = false, hairColor, outlineW, paint)

        // ── 6. Eyes ────────────────────────────────────────────────────────────
        val eyeR = r * 0.145f
        drawEye(canvas, leftEyePt,  eyeR, leftOpen,  outlineW, paint)
        drawEye(canvas, rightEyePt, eyeR, rightOpen, outlineW, paint)

        // ── 7. Nose (two small dots — standard emoji style) ───────────────────
        val noseC = nosePt ?: PointF(cx, cy + r * 0.14f)
        val ndot  = r * 0.038f
        paint.color = OUTLINE
        canvas.drawCircle(noseC.x - r * 0.09f, noseC.y, ndot, paint)
        canvas.drawCircle(noseC.x + r * 0.09f, noseC.y, ndot, paint)

        // ── 8. Mouth ───────────────────────────────────────────────────────────
        val ml = mouthL ?: PointF(cx - r * 0.30f, cy + r * 0.38f)
        val mr = mouthR ?: PointF(cx + r * 0.30f, cy + r * 0.38f)
        val mb = mouthB ?: PointF(cx,              cy + r * 0.55f)
        drawMouth(canvas, ml, mr, mb, smiling, r, outlineW, paint)

        // ── 9. Blush ───────────────────────────────────────────────────────────
        if (smiling > 0.45f) {
            val alpha = ((smiling - 0.45f) / 0.55f * 110).toInt().coerceIn(0, 110)
            paint.color = Color.argb(alpha, Color.red(BLUSH), Color.green(BLUSH), Color.blue(BLUSH))
            val lc = cheekL ?: PointF(cx - r * 0.62f, cy + r * 0.20f)
            val rc = cheekR ?: PointF(cx + r * 0.62f, cy + r * 0.20f)
            canvas.drawOval(lc.x - r * 0.19f, lc.y - r * 0.10f, lc.x + r * 0.19f, lc.y + r * 0.10f, paint)
            canvas.drawOval(rc.x - r * 0.19f, rc.y - r * 0.10f, rc.x + r * 0.19f, rc.y + r * 0.10f, paint)
        }

        return out
    }

    // ── Feature drawing ───────────────────────────────────────────────────────

    private fun drawHairCap(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        color: Int, ow: Float, paint: Paint
    ) {
        // Hair = part of the face circle above the hairline (roughly top 40 %)
        val hairlineY = cy - r * 0.26f
        val clip = Path().apply {
            addRect(cx - r - ow, cy - r - ow, cx + r + ow, hairlineY, Path.Direction.CW)
        }
        val faceCircle = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val hair = Path().apply { op(faceCircle, clip, Path.Op.INTERSECT) }

        paint.color = color; paint.style = Paint.Style.FILL
        canvas.drawPath(hair, paint)
        // Hairline outline
        paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow * 0.85f
        canvas.drawPath(hair, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawEyebrow(
        canvas: Canvas, eyePos: PointF, r: Float, smiling: Float,
        isLeft: Boolean, color: Int, ow: Float, paint: Paint
    ) {
        val hw    = r * 0.22f
        val baseY = eyePos.y - r * 0.20f
        // Happy = flat; neutral/sad = inner corners slightly raised
        val innerLift = if (smiling > 0.45f) 0f else r * 0.06f
        val outerLift = if (smiling > 0.45f) 0f else -r * 0.03f

        val (liftL, liftR) = if (isLeft) Pair(outerLift, innerLift) else Pair(innerLift, outerLift)

        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ow * 1.3f
        paint.strokeCap = Paint.Cap.ROUND
        val path = Path().apply {
            moveTo(eyePos.x - hw, baseY + liftL)
            quadTo(eyePos.x, baseY - hw * 0.10f, eyePos.x + hw, baseY + liftR)
        }
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawEye(
        canvas: Canvas, pos: PointF, r: Float, openProb: Float, ow: Float, paint: Paint
    ) {
        if (openProb < 0.30f) {
            // Closed — single curved line (standard emoji 😌 style)
            paint.color = OUTLINE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ow * 1.1f
            paint.strokeCap = Paint.Cap.ROUND
            val p = Path().apply {
                moveTo(pos.x - r, pos.y)
                quadTo(pos.x, pos.y - r * 0.35f, pos.x + r, pos.y)
            }
            canvas.drawPath(p, paint)
            paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
            return
        }

        val vScale = openProb.coerceIn(0.45f, 1.0f)
        val rx = r; val ry = r * vScale

        // White sclera
        paint.color = WHITE; paint.style = Paint.Style.FILL
        canvas.drawOval(pos.x - rx, pos.y - ry, pos.x + rx, pos.y + ry, paint)
        // Iris
        val irisR = rx * 0.58f
        paint.color = IRIS
        canvas.drawCircle(pos.x, pos.y, irisR, paint)
        // Pupil
        paint.color = PUPIL
        canvas.drawCircle(pos.x, pos.y, irisR * 0.54f, paint)
        // Specular highlight (standard emoji feature)
        paint.color = WHITE
        canvas.drawCircle(pos.x + rx * 0.28f, pos.y - ry * 0.28f, rx * 0.19f, paint)
        // Bold outline
        paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow
        canvas.drawOval(pos.x - rx, pos.y - ry, pos.x + rx, pos.y + ry, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawMouth(
        canvas: Canvas,
        ml: PointF, mr: PointF, mb: PointF,
        smiling: Float, r: Float, ow: Float, paint: Paint
    ) {
        val midX  = (ml.x + mr.x) / 2f
        val baseY = (ml.y + mr.y) / 2f
        paint.strokeCap = Paint.Cap.ROUND

        when {
            smiling > 0.50f -> {
                val depth = r * 0.30f * smiling
                // Filled dark mouth
                val mouth = Path().apply {
                    moveTo(ml.x, ml.y)
                    quadTo(midX, baseY + depth * 1.7f, mr.x, mr.y)
                    quadTo(midX, baseY + depth * 0.55f, ml.x, ml.y)
                }
                paint.color = LIP; paint.style = Paint.Style.FILL
                canvas.drawPath(mouth, paint)
                // White teeth strip
                val teeth = Path().apply {
                    moveTo(ml.x, ml.y)
                    quadTo(midX, baseY + depth * 0.42f, mr.x, mr.y)
                    lineTo(mr.x, ml.y); close()
                }
                paint.color = WHITE
                canvas.drawPath(teeth, paint)
                // Bold smile outline
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow
                val outline = Path().apply {
                    moveTo(ml.x, ml.y)
                    quadTo(midX, baseY + depth, mr.x, mr.y)
                }
                canvas.drawPath(outline, paint)
            }
            smiling > 0.25f -> {
                val d = r * 0.16f * smiling
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow * 1.05f
                val p = Path().apply { moveTo(ml.x, ml.y); quadTo(midX, baseY + d, mr.x, mr.y) }
                canvas.drawPath(p, paint)
            }
            smiling < 0.15f -> {
                // Frown
                val lift = r * 0.10f
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow * 1.05f
                val p = Path().apply { moveTo(ml.x, ml.y); quadTo(midX, baseY - lift, mr.x, mr.y) }
                canvas.drawPath(p, paint)
            }
            else -> {
                paint.color = OUTLINE; paint.style = Paint.Style.STROKE; paint.strokeWidth = ow * 1.05f
                canvas.drawLine(ml.x, baseY, mr.x, baseY, paint)
            }
        }

        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    // ── Expression geometry ───────────────────────────────────────────────────

    /**
     * Estimates smile probability (0–1) purely from the positions of the mouth
     * landmarks. Used when ML Kit's smilingProbability is null (happens on some
     * devices / lighting conditions).
     *
     * Principle: when smiling the mouth opens and MOUTH_BOTTOM drops further
     * below the corner midpoint relative to face height.
     */
    private fun estimateSmileGeometrically(face: Face): Float {
        val ml = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position  ?: return 0.3f
        val mr = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return 0.3f
        val mb = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: return 0.3f
        val faceH = face.boundingBox.height().toFloat().coerceAtLeast(1f)

        // Vertical drop from corner midpoint to mouth bottom, normalised by face height.
        // Closed neutral ≈ 0.03–0.05 · faceH
        // Broad smile     ≈ 0.10–0.18 · faceH
        val cornerMidY = (ml.y + mr.y) / 2f
        val drop = (mb.y - cornerMidY) / faceH

        return ((drop - 0.03f) / 0.13f).coerceIn(0f, 1f)
    }

    // ── Colour helpers ────────────────────────────────────────────────────────

    private fun extractAvg(
        bmp: Bitmap, bounds: Rect,
        cheekL: PointF?, cheekR: PointF?,
        cx: Float, cy: Float, r: Float
    ): Int {
        val pts = mutableListOf<Pair<Int,Int>>()
        fun addBmpPt(ep: PointF?) {
            ep ?: return
            val ix = (bounds.exactCenterX() + (ep.x - cx) / (SIZE * 0.80f) * bounds.width()).toInt().coerceIn(0, bmp.width-1)
            val iy = (bounds.exactCenterY() + (ep.y - cy) / (SIZE * 0.80f) * bounds.width()).toInt().coerceIn(0, bmp.height-1)
            pts += ix to iy
        }
        addBmpPt(cheekL); addBmpPt(cheekR)
        if (pts.isEmpty()) {
            val step = bounds.width() / 7
            for (dy in -1..1) for (dx in -1..1)
                pts += (bounds.centerX() + dx * step).coerceIn(0, bmp.width-1) to
                       (bounds.centerY() + dy * step).coerceIn(0, bmp.height-1)
        }
        var rr = 0L; var gg = 0L; var bb = 0L
        pts.forEach { (x, y) -> val c = bmp.getPixel(x, y); rr += Color.red(c); gg += Color.green(c); bb += Color.blue(c) }
        return Color.rgb((rr / pts.size).toInt(), (gg / pts.size).toInt(), (bb / pts.size).toInt())
    }

    private fun extractHair(bmp: Bitmap, bounds: Rect): Int {
        val cx   = bounds.centerX()
        val topY = (bounds.top - bounds.height() * 0.05f).toInt().coerceIn(0, bmp.height-1)
        val step = bounds.width() / 8
        var rr = 0L; var gg = 0L; var bb = 0L
        for (dx in -3..3) {
            val px = (cx + dx * step).coerceIn(0, bmp.width-1)
            val c  = bmp.getPixel(px, topY)
            rr += Color.red(c); gg += Color.green(c); bb += Color.blue(c)
        }
        return Color.rgb((rr / 7).toInt(), (gg / 7).toInt(), (bb / 7).toInt())
    }

    /**
     * Takes the raw sampled skin colour and makes it look like an emoji skin
     * tone without snapping to a fixed palette: boost saturation by ~30 %,
     * ensure it's bright enough to read as a face, and add a tiny warm tint.
     */
    private fun cartoonifySkin(raw: Int): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV(Color.red(raw), Color.green(raw), Color.blue(raw), hsv)
        hsv[1] = (hsv[1] * 1.35f).coerceIn(0f, 1f)   // boost saturation
        hsv[2] = hsv[2].coerceAtLeast(0.55f)           // minimum brightness
        val out = Color.HSVToColor(hsv)
        // Subtle warm tint: nudge red up, blue down
        return Color.rgb(
            minOf(255, Color.red(out) + 12),
            Color.green(out),
            maxOf(0,   Color.blue(out) - 10)
        )
    }

    /**
     * Takes the raw sampled hair colour and darkens/saturates it slightly
     * so it reads clearly as hair against the skin.
     */
    private fun cartoonifyHair(raw: Int): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV(Color.red(raw), Color.green(raw), Color.blue(raw), hsv)
        hsv[1] = (hsv[1] * 1.25f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * 0.80f)                      // darken slightly vs skin
        return Color.HSVToColor(hsv)
    }
}
