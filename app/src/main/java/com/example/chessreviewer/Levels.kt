package com.example.chessreviewer

/**
 * The strength steps the app offers, and how each one is handed to Stockfish.
 *
 * Stockfish has two strength settings. `UCI_LimitStrength` plus `UCI_Elo` is the one that maps to
 * a rating, and its floor is [FLOOR_ELO] (1320 in current builds). `Skill Level`, 0..20, is the
 * option that floor is translated into inside the engine, and it is the only lever that reaches
 * below it.
 *
 * What it does *not* need is help, and that is what went wrong before: the low steps drove Skill
 * Level *and* capped the search depth *and* let the move picker give up another three pawns. Three
 * weakenings multiplied, and at depth four the engine's own scores were noise, so the move it picked
 * was close to a random legal move. Measured against a 1320-rated opponent that step lost every game
 * in the harness; the same Skill Level with a normal search and one deliberate weakening wins half
 * of them.
 *
 * So each step now weakens once, in a way that keeps the scores trustworthy:
 *  - above the floor, `UCI_Elo`, which the Stockfish team calibrated against real ratings;
 *  - below it, `Skill Level` with a *node* budget instead of a depth cap. A node budget keeps the
 *    full width and the quiescence search, so the engine still sees a capture before it plays one,
 *    and only cuts how much of the tree it gets through;
 *  - and the move to show is drawn from the engine's top five lines inside [Step.slackCp], which is
 *    a real, scored weakening: usually the best move, sometimes a plausible worse one, never a move
 *    whose score was never computed.
 */
object Levels {

    /** the lowest UCI_Elo current Stockfish builds accept; under this, Skill Level is the lever */
    const val FLOOR_ELO = 1320

    /** centipawns a player of [elo] can be expected to give up on a move, above the floor */
    private fun eloSlack(elo: Int): Int =
        if (elo <= 0) 6
        else (6 + (2800 - elo).coerceAtLeast(0) / 55).coerceIn(6, 40)

    /**
     * One choice on the strength spinner.
     *
     * [elo] is the number shown and the one stored in the preferences, with 0 meaning full
     * strength, so a preference written by an earlier build still means what it did.
     * [skill] is the `Skill Level` to drive the engine with, or null when the step is expressed as
     * `UCI_Elo` instead. [nodes] is the node budget for a Skill Level step, 0 for none.
     * [slackCp] is how far below the engine's best move the picker may go on this step.
     *
     * The numbers under the floor were set by playing the settings against a 1320-rated opponent
     * (tools/level-games.py): each step is the widest budget that still scores clearly worse than
     * the step above it, without any of them playing moves the engine never scored.
     */
    class Step(
        val elo: Int,
        val name: String,
        val skill: Int?,
        val nodes: Int,
        val slackCp: Int,
        val mistakeChance: Float
    ) {
        val label: String get() = if (elo <= 0) name else "$name - $elo"

        val isFull: Boolean get() = elo <= 0

        /** true when this step is driven by Skill Level and a node budget instead of UCI_Elo */
        val usesSkill: Boolean get() = skill != null
    }

    /** weakest first, full strength last: this is the order the Elo button on the panel cycles */
    val steps: Array<Step> = arrayOf(
        Step(400, "Absolute beginner", 0, 8000, 400, 0.45f),
        Step(600, "Beginner", 0, 10000, 300, 0.35f),
        Step(800, "Novice", 1, 12000, 200, 0.25f),
        Step(1000, "Improver", 2, 16000, 120, 0.15f),
        Step(1320, "Casual", null, 0, eloSlack(1320), 1f),
        Step(1600, "Club", null, 0, eloSlack(1600), 1f),
        Step(1900, "Strong club", null, 0, eloSlack(1900), 1f),
        Step(2200, "Expert", null, 0, eloSlack(2200), 1f),
        Step(2500, "Master", null, 0, eloSlack(2500), 1f),
        Step(2800, "Super GM", null, 0, eloSlack(2800), 1f),
        Step(0, "Full strength", null, 0, eloSlack(0), 1f)
    )

    /** the values stored in the preferences, in the same order as [steps] */
    val storedValues: IntArray = IntArray(steps.size) { steps[it].elo }

    /** the spinner's labels, in the same order as [steps] */
    val labels: Array<String> = Array(steps.size) { steps[it].label }

    /**
     * The step for a stored rating: an exact match, else the nearest step at or under it, else the
     * weakest one. null means full strength, which is also the stored value 0.
     */
    fun stepFor(elo: Int?): Step? {
        if (elo == null || elo <= 0) return null
        for (s in steps) if (s.elo == elo) return s
        var best: Step? = null
        for (s in steps) {
            if (s.elo in 1..elo) {
                val b = best
                if (b == null || s.elo > b.elo) best = s
            }
        }
        return best ?: steps.first()
    }

    /**
     * How many candidate moves to ask for: five at every step, because the picker draws from them
     * at every step. Raising it above five is not free at the low steps, where a wider MultiPV also
     * widens the range Skill Level is allowed to pick from.
     */
    fun multiPvFor(elo: Int?): Int = MULTI_PV

    const val MULTI_PV = 5

    // ------------------------------------------------------------ how one search is run

    /** the shortest search worth asking for: `movetime 0` is the same to the engine as no limit */
    const val MIN_MOVE_TIME_MS = 50

    /** the longest search the panel will wait for, whatever a caller passes */
    const val MAX_MOVE_TIME_MS = 30_000

    /** depth ceiling sent with every search, so even a wedged clock cannot run one forever */
    const val SEARCH_DEPTH_CAP = 24

    /** how long past its own movetime a search may run before it is stopped and given up on */
    const val SEARCH_GRACE_MS = 3_000

    /** a caller's move time, brought into the range the engine and the panel can both live with */
    fun thinkTimeMs(asked: Int): Int = asked.coerceIn(MIN_MOVE_TIME_MS, MAX_MOVE_TIME_MS)

    /**
     * The `go` line for one search.
     *
     * Every search carries a depth ceiling and a move time, and none is ever sent as a bare `go`.
     * The engine's parse_limits only sets a limit when the line names one, so a `go` with no token,
     * or with `movetime 0`, leaves every limit at zero and the search runs until something else
     * stops it: that is how the panel can sit on "Thinking…" with no move and never come back.
     * [nodes] is the node budget the Skill Level steps use; it is added only when positive, so a
     * step at or above the rating floor runs on time alone.
     */
    fun goCommand(moveTimeMs: Int, nodes: Int): String {
        val time = thinkTimeMs(moveTimeMs)
        return if (nodes > 0) {
            "go depth $SEARCH_DEPTH_CAP nodes $nodes movetime $time"
        } else {
            "go depth $SEARCH_DEPTH_CAP movetime $time"
        }
    }
}
