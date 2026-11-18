package com.example.chessreviewer

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Finds the chess board in a full screenshot with no help from the user.
 * 1) coarse search: an 8x8 grid where all light squares share one colour and all dark squares share another
 * 2) precise alignment: the grid lines between squares are strong, evenly spaced edges; fit them to the pixel.
 */
object BoardFinder {
    class Found(val left: Int, val top: Int, val size: Int, val score: Float)

    private class Fit(val pos: Int, val q: Float, val sc: Float)

    /**
     * 0..1: how much the 8x8 grid at (left, top, size) looks like a checkerboard.
     * ns = samples per square (2 or 4); m = how far from the square's corner the samples are (fraction of a square).
     */
    fun score(px: IntArray, w: Int, h: Int, left: Int, top: Int, size: Int, ns: Int, m: Float): Float {
        if (size < 16 || left < 0 || top < 0 || left + size > w || top + size > h) return 0f
        val q = size / 8f
        var s0r = 0; var s0g = 0; var s0b = 0; var n0 = 0
        var s1r = 0; var s1g = 0; var s1b = 0; var n1 = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val par = (r + c) and 1
            for (k in 0 until ns) {
                val fx = if ((k and 1) == 0) m else 1f - m
                val fy = if (k < 2) m else 1f - m
                val x = left + ((c + fx) * q).toInt()
                val y = top + ((r + fy) * q).toInt()
                val p = px[y * w + x]
                val pr = (p shr 16) and 255
                val pg = (p shr 8) and 255
                val pb = p and 255
                if (par == 0) { s0r += pr; s0g += pg; s0b += pb; n0++ }
                else { s1r += pr; s1g += pg; s1b += pb; n1++ }
            }
        }
        val m0r = s0r.toFloat() / n0; val m0g = s0g.toFloat() / n0; val m0b = s0b.toFloat() / n0
        val m1r = s1r.toFloat() / n1; val m1g = s1g.toFloat() / n1; val m1b = s1b.toFloat() / n1
        val sep = abs(m0r - m1r) + abs(m0g - m1g) + abs(m0b - m1b)
        if (sep < 30f) return 0f
        val tol = minOf(40f, sep * 0.4f)
        var inliers = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val par = (r + c) and 1
            val mr = if (par == 0) m0r else m1r
            val mg = if (par == 0) m0g else m1g
            val mb = if (par == 0) m0b else m1b
            for (k in 0 until ns) {
                val fx = if ((k and 1) == 0) m else 1f - m
                val fy = if (k < 2) m else 1f - m
                val x = left + ((c + fx) * q).toInt()
                val y = top + ((r + fy) * q).toInt()
                val p = px[y * w + x]
                val d = abs(((p shr 16) and 255) - mr) + abs(((p shr 8) and 255) - mg) + abs((p and 255) - mb)
                if (d <= tol) inliers++
            }
        }
        return (inliers.toFloat() / (64 * ns)) * minOf(1f, sep / 50f)
    }

    private fun shrink(px: IntArray, w: Int, h: Int, f: Int): IntArray {
        val cw = w / f
        val ch = h / f
        val out = IntArray(cw * ch)
        val n = f * f
        for (y in 0 until ch) for (x in 0 until cw) {
            var r = 0; var g = 0; var b = 0
            for (yy in 0 until f) {
                val row = (y * f + yy) * w + x * f
                for (xx in 0 until f) {
                    val p = px[row + xx]
                    r += (p shr 16) and 255
                    g += (p shr 8) and 255
                    b += p and 255
                }
            }
            out[y * cw + x] = ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return out
    }

    private fun coarse(cp: IntArray, cw: Int, ch: Int, sMin: Int, sMax: Int): Found? {
        var best: Found? = null
        var bestScore = 0f
        for (s in sMin..sMax) {
            if (s > cw || s > ch) break
            for (top in 0..(ch - s)) for (left in 0..(cw - s)) {
                val sc = score(cp, cw, ch, left, top, s, 2, 0.2f)
                if (sc > bestScore) { bestScore = sc; best = Found(left, top, s, sc) }
            }
        }
        return best
    }

    /** rough refinement in full-resolution pixels (good to a few pixels) */
    private fun refine(px: IntArray, w: Int, h: Int, c: Found, f: Int): Found {
        var bl = c.left * f
        var bt = c.top * f
        var bs = c.size * f
        var bsc = score(px, w, h, bl, bt, bs, 4, 0.2f)
        for (step in intArrayOf(4, 2, 1)) {
            var guard = 0
            while (guard < 30) {
                guard++
                var nl = bl; var nt = bt; var nsz = bs; var nsc = bsc
                for (dl in -1..1) for (dt in -1..1) for (ds in -1..1) {
                    if (dl == 0 && dt == 0 && ds == 0) continue
                    val sc = score(px, w, h, bl + dl * step, bt + dt * step, bs + ds * step, 4, 0.2f)
                    if (sc > nsc + 0.0005f) {
                        nsc = sc; nl = bl + dl * step; nt = bt + dt * step; nsz = bs + ds * step
                    }
                }
                if (nsc <= bsc) break
                bl = nl; bt = nt; bs = nsz; bsc = nsc
            }
        }
        return Found(bl, bt, bs, bsc)
    }

    private fun lum3(p: Int): Int = ((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)

    /** Fit 9 evenly spaced lines (pos + k*q) to the strong edges in an edge-strength profile. fixedQ > 0 pins the spacing. */
    private fun fitAxis(prof: FloatArray, p0: Int, s0: Int, fixedQ: Float): Fit? {
        val n = prof.size
        val sp = FloatArray(n)
        for (i in 1 until n - 1) sp[i] = prof[i - 1] + 2f * prof[i] + prof[i + 1]
        var best: Fit? = null
        val nq = if (fixedQ > 0f) 1 else 193
        for (pos in maxOf(0, p0 - 24)..(p0 + 24)) {
            for (qi in 0 until nq) {
                val q = if (fixedQ > 0f) fixedQ else (s0 + (qi - 96) * 0.25f) / 8f
                if (pos + 8f * q > n + 1) continue
                var tot = 0f
                var cnt = 0
                for (k in 0..8) {
                    val yi = (pos + k * q).roundToInt() - 1
                    if (yi < 1 || yi >= n - 2) continue
                    tot += sp[yi]
                    cnt++
                }
                if (cnt < 7) continue
                val b = best
                if (b == null || tot > b.sc) best = Fit(pos, q, tot)
            }
        }
        return best
    }

    /** snap the rough rectangle onto the real square edges */
    private fun alignByEdges(px: IntArray, w: Int, h: Int, l0: Int, t0: Int, s0: Int): Found? {
        val xa = maxOf(0, l0)
        val xb = minOf(w, l0 + s0)
        val ya = maxOf(0, t0)
        val yb = minOf(h, t0 + s0)
        if (xb - xa < 64 || yb - ya < 64) return null

        val gy = FloatArray(h - 1)
        for (y in 0 until h - 1) {
            var sum = 0
            val row = y * w
            for (x in xa until xb) sum += abs(lum3(px[row + w + x]) - lum3(px[row + x]))
            gy[y] = sum.toFloat()
        }
        val gx = FloatArray(w - 1)
        for (x in 0 until w - 1) {
            var sum = 0
            for (y in ya until yb) sum += abs(lum3(px[y * w + x + 1]) - lum3(px[y * w + x]))
            gx[x] = sum.toFloat()
        }

        val fy = fitAxis(gy, t0, s0, 0f)
        val fx = fitAxis(gx, l0, s0, 0f)
        if (fy == null && fx == null) return null
        val useY = fx == null || (fy != null && fy.sc >= fx.sc)
        val q = if (useY) fy!!.q else fx!!.q
        val ay = if (useY) fy else fitAxis(gy, t0, s0, q)
        val ax = if (useY) fitAxis(gx, l0, s0, q) else fx
        if (ax == null || ay == null) return null
        val size = (q * 8f).roundToInt()
        val sc = score(px, w, h, ax.pos, ay.pos, size, 4, 0.12f)
        return Found(ax.pos, ay.pos, size, sc)
    }

    private fun finish(px: IntArray, w: Int, h: Int, r: Found): Found {
        val e = alignByEdges(px, w, h, r.left, r.top, r.size)
        return if (e != null && e.score >= 0.6f) e else r
    }

    /** px = full screenshot (w x h). Returns the board rectangle in screenshot pixels, or null if no board is visible. */
    fun find(px: IntArray, w: Int, h: Int): Found? {
        val f = 8
        val cw = w / f
        val ch = h / f
        if (cw < 24 || ch < 24) return null
        val cp = shrink(px, w, h, f)

        // most phone apps: the board is (nearly) full width
        var c = coarse(cp, cw, ch, (cw * 0.85f).toInt(), cw)
        if (c != null) {
            val r = refine(px, w, h, c, f)
            if (r.score >= 0.7f) return finish(px, w, h, r)
        }
        // otherwise: smaller board (margins, tablet, landscape)
        c = coarse(cp, cw, ch, (cw * 0.4f).toInt(), minOf(cw, ch))
        if (c != null) {
            val r = refine(px, w, h, c, f)
            if (r.score >= 0.7f) return finish(px, w, h, r)
        }
        return null
    }
}
