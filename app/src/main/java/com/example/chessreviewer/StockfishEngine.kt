package com.example.chessreviewer

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** cp / mate are from the point of view of the side to move (UCI convention). */
class EngineResult(val cp: Int?, val mate: Int?, val best: String)

class StockfishEngine(private val context: Context) {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

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
            if (!waitFor("uciok")) return false
            send("setoption name Threads value 2")
            send("setoption name Hash value 64")
            send("isready")
            waitFor("readyok")
        } catch (e: Exception) {
            false
        }
    }

    private fun send(cmd: String) {
        try {
            writer?.write(cmd + "\n")
            writer?.flush()
        } catch (e: Exception) {
        }
    }

    private fun waitFor(token: String): Boolean {
        var line = reader?.readLine()
        while (line != null) {
            if (line.startsWith(token)) return true
            line = reader?.readLine()
        }
        return false
    }

    fun analyze(fen: String, moveTimeMs: Int = 400): EngineResult? =
        analyzeRaw("position fen $fen", moveTimeMs)

    /** positionCommand is a full UCI line, e.g. "position fen ..." or "position startpos moves e2e4 e7e5" */
    @Synchronized
    fun analyzeRaw(positionCommand: String, moveTimeMs: Int = 400): EngineResult? {
        if (process?.isAlive != true && !start()) return null
        send(positionCommand)
        send("go movetime $moveTimeMs")

        var cp: Int? = null
        var mate: Int? = null
        var line = reader?.readLine()
        while (line != null) {
            if (line.contains(" score cp ")) {
                cp = line.substringAfter(" score cp ").substringBefore(" ").toIntOrNull()
                mate = null
            } else if (line.contains(" score mate ")) {
                mate = line.substringAfter(" score mate ").substringBefore(" ").toIntOrNull()
                cp = null
            }
            if (line.startsWith("bestmove")) {
                val parts = line.split(" ")
                return EngineResult(cp, mate, parts.getOrElse(1) { "" })
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
}
