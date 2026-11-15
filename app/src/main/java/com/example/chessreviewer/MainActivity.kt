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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.Locale

class MainActivity : Activity() {
    private val reqCapture = 100
    private val reqShizuku = 200
    private var engine: StockfishEngine? = null
    private lateinit var shizuku: ShizukuCapture
    private var shizukuOut: TextView? = null

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == reqShizuku) refreshShizuku()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        shizuku = ShizukuCapture(applicationContext)
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (e: Throwable) {
            // Shizuku's service is not up; the buttons below report that.
        }

        val info = TextView(this)
        info.textSize = 16f
        info.text = "Automatic mode:\n\n" +
            "1. Tap 'Allow overlay' and switch it on.\n" +
            "2. Tap 'Start' and accept the screen-capture prompt.\n" +
            "3. Open your chess game so the whole board is visible. A small panel floats at the bottom.\n" +
            "4. With the pieces in the START position, tap 'Learn' (once per piece set).\n" +
            "5. Play. The board is found automatically and the best move updates by itself.\n\n" +
            "Flip = swap which side is at the bottom. Turn = swap whose turn it is. " +
            "Save = writes a picture of what the app sees to Pictures/ChessReviewer (for debugging). " +
            "Engine strength and human pace are set further down this screen; the floating panel has " +
            "Elo and Pace buttons for the same two settings.\n\n" +
            "Screens that block capture (FLAG_SECURE) come back black to a normal screen recording. " +
            "The app reads those anyway when it can: through root if the phone is rooted, or through " +
            "Shizuku if it is not. For Shizuku, install Shizuku, start it over wireless debugging " +
            "(Android 11 and newer), then tap 'Grant Shizuku access' here. Protected screens are " +
            "readable that way on Android 12 and newer; on older versions only root can read them."

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

        // ---------------------------------------------------------------- protected screens

        val shizukuLabel = TextView(this)
        shizukuLabel.textSize = 16f
        shizukuLabel.text = "\nProtected screens (FLAG_SECURE):"

        val shizukuOut = TextView(this)
        shizukuOut.textSize = 14f
        this.shizukuOut = shizukuOut

        val grantBtn = Button(this)
        grantBtn.text = "Grant Shizuku access"
        grantBtn.setOnClickListener { requestShizukuPermission() }

        val openBtn = Button(this)
        openBtn.text = "Open Shizuku"
        openBtn.setOnClickListener { openShizuku() }

        val checkBtn = Button(this)
        checkBtn.text = "Check screen access"
        checkBtn.setOnClickListener {
            shizukuOut.text = "Checking root, then Shizuku…"
            Thread {
                val secure = SecureCapture(this, cacheDir)
                val dm = resources.displayMetrics
                val shot = secure.grab(dm.widthPixels, dm.heightPixels)
                val message = if (shot == null) {
                    "Can't read protected screens: " + secure.lastError +
                        ".\n" + secure.describe() +
                        "\nThey will stay black; type the position in below instead."
                } else {
                    val size = shot.width.toString() + "x" + shot.height
                    shot.recycle()
                    "Works (" + secure.describe() + ") read the screen at " + size +
                        ". Protected screens are readable while the app is running."
                }
                runOnUiThread { shizukuOut.text = message }
            }.start()
        }

        // ---------------------------------------------------------------- engine strength

        val strengthPrefs = getSharedPreferences("chess", MODE_PRIVATE)
        val eloSteps = Levels.storedValues
        val eloNames = Levels.labels

        val strengthLabel = TextView(this)
        strengthLabel.textSize = 16f
        strengthLabel.text = "\nEngine strength (the floating panel uses the same setting):"

        val eloSpinner = Spinner(this)
        eloSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, eloNames)
        val storedElo = strengthPrefs.getInt("elo", 0)
        eloSpinner.setSelection(eloSteps.indexOf(storedElo).let { if (it < 0) eloSteps.size - 1 else it })

        val humanBox = CheckBox(this)
        humanBox.text = "Human pace: vary the move, and the time to play it"
        humanBox.isChecked = strengthPrefs.getBoolean("human", true)

        val strengthNote = TextView(this)
        strengthNote.textSize = 13f
        strengthNote.text =
            "Stockfish plays weaker two ways. Down to 1320 the limit is its own UCI_Elo, which " +
            "is the setting that maps to a real rating. Under that it has nothing better to say, so " +
            "the steps below 1320 switch to its Skill Level option (0 to 20, the same scale UCI_Elo " +
            "is translated into) with a node budget instead of a depth cap, which shortens the " +
            "search without blinding it: a depth cap leaves the engine unable to score the moves it " +
            "is choosing between, and the play turns into random moves rather than weak ones. On " +
            "those steps the move shown is drawn from the engine's top five moves inside a " +
            "centipawn budget, so most moves are still its first choice and the occasional one is " +
            "the kind of mistake a player of that level makes.\n\n" +
            "Human pace varies the choice and the timing, so neither arrives the same way twice.\n\n" +
            "Neither of those guarantees anything by itself: chess.com reviews finished games " +
            "statistically, and assistance in a live or rated game is against its fair play rules."

        eloSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                strengthPrefs.edit().putInt("elo", eloSteps[position]).apply()
                applyEngineSettings()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        humanBox.setOnCheckedChangeListener { _, checked ->
            strengthPrefs.edit().putBoolean("human", checked).apply()
            applyEngineSettings()
        }

        val posLabel = TextView(this)
        posLabel.textSize = 16f
        posLabel.text = "\nAnalyze a position by hand:"

        val posInput = EditText(this)
        posInput.hint = "Paste a FEN, or UCI moves from the start, e.g.  e2e4 e7e5 g1f3"
        posInput.minLines = 2

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
        col.addView(shizukuLabel)
        col.addView(shizukuOut)
        col.addView(grantBtn)
        col.addView(openBtn)
        col.addView(checkBtn)
        col.addView(strengthLabel)
        col.addView(eloSpinner)
        col.addView(humanBox)
        col.addView(strengthNote)
        col.addView(posLabel)
        col.addView(posInput)
        col.addView(analyzeBtn)
        col.addView(result)

        val scroll = ScrollView(this)
        scroll.addView(col)
        setContentView(scroll)

        refreshShizuku()
    }

    override fun onResume() {
        super.onResume()
        refreshShizuku()
    }

    // -------------------------------------------------------------------- Shizuku

    private fun refreshShizuku() {
        val out = shizukuOut ?: return
        out.text = try {
            shizuku.report()
        } catch (e: Throwable) {
            "Shizuku: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun requestShizukuPermission() {
        val out = shizukuOut ?: return
        try {
            if (!shizuku.isRunning()) {
                out.text = "Shizuku is not running yet. Open it, start it, then come back."
                return
            }
            if (shizuku.hasPermission()) {
                out.text = shizuku.report()
                return
            }
            out.text = "Asking Shizuku for access…"
            Shizuku.requestPermission(reqShizuku)
        } catch (e: Throwable) {
            out.text = "Could not ask Shizuku: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun openShizuku() {
        val launch = try {
            packageManager.getLaunchIntentForPackage(ShizukuCapture.PACKAGE_SHIZUKU)
        } catch (e: Throwable) {
            null
        }
        if (launch != null) {
            startActivity(launch)
            return
        }
        // Not installed: the Play Store page is the quickest install path.
        val market = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=" + ShizukuCapture.PACKAGE_SHIZUKU)
        )
        try {
            startActivity(market)
        } catch (e: Throwable) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + ShizukuCapture.PACKAGE_SHIZUKU)
                )
            )
        }
    }

    // -------------------------------------------------------------------- engine

    /** push the strength chosen on this screen into the engine the floating panel uses */
    private fun applyEngineSettings() {
        val p = getSharedPreferences("chess", MODE_PRIVATE)
        val e = engine ?: return
        val stored = p.getInt("elo", 0)
        e.configure(if (stored <= 0) null else stored,
            if (p.getBoolean("human", true)) Levels.multiPvFor(stored) else 1)
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
            val p = getSharedPreferences("chess", MODE_PRIVATE)
            val storedElo = p.getInt("elo", 0)
            val eloLimit: Int? = if (storedElo <= 0) null else storedElo
            val human = p.getBoolean("human", true)
            e.configure(eloLimit, 1)
            val available = e.available()
            val r = if (available) e.analyzeRaw(cmd, if (human) 900 else 1000) else null
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
        return (if (black) "Black" else "White") + " to move\nBest move: $b\nEval (White): $ev"
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
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (e: Throwable) {
        }
        engine?.stop()
        super.onDestroy()
    }
}
