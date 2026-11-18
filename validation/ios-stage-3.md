# iOS Stage 3 verification: core recommendations and games

Date: 2026-11-18

Scope: `ios/EncoreCore` (docs/IOS-PLAN.md, Stage 3). It contains the recommendation engine, the Genie screen model, the Trivia and Poster Puzzle state machines and their screen models. There is no UI and no Xcode project yet; no UI strings were added, so the four locales are unaffected.

## What changed

| Area | Files (under `ios/EncoreCore/Sources/EncoreCore/`) |
|---|---|
| Engine | `Recommendation/RecommendationEngine.swift`: `ReasonKind`, `Reason`, `Recommendation`, `Recommendations`, `RecommendationEngine` |
| Genie | `Screens/GenieModel.swift`: `ReasonLine`, `GenieItem`, `GenieState`, `GenieModel` |
| Games | `Games/TriviaSession.swift` (pure state machine), `Games/PuzzleBoard.swift` (pure board) |
| Game screens | `Screens/TriviaModel.swift`, `Screens/PuzzleModel.swift` |
| Support | `Support/SeededRandom.swift` (SplitMix64, for repeatable games in tests and previews); `Support/Logging.swift` gains the `genie` and `games` categories |
| Catalog | `MovieCatalog.triviaQuestions()`, implemented by `CatalogSnapshot` (returns the validated trivia) |
| Tests | `RecommendationEngineTests`, `GenieModelTests`, `TriviaSessionTests`, `TriviaModelTests`, `PuzzleTests` (new); `Support/TestDoubles.swift` gains `Gate`, a feature gate, summary gates and trivia on the fake catalog |

Size: 870 lines of source and 1,062 lines of new tests.

## How it works under the hood

- **Engine.** A line-by-line port of Android `RecommendationEngine.kt`, pure and synchronous.
  - **Scoring.** affinity(f) = liked movies with f − disliked movies with f; score = 1·Σ genre + 3·director + 2·Σ actor. UNSEEN adds no affinity, but every rated movie is excluded. With no like it returns early, because nothing can score above zero.
  - **Names.** They are trimmed (Kotlin `trim()` semantics, already in `KotlinText`), blanks are dropped, and a repeated genre or actor counts once, keeping its first position.
  - **Name equality.** Names are keyed by UTF-16 code units (`NameKey`), not by Swift `String` equality. Swift treats canonically equivalent spellings (precomposed "é" and "e" + combining accent) as equal; Kotlin does not. Without this, the two apps could score the same catalog differently. There is a test for it.
  - **Order.** Score desc, then popularity desc with nil last, then id. Reasons: points desc, then director < actor < genre, then name, top 2. Ids and names compare by UTF-16 code units (plan I4), so emoji and other characters outside the BMP sort as on the JVM (tested).
- **Genie.** It follows the repository's `updates()` stream and recomputes on every list.
  - **Latest wins.** Each computation lives in a `TaskHandle`; a newer list replaces it and so cancels it. After every `await` a cancelled computation stops without publishing.
  - **Shared feature read.** The first catalog read is one unstructured task that every computation awaits. Cancelling the computation that started it does not cancel the read (Android: `NonCancellable`), so a rating made during the first read waits for it instead of reading again (tested: 1 read). A failed read is forgotten, so `retry()` reads again (tested: 2 reads).
  - **Off the main actor.** Scoring runs in a detached task; the model publishes on the main actor.
  - **Lifetime.** The compute task holds the model weakly and never across an `await`, so releasing the model ends the work.
  - **States.** `needsRatings` (no LIKE or DISLIKE), `noMatches(counts)`, `content(items, counts)`, `error`. A recommendation whose card is missing from the catalog is skipped. A ratings-read failure also shows `error`, and `retry()` reads the ratings again.
- **Trivia round (`TriviaSession`).** A value type that never reads a clock: every time-dependent call takes `now` in milliseconds, and randomness is passed in, so tests replay exact rounds.
  - **Timer.** One timer per phase: 15 s for a question, 2.5 s for the feedback. It keeps the "time left" and the "started at" (nil while frozen). Pause freezes it in both phases; hold freezes the feedback. The timer runs again only when neither applies, so pause and hold can be undone in either order (tested).
  - **Missed deadlines.** `advance(to:)` fires each deadline at its own time, in order. A late call (the app was suspended without pausing) replays what would have happened: timeout, then feedback end, then the next timeout. The loop terminates because every step either moves the deadline later or clears it.
  - **Rules (plan §3.5).** Up to 5 questions drawn without repeats; 100 points per correct answer, plus 50 for every correct answer while the streak is 3 or more; a timeout counts as wrong and resets the streak; an answer at or after the deadline is a timeout. One 50:50 per round hides two random wrong options; a hidden option can't be chosen. While paused, answers, 50:50 and "Next" are ignored, and the view hides the question.
- **Trivia screen (`TriviaModel`).** It reads the questions once, then owns one session.
  - **Ticker.** It wakes at the next deadline or the next whole second of the countdown, whichever comes first. `secondsLeft` therefore changes exactly once per second, with no polling. No ticker runs while paused, held or finished (tested).
  - **Countdown during feedback.** `secondsLeft` is taken at the moment of each action, so the feedback shows the time that was left when the player answered (0 after a timeout). This bug was found by a test added at the review gate (below).
  - **Clock.** Milliseconds on `ContinuousClock` by default; tests inject a `TestClock` and a closed sleeper and drive time by hand.
- **Poster Puzzle (`PuzzleBoard`).** `tiles[position]` is the piece shown there; the board is solved when every piece is at its own number.
  - **Never solved at start.** A shuffle that comes out in order gets its first two tiles swapped (1 draw in 9!). A test forces that path with a generator that makes the shuffle return the identity.
  - **Taps.** The first tap selects, a tap on the same tile deselects, a tap on another tile swaps. A solved board ignores taps. The VoiceOver actions "Select" and "Swap with selected" both map to `tap`. Progress = correct tiles / 9.
- **Puzzle screen (`PuzzleModel`).** Candidates are the featured pool (the 100 most popular movies with a poster and a synopsis). The current movie is left out first, then posters already in the image cache are preferred, so "New puzzle" never repeats the movie when another exists, and the puzzle works offline. `isPosterCached` is injected; Stage 8 wires it to the poster cache.

## Differences from Android (deliberate)

| Android | iOS | Why |
|---|---|---|
| `mapLatest` over the ratings flow | `TaskHandle` replacement + `isCancelled` checks | Swift has no `mapLatest`; same latest-wins behaviour |
| `withContext(NonCancellable)` for the first feature read | One unstructured task awaited by every computation | Awaiting a task's value is not cancelled with the awaiting task |
| `SharingStarted.WhileSubscribed(5 s)`: computes only while the screen collects | Computes from creation until released | No subscription concept in Observation. The app creates the model when the Genie tab first appears (Stage 7) |
| Ratings flow cannot fail at the Genie level | Ratings-read failure → `.error` + `retry()` | `RatingRepository.load()` can throw on iOS (Stage 2) |
| String keys in affinity maps | UTF-16 keys (`NameKey`) | Swift `String` equality is canonical equivalence; Kotlin's is code units |
| Trivia and Puzzle | Built from plan §3.5 | Android has not built them yet (Q7). iOS follows Android's reviewed rules if they change |

## Tests

Test-first: each group of test files was written before its source and failed to compile, which is the "red" state:
- engine and Genie: `cannot find type 'Recommendations'`, `cannot find 'Reason'`, `cannot find 'ReasonLine'`
- games: 20 × `cannot find type 'TriviaSession'`, 12 × `'PuzzleModel'`, 8 × `'TriviaModel'`, 4 × `'PuzzleBoard'`

| Suite | Source | Cases |
|---|---|---|
| `RecommendationEngineTests` | Android `RecommendationEngineTest` (16) | 16 ported + 2 iOS (canonically equivalent names stay apart; id and name ties follow UTF-16 order) |
| `GenieModelTests` | Android `GenieViewModelTest` (12) | 12 ported + 3 iOS (ratings-read failure and retry; an older computation finishing last never replaces the newer result; a failed shared feature read is read again on retry) |
| `TriviaSessionTests` | Plan §3.5 | 20: draw, short and empty pools, same seed → same round, scoring, streak bonus, broken streak, timeout, answer after the deadline, auto-advance, hold, Next, pause in both phases, pause + hold in both orders, missed deadlines replayed, 50:50 once per round, hidden option, round end |
| `TriviaModelTests` | Plan §3.5 | 9: load, unavailable, error + retry, ticker wakes at whole seconds, timeout, actions forwarded, pause stops the ticker, countdown during feedback and after it, play again without reading again |
| `PuzzleBoardTests` | Plan §3.5 | 8: 2,000 seeded shuffles are permutations and never solved, forced identity shuffle, select/swap, deselect, progress, solved board ignores taps, rows and columns, invalid tiles rejected |
| `PuzzleModelTests` | Plan §3.5 | 8: picks a movie with a poster, prefers cached posters, any candidate when nothing is cached, New puzzle changes movie, a lone cached poster is not repeated, unavailable, error + retry, taps reach the board |

**Two of my own test mistakes, fixed in the tests, not the code:**
- The first draft of Genie's undo test expected `["a"]` after disliking `b`. In the fixture that dislike cancels both Drama and Ada Vale, so the correct state is `noMatches(1, 1)`. Android's test never checks this in-between state.
- The first "identity shuffle" generator returned 0 from every `next()`. The standard library's unbiased `next(upperBound:)` rejects 0 for every bound and draws forever, which hung the test run. It now returns 1, which still maps to index 0.

**One `#expect` quirk.** `#expect(s.nextDeadline == 15_000 + 2_500 + 15_000 + 2_500)` reported `35000 == 35000` as a failure with Swift 6.3.3. Writing the right side as `Int64(…)` fixed it. I did not dig further.

## Commands run and results

| Command | Result |
|---|---|
| `ios/scripts/test-core.sh` before the sources existed | Build failed on the missing types (red), as listed above |
| `ios/scripts/test-core.sh` after `swift package clean` (before review fixes) | **205 tests in 23 suites passed, 1 skipped** (`RealCatalogCheck`, by design); no compiler warnings |
| `ios/scripts/test-core.sh` × 15 in a row (before review fixes) | 15 / 15 passed |
| `ios/scripts/test-core.sh` (after review fixes) | **213 tests in 23 suites passed, 1 skipped**: 135 from Stages 1–2 + 78 new; no compiler warnings |
| `ios/scripts/test-core.sh` × 15 in a row (after review fixes) | 15 / 15 passed |

Toolchain: Apple Swift 6.3.3, Command Line Tools only (`xcode-select -p` = `/Library/Developer/CommandLineTools`), macOS 26.

## Code-reviewer gate

The `code-reviewer` agent read the code against the Kotlin sources and plan §3.4–3.5; it did not build or run anything. It reported **no blockers**. It confirmed: engine parity line by line (scoring, exclusions, dedup, tie-breaks, reasons, clamp, counts), the non-cancellable shared feature read, latest-wins with no state stuck in `.loading`, termination of `advance(to:)`, the ticker arithmetic, the never-solved board, and Swift 6 isolation.

| Finding | Action |
|---|---|
| "New puzzle" repeated the movie when it was the only cached poster, even with other candidates | **Fixed.** The current movie is removed before the cache preference. Test added |
| `TriviaModel.load` had no cancellation guard (a `retry()` during a load could let the old task overwrite the new round) and dropped errors silently | **Fixed.** `isCancelled` guards and a `logError` sink (also added to `PuzzleModel`) |
| Genie's compute task held the model for its whole run and was not cancelled on release | **Fixed.** `TaskHandle`, and the task holds the model only between `await`s. Same change in `PuzzleModel` |
| Missing tests: Genie ratings-read failure, latest-wins ordering, failed shared read + retry; Trivia ticker on pause, `secondsLeft` across feedback; pause + hold together; UTF-16 tie-breaks | **Added** (8 tests). The `secondsLeft` test found a real bug: after an answer between two ticks, the feedback showed the countdown from the last tick. **Fixed** |
| Nit: duplicate ids kept the first rating; Kotlin `associate` keeps the last | **Changed** to last, for parity (the repository never repeats an id) |
| Nit: `startRound` read the clock twice | **Fixed** |
| Nit: up to 100 `isPosterCached` calls run one after another | **Kept.** Revisit in Stage 8 if the cache lookup turns out slow |
| Nit: the `posterUrl != nil` filter repeats the featured pool's own rule | **Kept** as a guard, with a comment |

## Limitations

- **No device or app run.** This stage is pure logic; all tests ran on macOS. Performance of Genie on the real 9,919-movie catalog on the iPhone is measured in Stage 7.
- **No Kotlin ↔ Swift diff on the real catalog.** The optional parity check (plan Q7) needs your OK and a temporary JVM test in `android/`; it was not run. Parity rests on the 16 ported Android tests plus the line-by-line review.
- **Trivia and Puzzle rules are not reviewed on Android.** They follow plan §3.5, including its assumptions: the bonus applies to every correct answer while the streak is ≥ 3, the 50:50 picks at random, and a timeout counts as wrong. Further choices of mine:
  - option order is kept as in the source data (not shuffled)
  - releasing a held feedback resumes the time it had left
  - a "Next" action leaves the feedback at once
  - the puzzle draws from the 100 most popular movies with a poster
  - consecutive Trivia rounds may repeat questions
- **Genie computes while alive**, not only while visible (see Differences). The app decides its lifetime in Stage 7.
- **Poster cache.** `PuzzleModel` takes an `isPosterCached` closure, which is nil until the Stage 8 poster loader exists.
- **Polled tests.** As in Stage 2, `eventually` replaces virtual time. No flakiness was seen in 30 full runs, but it is a bounded wait, not a proof of idleness.
- **Not committed.** It waits for your approval.
