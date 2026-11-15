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
the number goes down. It uses the two settings the engine has, in the order they work:

| Step | How it is asked for | What happens |
| --- | --- | --- |
| 2800 down to 1320 | `UCI_LimitStrength` on, `UCI_Elo` set, move drawn from the top five inside 6-84 centipawns | the engine's own rating limit, the one that maps to a real Elo |
| 1000 down to 400 | `Skill Level` 0-2 with a node budget of 12k-20k; the engine's own pick is played 50-80% of the time, and a scored alternative inside 200-700 centipawns the rest | `UCI_Elo`'s floor is 1320, so below it the engine is driven by the option that floor is translated into, plus one deliberate mistake |
| Full strength | nothing | no limit at all |

The low steps weaken in two ways and no more. Skill Level decides how far from its best move the
engine will go, and the node budget decides how well it can score the moves it is choosing between.
That engine pick is played most of the time; the rest of the time the move is drawn from the top
five inside a centipawn budget, which is the deliberate mistake that takes the step under the floor.

Two earlier designs failed here, and both are worth knowing about. Capping the search depth left the
engine unable to score the moves it was choosing between, so Skill Level 0's pick became close to a
random legal move: measured against a 1320-rated opponent that step lost every game. Drawing every
move from a wide budget on top of it had the same fault from the other side, because the widest
budget makes the pick near-uniform and the engine's own weaker choice never gets played. Keeping the
engine's pick and adding mistakes in moderation is what separates the levels.


`UCI_Elo` and `Skill Level` are the same mechanism underneath: an Elo is converted to a Skill Level
inside the engine, and Skill Level 0 is about what the 1320 floor already is. That is why the steps
under 1320 need the node budget and the wider draw at all, and why nothing under the floor claims a
rating the engine never showed.

Eleven levels are offered, 400 up to full strength. The Elo limit is clamped to the range this
particular Stockfish build advertises in its `uci` reply, and a build with no `UCI_Elo` at all is
driven by Skill Level across the whole range.

Pick a level on the main screen, or with the **Elo** button on the floating panel, which cycles the
same levels mid-game: a tap goes up the list, a long press goes down it. **Pace: human** (the
default) switches the stealth behaviour on:

- the engine is asked for its top five moves (`MultiPV 5`), and the one shown is drawn from those
  inside a centipawn budget of the best, weighted towards the best, so the play stays strong
  but is not identically the engine's first choice every move;
- the search time is varied per move instead of fixed;
- the panel shows how long to sit on the move before playing it. The window comes from the shape of
  the position (long in crowded middlegames, short on forced recaptures and mate in one) with the
  exact second left open, and a countdown runs down to it;
- a mate in one or two is always played, because anyone finds those.


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

The Shizuku route runs `SecureShotService` in a process Shizuku starts with the shell uid
(2000), calls `WindowManagerService.captureDisplay` with
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

Shizuku has to be started again after a reboot. If it is not running, protected screens stay
black and the panel says so; type the position into the app instead.

## Checking a level

`tools/level-games.py` plays the app's steps against a fixed opponent and prints the score, which is
the only measurement that answers "does this level work in a game":

```
python3 tools/level-games.py /path/to/stockfish --games 8 --movetime 60
```

The opponent is held at `UCI_Elo 1320`, Stockfish's own calibrated floor. Six games each at 60 ms a
move, the app's settings against a plain 1320 engine:

| Step | Score |
| --- | --- |
| 400 | 0/6 |
| 600 | 0/6 |
| 800 | 0/6 |
| 1000 | 0/6 |
| 1320 | 1.5/6 |

The four lowest losing is correct: they are labelled below 1320 and the opponent is 1320. What went
wrong before was not the score, it was how the moves were chosen. The shipped 400 step drew from a
320 centipawn budget across five near-equal candidates, which makes the choice close to uniform: it
was picked as the best move roughly one move in five, so the play was not a weak player's play, it
was a random legal move's. `tools/LevelCheck.kt` asserts the property that failed, that the engine's
own pick stays the most common choice at every step:

```
kotlinc Levels.kt Humanizer.kt tools/LevelCheck.kt -include-runtime -d levelcheck.jar
java -jar levelcheck.jar
```


## Build

The GitHub Actions workflow (`.github/workflows/build.yml`) downloads the arm64 Stockfish
release into `app/src/main/jniLibs/arm64-v8a/libstockfish.so` and builds the debug APK. The
Shizuku client libraries (`dev.rikka.shizuku:api` and `:provider`, 13.1.5) come from Maven
Central, which `settings.gradle.kts` already lists.
