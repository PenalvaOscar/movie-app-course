# Android Stage 5 verification: Genie

Date: 2026-11-18 · versionName 0.5.0 (versionCode 6) · committed at the owner's request, on top of the Stage 4 commit.

## Scope

- **`RecommendationEngine`**: pure Kotlin, written test-first. It scores every unrated movie from the user's likes and dislikes and gives the 1–2 strongest reasons for each pick.
- **Genie tab**: up to 50 picks, best first. Each row shows the poster, title, year · director, the source rating (only when the data has one), and one or two "Because you liked…" lines. A header says what the picks are based on ("Based on 3 likes and 1 dislike"). Tapping a row opens Details. On wide windows the list and Details sit side by side, as in Explore.
- **Live updates**: rating, changing or undoing anywhere (Swipe, Details, History) recomputes the list. A movie rated from Genie's own Details, UNSEEN included, leaves the list at once, and Undo brings it back.
- **States**:
  - Loading.
  - **Get started** (no likes or dislikes yet; UNSEEN alone doesn't count): explains how Genie works, with "Start swiping" (opens the Swipe card, not History) and "Browse movies" (opens Explore).
  - **No picks yet**: there are preferences, but nothing scores above zero (for example, only dislikes). Offers "Keep swiping".
  - **Error**: offers "Try again".
- **Strings**: EN + ES in `strings_genie.xml`, including plurals. DE/PT are still pending, as in earlier stages.

## How it works under the hood

| Piece | File | Notes |
|---|---|---|
| Engine | `core/recommendation/RecommendationEngine.kt` | affinity(f) = liked movies with f − disliked movies with f. score = 1×Σ genre + 3×director + 2×Σ actor (D6). UNSEEN adds nothing, and every rated movie is excluded. Names are trimmed, blanks are skipped, and a repeated genre counts once. Only score > 0 is shown. Ties go to higher popularity (missing popularity last), then id. Reasons are the top 2 positive contributions; on equal points the order is director, then actor, then genre, then name. Ratings of movies a catalog update removed are ignored, and they are not counted in the header either (O6). The engine computes each score first and builds reason objects only for movies that score above zero. |
| Catalog read | `core/data/catalog/db/CatalogDatabase.kt` | `recommendationFeatures()` makes three flat scans (`movies`, `movie_genres`, `movie_cast`) in one transaction. It uses no `IN (...)`, so the API 26 limit of 999 variables can't apply. Each distinct name is stored once and shared (about 20 genres across 26k rows). |
| ViewModel | `feature/genie/GenieViewModel.kt` | It combines `ratings` with a retry trigger through `mapLatest` and `stateIn(WhileSubscribed(5 s))`. It is activity-scoped, so the list survives tab switches, but it computes only while Genie is on screen. Catalog features are read **once** and cached. That first read runs under `NonCancellable`, so a rating change during it doesn't throw the work away (code-review finding, covered by a test that fails without the fix). Scoring runs on `Dispatchers.Default`. Errors are logged (`EncoreGenie`) and shown as the Error state. |
| UI | `feature/genie/GenieScreen.kt` | Built by a Sonnet sub-agent on the ViewModel contract above. Each row is one clickable merged node, so TalkBack reads the title, metadata and reasons together. Rows have no `maxLines` on reasons or metadata, so they wrap at 200 % text. Genre and person names come straight from the source data (English genres), the same as in Details. |
| Navigation | `ui/navigation/EncoreApp.kt` | The Genie tab has its own Navigation 3 stack (`GenieHome → MovieDetailsKey`) using the list-detail scene with `PopLatest`, mirroring Explore. Re-selecting the tab pops back to the list. |

No new libraries or Gradle changes, apart from the version bump.

## Data reality: Genie is mostly genre-driven

Measured on `data/movies.json`: **9,672 of the 9,919 movies have no director**, and the cast averages **0.1 names per movie**. With the AGENTS.md weights, almost every score therefore comes from genres. In practice:
- Most reasons read "Because you liked Drama and Romance films". Director and actor reasons appear only for the few movies that have credits.
- Many picks tie on score, and popularity decides between them. As a result, the top of the list stays similar until the user's likes span different genres.

This follows the specified weights faithfully; it is a property of the dataset, not a bug. See "Decisions for the owner".

## Results

- **Red phase**: the tests were written first. `compileDebugUnitTestKotlin` failed with 117 unresolved references.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL (run by a Haiku sub-agent, then re-run after the review fixes). Re-run once more before the commit, with `--rerun-tasks`: BUILD SUCCESSFUL, 107/107 JVM tests, lint 0 errors.
- **JVM unit tests: 107/107 pass, 28 of them new.**
  - `RecommendationEngineTest` (16): weights, summed affinity, symmetric dislikes, the dislikes-only case, UNSEEN excluded and adding nothing, blank names, trimming and duplicate genres, ties on popularity and id, reasons and their ordering, no negative reasons, the limit, and ratings of removed movies.
  - `GenieViewModelTest` (12): get-started state, UNSEEN-only, content with reasons and counts, live removal, Undo, no-matches, features loaded once, no restart during the first load, removed-movie likes not counted, a missing card skipped, error and retry, and merged reason lines.
- **Lint**: 0 errors. 111 warnings, all `MissingTranslation` (DE/PT); 21 of them are the new Genie strings. The two `MissingQuantity` warnings on the ES plurals were fixed.
- **Compiler**: no warnings in the new code. The two `centerAlignedTopAppBarColors` deprecation warnings in `EncoreApp.kt` come from the Stage 1 top bars and were left alone.
- **Instrumented tests**: they compile but **have not been run**, because no emulator was started, pending owner OK. Two new tests in `RoomMovieCatalogTest` cover the features query: every movie with genres, director, cast in billing order and popularity, plus a movie with no genres and no cast.
- **Emulator smoke test**: not run yet, pending owner OK.

## Code review

A `code-reviewer` agent reviewed the engine, data layer, ViewModel, navigation and tests. It found **no blockers**. What it confirmed and what was changed:

- **Confirmed correct**: the spec match, the thread-safety of both caches (Main only, with `mapLatest` running one computation at a time), the error/retry path, the `liked == 0` early exit, and the navigation.
- **Fixed**:
  - A rating change during the first catalog read restarted it. Fixed with `NonCancellable`, and a test that fails without the fix.
  - Each recompute allocated about 100k reason objects; it now scores first and builds reasons only for picks.
  - Name strings were duplicated; each distinct name is now stored once and shared.
  - Errors were swallowed; they are now logged.
  - The comment now states the cache assumption: Stage 6 "Update catalog" must clear the cache.
  - A negative limit could throw; it is now clamped.
  - The `popularity` side map was removed.
  - Five missing edge-case tests were added.
- **Not changed**: re-selecting the Genie tab doesn't scroll the list to the top. That is a product call, and Explore behaves the same way.

## Decisions for the owner

- **Genre dominance.** Because the dataset has almost no credits, Genie is in practice a genre recommender, with popularity breaking ties. The options are:
  1. **Accept it as specified** (recommended). It matches AGENTS.md exactly, and the reasons are honest.
  2. Add a secondary tie-break, such as source rating. This is not in the spec and would need your OK.
- **List size: 50 picks** `[Assumption]`. The list refills as you rate.
- **Genie as list-detail on tablets** (like Explore), whereas Swipe/History is single-pane.
- **Spanish wording**: likes and dislikes are counted as "me gusta" / "no me gusta" ("Basadas en 3 me gusta y 1 no me gusta").

## Limitations

- Instrumented tests, the emulator smoke test, TalkBack, 200 % text and the tablet layout have not been run in this stage (see Results).
- The time of the first feature read on a real device is unmeasured. `[Estimate]`: a few hundred ms on a low-end API 26 phone. The spinner covers it.
- DE/PT strings are still missing, as in every stage so far.
