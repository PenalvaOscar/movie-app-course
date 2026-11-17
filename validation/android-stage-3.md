# Android Stage 3 verification: Explore + Details

Date: 2026-11-17 · versionName 0.3.0 (versionCode 4) · approved by the owner

## Scope

- **Explore**: search by title or director, ignoring case and accents; genre filter chips; sort (Most popular, Highest rated, Newest, Title A–Z); a "Featured today" card; an adaptive poster grid. Loading, empty ("No movies match" + Clear filters) and error (+ Try again) states.
- **Details**: poster, title, year · runtime, the source rating with vote count and a "not your own" caption, genres, synopsis, director, cast, a localized release date, and **Sources** cards that open `sourceUrl` / `datasetSourceUrl` (Wikipedia CC BY-SA 4.0 / Kaggle dataset) plus a poster-rights note. Missing values are omitted, never shown as 0.
- **Navigation 3**: Explore has its own back stack (`ExploreHome → MovieDetailsKey(id)`). There is a top-bar Back when details are open, and re-selecting the Explore tab pops to the root. From 600 dp, a **list-detail scene** shows both panes, with a "Select a movie" placeholder.
- **Posters**: Coil 3 with a 150 MB disk cache in `cacheDir`. The procedural fallback is a gradient seeded by the id, a faint Spark viewfinder and the title. It shows while loading, on a missing URL, on a failed load, or offline with no cache.
- Strings: EN + ES (`strings_explore.xml`, `strings_details.xml`), continuing the owner's Stage 1 instruction. DE/PT are pending.
- Rating actions on Details (Like/Dislike/Unseen) are **not** included. They belong to Stage 4 with `RatingRepository`.

## How it works under the hood

| Piece | File | Notes |
|---|---|---|
| Query contract | `core/data/catalog/MovieCatalog.kt` | `MovieQuery(text, genre, sort)`, a lightweight `MovieSummary` for cards, `SearchText` (NFD + strip marks + lowercase; escapes LIKE wildcards `% _ \`), `pickFeatured` (`floorMod(epochDay, n)`: the same movie all day, rotating daily). |
| Room queries | `db/CatalogDatabase.kt` (`RoomMovieCatalog`) | Adds `searchTitle` / `searchDirector` columns, normalized at import, because SQLite's `LIKE` folds ASCII only, so "almodovar" would miss "Almodóvar". DB **version 2**: the destructive migration drops catalog.db and the next launch re-imports it (about 10 s; ratings are not stored there yet). One `@Query` per sort, because Room can't bind `ORDER BY`. `DESC` puts NULL last, so movies without a rating, year or popularity sink to the end. The genre filter is an indexed `IN (SELECT movieId FROM movie_genres …)`. Featured candidates are the top 30 by popularity with a poster and a synopsis. |
| Explore state | `feature/explore/ExploreViewModel.kt` | Activity-scoped, so the search, filters and results survive tab switches and rotation. Typing is debounced (300 ms); genre and sort changes search at once. `mapLatest` cancels a stale search. Featured is shown only on the unfiltered catalog. |
| Details state | `feature/details/MovieDetailsViewModel.kt` | Scoped to the back-stack entry (`rememberViewModelStoreNavEntryDecorator`), so it is cleared on pop. States: Loading / Content / NotFound (id no longer in the catalog, O6) / Error. |
| Source links | `feature/details/SourceLink.kt` | Catalog URLs are untrusted: only absolute http(s) URLs with a host open. `javascript:`, `file:` and `intent:` are dropped. Duplicates are removed. |
| Shell | `ui/navigation/EncoreApp.kt` | Tab UI state is kept in a `SaveableStateHolder` keyed by tab. The Explore back stack comes from `rememberNavBackStack` (serializable keys, so it survives process death). The list-detail strategy uses `BackNavigationBehavior.PopLatest` (see bugs). |
| Images | `EncoreApplication.kt` | `SingletonImageLoader.Factory` with an OkHttp fetcher that sends `User-Agent: Encore/<version> (Android movie discovery app)`, because Wikimedia asks clients to identify themselves. It carries no user data. |

Dependencies added (versions checked against Google Maven / Maven Central on 2026-11-17): Navigation 3 `1.2.1` (runtime + ui), `lifecycle-viewmodel-navigation3` `2.11.0`, `adaptive-navigation3` `1.3.0`, Coil `3.6.3` (`coil-compose`, `coil-network-okhttp`, which pulls in OkHttp 4.12.0).

## Results

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`: BUILD SUCCESSFUL. The first run failed once, transiently, while the emulator was using most of the host RAM. The immediate rerun passed with no code change.
- JVM unit tests: **52/52 pass**. New: `SearchTextTest` 4, `FeaturedPickTest` 4, `ExploreViewModelTest` 7, `MovieDetailsViewModelTest` 3, `SourceLinkTest` 5. They were written first; red was a compile failure (91 unresolved references) before the implementation existed. Fixtures are fictional.
- `./gradlew :app:connectedDebugAndroidTest` on `Encore_Lite` (API 37): **14/14 pass**, with the new `RoomMovieCatalogTest` 9 covering all four sorts with nulls last, accent and case folding, literal `%`/`_`, genre + text + sort combined, genre ranking, featured candidates, and details with genres and cast.
- Lint: 0 errors. Warnings: `MissingTranslation` (DE/PT, now 54 strings) and the existing `DataExtractionRules`.
- Emulator smoke (`Encore_Lite`, API 37). Screenshots `01`–`12` were taken in `android-stage-3/`, but only `04` and `12` are committed: the others show third-party posters, which AGENTS.md keeps out of the repo.
  1. Fresh install: "Catalog imported from ASSET: 9919 movies". Explore shows 9,919 movies, a featured card and real posters (`01`, `02`).
  2. Tapping a card opens Details with a top-bar Back (`03`, `04`). Back returns to the grid at the same scroll position.
  3. Search "menendez" finds *Stand and Deliver* (director "Ramón Menéndez") (`05`).
  4. Comedy filter → 3,069 movies, featured hidden; the sort menu with a check on the current option (`06`). After switching Swipe → Explore, the filter, sort and scroll are unchanged (`07`).
  5. ES + dark mode: "9.919 películas", "★ 7,1", "92 votos" (`08`, `09`). A locale change recreates the activity, and the sort survived it.
  6. 1280×800 @160 dpi: rail + list-detail. The placeholder shows until a movie is picked; a second pick replaces the detail pane; system Back closes the detail and the app stays open (`10`, `11`).
  7. Airplane mode + `pm clear`: import from the asset, all posters procedural (`12`).
  8. No Encore entries in the crash buffer. One `libc` "Unwind request" line came from a system process (pid 270) during the `uimode` change.

## Bugs found and fixed during the stage

- **Back left the app in two-pane mode.** The default `PopUntilScaffoldValueChange` treats "list + detail" and "list + placeholder" as the same scaffold value, so the scene reported nothing to pop. `PopLatest` matches the top-bar Back. Verified on the emulator.
- **Autocorrect rewrote name searches** ("menendez" → "Melendez", no match). The field now sets `autoCorrectEnabled = false`.
- **The poster note said posters load "from the source link"**, which is wrong: they load from the supplied `posterUrl`. The text was corrected in EN/ES.

## Open points for the owner

- **"Highest rated" stays a pure vote average** (owner decision, 2026-11-17). Low-vote titles can top the list (for example 10.0 from 1 vote; the catalog median is 454 votes). Ties break on vote count.
- Genre names are the source's English labels (and the source has both "Animation" and "Animated"). They are not translated or merged.
- The Kaggle records (about 9,669) mostly have no director or cast, so Details hides those sections for them.

## Limitations

- Code review gate: the plan assigned Explore/Details UI to a Sonnet sub-agent with an Opus review. This stage was built in the main session without sub-agents, and no code-reviewer pass has run yet.
- When the window leaves touch mode (after adb key events, or on a tablet with a hardware keyboard), Compose focuses the search field and the IME opens. This was seen only on the emulator after `input keyevent`.
- Tablet testing used `wm size` on the phone AVD. Restoring the phone size hung System UI (the known 8 GB host limitation); a cold emulator restart fixed it. No real tablet AVD was used, and 200% font scale and TalkBack were not exercised this stage.
- DE/PT strings are not added (owner instruction), and the package is still `com.encore.app` (D13), both unchanged from earlier stages.
