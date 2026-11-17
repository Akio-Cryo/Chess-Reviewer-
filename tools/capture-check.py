#!/usr/bin/env python3
"""Static checks on the capture path: the three rules the module has to keep.

    python3 tools/capture-check.py [app/src/main/java/com/example/chessreviewer]

Nothing here runs Android; it reads the source and fails when a rule that used to be broken is
broken again. The rules:

  1. every `CaptureError` constant is written as `CaptureError.NAME` or imported, because a bare
     name in another file does not resolve and the module stops building;
  2. no `acquireNextImage()`: it blocks until the screen repaints, which never happens on a still
     screen, and a `while (true)` around a frame poll must carry a deadline;
  3. no thread join or process wait without a timeout, so no frame request can wait forever;
  4. a still screen answers with the last frame, flagged not fresh, instead of a failure;
  5. a binder failure in the Shizuku path drops the binding so the next request re-binds.
"""
import os
import re
import sys

DEFAULT_DIR = os.path.join("app", "src", "main", "java", "com", "example", "chessreviewer")

failures = []
checks = 0


def check(name, ok, detail=""):
    global checks
    checks += 1
    if not ok:
        failures.append((name, detail))


def strip_comments(source):
    """The source with comments and doc-comment lines removed, for code-only checks."""
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    kept = []
    for line in source.splitlines():
        stripped = line.lstrip()
        if stripped.startswith(("//", "*")):
            kept.append("")
            continue
        kept.append(re.sub(r"\s//.*$", "", line))
    return "\n".join(kept)


def read(directory, name):
    path = os.path.join(directory, name)
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def enum_constants(source):
    """The constant names declared inside `enum class CaptureError { ... }`."""
    start = source.index("enum class CaptureError")
    open_brace = source.index("{", start)
    depth = 0
    end = open_brace
    for i in range(open_brace, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                end = i
                break
    body = source[open_brace + 1:end]
    names = set()
    for match in re.finditer(r"^\s{4}([A-Z][A-Z0-9_]{2,})\s*[,;(]", body, re.M):
        names.add(match.group(1))
    return names


def block_after(source, index):
    """The text inside the braces that open at or after `index`."""
    open_brace = source.index("{", index)
    depth = 0
    for i in range(open_brace, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[open_brace + 1:i]
    return ""


def bare_uses(source, names, own_enum_file):
    """Constants used without `CaptureError.` and without an import of themselves."""
    imported = set(re.findall(r"import com\.example\.chessreviewer\.CaptureError\.([A-Z0-9_]+)", source))
    hits = []
    for number, line in enumerate(source.splitlines(), 1):
        if line.lstrip().startswith(("*", "//")):
            continue
        for name in names:
            for match in re.finditer(r"(?<![.\w])" + name + r"\b", line):
                head = line[:match.start()].strip()
                if head.endswith("CaptureError."):
                    continue
                if own_enum_file and re.match(r"^\s{4}" + name + r"\s*[,;(]", line):
                    continue
                if name in imported:
                    continue
                hits.append("%s:%d uses %s" % (own_enum_file or "file", number, name))
    return hits


def main():
    directory = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DIR
    secure = read(directory, "SecureCapture.kt")
    shizuku = read(directory, "ShizukuCapture.kt")
    shot = read(directory, "SecureShotService.kt")
    names = enum_constants(secure)

    check("CaptureError has a code for each capture path", len(names) >= 15, "%d codes" % len(names))

    # 1: a bare constant name does not resolve in another file.
    for filename, source in (("ShizukuCapture.kt", shizuku), ("SecureShotService.kt", shot),
                             ("SecureCapture.kt", secure)):
        hits = bare_uses(source, names, filename)
        check("every CaptureError constant in %s is qualified or imported" % filename,
              not hits, "; ".join(hits[:6]))

    # 2: no blocking acquire, and every frame poll is bounded.
    for filename, source in (("SecureCapture.kt", secure),):
        code = strip_comments(source)
        check("no acquireNextImage() in %s" % filename,
              "acquireNextImage" not in code, "a blocking acquire is back")
        polls = [m.start() for m in re.finditer(r"while\s*\(\s*true\s*\)", code)]
        check("%s has at least one frame poll loop" % filename, polls, "no while (true) found")
        for index in polls:
            body = block_after(code, index)
            check("%s frame poll at offset %d carries a deadline" % (filename, index),
                  "deadline" in body, "no deadline in the loop")

    # 3: no join or wait without a timeout.
    for filename, source in (("SecureCapture.kt", secure), ("ShizukuCapture.kt", shizuku),
                             ("SecureShotService.kt", shot)):
        for match in re.finditer(r"\.(join|waitFor)\(\s*\)", source):
            check("%s %s() has a timeout" % (filename, match.group(1)), False,
                  "%s() called with no timeout" % match.group(1))

    # 4: a still screen is answered, not failed; a null or zero-size frame is typed.
    check("ProjectionSession answers a still screen from the last frame",
          "stillScreen(" in secure and "CaptureOutcome.Frame(copy, CaptureOutcome.Source.PROJECTION, false)" in secure)
    check("the last frame is remembered on both decode paths",
          secure.count("remember(") >= 3, "remember() calls: %d" % secure.count("remember("))
    check("CaptureOutcome.Frame carries freshness",
          "val fresh: Boolean = true" in secure)
    check("zero-size frames are typed, not returned",
          all(code in secure for code in ("FRAME_EMPTY", "FRAME_UNREADABLE", "FRAME_TIMEOUT")))
    check("a stopped projection says so",
          "PROJECTION_STOPPED" in secure and "onStop" in secure)

    # 5: the Shizuku branch re-binds instead of returning nothing.
    check("a dead Shizuku binding is dropped, kill included",
          shizuku.count("dropBinding(kill = true)") >= 2,
          "dropBinding(kill = true) calls: %d" % shizuku.count("dropBinding(kill = true)"))
    check("a binder call is bounded by a watchdog",
          "worker.join(budget + WATCHDOG_SLACK_MS)" in shizuku)
    check("ShizukuCapture has a rebind entry point", "fun rebind(" in shizuku)

    # 6: the privileged service cannot hang the app's binder call.
    check("SecureShotService runs its capture on a deadline",
          "worker.join(MAX_CAPTURE_MS + JOIN_SLACK_MS)" in shot)
    check("SecureShotService falls back to screencap",
          "shootScreencap()" in shot)

    for name, detail in failures:
        print("FAIL  " + name + (": " + detail if detail else ""))
    print("%d checks, %d failed" % (checks, len(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
