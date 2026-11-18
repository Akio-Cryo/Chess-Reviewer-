package com.example.chessreviewer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private val reqCapture = 100
    private var engine: StockfishEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val info = TextView(this)
        info.textSize = 16f
        info.text = "Automatic mode:\n\n" +
            "1. Tap 'Allow overlay' and switch it on.\n" +
            "2. Tap 'Start' and accept the screen-capture prompt.\n" +
            "3. Open your chess game so the whole board is visible. A small panel floats at the bottom.\n" +
            "4. With the pieces in the START position, tap 'Learn' (once per piece set).\n" +
            "5. Play. The board is found automatically and the best move updates by itself.\n\n" +
            "Level = engine strength (Max, then Elo levels down to 1320, then Skill 12 / 8 / 4 / 0 of 20; weaker levels sometimes pick weaker moves). Flip = swap which side is at the bottom. Turn = swap whose turn it is. " +
            "Save = writes a picture of what the app sees to Pictures/ChessReviewer (for debugging).\n\n" +
            "Some screens block screen capture (they show up black). For those, use the box below."

        val overlayBtn = Button(this)
        overlayBtn.text = "Allow overlay"
        overlayBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        val startBtn = Button(this)
        startBtn.text = "Start"
        startBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow 'Display over other apps' first", Toast.LENGTH_LONG).show()
            } else {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                @Suppress("DEPRECATION")
                startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
            }
        }

        val stopBtn = Button(this)
        stopBtn.text = "Stop"
        stopBtn.setOnClickListener { stopService(Intent(this, CaptureService::class.java)) }

        val posLabel = TextView(this)
        posLabel.textSize = 16f
        posLabel.text = "\nAnalyze a position by hand:"

        val posInput = EditText(this)
        posInput.hint = "Paste a FEN, or UCI moves from the start, e.g.  e2e4 e7e5 g1f3"
        posInput.minLines = 2

        val prefs = getSharedPreferences("chess", MODE_PRIVATE)
        val levelBtn = Button(this)
        levelBtn.text = "Engine strength: " + Levels.label(prefs.getInt("elo", 0)) + "  (tap to change)"
        levelBtn.setOnClickListener {
            val next = Levels.next(prefs.getInt("elo", 0))
            prefs.edit().putInt("elo", next).apply()
            levelBtn.text = "Engine strength: " + Levels.label(next) + "  (tap to change)"
        }

        val result = TextView(this)
        result.textSize = 18f

        val analyzeBtn = Button(this)
        analyzeBtn.text = "Analyze position"
        analyzeBtn.setOnClickListener { analyze(posInput.text.toString(), result) }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(40, 80, 40, 80)
        col.addView(info)
        col.addView(overlayBtn)
        col.addView(startBtn)
        col.addView(stopBtn)
        col.addView(posLabel)
        col.addView(posInput)
        col.addView(levelBtn)
        col.addView(analyzeBtn)
        col.addView(result)

        val scroll = ScrollView(this)
        scroll.addView(col)
        setContentView(scroll)
    }

    private fun analyze(input: String, out: TextView) {
        val text = input.trim()
        if (text.isEmpty()) {
            out.text = "Type or paste a position first."
            return
        }
        val cmd: String
        val blackToMove: Boolean
        if (text.contains('/')) {
            val f = text.split(Regex("\\s+")).toMutableList()
            if (f.size < 2) f.add("w")
            if (f.size < 3) f.add("-")
            if (f.size < 4) f.add("-")
            if (f.size < 5) f.add("0")
            if (f.size < 6) f.add("1")
            cmd = "position fen " + f.joinToString(" ")
            blackToMove = f[1] == "b"
        } else {
            val moves = text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            val ok = Regex("[a-h][1-8][a-h][1-8][qrbn]?")
            if (moves.any { !ok.matches(it) }) {
                out.text = "Moves must look like e2e4 (start square + end square), not Nf3. Or paste a FEN."
                return
            }
            cmd = "position startpos moves " + moves.joinToString(" ")
            blackToMove = moves.size % 2 == 1
        }

        out.text = "Thinking…"
        Thread {
            val e = engine ?: StockfishEngine(this).also { engine = it }
            e.setStrength(getSharedPreferences("chess", MODE_PRIVATE).getInt("elo", 0))
            val available = e.available()
            val r = if (available) e.analyzeRaw(cmd, 1000) else null
            runOnUiThread {
                out.text =
                    if (!available) "Stockfish isn't inside this APK (libstockfish.so missing)."
                    else if (r == null) "The engine couldn't read that position."
                    else format(r, blackToMove)
            }
        }.start()
    }

    private fun format(r: EngineResult, black: Boolean): String {
        val b = r.best
        if (b.length < 4 || b == "(none)") return "No legal moves (game over)."
        val mate = r.mate
        val cp = r.cp
        val ev = if (mate != null) {
            val m = if (black) -mate else mate
            if (m > 0) "White mates in $m" else "Black mates in ${-m}"
        } else if (cp != null) {
            String.format(Locale.US, "%+.2f", (if (black) -cp else cp) / 100.0)
        } else "?"
        return (if (black) "Black" else "White") + " to move\nSuggested move: $b\nEval (White): $ev"
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        engine?.stop()
        super.onDestroy()
    }
}
