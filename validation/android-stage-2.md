# Android Stage 2 verification: Catalog offline

Date: 2026-11-17 · versionName 0.2.0 (versionCode 3) · not committed (awaiting owner review)

## Scope

- **Room `catalog.db`** (rebuildable; destructive migration, per O4): `movies` (indexes on title, year, rating, popularity), `movie_genres` and `movie_cast` join tables (indexed on genre and actor, for Stage 3 filters and Stage 5 scoring), `trivia`, and a single-row `catalog_meta`.
- **Importer** with the order asset → download → "Initial data setup needed" + Retry.
- **Loading** (determinate %), **setup needed** (offline) and **error** (server / invalid data / storage) screens, shared via `core/ui/StateScreens.kt`.
- **Explore placeholder** shows the movie count: "9,919 movies in your catalog". The number is locale-formatted and the text uses a plural resource.
- Strings: EN + ES (`strings_catalog.xml`), continuing the Stage 1 owner instruction. DE/PT pending.

## How it works under the hood

| Piece | File | Notes |
|---|---|---|
| Asset bundling | `app/build.gradle.kts` (`CopyCatalogTask`) | Copies `../data/movies.json` into `build/generated/encoreCatalog/catalog/` and registers it as a generated asset dir through the Variant API. Nothing is written under `src/`. A missing file means no asset, and the app downloads instead. `-Pencore.bundleCatalog=false` forces that path. `.gitignore` also covers `android/app/src/main/assets/catalog/` and `*.zip`. |
| Parser | `core/data/catalog/CatalogParser.kt` | `kotlinx.serialization` `decodeFromStream`: reads the stream directly, without building a 10 MB `String`. Mirrors `api/setup.js`: both arrays present, ≥1 movie, unique movie ids, otherwise `InvalidCatalogException`. Per-record rules: a movie needs id + title; trivia needs a unique id, a known movie, a question, 4 non-blank options and a valid `answerIndex`. Failing records are counted, not silently dropped. A wrong JSON type fails the whole catalog, because it signals a format change. `0` and blank values become `null`. |
| Sources | `CatalogSources.kt` | `AssetCatalogSource` returns `null` when nothing is bundled. `DownloadCatalogSource` is a single `HttpURLConnection` GET (10 s connect / 30 s read timeouts); a non-200 reply becomes `HttpStatusException`. |
| Repository | `CatalogRepository.kt` | `StateFlow<CatalogState>`: `Checking → Loading(progress?) → Ready(count) / SetupNeeded(problem)`. A `Mutex` means concurrent calls import once. If a finished import exists, no source is opened. Problems are ranked OFFLINE < SERVER_ERROR < INVALID_DATA < STORAGE, and the most informative one is shown. An invalid asset falls back to the download. A storage failure stops immediately. Progress: 70% read/parse (bytes), 30% save (chunks). |
| Store | `db/CatalogDatabase.kt` (`RoomCatalogStore`) | One `withTransaction`: clear, insert in chunks of 500, then write `catalog_meta` **last**. Any failure rolls the whole transaction back, so the previous catalog survives. Process death mid-import has the same effect, and the next launch starts over. |
| UI gate | `feature/catalog/CatalogGate.kt` | An activity-scoped ViewModel, so rotation doesn't cancel an import. `Checking` renders only the background, so returning users don't see a "setting up" flash. |
| DI | `AppContainer.kt`, `EncoreApplication.kt` | Manual wiring (A5). |

## Results

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`: BUILD SUCCESSFUL.
- JVM unit tests: **29/29 pass**. `CatalogParserTest` 15 and `CatalogRepositoryTest` 14, written first (red confirmed before implementation). Fixtures are fictional.
- `./gradlew :app:connectedDebugAndroidTest` on `Encore_Lite` (API 37): **5/5 pass** (`RoomCatalogStoreTest`): stores all tables + meta, replace swaps the whole catalog, a failed replace keeps the previous catalog, more than one chunk works, an empty DB has no import.
- Lint: 0 errors, 14 warnings. 13 are `MissingTranslation` (DE/PT: 3 from Stage 1, 10 new); 1 is the existing `DataExtractionRules`.
- Release APK contains no `network_security_config` (cleartext stays debug-only). The debug APK contains `assets/catalog/movies.json` (10.2 MB uncompressed; the APK is 15.5 MB).
- Emulator smoke (`Encore_Lite`, API 37):
  1. Clean install with the asset: "Catalog imported from ASSET: 9919 movies, 1109 trivia", 0 rejected. About 10 s after the first frame, about 18 s from launch `[Estimate from logcat timestamps; slow swiftshader emulator]`. Screenshots `android-stage-2/01`, `03`.
  2. Relaunch: no re-import, count shown.
  3. No asset, airplane mode: "Initial data setup needed" + Try again (`android-stage-2/02`).
  4. No asset, `npm --prefix api start` + `adb reverse tcp:3000 tcp:3000`: "Catalog imported from DOWNLOAD: 9919 movies, 1109 trivia".
  5. No crash-buffer entries.

## Bugs found and fixed during the stage

- **Blank screen while the download connects**: the state stayed `Checking` for up to the 10 s connect timeout. It now switches to `Loading(null)` before opening each source. Test `shows loading while a source is still connecting`.
- **Download blocked on Android 17**: with targetSdk 37, local-network traffic is blocked unless `ACCESS_LOCAL_NETWORK` is granted at runtime, so `10.0.2.2` timed out. The debug URL is now `http://127.0.0.1:3000/catalog` with `adb reverse` (loopback needs no permission; verified on the emulator). Release uses the public HTTPS API and is unaffected.
- **Instrumented tests**: `kotlinx-coroutines-test` 1.11.0 clashed with the app's `coroutines-core` at runtime (`NoSuchMethodError`). The test now uses `runBlocking`, and the dependency was removed from androidTest.

## Gate review (code-reviewer agent)

No blockers. Fixed (test-first where testable):
- **Should-fix:** an unexpected exception while opening or parsing a source (for example a decoder bug or a `SecurityException`) crashed the app and left the screen on Loading. It now ends in a retryable error state. Test `an unexpected source failure is reported instead of crashing`.
- **Nit:** trivia `movieId` is now trimmed like movie ids. Test `ids are compared after trimming`.
- **Nit:** a double tap on Retry no longer queues a second attempt; the running `Job` is kept and further taps are ignored while it's active.

Not fixed, accepted for now:
- **No size cap on the download.** The full body is decoded in memory, so a huge or hostile response could run out of memory on low-RAM devices. It matters once "Update catalog" exists (Stage 6).
- **A cancelled import keeps parsing until done.** The parse is blocking, so when the activity finishes mid-import it runs to the end and the transaction then aborts. Data stays correct; it is only wasted work.

## Limitations / open points

- **The download path in debug needs `adb reverse tcp:3000 tcp:3000`** after every emulator boot, plus the API running. Without it the app shows "Initial data setup needed" (expected). Normal runs from Studio don't need it, because the asset is bundled.
- The parser holds the decoded catalog in memory before saving (not a token-level stream). The first import on API 26 has **not** been measured yet (no API 26 AVD run in this stage).
- DE/PT strings not added (owner's EN/ES-only instruction from Stage 1). `MissingTranslation` is still lowered to a warning.
- Ratings for movies removed by a catalog update (O6) and "Update catalog" in Settings belong to Stages 4 and 6. Today `replaceCatalog` is only used for the first import.
- Package is still `com.encore.app` (D13 says `com.encore.developer`), unchanged from Stage 1.
- The "System UI isn't responding" dialog in screenshot 01 is the known host limitation from Stages 0–1 (System UI process, not Encore).
- `android/gradle/gradle-daemon-jvm.properties` appeared untracked. It was generated by Gradle `updateDaemonJvm` outside this stage's changes; the owner decides whether to commit it.
