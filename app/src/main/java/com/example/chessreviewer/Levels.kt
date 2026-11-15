package com.example.chessreviewer

/**
 * The strength steps the app offers, and how each one is handed to Stockfish.
 *
 * Stockfish has two strength settings and they are not independent: `UCI_LimitStrength` plus
 * `UCI_Elo` is the one that maps to a rating, but that option has a floor of [FLOOR_ELO] (1320 in
 * current builds) and cannot express anything under it. `Skill Level`, 0..20, is what `UCI_Elo` is
 * translated into inside the engine, so Skill Level 0 on its own is about the same strength as that
 * floor rather than weaker.
 *
 * A step under the floor therefore sets Skill Level *and* caps the search depth, and the move
 * picker in [Humanizer] is allowed to give up more material. Between them that is what reaches a
 * beginner's strength, where Skill Level alone cannot.
 */
object Levels {

    /** the lowest UCI_Elo current Stockfish builds accept; under this, Skill Level is the lever */
    const val FLOOR_ELO = 1320

    /**
     * One choice on the strength spinner.
     *
     * [elo] is the number shown and the one stored in the preferences, with 0 meaning full
     * strength, so a preference written by an older build still means what it did.
     * [skill] is the `Skill Level` to drive the engine with, or null when the step is expressed as
     * `UCI_Elo` instead. [depth] is a search depth cap in plies, 0 for none.
     */
    class Step(val elo: Int, val name: String, val skill: Int?, val depth: Int) {
        val label: String get() = if (elo <= 0) name else "$name - $elo"

        val isFull: Boolean get() = elo <= 0

        /** centipawns a player of this level can be expected to give up on one move */
        val slackCp: Int
            get() = when {
                elo <= 0 -> 6
                elo >= FLOOR_ELO -> (6 + (3190 - elo) / 24).coerceIn(6, 90)
                elo >= 1000 -> 130
                elo >= 800 -> 180
                elo >= 600 -> 240
                else -> 320
            }
    }

    /** weakest first, full strength last: this is the order the Elo button on the panel cycles */
    val steps: Array<Step> = arrayOf(
        Step(400, "Absolute beginner", 0, 4),
        Step(600, "Beginner", 1, 5),
        Step(800, "Novice", 2, 6),
        Step(1000, "Improver", 4, 7),
        Step(1320, "Casual", null, 0),
        Step(1600, "Club", null, 0),
        Step(1900, "Strong club", null, 0),
        Step(2200, "Expert", null, 0),
        Step(2500, "Master", null, 0),
        Step(2800, "Super GM", null, 0),
        Step(0, "Full strength", null, 0)
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
}
