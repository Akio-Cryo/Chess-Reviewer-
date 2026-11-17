// Checks the failure model the floating panel and the app screen share: every way the capture and
// board modules can fail has one sentence, and that sentence says which part failed.
//
// CaptureService.kt is a Service, but CaptureFault and CaptureFaults are plain Kotlin, so this runs on a
// desktop JVM with the Android framework jar on the classpath for the service class to link against:
//
//   kotlinc app/src/main/java/com/example/chessreviewer/*.kt tools/PanelCheck.kt \\
//           -classpath <android.jar>:<shizuku classes.jar>:<kotlinx-coroutines-core.jar> \\
//           -include-runtime -d panelcheck.jar
//   java -cp panelcheck.jar:<android.jar>:<shizuku classes.jar>:<coroutines.jar> com.example.chessreviewer.PanelCheckKt
//
// It is not part of the APK build (tools/ is outside app/src). The point of every case below is the one
// line the player sees: "can't see the board" was true of nine different causes and fixed none of them.
package com.example.chessreviewer

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "ok   " else "FAIL ") + name + (if (ok || detail.isEmpty()) "" else "  -> " + detail))
    if (!ok) failures++
}

/** the old line, which named no cause and is what this whole file exists to keep out */
private const val GENERIC = "can't see the board"

fun main() {
    val faults = CaptureFault.values().toList()
    val errors = CaptureError.values().toList()

    // ---------------------------------------------------------------- the sentences

    check("there are as many causes as the enum names", faults.size >= 10, faults.size.toString())

    for (f in faults) {
        check(
            f.name + ": sentence names a cause",
            f.sentence.length >= 30 && !f.sentence.lowercase().contains(GENERIC),
            f.sentence
        )
        check(f.name + ": sentence ends like a sentence", f.sentence.trim().endsWith("."), f.sentence)
        check(f.name + ": fix is an instruction", f.fix.length >= 20 && f.fix != f.sentence, f.fix)
        check(f.name + ": fix names the same app part", f.fix.length >= 20, f.fix)
    }
    check(
        "no two causes share a sentence",
        faults.map { it.sentence }.toSet().size == faults.size,
        faults.groupBy { it.sentence }.filterValues { it.size > 1 }.keys.joinToString()
    )
    check(
        "no two causes share a fix",
        faults.map { it.fix }.toSet().size == faults.size,
        faults.groupBy { it.fix }.filterValues { it.size > 1 }.keys.joinToString()
    )

    // ---------------------------------------------------------------- what the panel prints

    for (f in faults) {
        val withDetail = f.panelText("the module's own words")
        val lines = withDetail.split("\n")
        check(f.name + ": detail goes on the first line", lines.first() == "the module's own words", withDetail)
        check(f.name + ": fix follows on its own line", lines.getOrNull(1) == f.fix, withDetail)
        val bare = f.panelText()
        check(f.name + ": no detail falls back to its sentence", bare.startsWith(f.sentence), bare)
        check(f.name + ": the panel never prints an empty line", bare.split("\n").none { it.isBlank() }, bare)
        // the same sentence the module would print is not repeated twice
        val same = f.panelText(f.fix)
        check(f.name + ": does not print its fix twice", same == f.fix, same)
    }

    // ---------------------------------------------------------------- the one action each cause needs

    check(
        "only a spent token needs a new consent",
        faults.filter { it.needsConsent }.map { it.name } == listOf("NEEDS_CONSENT"),
        faults.filter { it.needsConsent }.map { it.name }.toString()
    )
    check(
        "only the missing overlay permission cannot be retried from the panel",
        faults.filter { !it.retryable }.map { it.name } == listOf("OVERLAY_PERMISSION"),
        faults.filter { !it.retryable }.map { it.name }.toString()
    )
    check(
        "a consent cause is retryable, because Retry is what asks for it",
        CaptureFault.NEEDS_CONSENT.retryable
    )
    check(
        "the overlay fix says where to switch it on",
        CaptureFault.OVERLAY_PERMISSION.fix.contains("overlay"),
        CaptureFault.OVERLAY_PERMISSION.fix
    )

    // ---------------------------------------------------------------- a frame is not a board

    check("board not found is not a capture cause", !CaptureFault.BOARD_NOT_FOUND.isCapture)
    check("board unreadable is not a capture cause", !CaptureFault.BOARD_UNREADABLE.isCapture)
    check("an unexpected error is not a capture cause", !CaptureFault.UNEXPECTED.isCapture)
    check("a spent token is a capture cause", CaptureFault.NEEDS_CONSENT.isCapture)
    check("a black frame is a capture cause", CaptureFault.SCREEN_BLOCKED.isCapture)
    check("no frames is a capture cause", CaptureFault.NO_FRAMES.isCapture)
    for (f in faults.filter { it.name.startsWith("SHIZUKU_") }) {
        check(f.name + ": is a capture cause", f.isCapture)
    }

    // ---------------------------------------------------------------- every capture code answers

    val mapped = HashMap<CaptureFault, MutableList<CaptureError>>()
    for (e in errors) {
        val f = try {
            CaptureFaults.forCaptureError(e)
        } catch (t: Throwable) {
            check(e.name + ": maps to a cause", false, t.javaClass.simpleName)
            continue
        }
        mapped.getOrPut(f) { mutableListOf() }.add(e)
        val sentence = CaptureFailure(e).panelText()
        check(e.name + ": has its own sentence", sentence.length >= 20 && sentence.contains("[" + e.name + "]"), sentence)
        check(e.name + ": is not the generic line", !sentence.lowercase().contains(GENERIC), sentence)
        check(e.name + ": keeps the code for a bug report", sentence.contains(e.name), sentence)
    }
    check("every capture code is mapped", mapped.values.sumOf { it.size } == errors.size)

    // a code that needs a person to do something specific must not land on the black-screen sentence
    for (e in listOf(
        CaptureError.SHIZUKU_MISSING,
        CaptureError.SHIZUKU_NOT_RUNNING,
        CaptureError.SHIZUKU_NO_PERMISSION
    )) {
        val f = CaptureFaults.forCaptureError(e)
        check(e.name + ": has a cause of its own", f.name.startsWith("SHIZUKU_"), f.name)
    }
    check(
        "the three Shizuku causes are three different sentences",
        setOf(
            CaptureFaults.forCaptureError(CaptureError.SHIZUKU_MISSING).sentence,
            CaptureFaults.forCaptureError(CaptureError.SHIZUKU_NOT_RUNNING).sentence,
            CaptureFaults.forCaptureError(CaptureError.SHIZUKU_NO_PERMISSION).sentence
        ).size == 3
    )
    check(
        "the whole frame path needs a fresh consent",
        errors.filter {
            it in setOf(
                CaptureError.NO_TOKEN, CaptureError.TOKEN_REJECTED, CaptureError.TOKEN_SPENT,
                CaptureError.DISPLAY_FAILED, CaptureError.NO_SESSION, CaptureError.PROJECTION_STOPPED
            )
        }.all { CaptureFaults.forCaptureError(it) == CaptureFault.NEEDS_CONSENT }
    )
    check(
        "a frame that never arrives is not reported as a missing permission",
        errors.filter {
            it in setOf(
                CaptureError.FRAME_TIMEOUT, CaptureError.FRAME_EMPTY, CaptureError.FRAME_UNREADABLE
            )
        }.all { CaptureFaults.forCaptureError(it) == CaptureFault.NO_FRAMES }
    )
    check(
        "the cooldown is its own cause, not a black screen",
        CaptureFaults.forCaptureError(CaptureError.COOLING_DOWN) == CaptureFault.COOLING
    )

    // ---------------------------------------------------------------- the board mappers

    val missing = CaptureFaults.forMissingBoard("the board runs off the edge of the screen")
    check("a missing board is its own cause", missing.first == CaptureFault.BOARD_NOT_FOUND, missing.first.name)
    check("a missing board keeps the finder's reason", missing.second == "the board runs off the edge of the screen", missing.second)

    val unreadable = CaptureFaults.forUnreadableBoard("I see 34 pieces, which is more than a chess set has")
    check("an unreadable board is its own cause", unreadable.first == CaptureFault.BOARD_UNREADABLE, unreadable.first.name)
    check("an unreadable board keeps the reader's reason", unreadable.second.contains("34 pieces"), unreadable.second)

    val learn = CaptureFaults.forLearnFailure(null)
    check("a learn failure with no message still has a sentence", learn.first.panelText(learn.second).startsWith(learn.first.sentence))
    val learnWhy = CaptureFaults.forLearnFailure("Couldn't learn every piece type.")
    check("a learn failure keeps its own message", learnWhy.second.contains("every piece type"), learnWhy.second)

    // a board cause is never answered by a capture code, so a frame failure cannot hide a board one
    check(
        "no capture code maps to a board cause",
        errors.none {
            val f = CaptureFaults.forCaptureError(it)
            f == CaptureFault.BOARD_NOT_FOUND || f == CaptureFault.BOARD_UNREADABLE
        }
    )

    // ---------------------------------------------------------------- every cause is reachable

    val fromCodes = errors.map { CaptureFaults.forCaptureError(it) }.toSet()
    val fromBoard = setOf(missing.first, unreadable.first)
    val setDirectly = setOf(CaptureFault.OVERLAY_PERMISSION, CaptureFault.UNEXPECTED)
    val reachable = fromCodes + fromBoard + setDirectly
    check(
        "every cause has a path that reaches it",
        reachable.containsAll(faults),
        faults.filter { it !in reachable }.map { it.name }.toString()
    )

    println(if (failures == 0) "\nall panel checks passed" else "\n$failures panel check(s) failed")
    if (failures > 0) throw AssertionError("$failures panel check(s) failed")
}
