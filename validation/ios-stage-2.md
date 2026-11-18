# iOS Stage 2 verification: core ratings and undo

Date: 2026-11-18

Scope: `ios/EncoreCore` (docs/IOS-PLAN.md, Stage 2). It contains the rating model, the SQLite and in-memory rating stores, the repository with persisted undo, the Swipe and History screen models, and the user's rating in the Details model. There is no UI and no Xcode project yet; no UI strings were added.

## What changed

| Area | Files (under `ios/EncoreCore/Sources/EncoreCore/`) |
|---|---|
| Model | `Model/Rating.swift`: `RatingValue` (raw values `LIKE` / `DISLIKE` / `UNSEEN`, the names stored on disk), `Rating`, `RatingEvent` |
| Store contract | `Rating/RatingStore.swift`: `RatingStore`, `RatingTransaction`, `RatingState`, `RatingWrite`, `RatingStoreError` |
| Stores | `Rating/SQLiteDatabase.swift` (thin `libsqlite3` wrapper), `Rating/SQLiteRatingStore.swift` (actor, `user.sqlite`), `Rating/InMemoryRatingStore.swift` (actor, tests and previews) |
| Repository | `Rating/RatingRepository.swift` (`@MainActor @Observable`) |
| Support | `Support/TaskHandle.swift`: cancels a screen model's subscription task when the model is released |
| Screens | `Screens/SwipeModel.swift`, `Screens/HistoryModel.swift` (new); `Screens/DetailsModel.swift` gains `userRating` and `setRating(_:)`, and its initializer now takes the repository |
| Tests | `RatingRepositoryTests.swift`, `SQLiteRatingStoreTests.swift`, `RatingScreenModelTests.swift` (new); `ScreenModelTests.swift` (Details initializer); `Support/TestDoubles.swift` (`TestClock`, `eventually`) |

Size: 895 lines of source and 729 lines of new tests.

## How it works under the hood

- **One synchronous transaction body.** `RatingStore.write` takes a synchronous closure that gets a `RatingTransaction` (read, upsert, delete, append event, latest event, delete event). Android's version is a suspending block. In Swift, an `async` block inside an actor would let other calls interleave at every `await`, because actors are re-entrant. A synchronous body runs start to finish inside the store's actor, so writes are serialized with no lock. SQLite is synchronous anyway.
- **SQLite store.** `SQLiteRatingStore` is an actor that owns one connection.
  - **Opening.** It opens the file and migrates, and only then switches to WAL. The journal mode is stored in the file header, so a refused newer file must not be switched.
  - **Writes.** Each write runs in `BEGIN IMMEDIATE … COMMIT`. Any error, from the body or from `COMMIT` itself, triggers `ROLLBACK`, so the ratings table and the undo log never disagree. If `ROLLBACK` itself fails (`sqlite3_get_autocommit` still 0), the connection is closed: otherwise later reads would see the half-written change.
  - **State.** The new ratings list and "has history" are read *inside* the transaction, before `COMMIT`. The reported state is then exactly what the write left behind, and a failed read rolls the write back instead of reporting a false failure for a write that is already on disk.
- **Schema parity with Android.** The tables, column names, types, primary keys, the `index_ratings_updatedAt` index and `AUTOINCREMENT` were copied from Room's exported v1 schema. The undo column is `previousValue`, as on Android; plan §3.3 says `previous`, but Android is the reference. Enums are stored by name and times as epoch milliseconds, as on Android.
- **Migrations.** `migrations[n]` upgrades a file from version n to n + 1, and `PRAGMA user_version` records the version.
  - **Atomic.** The version is read, and all pending steps and the version bump run, inside one `BEGIN IMMEDIATE` transaction. A failing step leaves the file at its old version, with its data intact (tested). Reading the version under the write lock means a future second process (a widget, say) that migrated first is seen, and its steps are not run twice.
  - **No destructive fallback.** A file from a newer app version throws `newerSchema` and is not touched: same version, same tables, journal mode unchanged (tested). Room behaves the same way without `fallbackToDestructiveMigration`.
  - **Harness.** Tests can pass an extra step, such as a fake v2 that adds a column, and check that existing ratings and undo history survive.
- **Unknown values.** A stored value outside the three names throws `unreadableRow`; it is never mapped to a default. Room would crash on an unknown enum name, so reporting it is the closer safe equivalent.
- **Repository.** `RatingRepository` is the only writer.
  - **`setRating`.** It reads the current rating, does nothing if the value is unchanged (no undo step), and otherwise writes the rating and an event holding the previous value and time.
  - **`undo`.** It pops the newest event (`ORDER BY id DESC LIMIT 1`, global across movies) and restores the previous value with its *original* timestamp, or deletes a first rating.
  - **Clock.** It is read on the main actor before the write, so the body captures only plain values.
- **Publishing the state.** After each write the repository applies the returned state twice:
  - **Observable properties** (`ratings`, `canUndo`, `value(for:)`) for synchronous reads and SwiftUI.
  - **`updates()` streams** for screen models that react with async work. Each subscriber gets its own `AsyncStream`, starting with the current list. It uses `bufferingNewest(1)`, so a slow reader skips to the latest list (Android's `mapLatest` behaves the same way).

  The store finishes writes in order, but their continuations can resume on the main actor in any order. Each state therefore carries a revision, and an older one is dropped.
- **Swipe.** The queue is the catalog's swipe order minus rated ids (UNSEEN included) minus the optimistic `pending` set.
  - **Rated ids.** They are read from the repository's own state at each refresh. The update stream only says "refresh now". If the stream carried the rated set instead, a rating made and undone between two stream reads could leave a stale entry (review finding, below).
  - **Rating.** `rate` adds the id to `pending` and promotes the card behind in the same call, before the write finishes (tested synchronously). When the write returns, the id leaves `pending` either way: on success the repository already holds the rating, so the card stays gone.
  - **Failed write.** The card goes back and a `SwipeNotice(.saveFailed)` is posted. A notice has an id, so the same message twice still shows twice.
  - **Card cache.** Movies are read only for the top two cards, and a refresh with both cards cached updates the state synchronously.
- **Task lifetimes.** Swipe and History each hold their subscription task in a `TaskHandle`, which cancels it when the model is released. That ends the `for await` loop and removes the stream from the repository (tested).
- **History.** It rebuilds on every ratings update.
  - **Catalog reads.** Only ids not yet cached are read. A newer list cancels a build still reading (latest wins).
  - **Filtering.** A filter is applied to the items already built, with no catalog read.
  - **Removed movies (C10).** Ratings for movies missing from the catalog stay in the store but are hidden from the list and the counts.
  - **No chunking.** Android's 500-id chunks are gone: the snapshot has no SQL `IN` limit.
- **Details.** `userRating` is a computed read of the repository's observable state, so a change made in Swipe, in History or by Undo shows at once. A failed `setRating` is swallowed, as on Android, so the selection keeps showing what is on disk.

## Differences from Android (deliberate)

| Android | iOS | Why |
|---|---|---|
| Suspending transaction block | Synchronous body inside the store actor | Actor re-entrancy would let calls interleave at `await`s |
| Room re-queries on table invalidation | The repository publishes the state each write returns | It is the only writer, so it needs no triggers or invalidation tracking (plan §3.3) |
| Snackbar `Channel` of messages | `notice: SwipeNotice?` with an id + `dismissNotice(id:)` | A SwiftUI toast binds to an observable value (A-6) |
| History filter goes back through the flow pipeline | The filter applies to items already built | No catalog read on filter change |
| Unknown enum name in `user.db` crashes Room | `RatingStoreError.unreadableRow` | Reported, never guessed |

## Tests

Test-first: the three new test files and the Details initializer change were written before any source file. The first run failed to compile because none of the referenced types existed (32 × `cannot find 'SQLiteRatingStore'`, 22 × `cannot find 'Rating'`, 20 × `cannot find 'RatingRepository'`, 16 × `cannot find type 'SwipeModel'`, and so on). That is the "red" state. Once the implementation compiled, every test passed on its first run. One test first had to be rewritten to get past a compiler crash (see below).

| Suite | Android source | Cases |
|---|---|---|
| `RatingRepositoryTests` | `RatingRepositoryTest` (10) | 10 ported + 4 iOS (stream starts with the current list and follows changes; 20 concurrent writes are serialized, all logged, all undoable; a stale load that finishes after a write doesn't overwrite it; a released reader leaves the subscriber list) |
| `SQLiteRatingStoreTests` | `RoomRatingStoreTest` (3, instrumented) | 3 ported, on file-backed temp DBs + 7 iOS: an undo-shaped write (delete + delete event) that throws rolls back both tables, Android v1 schema and WAL on a new file, newer schema refused and untouched, a migration keeps ratings and history, a failing migration rolls back, an unknown stored value is reported, names and milliseconds on disk |
| `SwipeModelTests` | `SwipeViewModelTest` (9) | 9 ported + 3 iOS (a card rated here and undone from outside Swipe comes back; rate then immediate undo; dismissing a notice clears only that notice) |
| `HistoryModelTests` | `HistoryViewModelTest` (6) | 6 ported |
| `DetailsRatingTests` | `MovieDetailsViewModelTest` rating cases (2) | 2 ported |

**Timing in screen-model tests.** Android's tests use a virtual-time dispatcher and `advanceUntilIdle()`. Here, updates cross the store actor and an `AsyncStream`, so there is no single "idle" to wait for. The tests poll with `eventually { … }` every millisecond for at most 2 s and fail if the condition never holds, so a lost update fails the test instead of hanging it. The synchronous promotion test makes no wait at all.

**Compiler crash in one test.** The concurrent-writes test first used `withTaskGroup` with `@MainActor` child closures. Swift 6.3.3's region-based isolation checker crashed on it ("pattern that the region-based isolation checker does not understand"). The test now starts 20 unstructured `Task`s and awaits each one; it covers the same behaviour.

## Commands run and actual results

| Command | Result |
|---|---|
| `ios/scripts/test-core.sh` (first run, before any source) | Compile failure on the missing types (red, as intended) |
| `swift build` (in `ios/EncoreCore`) | Clean: 0 errors, 0 warnings in Swift 6 strict-concurrency mode |
| `ios/scripts/test-core.sh` (before review fixes) | 130 tests passed, 1 skipped; 15 / 15 repeated runs passed |
| `ios/scripts/test-core.sh` (after review fixes) | **135 tests in 17 suites passed, 1 skipped** (`RealCatalogCheck`, by design). 91 from Stage 1 + 44 new |
| `ios/scripts/test-core.sh` × 15 in a row (after fixes) | 15 / 15 runs passed, so no flakiness seen in the polled tests |

Toolchain: Apple Swift 6.3.3, Command Line Tools only (`xcode-select -p` = `/Library/Developer/CommandLineTools`), macOS 26.

## Code-reviewer gate

The `code-reviewer` agent read the code against the Android sources and the plan; it did not build or run anything. It reported **no blockers**. It confirmed: rollback of both tables, no re-entrancy in writes, the revision guard, History's latest-wins, SQLite binding and statement lifetimes, and `user_version` inside the migration transaction.

| Finding | Action |
|---|---|
| Swipe: a card rated and then undone from outside Swipe between two stream reads could stay hidden until restart (not reachable yet: only Swipe calls `undo`) | **Fixed.** The queue reads the repository state; `pending` is cleared when the write returns. Tests added |
| A failed `ROLLBACK` was swallowed and could leave the connection inside the transaction | **Fixed.** The connection is closed when autocommit is still off |
| `PRAGMA journal_mode = WAL` ran before the newer-schema check, so a refused file was still changed | **Fixed.** WAL is enabled after migrating, and the result is checked. The test now asserts the journal mode |
| Subscription tasks outlived their models until the next rating change | **Fixed** with `TaskHandle`. Test added |
| Migration read `user_version` outside the transaction | **Fixed.** It is read under `BEGIN IMMEDIATE` |
| Missing tests: undo rollback on SQLite, the revision guard, Swipe undo edge cases, subscriber clean-up | **Added** (5 tests) |
| Clock read on the main actor before the write. Android reads it inside the transaction, and actors don't promise FIFO order across priorities | **Kept.** A different `updatedAt` order needs two writes from different-priority tasks within the same millisecond. Matching Android would make the clock `@Sendable`. Listed under limitations |
| History reads ids missing from the catalog again on every change | **Kept**, for parity with Android. Cheap with the in-memory snapshot |
| `RatingRepository` never calls `finish()` on its streams | **Kept.** The repository lives as long as the app |

## Limitations

- **No device or app run.** This stage is pure logic: all tests ran on macOS. `SQLiteRatingStore.applicationSupport()` runs for the first time in Stage 6, and file protection and backup of `user.sqlite` are checked there too.
- **Same SQLite library, not the same engine build.** Android Room and iOS use different SQLite builds. The schema was compared column by column against Room's exported JSON, but no `user.db` file was moved between platforms. Nothing in the plan needs that.
- **Ratings load failure.** If `user.sqlite` can't be read at launch, Swipe and History show their error state and Details shows no selection. Swipe's Retry reloads; History has no Retry, as on Android. The app-level handling (what the user sees, whether the app can still browse) is decided in Stage 6.
- **Clock read before the write** (review finding, kept). Under rare priority inversion, two writes in the same instant could get `updatedAt` values in a different order from their undo order.
- **The external-undo Swipe test** guards the fixed design, but the original race depended on stream timing, so the old code would not fail it reliably.
- **Polled tests.** `eventually` replaces virtual time. It caught no flakiness in 15 runs, but it is a bounded wait, not a proof of idleness.
- **Not committed.** It waits for your approval.
