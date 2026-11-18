# iOS Stage 1 verification: core catalog

Date: 2026-11-18

Scope: `ios/EncoreCore`, a Swift package (docs/IOS-PLAN.md, Stage 1). It contains the catalog model, the parser, the in-memory snapshot with all queries, the catalog sources and the persisted store, the loader state machine, and the Explore and Details screen models. There is no UI and no Xcode project yet; no UI strings were added.

## What changed

| Area | Files (under `ios/EncoreCore/Sources/EncoreCore/`) |
|---|---|
| Package | `Package.swift`: tools 6.0, Swift 6 language mode, macOS 14 / iOS 17, no dependencies, links `sqlite3` (I2). The test target bundles `Fixtures/` |
| Model | `Model/Movie.swift`: `Movie`, `Trivia`, `MovieSummary`, `MovieFeatures` |
| Catalog | `CatalogParser`, `MovieCatalog` (protocol, `MovieQuery`, `MovieSort`), `SearchText`, `Featured` + `EpochDay`, `CatalogSnapshot`, `CatalogSource` (bundled, download), `CatalogStore` (`FileCatalogStore`), `CatalogLoader` |
| Screens | `ExploreModel`, `DetailsModel` (`@MainActor @Observable`, no SwiftUI import) |
| Support | `KotlinText` (Kotlin trim/blank, UTF-16 order, `NOCASE` compare), `JavaStringHash`, `SourceLink`, `Logging` (`os.Logger`) |
| Tests | 8 test files + `Fixtures/full-record.json` (fictional) under `Tests/EncoreCoreTests/` |

Size: 1,158 lines of source and about 1,200 lines of tests.

## How it works under the hood

- **Parser.** `JSONDecoder` reads into private DTOs. Every field goes through `decodeIfPresent`, so an absent key and an explicit `null` both fall back to the default, as Android's `coerceInputValues` does. A value of the wrong type still throws, and any throw becomes `InvalidCatalog.malformed`. Record rules and counts are the same as in `CatalogParser.kt`.
- **Kotlin and JVM parity helpers.** Without these, the same data could be cleaned, matched or sorted differently on the two platforms.
  - **Trimming.** Kotlin's `trim()` removes a slightly different set of characters than Foundation's `.whitespacesAndNewlines`: it also removes U+001C–U+001F and keeps U+0085. `kotlinTrimmed()` reproduces Kotlin's set exactly.
  - **Ordering.** Ties sort by UTF-16 code units, which is the JVM and SQLite order (plan I4). Swift's own `<` uses canonical Unicode ordering, which can differ.
  - **Title sort.** It folds A–Z only, like SQLite `COLLATE NOCASE`, so "É" sorts after "z", as on Android.
  - **Search lowercasing.** `lowercased(with: Locale(identifier: ""))` matches Java's `toLowerCase(Locale.ROOT)`, including the Greek final-sigma rule. Plain `lowercased()` does not; I checked both against the JVM.
- **Snapshot (plan A-1).** It replaces Room. At load it builds:
  - one normalized search key per title and director
  - four sort orders, kept as arrays of indices
  - a genre → movie-index map
  - the genre list
  - the recommendation features

  A query walks the chosen order and keeps the rows that match. No SQL is involved, so `%` and `_` are plain characters for free: matching is a byte-wise contains. The struct is immutable and `Sendable`, so it can be read from any thread. `movie(id:)` returns genres in alphabetical order, because that is what Room's `ORDER BY genre` showed on Android.
- **Loader.** `CatalogLoader` is `@MainActor @Observable`. Its states (`checking → loading(progress?) → ready / setupNeeded`) mirror Android's `CatalogState`.
  - **Order.** The persisted file is tried first, then the bundled resource, then the download.
  - **Off the main thread.** Parsing and indexing run in a `@concurrent` function, so the main thread never stalls on 10 MB of JSON.
  - **One attempt at a time.** Concurrent `ensureCatalog()` calls share a single running `Task`.
  - **Problems reported.** `URLError` → offline, HTTP status → server error, anything else → invalid data. Storage failures stop the attempt.
- **File store.** It writes `movies.json` atomically (temp file + rename), then `meta.json` last. A crash midway leaves a movies file without meta, which `load()` treats as "no catalog". The directory and the file are excluded from iCloud backup; the flag is set again after each atomic write, because the rename drops it.
- **Download.** It streams through `URLSession.bytes`, checks the size cap every 64 KiB and reports whole-percent progress. The cap is 50 MB `[Assumption]`, applied to both the declared and the actual length.
- **Explore.** Text changes wait 300 ms; genre and sort changes search at once. Each change cancels the previous `Task` (latest wins). Setting an equal query starts no search, matching how a `StateFlow` ignores equal values. The debounce `sleep` is injected, so tests control time without real waits.

## Differences from Android (deliberate, from the file-based design)

| Android | iOS | Why |
|---|---|---|
| The bundled asset is copied into Room | The bundled resource is read in place and never copied | No 10 MB duplicate on disk |
| The DB row count is checked at start | The persisted file is parsed on every launch, while still in `checking` | No DB. The cost was measured below |
| A store read failure stops the attempt (STORAGE) | It is reported as STORAGE, then the bundle and download are still tried | The bundle needs no storage. A STORAGE problem shows only if nothing else works |
| Connect timeout 10 s, read timeout 30 s | One 30 s idle timeout | `URLSession` has no separate connect timeout |
| No size cap on the download | 50 MB cap | Addresses the open Android review point (plan §3.2) |

## Tests

Test-first: the 8 test files were written before any source file. The first run failed to compile, because none of the referenced types existed (54 × `cannot find 'MovieQuery'`, 36 × `cannot find 'InvalidCatalog'`, and so on). That is the "red" state. There was no runtime-red step: once the implementation compiled, every test passed on its first run.

| Suite | Android source | Cases |
|---|---|---|
| `CatalogParserTests` | `CatalogParserTest` (15) | 15 ported + 2 iOS (Kotlin whitespace parity, a null inside the genres array) |
| `CatalogLoaderTests` | `CatalogRepositoryTest` (14) | 14 ported (adapted to the iOS load order) + 4 iOS (corrupt persisted file, unreadable store, ready is final, oversize) |
| `FileCatalogStoreTests` | n/a | 3 (nothing saved, round trip + backup exclusion, movies without meta) |
| `DownloadCatalogSourceTests` | n/a | 6 (stubbed `URLProtocol`: body + progress, unknown length, 503, declared and streamed over-cap, missing bundle) |
| `SearchTextTests` | `SearchTextTest` (4) | 4 ported (the LIKE-pattern cases became needle and literal-wildcard cases) + 1 iOS (final sigma, dotted İ) |
| `FeaturedPickTests` | `FeaturedPickTest` (4) | 4 ported + 1 iOS (local calendar epoch day) |
| `ExploreModelTests` | `ExploreViewModelTest` (7) | 7 ported + 1 iOS (a superseded search is dropped) |
| `DetailsModelTests` | `MovieDetailsViewModelTest` (3 of 5) | 3 ported + 1 iOS (source links). The 2 rating cases come in Stage 2 |
| `SourceLinkTests` | `SourceLinkTest` (5) | 5 ported + 1 iOS (case-insensitive host and scheme) |
| `CatalogSnapshotTests` | `RoomMovieCatalogTest` (9 + 2) | 11 ported + 6 iOS (alphabetical genres in Details, swipe order, newest tie-break, NOCASE, summaries, blank text) |
| `JavaStringHashTests` | n/a | 9 argument cases. Expected values come from the JVM (Android Studio JBR), not from memory |
| `RealCatalogCheck` | n/a | 1, skipped unless `ENCORE_REAL_CATALOG` is set |

## Commands run and actual results

| Command | Result |
|---|---|
| `swift build` (in `ios/EncoreCore`) | Clean: 0 errors, 0 warnings in Swift 6 strict-concurrency mode |
| `ios/scripts/test-core.sh` | **91 tests in 12 suites passed, 1 skipped** (`RealCatalogCheck`, by design) |
| `ENCORE_REAL_CATALOG=…/data/movies.json ios/scripts/test-core.sh --filter RealCatalogCheck` (debug) | Passed. 10,199,103 bytes; **9,919 movies, 1,109 trivia, 0 / 0 rejected** (matches the Android import log). Parse 0.142 s, index 0.290 s. 37 genres, led by Drama, Comedy, Action. "menendez" finds *Stand and Deliver* (Ramón Menéndez) in 0.044 s. 30 featured candidates |
| Same command with `-c release -Xswiftc -enable-testing` | Passed. Parse 0.119 s, index 0.110 s, search 0.041 s |
| JVM reference values (`java` from the Android Studio JBR, a throwaway file in `/tmp`, deleted) | `String.hashCode` for 9 inputs. Lowercasing `ΟΔΥΣΣΕΑΣ İSTANBUL` gives `οδυσσεας i̇stanbul`. Foundation's root-locale lowercasing matches it; the stdlib `lowercased()` gives `…σ` and does not |

**Cold-load estimate for I3 `[Estimate]`:** about 0.23 s on this Mac in release (parse + index). If an iPhone is 2–4× slower, that is about 0.5–0.9 s, inside the 1.0 s budget but without much margin. The real number is measured on the device in Stage 5.

## Limitations

- **No device or app run.** This stage is pure logic, so nothing ran on iOS: all tests ran on macOS. `BundledCatalogSource(bundle:)` and `FileCatalogStore.applicationSupport()` are exercised for the first time in Stage 5.
- **No live network test.** The download source was tested only against a stubbed `URLProtocol`, never against the public API. That is deliberate, to avoid network use in tests.
- **Parity is checked by ported tests, not side by side.** The real catalog was parsed and counted, but its four sort orders were not diffed against Android's Room output. One remaining JVM difference is untested: kotlinx accepts quoted numbers (`"year":"1999"`) where `JSONDecoder` refuses them. The real catalog has none (0 rejected, no malformed error), but a future source change could expose it.
- **Search speed.** A full-catalog search takes about 40 ms on the Mac. It runs off the main actor and after the debounce; it will be re-checked on the iPhone in Stage 5.
- **Not committed.** It waits for your approval. `ios/EncoreCore/.build/` is ignored, as `git status --ignored` confirms.
