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
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

/**
 * Every way the capture path can fail, with the one sentence the panel and the app screen show for it.
 *
 * The capture modules do not share an error type: [SecureCapture] reports a [SecureCapture.Mode] and a
 * free-text [SecureCapture.lastError], [ShizukuCapture] reports its own state in words, [BoardFinder.find]
 * answers null with nothing else, and [Vision] leaves its reason in [Vision.lastProblem]. [CaptureFaults]
 * maps all of them onto this enum, and both the floating panel in [CaptureService] and the app screen in
 * [MainActivity] render [sentence] and [fix] verbatim, so one cause is never described two ways.
 */
enum class CaptureFault(
    /** what went wrong, one sentence, no jargon */
    val sentence: String,
    /** the shortest thing that actually fixes it */
    val fix: String,
    /** Retry on the panel clears it without restarting the service */
    val retryable: Boolean = true,
    /** clearing it needs a fresh Android capture consent, which only MainActivity can ask for */
    val needsConsent: Boolean = false
) {
    OVERLAY_PERMISSION(
        "Chess Reviewer isn't allowed to draw over other apps, so the panel can't appear.",
        "Open Chess Reviewer, tap 'Allow overlay' and switch it on, then tap Start again.",
        retryable = false
    ),
    NEEDS_CONSENT(
        "Screen capture needs to be allowed again.",
        "Tap Retry and accept the capture prompt. The panel stays where it is.",
        needsConsent = true
    ),
    NO_FRAMES(
        "The screen stopped sending frames.",
        "Tap Retry. If it stays empty, start capture again from the app."
    ),
    COOLING(
        "Root and Shizuku were both tried a moment ago.",
        "Tap Retry in a couple of seconds."
    ),
    SHIZUKU_NOT_INSTALLED(
        "This screen hides itself from screen capture, and Shizuku isn't installed to read it anyway.",
        "Tap Retry once Shizuku is installed, or open a screen that allows capture."
    ),
    SHIZUKU_NOT_RUNNING(
        "This screen hides itself from screen capture, and Shizuku isn't running so I can't read it.",
        "Open Shizuku, start it, then tap Retry."
    ),
    SHIZUKU_NO_PERMISSION(
        "This screen hides itself from screen capture, and this app hasn't been given Shizuku access.",
        "Open Chess Reviewer, tap 'Grant Shizuku access', then tap Retry."
    ),
    SCREEN_BLOCKED(
        "This screen hides itself from screen capture, even through root and Shizuku.",
        "Tap Retry, or open a screen that allows capture. Typing the position always works."
    ),
    BOARD_NOT_FOUND(
        "I can't find a chess board on this screen.",
        "Open the game so the whole board is visible, drag this panel off it, then tap Retry."
    ),
    BOARD_UNREADABLE(
        "I can see the board but can't read the pieces.",
        "Put the pieces in the start position and tap Learn, or tap Retry."
    ),
    UNEXPECTED(
        "The screen reader hit an error.",
        "Tap Retry. If it comes back, tap Save and send me the picture."
    );

    /**
     * What the panel prints. [detail] is the module's own sentence when it has one (a
     * [CaptureFailure.panelText], a [BoardFinder.lastProblem], a [Vision.lastProblem]); it goes first
     * because it names the exact cause, and the fix follows on its own line.
     */
    /** the panel's fix line as an action the app screen can name too, e.g. for a consent prompt */
    fun panelText(detail: String = "", action: String = fix): String {
        val head = detail.trim().ifEmpty { sentence }
        return if (head == action) head else head + "\n" + action
    }

    /** the capture faults: cleared by a readable frame, as opposed to a readable board */
    val isCapture: Boolean
        get() = this == OVERLAY_PERMISSION || this == NEEDS_CONSENT || this == NO_FRAMES ||
            this == COOLING || this == SHIZUKU_NOT_INSTALLED || this == SHIZUKU_NOT_RUNNING ||
            this == SHIZUKU_NO_PERMISSION || this == SCREEN_BLOCKED
}

/**
 * Reads what the capture modules report and answers the one [CaptureFault] that names it.
 *
 * [CaptureError] already carries a typed code per path and a [CaptureFailure.panelText] per code, so a
 * capture failure's sentence is the module's own. What this adds is the *action*: a spent token needs a
 * new consent, a stopped Shizuku needs the user to open it, a cooldown needs a couple of seconds. Used by
 * the panel and by the app screen, so a failure reads the same way and offers the same next step in both.
 */
internal object CaptureFaults {

    /** One [CaptureError] onto the action it needs. Exhaustive on purpose: a new code forces a decision. */
    fun forCaptureError(error: CaptureError): CaptureFault = when (error) {
        // the token was cancelled, revoked or already used: only a fresh consent brings capture back
        CaptureError.NO_TOKEN,
        CaptureError.TOKEN_REJECTED,
        CaptureError.TOKEN_SPENT,
        CaptureError.DISPLAY_FAILED,
        CaptureError.NO_SESSION,
        CaptureError.PROJECTION_STOPPED -> CaptureFault.NEEDS_CONSENT

        // the session is alive, the picture is not arriving
        CaptureError.FRAME_TIMEOUT,
        CaptureError.FRAME_EMPTY,
        CaptureError.FRAME_UNREADABLE -> CaptureFault.NO_FRAMES

        CaptureError.COOLING_DOWN -> CaptureFault.COOLING

        CaptureError.SHIZUKU_MISSING -> CaptureFault.SHIZUKU_NOT_INSTALLED
        CaptureError.SHIZUKU_NOT_RUNNING -> CaptureFault.SHIZUKU_NOT_RUNNING
        CaptureError.SHIZUKU_NO_PERMISSION -> CaptureFault.SHIZUKU_NO_PERMISSION

        // a route was there and still could not read the screen, or there was no route at all
        CaptureError.NO_SU,
        CaptureError.NOT_ROOT,
        CaptureError.SHELL_TIMEOUT,
        CaptureError.BAD_IMAGE,
        CaptureError.SHIZUKU_BIND_FAILED,
        CaptureError.SHIZUKU_BIND_TIMEOUT,
        CaptureError.SHIZUKU_NULL_BINDING,
        CaptureError.SHIZUKU_DEAD,
        CaptureError.SHIZUKU_NO_FRAME,
        CaptureError.SHIZUKU_UNSUPPORTED,
        CaptureError.SHIZUKU_TIMEOUT,
        CaptureError.SHIZUKU_SHORT_READ,
        CaptureError.SHIZUKU_EMPTY_FRAME,
        CaptureError.SHIZUKU_BAD_IMAGE,
        CaptureError.NO_ROUTE -> CaptureFault.SCREEN_BLOCKED
    }

    /** A [CaptureOutcome.Failed] onto the fault and the sentence the module wrote for it. */
    fun forFailure(failure: CaptureFailure): Pair<CaptureFault, String> =
        forCaptureError(failure.error) to failure.panelText()

    /** [BoardFinder.find] answered null: its own sentence says which part of the search failed. */
    fun forMissingBoard(problem: String): Pair<CaptureFault, String> =
        CaptureFault.BOARD_NOT_FOUND to problem

    /** [Vision.readPosition] answered null: its reason is the detail under the sentence. */
    fun forUnreadableBoard(problem: String): Pair<CaptureFault, String> =
        CaptureFault.BOARD_UNREADABLE to problem

    /** [Vision.learn] answered null: its own message is more precise than any enum, so keep it. */
    fun forLearnFailure(message: String?): Pair<CaptureFault, String> =
        CaptureFault.BOARD_UNREADABLE to (message ?: "")
}

class CaptureService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var engine: StockfishEngine
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val tracker = Tracker()

    // the one failure showing on the panel, its detail line, and whether it has already been toasted
    @Volatile private var fault: CaptureFault? = null
    @Volatile private var faultDetail = ""
    private var toastedFault: CaptureFault? = null

    // engine strength and pacing, shared with MainActivity through the "chess" preferences
    private val rng = Random(System.currentTimeMillis())
    @Volatile private var elo: Int? = null
    @Volatile private var humanPace = true

    // the move showing on the panel, and when a person would plausibly play it
    @Volatile private var playText: String? = null
    @Volatile private var playAt = 0L
    @Volatile private var playWindow = 0L..0L

    /**
     * The MediaProjection half of the capture path. It owns the token, the virtual display and the
     * reader, because Android 14 lets one token back one display and refuses a second: resizing and
     * replacing a dead token are its business, not this service's.
     */
    private var session: ProjectionSession? = null
    private var screenW = 0
    private var screenH = 0
    private var started = false
    /** when the board first went missing, so a search in progress is not reported as a failure */
    private var boardMissSince = 0L

    // reads screens that FLAG_SECURE turns black for the ordinary capture path
    private lateinit var secure: SecureCapture
    private lateinit var shizuku: ShizukuCapture
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
    private var hintView: TextView? = null
    private var retryView: Button? = null
    private var frameView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("chess", MODE_PRIVATE)
        engine = StockfishEngine(this)
        secure = SecureCapture(this, cacheDir)
        shizuku = ShizukuCapture(applicationContext)
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
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")

        if (started) {
            // A retry from the panel or a fresh consent from MainActivity: swap only the capture
            // session. The panel, the learned piece set and the engine stay exactly as they are.
            if (data != null) restartCapture(code, data)
            return START_NOT_STICKY
        }
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
                    setFault(CaptureFault.UNEXPECTED, e.message ?: e.javaClass.simpleName)
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
        engine.configure(elo, if (humanPace) Levels.multiPvFor(elo) else 1)
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

    /**
     * Opens the capture session from the result of the screen-capture dialog. [ProjectionSession.start]
     * answers null on success, or the typed reason it failed, and each reason names the panel action.
     */
    private fun setupCapture(code: Int, data: Intent) {
        val created = try {
            ProjectionSession(this)
        } catch (e: Exception) {
            setFault(CaptureFault.NEEDS_CONSENT, e.message ?: e.javaClass.simpleName)
            return
        }
        session = created
        created.onStopped = {
            // The user revoked capture, or Android ended the session: name it, keep the panel, and let
            // Retry ask for a fresh consent and swap the session underneath.
            setFault(CaptureFault.NEEDS_CONSENT)
        }
        val failure = created.start(code, data)
        if (failure != null) {
            val (fault, detail) = CaptureFaults.forFailure(failure)
            setFault(fault, detail)
            return
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        clearFault()
    }

    /** Ends the capture session; the panel, the learned piece set and the engine settings survive. */
    private fun releaseCapture() {
        val s = session
        session = null
        try { s?.release() } catch (e: Exception) { }
    }

    /** A new consent token from MainActivity, applied without restarting the service. */
    private fun restartCapture(code: Int, data: Intent) {
        releaseCapture()
        secureMode = false
        lastSecureTry = 0L
        boardMissSince = 0L
        lastDetect = 0L
        reprocess = true
        setupCapture(code, data)
    }

    /**
     * A rotation or a resized display. The same token resizes the display it already backed; asking for a
     * second display on it is what Android 14 refuses, so this goes through the session's own resize.
     */
    private fun syncScreenSize() {
        val s = session ?: return
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels == screenW && dm.heightPixels == screenH) return
        val failure = s.ensureSize(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        if (failure != null) {
            val (fault, detail) = CaptureFaults.forFailure(failure)
            setFault(fault, detail)
            return
        }
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        boardRect = null
        boardMissSince = 0L
        lastDetect = 0L
        reprocess = true
        setStatus("The screen changed size; looking for the board again…")
    }

    // ------------------------------------------------------------------ failures

    /**
     * Puts one cause on the panel, once per change. The first line is the module's own sentence for what
     * went wrong (a [CaptureFailure.panelText], a [BoardFinder.lastProblem], a [Vision.lastProblem]), so
     * the panel never says "can't see the board" when it can say which part failed; the second line is
     * the fix. A Toast repeats the sentence for the case where the panel itself cannot be seen.
     */
    private fun setFault(f: CaptureFault, detail: String = "") {
        val changed = fault != f || (detail.isNotBlank() && detail != faultDetail)
        if (!changed) return
        fault = f
        faultDetail = detail
        lastFault = f
        val text = f.panelText(detail)
        handler.post {
            statusView?.text = text
            updateFaultUi()
        }
        if (toastedFault != f) {
            toastedFault = f
            handler.post {
                try { Toast.makeText(this, f.sentence, Toast.LENGTH_LONG).show() } catch (e: Exception) { }
            }
        }
    }

    private fun clearFault() {
        if (fault == null) return
        fault = null
        faultDetail = ""
        toastedFault = null
        lastFault = null
        handler.post { updateFaultUi() }
    }

    /** a readable frame clears the capture causes only: it does not clear "board not recognised" */
    private fun clearCaptureFault() {
        if (fault?.isCapture == true) clearFault()
    }

    private fun updateFaultUi() {
        val f = fault
        hintView?.apply {
            visibility = if (f == null) View.GONE else View.VISIBLE
            text = f?.fix.orEmpty()
        }
        retryView?.visibility = if (f != null && f.retryable) View.VISIBLE else View.GONE
    }

    /**
     * Puts the panel up when it could not be added before, which is what a missing overlay permission
     * used to need a stop and a start for. Nothing else about the service changes: the capture session,
     * the learned piece set and the engine stay as they are.
     */
    private fun ensurePanel() {
        if (panel != null) return
        if (!Settings.canDrawOverlays(this)) return
        buildPanel()
        if (panel != null && fault == CaptureFault.OVERLAY_PERMISSION) {
            clearFault()
            setStatus("Overlay permission granted. The panel is up; open your game and play.")
        }
    }

    /**
     * The panel's Retry. Everything that is still working is left alone: the learned piece set, the
     * engine, the panel position and the running service. Only the part that failed is re-tried.
     */
    private fun retry() {
        val f = fault ?: return
        when {
            f == CaptureFault.OVERLAY_PERMISSION -> {
                openOverlaySettings()
                return
            }
            f.needsConsent -> {
                // Only an Activity can raise the system dialog. MainActivity asks, and hands the token
                // back through onStartCommand so this service swaps the session in place.
                setStatus("Waiting for you to allow screen capture in Chess Reviewer…")
                requestConsent()
                return
            }
            f == CaptureFault.SHIZUKU_NOT_INSTALLED ||
                f == CaptureFault.SHIZUKU_NOT_RUNNING ||
                f == CaptureFault.SHIZUKU_NO_PERMISSION ||
                f == CaptureFault.SCREEN_BLOCKED ||
                f == CaptureFault.COOLING -> {
                // A fresh SecureCapture drops the module's own retry cooldown, so Retry means now.
                secure = SecureCapture(this, cacheDir)
                shizuku = ShizukuCapture(applicationContext)
                secureMode = false
                lastSecureTry = 0L
                reprocess = true
            }
            f == CaptureFault.NO_FRAMES -> {
                // The session is alive, the picture is not arriving: let the loop poll it again.
                reprocess = true
            }
            f == CaptureFault.UNEXPECTED -> {
                // Something threw inside the loop; the next tick is the retry.
                reprocess = true
            }
            f == CaptureFault.BOARD_NOT_FOUND || f == CaptureFault.BOARD_UNREADABLE -> {
                boardMissSince = 0L
                lastDetect = 0L
                lastLearnTry = 0L
                reprocess = true
                if (templates == null) learnRequested = true
            }
        }
        clearFault()
        setStatus("Retrying…")
    }

    /** where the overlay permission is switched on; only the system can grant it */
    private fun openOverlaySettings() {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (e: Exception) {
            setStatus(CaptureFault.OVERLAY_PERMISSION.panelText(e.message ?: ""))
        }
    }

    /** asks MainActivity for a fresh capture consent without stopping the service */
    private fun requestConsent() {
        val i = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_RECONSENT, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(i)
        } catch (e: Exception) {
            setFault(CaptureFault.UNEXPECTED, "could not open Chess Reviewer to ask: " + e.message)
        }
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

    /**
     * Picks the pixels to analyse.
     *
     * A window that sets FLAG_SECURE comes back solid black from MediaProjection, so a black frame is
     * thrown away and the same screen is read again through root `screencap`, or through Shizuku when the
     * phone is not rooted. If the screen becomes capturable again the normal path takes over on the next
     * frame. Every way out ends on a [CaptureFault], so the panel names which route failed and why.
     */
    private fun resolveFrame(): Frame? {
        var projectionFailure: CaptureFailure? = null
        val s = session
        if (s != null) {
            when (val outcome = s.frame(FRAME_WAIT_MS)) {
                is CaptureOutcome.Frame -> {
                    if (!looksBlocked(outcome.bitmap)) {
                        if (secureMode) {
                            secureMode = false
                            setStatus("Screen capture is working again.")
                        }
                        clearCaptureFault()
                        // a still screen comes back as the last buffer with fresh = false: the position
                        // has not changed, so it is not read again on every tick
                        return Frame(outcome.bitmap, outcome.fresh)
                    }
                    // black: the layer is secure, so a different route has to supply the pixels
                    outcome.bitmap.recycle()
                }
                is CaptureOutcome.Failed -> projectionFailure = outcome.failure
            }
        }

        // between root reads: keep showing the last readable frame instead of flickering
        if (secureMode && SystemClock.uptimeMillis() - lastSecureTry < SECURE_POLL_MS) {
            lastFrame?.let { return Frame(it, false) }
        }

        when (val shot = secureShotClean()) {
            is CaptureOutcome.Frame -> {
                if (!looksBlocked(shot.bitmap)) {
                    secureMode = true
                    clearCaptureFault()
                    return Frame(shot.bitmap, true)
                }
                // a route answered and the screen is still black: DRM, not a missing permission
                shot.bitmap.recycle()
                secureMode = false
                setFault(CaptureFault.SCREEN_BLOCKED, BLACK_EVEN_THROUGH_ROOT)
            }
            is CaptureOutcome.Failed -> {
                secureMode = false
                val (fault, detail) = CaptureFaults.forFailure(shot.failure)
                setFault(fault, detail)
            }
        }

        // the secure route has spoken; only mention the projection if it said nothing at all
        if (fault == null && projectionFailure != null) {
            val (fault, detail) = CaptureFaults.forFailure(projectionFailure)
            setFault(fault, detail)
        }
        return lastFrame?.let { Frame(it, false) }
    }

    /**
     * Root or Shizuku screenshot of the display, throttled to one per SECURE_POLL_MS. The typed reason
     * comes back with the failure, so the panel can print it instead of "no frame".
     */
    private fun secureShotClean(): CaptureOutcome {
        val now = SystemClock.uptimeMillis()
        if (now - lastSecureTry < SECURE_POLL_MS) {
            return CaptureOutcome.Failed(
                CaptureFailure(CaptureError.COOLING_DOWN, "waiting for the next try")
            )
        }
        lastSecureTry = now
        return secure.capture(screenW, screenH)
    }

    private fun tick() {
        // The overlay permission can be granted after the service started, when the panel could not be
        // added at all: put it up now rather than making the user stop and start.
        ensurePanel()
        refreshPlayStatus()
        syncScreenSize()
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

        // [CaptureService.Frame.fresh] is the module's own flag: a still screen answers with the last
        // frame it decoded, flagged fresh = false, because nothing on it has changed. Honour it rather
        // than assuming every frame carries new pixels, but only after the buttons that work on the
        // pixels in front of the user now: on a still board Save, Learn and Turn are what gets tapped.
        val frame = picked.bitmap
        val newFrame = picked.fresh
        frameFresh = newFrame || reprocess

        if (saveRequested) {
            saveRequested = false
            saveFrame(frame)
            return
        }

        if (learnRequested) {
            learnRequested = false
            val found = detectBoard(frame)
            if (found == null) {
                val (fault, detail) = CaptureFaults.forMissingBoard(BoardFinder.lastProblem)
                setFault(fault, detail)
                return
            }
            val rect = setRect(found)
            val bpx = cropBoard(frame, rect)
            if (bpx == null) {
                setFault(
                    CaptureFault.BOARD_UNREADABLE,
                    "The board runs off the edge of the screen, so only part of it can be read."
                )
                return
            }
            val res = Vision.learn(Vision.downscale(bpx, rect[2]))
            val learned = res.templates
            if (learned == null) {
                val (fault, detail) = CaptureFaults.forLearnFailure(res.error)
                setFault(fault, detail)
            } else {
                templates = learned
                whiteAtBottom = res.whiteBottom
                prefs.edit().putString("tpl", learned.serialize()).putBoolean("whiteBottom", res.whiteBottom).apply()
                resetRequested = true
                clearFault()
                setStatus("Learned! White is at the " + (if (res.whiteBottom) "bottom" else "top") + ". Now play.")
            }
            return
        }

        // Turn needs no pixels at all: it flips which side is to move and asks the engine again about
        // the position already read, so it runs before the freshness gate.
        if (turnFlipRequested) {
            turnFlipRequested = false
            tracker.blackToMove = !tracker.blackToMove
            if (stablePos != null) runEngine()
        }

        // Not fresh: the same pixels as last tick, and a still board cannot have moved or changed, so
        // there is nothing to crop, read or solve and the engine is not asked the same question again.
        // The move already on the panel stays there instead of being blanked. A reset, a flip, a resize
        // or a re-detected board sets reprocess, which is what lets a read run on unchanged pixels.
        if (!frameFresh) return

        val tpl = templates
        if (tpl == null) {
            if (newFrame) {
                setFault(
                    CaptureFault.BOARD_UNREADABLE,
                    "No piece set has been learned yet, and the start position is not on screen."
                )
            }
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
            // a search in progress is not a failure: only say so once the hunt has had a few tries
            val now = SystemClock.uptimeMillis()
            if (boardMissSince == 0L) boardMissSince = now
            if (newFrame) {
                if (now - boardMissSince < BOARD_SEARCH_GRACE_MS) {
                    setStatus("Looking for the chess board on screen…")
                } else {
                    val (fault, detail) = CaptureFaults.forMissingBoard(BoardFinder.lastProblem)
                    setFault(fault, detail)
                }
            }
            return
        }
        boardMissSince = 0L

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
                val (fault, detail) = CaptureFaults.forUnreadableBoard(Vision.lastProblem)
                setFault(fault, detail)
            } else if (obs != null) {
                clearFault()
            }
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
        engine.configure(elo, if (humanPace) Levels.multiPvFor(elo) else 1)
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
        return if (step.usesSkill) {
            "Engine at about ${step.elo} Elo (Skill Level ${step.skill}, ${step.nodes} nodes)."
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

        // the fix line for whatever is currently failing, and the button that re-tries it
        val hint = TextView(this)
        hint.setTextColor(0xFFFFC06A.toInt())
        hint.textSize = 12f
        hint.visibility = View.GONE
        hintView = hint
        root.addView(hint)

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
        // only visible while something is failing, so it never takes room from the usual buttons
        val retryBtn = addButton(row2, "Retry") { retry() }
        retryBtn.visibility = View.GONE
        retryView = retryBtn

        // The token can have failed before any sizes were read, so the panel takes its size from the
        // display itself when that happened: a zero-width panel is invisible, and that read as "no
        // panel at all" on top of whatever the real cause was.
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val sw = if (screenW > 0) screenW else dm.widthPixels
        val sh = if (screenH > 0) screenH else dm.heightPixels
        val w = (sw * 0.94f).toInt()
        val lp = overlayParams(w, WindowManager.LayoutParams.WRAP_CONTENT)
        lp.x = (sw - w) / 2
        lp.y = (sh - 250 * d).toInt()
        makeDraggable(root, lp)
        try {
            wm.addView(root, lp)
            panel = root
        } catch (e: Exception) {
            // No "display over other apps": the panel can never appear, and that is the cause to name.
            // The service keeps reading the screen, so granting it later needs no restart.
            panel = null
            setFault(CaptureFault.OVERLAY_PERMISSION, e.message ?: e.javaClass.simpleName)
        }

        if (templates != null) {
            setStatus("Piece set already learned. Open a game and play; the board is found automatically.")
        }
        updateFaultUi()
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

        try {
            wm.addView(root, lp)
            frameView = root
        } catch (e: Exception) {
            // the same missing overlay permission: name it rather than throwing out of the panel
            frameView = null
            setFault(CaptureFault.OVERLAY_PERMISSION, e.message ?: e.javaClass.simpleName)
            return
        }
        setStatus("Drag the grid so it matches the 8x8 board exactly (corner square = resize). Tap Frame again to lock.")    }

    override fun onDestroy() {
        scope.cancel()
        try { frameView?.let { wm.removeView(it) } } catch (e: Exception) { }
        try { panel?.let { wm.removeView(it) } } catch (e: Exception) { }
        releaseCapture()
        engine.stop()
        lastFrame?.recycle()
        lastFault = null
        super.onDestroy()
    }

    companion object {
        /**
         * The failure the panel is showing right now, so [MainActivity] can repeat the same sentence on
         * its own screen (the panel is invisible when overlay permission is what failed).
         */
        @Volatile var lastFault: CaptureFault? = null
            private set

        /** how long one frame request waits before it counts as "nothing arrived" */
        private const val FRAME_WAIT_MS = 200L

        /** a board that has not been found for this long is a failure, not a search in progress */
        private const val BOARD_SEARCH_GRACE_MS = 4_000L

        private const val BLACK_EVEN_THROUGH_ROOT =
            "Root and Shizuku both read this screen and both got black pixels, which is what a DRM video does."
    }
}
