package com.example.chessreviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.ByteArrayOutputStream

/**
 * The code that actually reads a FLAG_SECURE screen.
 *
 * This class does not run in the app's process. Shizuku starts it in a process of its own,
 * with the uid of whoever started Shizuku: 2000 (shell) when Shizuku was brought up over ADB
 * or wireless debugging, 0 (root) when it was started as root. That process holds
 * android.permission.READ_FRAME_BUFFER, and one more thing is needed:
 *
 *   ScreenCapture.CaptureArgs.Builder#setCaptureSecureLayers(true)
 *
 * Plain `screencap` (and MediaProjection, and AccessibilityService.takeScreenshot) leave
 * secure layers out, which is why they all return black. Asking WindowManagerService for a
 * display capture with secure layers switched on returns the real pixels, and the shell uid
 * is allowed to ask. No root, no Xposed, no LSPosed module.
 *
 * The class is loaded by name from Shizuku, so keep the package and the no-argument
 * constructor as they are. It is a user service process, not an Android app process: hidden
 * APIs are reachable here, and Runtime.exec runs as shell.
 */
class SecureShotService : ISecureShot.Stub() {

    @Volatile private var pending: ByteArray? = null
    @Volatile private var error = ""

    override fun destroy() {
        // The API calls this on the transaction code 16777114; leaving is our job.
        try { System.exit(0) } catch (t: Throwable) { }
    }

    override fun capture(width: Int, height: Int): Int {
        pending = null
        error = ""
        val bitmap: Bitmap? = try {
            shootSecure(width, height) ?: shootScreencap()
        } catch (t: Throwable) {
            error = describe(t)
            null
        }
        if (bitmap == null) {
            if (error.isBlank()) error = "the system returned no frame"
            return -1
        }
        val bytes: ByteArray? = try {
            val out = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) out.toByteArray() else null
        } catch (t: Throwable) {
            error = "could not encode the frame: " + describe(t)
            null
        } finally {
            try { bitmap.recycle() } catch (t: Throwable) { }
        }
        if (bytes == null || bytes.isEmpty()) return -2
        pending = bytes
        return bytes.size
    }

    override fun chunk(offset: Int, length: Int): ByteArray {
        val all = pending ?: return ByteArray(0)
        if (offset < 0 || offset >= all.size || length <= 0) return ByteArray(0)
        val end = minOf(all.size, offset + length)
        return all.copyOfRange(offset, end)
    }

    override fun lastError(): String = error

    // ------------------------------------------------------------------ capture paths

    /**
     * WindowManagerService.captureDisplay with secure layers included. Android 12 and up:
     * android.window.ScreenCapture only exists from API 31, which is why a Shizuku-only phone
     * older than that still sees black on protected screens.
     */
    private fun shootSecure(width: Int, height: Int): Bitmap? {
        val size = if (width > 0 && height > 0) intArrayOf(width, height) else displaySize()
        val w = size[0]
        val h = size[1]
        if (w <= 0 || h <= 0) {
            error = "could not work out the screen size"
            return null
        }

        val builderClass = Class.forName("android.window.ScreenCapture\$CaptureArgs\$Builder")
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

        val captureDisplay = windowManager.javaClass.getMethod(
            "captureDisplay",
            Int::class.javaPrimitiveType,
            Class.forName("android.window.ScreenCapture\$CaptureArgs"),
            Class.forName("android.window.ScreenCapture\$ScreenCaptureListener")
        )
        captureDisplay.invoke(windowManager, 0, captureArgs, listener)

        val buffer = listener.javaClass.getMethod("getBuffer").invoke(listener)
            ?: run {
                error = "the system returned no frame"
                return null
            }
        val asBitmap = buffer.javaClass.getMethod("asBitmap")
        val bitmap = asBitmap.invoke(buffer) as? Bitmap
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

    /** Last resort on Android 11 and older, and on ROMs that refuse the display capture. */
    private fun shootScreencap(): Bitmap? {
        val p = try {
            Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
        } catch (t: Throwable) {
            error = describe(t)
            return null
        }
        val bytes = try {
            p.inputStream.readBytes()
        } catch (t: Throwable) {
            error = describe(t)
            return null
        } finally {
            try { p.destroy() } catch (t: Throwable) { }
        }
        if (bytes.size < 8) return null
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            null
        }
    }

    private fun displaySize(): IntArray {
        val text = try {
            val p = Runtime.getRuntime().exec(arrayOf("wm", "size"))
            val out = p.inputStream.bufferedReader().readText()
            try { p.waitFor() } catch (t: Throwable) { }
            out
        } catch (t: Throwable) {
            ""
        }
        // "Physical size: 1080x2400" and, when the resolution was changed, "Override size: …"
        val match = Regex("size:\\s*(\\d+)x(\\d+)").findAll(text).lastOrNull()
            ?: return intArrayOf(0, 0)
        return intArrayOf(
            match.groupValues[1].toIntOrNull() ?: 0,
            match.groupValues[2].toIntOrNull() ?: 0
        )
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
}
