# Chess Reviewer

An Android overlay that watches a chess game on screen, reads the board, and shows the best
move from Stockfish. Protected screens (`FLAG_SECURE`) are read through root, or through
[Shizuku](https://shizuku.rikka.app) on a phone without root.

## Using it

1. Tap **Allow overlay** and switch on "Display over other apps".
2. Tap **Start** and accept the screen-capture prompt.
3. Open the chess game so the whole board is visible; a small panel floats at the bottom.
4. With the pieces in the start position, tap **Learn** (once per piece set).
5. Play. The board is found automatically and the best move updates by itself.

`Flip` swaps which side is at the bottom, `Turn` swaps whose move it is, `Save` writes what the
app sees to `Pictures/ChessReviewer` so you can send it along when something looks wrong.

## Engine strength and human pace

Strength is Stockfish's own weakening plus one scored choice, so the moves really do get weaker as
the number goes down. It uses the two settings the engine has, in the order they work: `UCI_Elo`
with `UCI_LimitStrength` from 1320 up, and `Skill Level` with a node budget below that.

### The level table

Eleven steps, weakest first. The last two columns are the values in `Levels.steps`; the panel's
**Elo** button walks this list, a tap up and a long press down, so going from full strength back
to 400 is one gesture and not ten.

| Elo shown | Name | Asked of the engine | Move may give up | Engine's own pick played |
| --- | --- | --- | --- | --- |
| 400 | Absolute beginner | `Skill Level` 0, 8000 nodes | 400 cp | 55% of the time |
| 600 | Beginner | `Skill Level` 0, 10000 nodes | 300 cp | 65% |
| 800 | Novice | `Skill Level` 1, 12000 nodes | 200 cp | 75% |
| 1000 | Improver | `Skill Level` 2, 16000 nodes | 120 cp | 85% |
| 1320 | Casual | `UCI_Elo` 1320 | 32 cp | always |
| 1600 | Club | `UCI_Elo` 1600 | 27 cp | always |
| 1900 | Strong club | `UCI_Elo` 1900 | 22 cp | always |
| 2200 | Expert | `UCI_Elo` 2200 | 16 cp | always |
| 2500 | Master | `UCI_Elo` 2500 | 11 cp | always |
| 2800 | Super GM | `UCI_Elo` 2800 | 6 cp | always |
| full strength | Full strength | nothing | 6 cp | always |

`UCI_Elo` is clamped to the range this particular build advertises in its `uci` reply, so a build
with no `UCI_Elo` at all is driven by Skill Level across the whole range instead.

Below 1320 the engine is weakened in two ways and no more. `Skill Level` decides how far from its
best move the engine will go, and the node budget decides how well it can score the moves it is
choosing between. The engine's own pick is played most of the time; the rest of the time the move
is drawn from a scored alternative inside the centipawn budget, which is the deliberate mistake
that takes the step below the floor.

### How each search is run

Every search the app sends is `go depth 24` plus a move time, and neither part is optional:

```
go depth 24 nodes 8000 movetime 612          a Skill Level step
go depth 24 movetime 874                     a UCI_Elo step
```

1. **The move time is clamped** to 50 ms at the short end and 30 s at the long end
   (`Levels.thinkTimeMs`). The engine treats `movetime 0` as no limit at all, so the clamp is what
   stops a caller passing zero and starting a search nothing will end.
2. **No `go` is ever sent bare.** Stockfish's `parse_limits` only sets a limit when the line names
   one, and a bare `go` leaves every limit at zero, so the search runs to maximum depth. That is
   how the panel used to sit on "Thinking" with no move and never come back.
3. **The 24 is a ceiling, not a level setting.** No step shortens the search: the low steps get a
   node budget instead, so the engine keeps full width and the quiescence search and still sees a
   capture before it plays one. The ceiling exists only so a wedged clock cannot leave a search
   running forever.
4. **A search that overruns is stopped.** Past its own move time plus 3 s
   (`Levels.SEARCH_GRACE_MS`) the engine is sent `stop` and given 800 ms to answer. Every read from
   the engine has a deadline, `uciok` and `readyok` included, and a `readyok` that never arrives
   counts as a dead engine, which is stopped and restarted on the next search.

Two earlier designs failed here, and both are worth knowing about. Sending the low steps a shallow
search made the engine unable to score the moves it was choosing between, so Skill Level 0's pick
became close to a random legal move: measured against a 1320-rated opponent that step lost every
game, and in a real game it read as "the app barely works". Drawing every move from a wide budget
had the same fault from the other side, because the widest budget makes the pick near-uniform and
the engine's own weaker choice never gets played. Keeping the engine's pick and adding mistakes in
moderation is what separates the levels.

`UCI_Elo` and `Skill Level` are the same mechanism underneath: an Elo is converted to a Skill Level
inside the engine, and Skill Level 0 is about what the 1320 floor already is. That is why the steps
under 1320 need the node budget and the drawn alternative at all, and why nothing under the floor
claims a rating the engine never showed.

### Which move is shown

`Humanizer.chooseMove` starts from the engine's own `bestmove` every time, because that is where
the strength setting lives: at `UCI_Elo` it is a move the engine has already weakened to that
rating, and at a Skill Level step it is the weaker move that step exists for. Replacing it with the
top scored line would play above the label, which is what an earlier version did by accident.

1. On a `UCI_Elo` step the move is drawn from the top five lines inside the centipawn budget,
   weighted towards the best, so the engine's first choice stays the common one and a move at the
   edge of the budget is the mistake that level actually makes.
2. On a Skill Level step the engine's pick is played outright, except for the share of moves in the
   table above, which come from a scored alternative inside the budget.
3. When the engine answers `bestmove (none)` or an empty string, **the picker falls back to the
   best scored line** rather than returning nothing: `(none)` is what the engine says on a position
   with no legal move, and an empty move is a cut-off line, and neither can be drawn on the board.
   Fewer than two candidate lines, or a mate score in the top line, also end at the engine's own
   move, because a mate score is not a centipawn value and cannot be weighed against one.
4. A mate in one or two is shown without drawing from the budget at all, because anyone finds those.

### Human pace and stealth

**Pace: human** is the default. It never touches the engine's strength setting, so a level plays as
strongly with it on as off; what it changes is which of the engine's scored moves is shown and how
long the panel waits before it shows one.

- the engine is asked for its top five moves (`MultiPV 5`), and the one shown may be any of them
  that sits inside the centipawn budget of the best, weighted towards the best, so the play stays
  strong but is not identically the engine's first choice every move. With pace off the engine's
  own pick is shown as it is;
- the search time is varied per move instead of fixed (500-1200 ms);
- the panel shows how long to sit on the move before playing it. The window comes from the shape of
  the position (long in crowded middlegames, short on a recapture or a mate in one) with the exact
  second inside it left open, and a countdown runs down to it;
- a mate in one or two is offered straight away, since those look odd to sit on.

**Again** asks the engine for a fresh pick on the same position, for when the first suggestion is a
move you do not want.

No app can promise chess.com will not flag assisted play: it reviews games statistically after the
fact, and assistance in a live or rated game is against its fair play rules. Human pace removes the
two easiest tells (identical top-engine moves every time, and an identical pause before every move).

## Protected screens (FLAG_SECURE)

MediaProjection, `AccessibilityService.takeScreenshot()` and plain `screencap` all leave secure
layers out, so those windows come back black. The app gets the real pixels two ways:

| Route | Needs | Works on |
| --- | --- | --- |
| Root | a working `su` (Magisk, KernelSU, APatch) | every Android version here |
| Shizuku | Shizuku installed, running, and access granted | Android 12 and newer |

The Shizuku route runs `SecureShotService` in a process Shizuku starts with the shell uid (2000),
calls `WindowManagerService.captureDisplay` with
`ScreenCapture.CaptureArgs.Builder#setCaptureSecureLayers(true)`, and hands the frame back to
the app in chunks over AIDL. No root, no Xposed or LSPosed module.

### Setting up Shizuku

1. Install Shizuku from the Play Store (`moe.shizuku.privileged.api`).
2. Enable Developer options and Wireless debugging, then start Shizuku from inside the app
   (Shizuku shows a pairing code and "Start via wireless debugging").
3. Open Chess Reviewer: the panel shows whether Shizuku is installed, running and allowed.
   Tap **Grant Shizuku access** and allow it.
4. Play as usual. When a game blocks screenshots, the overlay switches to the Shizuku frame
   by itself and says so; when capture works again it switches back.

Shizuku has to be started again after a reboot. If it is not running, protected screens stay black
and the panel names that cause; type the position into the app instead.

## When the panel reports a fault

The panel's first line is a named cause in plain words and the second is the shortest thing that
fixes it (`CaptureFault`, one entry per way this app can fail to see the screen). **Retry** appears
beside it and clears the fault without restarting the service: the learned piece set, the engine,
the panel position and the notification all survive, and a fresh capture session is opened
underneath the running service. Retry is hidden for the one fault it cannot fix, a missing overlay
permission, because the panel itself is what cannot be drawn in that case; the app screen repeats
the same sentence there, and it is the only report of it.

| Cause | What the panel says | What clears it |
| --- | --- | --- |
| Overlay permission | Chess Reviewer isn't allowed to draw over other apps, so the panel can't appear. | Allow overlay in the app, then Start. Retry cannot fix it, so it is not offered. |
| Consent spent, cancelled or revoked | Screen capture needs to be allowed again. | Retry, then accept the system prompt. The panel stays where it is. |
| Session alive, no picture arriving | The screen stopped sending frames. | Retry. If it stays empty, Start again from the app. |
| Root and Shizuku both tried within 5 s | Root and Shizuku were both tried a moment ago. | Retry in a couple of seconds. |
| Shizuku not installed | This screen hides itself from screen capture, and Shizuku isn't installed to read it anyway. | Install Shizuku, then Retry. |
| Shizuku installed but stopped | This screen hides itself from screen capture, and Shizuku isn't running so I can't read it. | Open Shizuku, start it, then Retry. |
| Shizuku access not granted | This screen hides itself from screen capture, and this app hasn't been given Shizuku access. | Grant Shizuku access in the app, then Retry. |
| Both routes read the screen and both got black | This screen hides itself from screen capture, even through root and Shizuku. | Retry, or enter the position by hand. |
| Pixels arrived, no board found | I can't find a chess board on this screen. | Open the game so the whole board is visible, drag the panel off it, Retry. |
| Board found, pieces unreadable | I can see the board but can't read the pieces. | Start position plus Learn, or Retry. |
| Anything thrown | The screen reader hit an error. | Retry, then Save and send the picture. |

A fault holds the status line until it is cleared, so a stray "Thinking" cannot wipe the cause. The
line under the sentence is the module's own text: `CaptureFailure.panelText` for a capture failure,
and `BoardFinder.lastProblem` or `Vision.lastProblem` for a board that could not be read, with the
score out of 1.0 or the count of visible squares inside it. Transient states set no fault at all, so
a board search in progress, the 500 ms poll gap and a still page do not flicker a false failure
every other tick.

`tools/PanelCheck.kt` asserts the table: every `CaptureError` code maps to exactly one fault, every
fault has a sentence and a fix, and the overlay fault is the only one that is not retryable.

## Checking a level

`tools/level-games.py` plays the app's steps against a fixed opponent and prints the score, which is
the only measurement that answers "does this level work in a game":

```
python3 tools/level-games.py /path/to/stockfish --games 8 --movetime 60
```

The opponent is held at `UCI_Elo 1320`, Stockfish's own calibrated floor. The harness applies the
same options, sends the same `go` line and runs the same picker as the app, so the score is the
score the app would get and not the engine's.

Twelve games each at 60 ms a move, against Stockfish 17.1 on x86-64, the app's settings against a
plain 1320 engine:

| Step | Score |
| --- | --- |
| 400 | 4.5/12 |
| 600 | 2.5/12 |
| 800 | 6.5/12 |
| 1000 | 10/12 |
| 1320 | 12/12 |

Twelve games is not enough to order the steps: a level that is genuinely even with its opponent
scores anything from nothing to everything over that many, which is why 600 reads below 400 here
although its settings are strictly stronger. What the table does show is that no step plays like a
random mover, which is the failure the harness exists for.

One result the harness cannot explain, stated rather than smoothed over: the app's 1320 step won
every one of its twelve games against the plain 1320 reference, and eight of eight in a separate
control run, while two reference engines split 3.5 of 8 between themselves. Whatever the reason, the
step is not weaker than its label in this harness. Ordering the steps, or claiming one is worth its
number, needs hundreds of games against a calibrated opponent, which this harness does not have.

`tools/LevelCheck.kt` asserts the property that failed, that the engine's own pick stays the most
common choice at every step:

```
kotlinc Levels.kt Humanizer.kt tools/LevelCheck.kt -include-runtime -d levelcheck.jar
java -jar levelcheck.jar
```

`tools/go-limit-check.py` takes the `go` line `Levels.goCommand` prints for every step and sends it
to a real engine, so the claim that no step can hang is measured rather than asserted.

## Version 1.6

`versionName` is 1.6 and the `versionCode` base went from 100 to 101, so a build of this version
installs over the last one. The code changes in this version:

1. **A level can no longer hang.** Every search carries `go depth 24` and a clamped move time, every
   read from the engine has a deadline, and an overrunning search is stopped and answered from its
   top scored line. Full strength used to be the one path with no limit worth the name.
2. **The panel names the failure.** Eleven named causes, the module's own sentence for each, and a
   Retry that clears the fault without restarting the service.
3. **Retry asks for consent instead of doing nothing.** When the token has been spent, Retry starts
   the app page with `MainActivity.EXTRA_RECONSENT`, which is now defined and read by both
   `onCreate` and `onNewIntent`, and the new token goes back to the service that is already
   running. The app page repeats the panel's sentence in the panel's own words, for the case where
   the panel cannot be drawn at all.
4. **An unchanged page is not read again.** A page that has not repainted comes back from the
   capture session as the previous buffer with `fresh = false`, and the analysis loop keeps that
   flag, so the pieces are read when the picture changes rather than on every poll.
5. **The capture module compiles.** `ShizukuCapture.kt` named the `SHIZUKU_*` failure codes without
   importing them, so those references did not resolve and no APK could be built from that source.
   With the imports in place the whole app module type-checks against the Android 14 framework jar
   with no errors.

None of this has run on a phone. The module has been type-checked and its level table played in the
harness above, not installed and played for real, so what is checked here is the code and the engine
settings, not how the sentences read in a live game. If the panel still says it cannot see the
board, `Save` writes the frame to `Pictures/ChessReviewer`: a black picture there means the pixels
never arrived, a picture with the board on it means the board or the piece read is what failed.

## Build

The GitHub Actions workflow (`.github/workflows/build.yml`) downloads the arm64 Stockfish release
into `app/src/main/jniLibs/arm64-v8a/libstockfish.so` and builds the debug APK. The Shizuku client
libraries (`dev.rikka.shizuku:api` and `:provider`, 13.1.5) come from Maven Central, which
`settings.gradle.kts` already lists.
