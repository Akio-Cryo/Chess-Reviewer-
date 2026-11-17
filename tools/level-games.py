#!/usr/bin/env python3
"""Plays a strength setting against a fixed opponent, the way the app plays it.

The app does not play the engine's first choice: it asks for the top few moves and draws
one inside a centipawn budget (Humanizer.chooseMove). This harness does the same, so the
score is the score the app would get, not the engine's.

    python3 tools/level-games.py /path/to/stockfish --games 4 --movetime 60

The opponent is UCI_Elo 1320, Stockfish's own calibrated rating limit. A step labelled 400
losing most of these is correct; a step labelled 1000 being unable to score at all is not.
"""
import argparse
import math
import random
import re
import subprocess


DEPTH_CAP = 24  # Levels.SEARCH_DEPTH_CAP: every `go` in the app carries it
MATE_BASE = 50000   # EngineLine.score counts a mate as 100000 -/+ n*100, so a |score| above this is
                    # a mate; real centipawn evaluations never come near it


class Engine:
    def __init__(self, path, cfg):
        self.cfg = cfg
        self.p = subprocess.Popen([path], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1)
        self.send("uci")
        self.wait("uciok")
        self.send("setoption name Threads value 1")
        self.send("setoption name Hash value 32")
        self.send("isready")
        self.wait("readyok")
        self.apply()

    def send(self, cmd):
        self.p.stdin.write(cmd + "\n")
        self.p.stdin.flush()

    def wait(self, token):
        while True:
            line = self.p.stdout.readline()
            if not line:
                raise RuntimeError("engine exited")
            if line.startswith(token):
                return line.strip()

    def option(self, name, value):
        self.send("setoption name %s value %s" % (name, value))
        self.send("isready")
        self.wait("readyok")

    def apply(self):
        cfg = self.cfg
        self.option("UCI_LimitStrength", "false")
        self.option("Skill Level", 20)
        self.option("MultiPV", cfg.get("multipv", 1))
        if cfg.get("elo") is not None:
            self.option("UCI_Elo", cfg["elo"])
            self.option("UCI_LimitStrength", "true")
        if cfg.get("skill") is not None:
            self.option("Skill Level", cfg["skill"])

    def go_command(self, movetime):
        # mirrors Levels.goCommand: every search carries the depth ceiling and the move time
        cfg = self.cfg
        depth = "go depth %d" % DEPTH_CAP
        if cfg.get("nodes"):
            return "%s nodes %d movetime %d" % (depth, cfg["nodes"], movetime)
        return "%s movetime %d" % (depth, movetime)

    def play(self, position, movetime):
        """the move the app would show, and the lines it was drawn from"""
        self.send(position)
        self.send(self.go_command(movetime))
        lines = {}
        best = None
        while True:
            line = self.p.stdout.readline()
            if not line:
                raise RuntimeError("engine exited")
            if line.startswith("info ") and " pv " in line:
                idx = re.search(r"multipv (\d+)", line)
                cp = re.search(r"score cp (-?\d+)", line)
                mate = re.search(r"score mate (-?\d+)", line)
                move = line.split(" pv ")[1].split()[0]
                i = int(idx.group(1)) if idx else 1
                score = None
                if mate:
                    m = int(mate.group(1))
                    score = 100000 - m * 100 if m > 0 else -100000 - m * 100
                elif cp:
                    score = int(cp.group(1))
                if score is not None:
                    lines[i] = (move, score)
            if line.startswith("bestmove"):
                best = line.split()[1]
                break
        ranked = [lines[k] for k in sorted(lines)]
        return choose(ranked, best, self.cfg.get("budget", 0),
                      self.cfg.get("mistake", 1.0), RNG)

    def fen_after(self, position, move):
        self.send(position + " moves " + move)
        self.send("d")
        while True:
            line = self.p.stdout.readline()
            if line.startswith("Fen: "):
                return line[5:].strip()


RNG = random.Random(20261115)


def choose(ranked, best, budget, mistake, rng):
    """Humanizer.chooseMove, in Python"""
    scored = [r for r in ranked if is_move(r[0])]
    own = best if is_move(best) else (scored[0][0] if scored else best)
    lines = scored if scored else ranked
    if not lines:
        return own
    top_move, top_score = lines[0]
    if mistake < 1.0 and rng.random() >= mistake:
        return own
    if len(lines) < 2:
        return own
    # a mate score is not a centipawn evaluation: the app plays its own move rather than weigh
    # alternatives against it (and exp() would overflow on the difference)
    if abs(top_score) >= MATE_BASE:
        return own
    # only lines with a real centipawn score can be weighed: the app drops mate lines here too
    pool = [(m, s) for m, s in lines if m != own and abs(s) < MATE_BASE and top_score - s <= budget]
    if not pool:
        return own
    temperature = max(budget / 4.0, 4.0)
    weights = [math.exp(-(top_score - s) / temperature) for _, s in pool]
    total = sum(weights)
    if total <= 0.0:
        return own
    roll = rng.random() * total
    for (m, _), w in zip(pool, weights):
        roll -= w
        if roll <= 0:
            return m
    return pool[-1][0]


def is_move(move):
    """Humanizer.isMove, in Python: `bestmove (none)` and an empty line are not moves"""
    return bool(move) and len(move) >= 4 and "a" <= move[0] <= "h"


RNG = random.Random(20261115)


SETTINGS = [
    dict(name="400 (skill0 n8k b400 m45)", skill=0, nodes=8000, multipv=5, budget=400, mistake=0.45),
    dict(name="600 (skill0 n10k b300 m35)", skill=0, nodes=10000, multipv=5, budget=300, mistake=0.35),
    dict(name="800 (skill1 n12k b200 m25)", skill=1, nodes=12000, multipv=5, budget=200, mistake=0.25),
    dict(name="1000 (skill2 n16k b120 m15)", skill=2, nodes=16000, multipv=5, budget=120, mistake=0.15),
    dict(name="1320 (UCI_Elo b32)", elo=1320, multipv=5, budget=32),
]


def play_game(white, black, movetime, max_plies=160):
    fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    for _ in range(max_plies):
        mover = white if fen.split()[1] == "w" else black
        move = mover.play("position fen " + fen, movetime)
        if move in ("(none)", "0000"):
            return (1.0 if fen.split()[1] == "b" else 0.0)
        nxt = mover.fen_after("position fen " + fen, move)
        if nxt == fen:
            return 0.5
        fen = nxt
    return 0.5


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("engine")
    ap.add_argument("--games", type=int, default=4)
    ap.add_argument("--movetime", type=int, default=60)
    args = ap.parse_args()

    rows = []
    for cfg in SETTINGS:
        points = 0.0
        for g in range(args.games):
            a = Engine(args.engine, cfg)
            b = Engine(args.engine, dict(name="reference", elo=1320))
            white, black = (a, b) if g % 2 == 0 else (b, a)
            try:
                score = play_game(white, black, args.movetime)
            except Exception as exc:                       # noqa: BLE001
                print("error", cfg["name"], exc, file=__import__("sys").stderr)
                score = 0.5
            points += score if white is a else 1.0 - score
            a.send("quit")
            b.send("quit")
        rows.append((cfg["name"], args.games, points))

    print("\n%-34s %5s %6s" % ("setting", "games", "score"))
    for name, games, points in rows:
        print("%-34s %5d %5.1f/%d" % (name, games, points, games))


if __name__ == "__main__":
    main()
