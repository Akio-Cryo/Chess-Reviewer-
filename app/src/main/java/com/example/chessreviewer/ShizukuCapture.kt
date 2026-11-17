package com.example.chessreviewer

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.SystemClock
import com.example.chessreviewer.CaptureError.SHIZUKU_BAD_IMAGE
import com.example.chessreviewer.CaptureError.SHIZUKU_BIND_FAILED
import com.example.chessreviewer.CaptureError.SHIZUKU_BIND_TIMEOUT
import com.example.chessreviewer.CaptureError.SHIZUKU_DEAD
import com.example.chessreviewer.CaptureError.SHIZUKU_EMPTY_FRAME
import com.example.chessreviewer.CaptureError.SHIZUKU_MISSING
import com.example.chessreviewer.CaptureError.SHIZUKU_NOT_RUNNING
import com.example.chessreviewer.CaptureError.SHIZUKU_NO_FRAME
import com.example.chessreviewer.CaptureError.SHIZUKU_NO_PERMISSION
import com.example.chessreviewer.CaptureError.SHIZUKU_NULL_BINDING
import com.example.chessreviewer.CaptureError.SHIZUKU_SHORT_READ
import com.example.chessreviewer.CaptureError.SHIZUKU_TIMEOUT
import com.example.chessreviewer.CaptureError.SHIZUKU_UNSUPPORTED
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's side of the Shizuku path: binds [SecureShotService] in Shizuku's privileged process
 * and asks it for frames. Everything here blocks, so call [capture] off the main thread.
 *
 * A binding is not permanent. Shizuku's process can be killed by the system, restarted when the
 * user reopens Shizuku, or refuse to start the service at all after an app update (the service
 * class it caches is the old one). Each of those used to end with a silent null; now every one
 * of them is a typed [CaptureFailure] and the next request re-binds instead of trusting a dead
 * binder. [rebind] forces that from the UI as well.
 *
 * Nothing is requested from the user here. The permission is asked for from MainActivity, where
 * there is a window for Shizuku's dialog to sit on.
 */
class ShizukuCapture(private val context: Context) {

    @Volatile private var service: ISecureShot? = null
    @Volatile private var connectLatch: CountDownLatch? = null
    @Volatile private var binding = false

    @Volatile var lastError = ""
        private set

    @Volatile var lastFailure: CaptureFailure? = null
        private set

    /** Binder deaths since the last successful frame; a rebind is forced while this is non-zero. */
    @Volatile private var deaths = 0

    /** Guards bind/unbind so two callers cannot each start a Shizuku user service. */
    private val bindLock = Any()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val live = binder != null && binder.pingBinder()
            service = if (live) ISecureShot.Stub.asInterface(binder) else null
            if (live) {
                deaths = 0
                lastFailure = null
                lastError = ""
            } else {
                record(CaptureFailure(SHIZUKU_NULL_BINDING, "Shizuku connected but handed back no usable binder"))
            }
            binding = false
            connectLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            record(CaptureFailure(SHIZUKU_DEAD, "the Shizuku capture service disconnected"))
        }

        override fun onBindingDied(name: ComponentName?) {
            record(CaptureFailure(SHIZUKU_DEAD, "the binding to the Shizuku capture service died"))
        }

        override fun onNullBinding(name: ComponentName?) {
            // The privileged process would not start the class: usually its cached copy is stale
            // after an app update. Say so, because the fix is on the user's side.
            record(
                CaptureFailure(
                    SHIZUKU_NULL_BINDING,
                    "Shizuku could not start SecureShotService. Update or reinstall this app, then restart Shizuku."
                )
            )
            binding = false
            connectLatch?.countDown()
        }
    }

    // ------------------------------------------------------------------ state

    /** Shizuku's own service is up and answering. */
    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    /** This app has been given access to Shizuku. */
    fun hasPermission(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** 0 when Shizuku runs as root, 2000 when it was started over ADB, -1 when unknown. */
    fun uid(): Int = try {
        Shizuku.getUid()
    } catch (t: Throwable) {
        -1
    }

    fun isInstalled(): Boolean = try {
        context.packageManager.getApplicationInfo(PACKAGE_SHIZUKU, 0) != null
    } catch (t: Throwable) {
        false
    }

    fun ready(): Boolean = isRunning() && hasPermission()

    /** True while a binder is held and answering. */
    fun isBound(): Boolean {
        val bound = service ?: return false
        return try {
            bound.asBinder()?.pingBinder() == true
        } catch (t: Throwable) {
            false
        }
    }

    /** One line for the UI, in plain words. */
    fun report(): String = when {
        !isInstalled() -> "Shizuku is not installed on this phone."
        !isRunning() -> "Shizuku is installed but not running. Open Shizuku and start it (wireless debugging), then come back."
        !hasPermission() -> "Shizuku is running. Tap 'Grant Shizuku access' so this app can use it."
        uid() == 0 -> "Shizuku is ready and running as root (uid 0)."
        uid() == 2000 -> "Shizuku is ready as shell (uid 2000). Protected screens are readable on Android 12 and newer."
        else -> "Shizuku is ready (uid " + uid() + ")."
    }

    // ------------------------------------------------------------------ capture

    /**
     * The display as the privileged process sees it, or the typed reason there is none. Every
     * failure leaves a message the panel can print, and a binder-level failure makes the next
     * call bind again instead of reusing the dead one.
     */
    fun capture(width: Int, height: Int, timeoutMs: Long): CaptureOutcome {
        if (!isInstalled()) {
            return failed(CaptureFailure(SHIZUKU_MISSING, "Shizuku is not installed (package " + PACKAGE_SHIZUKU + ")"))
        }
        if (!isRunning()) {
            return failed(CaptureFailure(SHIZUKU_NOT_RUNNING, "Shizuku is not running"))
        }
        if (!hasPermission()) {
            return failed(CaptureFailure(SHIZUKU_NO_PERMISSION, "Shizuku access is not granted to this app"))
        }
        val api = serviceOrBind(timeoutMs)
            ?: return CaptureOutcome.Failed(
                lastFailure ?: CaptureFailure(SHIZUKU_BIND_FAILED, "could not reach the Shizuku capture service")
            )
        return callWithin(api, width, height, timeoutMs)
    }

    /**
     * The screen as a Bitmap, or null with [lastFailure] set. Kept for callers that only want
     * pixels; new code should use [capture] and print the failure.
     */
    fun grab(width: Int, height: Int, timeoutMs: Long): Bitmap? = when (val outcome = capture(width, height, timeoutMs)) {
        is CaptureOutcome.Frame -> outcome.bitmap
        is CaptureOutcome.Failed -> null
    }

    /**
     * Drops the current binding and makes a fresh one. Returns null when the new binding came
     * up, or the typed reason it did not. The UI calls this after the user restarts Shizuku.
     */
    fun rebind(timeoutMs: Long = REBIND_MS): CaptureFailure? {
        dropBinding(kill = true)
        deaths = 0
        lastFailure = null
        lastError = ""
        val api = serviceOrBind(timeoutMs)
        return if (api != null) null else (lastFailure ?: CaptureFailure(SHIZUKU_BIND_FAILED, "could not re-bind the Shizuku capture service"))
    }

    /** Lets the binding go without killing the privileged process. */
    fun release() {
        dropBinding(kill = false)
    }

    // ------------------------------------------------------------------ internals

    /**
     * One capture on a worker thread, given up on at [timeoutMs].
     *
     * A binder call has no timeout of its own: if Shizuku's process wedges inside
     * `WindowManagerService.captureDisplay`, an inline call would block the analysis loop for as
     * long as the process stays wedged. The worker is abandoned instead, and the request comes
     * back as [SHIZUKU_TIMEOUT] so the panel can name it.
     */
    private fun callWithin(api: ISecureShot, width: Int, height: Int, timeoutMs: Long): CaptureOutcome {
        val budget = timeoutMs.coerceIn(1L, MAX_READ_MS)
        val holder = arrayOfNulls<CaptureOutcome>(1)
        val worker = Thread {
            holder[0] = try {
                request(api, width, height, budget)
            } catch (t: Throwable) {
                val text = describe(t)
                if (isBinderDeath(t)) {
                    record(CaptureFailure(SHIZUKU_DEAD, "the Shizuku capture service died mid-frame: " + text))
                    dropBinding(kill = true)
                } else {
                    record(CaptureFailure(SHIZUKU_BIND_FAILED, text))
                }
                CaptureOutcome.Failed(lastFailure ?: CaptureFailure(SHIZUKU_BIND_FAILED, text))
            }
        }
        worker.isDaemon = true
        worker.start()
        try {
            worker.join(budget + WATCHDOG_SLACK_MS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (worker.isAlive) {
            return failed(
                CaptureFailure(SHIZUKU_TIMEOUT, "the Shizuku capture did not answer in " + budget + " ms")
            )
        }
        return holder[0]
            ?: failed(CaptureFailure(SHIZUKU_TIMEOUT, "the Shizuku capture returned no result"))
    }

    private fun request(api: ISecureShot, width: Int, height: Int, timeoutMs: Long): CaptureOutcome {
        val total = api.capture(width, height)
        if (total <= 0) {
            val detail = try { api.lastError() } catch (t: Throwable) { "" }
            return failed(codeFailure(total, detail))
        }
        if (total > MAX_FRAME_BYTES) {
            return failed(
                CaptureFailure(SHIZUKU_BAD_IMAGE, "the Shizuku service announced a " + total + "-byte frame")
            )
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs.coerceIn(1L, MAX_READ_MS)
        val bytes = readAll(api, total, deadline)
            ?: return CaptureOutcome.Failed(lastFailure ?: CaptureFailure(SHIZUKU_SHORT_READ, "the frame arrived incomplete"))
        if (bytes.isEmpty()) {
            return failed(CaptureFailure(SHIZUKU_EMPTY_FRAME, "the Shizuku service sent zero bytes"))
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            null
        }
        if (bitmap == null) {
            return failed(CaptureFailure(SHIZUKU_BAD_IMAGE, "could not decode the " + bytes.size + "-byte Shizuku frame"))
        }
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            val w = bitmap.width
            val h = bitmap.height
            bitmap.recycle()
            return failed(CaptureFailure(SHIZUKU_EMPTY_FRAME, "the Shizuku frame was " + w + "x" + h))
        }
        deaths = 0
        lastFailure = null
        lastError = ""
        return CaptureOutcome.Frame(bitmap, CaptureOutcome.Source.SHIZUKU)
    }

    /** Reads the frame in chunks, giving up at [deadline] instead of spinning on a dead service. */
    private fun readAll(api: ISecureShot, total: Int, deadline: Long): ByteArray? {
        val out = ByteArrayOutputStream(total)
        var offset = 0
        while (offset < total) {
            if (SystemClock.uptimeMillis() > deadline) {
                record(
                    CaptureFailure(
                        SHIZUKU_SHORT_READ,
                        "the Shizuku service stopped at byte " + offset + " of " + total
                    )
                )
                return null
            }
            val want = minOf(CHUNK_BYTES, total - offset)
            val part = api.chunk(offset, want)
            if (part == null || part.isEmpty()) {
                record(
                    CaptureFailure(
                        SHIZUKU_SHORT_READ,
                        "the Shizuku service returned no bytes at offset " + offset + " of " + total
                    )
                )
                return null
            }
            if (part.size > want) {
                record(
                    CaptureFailure(
                        SHIZUKU_SHORT_READ,
                        "the Shizuku service sent " + part.size + " bytes where " + want + " were asked for"
                    )
                )
                return null
            }
            out.write(part, 0, part.size)
            offset += part.size
            if (offset > MAX_FRAME_BYTES) {
                record(
                    CaptureFailure(
                        SHIZUKU_BAD_IMAGE,
                        "the Shizuku frame passed " + MAX_FRAME_BYTES + " bytes"
                    )
                )
                return null
            }
        }
        return out.toByteArray()
    }

    private fun codeFailure(code: Int, detail: String): CaptureFailure {
        val text = if (detail.isBlank()) "the Shizuku capture failed with code " + code else detail
        val error = when (code) {
            SecureShotService.RESULT_EMPTY_FRAME -> CaptureError.SHIZUKU_EMPTY_FRAME
            SecureShotService.RESULT_ENCODE_FAILED -> CaptureError.SHIZUKU_BAD_IMAGE
            SecureShotService.RESULT_UNSUPPORTED -> CaptureError.SHIZUKU_UNSUPPORTED
            SecureShotService.RESULT_TIMEOUT -> CaptureError.SHIZUKU_TIMEOUT
            else -> CaptureError.SHIZUKU_NO_FRAME
        }
        return CaptureFailure(error, text)
    }

    /**
     * The bound service if it still answers, otherwise a fresh bind. Serialised on [bindLock]: two
     * threads binding at once would start two user services and leak one of them.
     */
    private fun serviceOrBind(timeoutMs: Long): ISecureShot? = synchronized(bindLock) { bindOrReuse(timeoutMs) }

    private fun bindOrReuse(timeoutMs: Long): ISecureShot? {
        service?.let { bound ->
            val binder = try {
                bound.asBinder()
            } catch (t: Throwable) {
                null
            }
            if (binder != null && binder.pingBinder()) return bound
            record(CaptureFailure(SHIZUKU_DEAD, "the bound Shizuku service stopped answering"))
            dropBinding(kill = true)
        }
        if (deaths > 0) dropBinding(kill = true)

        // Another thread is already binding: wait for its result instead of opening a second one.
        if (binding) {
            val inFlight = connectLatch
            if (inFlight != null) {
                try {
                    inFlight.await(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            val joined = service
            if (joined != null) {
                val b = try { joined.asBinder() } catch (t: Throwable) { null }
                if (b != null && b.pingBinder()) return joined
            }
        }

        val latch = CountDownLatch(1)
        connectLatch = latch
        binding = true
        deaths = 0
        val args = userServiceArgs()
        try {
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            binding = false
            connectLatch = null
            record(CaptureFailure(SHIZUKU_BIND_FAILED, "could not bind the Shizuku service: " + describe(t)))
            return null
        }
        try {
            // The first bind spawns the privileged process, which can take a couple of seconds.
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            connectLatch = null
            binding = false
        }
        val api = service
        if (api == null) {
            dropBinding(kill = true)
            if (lastFailure == null) {
                record(
                    CaptureFailure(
                        SHIZUKU_BIND_TIMEOUT,
                        "Shizuku did not connect in " + timeoutMs + " ms"
                    )
                )
            }
        }
        return api
    }

    private fun dropBinding(kill: Boolean) {
        service = null
        try {
            Shizuku.unbindUserService(userServiceArgs(), connection, kill)
        } catch (t: Throwable) {
            // Nothing to unbind, or Shizuku is already gone: the state above is what matters.
        }
    }

    private fun record(failure: CaptureFailure) {
        if (failure.error == CaptureError.SHIZUKU_DEAD || failure.error == CaptureError.SHIZUKU_NULL_BINDING) {
            deaths++
            service = null
        }
        lastFailure = failure
        lastError = failure.message
    }

    private fun failed(failure: CaptureFailure): CaptureOutcome {
        record(failure)
        return CaptureOutcome.Failed(failure)
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, SecureShotService::class.java.name)
        )
            .daemon(true)
            .processNameSuffix("shot")
            .debuggable(false)
            .version(1)

    private fun isBinderDeath(t: Throwable): Boolean {
        var e: Throwable? = t
        var guard = 0
        while (e != null && guard < 8) {
            if (e is android.os.DeadObjectException) return true
            if (e is android.os.RemoteException) return true
            e = e.cause
            guard++
        }
        return false
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
        const val PACKAGE_SHIZUKU = "moe.shizuku.privileged.api"
        private const val CHUNK_BYTES = 192 * 1024
        private const val MAX_READ_MS = 10_000L
        private const val REBIND_MS = 6_000L

        /** A frame bigger than this is a broken service answer, not a screenshot. */
        private const val MAX_FRAME_BYTES = 48 * 1024 * 1024

        /** How long past its own deadline a capture worker is waited for before it is abandoned. */
        private const val WATCHDOG_SLACK_MS = 1_000L
    }
}
