package com.example.chessreviewer

import kotlin.math.exp
import kotlin.random.Random

/**
 * Turns the engine's ranked candidates into something a person could plausibly have played,
 * and a plausible amount of time to have spent on it.
 *
 * Two things give an assisted game away to a review of the moves: every move being the engine's
 * first choice, and every move arriving after the same short pause. Both are avoidable without
 * making the play bad. Above Stockfish's own rating floor the move is drawn from the top few lines
 * that sit inside the centipawn budget of the level picked. On the steps driven by Skill Level the
 * engine has already made that choice itself, so this shows its move as it is: a second random pick
 * on top of it was what made those steps play far below their label.
 */
object Humanizer {

    /**
     * Centipawns a player of this level can be expected to give up on one move. The table lives in
     * [Levels]: it narrows all the way down to full strength, and under Stockfish's own UCI_Elo
     * floor it is the main lever, because Skill Level 0 is already as strong as that floor.
     */
    fun slackCp(elo: Int?): Int = Levels.stepFor(elo)?.slackCp ?: 6

    /**
     * Picks the move to show.
     *
     * On a step at or above Stockfish's rating floor the engine itself is as strong as its label
     * says, so the choice is drawn from the top few lines inside [slackCp] of the best, weighted
     * towards the best: the play stays strong and is not identically the engine's first choice
     * every move.
     *
     * On a Skill Level step the engine has already chosen a weaker move than the best one, and that
     * choice is the weakening: it is what is shown, most of the time. A share of the moves, set by
     * [Levels.Step.mistakeChance], is drawn from a scored alternative inside the budget instead,
     * which is the deliberate mistake that takes the step below the floor. Drawing purely from the
     * scored lines there would throw the engine's own weakened pick away and, with a wide budget,
     * amount to picking a near-random move; drawing never from them would leave the step playing
     * at the floor whatever its label.
     */
    fun chooseMove(r: EngineResult, elo: Int?, human: Boolean, rnd: Random): EngineLine {
        // the engine's own pick first, but never a pick that is not a move: when it reports
        // "(none)", or nothing at all, and yet scored candidate lines, play the best of those
        // instead of handing back something the panel cannot show
        val scored = r.lines.filter { isMove(it.move) }
        val own = if (isMove(r.best)) {
            EngineLine(r.best, r.cp, r.mate)
        } else {
            scored.firstOrNull() ?: EngineLine(r.best, r.cp, r.mate)
        }
        val lines = if (scored.isEmpty()) listOf(own) else scored
        val top = lines.first()
        if (!human) return own
        // a mate on the board gets played by anyone
        val mateIn = own.mate ?: top.mate
        if (mateIn != null && mateIn in 1..2) return own

        val step = Levels.stepFor(elo)
        val budget = step?.slackCp ?: 6
        // Stockfish's own pick is where its strength setting lives: at UCI_Elo it is a move the
        // engine has already weakened to that rating, and at a Skill Level step it is the weaker
        // move that step is for. Replacing it with the top scored line would play above the label,
        // so it is the anchor here and only a bounded share of the moves departs from it.
        if (step != null && step.usesSkill && rnd.nextFloat() >= step.mistakeChance) return own

        // nothing to choose between: the engine's own move is the answer, not an empty one
        if (lines.size < 2) return own
        // the engine's own pick scored nothing in this list, so trust it over the picked lines
        if (top.cp == null && top.mate == null) return own

        val best = top.score()
        // a mate score is not a centipawn evaluation: weighing alternatives against it would call
        // every one of them either free or infinitely expensive, so play the engine's own move
        if (best >= MATE_SCORE || best <= -MATE_SCORE) return own
        val pool = lines.filter { it.move != own.move && best - it.score() <= budget && it.cp != null }
        if (pool.isEmpty()) return own

        // a quarter of the budget is the temperature, so the engine's best stays the common choice
        // and a move at the edge of the budget is the occasional mistake this level actually makes
        val temperature = (budget / 4f).coerceAtLeast(4f).toDouble()
        val weights = pool.map { exp(-(best - it.score()) / temperature) }
        val total = weights.sum()
        if (total <= 0.0) return own
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

    /**
     * Whether a string from the engine is a move at all. `bestmove (none)` is what it says on a
     * position with no legal move, and an empty string is what it says when the line is cut off;
     * neither can be shown as a move or sent to the board.
     */
    fun isMove(move: String): Boolean = move.length >= 4 && move[0] in 'a'..'h'

    /** above this, a score is a mate count rather than a centipawn evaluation (see [EngineLine.score]) */
    private const val MATE_SCORE = 50_000
}
