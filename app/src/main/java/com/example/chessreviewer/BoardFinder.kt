package com.example.chessreviewer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the chess board in a full screenshot with no help from the user.
 * 1) coarse search: an 8x8 grid where all light squares share one colour and all dark squares share another
 * 2) precise alignment: the grid lines between squares are strong, evenly spaced edges; fit them to the pixel.
 *
 * The search is scaled to the picture it is given, so a tall phone screenshot, a tablet screenshot and a small
 * crop all cost about the same, and the whole picture is searched rather than assuming the board fills the
 * width. Every way out of [find] leaves a sentence in [lastProblem] saying what went wrong, because a bare
 * "no board" cannot be acted on.
 */
object BoardFinder {
    class Found(val left: Int, val top: Int, val size: Int, val score: Float)

    /** Why the last [find] returned null, in words the panel can show. Empty after a successful find. */
    var lastProblem: String = ""

    /** the two square colours must be at least this far apart (r+g+b) to count as a board at all */
    private const val MIN_SEP = 12f

    /** separation at which the two-colour test is fully trusted; a low-contrast theme lands well below it */
    private const val FULL_SEP = 36f

    /** below this a candidate is not a chess board */
    private const val ACCEPT = 0.66f

    /** a score this good is a board and no further searching can improve on it */
    private const val SETTLED = 0.94f

    /** a board narrower than this has squares under 12 px and no piece set can be told apart at that size */
    private const val MIN_SIZE = 96

    /** fewer squares than this inside the picture is not enough of a board to measure */
    private const val MIN_VISIBLE = 20

    /** how far from a square's corner the samples sit, as a fraction of a square. This has to be a few per cent,
     *  not a fifth: chess.com's piece artwork is transparent in the outer tenth of the square and solid inside
     *  it, so a sample at 20% lands on the piece and the two "square colours" come out as an average of the board
     *  and its pieces. Taken from the corner margin, every square tells you its own colour, occupied or not,
     *  which is what the checkerboard test needs. */
    private const val CORNER = 0.06f

    /** a sample never sits closer to the edge than this many pixels, because the grid line, the resampling bleed
     *  between two squares and a coordinate label all live in the first pixel or two */
    private const val MIN_INSET_PX = 1.5f

    /** and never further in than this, which is where the piece artwork starts */
    private const val MAX_CORNER = 0.11f

    private class Fit(val pos: Int, val q: Float, val sc: Float)

    /** what the grid at (left, top, size) looks like: 0..1 score, colour separation, squares inside the picture */
    private class Grid(val score: Float, val sep: Float, val visible: Int)

    private class Cand(val left: Int, val top: Int, val size: Int, val score: Float)

    /** a board that was found, with the measurement that accepted it */
    private class Hit(val found: Found, val grid: Grid)

    private val NO_GRID = Grid(0f, 0f, 0)

    // ------------------------------------------------------------------ sampling

    /**
     * Four samples a square, out of a scratch buffer: this is the search's inner loop. Returns 0 when the two
     * parities are the same colour, which is what a plain background, a menu or a block of text looks like.
     * Two rows of samples rather than one, because a grid that only half lines up with the squares underneath
     * it can line up perfectly along a single row.
     */
    private fun quick(px: IntArray, w: Int, h: Int, left: Int, top: Int, size: Int, scratch: IntArray? = null): Float {
        if (size < 16 || w <= 0 || h <= 0) return 0f
        val q = size / 8f
        val m = corner(q)
        val samp = scratch ?: IntArray(64 * 4)
        // a pixel from Bitmap.getPixels carries an alpha byte, so its value is negative: never test pixels by
        // their sign, keep a separate grid of which samples were inside the picture
        val inside = BooleanArray(64)
        var vis = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val i = r * 8 + c
            var ok = true
            for (k in 0 until 4) {
                val fx = if ((k and 1) == 0) m else 1f - m
                val fy = if (k < 2) m else 1f - m
                val x = left + ((c + fx) * q).toInt()
                val y = top + ((r + fy) * q).toInt()
                if (x < 0 || y < 0 || x >= w || y >= h) {
                    ok = false
                } else {
                    samp[i * 4 + k] = px[y * w + x]
                }
            }
            inside[i] = ok
            if (ok) vis++
        }
        if (vis < MIN_VISIBLE) return 0f
        var s0r = 0
        var s0g = 0
        var s0b = 0
        var n0 = 0
        var s1r = 0
        var s1g = 0
        var s1b = 0
        var n1 = 0
        for (i in 0 until 64) {
            if (!inside[i]) continue
            val par = ((i shr 3) + (i and 7)) and 1
            for (k in 0 until 4) {
                val p = samp[i * 4 + k]
                if (par == 0) {
                    s0r += (p shr 16) and 255
                    s0g += (p shr 8) and 255
                    s0b += p and 255
                    n0++
                } else {
                    s1r += (p shr 16) and 255
                    s1g += (p shr 8) and 255
                    s1b += p and 255
                    n1++
                }
            }
        }
        if (n0 == 0 || n1 == 0) return 0f
        val m0r = s0r.toFloat() / n0
        val m0g = s0g.toFloat() / n0
        val m0b = s0b.toFloat() / n0
        val m1r = s1r.toFloat() / n1
        val m1g = s1g.toFloat() / n1
        val m1b = s1b.toFloat() / n1
        val sep = abs(m0r - m1r) + abs(m0g - m1g) + abs(m0b - m1b)
        if (sep < MIN_SEP) return 0f
        val tol = max(10f, min(60f, sep * 0.45f))
        var good = 0
        var total = 0
        for (i in 0 until 64) {
            if (!inside[i]) continue
            val par = ((i shr 3) + (i and 7)) and 1
            val mr = if (par == 0) m0r else m1r
            val mg = if (par == 0) m0g else m1g
            val mb = if (par == 0) m0b else m1b
            for (k in 0 until 4) {
                val p = samp[i * 4 + k]
                if (abs(((p shr 16) and 255) - mr) + abs(((p shr 8) and 255) - mg) + abs((p and 255) - mb) <= tol) good++
                total++
            }
        }
        if (total == 0) return 0f
        return (good.toFloat() / total) * (0.6f + 0.4f * min(1f, sep / FULL_SEP))
    }

    /**
     * 0..1: how much the 8x8 grid at (left, top, size) looks like a checkerboard.
     * ns = samples per square (2 or 4); m = how far from the square's corner the samples are (fraction of a square).
     *
     * The worst few squares are dropped before averaging: a piece, a last-move highlight, a selection ring or
     * a coordinate label spoils a square or two, and that is not a reason to reject the whole board.
     */
    fun score(px: IntArray, w: Int, h: Int, left: Int, top: Int, size: Int, ns: Int, m: Float): Float =
        measure(px, w, h, left, top, size, ns, m).score

    private fun measure(px: IntArray, w: Int, h: Int, left: Int, top: Int, size: Int, ns: Int, m: Float): Grid {
        if (size < 16 || w <= 0 || h <= 0 || ns < 1) return NO_GRID
        if (px.size < w * h) return NO_GRID
        val q = size / 8f
        // a caller's margin is a hint: outside this band a sample sits on the grid line or on the piece
        val m = min(MAX_CORNER, max(m, corner(q)))
        val samp = IntArray(64 * ns)
        val inside = BooleanArray(64)
        var visible = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val i = r * 8 + c
            var ok = true
            for (k in 0 until ns) {
                val fx = if ((k and 1) == 0) m else 1f - m
                val fy = if (k < 2) m else 1f - m
                val x = left + ((c + fx) * q).toInt()
                val y = top + ((r + fy) * q).toInt()
                if (x < 0 || y < 0 || x >= w || y >= h) ok = false else samp[i * ns + k] = px[y * w + x]
            }
            inside[i] = ok
            if (ok) visible++
        }
        if (visible < MIN_VISIBLE) return Grid(0f, 0f, visible)
        var s0r = 0
        var s0g = 0
        var s0b = 0
        var n0 = 0
        var s1r = 0
        var s1g = 0
        var s1b = 0
        var n1 = 0
        for (i in 0 until 64) {
            if (!inside[i]) continue
            val par = ((i shr 3) + (i and 7)) and 1
            for (k in 0 until ns) {
                val p = samp[i * ns + k]
                if (par == 0) {
                    s0r += (p shr 16) and 255
                    s0g += (p shr 8) and 255
                    s0b += p and 255
                    n0++
                } else {
                    s1r += (p shr 16) and 255
                    s1g += (p shr 8) and 255
                    s1b += p and 255
                    n1++
                }
            }
        }
        if (n0 == 0 || n1 == 0) return Grid(0f, 0f, visible)
        val m0r = s0r.toFloat() / n0
        val m0g = s0g.toFloat() / n0
        val m0b = s0b.toFloat() / n0
        val m1r = s1r.toFloat() / n1
        val m1g = s1g.toFloat() / n1
        val m1b = s1b.toFloat() / n1
        val sep = abs(m0r - m1r) + abs(m0g - m1g) + abs(m0b - m1b)
        if (sep < MIN_SEP) return Grid(0f, sep, visible)
        val tol = max(10f, min(60f, sep * 0.45f))
        val per = FloatArray(visible)
        var at = 0
        var all = 0f
        for (i in 0 until 64) {
            if (!inside[i]) continue
            val par = ((i shr 3) + (i and 7)) and 1
            val mr = if (par == 0) m0r else m1r
            val mg = if (par == 0) m0g else m1g
            val mb = if (par == 0) m0b else m1b
            var good = 0
            for (k in 0 until ns) {
                val p = samp[i * ns + k]
                if (abs(((p shr 16) and 255) - mr) + abs(((p shr 8) and 255) - mg) + abs((p and 255) - mb) <= tol) good++
            }
            val f = good.toFloat() / ns
            per[at] = f
            at++
            all += f
        }
        java.util.Arrays.sort(per)
        val drop = min(8, visible / 4)
        var sum = 0f
        var cnt = 0
        for (i in drop until visible) {
            sum += per[i]
            cnt++
        }
        val trimmed = if (cnt > 0) sum / cnt else all / visible
        val full = all / visible
        val base = 0.5f * trimmed + 0.5f * full
        return Grid(base * (0.6f + 0.4f * min(1f, sep / FULL_SEP)), sep, visible)
    }

    // ------------------------------------------------------------------ search

    private fun shrinkFactor(w: Int, h: Int): Int = max(2, (min(w, h) / 320f).roundToInt())

    private fun shrink(px: IntArray, w: Int, h: Int, f: Int): IntArray {
        val cw = w / f
        val ch = h / f
        val out = IntArray(cw * ch)
        val n = f * f
        for (y in 0 until ch) for (x in 0 until cw) {
            var r = 0
            var g = 0
            var b = 0
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

    /**
     * Best grid at each size between sMin and sMax, searched on the shrunk copy. allowClip lets a grid hang
     * over the edge of the picture, which is how a board that is partly off-screen is recognised. Stops as soon
     * as a grid is too good to beat, so an ordinary screenshot costs one short pass.
     */
    private fun scan(
        cp: IntArray,
        cw: Int,
        ch: Int,
        sMin: Int,
        sMax: Int,
        sizeStep: Int,
        posStep: Int,
        keep: Int,
        allowClip: Boolean,
        settle: Boolean
    ): List<Cand> {
        // the best grid at each size rather than the best overall: a slightly wrong size can score as well as
        // the real board, and keeping only the overall best would let a dozen of those crowd the real one out
        val slots = ((sMax - sMin) / sizeStep) + 2
        val best = arrayOfNulls<Cand>(max(1, slots))
        val scratch = IntArray(64 * 4)
        var settled = false
        var s = max(16, sMin + ((sMax - sMin) / sizeStep) * sizeStep)
        while (s >= sMin && !settled) {
            val loX = if (allowClip) 16 - s else 0
            val hiX = if (allowClip) max(0, cw - 16) else cw - s
            val loY = if (allowClip) 16 - s else 0
            val hiY = if (allowClip) max(0, ch - 16) else ch - s
            val slot = min(best.size - 1, max(0, (s - sMin) / sizeStep))
            var top = loY
            while (top <= hiY && !settled) {
                var left = loX
                while (left <= hiX) {
                    val sc = quick(cp, cw, ch, left, top, s, scratch)
                    val cur = best[slot]
                    if (cur == null || sc > cur.score) {
                        best[slot] = Cand(left, top, s, sc)
                        if (settle && sc >= SETTLED) {
                            settled = true
                            break
                        }
                    }
                    left += posStep
                }
                top += posStep
            }
            s -= sizeStep
        }
        val out = ArrayList<Cand>()
        for (c in best) if (c != null) out.add(c)
        out.sortByDescending { it.score }
        return if (out.size > keep) ArrayList(out.subList(0, keep)) else out
    }

    /** hill-climb the rectangle onto the real square edges, a few pixels at a time */
    private fun refine(px: IntArray, w: Int, h: Int, l0: Int, t0: Int, s0: Int): Found {
        var bl = l0
        var bt = t0
        var bs = s0
        val scratch = IntArray(64 * 4)
        var bsc = quick(px, w, h, bl, bt, bs, scratch)
        for (step in intArrayOf(12, 4, 1)) {
            var guard = 0
            while (guard < 24) {
                guard++
                var nl = bl
                var nt = bt
                var ns = bs
                var nsc = bsc
                for (dl in -1..1) for (dt in -1..1) for (ds in -1..1) {
                    if (dl == 0 && dt == 0 && ds == 0) continue
                    val sc = quick(px, w, h, bl + dl * step, bt + dt * step, bs + ds * step, scratch)
                    if (sc > nsc + 0.0005f) {
                        nsc = sc
                        nl = bl + dl * step
                        nt = bt + dt * step
                        ns = bs + ds * step
                    }
                }
                if (nsc <= bsc) break
                bl = nl
                bt = nt
                bs = ns
                bsc = nsc
            }
        }
        return Found(bl, bt, bs, bsc)
    }

    /** how far into a square of q pixels a sample can sit and still be on the board rather than on a piece */
    private fun corner(q: Float): Float = min(MAX_CORNER, max(CORNER, MIN_INSET_PX / q))

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
        val sc = quick(px, w, h, ax.pos, ay.pos, size)
        return Found(ax.pos, ay.pos, size, sc)
    }

    private fun finish(px: IntArray, w: Int, h: Int, r: Found): Found {
        val e = alignByEdges(px, w, h, r.left, r.top, r.size) ?: return r
        // A few pixels either way scores the same, because the samples stay inside their own squares. The edge
        // fit is the one that knows where the squares actually end, so it wins unless it measures clearly worse.
        val ge = measure(px, w, h, e.left, e.top, e.size, 4, 0.12f).score
        val gr = measure(px, w, h, r.left, r.top, r.size, 4, 0.12f).score
        return if (ge >= gr - 0.02f) e else r
    }

    // ------------------------------------------------------------------ entry points

    private fun fail(msg: String): Found? {
        lastProblem = msg
        return null
    }

    private fun two(v: Float): String {
        val n = (v * 100f).roundToInt()
        val frac = n % 100
        return (n / 100).toString() + "." + (if (frac < 10) "0" else "") + frac.toString()
    }

    /** px = full screenshot (w x h). Returns the board rectangle in screenshot pixels, or null with [lastProblem] set. */
    fun find(px: IntArray, w: Int, h: Int): Found? {
        lastProblem = ""
        if (w <= 0 || h <= 0) return fail("the captured picture is empty")
        if (px.size < w * h) return fail("the captured picture holds " + px.size + " pixels but is said to be " + w + "x" + h)
        if (w < 48 || h < 48) return fail("the captured picture is only " + w + "x" + h + " pixels, too small to hold a board")
        try {
            return locate(px, w, h)
        } catch (e: Exception) {
            val why = e.message
            return fail("I couldn't look for the board (" + e.javaClass.simpleName + (if (why == null) "" else ": " + why) + ")")
        }
    }

    /** one full search at one stride, returning the best measured board or null */
    private fun search(
        px: IntArray,
        w: Int,
        h: Int,
        cp: IntArray,
        cw: Int,
        ch: Int,
        f: Int,
        sMin: Int,
        sMax: Int,
        sizeStep: Int,
        posStep: Int,
        allowClip: Boolean
    ): Hit? {
        val rough = scan(cp, cw, ch, sMin, sMax, sizeStep, posStep, 8, allowClip, true)
        if (rough.isEmpty()) return null
        val fine = ArrayList<Cand>()
        for (c in rough) {
            var best = c
            val loS = max(16, c.size - 8)
            val hiS = min(sMax, c.size + 8)
            for (s in loS..hiS) {
                val loX = max(if (allowClip) 16 - s else 0, c.left - 8)
                val hiX = min(if (allowClip) max(0, cw - 16) else cw - s, c.left + 8)
                val loY = max(if (allowClip) 16 - s else 0, c.top - 8)
                val hiY = min(if (allowClip) max(0, ch - 16) else ch - s, c.top + 8)
                var top = loY
                while (top <= hiY) {
                    var left = loX
                    while (left <= hiX) {
                        val sc = quick(cp, cw, ch, left, top, s)
                        if (sc > best.score) best = Cand(left, top, s, sc)
                        left++
                    }
                    top++
                }
            }
            fine.add(best)
        }
        fine.sortByDescending { it.score }

        var hit: Hit? = null
        var tries = 0
        for (c in fine) {
            if (tries >= 6) break
            tries++
            val r = refine(px, w, h, c.left * f, c.top * f, c.size * f)
            val cand = finish(px, w, h, r)
            val g = measure(px, w, h, cand.left, cand.top, cand.size, 4, 0.12f)
            if (hit == null || g.score > hit.grid.score) hit = Hit(cand, g)
            if (g.score >= SETTLED) break
        }
        return hit
    }

    private fun locate(px: IntArray, w: Int, h: Int): Found? {
        val f = shrinkFactor(w, h)
        val cp = shrink(px, w, h, f)
        val cw = w / f
        val ch = h / f
        if (cw < 24 || ch < 24) {
            return fail("the picture is " + w + "x" + h + " pixels, too small to search for a board")
        }

        val shortest = min(cw, ch)
        val sMin = max(20, (shortest * 0.30f).toInt())
        val sizeStep = max(4, (shortest * 0.02f).roundToInt())

        // whole boards first: this is the normal case and the cheap one
        var hit = search(px, w, h, cp, cw, ch, f, sMin, shortest, sizeStep, 4, false)
        if (hit == null || hit.grid.score < ACCEPT) {
            // Nothing square and whole. A board hanging over the edge of the screen is what a tall phone shows
            // when the game sits too high or too low, and it is worth naming: the fix is to scroll, not to
            // change the theme. Measured straight from the scan, without refinement, since it is a diagnosis.
            val over = scan(cp, cw, ch, sMin, (shortest * 1.6f).toInt(), sizeStep * 2, 6, 8, true, false)
            var cut = 0
            for (c in over) {
                val g = measure(px, w, h, c.left * f, c.top * f, c.size * f, 4, 0.12f)
                if (g.visible < 64 && g.visible > cut && g.score >= ACCEPT) cut = g.visible
            }
            if (cut > 0) {
                return fail("the board is partly off the edge of the screen (only " + cut + " of its 64 squares are inside the picture). Scroll or zoom so the whole board fits.")
            }
        }
        if (hit == null || hit.grid.score <= 0f) {
            return fail("there is no two-colour checkerboard anywhere on this screen, so no chess board is visible. Open a game with the board showing, and drag this panel off the board.")
        }
        if (hit.grid.score < ACCEPT) {
            return fail("the strongest checkerboard I found scored " + two(hit.grid.score) + " out of 1.0, which is too weak to trust. Make sure the whole board is showing, nothing is covering it, and the game is not paused or dimmed.")
        }
        if (hit.found.size < MIN_SIZE) {
            return fail("the board on screen is only " + hit.found.size + " pixels wide, about " + (hit.found.size / 8) + " pixels a square, which is too small to tell the pieces apart. Make the board bigger on screen.")
        }
        if (hit.grid.visible < 64) {
            return fail("the board is partly off the edge of the screen (only " + hit.grid.visible + " of its 64 squares are inside the picture). Scroll or zoom so the whole board fits.")
        }
        return hit.found
    }
}
