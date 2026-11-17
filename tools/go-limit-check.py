#!/usr/bin/env python3
"""Runs the app's own `go` lines against a real Stockfish and checks every one comes back.

    python3 tools/go-limit-check.py /path/to/stockfish --jar levelcheck.jar

The commands are not written here: tools/LevelCheck.kt prints them with --print-go, so this
checks the strings the app sends, not a copy of them. Each line is `setting<TAB>command`, the
strength setting the app would push into the engine followed by the `go` line it would then send.

Three things are checked:
  1. every `go` line comes back with a bestmove inside its own movetime plus the app's grace;
  2. the node budget on the low steps still comes back (a Skill Level search with a node cap is
     the path that used to leave the panel on "Thinking..." for good);
  3. `go movetime 0` and a bare `go` do NOT come back, which is the failure the clamp prevents,
     and `stop` then makes the engine answer as the app does when a search overruns.
"""
import argparse
import os
import select
import subprocess
import sys
import time

GRACE_MS = 3000          # Levels.SEARCH_GRACE_MS
SLACK_MS = 2000          # start-up and scheduling noise allowed on top of the grace
HANG_PROBE_S = 3.0       # a command that has not answered in this long is treated as unlimited


class Engine:
    def __init__(self, path):
        self.p = subprocess.Popen([path], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  bufsize=0)
        self.buf = b""
        self.send("uci")
        if self.expect("uciok", 10.0) is None:
            raise RuntimeError("no uciok from %s" % path)
        self.send("setoption name Threads value 1")
        self.send("setoption name Hash value 64")
        self.send("isready")
        if self.expect("readyok", 10.0) is None:
            raise RuntimeError("engine never became ready")

    def send(self, cmd):
        self.p.stdin.write((cmd + "\n").encode())
        self.p.stdin.flush()

    def readline(self, timeout):
        deadline = time.monotonic() + timeout
        while b"\n" not in self.buf:
            remain = deadline - time.monotonic()
            if remain <= 0:
                return None
            ready, _, _ = select.select([self.p.stdout], [], [], remain)
            if not ready:
                return None
            chunk = os.read(self.p.stdout.fileno(), 65536)
            if not chunk:
                return None
            self.buf += chunk
        line, self.buf = self.buf.split(b"\n", 1)
        return line.decode("utf-8", "replace")

    def expect(self, token, timeout):
        deadline = time.monotonic() + timeout
        while True:
            remain = deadline - time.monotonic()
            if remain <= 0:
                return None
            line = self.readline(remain)
            if line is None:
                return None
            if line.startswith(token):
                return line

    def apply(self, setting):
        """the same option writes StockfishEngine.applyStrength makes"""
        self.send("setoption name UCI_LimitStrength value false")
        self.send("setoption name Skill Level value 20")
        self.send("setoption name MultiPV value 5")
        kind, _, value = setting.partition(":")
        if kind == "elo":
            self.send("setoption name UCI_Elo value %s" % value)
            self.send("setoption name UCI_LimitStrength value true")
        elif kind == "skill":
            self.send("setoption name Skill Level value %s" % value)
        self.send("isready")
        self.expect("readyok", 5.0)

    def run(self, command, timeout):
        """seconds until bestmove, or None if it never came inside the timeout"""
        self.send("position startpos moves e2e4 e7e5 g1f3 b8c6")
        started = time.monotonic()
        self.send(command)
        deadline = started + timeout
        while True:
            remain = deadline - time.monotonic()
            if remain <= 0:
                return None
            line = self.readline(remain)
            if line is None:
                return None
            if line.startswith("bestmove"):
                return time.monotonic() - started

    def quit(self):
        try:
            self.send("quit")
            self.p.wait(timeout=5)
        except Exception:
            self.p.kill()


def movetime_ms(command):
    parts = command.split()
    if "movetime" in parts:
        return int(parts[parts.index("movetime") + 1])
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stockfish")
    ap.add_argument("--jar", default="levelcheck.jar",
                    help="the jar tools/LevelCheck.kt builds (kotlinc Levels.kt Humanizer.kt "
                         "tools/LevelCheck.kt -include-runtime -d levelcheck.jar)")
    args = ap.parse_args()

    if not os.path.exists(args.jar):
        print("SKIP no %s: build it with the kotlinc line in tools/LevelCheck.kt" % args.jar)
        return 2

    rows = []
    out = subprocess.run(["java", "-jar", args.jar, "--print-go"],
                         capture_output=True, text=True)
    if out.returncode != 0:
        print("FAIL --print-go exited %d\n%s" % (out.returncode, out.stderr[-2000:]))
        return 1
    for line in out.stdout.splitlines():
        if "\t" in line:
            setting, command = line.split("\t", 1)
            rows.append((setting, command))

    failures = 0
    engine = Engine(args.stockfish)
    try:
        for setting, command in rows:
            engine.apply(setting)
            ms = movetime_ms(command)
            limit = (ms + GRACE_MS + SLACK_MS) / 1000.0
            took = engine.run(command, limit)
            ok = took is not None and took <= limit
            print("%s %-10s %-46s %s" % (
                "ok  " if ok else "FAIL", setting, command,
                "%.2fs" % took if took is not None else "no bestmove in %.1fs" % limit))
            if not ok:
                failures += 1
                engine.send("stop")
                engine.expect("bestmove", 2.0)

        # the failure the clamp exists for: an unlimited search never answers on its own
        for label, command in (("movetime 0", "go movetime 0"), ("bare go", "go")):
            engine.apply("full")
            took = engine.run(command, HANG_PROBE_S)
            ok = took is None
            print("%s probe %-40s %s" % (
                "ok  " if ok else "FAIL", command,
                "no answer in %.0fs, as an unlimited search should" % HANG_PROBE_S if ok
                else "answered in %.2fs, so it was not unlimited" % took))
            if not ok:
                failures += 1
            # the app's recovery: ask it to stop and take what it has
            engine.send("stop")
            answered = engine.expect("bestmove", 3.0)
            ok = answered is not None
            print("%s stop after %-31s %s" % (
                "ok  " if ok else "FAIL", command,
                "bestmove came back" if ok else "still no bestmove"))
            if not ok:
                failures += 1
    finally:
        engine.quit()

    print("\n%d command(s), %d failure(s)" % (len(rows), failures))
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
