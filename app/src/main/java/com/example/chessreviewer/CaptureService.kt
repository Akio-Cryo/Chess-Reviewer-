package com.example.chessreviewer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class CaptureService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var engine: StockfishEngine
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val tracker = Tracker()

    // engine strength and pacing, shared with MainActivity through the "chess" preferences
    private val rng = Random(System.currentTimeMillis())
    private val MULTI_PV = 5
    @Volatile private var elo: Int? = null
    @Volatile private var humanPace = true

    // the move showing on the panel, and when a person would plausibly play it
    @Volatile private var playText: String? = null
    @Volatile private var playAt = 0L
    @Volatile private var playWindow = 0L..0L

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var screenW = 0
    private var screenH = 0
    private var started = false

    // reads screens that FLAG_SECURE turns black for the ordinary capture path
    private lateinit var secure: SecureCapture
    private var secureMode = false
    private var lastSecureTry = 0L
    private val SECURE_POLL_MS = 500L

    // settings shared between the UI thread and the analysis loop
    @Volatile private var boardRect: IntArray? = null   // left, top, size (screen pixels)
    @Volatile private var templates: Templates? = null
    @Volatile private var whiteAtBottom = true
    @Volatile private var learnRequested = false
    @Volatile private var resetRequested = false
    @Volatile private var turnFlipRequested = false
    @Volatile private var saveRequested = false
    @Volatile private var freshAfter = 0L

    // used only by the analysis loop
    private var lastFrame: Bitmap? = null
    private var frameFresh = false
    private var observed: CharArray? = null
    private var reprocess = false
    private var stablePos: CharArray? = null
    private var stableKey: String? = null
    private var pendingKey: String? = null
    private var pendingCount = 0

    // overlay views
    private var panel: View? = null
    private var statusView: TextView? = null
    private var frameView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("chess", MODE_PRIVATE)
        engine = StockfishEngine(this)
        secure = SecureCapture(this, cacheDir)
        loadEngineSettings()
        whiteAtBottom = prefs.getBoolean("whiteBottom", true)
        prefs.getString("tpl", null)?.let { templates = Templates.parse(it) }
        prefs.getString("rect", null)?.let { s ->
            val p = s.split(",").mapNotNull { it.toIntOrNull() }
            if (p.size == 3) boardRect = intArrayOf(p[0], p[1], p[2])
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (started) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        setupCapture(code, data)
        buildPanel()
        scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    setStatus("Error: " + e.message)
                }
                delay(450)
            }
        }
        return START_NOT_STICKY
    }

    /** strength and pacing as chosen in MainActivity, or with the panel buttons */
    private fun loadEngineSettings() {
        val stored = prefs.getInt("elo", 0)
        elo = if (stored <= 0) null else stored
        humanPace = prefs.getBoolean("human", true)
        engine.configure(elo, if (humanPace) MULTI_PV else 1)
    }

    private fun startAsForeground() {
        val ch = NotificationChannel("capture", "Chess Reviewer", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        val n = Notification.Builder(this, "capture")
            .setContentTitle("Chess Reviewer is running")
            .setContentText("Watching the screen for your chess board")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun setupCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader = r
        vdisplay = proj.createVirtualDisplay(
            "chess-capture", screenW, screenH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
    }

    // ------------------------------------------------------------------ analysis loop

    private var lastDetect = 0L
    private var lastLearnTry = 0L
    private var failCount = 0

    private fun detectBoard(frame: Bitmap): BoardFinder.Found? {
        val w = minOf(screenW, frame.width)
        val h = minOf(screenH, frame.height)
        val all = IntArray(w * h)
        frame.getPixels(all, 0, w, 0, 0, w, h)
        return BoardFinder.find(all, w, h)
    }

    private fun setRect(f: BoardFinder.Found): IntArray {
        val rect = intArrayOf(f.left, f.top, f.size)
        boardRect = rect
        prefs.edit().putString("rect", "${f.left},${f.top},${f.size}").apply()
        return rect
    }

    /** full-resolution pixels of the board area */
    private fun cropBoard(frame: Bitmap, rect: IntArray): IntArray? {
        val s = rect[2]
        if (s < Vision.B / 2) return null
        if (rect[0] < 0 || rect[1] < 0 || rect[0] + s > frame.width || rect[1] + s > frame.height) return null
        val px = IntArray(s * s)
        frame.getPixels(px, 0, s, rect[0], rect[1], s, s)
        return px
    }

    /** 32 pieces, all on ranks 1, 2, 7, 8 */
    private fun startLike(p: CharArray): Boolean {
        var n = 0
        for (i in 0 until 64) if (p[i] != '.') n++
        if (n != 32) return false
        for (i in 16 until 48) if (p[i] != '.') return false
        return true
    }

    /** debug: write what the app sees to Pictures/ChessReviewer so the user can share it */
    private fun saveFrame(frame: Bitmap) {
        // On a protected screen the captured frame is black; save the root view instead so the
        // picture is actually useful.
        var src = frame
        var borrowed = false
        if (looksBlocked(src)) {
            val shot = secure.grab(screenW, screenH)
            if (shot != null && !looksBlocked(shot)) {
                src = shot
                borrowed = true
            } else {
                shot?.recycle()
            }
        }
        try {
            val v = ContentValues()
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "chess_debug_" + System.currentTimeMillis() + ".png")
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChessReviewer")
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            if (uri == null) { setStatus("Couldn't save the picture."); return }
            val w = minOf(screenW, src.width)
            val h = minOf(screenH, src.height)
            val crop = Bitmap.createBitmap(src, 0, 0, w, h)
            contentResolver.openOutputStream(uri)?.use { out -> crop.compress(Bitmap.CompressFormat.PNG, 100, out) }
            crop.recycle()
            setStatus("Saved a picture of what I see to Pictures/ChessReviewer (open Photos / Gallery). Send it to me.")
        } catch (e: Exception) {
            setStatus("Couldn't save the picture: " + e.message)
        } finally {
            if (borrowed) src.recycle()
        }
    }

    /** secure screens show up as (almost) pure black in a screen capture */
    private fun looksBlocked(frame: Bitmap): Boolean {
        val w = minOf(screenW, frame.width)
        val h = minOf(screenH, frame.height)
        if (w < 48 || h < 96) return false
        var dark = 0
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = frame.getPixel(x, y)
                if (((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255) < 12) dark++
                n++
                x += w / 24
            }
            y += h / 48
        }
        return n > 0 && dark * 100 / n >= 85
    }

    /** one bitmap to analyse, and whether it is new pixels */
    private class Frame(val bitmap: Bitmap, val fresh: Boolean)

    /** whatever MediaProjection last handed us, as a bitmap, or null when nothing arrived */
    private fun takeProjectionFrame(): Bitmap? {
        val img = try { reader?.acquireLatestImage() } catch (e: Exception) { null } ?: return null
        try {
            val plane = img.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * img.width
            val bmp = Bitmap.createBitmap(
                img.width + rowPadding / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(plane.buffer)
            return bmp
        } catch (e: Exception) {
            return null
        } finally {
            try { img.close() } catch (e: Exception) { }
        }
    }

    /**
     * Picks the pixels to analyse.
     *
     * MediaProjection returns solid black for a window that sets FLAG_SECURE, so a black
     * frame is thrown away and the same screen is re-read through root `screencap`, or through
     * Shizuku when the phone is not rooted. If the screen becomes capturable again the normal
     * path takes over on the next frame.
     */
    private fun resolveFrame(): Frame? {
        val proj = takeProjectionFrame()
        if (proj != null) {
            if (!looksBlocked(proj)) {
                if (secureMode) {
                    secureMode = false
                    setStatus("Screen capture is working again.")
                }
                return Frame(proj, true)
            }
            proj.recycle()
            if (secureMode && SystemClock.uptimeMillis() - lastSecureTry < SECURE_POLL_MS) {
                // between root reads: keep showing the last readable frame instead of flickering
                lastFrame?.let { return Frame(it, false) }
            }
            if (secure.mode == SecureCapture.Mode.UNKNOWN) {
                setStatus("This screen blocks capture. Checking root, then Shizuku, so I can read it anyway…")
            }
            val shot = secureShot()
            if (shot != null) {
                secureMode = true
                return Frame(shot, true)
            }
            secureMode = false
            blockedHint()
            return null
        }
        if (secureMode) {
            val shot = secureShot()
            if (shot != null) return Frame(shot, true)
        }
        lastFrame?.let { return Frame(it, false) }
        return null
    }

    /** Root or Shizuku screenshot of the display, throttled to one per SECURE_POLL_MS. */
    private fun secureShot(): Bitmap? {
        val now = SystemClock.uptimeMillis()
        if (now - lastSecureTry < SECURE_POLL_MS) return null
        lastSecureTry = now
        val shot = secure.grab(screenW, screenH)
        if (shot == null || looksBlocked(shot)) {
            shot?.recycle()
            return null
        }
        return shot
    }

    private fun blockedHint() {
        val detail = secure.lastError.take(90)
        setStatus(
            when (secure.mode) {
                SecureCapture.Mode.ROOT ->
                    "This screen blocks capture and even root saw only black (DRM video does that). Open a screen that allows capture, or type the position into the app."
                SecureCapture.Mode.SHIZUKU ->
                    "This screen blocks capture and even Shizuku saw only black (DRM video does that). Open a screen that allows capture, or type the position into the app."
                SecureCapture.Mode.NONE ->
                    "This screen blocks screen capture (FLAG_SECURE) and neither root nor Shizuku could read it" +
                        (if (detail.isBlank()) "" else " (" + detail + ")") +
                        ". Start Shizuku, grant this app access, and reopen Chess Reviewer so it can reach Shizuku; " +
                        "or type the position into the Chess Reviewer app instead."
                else ->
                    "This screen blocks capture. Looking for root, then Shizuku, so I can read it anyway…"
            }
        )
    }

    private fun tick() {
        refreshPlayStatus()
        if (resetRequested) {
            resetRequested = false
            observed = null; stablePos = null; stableKey = null
            pendingKey = null; pendingCount = 0
            tracker.reset()
            playText = null
            playAt = 0L
            reprocess = true
        }

        val picked = resolveFrame() ?: return
        if (picked.bitmap !== lastFrame) {
            lastFrame?.recycle()
            lastFrame = picked.bitmap
        }
        if (SystemClock.uptimeMillis() <= freshAfter) { frameFresh = false; return }
        if (picked.fresh) frameFresh = true
        if (!frameFresh) return

        val frame = picked.bitmap
        val newFrame = picked.fresh

        if (saveRequested) {
            saveRequested = false
            saveFrame(frame)
            return
        }

        if (learnRequested) {
            learnRequested = false
            val found = detectBoard(frame)
            if (found == null) {
                setStatus("I can't find a chess board on the screen. Open the game so the whole board is visible (and drag this panel off the board), then tap Learn again.")
                return
            }
            val rect = setRect(found)
            val bpx = cropBoard(frame, rect)
            if (bpx == null) {
                setStatus("The board is partly off-screen. Scroll so it is fully visible and tap Learn again.")
                return
            }
            val res = Vision.learn(Vision.downscale(bpx, rect[2]))
            val learned = res.templates
            if (learned == null) {
                setStatus(res.error ?: "Couldn't recognise the starting position.")
            } else {
                templates = learned
                whiteAtBottom = res.whiteBottom
                prefs.edit().putString("tpl", learned.serialize()).putBoolean("whiteBottom", res.whiteBottom).apply()
                resetRequested = true
                setStatus("Learned! White is at the " + (if (res.whiteBottom) "bottom" else "top") + ". Now play.")
            }
            return
        }

        val tpl = templates
        if (tpl == null) {
            if (newFrame) setStatus("Open a game with the pieces in the START position, then tap Learn.")
            return
        }

        // make sure we know where the board is (re-detects by itself if it moved)
        var rect = boardRect
        var px: IntArray? = null
        if (rect != null) px = cropBoard(frame, rect)
        var ok = false
        if (rect != null && px != null) {
            ok = !newFrame || BoardFinder.score(px, rect[2], rect[2], 0, 0, rect[2], 4, 0.12f) >= 0.5f
        }
        if (!ok) {
            val now = SystemClock.uptimeMillis()
            if (now - lastDetect > 3000) {
                lastDetect = now
                val found = detectBoard(frame)
                if (found != null) {
                    rect = setRect(found)
                    px = cropBoard(frame, rect)
                    reprocess = true
                }
            }
        }
        if (rect == null || px == null) {
            if (newFrame) setStatus("Looking for the chess board on screen…")
            return
        }

        if (newFrame || reprocess) {
            reprocess = false
            val small = Vision.downscale(px, rect[2])
            val obs = Vision.readPosition(small, tpl, whiteAtBottom)
            val tNow = SystemClock.uptimeMillis()
            if ((obs == null || (Chess.placement(obs) != Chess.START && startLike(obs))) && tNow - lastLearnTry > 1500) {
                // maybe a new piece set / board theme: if the start position is on screen, learn it automatically
                lastLearnTry = tNow
                val res = Vision.learn(small)
                val learned = res.templates
                if (learned != null) {
                    templates = learned
                    whiteAtBottom = res.whiteBottom
                    prefs.edit().putString("tpl", learned.serialize()).putBoolean("whiteBottom", res.whiteBottom).apply()
                    resetRequested = true
                    setStatus("Recognised the start position and learned this board automatically.")
                    return
                }
            }
            if (obs != null && Chess.placement(obs) != Chess.START && startLike(obs)) {
                // maybe we're playing the other colour now: if reading it flipped gives the start position, flip
                val alt = Vision.readPosition(small, tpl, !whiteAtBottom)
                if (alt != null && Chess.placement(alt) == Chess.START) {
                    whiteAtBottom = !whiteAtBottom
                    prefs.edit().putBoolean("whiteBottom", whiteAtBottom).apply()
                    resetRequested = true
                    return
                }
            }
            observed = obs
            if (obs == null) failCount++ else failCount = 0
            if (obs == null && (stableKey == null || failCount >= 4)) {
                setStatus("Can't read the board (" + Vision.lastProblem + "). If the pieces or board colours look different from before, go back to the start of a game and I'll relearn them. Or tap Save and send me the picture.")
            }
        }

        if (turnFlipRequested) {
            turnFlipRequested = false
            tracker.blackToMove = !tracker.blackToMove
            if (stablePos != null) runEngine()
        }

        val obs = observed ?: return
        val key = Chess.placement(obs)
        if (key == stableKey) {
            pendingKey = null
            pendingCount = 0
            return
        }
        if (key == pendingKey) {
            pendingCount++
        } else {
            pendingKey = key
            pendingCount = 1
        }
        if (pendingCount >= 2) {
            stableKey = key
            stablePos = obs
            pendingKey = null
            pendingCount = 0
            tracker.update(obs, whiteAtBottom)
            runEngine()
        }
    }

    private fun runEngine() {
        val pos = stablePos ?: return
        if (!engine.available()) {
            setStatus("Stockfish isn't inside this APK (libstockfish.so missing). Check the 'Download Stockfish' step of the build.")
            return
        }
        engine.configure(elo, if (humanPace) MULTI_PV else 1)
        setStatus("Thinking…")
        // vary the search time as well: a fixed budget shows up as a fixed pause
        val moveTime = if (humanPace) 500 + rng.nextInt(700) else 900
        val r = engine.analyze(tracker.fen(pos), moveTime)
        if (r == null) {
            setStatus("The engine didn't answer. The position may be misread; try the Turn button.")
            return
        }
        val chosen = Humanizer.chooseMove(r, elo, humanPace, rng)
        val window = Humanizer.thinkWindowMs(r, pos, humanPace, rng)
        playText = describe(chosen, pos)
        playWindow = window
        playAt = if (window.last <= 0L) {
            0L
        } else {
            SystemClock.uptimeMillis() + window.first +
                (rng.nextDouble() * (window.last - window.first)).toLong()
        }
        setStatus(playStatus())
    }

    /** the move on the panel, plus how long to sit on it before playing */
    private fun playStatus(): String {
        val base = playText ?: return ""
        if (playAt <= 0L) return base + "\nPlay it now."
        val left = playAt - SystemClock.uptimeMillis()
        if (left <= 0L) {
            playAt = 0L
            return base + "\nPlay it now."
        }
        val secs = (left + 999) / 1000
        val lo = playWindow.first / 1000
        val hi = playWindow.last / 1000
        return base + "\nPlay in " + secs + "s  (human pace " + lo + "-" + hi + "s)"
    }

    private fun refreshPlayStatus() {
        if (playText == null || playAt == 0L) return
        val text = playStatus()
        if (text.isNotEmpty()) setStatus(text)
    }

    private fun describe(line: EngineLine, pos: CharArray): String {
        val black = tracker.blackToMove
        val side = if (black) "Black" else "White"
        val b = line.move
        if (b.length < 4 || b == "(none)") return "$side to move: no legal moves (game over?)"
        val from = (b[1] - '1') * 8 + (b[0] - 'a')
        val to = (b[3] - '1') * 8 + (b[2] - 'a')
        if (from !in 0..63 || to !in 0..63) return "$side to move\nPlay: $b"
        val names = mapOf('p' to "Pawn", 'n' to "Knight", 'b' to "Bishop", 'r' to "Rook", 'q' to "Queen", 'k' to "King")
        val piece = pos[from].lowercaseChar()
        var move =
            if (piece == 'k' && abs(to % 8 - from % 8) == 2) {
                if (to % 8 > from % 8) "Castle kingside" else "Castle queenside"
            } else {
                (names[piece] ?: "Piece") + " " + b.substring(0, 2) + " -> " + b.substring(2, 4)
            }
        if (pos[to] != '.') move += " (capture)"
        if (b.length > 4) move += " =" + b[4].uppercaseChar()

        val mate = line.mate
        val cp = line.cp
        val ev = if (mate != null) {
            val m = if (black) -mate else mate
            if (m > 0) "White mates in $m" else "Black mates in ${-m}"
        } else if (cp != null) {
            val v = (if (black) -cp else cp) / 100.0
            String.format(Locale.US, "%+.2f", v)
        } else "?"
        return "$side to move\nPlay: $move  ($b)\nEval (White): $ev"
    }

    // ------------------------------------------------------------------ overlay UI

    private fun setStatus(text: String) {
        handler.post { statusView?.text = text }
    }

    private fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        return lp
    }

    private fun makeDraggable(v: View, lp: WindowManager.LayoutParams) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt()
                    lp.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(v, lp)
                }
            }
            true
        }
    }

    private fun eloLabel(e: Int?): String = if (e == null) "Elo: max" else "Elo: $e"

    /** what the engine is actually doing at the current step, for the panel's status line */
    private fun strengthStatus(): String {
        val step = Levels.stepFor(elo) ?: return "Engine at full strength."
        return if (step.skill != null) {
            "Engine at about ${step.elo} Elo (Skill Level ${step.skill}, depth cap ${step.depth})."
        } else {
            "Engine limited to about ${step.elo} Elo."
        }
    }

    /**
     * Steps the strength by one. A tap goes up the list, a long press down it: the list now runs
     * from 400 Elo to full strength, and walking ten steps one way to get back is no good.
     */
    private fun cycleElo(button: Button, down: Boolean = false) {
        val steps = Levels.storedValues
        val current = elo ?: 0
        val index = steps.indexOf(current).let { if (it < 0) steps.size - 1 else it }
        val next = steps[if (down) (index - 1 + steps.size) % steps.size else (index + 1) % steps.size]
        elo = if (next == 0) null else next
        prefs.edit().putInt("elo", next).apply()
        button.text = eloLabel(elo)
        setStatus(strengthStatus())
        if (stablePos != null) scope.launch { runEngine() }
    }

    private fun togglePace(button: Button) {
        humanPace = !humanPace
        prefs.edit().putBoolean("human", humanPace).apply()
        button.text = if (humanPace) "Pace: human" else "Pace: off"
        setStatus(
            if (humanPace) "Human pace on: the move may be one of the top few, and the panel gives you a delay to play it in."
            else "Human pace off: always the engine's first choice, straight away."
        )
        if (stablePos != null) scope.launch { runEngine() }
    }

    private fun buildPanel() {
        val d = resources.displayMetrics.density
        val pad = (8 * d).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xE6111111.toInt())
        root.setPadding(pad, pad, pad, pad)

        val status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.textSize = 14f
        status.text = "Open your chess game so the whole board is visible. At the START position tap Learn. That's it, the board is found automatically."
        statusView = status
        root.addView(status)

        fun makeRow(): LinearLayout {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            root.addView(r)
            return r
        }
        fun addButton(parent: LinearLayout, label: String, action: () -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.textSize = 11f
            b.isAllCaps = false
            b.setPadding(2, 0, 2, 0)
            b.setOnClickListener { action() }
            parent.addView(b, LinearLayout.LayoutParams(0, (42 * d).toInt(), 1f))
            return b
        }
        val row = makeRow()
        addButton(row, "Learn") { learnRequested = true; playText = null; setStatus("Learning…") }
        addButton(row, "Flip") {
            whiteAtBottom = !whiteAtBottom
            prefs.edit().putBoolean("whiteBottom", whiteAtBottom).apply()
            resetRequested = true
            setStatus("Board flipped: White is now at the " + (if (whiteAtBottom) "bottom." else "top."))
        }
        addButton(row, "Turn") { turnFlipRequested = true }
        addButton(row, "Save") { saveRequested = true; setStatus("Saving a picture…") }
        addButton(row, "Stop") { stopSelf() }

        // strength and pacing, changeable without leaving the game
        val row2 = makeRow()
        val eloBtn = addButton(row2, eloLabel(elo)) { }
        eloBtn.setOnClickListener { cycleElo(eloBtn) }
        eloBtn.setOnLongClickListener { cycleElo(eloBtn, down = true); true }
        val paceBtn = addButton(row2, if (humanPace) "Pace: human" else "Pace: off") { }
        paceBtn.setOnClickListener { togglePace(paceBtn) }
        addButton(row2, "Again") {
            if (stablePos != null) {
                setStatus("Thinking again…")
                scope.launch { runEngine() }
            }
        }

        val w = (screenW * 0.94f).toInt()
        val lp = overlayParams(w, WindowManager.LayoutParams.WRAP_CONTENT)
        lp.x = (screenW - w) / 2
        lp.y = (screenH - 250 * d).toInt()
        makeDraggable(root, lp)
        wm.addView(root, lp)
        panel = root

        if (templates != null) {
            setStatus("Piece set already learned. Open a game and play; the board is found automatically.")
        }
    }

    private fun toggleFrame() {
        val fv = frameView
        if (fv != null) {
            val loc = IntArray(2)
            fv.getLocationOnScreen(loc)
            val size = fv.width
            boardRect = intArrayOf(loc[0], loc[1], size)
            prefs.edit().putString("rect", "${loc[0]},${loc[1]},$size").apply()
            try { wm.removeView(fv) } catch (e: Exception) { }
            frameView = null
            frameFresh = false
            freshAfter = SystemClock.uptimeMillis() + 500
            resetRequested = true
            setStatus(
                if (templates == null) "Board locked. Put the pieces in the START position and tap Learn."
                else "Board locked. Analysis is automatic now."
            )
        } else {
            showFrame()
        }
    }

    private fun showFrame() {
        val d = resources.displayMetrics.density
        val r = boardRect
        val size = if (r != null) r[2] else (screenW * 0.92f).toInt()
        val lp = overlayParams(size, size)
        lp.x = if (r != null) r[0] else (screenW - size) / 2
        lp.y = if (r != null) r[1] else screenH / 4

        val paint = Paint()
        paint.color = Color.argb(210, 0, 255, 100)
        paint.strokeWidth = 3f
        paint.style = Paint.Style.STROKE

        val root = object : FrameLayout(this) {
            override fun onDraw(canvas: Canvas) {
                val w = width.toFloat()
                val h = height.toFloat()
                for (i in 0..8) {
                    canvas.drawLine(w * i / 8f, 0f, w * i / 8f, h, paint)
                    canvas.drawLine(0f, h * i / 8f, w, h * i / 8f, paint)
                }
            }
        }
        root.setWillNotDraw(false)
        makeDraggable(root, lp)

        val handle = View(this)
        handle.setBackgroundColor(0xCC00CC66.toInt())
        val hs = (48 * d).toInt()
        root.addView(handle, FrameLayout.LayoutParams(hs, hs, Gravity.BOTTOM or Gravity.END))
        var hx = 0f
        var hy = 0f
        var startSize = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { hx = e.rawX; hy = e.rawY; startSize = lp.width }
                MotionEvent.ACTION_MOVE -> {
                    val grow = maxOf(e.rawX - hx, e.rawY - hy).toInt()
                    val ns = (startSize + grow).coerceIn((160 * d).toInt(), screenW)
                    lp.width = ns
                    lp.height = ns
                    wm.updateViewLayout(root, lp)
                }
            }
            true
        }

        wm.addView(root, lp)
        frameView = root
        setStatus("Drag the grid so it matches the 8x8 board exactly (corner square = resize). Tap Frame again to lock.")
    }

    override fun onDestroy() {
        scope.cancel()
        try { frameView?.let { wm.removeView(it) } } catch (e: Exception) { }
        try { panel?.let { wm.removeView(it) } } catch (e: Exception) { }
        try { vdisplay?.release() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        engine.stop()
        lastFrame?.recycle()
        super.onDestroy()
    }
}
