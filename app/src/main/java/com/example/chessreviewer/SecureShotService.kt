package com.example.chessreviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * The code that actually reads a FLAG_SECURE screen.
 *
 * This class does not run in the app's process. Shizuku starts it in a process of its own, with
 * the uid of whoever started Shizuku: 2000 (shell) when Shizuku was brought up over ADB or
 * wireless debugging, 0 (root) when it was started as root. That process holds
 * android.permission.READ_FRAME_BUFFER, and one more thing is needed:
 *
 *   ScreenCapture.CaptureArgs.Builder#setCaptureSecureLayers(true)
 *
 * Plain screencap (and MediaProjection, and AccessibilityService.takeScreenshot) leave secure
 * layers out, which is why they all return black. Asking WindowManagerService for a display
 * capture with secure layers switched on returns the real pixels, and the shell uid is allowed
 * to ask. No root, no Xposed, no LSPosed module.
 *
 * Every failure is a negative code plus a line in lastError(), and no call here can hang. The
 * capture runs on a worker that is abandoned at its deadline, because `captureDisplay` and
 * `screencap` are both system calls that can wedge and this process has to keep answering
 * binder calls from the app either way. The screencap fallback and the `wm size` probe have
 * deadlines of their own.
 *
 * The class is loaded by name from Shizuku, so keep the package and the no-argument
 * constructor as they are. It is a user service process, not an Android app process: hidden
 * APIs are reachable here, and Runtime.exec runs as shell.
 */
class SecureShotService : ISecureShot.Stub() {

    private val lock = Any()
    private var pending: ByteArray? = null

    @Volatile private var error = ""

    override fun destroy() {
        // The API calls this on the transaction code 16777114; leaving is our job.
        synchronized(lock) { pending = null }
        try { System.exit(0) } catch (t: Throwable) { }
    }

    /**
     * Grabs the display, encodes it, and answers the size in bytes, or a negative [RESULT_NO_FRAME]
     * style code with the reason in [lastError].
     *
     * The work runs on a worker thread with a hard deadline: a capture path that wedges must not
     * leave the app's binder call hanging, which is what "it just says it cannot see the screen"
     * looks like from the other side.
     */
    override fun capture(width: Int, height: Int): Int {
        synchronized(lock) { pending = null }
        error = ""
        val deadline = SystemClock.uptimeMillis() + MAX_CAPTURE_MS
        val holder = arrayOfNulls<Bitmap>(1)
        val thrown = arrayOfNulls<String>(1)
        val worker = Thread {
            try {
                holder[0] = shoot(width, height, deadline)
            } catch (t: Throwable) {
                thrown[0] = describe(t)
            }
        }
        worker.isDaemon = true
        worker.start()
        try {
            worker.join(MAX_CAPTURE_MS + JOIN_SLACK_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (worker.isAlive) {
            error = "the capture did not finish in " + MAX_CAPTURE_MS + " ms"
            return RESULT_TIMEOUT
        }

        val bitmap = holder[0]
        if (bitmap == null) {
            if (error.isBlank()) error = thrown[0] ?: "the system returned no frame"
            return codeFor(error)
        }
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            val w = bitmap.width
            val h = bitmap.height
            recycleQuietly(bitmap)
            error = "the system returned a " + w + "x" + h + " frame"
            return RESULT_EMPTY_FRAME
        }
        if (SystemClock.uptimeMillis() > deadline) {
            recycleQuietly(bitmap)
            error = "the capture took longer than " + MAX_CAPTURE_MS + " ms"
            return RESULT_TIMEOUT
        }

        val bytes = encode(bitmap)
        if (bytes == null || bytes.isEmpty()) {
            if (error.isBlank()) error = "the encoded frame was empty"
            return RESULT_ENCODE_FAILED
        }
        if (bytes.size > MAX_FRAME_BYTES) {
            error = "the encoded frame was " + bytes.size + " bytes"
            return RESULT_NO_FRAME
        }
        synchronized(lock) {
            pending = bytes
            error = ""
        }
        return bytes.size
    }

    override fun chunk(offset: Int, length: Int): ByteArray {
        val all = synchronized(lock) { pending } ?: return ByteArray(0)
        if (offset < 0 || offset >= all.size || length <= 0) return ByteArray(0)
        val end = minOf(all.size, offset + length)
        return all.copyOfRange(offset, end)
    }

    override fun lastError(): String = error

    // ------------------------------------------------------------------ capture paths

    /** Secure-layer capture, falling back to screencap when that is not available on this build. */
    private fun shoot(width: Int, height: Int, deadline: Long): Bitmap? {
        var secureError = ""
        if (Build.VERSION.SDK_INT >= 31) {
            val secure = shootSecure(width, height, deadline)
            if (secure != null) return secure
            secureError = error
            error = ""
        }
        val fallback = shootScreencap()
        if (fallback != null) return fallback
        if (error.isBlank()) {
            error = if (secureError.isBlank()) "the system returned no frame" else secureError
        } else if (secureError.isNotBlank()) {
            error = error + "; the secure path said: " + secureError
        }
        return null
    }

    /** JPEG at quality 92: good enough to read pieces from, small enough for the binder. */
    private fun encode(bitmap: Bitmap): ByteArray? {
        return try {
            val out = ByteArrayOutputStream()
            val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            if (encoded) out.toByteArray() else null
        } catch (t: Throwable) {
            if (error.isBlank()) error = "could not encode the frame: " + describe(t)
            null
        } finally {
            recycleQuietly(bitmap)
        }
    }

    /** Which negative code to report when nothing was captured. */
    private fun codeFor(text: String): Int {
        val lower = text.lowercase()
        if (lower.contains("timeout") || lower.contains("did not finish") || lower.contains("did not answer")) {
            return RESULT_TIMEOUT
        }
        if (lower.contains("android 12")) return RESULT_UNSUPPORTED
        if (Build.VERSION.SDK_INT < 31) return RESULT_UNSUPPORTED
        return RESULT_NO_FRAME
    }

    /**
     * WindowManagerService.captureDisplay with secure layers included. Android 12 and up:
     * android.window.ScreenCapture only exists from API 31, which is why a Shizuku-only phone
     * older than that still sees black on protected screens.
     */
    private fun shootSecure(width: Int, height: Int, deadline: Long): Bitmap? {
        if (Build.VERSION.SDK_INT < 31) {
            error = "secure-layer capture needs Android 12 (API 31); this phone is API " + Build.VERSION.SDK_INT
            return null
        }
        val size = if (width > 0 && height > 0) intArrayOf(width, height) else displaySize()
        if (SystemClock.uptimeMillis() > deadline) {
            error = "timeout working out the screen size"
            return null
        }
        val w = size[0]
        val h = size[1]
        if (w <= 0 || h <= 0) {
            error = "could not work out the screen size"
            return null
        }

        val builderClass = try {
            Class.forName("android.window.ScreenCapture\$CaptureArgs\$Builder")
        } catch (t: Throwable) {
            error = "this build has no ScreenCapture.CaptureArgs: " + describe(t)
            return null
        }
        val builder = builderClass.getDeclaredConstructor().newInstance()
        builderClass.getMethod("setSourceCrop", Rect::class.java).invoke(builder, Rect(0, 0, w, h))
        try {
            builderClass
                .getMethod("setSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(builder, w, h)
        } catch (t: Throwable) {
            // setSize is a convenience; the source crop already carries the size on all builds
        }
        builderClass
            .getMethod("setCaptureSecureLayers", Boolean::class.javaPrimitiveType)
            .invoke(builder, true)
        val captureArgs = builderClass.getMethod("build").invoke(builder)

        val screenCaptureClass = Class.forName("android.window.ScreenCapture")
        val listener = screenCaptureClass.getMethod("createSyncCaptureListener").invoke(null)
            ?: run {
                error = "no screen capture listener"
                return null
            }

        val windowManager = Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService")
            .invoke(null)
            ?: run {
                error = "window manager service is not reachable"
                return null
            }

        val argsClass = Class.forName("android.window.ScreenCapture\$CaptureArgs")
        val listenerClass = Class.forName("android.window.ScreenCapture\$ScreenCaptureListener")
        val captureDisplay = findCaptureDisplay(windowManager.javaClass.methods, argsClass, listenerClass)
            ?: run {
                error = "this build has no captureDisplay(int, CaptureArgs, ScreenCaptureListener)"
                return null
            }
        captureDisplay.invoke(windowManager, 0, captureArgs, listener)

        if (SystemClock.uptimeMillis() > deadline) {
            error = "captureDisplay took longer than " + MAX_CAPTURE_MS + " ms"
            return null
        }
        val buffer = listener.javaClass.getMethod("getBuffer").invoke(listener)
            ?: run {
                error = "the system returned no frame"
                return null
            }
        val bitmap = buffer.javaClass.getMethod("asBitmap").invoke(buffer) as? Bitmap
            ?: run {
                error = "the frame was not a bitmap"
                return null
            }
        // Screenshot bitmaps are hardware backed; the encoder and the caller want a normal one.
        return try {
            bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: bitmap
        } catch (t: Throwable) {
            bitmap
        }
    }

    /** The exact captureDisplay(int, CaptureArgs, ScreenCaptureListener) this build declares. */
    private fun findCaptureDisplay(methods: Array<Method>, argsClass: Class<*>, listenerClass: Class<*>): Method? {
        for (m in methods) {
            if (m.name != "captureDisplay") continue
            val p = m.parameterTypes
            if (p.size == 3 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == argsClass &&
                p[2] == listenerClass
            ) {
                return m
            }
        }
        return null
    }

    /** Last resort on Android 11 and older, and on ROMs that refuse the display capture. */
    private fun shootScreencap(): Bitmap? {
        val bytes = runBytes(SCREENCAP_TIMEOUT_MS, "screencap", "-p") ?: return null
        if (bytes.size < 8) {
            if (error.isBlank()) error = "screencap returned " + bytes.size + " bytes"
            return null
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            null
        }
        if (bitmap == null) error = "could not decode the " + bytes.size + "-byte screencap output"
        return bitmap
    }

    /** Runs a command and returns its stdout, giving up at [timeoutMs] instead of blocking. */
    private fun runBytes(timeoutMs: Long, vararg command: String): ByteArray? {
        val argv = Array(command.size) { index -> command[index] }
        val process = try {
            Runtime.getRuntime().exec(argv)
        } catch (t: Throwable) {
            error = "could not run " + command.first() + ": " + describe(t)
            return null
        }
        val out = ByteArrayOutputStream()
        val reader = Thread {
            try {
                process.inputStream.use { input -> input.copyTo(out) }
            } catch (t: Throwable) {
            }
        }
        reader.isDaemon = true
        reader.start()
        try {
            reader.join(timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (reader.isAlive) {
            try { process.destroy() } catch (t: Throwable) { }
            error = command.joinToString(" ") + " did not finish in " + timeoutMs + " ms"
            return null
        }
        try {
            process.waitFor(200, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try { process.destroy() } catch (t: Throwable) { }
        return out.toByteArray()
    }

    /** "Physical size: 1080x2400" and, when the resolution was changed, "Override size: ..." */
    private fun displaySize(): IntArray {
        val bytes = runBytes(WM_TIMEOUT_MS, "wm", "size") ?: return intArrayOf(0, 0)
        val text = String(bytes, Charsets.UTF_8)
        val match = Regex("size:\\s*(\\d+)x(\\d+)").findAll(text).lastOrNull()
            ?: return intArrayOf(0, 0)
        return intArrayOf(
            match.groupValues[1].toIntOrNull() ?: 0,
            match.groupValues[2].toIntOrNull() ?: 0
        )
    }

    private fun recycleQuietly(bitmap: Bitmap) {
        try { bitmap.recycle() } catch (t: Throwable) { }
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

    companion object {
        /** The system handed back no frame at all. */
        const val RESULT_NO_FRAME = -1

        /** A frame arrived but could not be encoded for the trip back. */
        const val RESULT_ENCODE_FAILED = -2

        /** A frame arrived with zero width or height. */
        const val RESULT_EMPTY_FRAME = -3

        /** This Android version cannot capture secure layers this way. */
        const val RESULT_UNSUPPORTED = -4

        /** A step ran past its deadline. */
        const val RESULT_TIMEOUT = -5

        /**
         * The whole capture, kept under the app's own wait for it (4s once the binding is up), so
         * the app reports the real reason instead of a watchdog timeout of its own.
         */
        private const val MAX_CAPTURE_MS = 2_500L

        /** How long past the deadline a wedged worker is waited for before it is abandoned. */
        private const val JOIN_SLACK_MS = 500L

        private const val SCREENCAP_TIMEOUT_MS = 2_000L
        private const val WM_TIMEOUT_MS = 800L

        /** A frame bigger than this is a broken answer, not a screenshot. */
        private const val MAX_FRAME_BYTES = 48 * 1024 * 1024
    }
}
