# Android code review (Stages 0–5)

Date: 2026-11-18. Scope: everything under `android/` at commit `8955c6f`, plus the untracked working-tree state. This was a read-only review: no code was changed, and no Gradle, emulator or device run happened. `ios/` is out of scope.

Method: five `code-reviewer` sub-agents worked in parallel, one per area (see "What each reviewer checked"). The lead session then checked every blocker and should-fix against the source before writing this report. Findings it confirmed have no tag. Findings it could not confirm are tagged **[Unverified]**.

## TL;DR

The data layer is solid:
- Each rating and its undo step are written in one Room transaction.
- `user.db` has no destructive fallback and its schema is exported.
- The catalog replace is atomic.
- The recommendation engine matches D6/D7 exactly.
- Cleartext is allowed only in debug, and only for loopback.

There is **one blocker**, in the Swipe card gesture code. A second drag on a card while it is flying out cancels the animation before the rating is saved. The card then stays stuck until the user leaves the screen.

The **should-fix** items fall into four groups:
- Swipe/History error states that can't recover.
- Two unbounded network reads in the catalog download.
- Undo snackbars that queue up.
- Missing Compose UI tests: nothing in `androidTest` touches the UI.

DE/PT strings are still missing. That is a known, owner-approved deviation, not a new bug.

Paths below are relative to `android/app/src/main/java/com/encore/app/` unless they start with `android/`.

---

## Blocker

### B1. A second drag during the fling-out leaves the Swipe card stuck
- **Where:** `feature/swipe/SwipeScreen.kt:237-249` (`flingOut`), `:126-133` (`commit`), `:277-287` (`pointerInput`)
- **Problem:** `flingOut` sets `committed = true`, then runs `offset.animateTo(...)` for 220 ms. During that time the card's `pointerInput` is still active, and the hit area moves with the `graphicsLayer` translation. A new drag calls `offset.snapTo` (line 286), and a drag cancel calls `settle()`. Both take `Animatable`'s mutator mutex, which cancels the running `animateTo`. The `CancellationException` ends the `commit` coroutine before `onRate` runs. Because `committed` is already true, every later swipe, button and TalkBack custom action returns `false`.
- **Why it matters:** fast swiping is the main way people use this screen. The card freezes at a partial offset and no rating is saved. The only recovery is to leave Swipe and come back (a tab switch or rotation), which creates a new `SwipeCardState`.
- **Fix:**
  - In `flingOut`, return `true` even if the animation is interrupted: `try { offset.animateTo(...) } catch (e: CancellationException) { /* still commit */ }`. Alternatively, move `onRate` into a `finally` in `commit`, which also fixes S3.
  - Make `onDrag`, `onDragEnd` and `onDragCancel` return early when `state.committed` is true, or key `pointerInput` on `committed`.
  - Add a Compose UI test that starts a second drag in the middle of the fling.

---

## Should-fix

### S1. Swipe Retry spins forever when a card fails to load
- **Where:** `feature/swipe/SwipeViewModel.kt:96-108` (`load`), `:123-127` (`retry`)
- **Problem:** when `catalog.movie()` throws, or returns null for both top ids, `load()` returns `Error` but `loadFailed` stays `false`. `retry()` sets the state to `Loading` and then sets `loadFailed.value = false`. A StateFlow doesn't emit an equal value, and `order` is not null, so nothing runs again. The spinner stays until some rating changes.
- **Why it matters:** the user taps Retry and gets a spinner that never ends. The trigger is a database read failure, which is rare, so this is not a blocker.
- **Fix:** add `private val retryTick = MutableStateFlow(0)`, include it in the `combine` in `queueState`, and increment it in `retry()`. Add a test where only `movie()` fails. The existing retry test fails `swipeOrder()`, so it only covers the path that already works.

### S2. A catalog download has no size cap and no total deadline
- **Where:** `core/data/catalog/CatalogSources.kt:31-40`, `core/data/catalog/CatalogRepository.kt:97-112`
- **Problem:**
  - The whole response is decoded in memory, and nothing limits its size. `OutOfMemoryError` is an `Error`, so the `catch (e: Exception)` that is supposed to "end in a retryable state" never catches it.
  - `readTimeout = 30 s` applies to each read, not to the whole download. A server that sends one byte every 29 s keeps the import running indefinitely.
  - `open()`/`parse()` are blocking calls, so coroutine cancellation can't interrupt them, and the import mutex stays held.
- **Why it matters:** this is the only place where untrusted network input enters the app. A misconfigured endpoint, or a redirect to the wrong place, could crash the app on every start or leave it on the Loading screen with no Retry. The release URL is HTTPS and controlled by the owner, so the likelihood is low.
- **Fix:**
  - Reject the response when `contentLengthLong` is above a ceiling, for example 4× today's file.
  - Make `CountingInputStream` throw an `IOException` after N bytes or after a total deadline.
  - Optionally, call `connection.disconnect()` on cancellation.
  - Add unit tests with fake streams that are too large or too slow.

### S3. A rating is lost if the user leaves Swipe during the fling
- **Where:** `feature/swipe/SwipeScreen.kt:122-133`
- **Problem:** `commit` runs in `rememberCoroutineScope()`. If the user switches tabs, opens History or rotates the device within 220 ms, the scope is cancelled before `onRate` runs.
- **Why it matters:** the user saw the card fly away, but nothing was saved.
- **Fix:** the same as B1. Call `onRate` in a `finally`, or call it before the animation. The ViewModel is already optimistic through its `pending` set.

### S4. Undo snackbars queue instead of replacing each other
- **Where:** `ui/navigation/EncoreApp.kt:116-132`
- **Problem:** `messages.collect { … showSnackbar(text) }` handles one message at a time, and `showSnackbar` suspends until the snackbar is dismissed. So when the next message arrives, `currentSnackbarData` is already null and `dismiss()` on line 130 does nothing.
- **Why it matters:** five quick undos produce five snackbars in a row, about 4 s each. The later ones describe a state that is already out of date, and TalkBack reads each one out.
- **Fix:** use `messages.collectLatest { snackbar.showSnackbar(it.toText()) }`. Cancelling an active `showSnackbar` dismisses it. Then drop line 130.

### S5. A Genie ratings-flow error is not caught **[Partly unverified]**
- **Where:** `feature/genie/GenieViewModel.kt:72-99`
- **Problem:** the try/catch is inside `mapLatest`. An exception thrown by the upstream `ratings.ratings` Room flow gets past it and reaches `stateIn` in `viewModelScope`, which crashes the app. How likely Room is to throw here (disk error, corruption) is [Unverified].
- **Why it matters:** Error/Retry is meant to be the worst outcome. Instead, an upstream error would crash the app.
- **Fix:** `retryRequests.flatMapLatest { ratings.ratings.mapLatest { compute(it) }.catch { logError(…); emit(GenieUiState.Error) } }`. A plain `.catch` before `stateIn` would end the flow for good, and Retry would then do nothing.
- **Also:** add `.map { it.associate { r -> r.movieId to r.value } }.distinctUntilChanged()`. Room emits again whenever the table is invalidated, even with no change, and each emission rescores the whole catalog.

### S6. History's error state shows zero counts and has no retry
- **Where:** `feature/history/HistoryViewModel.kt:71-72`, `feature/history/HistoryScreen.kt:77-80`
- **Problem:** the error state drops `counts`, so every filter chip reads "(0)". There is no retry action, and the state only clears when the ratings change.
- **Fix:** keep the last counts, and add a retry trigger to the pipeline, as in S1.

### S7. Explore's query, genre and sort are lost on process death
- **Where:** `feature/explore/ExploreViewModel.kt:51`
- **Problem:** the query is only a `MutableStateFlow`, with no `SavedStateHandle`. After process death the back stack (`rememberNavBackStack`) and the grid scroll position come back, but the filters reset. The user returns to an unfiltered list, and the restored scroll offset now points into a different list.
- **Fix:** create the ViewModel with `createSavedStateHandle()` in the factory, back `text`/`genre`/`sort` with `handle.getStateFlow`, and add one test that rebuilds the ViewModel from a pre-filled handle.

### S8. Poster URLs reach Coil with no scheme check
- **Where:** `core/ui/MoviePoster.kt:53-55`; `core/data/catalog/CatalogParser.kt:84` only applies `blankToNull()`
- **Problem:** `SourceLink` treats catalog URLs as untrusted, but `posterUrl` doesn't get the same treatment. Coil 3 also loads `file://`, `content://` and `android.resource://` URIs.
- **Why it matters:** the impact is low, because Coil only decodes images. But a tampered catalog could make the app read local files, and the trust boundary is inconsistent.
- **Fix:** accept only `https`, or `http` with a host, either in the parser or in a small `PosterUrl.from()` next to `SourceLink`, and add tests. Also document that an `http://` poster always falls back in release, because cleartext is blocked.

### S9. `MovieDetailsKey` may share one ViewModel across tab stacks **[Unverified]**
- **Where:** `ui/navigation/EncoreApp.kt:69`, `:237`, `:282`, `:316`
- **Problem:** all three `NavDisplay`s use `rememberViewModelStoreNavEntryDecorator()` under the same Activity `ViewModelStoreOwner`. Per-entry stores are keyed by `contentKey`, which defaults to `key.toString()`. If so, `MovieDetailsKey("42")` open in both Explore and Swipe would share one `MovieDetailsViewModel`, and popping it in one tab would clear it for the other. This was not checked against the navigation3 1.2.1 / lifecycle-viewmodel-navigation3 source.
- **Fix:** first confirm it with a test: open the same movie in two tabs, pop one, and check the other. If it is confirmed, add the tab to the key (`MovieDetailsKey(movieId, tab)`) or set a distinct `contentKey` per stack.

### S10. Back on the root of a non-start tab exits the app
- **Where:** `ui/navigation/EncoreApp.kt` (the only `BackHandler` is for Settings, line 136)
- **Problem:** at the root of Swipe, Genie or Arcade, Back closes the app instead of going to Explore. Android's guidance for a fixed start destination says it should go to Explore.
- **Fix:** `BackHandler(enabled = current != Destination.Explore && currentStackSize == 1) { current = Destination.Explore }`.

### S11. Lowering `MissingTranslation` to a warning also hides ES gaps
- **Where:** `android/app/build.gradle.kts:39-42`; `res/values-de/strings.xml`, `res/values-pt/strings.xml`
- **Problem:** the downgrade applies to every locale, so a missing Spanish key no longer fails lint. The partial DE/PT stubs translate only the 5 nav and placeholder keys, so German and Portuguese devices get a mixed-language UI.
- **Fix (owner's call):**
  - **Option A:** delete the two stubs and restore the error. Lint only checks locales that exist, so EN/ES parity would be enforced again until DE/PT land.
  - **Option B:** keep the stubs and add a unit test that compares EN and ES key sets.

### S12. Missing or weak tests
- **`androidTest`** has only Room tests. There are no Compose UI tests at all. Missing:
  - Swipe directions, the snap-back threshold, custom actions, and a second drag mid-fling (B1).
  - Explore/Details empty and error states, poster fallback, and sort-menu `selected` semantics.
  - Genie `ReasonText` with one name and with two.
  - TalkBack was never tried on a device (`validation/android-stage-4.md:63`).
- **Swipe:**
  - A rating made in Details while the Swipe ViewModel is alive.
  - Undo while a write is still pending.
  - Retry after only `movie()` fails (S1).
- **History:** undoing a changed value (not a first rating) puts the row back in its original order (`previousAt`).
- **Genie:**
  - A `summaries()` failure.
  - A retry that fails again.
  - Content → NoMatches after a like is undone.
  - `limit = 0` and a negative limit in `RecommendationEngineTest` (the clamp at `RecommendationEngine.kt:94` is untested).
- **Explore:**
  - A text change and then a genre change inside the 300 ms debounce window.
  - Retry while a debounce is pending.
  - `CatalogGateViewModel.retry` ignoring a double tap.
- **`SourceLink`:** uppercase `HTTPS://`, `https://` with no host, `https://user@evil.com`, and `en.wikipedia.org.evil.com`, which should be OTHER.
- **Data:**
  - `FakeRatingStore.failWrites` throws on the first write, so the unit tests never check rollback after a partial write. (The instrumented `RoomRatingStoreTest.kt:73` does cover it.)
  - There is no `MigrationTestHelper` for `user.db`. It is not needed at v1, but it should exist before the first migration.

### S13. The release build is not hardened yet (Stage 9)
- **Where:** `android/app/build.gradle.kts:30-33`
- **Problem:** `isMinifyEnabled = false`, there is no `isShrinkResources`, no `proguard-rules.pro` and no release `signingConfig`.
- **Why it matters:** this is fine before Stage 9, but turning on R8 later is where regressions will appear.
- **Fix (Stage 9):**
  - Enable minify and shrink resources, using `proguard-android-optimize.txt`.
  - Smoke-test the release build: kill the process (`adb shell am kill`) on a nested back stack and relaunch. The `@Serializable` NavKeys are restored through serializers.

---

## Nits

| # | Where | Issue | Fix |
|---|---|---|---|
| N1 | `feature/swipe/SwipeScreen.kt:294-296`, `:264-267` | `state.progress()` reads `offset.value` during composition, so the top card recomposes on every drag frame. The `actions` list is rebuilt each time. | Read the alpha in `graphicsLayer { alpha = … }`, decide visibility with `derivedStateOf`, and wrap `actions` in `remember`. |
| N2 | `core/recommendation/RecommendationEngine.kt:105-109` | Feature names are trimmed but not case-folded. Whether the data has case variants is [Unverified]. | Build the key with `lowercase(Locale.ROOT)` and collapsed whitespace, but keep the original spelling for display. |
| N3 | `feature/genie/GenieViewModel.kt:66-70` | The Stage 6 warning says to clear `features`, but `cards` goes stale too after a catalog update. | Clear both in one `invalidate()` method. |
| N4 | `feature/genie/GenieViewModel.kt:108` | Retry gives no feedback: the UI stays on Error until the new result arrives. | Emit `Loading` when retry is triggered. |
| N5 | `feature/genie/GenieScreen.kt:68`, `:150-151` | The spinner has no semantics, and `semantics(mergeDescendants)` on the card is redundant next to `clickable`. | Add a loading `contentDescription`, and use `clickable(onClickLabel = …)`. |
| N6 | `feature/genie/GenieScreen.kt:145` | The `" · "` separator is hard-coded. | Move it to resources, or leave it if this pattern is accepted app-wide. |
| N7 | `core/data/catalog/db/CatalogDatabase.kt:151` | `ORDER BY title COLLATE NOCASE` puts "Élite" after "Z". | Sort on the normalized `searchTitle`. |
| N8 | `core/data/catalog/MovieCatalog.kt:59-60` | NFD folding misses ß, Ø, Ł, Æ and Œ, so "lodz" doesn't find "Łódź". | Add an explicit fold map with tests. The catalog must be re-imported after the change. |
| N9 | `feature/details/MovieDetailsScreen.kt:122` | `SourceLink.forMovie` is re-parsed on every recomposition. | Wrap it in `remember(...)`. |
| N10 | `feature/explore/ExploreScreen.kt:265`, `:289` | The featured card has two TalkBack focus targets with the same action. | Hide the inner button from semantics. |
| N11 | `feature/explore/ExploreScreen.kt:108-141` | Fixed item keys ("search", "featured") share one key space with movie ids. | Prefix the movie keys, e.g. `"m:$id"`. |
| N12 | `feature/explore/ExploreScreen.kt:143-145` | In two-pane mode the grid doesn't show which movie is open. | Pass `selectedId`, add `semantics { selected }` and a border. |
| N13 | `feature/explore/ExploreViewModel.kt:56-57`, `feature/history/HistoryViewModel.kt:60-61` | Genres and the featured pool are cached for the ViewModel's lifetime. History re-queries ids that are no longer in the catalog on every emission. | Invalidate on catalog update (Stage 6), and remember the missing ids. |
| N14 | `feature/history/HistoryScreen.kt:100` | `NumberFormat.getIntegerInstance` is created on every recomposition. | Wrap it in `remember(locale)`. |
| N15 | `core/data/catalog/CatalogParser.kt:102` | Duplicate trivia options are accepted. | Check `options.map(String::trim).distinct().size == 4`. |
| N16 | `core/data/catalog/CatalogParser.kt:123-134` | A float in an integer field (e.g. `2001.0`) rejects the whole catalog. This follows the documented rule. | Add a test that pins this behaviour. |
| N17 | `core/data/catalog/db/CatalogDatabase.kt:298-308` | `movie()` uses three queries outside a transaction. It is safe today, but would race with a future catalog refresh. | Wrap it in `withTransaction`. |
| N18 | `core/data/rating/RatingRepository.kt:8` | `RatingValue` is persisted by name, but the enum has no warning about that. | Add a comment that renaming a constant needs a migration. |
| N19 | `ui/theme/Theme.kt:31`, `:55` | The outline colours (≈1.3:1 light, ≈1.6:1 dark) fail the 3:1 contrast needed for component boundaries. They come from DESIGN.md. | Raise with the owner. |
| N20 | `ui/theme/Theme.kt:14-58` | `secondary`, `surfaceVariant`, `surfaceContainer*`, `inverse*` and `errorContainer` are unset, so they fall back to the Material baseline purple. | Define them before Settings and Arcade add dialogs and menus. |
| N21 | `res/xml/data_extraction_rules.xml`, `full_backup_content.xml` | The rules are include-only, so Stage 6 Settings preferences will be left out of backups. | Add them when Settings lands. |
| N22 | `android/gradle/libs.versions.toml:27` | `androidx-compose-material-icons` is never used. | Remove it. |
| N23 | `android/gradle/wrapper/gradle-wrapper.properties` | There is no `distributionSha256Sum`, and `android/gradle/gradle-daemon-jvm.properties` is untracked. | Add the checksum and commit the file. |
| N24 | `feature/swipe/SwipeViewModel.kt:130-135` | When `setRating` returns false, the id stays in `pending`. This is harmless. | Remove it from `pending` when the call returns false. |

---

## Known deviations (accepted, not new bugs)

- **Locales:** Android ships EN and ES only. DE and PT are deferred on the owner's instruction, and `MissingTranslation` is a warning. This deviates from AGENTS.md / D8 / C8. In EN/ES, all 8 string files have the same keys and placeholders, and ES has `one/many/other` plurals. S11 covers the side effect on lint.
- **Package name:** `applicationId` and `namespace` are `com.encore.app`. `docs/IOS-PLAN.md` says `com.encore.developer`.
- **Unbuilt stages:** Settings and Arcade are placeholders until Stages 6–8. Release hardening is Stage 9 (S13).
- **Missing review gates:** the code-review gates for Stages 3 and 4 were skipped (`docs/IOS-PLAN.md` §0). B1, S1, S3 and S6 are all in Stage 4 code.

---

## What each reviewer checked and found correct

### 1. `core/data`: catalog import, parser, Room, ratings and undo
- **Atomic writes:** `setRating` and `undo` each run read, write and event in one `withTransaction`. Room serializes them, so rapid rate/undo calls can't interleave.
- **Undo semantics:** undo is global, LIFO and persisted. It restores the previous value with its original timestamp, or deletes a first rating. Re-picking the current value writes nothing (D7/C4).
- **`user.db` safety:** `exportSchema = true`, with `schemas/…/1.json` committed and no destructive fallback. `fallbackToDestructiveMigration` is used only on `catalog.db`. There is no foreign key from ratings into the catalog.
- **Catalog replace:** clearing, chunked inserts and the meta row (written last) all happen in one transaction. A failed replace keeps the old catalog, and a test covers this.
- **Single import:** a mutex plus a Ready short-circuit allows only one import. `CancellationException` is rethrown everywhere.
- **Parser:**
  - Rejects missing or empty arrays, malformed JSON and duplicate ids.
  - Zero or blank values become null, so no ratings, years or artwork are fabricated.
  - Trivia is checked for structure and references.
  - Rejected records are counted and stored.
  - Attribution URLs are kept.
- **Query safety:** LIKE patterns are escaped. `summaries` is chunked to stay under the 999-variable limit. `recommendationFeatures` reads a consistent snapshot.

### 2. `core/recommendation` and `feature/genie`
- **Weights:** genre 1, director 3, actor 2, with equal negative weights for dislikes. A repeated feature within one movie counts once, and blank names are skipped.
- **UNSEEN and rated movies:** UNSEEN gives no signal. Every rated movie, UNSEEN included, is excluded as a candidate.
- **Thresholds:** only scores above 0 are shown, and the `liked == 0` early exit is correct.
- **Ordering is deterministic:**
  - Candidates: score, then popularity (missing values last), then id.
  - Reasons: points, then kind, then name.
- **Feature cache:** `NonCancellable` combined with `mapLatest` caches the feature list correctly, and a test covers it.
- **Threading:** scoring runs on `Dispatchers.Default`.
- **Strings and accessibility:** EN/ES keys and plurals match, headings are marked, and buttons are at least 48 dp.

### 3. `feature/swipe`, `feature/history`, `core/ui/RatingUi`
- **Queue:** it excludes every rated movie, UNSEEN included, and reacts live to ratings and undo made anywhere. Undo of a first rating brings the movie back.
- **Double-tap guard:** buttons, custom actions and a drag release together still rate only once.
- **Drag state:** it is fresh per movie, and an upward or short drag snaps back.
- **Stamps:** they show an icon plus a word, so the choice never depends on colour alone, and they are hidden from TalkBack.
- **Buttons and actions:** the 56–64 dp buttons are labelled, and the card has three custom actions plus a click label.
- **History:** stable keys, newest first, ratings of removed movies hidden but kept, separate empty and filtered-empty states.
- **One-shot messages:** the messages `Channel` is consumed once.

### 4. `feature/explore`, `feature/details`, `feature/catalog`, `core/ui` posters and state screens
- **Search:** lowercasing uses `Locale.ROOT` (Turkish İ works). Accent folding is shared by import and query, and LIKE wildcards are escaped.
- **Debounce:** only text changes are debounced, and `mapLatest` cancels older searches.
- **Posters:**
  - Loaded by Coil 3 with OkHttp and a 150 MB disk cache in `cacheDir`.
  - The procedural fallback shows while loading, on failure, and when the URL is null.
  - Posters are decorative to TalkBack.
- **`SourceLink`:** allows only http(s) with a host, which rejects `javascript:`, `intent:` and `file:` (tested). The host check matches exact hosts or real subdomains. `ActivityNotFoundException` is caught.
- **Attribution and ratings:** source links, licence text and the image note are present. The rating badge is hidden when the rating is null, so nothing is fabricated.
- **Lists:** stable keys and `contentType`.
- **Accessibility:** 48 dp targets, headings, and `selected` semantics on the sort menu.

### 5. App shell, DI, navigation, theme, build config, resources
- **Cleartext:** allowed only in the debug overlay, for `127.0.0.1` and `localhost` without subdomains. Release uses HTTPS and has no network config.
- **Backups:** the `cloud-backup`, `device-transfer` and API 26–30 rules are identical. They include only `user.db` and `-wal`. `catalog.db` and the poster cache are excluded.
- **Manifest:** only the launcher Activity is exported, and the only permission is `INTERNET`.
- **`AppContainer`:** uses `applicationContext`, its `lazy` properties are synchronized, and it holds no scope that could leak.
- **Navigation:** NavKeys are `@Serializable`. The tab and Settings flag use `rememberSaveable`, and per-tab state uses a `SaveableStateHolder`.
- **Display and theme:** edge-to-edge is set up correctly. Dynamic colour is deliberately off, and dark mode follows the system.
- **Nav bar accessibility:** items have labels and `selected` semantics.
- **Repo hygiene:** the catalog asset is generated under `build/` and its path is gitignored. `local.properties`, `.idea/` and `.env*` are ignored.

---

## Known gaps in this review

- **Nothing was run.** No Gradle build, unit tests, lint, emulator or device. The last recorded green run is in `validation/android-stage-5.md` (107/107 JVM tests).
- **TalkBack** and other device accessibility behaviour were judged from the code only.
- **S5 and S9** depend on library internals (Room flow error behaviour, the navigation3 entry ViewModel stores) that were not checked against library source or with a test.
- **Compose performance (N1)** was judged from reading the code. It was not profiled.
- **Recommendation quality on the real catalog** was not checked. The real catalog was not read, so whether its data has case variants (N2) or float years (N16) is unknown.
- **Library versions** in `libs.versions.toml` were not checked against current releases or advisories.
- **Out of scope:** `api/`, `ios/`, and the untracked screenshots in `validation/android-stage-3/` and `android-stage-4/`. C9 forbids committing screenshots that show third-party posters, so check those before committing them.
