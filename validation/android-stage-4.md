# Android Stage 4 verification: Ratings, Swipe, History

Date: 2026-11-17 · versionName 0.4.0 (versionCode 5) · committed at the owner's request

## Scope

- **`RatingRepository` + persisted undo** in a new `user.db`. This is the only code that writes ratings; Swipe, Details and History all go through it.
- **Swipe tab**: one card at a time, most popular unrated movie first (4b). Right = Like, left = Dislike, down = Not seen. A drag stamp (icon + word) follows the gesture, and a short or upward drag snaps back. Three buttons give the same actions, and the card exposes them as TalkBack custom actions. Tapping the card opens Details. A light haptic fires on each rating (O9). States: loading, "You've rated every movie" (+ Open history), and error (+ Try again).
- **Top bar on Swipe**: Undo (disabled when there is nothing to undo) and History. A snackbar reports what Undo did, for example "The Batman is back to Disliked" or "Rating removed: No Exit". TalkBack announces it.
- **History**: every rated movie, newest change first, with filter chips that show counts (All / Liked / Not seen / Disliked). Each row has an icon and a word for its value. Tapping a row opens Details, where the rating can be changed. Undo is in the top bar here too. There is an empty state and a filtered-empty state.
- **Details**: a "Your rating" single-choice row (Dislike / Not seen / Like), live, so changes made elsewhere and Undo show at once. Picking the current value again does nothing: only Undo removes a rating (AGENTS.md).
- **Auto Backup (O5)**: `allowBackup` is now on. Only `user.db` (+ WAL) is included, so `catalog.db` and the poster cache stay out. This also clears the old `DataExtractionRules` lint warning.
- Strings: EN + ES (`strings_rating.xml`, `strings_swipe.xml`, `strings_history.xml`, plus one in `strings_details.xml`). DE/PT are still pending, as in earlier stages.

## How it works under the hood

| Piece | File | Notes |
|---|---|---|
| Model + logic | `core/data/rating/RatingRepository.kt` | `setRating` reads the current rating, writes the new one and an event `(movieId, previous, previousAt, value, createdAt)` in **one transaction**. If the value is unchanged it is a no-op and adds no undo step. `undo()` pops the newest event: it restores `previous` with its **original timestamp** (so History order is exactly as before), or deletes the rating when `previous == null`. Undo is global and LIFO across screens. |
| Storage | `core/data/rating/db/UserDatabase.kt` | Tables `ratings(movieId PK, value, updatedAt)` and `rating_events(id autoinc, …)`. Enums are stored by name. There is no foreign key into `catalog.db`, so a catalog update never touches ratings (O6). There is **no destructive fallback**: a missing migration fails loudly instead of wiping data. The schema is exported to `android/app/schemas/…/1.json` through the Room Gradle plugin (O4; that file is meant to be committed). |
| Swipe queue | `feature/swipe/SwipeViewModel.kt` | The two DBs can't be joined, so the queue is `catalog.swipeOrder()` (all ids by popularity, loaded once, ~10k strings) minus rated ids (a Room `Flow`) minus a **pending** set. Only the top two cards are read as full `Movie`s. `rate()` promotes the next card **synchronously**, so a swiped card never flashes back while the write lands. A failed write puts the card back and shows "Couldn't save that". It is activity-scoped, so the shell's Undo button and History share it. |
| History | `feature/history/HistoryViewModel.kt` | Follows `ratings` live. Movie cards come from `catalog.summaries(ids)` in chunks of 500 (API 26 SQLite allows at most 999 bound variables) and are cached for the screen's life. Ratings whose movie left the catalog are kept but hidden (O6). |
| Gestures | `feature/swipe/SwipeScreen.kt` | An `Animatable<Offset>` drives translation and tilt. Release thresholds: 30% of the width horizontally, 20% of the height downward. Up never chooses. The fling uses `tween(220)`, which follows the system animator scale (reduced motion = instant). The card is 3:4 and at most 420 dp wide; when height is tight it widens up to square, and the footer drops the synopsis, so the poster stays visible in landscape and at 200% text. Phone landscape puts the buttons beside the card. |
| Colors | `ui/theme/Theme.kt` (`LocalRatingColors`), `core/ui/RatingUi.kt` | Icons and labels use the text-safe colors (#047857 / #DC2626 / #1D4ED8, P3), and the bright tints are only the button rings. Dark values are `[Assumption]`. Every use pairs color with a glyph and a word. |
| Navigation | `ui/navigation/EncoreApp.kt` | The Swipe tab now has its own Navigation 3 back stack (`SwipeHome → HistoryKey → MovieDetailsKey`), single pane. Re-selecting the tab pops to its root, and the title switches to "History" on that screen. |

No new libraries. The only Gradle addition is the Room Gradle plugin (`androidx.room`, same version as Room, 2.8.5), which handles schema export.

## Results

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`: BUILD SUCCESSFUL.
- JVM unit tests: **79/79 pass**, 27 of them new: `RatingRepositoryTest` 10, `SwipeViewModelTest` 9, `HistoryViewModelTest` 6, and `MovieDetailsViewModelTest` +2. They were written first; red was a compile failure (269 unresolved references). After the first implementation, one test failed ("card leaves at once, before the write finishes"), which led to the synchronous promotion described above. Fixtures are fictional; `FakeRatingStore` mirrors Room's transaction and rollback semantics.
- `./gradlew :app:connectedDebugAndroidTest` on `Encore_Lite` (API 37): **17/17 pass**. The new `RoomRatingStoreTest` (3) checks that ratings and undo history survive closing and reopening a file-backed `user.db`, that an exception mid-transaction rolls back both tables, and that the observed rating follows changes and undo.
- Lint: 0 errors. The only warnings are `MissingTranslation` (90, DE/PT). The `DataExtractionRules` warning is gone.
- The release APK contains no `network_security_config`.
- Emulator smoke on `Encore_Lite`, API 37. Screenshots are in `android-stage-4/`. **All of them show third-party posters, so none should be committed** (AGENTS.md). They are for review only.
  1. Fresh install: the catalog is imported from the asset. Swipe shows "9,919 movies left to rate" with the most popular movie first. Undo is disabled (`01`).
  2. A drag right likes the card, a drag left dislikes it, and a drag down marks it Not seen; the count drops each time. A short drag, an upward drag, and a release near the center all snap back. The Like stamp is visible during the drag (`02`).
  3. Undo brings the last movie back to the front, and the snackbar says "Rating removed: No Exit" (`03`). History lists Disliked and Liked rows, newest first, with counts (`04`).
  4. History → The Batman opens Details with Dislike selected (`05`). Changing it to Like updates History. Undo restores it, and the snackbar says "The Batman is back to Disliked".
  5. After `am force-stop` and a relaunch, Undo continues through the persisted history ("Rating removed: The Batman").
  6. ES + dark mode: "Te quedan 9.918 películas por valorar", with the button descriptions and History chips in Spanish (`06`, `07`).
  7. 200% font scale (`08`) and a 1280×720 landscape window (`09`): after the card-sizing fix the poster, title and all three actions stay visible.
  8. The crash buffer has no Encore entries.

## Bugs found and fixed during the stage

- **A swiped card could flash back.** The queue updates asynchronously after the write, so for a frame the old card was on top again. `rate()` now promotes the next card at once; this is covered by a test.
- **The gesture hints sat on different lines**, because the Not seen button is 56 dp and the others are 64 dp. Each button now sits in a 64 dp slot.
- **History filter chips were 48 dp tall.** M3 chips already get a 48 dp touch target, so the explicit height was removed.
- **At 200% text and in landscape the poster shrank to a sliver.** The card can now widen up to square when height is tight, and the footer becomes compact (one-line title, no synopsis).

## Decisions for the owner

- **The Dislike glyph is a thumbs-down, not the source's X.** ICONS.md flags X as reading like "close", and Dislike now also appears in Details and History. Like stays a heart (source glyph) and Not seen is the eye-off (P6). The mix of heart and thumbs-down is a judgment call: confirm it, or choose a matching pair.
- **Auto Backup was turned on in this stage** (O5 was already decided; `user.db` exists only from now on). Say if you would rather defer it to Stage 6.
- **Undo history is unbounded** (one small row per change). Undo is global: it reverts the newest change made anywhere, including in Details.
- **The Undo button lives only on Swipe and History.** In Details the user changes a rating directly; Undo from Details would need a snackbar there, which can be added if wanted.

## Limitations

- Code review gate: the plan calls for a code-reviewer pass at this gate, and Sonnet sub-agents for the UI. This stage was built in the main session, and **no code-reviewer pass has run yet**.
- TalkBack was not exercised on the device. The custom actions (Like / Dislike / Not seen on the card) and the radio semantics of the Details row are verified only in code, not by ear.
- Rotating the emulator with `user_rotation` did not relayout the window (half of the frame was black), so landscape was tested with `wm size 1280×720`. Twice the emulator's system services stopped responding: once after `wm size reset`, once mid-sequence. These are the known 8 GB host limitation; a cold boot fixed both. No real tablet or rotation test was done.
- At 200% text the bottom navigation labels wrap mid-word ("Explor/e"). This is from the Stage 1 shell, not this stage; it is left for Stage 9 hardening.
- DE/PT strings are not added, as in earlier stages.
