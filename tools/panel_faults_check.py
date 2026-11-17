#!/usr/bin/env python3
"""One PASS or FAIL line per rule, each naming the file and line its evidence sits on.

The three defects this project has already fixed once must not come back, and the panel wiring that
surrounds them must stay. The rules are static: nothing here runs Android, it reads the Kotlin.

  R1 (a) MainActivity declares EXTRA_RECONSENT and reads it in onCreate and in onNewIntent, and
          each read asks for a consent. A constant that does not exist does not compile; a read
          that ignores the extra is the "Retry does nothing" defect in another shape.
  R2 (b) CaptureService reaches that constant only as MainActivity.EXTRA_RECONSENT: no
          redefinition, no import of it, no second copy of the "reconsent" string. Two copies of
          the name are how the two files drift apart.
  R3 (c) MainActivity gives faultView its text and makes it visible, from a function a lifecycle
          path calls. The defect was a view that was created, set GONE, and never filled.
  R4 (d) CaptureService passes outcome.fresh into Frame. The defect was Frame(outcome.bitmap, true),
          which re-reads an unchanged frame on every tick.
  R5 (e) ShizukuCapture imports every CaptureError constant it writes unqualified. A bare enum
          constant does not resolve across files, and that is what stopped the module building.
  R6     every capture cause is produced somewhere, and the panel's Retry reaches each retryable one.
  R7     the panel rebuilds itself when the overlay permission arrives, and asks for a fresh
          consent through MainActivity's extra instead of starting a second service.
  R8     the app screen puts the failure first, offers the cause's own button, and announces it once.
  R9     no generic "can't see the board" line is left in either file.

    python3 tools/panel_faults_check.py                # the app this script sits in
    python3 tools/panel_faults_check.py --src PATH     # or a copy of it

Exit code 0 when every rule passes, 1 when one fails (the failing lines are printed under it),
2 when the source folder is not there.
"""
import argparse
import pathlib
import re
import sys

FAILS = []

ACT = "MainActivity.kt"
SVC = "CaptureService.kt"
SHZ = "ShizukuCapture.kt"
SEC = "SecureCapture.kt"

LIFECYCLE = ("onCreate", "onStart", "onPostResume", "onResume", "onNewIntent",
             "onActivityResult", "onWindowFocusChanged")

FUN_HEAD = re.compile(r"(?:^|\s)fun\s+(\w+)\s*\(")


def line_of(text, index):
    """1-based line of a character offset."""
    return text.count("\n", 0, max(index, 0)) + 1


def brace_body(text, pos):
    """The (start, end) of the braced body after a function's parameter list, or None.

    `pos` is just past the opening paren of the parameters, so the scan starts inside it. An
    expression body (`fun f() = ...`) answers None: there is no block to look inside.
    """
    depth = 1
    i = pos
    while i < len(text):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif depth == 0 and c == "=":
            return None
        elif depth == 0 and c == "{":
            inner = 0
            for j in range(i, len(text)):
                if text[j] == "{":
                    inner += 1
                elif text[j] == "}":
                    inner -= 1
                    if inner == 0:
                        return i + 1, j
            return None
        i += 1
    return None


def function_spans(text):
    """{name: (body_start, body_end)} for every function with a braced body."""
    spans = {}
    for m in FUN_HEAD.finditer(text):
        span = brace_body(text, m.end())
        if span:
            spans.setdefault(m.group(1), span)
    return spans


def body_of(text, header_regex):
    """The brace-balanced body of the first block whose header matches, or ''."""
    m = re.search(header_regex, text)
    if not m:
        return ""
    i = text.find("{", m.end() - 1)
    if i < 0:
        return ""
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[i + 1:j]
    return ""


def enum_constants(text):
    """The constant names declared inside `enum class CaptureError { ... }`."""
    try:
        start = text.index("enum class CaptureError")
    except ValueError:
        return set()
    open_brace = text.index("{", start)
    depth = 0
    end = open_brace
    for i in range(open_brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                end = i
                break
    body = text[open_brace + 1:end]
    return set(re.findall(r"^\s{4}([A-Z][A-Z0-9_]{2,})\s*[,;(]", body, re.M))


def is_comment(line):
    return line.strip().startswith(("//", "*", "/*"))


def code_lines(text):
    """(line number, line) for every line that is not inside a comment."""
    return [(n, line) for n, line in enumerate(text.splitlines(), 1) if not is_comment(line)]


# ---------------------------------------------------------------------------- the five rules


def rule_r1(act):
    v = []
    m = re.search(r'const\s+val\s+EXTRA_RECONSENT\s*=\s*"reconsent"', act)
    if not m:
        at = act.find("companion object")
        v.append((ACT, line_of(act, at) if at >= 0 else 1,
                  'no `const val EXTRA_RECONSENT = "reconsent"`; CaptureService names a constant '
                  "that does not exist, so the module does not compile"))
    spans = function_spans(act)
    # a helper that reads the extra itself, e.g. `consumeReconsent(intent)`, counts as the lifecycle
    # path reading it: the constant has to be reached, not spelled on that exact line
    readers = {fn for fn, (s, e) in spans.items()
               if re.search(r"getBooleanExtra\(\s*EXTRA_RECONSENT", act[s:e])}
    for fn in ("onCreate", "onNewIntent"):
        span = spans.get(fn)
        if not span:
            v.append((ACT, 1, "no %s(); the extra is never read on that path" % fn))
            continue
        body_start, body_end = span
        body = act[body_start:body_end]
        read = re.search(r"getBooleanExtra\(\s*EXTRA_RECONSENT", body)
        helper = None
        if not read:
            for name in sorted(readers):
                hit = re.search(r"\b" + name + r"\s*\(", body)
                if hit:
                    helper = (name, hit)
                    break
        if not read and not helper:
            v.append((ACT, line_of(act, body_start),
                      "%s does not read EXTRA_RECONSENT, directly or through a helper" % fn))
            continue
        acts = "requestCaptureConsent()" in body
        if helper:
            s2, e2 = spans[helper[0]]
            acts = acts or "requestCaptureConsent()" in act[s2:e2]
        if not acts:
            at = body_start + (read.start() if read else helper[1].start())
            v.append((ACT, line_of(act, at),
                      "%s reads EXTRA_RECONSENT and then does nothing with it" % fn))
    evidence = "%s:%d" % (ACT, line_of(act, m.start())) if m else ""
    return evidence, v


def rule_r2(svc):
    v = []
    code = code_lines(svc)
    qualified = [n for n, line in code if "MainActivity.EXTRA_RECONSENT" in line]
    if not qualified:
        v.append((SVC, 1, "never asks MainActivity for a consent: MainActivity.EXTRA_RECONSENT is absent"))
    for n, line in code:
        if re.search(r"\bconst\s+val\s+EXTRA_RECONSENT", line):
            v.append((SVC, n, "redefines the constant here; it belongs to MainActivity"))
        if re.match(r"\s*import\s+.*EXTRA_RECONSENT", line):
            v.append((SVC, n, "imports the constant instead of writing MainActivity.EXTRA_RECONSENT"))
            continue
        if '"reconsent"' in line:
            v.append((SVC, n, 'a second copy of the string "reconsent"'))
        for hit in re.finditer(r"(?<![.\w])EXTRA_RECONSENT\b", line):
            if line[:hit.start()].rstrip().endswith("MainActivity."):
                continue
            v.append((SVC, n, "bare EXTRA_RECONSENT; qualify it as MainActivity.EXTRA_RECONSENT"))
    evidence = "%s:%d" % (SVC, qualified[0]) if qualified else ""
    return evidence, v


def fault_view_writes(body):
    """(text assignment, VISIBLE assignment) made to faultView itself, not to any other view.

    `button.text = "Allow overlay"` is a text assignment to a different view, so the receiver has to
    be the field or a local alias of it: `val view = faultView ?: return` then `view.text = ...`.
    """
    aliases = set(re.findall(r"val\s+(\w+)\s*=\s*faultView\b", body))
    aliases.add("faultView")
    # both spellings reach the field: `faultView?.text =` (safe call) and `faultView.text =` (direct)
    receiver = r"\b(?:%s)\s*(?:\?\.|\.)\s*" % "|".join(re.escape(a) for a in sorted(aliases))
    return (re.search(receiver + r"text\s*=", body),
            re.search(receiver + r"visibility\s*=\s*View\.VISIBLE", body))


def rule_r3(act):
    v = []
    field = re.search(r"private\s+var\s+faultView\s*:\s*TextView\?", act)
    if not field:
        v.append((ACT, 1, "no `private var faultView: TextView?` field"))
    spans = function_spans(act)
    touching = {fn: span for fn, span in spans.items() if "faultView" in act[span[0]:span[1]]}
    renderers = {}
    for fn, (s, e) in touching.items():
        text_write, visible_write = fault_view_writes(act[s:e])
        if text_write and visible_write:
            renderers[fn] = (s, e)
    if not touching:
        v.append((ACT, line_of(act, field.start()) if field else 1, "nothing ever reads faultView"))
    elif not renderers:
        for fn in sorted(touching):
            s, e = touching[fn]
            text_write, visible_write = fault_view_writes(act[s:e])
            missing = []
            if not text_write:
                missing.append("assigns it no text")
            if not visible_write:
                missing.append("never makes it VISIBLE")
            if missing:
                v.append((ACT, line_of(act, s), "%s %s, so the screen shows nothing" % (fn, " and ".join(missing))))
    calls = []
    for fn in LIFECYCLE:
        span = spans.get(fn)
        if not span:
            continue
        body = act[span[0]:span[1]]
        for render in sorted(renderers):
            hit = re.search(r"\b" + render + r"\s*\(", body)
            if hit:
                calls.append("%s:%d calls %s()" % (ACT, line_of(act, span[0] + hit.start()), render))
    if renderers and not calls:
        v.append((ACT, line_of(act, field.start()) if field else 1,
                  "faultView is filled by %s but no lifecycle path calls it, so the screen never "
                  "shows it" % "/".join(sorted(renderers))))
    evidence = calls[0] if calls else ""
    return evidence, v


def rule_r4(svc):
    v = []
    if not re.search(r"class\s+Frame\(\s*val\s+bitmap\s*:\s*Bitmap\s*,\s*val\s+fresh\s*:\s*Boolean", svc):
        v.append((SVC, 1, "Frame no longer carries a `fresh` flag"))
    good = re.search(r"Frame\(\s*outcome\.bitmap\s*,\s*outcome\.fresh\s*\)", svc)
    if not good:
        v.append((SVC, 1, "no Frame(outcome.bitmap, outcome.fresh) anywhere: the projection path "
                          "drops the freshness of the frame"))
    for m in re.finditer(r"Frame\(\s*([^()]*?)\s*\)", svc):
        parts = [p.strip() for p in m.group(1).split(",")]
        if len(parts) != 2:
            continue
        source, fresh = parts
        if "outcome" in source and fresh in ("true", "false"):
            v.append((SVC, line_of(svc, m.start()),
                      "Frame(..., %s) hardcodes freshness for %s; pass .fresh" % (fresh, source)))
    evidence = "%s:%d" % (SVC, line_of(svc, good.start())) if good else ""
    return evidence, v


def rule_r5(shz, sec):
    v = []
    names = enum_constants(sec)
    if not names:
        return "", [(SEC, 1, "CaptureError has no constants to check against")]
    imports = set(re.findall(r"import\s+com\.example\.chessreviewer\.CaptureError\.([A-Z0-9_]+)", shz))
    for n, line in code_lines(shz):
        if re.match(r"\s*import\s+", line):
            continue
        for name in names:
            for _ in re.finditer(r"(?<![.\w])" + name + r"\b", line):
                if name in imports:
                    continue
                v.append((SHZ, n, "%s is written unqualified and is not imported, so the module "
                                   "stops compiling" % name))
    first = re.search(r"import\s+com\.example\.chessreviewer\.CaptureError\.", shz)
    evidence = ""
    if first:
        evidence = "%s:%d imports %d of %d CaptureError constants" % (
            SHZ, line_of(shz, first.start()), len(imports), len(names))
    return evidence, v


def rule_r6(svc):
    v = []
    enum = body_of(svc, r"enum class CaptureFault\(")
    if not enum:
        return "", [(SVC, 1, "CaptureFault is not an enum of causes any more")]
    base = line_of(svc, svc.find("enum class CaptureFault"))
    causes = re.findall(r"^\s{4}([A-Z][A-Z0-9_]*)\(", enum, re.M)
    if len(causes) < 10:
        v.append((SVC, base, "only %d capture causes are declared" % len(causes)))
    blocks = re.findall(r"^\s{4}([A-Z][A-Z0-9_]*)\((.*?)\n\s{4}\)", enum, re.M | re.S)
    not_retryable = {name for name, props in blocks if "retryable = false" in props}
    needs_consent = {name for name, props in blocks if "needsConsent = true" in props}
    if not_retryable != {"OVERLAY_PERMISSION"}:
        v.append((SVC, base, "the causes that are not retryable from the panel changed: %s"
                  % sorted(not_retryable)))
    if needs_consent != {"NEEDS_CONSENT"}:
        v.append((SVC, base, "the causes that need an Activity changed: %s" % sorted(needs_consent)))
    retry_body = body_of(svc, r"private fun retry\(\)")
    if not retry_body:
        v.append((SVC, 1, "no retry() in the panel"))
    else:
        handled = set(not_retryable) | (needs_consent if "f.needsConsent" in retry_body else set())
        unhandled = [c for c in causes if c not in handled and c not in retry_body]
        if unhandled:
            v.append((SVC, line_of(svc, svc.find("private fun retry()")),
                      "Retry reaches no branch for %s" % unhandled))
    gate = "retryView?.visibility = if (f != null && f.retryable)"
    if gate not in svc:
        v.append((SVC, 1, "Retry is no longer shown only for a retryable cause"))
    unproduced = []
    for c in causes:
        declarations = len(re.findall(r"^\s{4}" + c + r"\(", svc, re.M))
        uses = len(re.findall(r"CaptureFault\." + c + r"\b", svc))
        if uses < 1 and declarations < 1:
            unproduced.append(c)
    if unproduced:
        v.append((SVC, base, "declared but never produced: %s" % unproduced))
    lines = svc.splitlines()
    for fn, cause in (("forMissingBoard", "BOARD_NOT_FOUND"), ("forUnreadableBoard", "BOARD_UNREADABLE")):
        snippet, at = "", 0
        for i, cand in enumerate(lines):
            if ("fun " + fn + "(") in cand and cand.strip().startswith("fun "):
                snippet = cand + "\n" + (lines[i + 1] if i + 1 < len(lines) else "")
                at = i + 1
                break
        if not ("CaptureFault." + cause in snippet and "problem" in snippet):
            v.append((SVC, at or 1, "%s no longer keeps the module's reason as the detail" % fn))
    if "CaptureFault.UNEXPECTED" not in svc:
        v.append((SVC, 1, "an error thrown inside the loop is no longer caught and named"))
    evidence = "%s:%d" % (SVC, base)
    return evidence, v


def rule_r7(svc):
    v = []
    at = svc.find("private fun ensurePanel()")
    if at < 0:
        v.append((SVC, 1, "no ensurePanel(): the panel cannot come back without a restart"))
    tick = body_of(svc, r"private fun tick\(\)")
    if "ensurePanel()" not in tick:
        v.append((SVC, line_of(svc, svc.find("private fun tick(")) if "private fun tick(" in svc else 1,
                  "the loop never rebuilds the panel once the overlay permission arrives"))
    request = svc.find("putExtra(MainActivity.EXTRA_RECONSENT, true)")
    if request < 0:
        v.append((SVC, 1, "the panel does not ask for a consent through MainActivity's extra"))
    if "if (screenW > 0) screenW else dm.widthPixels" not in svc:
        v.append((SVC, 1, "the panel no longer falls back to the display width"))
    evidence = "%s:%d" % (SVC, line_of(svc, at)) if at >= 0 else ""
    if request >= 0:
        evidence = "%s:%d asks for the consent" % (SVC, line_of(svc, request))
    return evidence, v


def rule_r8(act):
    v = []
    fault_at = act.find("col.addView(faultView)")
    info_at = act.find("col.addView(info)")
    if fault_at < 0 or info_at < 0 or fault_at > info_at:
        v.append((ACT, line_of(act, fault_at) if fault_at >= 0 else 1,
                  "the failure is not the first thing on the screen"))
    if "col.addView(faultBtn)" not in act:
        v.append((ACT, 1, "the failure has no button of its own"))
    show_at = act.find("private fun showFault(")
    show = body_of(act, r"private fun showFault\(\)")
    if not show:
        v.append((ACT, line_of(act, show_at) if show_at >= 0 else 1, "no showFault()"))
    else:
        for needle, msg in (
            ("CaptureFault.OVERLAY_PERMISSION", "the overlay cause offers no button"),
            ("CaptureFault.NEEDS_CONSENT", "the spent-token cause asks for no consent"),
            ("f.isCapture", "a capture cause offers no retry"),
            ("toastedFault", "a failure is announced on every visit"),
        ):
            if needle not in show:
                v.append((ACT, line_of(act, show_at), msg))
        if not ("else ->" in show and "button.visibility = View.GONE" in show):
            v.append((ACT, line_of(act, show_at),
                      "a board cause pretends a new consent fixes it"))
    request = body_of(act, r"private fun requestCaptureConsent\(\)")
    if not request:
        v.append((ACT, 1, "no requestCaptureConsent()"))
    else:
        if "CaptureFault.OVERLAY_PERMISSION" not in request:
            v.append((ACT, line_of(act, act.find("private fun requestCaptureConsent(")),
                      "the overlay preflight names no cause"))
        if "createScreenCaptureIntent" not in request:
            v.append((ACT, line_of(act, act.find("private fun requestCaptureConsent(")),
                      "the consent dialog is not asked for from here"))
    resume = body_of(act, r"override fun onResume")
    if "showFault()" not in resume:
        v.append((ACT, 1, "the app screen does not refresh the cause when it comes back"))
    return "", v


def rule_r9(texts):
    v = []
    for label, text in texts:
        for n, line in enumerate(text.splitlines(), 1):
            if "can't see the board" in line.lower() and not is_comment(line):
                v.append((label, n, "a generic 'can't see the board' line is back; it names no cause"))
    return "", v


def strip_line(text, needle, limit=1):
    """The text with the first `limit` lines containing `needle` removed: (text, removed)."""
    out, removed = [], 0
    for line in text.splitlines(keepends=True):
        if removed < limit and needle in line:
            removed += 1
            continue
        out.append(line)
    return "".join(out), removed


def strip_line_in_body(text, function, needle):
    """The same, but only inside one function's body, so a shared line elsewhere survives."""
    span = function_spans(text).get(function)
    if not span:
        return text, 0
    body, removed = strip_line(text[span[0]:span[1]], needle)
    return text[:span[0]] + body + text[span[1]:], removed


def strip_calls(text, function):
    """The text with every `function()` call that sits on a line of its own removed."""
    out, removed = [], 0
    for line in text.splitlines(keepends=True):
        if line.strip() == function + "()":
            removed += 1
            continue
        out.append(line)
    return "".join(out), removed


def self_test(svc, act, shz, sec):
    """Break each fixed defect on purpose and require the rule that owns it to notice.

    A rule that passes on the tree because it cannot see anything is worthless, so every rule here
    is shown failing against a mutation of the same file it reads. Nothing is written: the
    mutations are strings in memory.
    """
    cases = []

    def case(rid, description, mutated, run):
        noticed = bool(run(mutated)[1])
        cases.append((rid, description, noticed))

    act_no_decl, _ = strip_line(act, 'const val EXTRA_RECONSENT = "reconsent"')
    case("R1", "the constant is deleted from MainActivity", act_no_decl, rule_r1)

    act_no_read, _ = strip_line_in_body(act, "onNewIntent", "consumeReconsent(intent)")
    case("R1", "onNewIntent stops reading the extra", act_no_read, rule_r1)

    act_no_text, _ = strip_line(act, "faultView?.text = f.panelText(screenDetail)")
    case("R3", "faultView is never given its text", act_no_text, rule_r3)

    act_no_call, removed = strip_calls(act, "showFault")
    case("R3", "no lifecycle path calls showFault() (%d calls removed)" % removed, act_no_call, rule_r3)

    svc_hardcoded = svc.replace("Frame(outcome.bitmap, outcome.fresh)", "Frame(outcome.bitmap, true)")
    case("R4", "the projection path hardcodes fresh = true", svc_hardcoded, rule_r4)

    shz_no_import, _ = strip_line(shz, "import com.example.chessreviewer.CaptureError.SHIZUKU_TIMEOUT")
    case("R5", "one CaptureError import is dropped while its use stays", shz_no_import,
         lambda text: rule_r5(text, sec))

    svc_redefined = svc.replace("putExtra(MainActivity.EXTRA_RECONSENT, true)",
                               "putExtra(EXTRA_RECONSENT, true)").replace(
        "companion object {",
        'companion object {\n        private const val EXTRA_RECONSENT = "reconsent"', 1)
    case("R2", "CaptureService keeps its own copy of the constant", svc_redefined, rule_r2)

    broken = []
    for rid, description, noticed in cases:
        print(("PASS  " if noticed else "FAIL  ") + "self-test %s: %s" % (rid, description))
        if not noticed:
            broken.append(rid)
    return len(cases), broken


# ---------------------------------------------------------------------------- reporting


def report(rid, tag, title, evidence, violations):
    if violations:
        print("FAIL  %s (%s) %s" % (rid, tag, title))
        for name, line, message in violations:
            print("        %s:%d  %s" % (name, line, message))
        FAILS.append(rid)
    else:
        shown = "  [%s]" % evidence if evidence else ""
        print("PASS  %s (%s) %s%s" % (rid, tag, title, shown))


def main():
    here = pathlib.Path(__file__).resolve().parent
    ap = argparse.ArgumentParser(description="one PASS or FAIL line per panel rule")
    ap.add_argument("--src", default=str(here.parent / "app/src/main/java/com/example/chessreviewer"),
                    help="the folder holding the app's .kt files (default: the app next to this script)")
    ap.add_argument("--self-test", action="store_true",
                    help="break each fixed defect again, in memory, and require its rule to notice")
    args = ap.parse_args()

    src = pathlib.Path(args.src)
    paths = {name: src / name for name in (SVC, ACT, SHZ, SEC)}
    missing = [name for name, path in paths.items() if not path.is_file()]
    if missing:
        print("cannot check %s: missing %s" % (src, ", ".join(missing)))
        return 2

    svc = paths[SVC].read_text(encoding="utf-8")
    act = paths[ACT].read_text(encoding="utf-8")
    shz = paths[SHZ].read_text(encoding="utf-8")
    sec = paths[SEC].read_text(encoding="utf-8")

    if args.self_test:
        print("self-test: %s" % src)
        ran, broken = self_test(svc, act, shz, sec)
        print("\n%d self-tests, %d not noticed" % (ran, len(broken)))
        return 1 if broken else 0

    print("panel faults: %s" % src)
    rules = (
        ("R1", "a", "MainActivity declares EXTRA_RECONSENT and reads it in onCreate and onNewIntent",
         lambda: rule_r1(act)),
        ("R2", "b", "CaptureService names it only as MainActivity.EXTRA_RECONSENT", lambda: rule_r2(svc)),
        ("R3", "c", "MainActivity fills faultView and shows it from a lifecycle path", lambda: rule_r3(act)),
        ("R4", "d", "CaptureService passes outcome.fresh into Frame", lambda: rule_r4(svc)),
        ("R5", "e", "ShizukuCapture imports every CaptureError constant it uses unqualified",
         lambda: rule_r5(shz, sec)),
        ("R6", "-", "every capture cause is produced and Retry reaches each retryable one", lambda: rule_r6(svc)),
        ("R7", "-", "the panel rebuilds itself and asks for a consent through MainActivity", lambda: rule_r7(svc)),
        ("R8", "-", "the app screen shows the failure first, with the cause's own button", lambda: rule_r8(act)),
        ("R9", "-", "no generic 'can't see the board' line is left",
         lambda: rule_r9(((SVC, svc), (ACT, act)))),
    )
    for rid, tag, title, run in rules:
        try:
            evidence, violations = run()
        except Exception as exc:  # a rule that cannot read the source is a failure, not a crash
            evidence, violations = "", [(src.name, 1, "the check itself raised %r" % (exc,))]
        report(rid, tag, title, evidence, violations)

    print("\n%d rules, %d failed" % (len(rules), len(FAILS)))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
