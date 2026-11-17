package com.example.chessreviewer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Templates(val tpl: Array<FloatArray>, val lumThreshold: Float, val occThreshold: Float) {
    fun serialize(): String =
        "$lumThreshold,$occThreshold\n" + tpl.joinToString("\n") { t -> t.joinToString(",") }

    companion object {
        fun parse(s: String): Templates? {
            return try {
                val lines = s.split("\n")
                if (lines.size != 7) return null
                val head = lines[0].split(",")
                if (head.size != 2) return null
                val t = Array(6) { i -> lines[i + 1].split(",").map { it.toFloat() }.toFloatArray() }
                if (t.any { it.size != Vision.G * Vision.G }) null
                else Templates(t, head[0].toFloat(), head[1].toFloat())
            } catch (e: Exception) {
                null
            }
        }
    }
}

class LearnResult(val templates: Templates?, val whiteBottom: Boolean, val error: String?)

/**
 * Turns a screenshot of the board into a position.
 * For every square: work out that square's own colour from the board's two square colours, find the pixels that
 * differ from it, fill the holes to get the piece silhouette, and compare the silhouette with templates learned
 * from the starting position.
 *
 * Nothing here depends on one board theme, one piece set or the light theme: the background is measured per
 * square, the background/piece split is measured per square, and white against black is decided by comparing a
 * piece with the square it stands on.
 */
object Vision {
    const val N = 40          // pixels per square after downscaling
    const val B = N * 8       // board size after downscaling
    const val G = N / 2       // feature grid per square

    /** how far in from a square edge the background patches start */
    private const val PATCH = 4

    /** why the last readPosition() or learn() came back empty (shown to the user) */
    var lastProblem: String = ""

    /** score = how "piece-like" the square is (higher = more likely a piece is on it) */
    class Sq(val score: Float, val feat: FloatArray, val lum: Float, val bgLum: Float = 0f)

    /** Box-average a square s x s board down to B x B. */
    fun downscale(src: IntArray, s: Int): IntArray {
        val out = IntArray(B * B)
        if (s < 8 || src.size < s * s) return out
        for (y in 0 until B) {
            val y0 = y * s / B
            val y1 = maxOf(y0 + 1, (y + 1) * s / B)
            for (x in 0 until B) {
                val x0 = x * s / B
                val x1 = maxOf(x0 + 1, (x + 1) * s / B)
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (yy in y0 until y1) {
                    val row = yy * s
                    for (xx in x0 until x1) {
                        val p = src[row + xx]
                        r += (p shr 16) and 255
                        g += (p shr 8) and 255
                        b += p and 255
                        n++
                    }
                }
                out[y * B + x] = ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return out
    }

    private fun lumOf(p: Int): Float =
        0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)

    private fun dist(a: Int, b: Int): Int =
        abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
            abs(((a shr 8) and 255) - ((b shr 8) and 255)) +
            abs((a and 255) - (b and 255))

    /** average colour of a 3x3 patch, PATCH px in from a corner of a square: clear of the grid line, of a
     *  coordinate label and of the wide base of a piece, and still inside the square when the crop is a few
     *  pixels off, which a real screenshot always is */

    private fun patch(px: IntArray, ox: Int, oy: Int): Int {
        var r = 0
        var g = 0
        var b = 0
        for (dy in 0 until 3) for (dx in 0 until 3) {
            val p = px[(oy + dy) * B + ox + dx]
            r += (p shr 16) and 255
            g += (p shr 8) and 255
            b += p and 255
        }
        return ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9)
    }

    /**
     * The board's two square colours, index 0 for squares where (row + col) is even and 1 where it is odd.
     * Taken as a median over the 64 squares' corner patches, so a piece, a coordinate label or a highlight on
     * a few squares cannot move the board's own colours.
     */
    private fun colours(px: IntArray): IntArray {
        val out = IntArray(2)
        if (px.size < B * B) return out
        val r0 = IntArray(64)
        val g0 = IntArray(64)
        val b0 = IntArray(64)
        val r1 = IntArray(64)
        val g1 = IntArray(64)
        val b1 = IntArray(64)
        var n0 = 0
        var n1 = 0
        for (row in 0 until 8) for (col in 0 until 8) {
            val ox = col * N
            val oy = row * N
            // the most common of the four corner patches: the one a piece or a label is least likely to cover
            val p = mostCommon(
                patch(px, ox + PATCH, oy + PATCH),
                patch(px, ox + N - PATCH - 3, oy + PATCH),
                patch(px, ox + PATCH, oy + N - PATCH - 3),
                patch(px, ox + N - PATCH - 3, oy + N - PATCH - 3)
            )
            if (((row + col) and 1) == 0) {
                r0[n0] = (p shr 16) and 255
                g0[n0] = (p shr 8) and 255
                b0[n0] = p and 255
                n0++
            } else {
                r1[n1] = (p shr 16) and 255
                g1[n1] = (p shr 8) and 255
                b1[n1] = p and 255
                n1++
            }
        }
        if (n0 == 0 || n1 == 0) return out
        java.util.Arrays.sort(r0, 0, n0)
        java.util.Arrays.sort(g0, 0, n0)
        java.util.Arrays.sort(b0, 0, n0)
        java.util.Arrays.sort(r1, 0, n1)
        java.util.Arrays.sort(g1, 0, n1)
        java.util.Arrays.sort(b1, 0, n1)
        out[0] = (r0[n0 / 2] shl 16) or (g0[n0 / 2] shl 8) or b0[n0 / 2]
        out[1] = (r1[n1 / 2] shl 16) or (g1[n1 / 2] shl 8) or b1[n1 / 2]
        return out
    }

    /** of four colours, the one closest to at least one other (pieces and labels touch one corner, not four) */
    private fun mostCommon(a: Int, b: Int, c: Int, d: Int): Int {
        val all = intArrayOf(a, b, c, d)
        var best = a
        var bestN = -1
        for (i in 0 until 4) {
            var n = 0
            for (j in 0 until 4) if (dist(all[i], all[j]) < 50) n++
            if (n > bestN) {
                bestN = n
                best = all[i]
            }
        }
        return best
    }

    /**
     * Where background ends and a piece begins, for this square. A fixed number cannot work: on a dark board the
     * whole difference is a few levels, on a light one it is a hundred. The split is put just above the noise of
     * this square's own background.
     */
    private fun threshold(d: IntArray): Int {
        val hist = IntArray(256)
        for (v in d) hist[min(255, v / 3)]++
        var mode = 0
        for (i in 1 until 256) if (hist[i] > hist[mode]) mode = i
        val modeVal = mode * 3 + 1
        var s = 0.0
        var n = 0
        for (v in d) {
            if (v <= modeVal + 8) {
                val e = (v - modeVal).toDouble()
                s += e * e
                n++
            }
        }
        val sigma = if (n > 4) sqrt(s / n).toFloat() else 0f
        return min(160f, max(22f, modeVal + max(12f, 4f * sigma))).toInt()
    }

    /** the square at (row, col) of an already downscaled board */
    fun analyzeSquare(px: IntArray, row: Int, col: Int): Sq = analyze(px, row, col, colours(px))

    private fun analyze(px: IntArray, row: Int, col: Int, cols: IntArray): Sq {
        if (px.size < B * B) return Sq(0f, FloatArray(G * G), 0f, 0f)
        val ox = col * N
        val oy = row * N
        val parity = (row + col) and 1
        var bg = cols[parity]
        val p11 = patch(px, ox + PATCH, oy + PATCH)
        val p1n = patch(px, ox + N - PATCH - 3, oy + PATCH)
        val pn1 = patch(px, ox + PATCH, oy + N - PATCH - 3)
        val pnn = patch(px, ox + N - PATCH - 3, oy + N - PATCH - 3)
        // a last-move highlight or a check flash paints the whole square: all four patches then agree on a
        // colour that is clearly not this square's own. Follow it, so a piece standing on a highlighted square
        // is measured against the colour it really sits on.
        val ar = ((p11 shr 16 and 255) + (p1n shr 16 and 255) + (pn1 shr 16 and 255) + (pnn shr 16 and 255)) / 4
        val ag = ((p11 shr 8 and 255) + (p1n shr 8 and 255) + (pn1 shr 8 and 255) + (pnn shr 8 and 255)) / 4
        val ab = ((p11 and 255) + (p1n and 255) + (pn1 and 255) + (pnn and 255)) / 4
        val ave = (ar shl 16) or (ag shl 8) or ab
        var agree = true
        for (p in intArrayOf(p11, p1n, pn1, pnn)) if (dist(p, ave) > 45) agree = false
        if (agree && dist(ave, bg) > 70) bg = ave

        val br = (bg shr 16) and 255
        val bgc = (bg shr 8) and 255
        val bb = bg and 255
        val d = IntArray(N * N)
        for (y in 0 until N) for (x in 0 until N) {
            val p = px[(oy + y) * B + ox + x]
            d[y * N + x] = abs(((p shr 16) and 255) - br) + abs(((p shr 8) and 255) - bgc) + abs((p and 255) - bb)
        }
        val cut = threshold(d)
        // The outer two pixels of the square are where the picture is resampled between squares, where the
        // grid line is, and where a coordinate label is written. They belong to the board, never to a piece,
        // so the silhouette stops short of them and a piece touching an edge cannot swallow the square.
        val mask = BooleanArray(N * N)
        for (y in 0 until N) for (x in 0 until N) {
            if (x < 2 || y < 2 || x >= N - 2 || y >= N - 2) continue
            mask[y * N + x] = d[y * N + x] > cut
        }

        // flood-fill the background from the border; whatever isn't reached is part of the piece
        val reached = BooleanArray(N * N)
        val queue = IntArray(N * N)
        var head = 0
        var tail = 0
        fun push(i: Int) {
            if (!mask[i] && !reached[i]) {
                reached[i] = true
                queue[tail] = i
                tail++
            }
        }
        for (i in 0 until N) {
            push(i)
            push((N - 1) * N + i)
            push(i * N)
            push(i * N + N - 1)
        }
        while (head < tail) {
            val i = queue[head]
            head++
            val y = i / N
            val x = i % N
            if (y > 0) push(i - N)
            if (y < N - 1) push(i + N)
            if (x > 0) push(i - 1)
            if (x < N - 1) push(i + 1)
        }
        val sil = BooleanArray(N * N) { mask[it] || !reached[it] }

        // Islands that are not pieces: a coordinate label, the dot on an empty legal-move square, the ring
        // round a selected piece. All of them live on top of squares that are empty.
        val label = IntArray(N * N) { -1 }
        val stack = IntArray(N * N)
        val members = IntArray(N * N)
        val start = IntArray(40)
        val end = IntArray(40)
        var comps = 0
        var mem = 0
        for (s in 0 until N * N) {
            if (!sil[s] || label[s] >= 0) continue
            if (comps >= 39) break
            start[comps] = mem
            var sp = 0
            stack[sp++] = s
            label[s] = comps
            members[mem++] = s
            while (sp > 0) {
                val i = stack[--sp]
                val y = i / N
                val x = i % N
                if (y > 0 && sil[i - N] && label[i - N] < 0) {
                    label[i - N] = comps
                    stack[sp++] = i - N
                    members[mem++] = i - N
                }
                if (y < N - 1 && sil[i + N] && label[i + N] < 0) {
                    label[i + N] = comps
                    stack[sp++] = i + N
                    members[mem++] = i + N
                }
                if (x > 0 && sil[i - 1] && label[i - 1] < 0) {
                    label[i - 1] = comps
                    stack[sp++] = i - 1
                    members[mem++] = i - 1
                }
                if (x < N - 1 && sil[i + 1] && label[i + 1] < 0) {
                    label[i + 1] = comps
                    stack[sp++] = i + 1
                    members[mem++] = i + 1
                }
            }
            end[comps] = mem
            comps++
        }

        val keep = BooleanArray(N * N)
        var area = 0
        val minArea = max(24, (N * N) / 40)
        for (id in 0 until comps) {
            val a = end[id] - start[id]
            if (a < minArea) continue
            var x0 = N
            var x1 = -1
            var y0 = N
            var y1 = -1
            for (k in start[id] until end[id]) {
                val i = members[k]
                val x = i % N
                val y = i / N
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            val fill = a.toFloat() / (bw * bh)
            // the small round dot chess.com puts on a square a piece can move to
            if (bw <= N * 0.42f && bh <= N * 0.42f && fill > 0.5f && a < N * N * 0.2f) continue
            // the ring round the piece you have picked up, or the frame of the board itself
            if (bw >= N * 0.86f && bh >= N * 0.86f && fill < 0.4f) continue
            for (k in start[id] until end[id]) keep[members[k]] = true
            area += a
        }
        if (area == 0) return Sq(0f, FloatArray(G * G), 0f, lumOf(bg))

        val feat = FloatArray(G * G)
        for (gy in 0 until G) for (gx in 0 until G) {
            var c = 0
            for (dy in 0..1) for (dx in 0..1) if (keep[(gy * 2 + dy) * N + gx * 2 + dx]) c++
            feat[gy * G + gx] = c / 4f
        }

        var ls = 0f
        var ln = 0
        var energy = 0
        for (y in 10 until 30) for (x in 10 until 30) {
            val i = y * N + x
            if (keep[i]) {
                ls += lumOf(px[(oy + y) * B + ox + x])
                ln++
                energy += minOf(d[i], 200)
            }
        }
        val lum = if (ln > 0) ls / ln else 0f
        return Sq(area + energy / 20f, feat, lum, lumOf(bg))
    }

    /** Learn piece shapes from a screenshot of the starting position. */
    fun learn(px: IntArray): LearnResult {
        lastProblem = ""
        if (px.size < B * B) {
            lastProblem = "the board picture was " + px.size + " pixels, less than the " + (B * B) + " a board needs"
            return LearnResult(null, true, lastProblem)
        }
        try {
            return learnNow(px)
        } catch (e: Exception) {
            val why = e.message
            lastProblem = "I couldn't learn this board (" + e.javaClass.simpleName + (if (why == null) "" else ": " + why) + ")"
            return LearnResult(null, true, lastProblem)
        }
    }

    private fun learnNow(px: IntArray): LearnResult {
        val cols = colours(px)
        val sq = Array(64) { analyze(px, it / 8, it % 8, cols) }

        // we know exactly 32 squares hold pieces: take the 32 most piece-like squares and compare with the start layout
        val order = (0 until 64).sortedByDescending { sq[it].score }
        val top = order.take(32).toSet()
        val missing = ArrayList<Int>()
        val extra = ArrayList<Int>()
        for (i in 0 until 64) {
            val r = i / 8
            val expected = r <= 1 || r >= 6
            val seen = i in top
            if (expected && !seen) missing.add(i)
            if (!expected && seen) extra.add(i)
        }
        // a couple of odd squares are tolerated (e.g. one highlighted or selected piece)
        if (missing.size + extra.size > 2) {
            fun lbl(list: List<Int>): String =
                list.take(6).joinToString(", ") { "col " + (it % 8 + 1) + "/row " + (it / 8 + 1) }
            var msg = "Start position not recognised."
            if (missing.isNotEmpty()) msg += " Can't see a piece at: " + lbl(missing) + "."
            if (extra.isNotEmpty()) msg += " Saw something on an empty square at: " + lbl(extra) + "."
            msg += " (Counted from the board's top-left corner.) Make sure the board is in its starting position (no piece selected or highlighted) and no arrows, hints or pop-ups cover it."
            lastProblem = msg
            return LearnResult(null, true, msg)
        }

        // squares we trust as real pieces of the start layout
        val use = BooleanArray(64)
        for (i in 0 until 64) {
            val r = i / 8
            use[i] = (r <= 1 || r >= 6) && (i in top)
        }

        var minGood = Float.MAX_VALUE
        var maxEmpty = 0f
        for (i in 0 until 64) {
            val r = i / 8
            val expected = r <= 1 || r >= 6
            if (use[i]) minGood = minOf(minGood, sq[i].score)
            else if (!expected && i !in top) maxEmpty = maxOf(maxEmpty, sq[i].score)
        }
        val occ = if (minGood > maxEmpty) (minGood + maxEmpty) / 2f else minGood * 0.85f

        fun avg(rows: IntRange): Float {
            var s = 0f
            var n = 0
            for (r in rows) for (c in 0 until 8) {
                if (use[r * 8 + c]) {
                    s += sq[r * 8 + c].lum
                    n++
                }
            }
            return if (n > 0) s / n else 0f
        }
        val bottomLum = avg(6..7)
        val topLum = avg(0..1)
        val whiteBottom = bottomLum > topLum
        val whiteLum = if (whiteBottom) bottomLum else topLum
        val blackLum = if (whiteBottom) topLum else bottomLum
        if (whiteLum - blackLum < 30f) {
            lastProblem = "Can't tell the white pieces from the black ones (colours too similar). Try a different piece set or board theme."
            return LearnResult(null, true, lastProblem)
        }

        val backW = if (whiteBottom) "RNBQKBNR" else "RNBKQBNR"
        val backB = if (whiteBottom) "rnbqkbnr" else "rnbkqbnr"

        val sum = Array(6) { FloatArray(G * G) }
        val cnt = IntArray(6)
        for (i in 0 until 64) {
            val r = i / 8
            val c = i % 8
            if (r in 2..5) continue
            if (!use[i]) continue
            val ch: Char =
                if (r == 0) (if (whiteBottom) backB[c] else backW[c])
                else if (r == 1) (if (whiteBottom) 'p' else 'P')
                else if (r == 6) (if (whiteBottom) 'P' else 'p')
                else (if (whiteBottom) backW[c] else backB[c])
            val t = "pnbrqk".indexOf(ch.lowercaseChar())
            val f = sq[i].feat
            for (j in f.indices) sum[t][j] += f[j]
            cnt[t]++
        }
        for (t in 0 until 6) if (cnt[t] == 0) {
            lastProblem = "Couldn't learn every piece type. Reset the board to the start position and try again."
            return LearnResult(null, true, lastProblem)
        }
        val tpl = Array(6) { t -> FloatArray(G * G) { j -> sum[t][j] / cnt[t] } }
        return LearnResult(Templates(tpl, (whiteLum + blackLum) / 2f, occ), whiteBottom, null)
    }

    private fun classify(s: Sq, t: Templates): Char {
        var best = 0
        var bd = Float.MAX_VALUE
        for (i in 0 until 6) {
            val tp = t.tpl[i]
            var d = 0f
            for (j in tp.indices) {
                val e = s.feat[j] - tp[j]
                d += e * e
            }
            if (d < bd) {
                bd = d
                best = i
            }
        }
        val ch = "pnbrqk"[best]
        // White or black: compare the piece with the square it stands on, so another board theme, a dark
        // theme or a highlighted square cannot flip the colours. When the piece and its square are almost the
        // same brightness, fall back to the white/black split learned from the starting position.
        val white = when {
            s.lum > s.bgLum + 25f -> true
            s.lum < s.bgLum - 25f -> false
            else -> s.lum > t.lumThreshold
        }
        return if (white) ch.uppercaseChar() else ch
    }

    /** Reads the whole board. Returns null if the result isn't a sane chess position, with [lastProblem] set. */
    fun readPosition(px: IntArray, t: Templates, whiteAtBottom: Boolean): CharArray? {
        lastProblem = ""
        if (px.size < B * B) {
            lastProblem = "the board picture was " + px.size + " pixels, less than the " + (B * B) + " a board needs"
            return null
        }
        try {
            return readNow(px, t, whiteAtBottom)
        } catch (e: Exception) {
            val why = e.message
            lastProblem = "I couldn't read the board (" + e.javaClass.simpleName + (if (why == null) "" else ": " + why) + ")"
            return null
        }
    }

    private fun readNow(px: IntArray, t: Templates, whiteAtBottom: Boolean): CharArray? {
        val cols = colours(px)
        val pos = CharArray(64) { '.' }
        for (r in 0 until 8) for (c in 0 until 8) {
            val s = analyze(px, r, c, cols)
            if (s.score < t.occThreshold) continue
            val file = if (whiteAtBottom) c else 7 - c
            val rank = if (whiteAtBottom) 7 - r else r
            pos[rank * 8 + file] = classify(s, t)
        }
        var wk = 0
        var bk = 0
        var n = 0
        for (ch in pos) {
            if (ch == 'K') wk++
            if (ch == 'k') bk++
            if (ch != '.') n++
        }
        if (n == 0) {
            lastProblem = "I can't see a single piece on the board"
            return null
        }
        if (n > 32) {
            lastProblem = "I see " + n + " pieces, which is more than a chess set has; something on the board is being read as a piece"
            return null
        }
        if (wk != 1 || bk != 1) {
            lastProblem = "I see " + n + " pieces, " + wk + " white king(s), " + bk + " black king(s)"
            return null
        }
        for (f in 0..7) {
            if (pos[f] == 'P' || pos[f] == 'p' || pos[56 + f] == 'P' || pos[56 + f] == 'p') {
                lastProblem = "a pawn looks like it is on the first or last rank"
                return null
            }
        }
        return pos
    }
}
