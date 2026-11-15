package com.example.chessreviewer

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** One candidate move from the engine's last search, scored from the side to move's point of view. */
class EngineLine(val move: String, val cp: Int?, val mate: Int?) {
    /** one comparable number, in centipawns, with mates counted as (almost) infinite */
    fun score(): Int = when {
        mate != null && mate > 0 -> 100000 - mate * 100
        mate != null -> -100000 - mate * 100
        cp != null -> cp
        else -> 0
    }
}

/**
 * cp / mate are from the point of view of the side to move (UCI convention).
 * [lines] holds the ranked candidates the engine reported, best first; with MultiPV 1 it is
 * empty and the caller should just play [best].
 */
class EngineResult(
    val cp: Int?,
    val mate: Int?,
    val best: String,
    val lines: List<EngineLine> = emptyList()
)

class StockfishEngine(private val context: Context) {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    /** null = full strength; otherwise the rating the engine is limited to */
    @Volatile var elo: Int? = null

    /** how many candidate moves to ask for; more than one lets the move choice be varied */
    @Volatile var multiPv: Int = 1

    /** node budget for the steps driven by Skill Level, 0 for no limit */
    @Volatile private var nodesCap = 0

    // what this particular Stockfish build says it accepts, read from its "uci" reply
    private var eloMin = 1320
    private var eloMax = 3190
    private var hasLimitStrength = false
    private var hasSkillLevel = false

    private fun binary() = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")

    fun available(): Boolean = binary().exists()

    @Synchronized
    fun start(): Boolean {
        stop()
        val bin = binary()
        if (!bin.exists()) return false
        return try {
            val p = ProcessBuilder(bin.absolutePath).redirectErrorStream(true).start()
            process = p
            reader = BufferedReader(InputStreamReader(p.inputStream))
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            send("uci")
            if (!waitFor("uciok", readOptions = true)) return false
            send("setoption name Threads value 2")
            send("setoption name Hash value 64")
            applyStrength()
            send("isready")
            waitFor("readyok")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sets how strong the engine plays and how many candidate moves it reports. Safe to call
     * while it is running: the new limits apply to the next search.
     */
    @Synchronized
    fun configure(elo: Int?, multiPv: Int) {
        this.elo = elo
        this.multiPv = multiPv
        if (process?.isAlive == true) {
            applyStrength()
            send("isready")
            waitFor("readyok")
        }
    }

    /**
     * Pushes the current strength setting into the engine.
     *
     * `UCI_Elo` and `Skill Level` are the same setting underneath: the engine turns an Elo into a
     * Skill Level, and `UCI_Elo`'s own floor is [eloMin] (1320 in current builds). A rating under
     * that floor can only be sent as `Skill Level` itself, with a node budget that shortens the
     * search without blinding it.
     */
    private fun applyStrength() {
        val step = Levels.stepFor(elo)
        if (step == null) {
            if (hasLimitStrength) send("setoption name UCI_LimitStrength value false")
            if (hasSkillLevel) send("setoption name Skill Level value 20")
            nodesCap = 0
        } else if (step.skill != null && hasSkillLevel) {
            // UCI_LimitStrength has to be off first, or the engine recomputes Skill Level
            // from UCI_Elo and undoes the value set here
            if (hasLimitStrength) send("setoption name UCI_LimitStrength value false")
            send("setoption name Skill Level value ${step.skill.coerceIn(0, 20)}")
            nodesCap = step.nodes
        } else {
            // back to the neutral skill, so a build that recomputes it from UCI_Elo starts clean
            if (hasSkillLevel) send("setoption name Skill Level value 20")
            if (hasLimitStrength) {
                send("setoption name UCI_Elo value ${step.elo.coerceIn(eloMin, eloMax)}")
                send("setoption name UCI_LimitStrength value true")
            } else if (hasSkillLevel) {
                // no UCI_Elo in this build: the Skill Level that matches the rating asked for,
                // spread across the range the engine advertises
                val span = ((eloMax - eloMin) / 20).coerceAtLeast(1)
                send("setoption name Skill Level value ${((step.elo - eloMin) / span).coerceIn(0, 20)}")
            }
            nodesCap = 0
        }
        send("setoption name MultiPV value ${multiPv.coerceIn(1, 5)}")
    }

    private fun send(cmd: String) {
        try {
            writer?.write(cmd + "\n")
            writer?.flush()
        } catch (e: Exception) {
        }
    }

    private fun waitFor(token: String, readOptions: Boolean = false): Boolean {
        var line = reader?.readLine()
        while (line != null) {
            if (readOptions) noteOption(line)
            if (line.startsWith(token)) return true
            line = reader?.readLine()
        }
        return false
    }

    /** remembers the strength options this build advertises, so the Elo is clamped to them */
    private fun noteOption(line: String) {
        if (!line.startsWith("option name ")) return
        val name = line.removePrefix("option name ").substringBefore(" type ")
        when (name) {
            "UCI_Elo" -> {
                UCI_ELO_MIN.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { eloMin = it }
                UCI_ELO_MAX.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { eloMax = it }
            }
            "UCI_LimitStrength" -> hasLimitStrength = true
            "Skill Level" -> hasSkillLevel = true
        }
    }

    fun analyze(fen: String, moveTimeMs: Int = 400): EngineResult? =
        analyzeRaw("position fen $fen", moveTimeMs)

    /** positionCommand is a full UCI line, e.g. "position fen ..." or "position startpos moves e2e4 e7e5" */
    @Synchronized
    fun analyzeRaw(positionCommand: String, moveTimeMs: Int = 400): EngineResult? {
        if (process?.isAlive != true && !start()) return null
        send(positionCommand)
        val cap = nodesCap
        send(if (cap > 0) "go nodes $cap movetime $moveTimeMs" else "go movetime $moveTimeMs")

        var cp: Int? = null
        var mate: Int? = null
        val candidates = HashMap<Int, EngineLine>()
        var line = reader?.readLine()
        while (line != null) {
            val text = line
            if (text.startsWith("info ")) {
                if (text.contains(" pv ")) {
                    val index = MULTIPV.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val cpScore = SCORE_CP.find(text)?.groupValues?.get(1)?.toIntOrNull()
                    val mateScore = SCORE_MATE.find(text)?.groupValues?.get(1)?.toIntOrNull()
                    val move = text.substringAfter(" pv ").trim().substringBefore(' ')
                    if (move.length >= 4) {
                        candidates[index] = EngineLine(move, if (mateScore == null) cpScore else null, mateScore)
                        if (index == 1) {
                            cp = if (mateScore == null) cpScore else null
                            mate = mateScore
                        }
                    }
                } else if (text.contains(" score ")) {
                    // some builds print a score before the first pv line
                    val mateScore = SCORE_MATE.find(text)?.groupValues?.get(1)?.toIntOrNull()
                    if (mateScore != null) {
                        mate = mateScore
                        cp = null
                    } else {
                        SCORE_CP.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { cp = it }
                    }
                }
            }
            if (text.startsWith("bestmove")) {
                val parts = text.split(" ")
                val best = parts.getOrElse(1) { "" }
                val ranked = candidates.toSortedMap().values.toList()
                // when the engine's own pick is not the line it scored best, that pick has no
                // score of its own here: leave it unknown rather than lending it the top one
                val withBest = when {
                    ranked.isEmpty() -> listOf(EngineLine(best, cp, mate))
                    ranked[0].move == best -> ranked
                    else -> listOf(EngineLine(best, null, null)) + ranked
                }
                return EngineResult(cp, mate, best, withBest)
            }
            line = reader?.readLine()
        }
        stop() // engine died (e.g. illegal position); it restarts on the next call
        return null
    }

    @Synchronized
    fun stop() {
        send("quit")
        try { process?.destroy() } catch (e: Exception) { }
        process = null
        reader = null
        writer = null
    }

    private companion object {
        val MULTIPV = Regex("multipv (\\d+)")
        val SCORE_CP = Regex("score cp (-?\\d+)")
        val SCORE_MATE = Regex("score mate (-?\\d+)")
        val UCI_ELO_MIN = Regex("min (-?\\d+)")
        val UCI_ELO_MAX = Regex("max (-?\\d+)")
    }
}
