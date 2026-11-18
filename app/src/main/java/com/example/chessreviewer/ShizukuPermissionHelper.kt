package com.example.chessreviewer

import android.content.pm.PackageManager
import java.io.File
import rikka.shizuku.Shizuku

/**
 * One door to Shizuku for the whole app.
 *
 * Shizuku runs the code we send it with the shell uid (2000), so anything that needs a
 * privileged process works on a phone that was never rooted, as long as Shizuku itself is
 * running and this app was granted access.
 */
object ShizukuPermissionHelper {

    const val REQUEST_CODE = 4242

    @Volatile
    var lastError: String = ""
        private set

    /** Called on the main thread with the result of [requestPermission]. */
    @Volatile
    var onResult: ((granted: Boolean) -> Unit)? = null

    private val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            onResult?.invoke(grantResult == PackageManager.PERMISSION_GRANTED)
        }
    }

    /** True when the Shizuku app is installed and its service is up. Safe before any UI exists. */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        lastError = t.message ?: t.javaClass.simpleName
        false
    }

    /** True when Shizuku is up and this app already holds the grant. */
    fun hasPermission(): Boolean {
        if (!isAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    /** Shizuku's own "explain why first" flag; false when Shizuku is not running. */
    fun shouldShowRationale(): Boolean = try {
        isAvailable() && Shizuku.shouldShowRequestPermissionRationale()
    } catch (t: Throwable) {
        false
    }

    /** Asks Shizuku for the grant. The answer arrives through [onResult], not here. */
    fun requestPermission() {
        if (!isAvailable()) {
            lastError = "Shizuku is not running"
            return
        }
        if (hasPermission()) {
            onResult?.invoke(true)
            return
        }
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            onResult?.invoke(false)
        }
    }

    /** Register the result listener. Call once from Application.onCreate. */
    fun register() {
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
        }
    }

    /** Unregister it again. Call from Application.onTerminate. */
    fun unregister() {
        try {
            Shizuku.removeRequestPermissionResultListener(listener)
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
        }
    }

    /**
     * One line for the panel: what the user has to do next, if anything.
     */
    fun describe(): String = when {
        !isAvailable() -> "Shizuku is not running. Start it, then reopen Chess Reviewer."
        !hasPermission() -> "Shizuku is running. Tap 'Grant Shizuku access' to allow this app."
        else -> "Shizuku is ready (uid ${uidText()})."
    }

    fun uidText(): String = try {
        Shizuku.getUid().toString()
    } catch (t: Throwable) {
        "?"
    }

    // ---- privileged processes -------------------------------------------------

    /**
     * Shizuku's process starter. It is @Deprecated and private in the current API (removal is
     * planned for API 14), so it is reached reflectively: this compiles against any 13.x and
     * simply returns null where the method is gone.
     */
    private val remoteNewProcess: java.lang.reflect.Method? by lazy {
        try {
            Shizuku::class.java
                .getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                .apply { isAccessible = true }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Runs [cmd] as the shell user through Shizuku.
     *
     * Falls back to `su` only when Shizuku is not available at all, so a rooted phone keeps
     * working and a Shizuku phone never touches su.
     */
    fun newProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process? {
        if (isAvailable() && hasPermission()) {
            val starter = remoteNewProcess
            if (starter != null) {
                try {
                    val p = starter.invoke(null, cmd, env, dir) as? Process
                    if (p != null) return p
                } catch (t: Throwable) {
                    lastError = t.cause?.message ?: t.message ?: t.javaClass.simpleName
                }
            } else {
                lastError = "this Shizuku build has no newProcess"
            }
        }
        if (isAvailable()) return null   // Shizuku is there but refused: do not silently use su
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd.joinToString(" ")), env, dir?.let { File(it) })
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            null
        }
    }

    /** [newProcess] plus reading it to the end. Null on any failure. */
    fun exec(cmd: Array<String>, timeoutMs: Long = 4000L): String? {
        val p = newProcess(cmd) ?: return null
        return try {
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            text
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            null
        } finally {
            try { p.destroy() } catch (t: Throwable) { }
        }
    }
}
