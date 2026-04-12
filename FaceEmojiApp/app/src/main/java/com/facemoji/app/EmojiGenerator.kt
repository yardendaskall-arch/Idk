package com.facemoji.app

import android.graphics.*
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark

/**
 * Draws a custom emoji that looks like the detected face.
 *
 * Uses:
 *  - Face bounding box + landmark positions to place features
 *  - Cheek pixels → skin tone
 *  - Above-face pixels  → hair colour
 *  - smilingProbability → mouth curve
 *  - eyeOpenProbability → eye openness
 */
class EmojiGenerator {

    companion object {
        private const val SIZE = 512
    }

    fun generate(face: Face, src: Bitmap): Bitmap {
        val out    = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val bounds  = face.boundingBox
        val faceCX  = bounds.exactCenterX()
        val faceCY  = bounds.exactCenterY()
        val faceW   = bounds.width().toFloat()

        // Radius of the emoji circle on the output canvas
        val r  = SIZE * 0.40f
        val cx = SIZE / 2f
        val cy = SIZE / 2f

        // Map a landmark (absolute image coords) into emoji canvas coords
        fun pt(lm: FaceLandmark?): PointF? {
            lm ?: return null
            val nx = (lm.position.x - faceCX) / faceW
            val ny = (lm.position.y - faceCY) / faceW
            return PointF(cx + nx * SIZE * 0.78f, cy + ny * SIZE * 0.78f)
        }

        val smiling   = face.smilingProbability          ?: 0.5f
        val leftOpen  = face.leftEyeOpenProbability      ?: 1f
        val rightOpen = face.rightEyeOpenProbability     ?: 1f

        val leftEyePt  = pt(face.getLandmark(FaceLandmark.LEFT_EYE))  ?: PointF(cx - r * 0.36f, cy - r * 0.05f)
        val rightEyePt = pt(face.getLandmark(FaceLandmark.RIGHT_EYE)) ?: PointF(cx + r * 0.36f, cy - r * 0.05f)
        val nosePt     = pt(face.getLandmark(FaceLandmark.NOSE_BASE))
        val mouthL     = pt(face.getLandmark(FaceLandmark.MOUTH_LEFT))
        val mouthR     = pt(face.getLandmark(FaceLandmark.MOUTH_RIGHT))
        val mouthB     = pt(face.getLandmark(FaceLandmark.MOUTH_BOTTOM))
        val cheekL     = pt(face.getLandmark(FaceLandmark.LEFT_CHEEK))
        val cheekR     = pt(face.getLandmark(FaceLandmark.RIGHT_CHEEK))

        // ── Colours ──────────────────────────────────────────────────────────
        val skinColor = extractSkinTone(src, bounds, cheekL, cheekR, cx, cy, r)
        val skinDark  = darken(skinColor, 0.72f)
        val hairColor = extractHairColor(src, bounds)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // ── 1. Drop shadow ───────────────────────────────────────────────────
        paint.color = Color.argb(50, 0, 0, 0)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx + 8f, cy + 10f, r + 6f, paint)

        // ── 2. Face circle ───────────────────────────────────────────────────
        paint.color = skinColor
        canvas.drawCircle(cx, cy, r, paint)
        paint.color = skinDark
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = SIZE * 0.013f
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL

        // ── 3. Hair cap ──────────────────────────────────────────────────────
        drawHair(canvas, cx, cy, r, hairColor, paint)

        // ── 4. Ears ──────────────────────────────────────────────────────────
        drawEar(canvas, cx - r * 0.98f, cy + r * 0.05f, r * 0.18f, skinColor, skinDark, paint)
        drawEar(canvas, cx + r * 0.98f, cy + r * 0.05f, r * 0.18f, skinColor, skinDark, paint)

        // ── 5. Eyebrows ──────────────────────────────────────────────────────
        val browColor = darken(hairColor, 0.55f)
        drawEyebrow(canvas, leftEyePt,  r * 0.24f, r * 0.14f, smiling, isLeft = true,  browColor, paint)
        drawEyebrow(canvas, rightEyePt, r * 0.24f, r * 0.14f, smiling, isLeft = false, browColor, paint)

        // ── 6. Eyes ──────────────────────────────────────────────────────────
        drawEye(canvas, leftEyePt,  r * 0.126f, leftOpen,  paint)
        drawEye(canvas, rightEyePt, r * 0.126f, rightOpen, paint)

        // ── 7. Nose ──────────────────────────────────────────────────────────
        val noseCenter = nosePt ?: PointF(cx, cy + r * 0.12f)
        paint.color = skinDark
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = SIZE * 0.009f
        paint.strokeCap = Paint.Cap.ROUND
        val nosePath = Path().apply {
            moveTo(noseCenter.x - r * 0.055f, noseCenter.y - r * 0.04f)
            quadTo(noseCenter.x - r * 0.09f,  noseCenter.y + r * 0.07f, noseCenter.x,              noseCenter.y + r * 0.05f)
            quadTo(noseCenter.x + r * 0.09f,  noseCenter.y + r * 0.07f, noseCenter.x + r * 0.055f, noseCenter.y - r * 0.04f)
        }
        canvas.drawPath(nosePath, paint)
        paint.style = Paint.Style.FILL

        // ── 8. Mouth ─────────────────────────────────────────────────────────
        val ml = mouthL ?: PointF(cx - r * 0.28f, cy + r * 0.40f)
        val mr = mouthR ?: PointF(cx + r * 0.28f, cy + r * 0.40f)
        val mb = mouthB ?: PointF(cx,              cy + r * 0.55f)
        drawMouth(canvas, ml, mr, mb, smiling, r, paint)

        // ── 9. Blush (when smiling) ──────────────────────────────────────────
        if (smiling > 0.45f) {
            val alpha = ((smiling - 0.45f) / 0.55f * 90).toInt()
            paint.color = Color.argb(alpha, 255, 90, 110)
            val lc = cheekL ?: PointF(cx - r * 0.60f, cy + r * 0.24f)
            val rc = cheekR ?: PointF(cx + r * 0.60f, cy + r * 0.24f)
            canvas.drawOval(lc.x - r * 0.17f, lc.y - r * 0.09f, lc.x + r * 0.17f, lc.y + r * 0.09f, paint)
            canvas.drawOval(rc.x - r * 0.17f, rc.y - r * 0.09f, rc.x + r * 0.17f, rc.y + r * 0.09f, paint)
        }

        return out
    }

    // ── Drawing helpers ──────────────────────────────────────────────────────

    private fun drawHair(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, paint: Paint) {
        // Hair = the part of a slightly-larger circle that sits above the hairline (~40 % up the face)
        paint.color = color
        paint.style = Paint.Style.FILL

        val hairCircle = Path().apply { addCircle(cx, cy - r * 0.04f, r * 1.04f, Path.Direction.CW) }
        val hairlineClip = Path().apply {
            addRect(cx - r * 1.2f, cy - r * 1.2f, cx + r * 1.2f, cy - r * 0.28f, Path.Direction.CW)
        }
        val hair = Path()
        hair.op(hairCircle, hairlineClip, Path.Op.INTERSECT)
        canvas.drawPath(hair, paint)
    }

    private fun drawEar(canvas: Canvas, x: Float, y: Float, r: Float, skin: Int, dark: Int, paint: Paint) {
        paint.color = skin
        paint.style = Paint.Style.FILL
        canvas.drawOval(x - r, y - r * 1.3f, x + r, y + r * 1.3f, paint)
        paint.color = dark
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.2f
        canvas.drawOval(x - r * 0.5f, y - r * 0.7f, x + r * 0.5f, y + r * 0.7f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawEyebrow(
        canvas: Canvas, eyePos: PointF, halfW: Float, yOff: Float,
        smiling: Float, isLeft: Boolean, color: Int, paint: Paint
    ) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = halfW * 0.28f
        paint.strokeCap = Paint.Cap.ROUND

        val baseY    = eyePos.y - yOff
        // Slight inner-corner tilt based on expression
        val tilt     = halfW * (if (smiling > 0.5f) -0.10f else 0.15f)
        val innerDY  = if (isLeft) tilt  else -tilt
        val outerDY  = if (isLeft) -tilt else  tilt

        val path = Path().apply {
            moveTo(eyePos.x - halfW, baseY + outerDY)
            quadTo(eyePos.x, baseY - halfW * 0.10f, eyePos.x + halfW, baseY + innerDY)
        }
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawEye(canvas: Canvas, pos: PointF, r: Float, openProb: Float, paint: Paint) {
        if (openProb < 0.30f) {
            // Closed / squinting — just a curved line
            paint.color  = Color.parseColor("#2C2C2C")
            paint.style  = Paint.Style.STROKE
            paint.strokeWidth = r * 0.38f
            paint.strokeCap   = Paint.Cap.ROUND
            val p = Path().apply {
                moveTo(pos.x - r, pos.y)
                quadTo(pos.x, pos.y - r * 0.30f, pos.x + r, pos.y)
            }
            canvas.drawPath(p, paint)
            paint.style = Paint.Style.FILL
            return
        }

        val vScale = openProb.coerceIn(0.40f, 1.0f)

        // White
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawOval(pos.x - r, pos.y - r * vScale, pos.x + r, pos.y + r * vScale, paint)

        // Iris (blue-grey)
        val irisR = r * 0.60f
        paint.color = Color.parseColor("#3A5FA0")
        canvas.drawCircle(pos.x, pos.y, irisR, paint)

        // Pupil
        paint.color = Color.parseColor("#151515")
        canvas.drawCircle(pos.x, pos.y, irisR * 0.54f, paint)

        // Highlight
        paint.color = Color.WHITE
        canvas.drawCircle(pos.x + r * 0.23f, pos.y - r * 0.23f, r * 0.17f, paint)

        // Outline
        paint.color = Color.parseColor("#2C2C2C")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.12f
        canvas.drawOval(pos.x - r, pos.y - r * vScale, pos.x + r, pos.y + r * vScale, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawMouth(
        canvas: Canvas,
        ml: PointF, mr: PointF, mb: PointF,
        smiling: Float, r: Float, paint: Paint
    ) {
        val midX = (ml.x + mr.x) / 2f
        val midY = (ml.y + mr.y) / 2f

        paint.strokeCap = Paint.Cap.ROUND
        val lipDark = Color.parseColor("#7A1818")

        when {
            smiling > 0.50f -> {
                val depth = r * 0.26f * smiling

                // Dark mouth interior
                val mouth = Path().apply {
                    moveTo(ml.x, ml.y)
                    quadTo(midX, midY + depth * 1.6f, mr.x, mr.y)
                    quadTo(midX, midY + depth * 0.5f,  ml.x, ml.y)
                }
                paint.color = lipDark
                paint.style = Paint.Style.FILL
                canvas.drawPath(mouth, paint)

                // Teeth
                val teeth = Path().apply {
                    moveTo(ml.x, ml.y)
                    quadTo(midX, midY + depth * 0.4f, mr.x, mr.y)
                    lineTo(mr.x, ml.y)
                    close()
                }
                paint.color = Color.WHITE
                canvas.drawPath(teeth, paint)

                // Upper-lip line
                paint.color = lipDark
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.048f
                val line = Path().apply {
                    moveTo(ml.x, ml.y)
                    quadTo(midX, midY + depth, mr.x, mr.y)
                }
                canvas.drawPath(line, paint)
            }

            smiling > 0.25f -> {
                val depth = r * 0.16f * smiling
                paint.color = lipDark
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.052f
                val line = Path().apply {
                    moveTo(ml.x, ml.y)
                    quadTo(midX, midY + depth, mr.x, mr.y)
                }
                canvas.drawPath(line, paint)
            }

            smiling < 0.15f -> {
                // Slight frown
                val lift = r * 0.10f
                paint.color = lipDark
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.050f
                val line = Path().apply {
                    moveTo(ml.x, ml.y)
                    quadTo(midX, midY - lift, mr.x, mr.y)
                }
                canvas.drawPath(line, paint)
            }

            else -> {
                // Neutral
                paint.color = lipDark
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.048f
                canvas.drawLine(ml.x, midY, mr.x, midY, paint)
            }
        }

        paint.style = Paint.Style.FILL
    }

    // ── Colour extraction ────────────────────────────────────────────────────

    /**
     * Samples pixels at both cheek landmarks (or centre of face) and returns a
     * slightly warmed, cartoon-friendly average colour.
     */
    private fun extractSkinTone(
        bmp: Bitmap, bounds: Rect,
        cheekL: PointF?, cheekR: PointF?,
        cx: Float, cy: Float, r: Float
    ): Int {
        val samples = mutableListOf<PointF>()

        // Prefer actual cheek landmarks mapped back to image coords
        fun addCheekSample(emojiPt: PointF?) {
            emojiPt ?: return
            // Convert emoji-canvas coords back to image coords (approximate)
            val imgX = (bounds.exactCenterX() + (emojiPt.x - cx) / (SIZE * 0.78f) * bounds.width()).toInt()
                .coerceIn(0, bmp.width - 1)
            val imgY = (bounds.exactCenterY() + (emojiPt.y - cy) / (SIZE * 0.78f) * bounds.width()).toInt()
                .coerceIn(0, bmp.height - 1)
            samples.add(PointF(imgX.toFloat(), imgY.toFloat()))
        }

        if (cheekL != null && cheekR != null) {
            addCheekSample(cheekL)
            addCheekSample(cheekR)
        } else {
            // Fall back: sample a 5×5 grid from the centre of the face
            val step = bounds.width() / 8
            for (dy in -1..1) for (dx in -1..1)
                samples.add(PointF(
                    (bounds.exactCenterX() + dx * step).coerceIn(0f, bmp.width - 1f),
                    (bounds.exactCenterY() + dy * step).coerceIn(0f, bmp.height - 1f)
                ))
        }

        var rSum = 0L; var gSum = 0L; var bSum = 0L
        for (p in samples) {
            val c = bmp.getPixel(p.x.toInt(), p.y.toInt())
            rSum += Color.red(c); gSum += Color.green(c); bSum += Color.blue(c)
        }
        val n = samples.size.coerceAtLeast(1)
        // Slight warm boost for cartoon look
        return Color.rgb(
            minOf(255, (rSum / n * 1.08).toInt()),
            (gSum / n).toInt(),
            (bSum / n * 0.88).toInt()
        )
    }

    /** Samples the area just above the face bounding box to detect hair colour. */
    private fun extractHairColor(bmp: Bitmap, bounds: Rect): Int {
        val cx   = bounds.exactCenterX().toInt()
        val topY = (bounds.top - bounds.height() * 0.08f).toInt().coerceIn(0, bmp.height - 1)
        val step = bounds.width() / 7

        var rSum = 0L; var gSum = 0L; var bSum = 0L; var n = 0
        for (dx in -3..3) {
            val px = (cx + dx * step).coerceIn(0, bmp.width - 1)
            val c = bmp.getPixel(px, topY)
            rSum += Color.red(c); gSum += Color.green(c); bSum += Color.blue(c); n++
        }
        return if (n == 0) Color.parseColor("#3D2B1F")
        else Color.rgb((rSum / n).toInt(), (gSum / n).toInt(), (bSum / n).toInt())
    }

    private fun darken(color: Int, f: Float) = Color.rgb(
        (Color.red(color)   * f).toInt().coerceIn(0, 255),
        (Color.green(color) * f).toInt().coerceIn(0, 255),
        (Color.blue(color)  * f).toInt().coerceIn(0, 255)
    )
}
