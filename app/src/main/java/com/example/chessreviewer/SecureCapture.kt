package com.example.chessreviewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Every reason a frame request can come back empty: one code per path, so the panel can print
 * something specific instead of "no frame".
 */
enum class CaptureError {
    /** No consent result at all: the screen-capture dialog was cancelled or never shown. */
    NO_TOKEN,

    /** The system refused the token handed back by the dialog (revoked, or already used once). */
    TOKEN_REJECTED,

    /** createVirtualDisplay refused the token: it has already backed one display. */
    TOKEN_SPENT,

    /** The token was fine but no virtual display came back. */
    DISPLAY_FAILED,

    /** The session was never opened, so there is no reader to ask. */
    NO_SESSION,

    /** The reader exists but the deadline passed with no image in it. */
    FRAME_TIMEOUT,

    /** The system handed back an image with zero width or height, or a zero-byte buffer. */
    FRAME_EMPTY,

    /** Pixels arrived but could not be copied into a bitmap. */
    FRAME_UNREADABLE,

    /** The user or the system stopped the projection: a new consent is needed. */
    PROJECTION_STOPPED,

    /** Root route: there is no su binary on this phone. */
    NO_SU,

    /** Root route: su answered but not as uid 0. */
    NOT_ROOT,

    /** Root route: screencap did not finish inside its deadline. */
    SHELL_TIMEOUT,

    /** Root route: the bytes were not a decodable PNG. */
    BAD_IMAGE,

    /** Shizuku is not installed. */
    SHIZUKU_MISSING,

    /** Shizuku is installed but its service is not running. */
    SHIZUKU_NOT_RUNNING,

    /** Shizuku is running but this app was never granted access. */
    SHIZUKU_NO_PERMISSION,

    /** Binding Shizuku's user service threw. */
    SHIZUKU_BIND_FAILED,

    /** Shizuku accepted the bind but the service never connected inside the deadline. */
    SHIZUKU_BIND_TIMEOUT,

    /** Shizuku started the process but it handed back no binder (stale service code). */
    SHIZUKU_NULL_BINDING,

    /** The bound Shizuku process died: its binder is gone. */
    SHIZUKU_DEAD,

    /** The Shizuku capture returned a negative code. */
    SHIZUKU_NO_FRAME,

    /** The Shizuku service cannot capture secure layers on this Android version. */
    SHIZUKU_UNSUPPORTED,

    /** The Shizuku service ran out of its own deadline. */
    SHIZUKU_TIMEOUT,

    /** The Shizuku frame arrived shorter than its stated size. */
    SHIZUKU_SHORT_READ,

    /** The Shizuku frame was empty or zero-sized. */
    SHIZUKU_EMPTY_FRAME,

    /** The Shizuku bytes were not a decodable image. */
    SHIZUKU_BAD_IMAGE,

    /** Neither root nor Shizuku could read this screen. */
    NO_ROUTE,

    /** Both routes were tried a moment ago; waiting before trying again. */
    COOLING_DOWN
}

/**
 * A failed frame request: a code, and a line a person can read. Never null and never blank, so
 * every caller can print something useful. [panelText] is the line for the overlay panel.
 */
class CaptureFailure(val error: CaptureError, val detail: String = "") {

    /** Code and detail on one line, for logs and for a bug report. */
    val message: String
        get() = if (detail.isBlank()) error.name else error.name + ": " + detail

    /** One sentence for a person, with the code kept at the end for a bug report. */
    fun panelText(): String {
        val lead = when (error) {
            CaptureError.NO_TOKEN,
            CaptureError.TOKEN_REJECTED,
            CaptureError.TOKEN_SPENT,
            CaptureError.DISPLAY_FAILED,
            CaptureError.NO_SESSION,
            CaptureError.PROJECTION_STOPPED -> "Screen capture needs to be allowed again."
            CaptureError.FRAME_TIMEOUT -> "The screen stopped sending frames."
            CaptureError.FRAME_EMPTY -> "The screen sent an empty frame."
            CaptureError.FRAME_UNREADABLE -> "The screen frame could not be read."
            CaptureError.NO_SU, CaptureError.NOT_ROOT -> "There is no root shell on this phone."
            CaptureError.SHELL_TIMEOUT -> "screencap did not answer in time."
            CaptureError.BAD_IMAGE -> "The screenshot was not a readable image."
            CaptureError.SHIZUKU_MISSING -> "Shizuku is not installed on this phone."
            CaptureError.SHIZUKU_NOT_RUNNING -> "Shizuku is installed but not running."
            CaptureError.SHIZUKU_NO_PERMISSION -> "Shizuku access has not been granted to this app."
            CaptureError.SHIZUKU_BIND_FAILED,
            CaptureError.SHIZUKU_BIND_TIMEOUT,
            CaptureError.SHIZUKU_NULL_BINDING -> "Shizuku would not start its capture service."
            CaptureError.SHIZUKU_DEAD -> "The Shizuku capture service stopped."
            CaptureError.SHIZUKU_NO_FRAME,
            CaptureError.SHIZUKU_EMPTY_FRAME -> "Shizuku returned an empty frame."
            CaptureError.SHIZUKU_UNSUPPORTED -> "This Android version cannot read protected screens through Shizuku."
            CaptureError.SHIZUKU_TIMEOUT -> "The Shizuku capture did not finish in time."
            CaptureError.SHIZUKU_SHORT_READ -> "The Shizuku frame arrived incomplete."
            CaptureError.SHIZUKU_BAD_IMAGE -> "The Shizuku frame was not a readable image."
            CaptureError.NO_ROUTE -> "This screen blocks capture and neither root nor Shizuku could read it."
            CaptureError.COOLING_DOWN -> "Waiting a moment before trying root and Shizuku again."
        }
        return if (detail.isBlank()) lead + " [" + error.name + "]"
        else lead + " " + detail + " [" + error.name + "]"
    }

    override fun toString(): String = message
}

/** A frame, or the reason there is none. Never an empty bitmap. */
sealed class CaptureOutcome {

    enum class Source { PROJECTION, ROOT, SHIZUKU }

    /**
     * Pixels. The bitmap always belongs to the caller, which recycles it.
     *
     * [fresh] is false when the session answered with the last frame it decoded because the screen
     * has not been repainted since: Android only hands a virtual display a new buffer when the
     * content changes, so a still screen looks exactly like a dead one. A caller can use it to skip
     * re-reading a position it has already read.
     */
    class Frame(val bitmap: Bitmap, val source: Source, val fresh: Boolean = true) : CaptureOutcome()

    class Failed(val failure: CaptureFailure) : CaptureOutcome()
}

/**
 * The MediaProjection half of the capture path: one token, one virtual display, one reader,
 * kept alive for the whole service session.
 *
 * Three platform rules shape this class, and each one used to cost a frame:
 *
 *  1. The callback has to be registered *before* createVirtualDisplay, or Android 14 and newer
 *     throw IllegalStateException. [start] registers first, on purpose.
 *  2. A token backs one virtual display. Asking the same token for a second one throws, so a
 *     size change resizes the existing display and swaps its surface, and only a brand-new
 *     consent can replace a dead token ([needsConsent] says when).
 *  3. acquireNextImage() with no timeout blocks forever. [frame] polls acquireLatestImage()
 *     against a deadline instead, so a screen that stops producing frames returns
 *     [CaptureError.FRAME_TIMEOUT] instead of hanging the analysis loop.
 *
 * Nothing here touches the UI. [start], [ensureSize] and [frame] may be called from any
 * thread; a frame request while another is in flight waits its turn.
 */
class ProjectionSession(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    // Read from the analysis thread while the main thread stops or resizes the session.
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var display: VirtualDisplay? = null

    /**
     * The last frame this session decoded, in the session's own pixels: the pixels handed to a
     * caller are a copy, so the caller is free to recycle them. This is what answers a request
     * made while the screen is standing still.
     */
    private var cached: Bitmap? = null

    private var width = 0
    private var height = 0
    private var density = 0

    /** True when only a fresh consent Intent can bring capture back. */
    @Volatile var needsConsent = false
        private set

    @Volatile var lastFailure: CaptureFailure? = null
        private set

    /** Called when the user or the system stops the projection. Runs on the main thread. */
    @Volatile var onStopped: (() -> Unit)? = null

    val isRunning: Boolean
        get() = projection != null && reader != null && display != null

    /**
     * Opens a session from the result the screen-capture dialog handed back. Returns null on
     * success, or the typed reason it failed; such a failure always sets [needsConsent], so the
     * caller knows to show the dialog again.
     */
    fun start(resultCode: Int, data: Intent?): CaptureFailure? {
        release()
        if (data == null) return fail(CaptureError.NO_TOKEN, "the screen-capture dialog returned no data")
        val manager = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return fail(CaptureError.NO_TOKEN, "this phone has no MediaProjectionManager")
        val token = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            return fail(CaptureError.TOKEN_REJECTED, describe(t))
        }
        if (token == null) return fail(CaptureError.TOKEN_REJECTED, "getMediaProjection returned null")

        // Rule 1: the callback goes on before the display is asked for.
        try {
            token.registerCallback(callback, main)
        } catch (t: Throwable) {
            try { token.stop() } catch (ignored: Throwable) { }
            return fail(CaptureError.TOKEN_REJECTED, "could not register the projection callback: " + describe(t))
        }

        val metrics = screenMetricsOf(app)
        synchronized(lock) {
            projection = token
            width = metrics[0]
            height = metrics[1]
            density = metrics[2]
            needsConsent = false
            lastFailure = null
        }
        return openDisplay()
    }

    /**
     * Keeps the display alive across a rotation, a split-screen resize or a resolution change.
     * Returns null when the session already has the right size, or the typed reason it could
     * not be resized (which means a new consent).
     */
    fun ensureSize(w: Int, h: Int, dpi: Int): CaptureFailure? {
        if (w <= 0 || h <= 0) return fail(CaptureError.DISPLAY_FAILED, "asked for a " + w + "x" + h + " display")
        val actualDpi = if (dpi > 0) dpi else density
        synchronized(lock) {
            if (reader != null && display != null && w == width && h == height) return null
            val current = display
            val token = projection
            if (current == null || token == null) {
                width = w
                height = h
                if (actualDpi > 0) density = actualDpi
                return openDisplay()
            }
            // Rule 2: resize and re-point the existing display; a second createVirtualDisplay
            // on the same token is refused.
            val fresh = newReader(w, h)
            return try {
                current.resize(w, h, actualDpi)
                current.surface = fresh.surface
                closeReader()
                // The cached frame is the old size: answering a request with it after a resize
                // would hand the caller pixels that no longer line up with the screen.
                forgetCache()
                reader = fresh
                width = w
                height = h
                density = actualDpi
                null
            } catch (t: Throwable) {
                try { fresh.close() } catch (ignored: Throwable) { }
                fail(CaptureError.TOKEN_SPENT, "the display refused a resize: " + describe(t))
            }
        }
    }

    /**
     * The freshest frame, waiting at most [timeoutMs] for the screen to send a new one.
     *
     * A screen that is standing still sends nothing: the virtual display is only given a buffer
     * when the content changes. Treating that as a failure made a working capture look like a dead
     * one, so when the deadline passes the session answers with a copy of the last frame it
     * decoded, flagged `fresh = false`. Only a session with no frame at all fails, and then with a
     * typed reason. This never returns an empty bitmap and never waits longer than [timeoutMs].
     */
    fun frame(timeoutMs: Long): CaptureOutcome {
        val wait = timeoutMs.coerceIn(1L, MAX_FRAME_WAIT_MS)
        val deadline = SystemClock.uptimeMillis() + wait
        var readFailure: CaptureFailure? = null
        while (true) {
            val current = reader
            if (current == null) {
                return CaptureOutcome.Failed(
                    lastFailure ?: CaptureFailure(CaptureError.NO_SESSION, "the capture session is not open")
                )
            }
            val image: Image? = try {
                current.acquireLatestImage()
            } catch (t: Throwable) {
                // A reader swapped out by a resize, or closed under us, throws here. Keep asking
                // until the deadline instead of reporting a dead screen on one bad poll.
                readFailure = CaptureFailure(CaptureError.FRAME_UNREADABLE, describe(t))
                null
            }
            if (image != null) {
                return try {
                    toBitmap(image)
                } finally {
                    try { image.close() } catch (ignored: Throwable) { }
                }
            }
            if (!isRunning) return CaptureOutcome.Failed(stoppedFailure())
            if (SystemClock.uptimeMillis() >= deadline) return stillScreen(wait, readFailure)
            sleepQuietly(FRAME_POLL_MS)
        }
    }

    /** The pixels of the last frame, or null. Convenience for callers that only want pixels. */
    fun grab(timeoutMs: Long): Bitmap? = when (val outcome = frame(timeoutMs)) {
        is CaptureOutcome.Frame -> outcome.bitmap
        is CaptureOutcome.Failed -> null
    }

    /** Releases everything and stops the projection. Safe to call twice. */
    fun release() {
        val token: MediaProjection?
        synchronized(lock) {
            try { display?.release() } catch (ignored: Throwable) { }
            display = null
            closeReader()
            forgetCache()
            token = projection
            projection = null
        }
        try { token?.unregisterCallback(callback) } catch (ignored: Throwable) { }
        try { token?.stop() } catch (ignored: Throwable) { }
    }

    // ------------------------------------------------------------------ internals

    /**
     * The deadline passed with no new buffer. That is what a screen which has not changed looks
     * like, so hand back the last frame as a fresh copy (the caller recycles it) rather than
     * failing a capture that is working. With nothing cached the request really is empty.
     */
    private fun stillScreen(timeoutMs: Long, readFailure: CaptureFailure?): CaptureOutcome {
        val copy = copyOfCached()
        if (copy != null) return CaptureOutcome.Frame(copy, CaptureOutcome.Source.PROJECTION, false)
        val f = readFailure ?: CaptureFailure(CaptureError.FRAME_TIMEOUT, "no frame in " + timeoutMs + " ms")
        lastFailure = f
        return CaptureOutcome.Failed(f)
    }

    /** A copy of the cached frame, or null when there is none left to copy. */
    private fun copyOfCached(): Bitmap? {
        val source = synchronized(lock) { cached } ?: return null
        val copy = try {
            if (source.isRecycled) null else source.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            null
        }
        if (copy == null) {
            synchronized(lock) {
                if (cached === source) {
                    try { cached?.recycle() } catch (ignored: Throwable) { }
                    cached = null
                }
            }
        }
        return copy
    }

    /**
     * Keeps a private copy of [bitmap] as the frame a later request can reuse. A copy, not the
     * bitmap itself: the caller owns and recycles the one it was given, and a cache pointing at
     * recycled pixels would fail the request after it.
     */
    private fun remember(bitmap: Bitmap) {
        val snapshot = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            null
        } ?: return
        val previous = synchronized(lock) {
            val old = cached
            cached = snapshot
            old
        }
        if (previous !== null && previous !== snapshot) {
            try { previous.recycle() } catch (ignored: Throwable) { }
        }
    }

    /** Drops the cached frame: the session is closing, or the screen changed size under it. */
    private fun forgetCache() {
        val old = synchronized(lock) {
            val held = cached
            cached = null
            held
        }
        try { old?.recycle() } catch (ignored: Throwable) { }
    }

    private fun openDisplay(): CaptureFailure? {
        val token = projection
            ?: return fail(CaptureError.NO_SESSION, "no projection token")
        if (width <= 0 || height <= 0 || density <= 0) {
            return fail(
                CaptureError.DISPLAY_FAILED,
                "screen size came back as " + width + "x" + height + " @" + density + "dpi"
            )
        }
        val fresh = newReader(width, height)
        val made = try {
            token.createVirtualDisplay(
                "chess-capture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, fresh.surface, null, main
            )
        } catch (t: Throwable) {
            try { fresh.close() } catch (ignored: Throwable) { }
            return fail(CaptureError.TOKEN_SPENT, describe(t))
        }
        if (made == null) {
            try { fresh.close() } catch (ignored: Throwable) { }
            return fail(CaptureError.DISPLAY_FAILED, "the system refused the virtual display")
        }
        synchronized(lock) {
            closeReader()
            reader = fresh
            display = made
        }
        return null
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, IMAGE_BUFFER_COUNT)

    private fun closeReader() {
        try { reader?.close() } catch (ignored: Throwable) { }
        reader = null
    }

    private fun toBitmap(image: Image): CaptureOutcome {
        if (image.width <= 0 || image.height <= 0) {
            return empty(CaptureError.FRAME_EMPTY, "the system handed back a " + image.width + "x" + image.height + " frame")
        }
        val plane = image.planes.firstOrNull()
            ?: return empty(CaptureError.FRAME_EMPTY, "the frame had no pixel plane")
        val buffer = plane.buffer
        if (!buffer.hasRemaining()) {
            return empty(CaptureError.FRAME_EMPTY, "the frame had an empty pixel buffer")
        }
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride < image.width * pixelStride) {
            return empty(
                CaptureError.FRAME_UNREADABLE,
                "stride " + rowStride + " is too small for " + image.width + " pixels"
            )
        }
        val paddingPx = rowStride / pixelStride - image.width
        val padded = try {
            Bitmap.createBitmap(image.width + paddingPx, image.height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            return empty(
                CaptureError.FRAME_UNREADABLE,
                "could not allocate a " + image.width + "x" + image.height + " bitmap: " + describe(t)
            )
        }
        try {
            padded.copyPixelsFromBuffer(buffer)
        } catch (t: Throwable) {
            padded.recycle()
            return empty(CaptureError.FRAME_UNREADABLE, "could not copy the frame pixels: " + describe(t))
        }
        if (paddingPx == 0) {
            remember(padded)
            return CaptureOutcome.Frame(padded, CaptureOutcome.Source.PROJECTION)
        }
        val exact = try {
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        } catch (t: Throwable) {
            padded.recycle()
            return empty(CaptureError.FRAME_UNREADABLE, "could not trim the frame padding: " + describe(t))
        }
        if (exact !== padded) padded.recycle()
        if (exact.width <= 0 || exact.height <= 0) {
            val w = exact.width
            val h = exact.height
            exact.recycle()
            return empty(CaptureError.FRAME_EMPTY, "the trimmed frame was " + w + "x" + h)
        }
        lastFailure = null
        remember(exact)
        return CaptureOutcome.Frame(exact, CaptureOutcome.Source.PROJECTION)
    }

    private fun empty(code: CaptureError, detail: String): CaptureOutcome {
        val f = CaptureFailure(code, detail)
        lastFailure = f
        return CaptureOutcome.Failed(f)
    }

    private fun stoppedFailure(): CaptureFailure {
        return lastFailure ?: CaptureFailure(CaptureError.PROJECTION_STOPPED, "the screen capture was stopped")
    }

    private fun fail(code: CaptureError, detail: String): CaptureFailure {
        val f = CaptureFailure(code, detail)
        lastFailure = f
        if (code == CaptureError.NO_TOKEN || code == CaptureError.TOKEN_REJECTED ||
            code == CaptureError.TOKEN_SPENT || code == CaptureError.DISPLAY_FAILED
        ) {
            needsConsent = true
        }
        return f
    }

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The token is dead from here on; the display and the reader go with it.
            synchronized(lock) {
                try { display?.release() } catch (ignored: Throwable) { }
                display = null
                closeReader()
                forgetCache()
                projection = null
                needsConsent = true
                lastFailure = CaptureFailure(CaptureError.PROJECTION_STOPPED, "the screen capture was stopped")
            }
            try { onStopped?.invoke() } catch (ignored: Throwable) { }
        }
    }

    companion object {
        private const val IMAGE_BUFFER_COUNT = 3
        private const val FRAME_POLL_MS = 4L
        private const val MAX_FRAME_WAIT_MS = 8_000L

        private fun sleepQuietly(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun describe(t: Throwable): String {
            var e: Throwable? = t
            var guard = 0
            while (e?.cause != null && e.cause !== e && guard < 6) {
                e = e.cause
                guard++
            }
            val real = e ?: t
            val name = real.javaClass.simpleName
            val message = real.message
            return if (message.isNullOrBlank()) name else name + ": " + message
        }

        @Suppress("DEPRECATION")
        internal fun screenMetricsOf(context: Context): IntArray {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return intArrayOf(0, 0, 0)
            val dm = DisplayMetrics()
            try {
                wm.defaultDisplay.getRealMetrics(dm)
            } catch (t: Throwable) {
                return intArrayOf(0, 0, 0)
            }
            return intArrayOf(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }
}

/**
 * Reads screens that MediaProjection hands back as solid black.
 *
 * A black frame is what a window with FLAG_SECURE looks like to every ordinary capture path:
 * MediaProjection, AccessibilityService.takeScreenshot() and plain screencap all leave secure
 * layers out. Two routes get past that, and this class tries both:
 *
 *  * root, by running the stock screencap as uid 0;
 *  * Shizuku, by asking WindowManagerService for a display capture with secure layers in it
 *    from Shizuku's privileged process (see [SecureShotService]). On a non-rooted phone that
 *    is the whole point: no root, no Xposed, and it works from Android 12 up.
 *
 * [capture] returns a real bitmap or a [CaptureFailure] naming the exact path that failed and
 * why. It never returns an empty bitmap and it never waits forever. Everything here blocks, so
 * call it from a background thread.
 */
class SecureCapture(context: Context, cacheDir: File) {

    enum class Mode { UNKNOWN, ROOT, SHIZUKU, NONE }

    /** Which route last worked. UNKNOWN until the first successful read. */
    @Volatile var mode = Mode.UNKNOWN
        private set

    @Volatile var lastError = ""
        private set

    /** The typed reason of the last failed [capture], or null after one worked. */
    @Volatile var lastFailure: CaptureFailure? = null
        private set

    private val root = RootCapture(cacheDir)
    private val shizuku = ShizukuCapture(context.applicationContext)

    /** Do not hammer a phone that has neither root nor Shizuku for every frame. */
    @Volatile private var retryAfter = 0L

    @Volatile private var cooldownFailure: CaptureFailure? = null

    /**
     * The screen through root or Shizuku, or the typed reason it could not be read. [width] and
     * [height] are the size the caller wants when it knows it; 0 lets the route ask the system.
     */
    fun capture(width: Int = 0, height: Int = 0): CaptureOutcome {
        val now = SystemClock.uptimeMillis()
        if (now < retryAfter) {
            val f = cooldownFailure
                ?: CaptureFailure(CaptureError.COOLING_DOWN, "retrying in " + ((retryAfter - now + 999) / 1000) + "s")
            lastFailure = f
            lastError = f.message
            return CaptureOutcome.Failed(f)
        }

        var rootFailure: CaptureFailure? = null
        if (mode != Mode.SHIZUKU) {
            when (val fromRoot = root.capture()) {
                is CaptureOutcome.Frame -> {
                    mode = Mode.ROOT
                    clearFailure()
                    return fromRoot
                }
                is CaptureOutcome.Failed -> rootFailure = fromRoot.failure
            }
        }

        val budget = if (mode == Mode.UNKNOWN) FIRST_SHIZUKU_MS else SHIZUKU_MS
        return when (val fromShizuku = shizuku.capture(width, height, budget)) {
            is CaptureOutcome.Frame -> {
                mode = Mode.SHIZUKU
                clearFailure()
                fromShizuku
            }
            is CaptureOutcome.Failed -> {
                mode = Mode.NONE
                val f = biggestFailure(rootFailure, fromShizuku.failure)
                lastFailure = f
                lastError = f.message
                // Shizuku may be started, or access granted, while the overlay keeps running.
                retryAfter = now + RETRY_MS
                cooldownFailure = f
                CaptureOutcome.Failed(f)
            }
        }
    }

    /**
     * The screen as a Bitmap, or null when this phone cannot do the bypass. Kept for callers
     * that only want pixels; new code should use [capture] and print the failure.
     */
    fun grab(width: Int = 0, height: Int = 0): Bitmap? = when (val outcome = capture(width, height)) {
        is CaptureOutcome.Frame -> outcome.bitmap
        is CaptureOutcome.Failed -> null
    }

    /** One line naming what the app can and cannot do, for the panel and the activity. */
    fun describe(): String = when {
        mode == Mode.ROOT -> "Reading protected screens through root."
        mode == Mode.SHIZUKU -> "Reading protected screens through Shizuku."
        shizuku.ready() -> "Shizuku is ready; the first protected screen will be read through it."
        shizuku.isRunning() -> "Shizuku is running but access is not granted."
        else -> "No root and no Shizuku: protected screens stay black."
    }

    private fun clearFailure() {
        lastFailure = null
        lastError = ""
        retryAfter = 0L
        cooldownFailure = null
    }

    /**
     * Which failure to show when both routes failed. A route that is present but broken says
     * more than a route that was never there, so Shizuku's reason wins whenever it spoke.
     */
    private fun biggestFailure(rootFailure: CaptureFailure?, shizukuFailure: CaptureFailure): CaptureFailure {
        val shizukuAbsent = shizukuFailure.error == CaptureError.SHIZUKU_MISSING ||
            shizukuFailure.error == CaptureError.SHIZUKU_NOT_RUNNING ||
            shizukuFailure.error == CaptureError.SHIZUKU_NO_PERMISSION
        if (rootFailure == null || !shizukuAbsent) return shizukuFailure
        val both = listOf(rootFailure.detail, shizukuFailure.detail)
            .filter { it.isNotBlank() }
            .joinToString("; ")
        return CaptureFailure(rootFailure.error, both)
    }

    companion object {
        private const val RETRY_MS = 5_000L
        private const val FIRST_SHIZUKU_MS = 8_000L
        private const val SHIZUKU_MS = 4_000L
    }
}

/**
 * The root route: the stock screencap binary run as uid 0. SurfaceFlinger lets root read secure
 * layers, so this returns real pixels where everything else returns black.
 */
class RootCapture(private val cacheDir: File) {

    enum class Mode { UNKNOWN, ROOT, NONE }

    /** UNKNOWN until the first attempt, ROOT once a uid 0 shell answered, NONE when there is none. */
    @Volatile var mode = Mode.UNKNOWN
        private set

    @Volatile var lastError = ""
        private set

    @Volatile var lastFailure: CaptureFailure? = null
        private set

    private var attempts = 0
    @Volatile private var current: Process? = null

    /** The screen as a Bitmap, or null with [lastFailure] set. */
    fun grab(): Bitmap? = when (val outcome = capture()) {
        is CaptureOutcome.Frame -> outcome.bitmap
        is CaptureOutcome.Failed -> null
    }

    /** The screen through uid 0, or the typed reason there is none. */
    fun capture(): CaptureOutcome {
        if (mode == Mode.NONE) {
            return CaptureOutcome.Failed(
                lastFailure ?: CaptureFailure(CaptureError.NO_SU, lastError.ifBlank { "no working root shell" })
            )
        }
        val first = attempts == 0
        attempts++
        // Magisk and friends show their grant dialog on the first call and can hold the shell.
        val timeoutMs = if (first) FIRST_TIMEOUT_MS else TIMEOUT_MS
        val deadline = SystemClock.uptimeMillis() + timeoutMs

        val holder = arrayOfNulls<ByteArray>(1)
        val worker = Thread { holder[0] = shootOnce(deadline, timeoutMs) }
        worker.isDaemon = true
        worker.start()
        try {
            worker.join(timeoutMs + JOIN_SLACK_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (worker.isAlive) {
            try { current?.destroy() } catch (ignored: Exception) { }
            val f = CaptureFailure(CaptureError.SHELL_TIMEOUT, "screencap did not finish in " + timeoutMs + " ms")
            lastFailure = f
            lastError = f.message
            return CaptureOutcome.Failed(f)
        }
        val bytes = holder[0]
            ?: return CaptureOutcome.Failed(
                lastFailure ?: CaptureFailure(CaptureError.BAD_IMAGE, lastError.ifBlank { "screencap returned nothing" })
            )
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Exception) {
            null
        }
        if (bitmap == null) {
            val f = CaptureFailure(CaptureError.BAD_IMAGE, "could not decode the " + bytes.size + "-byte screenshot")
            lastFailure = f
            lastError = f.message
            return CaptureOutcome.Failed(f)
        }
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            val w = bitmap.width
            val h = bitmap.height
            bitmap.recycle()
            val f = CaptureFailure(CaptureError.FRAME_EMPTY, "screencap returned a " + w + "x" + h + " image")
            lastFailure = f
            lastError = f.message
            return CaptureOutcome.Failed(f)
        }
        lastFailure = null
        lastError = ""
        return CaptureOutcome.Frame(bitmap, CaptureOutcome.Source.ROOT)
    }

    private fun shootOnce(deadline: Long, timeoutMs: Long): ByteArray? {
        val su = suBinary()
        if (su == null) {
            fail(CaptureError.NO_SU, "no su binary on this phone")
            return null
        }
        if (mode != Mode.ROOT) {
            val id = readAll(su, "id", deadline)
            val text = id?.toString(Charsets.UTF_8) ?: ""
            if (!text.contains("uid=0")) {
                mode = Mode.NONE
                fail(
                    CaptureError.NOT_ROOT,
                    if (text.isBlank()) "su did not answer (missing or denied)" else "su is not root: " + text.trim().take(80)
                )
                return null
            }
            mode = Mode.ROOT
        }
        // PNG on stdout first: no file permissions to fight with.
        val png = readAll(su, "screencap -p", deadline)
        if (png != null && isPng(png)) return png
        return shootToFile(su, deadline, timeoutMs)
    }

    /** Some builds lose the PNG on stdout; ask screencap to write into our own cache dir instead. */
    private fun shootToFile(su: String, deadline: Long, timeoutMs: Long): ByteArray? {
        val out = File(cacheDir, "secure_shot.png")
        try { if (out.exists()) out.delete() } catch (ignored: Exception) { }
        val path = out.absolutePath
        readAll(su, "screencap -p " + path + "; chmod 0644 " + path + " 2>/dev/null", deadline)
        val bytes = try {
            if (out.exists() && out.length() > 8L) out.readBytes() else null
        } catch (t: Exception) {
            null
        }
        try { out.delete() } catch (ignored: Exception) { }
        if (bytes == null || !isPng(bytes)) {
            if (lastFailure == null) fail(CaptureError.BAD_IMAGE, "screencap wrote no PNG in " + timeoutMs + " ms")
            return null
        }
        return bytes
    }

    /** Runs one command through su and reads all of stdout, giving up at [deadline]. */
    private fun readAll(su: String, command: String, deadline: Long): ByteArray? {
        val err = StringBuilder()
        var started: Process? = null
        try {
            val p = ProcessBuilder(su, "-c", command).start()
            started = p
            current = p

            val drain = Thread {
                try {
                    val r = InputStreamReader(p.errorStream)
                    val ca = CharArray(512)
                    var n = r.read(ca)
                    while (n > 0) {
                        if (err.length < 1024) err.append(ca, 0, n)
                        n = r.read(ca)
                    }
                } catch (ignored: Exception) {
                }
            }
            drain.isDaemon = true
            drain.start()

            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                if (SystemClock.uptimeMillis() > deadline) {
                    fail(CaptureError.SHELL_TIMEOUT, command + " was still running at the deadline")
                    try { p.destroy() } catch (ignored: Exception) { }
                    return null
                }
                val n = p.inputStream.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) {
                    fail(CaptureError.BAD_IMAGE, "screencap output passed " + MAX_BYTES + " bytes")
                    try { p.destroy() } catch (ignored: Exception) { }
                    return null
                }
                buf.write(chunk, 0, n)
            }
            p.waitFor(200, TimeUnit.MILLISECONDS)
            if (err.isNotBlank()) lastError = err.toString().trim().take(160)
            return buf.toByteArray()
        } catch (e: Exception) {
            fail(CaptureError.BAD_IMAGE, e.message ?: e.javaClass.simpleName)
            return null
        } finally {
            current = null
            try { started?.destroy() } catch (ignored: Exception) { }
        }
    }

    private fun suBinary(): String? {
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su"
        )
        for (c in candidates) {
            val f = File(c)
            if (f.exists() && f.canExecute()) return c
        }
        return "su" // let the shell resolve it through PATH
    }

    private fun fail(code: CaptureError, detail: String) {
        val f = CaptureFailure(code, detail)
        lastFailure = f
        lastError = f.message
    }

    private fun isPng(b: ByteArray): Boolean =
        b.size > 8 &&
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()

    private companion object {
        const val MAX_BYTES = 48 * 1024 * 1024
        const val FIRST_TIMEOUT_MS = 15_000L
        const val TIMEOUT_MS = 4_000L
        const val JOIN_SLACK_MS = 2_000L
    }
}
