package com.facemoji.app

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * Single-frame GIF89a encoder.
 *
 * Colour quantisation: NeuQuant neural-net algorithm by Anthony Dekker (1994)
 *   — public-domain reference implementation, widely used in image tools.
 * LZW compression:     GIF89a specification (Compuserve, 1989).
 *
 * Usage:
 *   GifEncoder().encode(bitmap, outputStream)
 */
class GifEncoder {

    fun encode(bitmap: Bitmap, out: OutputStream) {
        val w = bitmap.width
        val h = bitmap.height

        val argb = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }

        // Quantise to 256 colours
        val nq      = NeuQuant(argb, sampleFac = 10)
        val palette = nq.process()          // 768 bytes — 256 × RGB
        val indexed = ByteArray(argb.size) { nq.map(argb[it]).toByte() }

        out.write("GIF89a".toByteArray())
        out.word(w); out.word(h)
        out.write(0xF7)   // GCT present, 8-bit colour resolution, 256 colours
        out.write(0x00)   // background colour index
        out.write(0x00)   // pixel aspect ratio (square)

        out.write(palette)
        repeat(768 - palette.size) { out.write(0) }   // pad to 256 colours

        out.write(0x2C)   // image separator
        out.word(0); out.word(0); out.word(w); out.word(h)
        out.write(0x00)   // no local colour table, not interlaced

        lzwCompress(indexed, colorDepth = 8, out)

        out.write(0x3B)   // GIF trailer
        out.flush()
    }

    // ── LZW compression ───────────────────────────────────────────────────────

    private fun lzwCompress(pixels: ByteArray, colorDepth: Int, out: OutputStream) {
        val initCS    = colorDepth.coerceAtLeast(2)
        out.write(initCS)

        val clearCode = 1 shl initCS
        val eoiCode   = clearCode + 1
        var freeCode  = eoiCode + 1
        var codeSize  = initCS + 1
        var limit     = (1 shl codeSize) - 1

        // Dictionary: (prefix << 8) | suffix → code
        val dict = HashMap<Int, Int>(8192)

        var accum    = 0
        var accumBits = 0
        val packet   = ByteArray(256)
        var pktLen   = 0

        fun flushPacket() {
            if (pktLen > 0) { out.write(pktLen); out.write(packet, 0, pktLen); pktLen = 0 }
        }

        fun emit(code: Int) {
            accum     = accum or (code shl accumBits)
            accumBits += codeSize
            while (accumBits >= 8) {
                packet[pktLen++] = (accum and 0xFF).toByte()
                accum      = accum ushr 8
                accumBits -= 8
                if (pktLen == 255) flushPacket()
            }
        }

        fun clearDict() {
            dict.clear()
            freeCode = eoiCode + 1
            codeSize = initCS + 1
            limit    = (1 shl codeSize) - 1
        }

        emit(clearCode)
        clearDict()

        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val c   = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            val hit = dict[key]
            if (hit != null) {
                prefix = hit
            } else {
                emit(prefix)
                if (freeCode < 4096) {
                    dict[key] = freeCode++
                    if (freeCode > limit && codeSize < 12) {
                        codeSize++
                        limit = (1 shl codeSize) - 1
                    }
                } else {
                    emit(clearCode)
                    clearDict()
                }
                prefix = c
            }
        }
        emit(prefix)
        emit(eoiCode)

        // Flush remaining bits
        while (accumBits > 0) {
            packet[pktLen++] = (accum and 0xFF).toByte()
            accum      = accum ushr 8
            accumBits -= 8
            if (pktLen == 255) flushPacket()
        }
        flushPacket()
        out.write(0)  // block terminator
    }

    private fun OutputStream.word(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
}

// ─────────────────────────────────────────────────────────────────────────────
//  NeuQuant — Neural-Net Colour Quantisation
//
//  Based on the public-domain C implementation by Anthony Dekker (1994):
//    "Kohonen neural networks for optimal colour quantization"
//    Network: Computation in Neural Systems, Vol. 5 (1994) pp 351–367
//
//  Java adaptation: Kevin Weiner, FM Software (Jan 2004)
//  Kotlin adaptation for FaceEmojiApp.
// ─────────────────────────────────────────────────────────────────────────────
private class NeuQuant(private val pixels: IntArray, private val sampleFac: Int) {

    private val NETSIZE    = 256
    private val MAXNETPOS  = NETSIZE - 1
    private val BIAS_SHIFT = 4
    private val N_CYCLES   = 100

    private val INT_BIAS_SHIFT = 16
    private val INT_BIAS       = 1 shl INT_BIAS_SHIFT

    private val GAMMA_SHIFT = 10
    private val GAMMA       = 1 shl GAMMA_SHIFT
    private val BETA_SHIFT  = 10
    private val BETA        = INT_BIAS shr BETA_SHIFT
    private val BETA_GAMMA  = INT_BIAS shl (GAMMA_SHIFT - BETA_SHIFT)

    private val INIT_RAD       = NETSIZE shr 3
    private val RAD_BIAS_SHIFT = 6
    private val RAD_BIAS       = 1 shl RAD_BIAS_SHIFT
    private val INIT_RADIUS    = INIT_RAD * RAD_BIAS
    private val RADIUS_DEC     = 30

    private val ALPHA_BIAS_SHIFT = 10
    private val INIT_ALPHA       = 1 shl ALPHA_BIAS_SHIFT

    private val RAD_BIAS_SHIFT2  = 8
    private val RAD_BIAS2        = 1 shl RAD_BIAS_SHIFT2
    private val ALPHA_RAD_SHIFT  = ALPHA_BIAS_SHIFT + RAD_BIAS_SHIFT2
    private val ALPHA_RAD_BIAS   = 1 shl ALPHA_RAD_SHIFT

    // network[i] = [b, g, r, index]  (fixed-point, shifted by BIAS_SHIFT)
    private val network  = Array(NETSIZE) { IntArray(4) }
    private val netindex = IntArray(256)
    private val bias     = IntArray(NETSIZE)
    private val freq     = IntArray(NETSIZE)
    private val radpower = IntArray(INIT_RAD)

    init {
        for (i in 0 until NETSIZE) {
            val base = (i shl (BIAS_SHIFT + 8)) / NETSIZE
            network[i][0] = base; network[i][1] = base; network[i][2] = base
            freq[i] = INT_BIAS / NETSIZE
            bias[i] = 0
        }
    }

    /** Train and return 768-byte RGB palette (256 × 3). */
    fun process(): ByteArray {
        learn(); unbiasNet(); buildIndex()
        return colorMap()
    }

    private fun colorMap(): ByteArray {
        val map   = ByteArray(3 * NETSIZE)
        val index = IntArray(NETSIZE)
        for (i in 0 until NETSIZE) index[network[i][3]] = i
        var k = 0
        for (i in 0 until NETSIZE) {
            val j = index[i]
            map[k++] = (network[j][0] shr BIAS_SHIFT).toByte()
            map[k++] = (network[j][1] shr BIAS_SHIFT).toByte()
            map[k++] = (network[j][2] shr BIAS_SHIFT).toByte()
        }
        return map
    }

    private fun buildIndex() {
        var prevCol  = 0; var start = 0
        for (i in 0 until NETSIZE) {
            val n = network[i]
            var minPos = i; var minG = n[1] shr BIAS_SHIFT
            for (j in i + 1 until NETSIZE) {
                if (network[j][1] < minG) { minPos = j; minG = network[j][1] }
            }
            if (i != minPos) {
                val m = network[minPos]
                var t = n[0]; n[0] = m[0]; m[0] = t
                t = n[1]; n[1] = m[1]; m[1] = t
                t = n[2]; n[2] = m[2]; m[2] = t
                t = n[3]; n[3] = m[3]; m[3] = t
            }
            if (minG != prevCol) {
                netindex[prevCol] = (start + i) shr 1
                for (j in prevCol + 1 until minG) netindex[j] = i
                prevCol = minG; start = i
            }
        }
        netindex[prevCol] = (start + MAXNETPOS) shr 1
        for (j in prevCol + 1..255) netindex[j] = MAXNETPOS
    }

    private fun learn() {
        var alphaDec = 30 + (sampleFac - 1) / 3
        val samples  = pixels.size / sampleFac
        var delta    = samples / N_CYCLES
        if (delta == 0) delta = 1
        var alpha    = INIT_ALPHA
        var radius   = INIT_RADIUS

        var rad = radius shr RAD_BIAS_SHIFT
        if (rad <= 1) rad = 0
        for (i in 0 until rad)
            radpower[i] = alpha * ((rad * rad - i * i) * RAD_BIAS2 / (rad * rad))

        val step = when {
            pixels.size % 499 != 0 -> 499
            pixels.size % 491 != 0 -> 491
            pixels.size % 487 != 0 -> 487
            else                   -> 503
        }

        var pos = 0; var i = 0
        while (i < samples) {
            val px = pixels[pos % pixels.size]
            val b  = (px and 0xFF)         shl BIAS_SHIFT
            val g  = ((px shr 8)  and 0xFF) shl BIAS_SHIFT
            val r  = ((px shr 16) and 0xFF) shl BIAS_SHIFT

            val best = contest(b, g, r)
            alterSingle(alpha, best, b, g, r)
            if (rad != 0) alterNeigh(rad, best, b, g, r)

            pos += step
            if (pos >= pixels.size) pos -= pixels.size
            i++
            if (i % delta == 0) {
                alpha  -= alpha / alphaDec
                radius -= radius / RADIUS_DEC
                rad     = radius shr RAD_BIAS_SHIFT
                if (rad <= 1) rad = 0
                for (k in 0 until rad)
                    radpower[k] = alpha * ((rad * rad - k * k) * RAD_BIAS2 / (rad * rad))
            }
        }
    }

    private fun alterSingle(alpha: Int, i: Int, b: Int, g: Int, r: Int) {
        val n = network[i]
        n[0] -= (alpha * (n[0] - b)) / INIT_ALPHA
        n[1] -= (alpha * (n[1] - g)) / INIT_ALPHA
        n[2] -= (alpha * (n[2] - r)) / INIT_ALPHA
    }

    private fun alterNeigh(rad: Int, i: Int, b: Int, g: Int, r: Int) {
        val lo = maxOf(i - rad, -1);  val hi = minOf(i + rad, NETSIZE)
        var hi2 = i + 1; var lo2 = i - 1; var m = 1
        while (hi2 < hi || lo2 > lo) {
            val a = radpower[m++]
            if (hi2 < hi) { val p = network[hi2++]; p[0] -= (a * (p[0]-b))/ALPHA_RAD_BIAS; p[1] -= (a * (p[1]-g))/ALPHA_RAD_BIAS; p[2] -= (a * (p[2]-r))/ALPHA_RAD_BIAS }
            if (lo2 > lo) { val p = network[lo2--]; p[0] -= (a * (p[0]-b))/ALPHA_RAD_BIAS; p[1] -= (a * (p[1]-g))/ALPHA_RAD_BIAS; p[2] -= (a * (p[2]-r))/ALPHA_RAD_BIAS }
        }
    }

    private fun contest(b: Int, g: Int, r: Int): Int {
        var bestD = Int.MAX_VALUE; var bestBiasD = Int.MAX_VALUE
        var bestPos = -1; var bestBiasPos = -1
        for (i in 0 until NETSIZE) {
            val n = network[i]
            val d = Math.abs(n[0]-b) + Math.abs(n[1]-g) + Math.abs(n[2]-r)
            if (d < bestD) { bestD = d; bestPos = i }
            val bd = d - (bias[i] shr (INT_BIAS_SHIFT - BIAS_SHIFT))
            if (bd < bestBiasD) { bestBiasD = bd; bestBiasPos = i }
            freq[i] -= freq[i] shr BETA_SHIFT
            bias[i] += freq[i] shl GAMMA_SHIFT
        }
        freq[bestPos] += BETA
        bias[bestPos] -= BETA_GAMMA
        return bestBiasPos
    }

    private fun unbiasNet() {
        for (i in 0 until NETSIZE) {
            network[i][0] = network[i][0] shr BIAS_SHIFT
            network[i][1] = network[i][1] shr BIAS_SHIFT
            network[i][2] = network[i][2] shr BIAS_SHIFT
            network[i][3] = i
        }
    }

    /** Find the index of the palette entry closest to (b, g, r). */
    fun map(argb: Int): Int {
        val b = argb and 0xFF
        val g = (argb shr 8)  and 0xFF
        val r = (argb shr 16) and 0xFF

        var best = -1; var bestDist = 1000
        var i = netindex[g]; var j = i - 1
        while (i < NETSIZE || j >= 0) {
            if (i < NETSIZE) {
                val n = network[i]; var d = n[1] - g
                if (d >= bestDist) { i = NETSIZE } else {
                    i++; if (d < 0) d = -d
                    d += Math.abs(n[0] - b)
                    if (d < bestDist) { d += Math.abs(n[2] - r); if (d < bestDist) { bestDist = d; best = n[3] } }
                }
            }
            if (j >= 0) {
                val n = network[j]; var d = g - n[1]
                if (d >= bestDist) { j = -1 } else {
                    j--; if (d < 0) d = -d
                    d += Math.abs(n[0] - b)
                    if (d < bestDist) { d += Math.abs(n[2] - r); if (d < bestDist) { bestDist = d; best = n[3] } }
                }
            }
        }
        return best
    }
}
