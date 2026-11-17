// Checks the strength table and the move picker without the Android SDK.
//
// Both of the classes under test are plain Kotlin, but Humanizer.chooseMove takes the engine's
// result types, which live in StockfishEngine.kt next to an Android-only class, so this file
// carries their shape and the compile set is just:
//
//   kotlinc Levels.kt Humanizer.kt LevelCheck.kt -include-runtime -d levelcheck.jar
//   java -jar levelcheck.jar
//
// With --print-go it prints, one per line, `setting<TAB>command`: the strength setting the app
// would push into Stockfish for that step and the exact `go` line it would then send.
// tools/go-limit-check.py runs those lines against a real engine.
//
// It is not part of the APK build (tools/ is outside app/src), and it exists so a change to a
// level's numbers can be checked before anyone plays a game with it.
package com.example.chessreviewer

import kotlin.random.Random

/** the shape of the engine's line, same as StockfishEngine.kt */
class EngineLine(val move: String, val cp: Int?, val mate: Int?) {
    fun score(): Int = when {
        mate != null && mate > 0 -> 100000 - mate * 100
        mate != null -> -100000 - mate * 100
        cp != null -> cp
        else -> 0
    }
}

class EngineResult(
    val cp: Int?,
    val mate: Int?,
    val best: String,
    val lines: List<EngineLine> = emptyList()
)

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "ok   " else "FAIL ") + name + (if (ok || detail.isEmpty()) "" else "  -> " + detail))
    if (!ok) failures++
}

private fun draw(r: EngineResult, elo: Int?, draws: Int = 4000, seed: Int = 7): List<EngineLine> {
    val rnd = Random(seed)
    return (0 until draws).map { Humanizer.chooseMove(r, elo, true, rnd) }
}

/** the setting the app's applyStrength would push for this step */
private fun setting(s: Levels.Step?): String = when {
    s == null || s.isFull -> "full"
    s.usesSkill -> "skill:" + s.skill
    else -> "elo:" + s.elo
}

fun main(args: Array<String>) {
    if (args.contains("--print-go")) {
        for (t in intArrayOf(400, 900, 30_000, 0, -5)) {
            println(setting(null) + "\t" + Levels.goCommand(t, 0))
        }
        for (s in Levels.steps) {
            println(setting(s) + "\t" + Levels.goCommand(600, s.nodes))
        }
        return
    }
    // ------------------------------------------------ the table
    val values = Levels.storedValues.toList()
    check("full strength is last and stored as 0", values.last() == 0)
    check("steps are ordered weakest first", values.zipWithNext().all { (a, b) -> a < b || b == 0 })
    check("every step under the floor is driven by Skill Level", Levels.steps
        .filter { it.elo in 1 until Levels.FLOOR_ELO }
        .all { it.usesSkill && it.nodes > 0 })
    check("every step at or above the floor is driven by UCI_Elo", Levels.steps
        .filter { it.elo >= Levels.FLOOR_ELO }
        .all { !it.usesSkill && it.nodes == 0 })
    check("slack never grows as the level rises",
        Levels.steps.filter { it.elo >= 1 }.zipWithNext().all { (lower, higher) ->
            higher.slackCp <= lower.slackCp
        })
    check("the weakest step gives up more than the strongest",
        Levels.steps.first().slackCp > Levels.steps.last().slackCp)

    // ------------------------------------------------ resolving a stored rating
    check("an exact step resolves to itself", Levels.stepFor(1900)?.elo == 1900)
    check("a rating between steps resolves downwards", Levels.stepFor(1500)?.elo == 1320)
    check("zero is full strength", Levels.stepFor(0) == null)
    check("null is full strength", Levels.stepFor(null) == null)
    check("below the weakest step resolves to the weakest", Levels.stepFor(120)?.elo == 400)

    // ------------------------------------------------ the picker
    val lines = listOf(
        EngineLine("e2e4", 40, null),
        EngineLine("d2d4", 20, null),
        EngineLine("g1f3", 0, null),
        EngineLine("a2a3", -120, null),
        EngineLine("h2h3", -300, null)
    )
    val r = EngineResult(40, null, "e2e4", lines)

    val strong = draw(r, 2800)
    val weak = draw(r, 400)
    val strongWorst = strong.maxOf { it.score() }.let { 40 - it }
    val weakWorst = weak.maxOf { it.score() }.let { 40 - it }
    check("2800 never goes past its budget", strongWorst <= Levels.stepFor(2800)!!.slackCp,
        "worst $strongWorst")
    check("400 never goes past its budget", weakWorst <= Levels.stepFor(400)!!.slackCp,
        "worst $weakWorst")
    check("400 uses more of the list than 2800",
        weak.map { it.move }.toSet().size > strong.map { it.move }.toSet().size,
        weak.map { it.move }.toSet().toString())
    check("the best move is still the most common choice at 400",
        weak.groupingBy { it.move }.eachCount().maxByOrNull { it.value }!!.key == "e2e4")
    check("a mate in one is always played", run {
        val mate = EngineResult(30000, 1, "h5f7", listOf(
            EngineLine("h5f7", null, 1), EngineLine("a2a3", -300, null)))
        draw(mate, 400, 500).all { it.move == "h5f7" }
    })
    check("an unscored engine pick is trusted over the scored lines", run {
        val own = EngineResult(null, null, "b1c3", listOf(
            EngineLine("b1c3", null, null), EngineLine("a2a3", -300, null)))
        draw(own, 400, 500).all { it.move == "b1c3" }
    })
    check("human pace off gives the engine's first line", run {
        val rnd = Random(3)
        (0 until 200).all {
            Humanizer.chooseMove(r, 400, false, rnd).move == "e2e4"
        }
    })
    check("a single line is played as it is", run {
        val one = EngineResult(30, null, "e2e4", listOf(EngineLine("e2e4", 30, null)))
        draw(one, 400, 200).all { it.move == "e2e4" }
    })
    check("the engine's own weaker pick is still the move most of the time", run {
        // at a Skill Level step the engine's own pick is not the best line, and it is what the
        // step's strength comes from: it must survive the picker
        val own = EngineResult(40, null, "a2a3", listOf(
            EngineLine("e2e4", 40, null), EngineLine("d2d4", 20, null),
            EngineLine("g1f3", 0, null), EngineLine("h2h3", -300, null)))
        val picked = draw(own, 400)
        val counts = picked.groupingBy { it.move }.eachCount()
        counts.maxByOrNull { it.value }!!.key == "a2a3" && picked.size > 0
    })
    check("at a Skill Level step the alternatives stay inside the budget", run {
        val own = EngineResult(40, null, "a2a3", lines)
        draw(own, 400).all { 40 - it.score() <= Levels.stepFor(400)!!.slackCp }
    })

    // ------------------------------------------------ the search command
    check("every search carries a depth ceiling and a move time", run {
        listOf(-5, 0, 400, 900, 30_000, 99_000).all { asked ->
            val cmd = Levels.goCommand(asked, 0)
            val time = Regex("movetime (\\d+)").find(cmd)?.groupValues?.get(1)?.toIntOrNull()
            cmd.startsWith("go ") && cmd.contains("depth ${Levels.SEARCH_DEPTH_CAP}") &&
                time != null && time >= Levels.MIN_MOVE_TIME_MS && time <= Levels.MAX_MOVE_TIME_MS
        }
    })
    check("a zero or negative move time is never sent", run {
        listOf(-5, 0).all {
            Regex("movetime (\\d+)").find(Levels.goCommand(it, 0))!!.groupValues[1].toInt() == Levels.MIN_MOVE_TIME_MS
        }
    })
    check("no command is ever a bare go", run {
        listOf(-5, 0, 400, 900, 30_000).none { Levels.goCommand(it, 0).trim() == "go" }
    })
    check("the node budget is sent only when there is one", run {
        !Levels.goCommand(400, 0).contains("nodes") && Levels.goCommand(400, 8000).contains("nodes 8000")
    })
    check("the Skill Level steps all send their own node budget", run {
        Levels.steps.filter { it.usesSkill }.all { Levels.goCommand(700, it.nodes).contains("nodes ${it.nodes}") }
    })
    check("every step, full strength included, sends a bounded search", run {
        // the composed line each step actually produces, node budget and all: this is the one
        // string the engine sees, so it is where "no unlimited go" has to hold
        val forms = listOf<Levels.Step?>(null) + Levels.steps.toList()
        forms.all { s ->
            val cmd = Levels.goCommand(600, s?.nodes ?: 0)
            cmd.matches(Regex("go depth ${Levels.SEARCH_DEPTH_CAP}( nodes \\d+)? movetime \\d+")) &&
                Regex("movetime (\\d+)").find(cmd)!!.groupValues[1].toInt() in
                Levels.MIN_MOVE_TIME_MS..Levels.MAX_MOVE_TIME_MS
        }
    })
    check("full strength is a depth and a move time, never a bare go", run {
        Levels.goCommand(400, 0) != "go" && Levels.goCommand(400, 0).startsWith("go depth ")
    })

    // ------------------------------------------------ an unanswered search
    check("an engine pick that is not a move falls back to a scored line", run {
        // bestmove (none) on a board that was misread: the panel must still be given a move
        val none = EngineResult(null, null, "(none)", listOf(
            EngineLine("e2e4", 30, null), EngineLine("d2d4", 10, null)))
        draw(none, 400, 400).all { Humanizer.isMove(it.move) }
    })
    check("an empty engine pick falls back to a scored line", run {
        val empty = EngineResult(null, null, "", listOf(
            EngineLine("e2e4", 30, null), EngineLine("d2d4", 10, null)))
        draw(empty, 400, 400).all { it.move == "e2e4" || it.move == "d2d4" }
    })
    check("the fallback line is the one it scored best", run {
        val empty = EngineResult(null, null, "", listOf(
            EngineLine("e2e4", 30, null), EngineLine("a2a3", -900, null)))
        draw(empty, 400, 300).all { it.move == "e2e4" }
    })
    check("human pace off answers with the fallback too", run {
        val empty = EngineResult(null, null, "(none)", listOf(EngineLine("e2e4", 30, null)))
        (0 until 100).all { Humanizer.chooseMove(empty, 0, false, Random(it)).move == "e2e4" }
    })
    check("no lines and no pick still comes back, and says so", run {
        val dead = EngineResult(null, null, "(none)", emptyList())
        Humanizer.chooseMove(dead, 400, true, Random(1)).move == "(none)"
    })
    check("a mate score is played as the engine's pick, never diced against", run {
        // being mated in three: the top line is a mate, so no centipawn budget can apply to it
        val losing = EngineResult(null, -3, "e2e4", listOf(
            EngineLine("e2e4", null, -3), EngineLine("d2d4", -400, null),
            EngineLine("a2a3", -900, null)))
        draw(losing, 400, 500).all { it.move == "e2e4" }
    })
    check("a big centipawn evaluation still draws from the budget", run {
        val plain = EngineResult(950, null, "e2e4", listOf(
            EngineLine("e2e4", 950, null), EngineLine("d2d4", 900, null)))
        draw(plain, 400, 500).all { it.move == "e2e4" || it.move == "d2d4" }
    })
    check("isMove rejects what the engine says when it has nothing", run {
        !Humanizer.isMove("") && !Humanizer.isMove("(none)") && Humanizer.isMove("e2e4") &&
            Humanizer.isMove("e7e8q")
    })

    // ------------------------------------------------ the timing window
    check("human pace off means play it now", run {
        val rnd = Random(5)
        val w = Humanizer.thinkWindowMs(r, CharArray(64) { '.' }, false, rnd)
        w.first == 0L && w.last == 0L
    })
    check("a crowded board gets a longer window than an empty one", run {
        val rnd = Random(9)
        val crowded = Humanizer.thinkWindowMs(r, CharArray(64) { 'p' }, true, rnd).last
        val sparse = Humanizer.thinkWindowMs(r, CharArray(4) { 'p' } + CharArray(60) { '.' }, true, rnd).last
        crowded > sparse
    })

    println(if (failures == 0) "\nall checks passed" else "\n$failures check(s) failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
