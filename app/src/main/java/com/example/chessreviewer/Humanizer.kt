package com.example.chessreviewer

import kotlin.math.exp
import kotlin.random.Random

/**
 * Turns the engine's ranked candidates into something a person could plausibly have played,
 * and a plausible amount of time to have spent on it.
 *
 * Two things give an assisted game away to a review of the moves: every move being the engine's
 * first choice, and every move arriving after the same short pause. Both are avoidable without
 * making the play bad. The move is drawn from the top few lines that sit inside the centipawn
 * budget of the level picked, and the delay follows the shape of a human think (long in open
 * middlegames, short on forced recaptures and mate in one), with the exact second left open.
 */
object Humanizer {

    /** centipawns a player of this level can be expected to give up on one move */
    fun slackCp(elo: Int?): Int {
        val e = elo ?: 3190
        return (6 + (3190 - e) / 24).coerceIn(6, 90)
    }

    /**
     * Picks the move to show. At full strength with human pace off this is simply the engine's
     * first choice; otherwise it is one of the moves within [slackCp] of it, weighted by how
     * little it gives up, so the strong move is common and the loose one is occasional.
     */
    fun chooseMove(r: EngineResult, elo: Int?, human: Boolean, rnd: Random): EngineLine {
        val lines = r.lines.ifEmpty { listOf(EngineLine(r.best, r.cp, r.mate)) }
        val top = lines.first()
        if (!human || lines.size < 2) return top
        // a mate on the board gets played by anyone
        val mateIn = top.mate
        if (mateIn != null && mateIn > 0) return top

        val best = top.score()
        val budget = slackCp(elo)
        val pool = lines.filter { best - it.score() <= budget }
        if (pool.size < 2) return top

        val temperature = (budget / 2).coerceAtLeast(4).toDouble()
        val weights = pool.map { exp(-(best - it.score()) / temperature) }
        val total = weights.sum()
        if (total <= 0.0) return top
        var roll = rnd.nextDouble() * total
        for (i in pool.indices) {
            roll -= weights[i]
            if (roll <= 0.0) return pool[i]
        }
        return pool.last()
    }

    /**
     * A believable window, in milliseconds, for how long someone would spend before playing.
     * A zero-length window means "play it whenever you like" (human pace off).
     */
    fun thinkWindowMs(r: EngineResult, pos: CharArray, human: Boolean, rnd: Random): LongRange {
        if (!human) return 0L..0L

        var pieces = 0
        for (c in pos) if (c != '.') pieces++

        // boards crowded with pieces read as hard, sparse endgames as quiet
        var base = when {
            pieces >= 26 -> 6000L
            pieces >= 18 -> 4600L
            pieces >= 10 -> 3200L
            else -> 2300L
        }

        val top = r.lines.firstOrNull()
        val mateIn = top?.mate
        val obvious = (mateIn != null && mateIn in 1..2) || r.lines.size <= 1
        if (obvious) base = 1200L
        else if (top != null && isCapture(top.move, pos)) base = (base * 0.68f).toLong()

        // a little noise per move, so the same kind of position does not give the same pause
        base = (base * (0.85f + rnd.nextFloat() * 0.3f)).toLong()
        val spread = (base * 0.45f).toLong().coerceAtLeast(350L)
        val low = (base - spread).coerceIn(900L, 24000L)
        val high = (base + spread).coerceIn(low, 30000L)
        return low..high
    }

    fun isCapture(move: String, pos: CharArray): Boolean {
        if (move.length < 4) return false
        val file = move[3] - 'a'
        val rank = move[2] - '1'
        if (file !in 0..7 || rank !in 0..7) return false
        return pos[rank * 8 + file] != '.'
    }
}
