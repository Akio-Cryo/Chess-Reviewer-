// Checks BoardFinder and Vision against screenshots drawn the way a phone shows a chess game.
//
// Neither class touches Android, so this file and the three under test compile and run on a desktop JVM:
//
//   kotlinc app/src/main/java/com/example/chessreviewer/BoardFinder.kt \\
//           app/src/main/java/com/example/chessreviewer/Vision.kt \\
//           app/src/main/java/com/example/chessreviewer/Chess.kt \\
//           tools/BoardCheck.kt -include-runtime -d boardcheck.jar
//   java -jar boardcheck.jar
//
// It is not part of the APK build (tools/ is outside app/src). Every case either reads the position it drew or
// comes back with a sentence saying why not. The second half is the point: a bare null is what the panel cannot
// explain to the player.
package com.example.chessreviewer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.io.File

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "ok   " else "FAIL ") + name + (if (ok || detail.isEmpty()) "" else "  -> " + detail))
    if (!ok) failures++
}

// ------------------------------------------------------------------ drawing

/** the way Bitmap.getPixels hands a pixel over: opaque, so the int is negative */
private fun rgb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)

private fun mix(a: Int, b: Int, f: Float): Int {
    val ar = (a shr 16) and 255
    val ag = (a shr 8) and 255
    val ab = a and 255
    val br = (b shr 16) and 255
    val bg = (b shr 8) and 255
    val bb = b and 255
    return rgb(
        (ar + (br - ar) * f).roundToInt(),
        (ag + (bg - ag) * f).roundToInt(),
        (ab + (bb - ab) * f).roundToInt()
    )
}

/** A phone screenshot being drawn. */
private class Shot(val w: Int, val h: Int) {
    val px = IntArray(w * h)

    fun fill(c: Int) {
        java.util.Arrays.fill(px, c)
    }

    fun blend(x: Int, y: Int, c: Int, f: Float) {
        if (x < 0 || y < 0 || x >= w || y >= h) return
        px[y * w + x] = mix(px[y * w + x], c, f)
    }

    fun box(x0: Int, y0: Int, x1: Int, y1: Int, c: Int, f: Float = 1f) {
        for (y in y0 until y1) for (x in x0 until x1) blend(x, y, c, f)
    }

    fun disc(cx: Float, cy: Float, r: Float, c: Int, f: Float = 1f) {
        val x0 = (cx - r).toInt()
        val x1 = (cx + r).toInt() + 1
        val y0 = (cy - r).toInt()
        val y1 = (cy + r).toInt() + 1
        for (y in y0 until y1) for (x in x0 until x1) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            if (dx * dx + dy * dy <= r * r) blend(x, y, c, f)
        }
    }

    fun ring(cx: Float, cy: Float, r: Float, thick: Float, c: Int, f: Float = 1f) {
        val x0 = (cx - r - thick).toInt()
        val x1 = (cx + r + thick).toInt() + 1
        val y0 = (cy - r - thick).toInt()
        val y1 = (cy + r + thick).toInt() + 1
        for (y in y0 until y1) for (x in x0 until x1) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            val d = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (d >= r - thick && d <= r + thick) blend(x, y, c, f)
        }
    }

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, c: Int, f: Float = 1f) {
        val x0 = (cx - rx).toInt()
        val x1 = (cx + rx).toInt() + 1
        val y0 = (cy - ry).toInt()
        val y1 = (cy + ry).toInt() + 1
        for (y in y0 until y1) for (x in x0 until x1) {
            val dx = (x + 0.5f - cx) / rx
            val dy = (y + 0.5f - cy) / ry
            if (dx * dx + dy * dy <= 1f) blend(x, y, c, f)
        }
    }
}

private class Theme(val name: String, val light: Int, val dark: Int, val white: Int, val black: Int)

/** One piece, drawn from circles and bars, inside its square. */
private fun piece(s: Shot, kind: Char, cx: Float, cy: Float, q: Float, col: Int, k: Float) {
    val head = q * k
    when (kind.lowercaseChar()) {
        'p' -> {
            s.disc(cx, cy - 0.18f * head, 0.12f * head, col)
            s.ellipse(cx, cy + 0.10f * head, 0.19f * head, 0.19f * head, col)
        }
        'r' -> {
            s.box((cx - 0.22f * head).toInt(), (cy - 0.16f * head).toInt(), (cx + 0.22f * head).toInt(), (cy + 0.32f * head).toInt(), col)
            for (i in 0 until 3) s.disc(cx + (-0.18f + 0.18f * i) * head, cy - 0.20f * head, 0.075f * head, col)
        }
        'n' -> {
            s.disc(cx + 0.08f * head, cy - 0.20f * head, 0.14f * head, col)
            s.ellipse(cx - 0.04f * head, cy + 0.06f * head, 0.22f * head, 0.24f * head, col)
        }
        'b' -> {
            s.disc(cx, cy - 0.28f * head, 0.08f * head, col)
            s.ellipse(cx, cy - 0.05f * head, 0.15f * head, 0.20f * head, col)
            s.ellipse(cx, cy + 0.20f * head, 0.22f * head, 0.10f * head, col)
        }
        'q' -> {
            s.ellipse(cx, cy + 0.12f * head, 0.20f * head, 0.20f * head, col)
            for (i in 0 until 5) s.disc(cx + (-0.22f + 0.11f * i) * head, cy - 0.24f * head, 0.055f * head, col)
        }
        else -> {
            s.ellipse(cx, cy + 0.12f * head, 0.19f * head, 0.20f * head, col)
            s.box((cx - 0.04f * head).toInt(), (cy - 0.34f * head).toInt(), (cx + 0.04f * head).toInt(), (cy - 0.08f * head).toInt(), col)
            s.box((cx - 0.13f * head).toInt(), (cy - 0.28f * head).toInt(), (cx + 0.13f * head).toInt(), (cy - 0.21f * head).toInt(), col)
        }
    }
    // the base every piece stands on
    s.ellipse(cx, cy + 0.34f * head, 0.30f * head, 0.06f * head, col)
}

/** a coordinate label: two short strokes in the corner of an edge square */
private fun glyph(s: Shot, x: Int, y: Int, q: Float, col: Int) {
    val hh = max(3, (q * 0.16f).roundToInt())
    val ww = max(2, (q * 0.08f).roundToInt())
    s.box(x, y, x + ww, y + hh, col, 0.8f)
    s.box(x, y + hh / 2, x + ww + ww / 2, y + hh / 2 + 1, col, 0.8f)
}

private fun cellOf(name: String, whiteBottom: Boolean): Int {
    val file = name[0] - 'a'
    val rank = name[1] - '1'
    return if (whiteBottom) (7 - rank) * 8 + file else rank * 8 + (7 - file)
}

/** 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR' to the CharArray(64) Chess uses */
private fun board(placement: String): CharArray {
    val pos = CharArray(64) { '.' }
    val ranks = placement.split("/")
    for (ri in 0 until 8) {
        val rank = 7 - ri
        var file = 0
        for (ch in ranks[ri]) {
            if (ch in '0'..'9') {
                file += ch - '0'
            } else {
                if (file in 0..7) pos[rank * 8 + file] = ch
                file++
            }
        }
    }
    return pos
}

private fun drawGame(
    w: Int,
    h: Int,
    left: Int,
    top: Int,
    size: Int,
    th: Theme,
    pos: CharArray,
    whiteBottom: Boolean,
    coords: Boolean,
    lastMove: Pair<String, String>?,
    dots: List<String>,
    outlined: Boolean
): Shot {
    val s = Shot(w, h)
    s.fill(rgb(28, 28, 28))
    // a header band and a chat band, so the screenshot is a phone screen and not just a board
    s.box(0, 0, w, 110, rgb(48, 48, 52))
    for (i in 0 until 6) s.box(40 + i * 90, 46, 40 + i * 90 + 60, 64, rgb(96, 96, 100))
    s.box(0, h - 150, w, h, rgb(40, 40, 44))
    for (i in 0 until 4) s.box(30 + i * 150, h - 110, 30 + i * 150 + 110, h - 96, rgb(84, 84, 88))

    val q = size / 8f
    val hi = HashSet<Int>()
    if (lastMove != null) {
        hi.add(cellOf(lastMove.first, whiteBottom))
        hi.add(cellOf(lastMove.second, whiteBottom))
    }
    val dotCells = dots.map { cellOf(it, whiteBottom) }.toSet()

    for (r in 0 until 8) for (c in 0 until 8) {
        val x0 = (left + c * q).roundToInt()
        val y0 = (top + r * q).roundToInt()
        val x1 = (left + (c + 1) * q).roundToInt()
        val y1 = (top + (r + 1) * q).roundToInt()
        val light = ((r + c) and 1) == 0
        s.box(x0, y0, x1, y1, if (light) th.light else th.dark)
        if ((r * 8 + c) in hi) s.box(x0, y0, x1, y1, rgb(255, 226, 60), 0.38f)
        if ((r * 8 + c) in dotCells) s.disc((x0 + x1) / 2f, (y0 + y1) / 2f, q * 0.15f, rgb(20, 20, 20), 0.16f)
    }

    for (r in 0 until 8) for (c in 0 until 8) {
        val ch = pos[if (whiteBottom) (7 - r) * 8 + c else r * 8 + (7 - c)]
        if (ch == '.') continue
        val cx = left + (c + 0.5f) * q
        val cy = top + (r + 0.5f) * q
        val white = ch.isUpperCase()
        val col = if (white) th.white else th.black
        if (outlined) {
            val rim = if (white) rgb(20, 20, 20) else rgb(20, 20, 20)
            piece(s, ch, cx, cy, q, rim, 1.10f)
        }
        piece(s, ch, cx, cy, q, col, 1f)
    }

    if (coords) {
        val col = mix(th.light, th.dark, 0.5f)
        for (c in 0 until 8) {
            val x0 = (left + c * q).roundToInt()
            val y1 = (top + 8 * q).roundToInt()
            glyph(s, x0 + (q * 0.78f).toInt(), y1 - (q * 0.22f).toInt(), q, col)
        }
        for (r in 0 until 8) {
            val y0 = (top + r * q).roundToInt()
            glyph(s, left + (q * 0.04f).toInt(), y0 + (q * 0.05f).toInt(), q, col)
        }
    }
    return s
}

private fun flat(w: Int, h: Int, c: Int): Shot {
    val s = Shot(w, h)
    s.fill(c)
    return s
}

// ------------------------------------------------------------------ helpers

private fun crop(shot: Shot, left: Int, top: Int, size: Int): IntArray {
    val out = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) out[y * size + x] = shot.px[(top + y) * shot.w + (left + x)]
    return out
}

/** null when the rectangle really is eight by eight squares of two alternating colours */
private fun eightByEight(shot: Shot, left: Int, top: Int, size: Int): String? {
    if (left < 0 || top < 0 || left + size > shot.w || top + size > shot.h) return "the rectangle runs off the picture"
    if (size < 64) return "the rectangle is only " + size + " pixels wide"
    val q = size / 8f
    val sum = FloatArray(6)
    val n = IntArray(2)
    for (r in 0 until 8) for (c in 0 until 8) {
        val par = (r + c) and 1
        for (k in 0 until 4) {
            val fx = if ((k and 1) == 0) 0.15f else 0.85f
            val fy = if (k < 2) 0.15f else 0.85f
            val x = (left + (c + fx) * q).toInt()
            val y = (top + (r + fy) * q).toInt()
            val p = shot.px[y * shot.w + x]
            sum[par * 3] += ((p shr 16) and 255).toFloat()
            sum[par * 3 + 1] += ((p shr 8) and 255).toFloat()
            sum[par * 3 + 2] += (p and 255).toFloat()
            n[par]++
        }
    }
    if (n[0] == 0 || n[1] == 0) return "no squares of one colour"
    val sep = abs(sum[0] / n[0] - sum[3] / n[1]) + abs(sum[1] / n[0] - sum[4] / n[1]) + abs(sum[2] / n[0] - sum[5] / n[1])
    if (sep < 40f) return "the two square colours are only " + sep.roundToInt() + " apart"
    var good = 0
    for (r in 0 until 8) for (c in 0 until 8) {
        val par = (r + c) and 1
        var d0 = 0f
        var d1 = 0f
        for (k in 0 until 4) {
            val fx = if ((k and 1) == 0) 0.15f else 0.85f
            val fy = if (k < 2) 0.15f else 0.85f
            val x = (left + (c + fx) * q).toInt()
            val y = (top + (r + fy) * q).toInt()
            val p = shot.px[y * shot.w + x]
            for (q2 in 0 until 3) {
                val v = ((p shr (16 - q2 * 8)) and 255).toFloat()
                d0 += abs(v - sum[q2] / n[0])
                d1 += abs(v - sum[3 + q2] / n[1])
            }
        }
        if ((par == 0 && d0 < d1) || (par == 1 && d1 < d0)) good++
    }
    return if (good >= 60) null else "only " + good + " of 64 squares line up with a two colour grid"
}

/** the rectangle lands on the board: within 2% of it, so the crop holds all eight squares and no more of
 *  anything else. The exact pixel is not the point, and a low-contrast theme cannot pin it down closer. */
private fun covers(f: BoardFinder.Found, left: Int, top: Int, size: Int): Boolean {
    val slack = max(4, (size / 50.0).roundToInt())
    return abs(f.size - size) <= slack + 4 && abs(f.left - left) <= slack && abs(f.top - top) <= slack
}

// ------------------------------------------------------------------ real chess.com screenshots
//
// Everything above is drawn from circles and bars: it proves the pipeline, not chess.com. The cases below use
// chess.com's own files instead. The board is the texture PNG chess.com serves for that theme (1600x1600 for
// the whole eight by eight, so it is scaled exactly as the board element scales its background). The pieces are
// the PNGs for that piece set, 150x150 each. The coordinates are drawn at the geometry its board uses, read off
// the live analysis board: an svg with viewBox "0 0 100 100", font-size 2.8, weight 600, rank digits at x 0.75
// with baselines 3.5, 15.75 ... 90.75, file letters at x 10, 22.5 ... 97.5 on a baseline of 99, each label
// painted in the other square's colour. A theme that fails here fails on a phone.

/** where the downloaded chess.com files are, or null when this check is run without them */
private fun fixtures(): File? {
    val tries = ArrayList<String>()
    val given = System.getProperty("fixtures") ?: System.getenv("CHESS_FIXTURES")
    if (given != null) tries.add(given)
    tries.add("tools/fixtures/chesscom")
    tries.add("../tools/fixtures/chesscom")
    for (t in tries) if (File(t, "boards/green.png").isFile) return File(t)
    return null
}

private fun png(f: File): java.awt.image.BufferedImage? = try {
    if (f.isFile) javax.imageio.ImageIO.read(f) else null
} catch (e: Exception) {
    null
}

/** one board theme and one piece set, as chess.com ships them */
private class Skin(val board: java.awt.image.BufferedImage, val pieces: Map<Char, java.awt.image.BufferedImage>)

private fun skin(dir: File, theme: String, set: String): Skin? {
    val b = png(File(dir, "boards/" + theme + ".png")) ?: return null
    val m = HashMap<Char, java.awt.image.BufferedImage>()
    for (p in "pnbrqk") {
        val w = png(File(dir, "pieces/" + set + "/w" + p + ".png")) ?: return null
        val bl = png(File(dir, "pieces/" + set + "/b" + p + ".png")) ?: return null
        m[p.uppercaseChar()] = w
        m[p] = bl
    }
    return Skin(b, m)
}

/** the theme's two square colours, averaged out of its own texture: a8 is the light one */
private fun skinColours(b: java.awt.image.BufferedImage): IntArray {
    fun at(cx: Int, cy: Int): Int {
        val f = b.width / 8
        var r = 0
        var g = 0
        var bl = 0
        var n = 0
        for (y in 0 until f / 4) for (x in 0 until f / 4) {
            val p = b.getRGB(cx * f + f / 4 + x, cy * f + f / 4 + y)
            r += (p shr 16) and 255
            g += (p shr 8) and 255
            bl += p and 255
            n++
        }
        return rgb(r / n, g / n, bl / n)
    }
    return intArrayOf(at(0, 0), at(1, 0))
}

/**
 * A phone screenshot of a game, built out of the skin's own pixels. The chrome around the board is rectangles,
 * which is the point: a real screen is mostly rectangles, and only the board is a board.
 */
private fun realShot(
    w: Int,
    h: Int,
    left: Int,
    top: Int,
    size: Int,
    sk: Skin,
    pos: CharArray,
    whiteBottom: Boolean,
    coords: Boolean,
    lastMove: Pair<String, String>?,
    dots: List<String>,
    selected: String?
): Shot {
    val out = Shot(w, h)
    val im = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = im.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

    // the app around the board: status bar, the opponent, the board, you, the tab bar
    g.color = java.awt.Color(48, 46, 43)
    g.fillRect(0, 0, w, h)
    g.color = java.awt.Color(38, 36, 33)
    g.fillRect(0, 0, w, h / 28)
    g.color = java.awt.Color(60, 58, 55)
    g.fillOval(w / 22, h / 60, w / 12, w / 12)
    g.fillOval(w / 22, top + size + h / 60, w / 12, w / 12)
    g.color = java.awt.Color(90, 88, 84)
    g.fillRect(w / 6, h / 40, w / 2, h / 90)
    g.fillRect(w / 6, top + size + h / 50, w / 2, h / 90)
    g.fillRect(w / 6, top - h / 60, w / 2, h / 120)
    g.color = java.awt.Color(70, 68, 65)
    g.fillRect(0, h - h / 22, w, h / 22)
    for (i in 0 until 5) g.fillRect(w / 10 + i * w / 6, h - h / 30, w / 14, h / 90)

    // the board, from the theme's own texture
    g.drawImage(sk.board, left, top, left + size, top + size, null)
    val q = size / 8f
    val cols = skinColours(sk.board)

    fun squareRect(cell: Int): IntArray {
        val r = cell / 8
        val c = cell % 8
        return intArrayOf(
            (left + c * q).roundToInt(),
            (top + r * q).roundToInt(),
            (left + (c + 1) * q).roundToInt(),
            (top + (r + 1) * q).roundToInt()
        )
    }

    // the last move, and the square a piece was picked up from: a translucent yellow, as the app paints it
    fun tint(cell: Int, alpha: Float) {
        val rect = squareRect(cell)
        val old = g.composite
        g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha)
        g.color = java.awt.Color(255, 255, 60)
        g.fillRect(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
        g.composite = old
    }
    if (lastMove != null) {
        tint(cellOf(lastMove.first, whiteBottom), 0.3f)
        tint(cellOf(lastMove.second, whiteBottom), 0.3f)
    }
    if (selected != null) tint(cellOf(selected, whiteBottom), 0.45f)

    // a dot on every square the picked-up piece can go to
    for (d in dots) {
        val rect = squareRect(cellOf(d, whiteBottom))
        val old = g.composite
        g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.16f)
        g.color = java.awt.Color.BLACK
        val rad = (q * 0.145f).roundToInt()
        g.fillOval((rect[0] + rect[2]) / 2 - rad, (rect[1] + rect[3]) / 2 - rad, rad * 2, rad * 2)
        g.composite = old
    }

    // the pieces, from the set's own PNGs, drawn at the size of a square exactly as the board draws them
    for (r in 0 until 8) for (c in 0 until 8) {
        val file = if (whiteBottom) c else 7 - c
        val rank = if (whiteBottom) 7 - r else r
        val ch = pos[rank * 8 + file]
        if (ch == '.') continue
        val art = sk.pieces[ch] ?: continue
        val x0 = (left + c * q).roundToInt()
        val y0 = (top + r * q).roundToInt()
        g.drawImage(art, x0, y0, q.roundToInt(), q.roundToInt(), null)
    }

    // the coordinates, at chess.com's own geometry, each in the other square's colour
    if (coords) {
        g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, max(6, (0.028f * size).toInt()))
        for (i in 0..7) {
            g.color = java.awt.Color(if ((i and 1) == 0) cols[1] else cols[0])
            g.drawString((8 - i).toString(), (left + 0.0075f * size).toInt(), (top + (3.5f + 12.5f * i) / 100f * size).roundToInt())
            g.color = java.awt.Color(if (((7 + i) and 1) == 0) cols[1] else cols[0])
            g.drawString(('a' + i).toString(), (left + (10f + 12.5f * i) / 100f * size).toInt(), (top + 0.99f * size).roundToInt())
        }
    }
    g.dispose()
    im.getRGB(0, 0, w, h, out.px, 0, w)
    return out
}

/** how a crop of the board is read: find it, learn the start from it, then read one position */
private fun readFrom(shot: Shot, tpl: Templates, whiteBottom: Boolean): CharArray? {
    val f = BoardFinder.find(shot.px, shot.w, shot.h) ?: return null
    return Vision.readPosition(Vision.downscale(crop(shot, f.left, f.top, f.size), f.size), tpl, whiteBottom)
}

/** one real-asset case: all the checks a phone screenshot has to pass, or a sentence saying why it did not */
private fun dump(name: String, shot: Shot) {
    if (System.getProperty("dump") == null) return
    val im = java.awt.image.BufferedImage(shot.w, shot.h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    im.setRGB(0, 0, shot.w, shot.h, shot.px, 0, shot.w)
    val f = File("/tmp/w11/dump-" + name.replace(" ", "-") + ".png")
    f.parentFile.mkdirs()
    javax.imageio.ImageIO.write(im, "png", f)
}

private fun realCase(
    name: String,
    shot: Shot,
    left: Int,
    top: Int,
    size: Int,
    expect: String,
    whiteBottom: Boolean,
    after: ((Shot, Templates) -> Unit)? = null
) {
    dump(name, shot)
    val f = BoardFinder.find(shot.px, shot.w, shot.h)
    check(name + ": board found", f != null, BoardFinder.lastProblem)
    if (f == null) {
        check(name + ": reason is a sentence", BoardFinder.lastProblem.length > 20, "reason: " + BoardFinder.lastProblem)
        println("     (" + name + ": " + BoardFinder.lastProblem + ")")
        return
    }
    val why = eightByEight(shot, f.left, f.top, f.size)
    check(name + ": eight by eight squares", why == null, why ?: "")
    check(name + ": rectangle covers the board", covers(f, left, top, size), "left " + f.left + " top " + f.top + " size " + f.size)
    val small = Vision.downscale(crop(shot, f.left, f.top, f.size), f.size)
    val lr = Vision.learn(small)
    check(name + ": starting position learned", lr.templates != null, lr.error ?: "")
    val tpl = lr.templates ?: return
    check(name + ": board is the right way up", lr.whiteBottom == whiteBottom, "whiteBottom=" + lr.whiteBottom)
    val pos = Vision.readPosition(small, tpl, whiteBottom)
    check(name + ": position read", pos != null, Vision.lastProblem)
    if (pos != null) check(name + ": position matches", Chess.placement(pos) == expect, "read " + Chess.placement(pos))
    if (after != null) after(shot, tpl)
}

// ------------------------------------------------------------------ the cases

fun main() {
    System.setProperty("java.awt.headless", "true")
    val pale = rgb(238, 238, 210)
    val green = rgb(118, 150, 86)
    val classic = Theme("classic green", pale, green, rgb(250, 250, 250), rgb(38, 38, 38))
    val dark = Theme("dark", rgb(58, 53, 46), rgb(42, 38, 34), rgb(237, 232, 222), rgb(74, 69, 64))
    val start = board(Chess.START)
    val mid = board("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R")

    // 1: the ordinary case, a portrait phone with the board full width and coordinates showing
    run {
        val shot = drawGame(1080, 2340, 0, 140, 1080, classic, start, true, true, null, emptyList(), false)
        val t0 = System.nanoTime()
        val f = BoardFinder.find(shot.px, shot.w, shot.h)
        val ms = (System.nanoTime() - t0) / 1000000
        println("     (portrait 1080x2340 detect took " + ms + " ms)")
        check("portrait: board found", f != null, BoardFinder.lastProblem)
        if (f != null) {
            val why = eightByEight(shot, f.left, f.top, f.size)
            check("portrait: eight by eight squares", why == null, why ?: "")
            check(
                "portrait: rectangle covers the board",
                covers(f, 0, 140, 1080),
                "left " + f.left + " top " + f.top + " size " + f.size
            )
            check("portrait: found in under 1500 ms", ms < 1500, ms.toString() + " ms")
            val small = Vision.downscale(crop(shot, f.left, f.top, f.size), f.size)
            val lr = Vision.learn(small)
            check("portrait: starting position learned", lr.templates != null, lr.error ?: "")
            check("portrait: white at the bottom", lr.whiteBottom, "whiteBottom=" + lr.whiteBottom)
            if (lr.templates != null) {
                val pos = Vision.readPosition(small, lr.templates, true)
                check("portrait: start position read back", pos != null, Vision.lastProblem)
                if (pos != null) check("portrait: start position matches", Chess.placement(pos) == Chess.START, "read " + Chess.placement(pos))
            }
        }
    }

    // 2: a dark theme, a board with margins, a mid-game with a last move highlighted and move dots on empty squares
    run {
        val shot = drawGame(1080, 2400, 60, 210, 960, dark, start, true, true, null, emptyList(), true)
        val f = BoardFinder.find(shot.px, shot.w, shot.h)
        check("dark theme: board found", f != null, BoardFinder.lastProblem)
        if (f != null) {
            val why = eightByEight(shot, f.left, f.top, f.size)
            check("dark theme: eight by eight squares", why == null, why ?: "")
            check(
                "dark theme: rectangle covers the board",
                covers(f, 60, 210, 960),
                "left " + f.left + " top " + f.top + " size " + f.size
            )
            val small = Vision.downscale(crop(shot, f.left, f.top, f.size), f.size)
            val lr = Vision.learn(small)
            check("dark theme: starting position learned", lr.templates != null, lr.error ?: "")
            val tpl = lr.templates
            if (tpl != null) {
                check("dark theme: white at the bottom", lr.whiteBottom, "whiteBottom=" + lr.whiteBottom)
                val game = drawGame(1080, 2400, 60, 210, 960, dark, mid, true, true, "e2" to "e4", listOf("f3", "d4", "c5"), true)
                val pos = Vision.readPosition(Vision.downscale(crop(game, f.left, f.top, f.size), f.size), tpl, true)
                check("dark theme: mid game with highlight and dots reads", pos != null, Vision.lastProblem)
                if (pos != null) check("dark theme: mid game position matches", Chess.placement(pos) == "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R", "read " + Chess.placement(pos))
            }
        }
    }

    // 3: the player has black, so the board is drawn the other way up
    run {
        val shot = drawGame(1080, 2340, 0, 140, 1080, classic, start, false, true, null, emptyList(), false)
        val f = BoardFinder.find(shot.px, shot.w, shot.h)
        check("black at the bottom: board found", f != null, BoardFinder.lastProblem)
        if (f != null) {
            val small = Vision.downscale(crop(shot, f.left, f.top, f.size), f.size)
            val lr = Vision.learn(small)
            check("black at the bottom: starting position learned", lr.templates != null, lr.error ?: "")
            check("black at the bottom: white at the top", lr.templates != null && !lr.whiteBottom, "whiteBottom=" + lr.whiteBottom)
            if (lr.templates != null) {
                val pos = Vision.readPosition(small, lr.templates, false)
                check("black at the bottom: start position read back", pos != null, Vision.lastProblem)
                if (pos != null) check("black at the bottom: start position matches", Chess.placement(pos) == Chess.START, "read " + Chess.placement(pos))
            }
        }
    }

    // 4: the board is cut off by the bottom of the screen
    run {
        val shot = drawGame(1080, 2340, 0, 1665, 1080, classic, start, true, true, null, emptyList(), false)
        val f = BoardFinder.find(shot.px, shot.w, shot.h)
        check("cut off board: not reported as a board", f == null, "found left " + (f?.left) + " top " + (f?.top) + " size " + (f?.size))
        check("cut off board: reason given", BoardFinder.lastProblem.isNotEmpty(), "empty reason")
        check("cut off board: reason names the edge", BoardFinder.lastProblem.contains("off the edge"), BoardFinder.lastProblem)
        println("     (cut off reason: " + BoardFinder.lastProblem + ")")
    }

    // 5: a screen with no board on it at all
    run {
        val shot = flat(1080, 2340, rgb(32, 34, 38))
        for (i in 0 until 40) shot.box(40, 200 + i * 50, 1000, 216 + i * 50, rgb(70, 72, 76))
        val f = BoardFinder.find(shot.px, shot.w, shot.h)
        check("no board: not reported as a board", f == null, "found a board at " + (f?.left) + "," + (f?.top))
        check("no board: reason given", BoardFinder.lastProblem.isNotEmpty(), "empty reason")
        println("     (no board reason: " + BoardFinder.lastProblem + ")")
    }

    // 6: a picture too small to hold a board
    run {
        val shot = flat(40, 40, rgb(60, 60, 60))
        val f = BoardFinder.find(shot.px, shot.w, shot.h)
        check("tiny picture: not reported as a board", f == null, "found a board")
        check("tiny picture: reason given", BoardFinder.lastProblem.isNotEmpty(), "empty reason")
        println("     (tiny picture reason: " + BoardFinder.lastProblem + ")")
    }

    // 7: a board that only fills part of the screen, as a split screen or a small tablet window shows it
    run {
        val shot = drawGame(1080, 1920, 300, 600, 480, classic, start, true, true, null, emptyList(), false)
        val f = BoardFinder.find(shot.px, shot.w, shot.h)
        check("small board in the middle: board found", f != null, BoardFinder.lastProblem)
        if (f != null) {
            val why = eightByEight(shot, f.left, f.top, f.size)
            check("small board in the middle: eight by eight squares", why == null, why ?: "")
            check(
                "small board in the middle: rectangle covers the board",
                covers(f, 300, 600, 480),
                "left " + f.left + " top " + f.top + " size " + f.size
            )
            val small = Vision.downscale(crop(shot, f.left, f.top, f.size), f.size)
            val lr = Vision.learn(small)
            check("small board in the middle: start position learned", lr.templates != null, lr.error ?: "")
        }
    }

    // 8: what the app hands in when the capture went wrong
    run {
        val px = IntArray(100)
        val f = BoardFinder.find(px, 1080, 2340)
        check("short buffer: not reported as a board", f == null, "found a board")
        check("short buffer: reason given", BoardFinder.lastProblem.isNotEmpty(), "empty reason")
    }

    // 9: a blank board picture reaches Vision, which must say why
    run {
        val small = IntArray(Vision.B * Vision.B) { rgb(120, 120, 120) }
        val lr = Vision.learn(small)
        check("blank picture: learn refuses", lr.templates == null, "learned templates from a flat picture")
        check("blank picture: learn gives a reason", (lr.error ?: "").isNotEmpty(), "empty reason")
        val tpl = Templates(Array(6) { FloatArray(Vision.G * Vision.G) }, 128f, 40f)
        val pos = Vision.readPosition(small, tpl, true)
        check("blank picture: read refuses", pos == null, "read a position from a flat picture")
        check("blank picture: read gives a reason", Vision.lastProblem.isNotEmpty(), "empty reason")
        println("     (blank picture reason: " + Vision.lastProblem + ")")
    }

    // 10: the "is this crop still the board" check the service makes every frame
    run {
        val shot = drawGame(1080, 2340, 0, 140, 1080, classic, start, true, true, null, emptyList(), false)
        val px = crop(shot, 0, 140, 1080)
        val on = BoardFinder.score(px, 1080, 1080, 0, 0, 1080, 4, 0.12f)
        check("crop of the board scores above 0.5", on >= 0.5f, "scored " + on)
        val chat = flat(1080, 2340, rgb(32, 34, 38))
        for (i in 0 until 40) chat.box(40, 200 + i * 50, 1000, 216 + i * 50, rgb(70, 72, 76))
        val off = BoardFinder.score(crop(chat, 0, 140, 1080), 1080, 1080, 0, 0, 1080, 4, 0.12f)
        check("crop of a chat screen scores below 0.5", off < 0.5f, "scored " + off)
    }

    // 11 and on: chess.com's own board textures and piece artwork, at phone resolutions
    val fx = fixtures()
    if (fx == null) {
        println("     (tools/fixtures/chesscom is not here, so the chess.com screenshots were not checked)")
    } else {
        val fxStart = board(Chess.START)
        val fxMid = board("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R")
        val greenNeo = skin(fx, "green", "neo")
        val brownClassic = skin(fx, "brown", "classic")
        val blueAlpha = skin(fx, "blue", "alpha")
        val marbleNature = skin(fx, "marble", "nature")
        val lightTournament = skin(fx, "light", "tournament")
        val neonGlass = skin(fx, "neon", "glass")
        for (missing in listOf(
            "green/neo" to greenNeo, "brown/classic" to brownClassic, "blue/alpha" to blueAlpha,
            "marble/nature" to marbleNature, "light/tournament" to lightTournament, "neon/glass" to neonGlass
        )) if (missing.second == null) println("     (" + missing.first + " fixture missing, its cases are skipped)")

        // 11: the default look, a portrait phone, the board full width under the opponent's name
        if (greenNeo != null) {
            realCase(
                "chess.com green neo",
                realShot(1080, 2340, 0, 300, 1080, greenNeo, fxStart, true, true, null, emptyList(), null),
                0, 300, 1080, Chess.START, true
            )
        }

        // 12: another theme and another piece set, and a board whose width is not a multiple of eight, so a
        // square is 130.375 pixels rather than a whole number, which is what a padded layout gives you
        if (brownClassic != null) {
            realCase(
                "chess.com brown classic 1043px",
                realShot(1080, 2400, 18, 268, 1043, brownClassic, fxStart, true, true, null, emptyList(), null),
                18, 268, 1043, Chess.START, true
            )
        }

        // 13: the player has black, so the board is drawn the other way up
        if (blueAlpha != null) {
            realCase(
                "chess.com blue alpha flipped",
                realShot(1080, 2340, 40, 290, 1000, blueAlpha, fxStart, false, true, null, emptyList(), null),
                40, 290, 1000, Chess.START, false
            )
        }

        // 14: a mid game on the default skin: the last move highlighted, a piece picked up, the dots showing
        // where it can go, and the coordinates on top of it all
        if (greenNeo != null) {
            realCase(
                "chess.com green neo start",
                realShot(1080, 2340, 0, 300, 1080, greenNeo, fxStart, true, true, null, emptyList(), null),
                0, 300, 1080, Chess.START, true
            ) { _, tpl ->
                val mid = realShot(1080, 2340, 0, 300, 1080, greenNeo, fxMid, true, true, "e2" to "e4", listOf("e2", "d4", "f3"), "e2")
                val got = readFrom(mid, tpl, true)
                check("chess.com green neo midgame: read", got != null, Vision.lastProblem)
                if (got != null) check(
                    "chess.com green neo midgame: position matches",
                    Chess.placement(got) == "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R",
                    "read " + Chess.placement(got)
                )
                val end = realShot(1080, 2340, 0, 300, 1080, greenNeo, board("8/8/4k3/8/8/4K3/8/8"), true, true, null, emptyList(), null)
                val endRead = readFrom(end, tpl, true)
                check("chess.com green neo endgame: read", endRead != null, Vision.lastProblem)
                if (endRead != null) check("chess.com green neo endgame: position matches", Chess.placement(endRead) == "8/8/4k3/8/8/4K3/8/8", "read " + Chess.placement(endRead))
            }
        }

        // 15: a photographic board texture, which has no two flat colours anywhere on it
        if (marbleNature != null) {
            realCase(
                "chess.com marble nature",
                realShot(1080, 2340, 60, 300, 960, marbleNature, fxStart, true, true, null, emptyList(), null),
                60, 300, 960, Chess.START, true
            )
        }

        // 16: a low contrast theme, where the two square colours are a few levels apart. This has to give eight
        // by eight squares or a sentence saying why not, and never a bare empty answer.
        if (lightTournament != null) {
            val shot = realShot(1080, 2340, 0, 300, 1080, lightTournament, fxStart, true, true, null, emptyList(), null)
            val f = BoardFinder.find(shot.px, shot.w, shot.h)
            if (f == null) {
                check("chess.com light theme: names its reason", BoardFinder.lastProblem.length > 20, "reason: " + BoardFinder.lastProblem)
                println("     (light theme: " + BoardFinder.lastProblem + ")")
            } else {
                check("chess.com light theme: eight by eight squares", eightByEight(shot, f.left, f.top, f.size) == null, eightByEight(shot, f.left, f.top, f.size) ?: "")
                val tpl = Vision.learn(Vision.downscale(crop(shot, f.left, f.top, f.size), f.size)).templates
                check("chess.com light theme: starting position learned", tpl != null, Vision.lastProblem)
                if (tpl != null) {
                    val got = Vision.readPosition(Vision.downscale(crop(shot, f.left, f.top, f.size), f.size), tpl, true)
                    check("chess.com light theme: position read", got != null, Vision.lastProblem)
                    if (got != null) check("chess.com light theme: position matches", Chess.placement(got) == Chess.START, "read " + Chess.placement(got))
                }
            }
        }

        // 17: a bright theme with a see-through piece set, the worst case for telling a piece from a square
        if (neonGlass != null) {
            val shot = realShot(1080, 2340, 0, 300, 1080, neonGlass, fxStart, true, true, null, emptyList(), null)
            val f = BoardFinder.find(shot.px, shot.w, shot.h)
            if (f == null) {
                check("chess.com neon theme: names its reason", BoardFinder.lastProblem.length > 20, "reason: " + BoardFinder.lastProblem)
                println("     (neon theme: " + BoardFinder.lastProblem + ")")
            } else {
                check("chess.com neon theme: eight by eight squares", eightByEight(shot, f.left, f.top, f.size) == null, eightByEight(shot, f.left, f.top, f.size) ?: "")
                val lr = Vision.learn(Vision.downscale(crop(shot, f.left, f.top, f.size), f.size))
                check("chess.com neon theme: starting position learned", lr.templates != null, lr.error ?: Vision.lastProblem)
                if (lr.templates != null) {
                    val got = Vision.readPosition(Vision.downscale(crop(shot, f.left, f.top, f.size), f.size), lr.templates!!, true)
                    check("chess.com neon theme: position read", got != null, Vision.lastProblem)
                    if (got != null) check("chess.com neon theme: position matches", Chess.placement(got) == Chess.START, "read " + Chess.placement(got))
                }
            }
        }

        // 18: the board hanging over the bottom of a tall phone, which is the fault the panel is meant to name
        if (greenNeo != null) {
            val shot = realShot(1080, 2340, 0, 1500, 1080, greenNeo, fxStart, true, false, null, emptyList(), null)
            val f = BoardFinder.find(shot.px, shot.w, shot.h)
            check("chess.com board off the bottom: not reported as a board", f == null, "found left " + (f?.left) + " top " + (f?.top) + " size " + (f?.size))
            check("chess.com board off the bottom: reason names the edge", BoardFinder.lastProblem.contains("off the edge"), BoardFinder.lastProblem)
            println("     (chess.com off the bottom: " + BoardFinder.lastProblem + ")")
        }
    }

    println("")
    if (failures == 0) println("all checks passed") else println(failures.toString() + " check(s) failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
