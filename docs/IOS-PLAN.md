# Encore iOS app: implementation plan

Status: **draft for owner approval** (2026-11-18). This plan authorizes no code: AGENTS.md requires the owner's go before implementation starts.

Repository: the iOS app lives in the **same repository as the Android app**, under `ios/`, next to `android/` (owner decision, 2026-11-18). The repository is being renamed from `movie-app-android` to **`movie-app-course`**, locally and on GitHub. Paths in this file are relative to the repo root.

Labels: `[Assumption]` = my default, needs your OK; `[Unverified]` = not yet checked against Apple docs or on the device; `[Estimate]` = a number I have not measured.

---

## 0. Context

Encore is the movie discovery app from the Master.dev workshop. Android and iOS are independent native apps built from the same UX brief. They share product rules, not code.

### Sources read

Everything comes from this repo (the Android side was read-only for this plan):

- **UX brief:** `README.md`.
- **Design and decisions:** `DESIGN.md`, `ICONS.md`, `DECISIONS.md`.
- **Product rules:** `AGENTS.md`.
- **API:** `api/server.js` and `api/setup.js`.
- **Attribution:** `data/SOURCE-ATTRIBUTION.md`.
- **Stage reports:** `validation/starter.md` and `android-stage-0.md` to `android-stage-5.md`.
- **Approved Android plan:** `~/.claude/plans/write-the-pan-to-zazzy-pebble.md`. It is not in either repo.
- **Android code that defines exact behaviour:**
  - `RecommendationEngine.kt`
  - `RatingRepository.kt`
  - `UserDatabase.kt`
  - `CatalogParser.kt`
  - `MovieCatalog.kt`
  - `CatalogDatabase.kt`
  - `MoviePoster.kt`
  - `ExploreViewModel.kt`
  - `GenieViewModel.kt`

`reference/upstream-lumio/` served only as an example of how a plan and its validation can look. None of its decisions apply here.

### State of the Android app that iOS inherits

- **Stages 0–5 are done** and committed: shell, catalog, Explore/Details, ratings/Swipe/History, Genie. **Stages 6–9 are pending**: Settings, Arcade/Trivia, Poster Puzzle, hardening.
  - Trivia and Puzzle rules therefore exist only in the Android plan. Android has no code for them yet.
- **Android ships EN and ES only.** DE and PT were deferred on your instruction from Stage 1, and `MissingTranslation` was lowered to a warning (111 warnings by Stage 5). AGENTS.md still requires all four locales to stay aligned. See open question Q5.
- **Android package mismatch:** the package is still `com.encore.app`, while the plan's A1/D13 says `com.encore.developer`. DECISIONS.md has no D13 entry yet.
- **Code-review gates were skipped.** The plan put a code-reviewer pass at the Stage 3 and 4 gates; neither ran. Stage 5 had one.

### iOS location

`ios/` in this repo, which does not exist yet. The earlier empty sibling folder `../movie-app-ios` was removed. Shared material is used in place, not copied:
- the brief (`README.md`)
- design and decisions (`DESIGN.md`, `ICONS.md`, `DECISIONS.md`)
- the API (`api/`)
- the catalog download (`data/movies.json`, ignored)
- attribution (`data/SOURCE-ATTRIBUTION.md`)
- brand assets (`design/spark/`)
- the fonts already in `android/app/src/main/res/font/`

### Facts I verified on this Mac on 2026-11-18

| Fact | How I checked |
|---|---|
| macOS 26.5.2, 51 GB free on `/`, Command Line Tools only, Swift 6.3.3 | `sw_vers`, `df`, `swift --version`, `xcode-select -p` |
| The CLT ship `Testing.framework`, the Observation macro plugin (`libObservationMacros.dylib`) and the SQLite 3.51 headers and library | Directory listing; a script with `import SQLite3` + `@Observable` ran |
| The CLT do **not** ship the SwiftData macro plugin, so `@Model` cannot compile before Xcode is installed | Plugin directory listing (only `ObservationMacros` and `SwiftMacros`) |
| `swift test` with Swift Testing works with this exact command (a throwaway probe package with an SQLite-backed test passed; the probe was deleted afterwards): | Ran it |

```bash
F=/Library/Developer/CommandLineTools/Library/Developer/Frameworks; L=/Library/Developer/CommandLineTools/Library/Developer/usr/lib; swift test -Xswiftc -F$F -Xlinker -F$F -Xlinker -rpath -Xlinker $F -Xlinker -rpath -Xlinker $L
```

Without the second rpath (`…/usr/lib`, which holds `lib_TestingInterop.dylib`), the test bundle crashes at load.

### Apple facts that shape the plan

- **Xcode 26.6** needs macOS Tahoe 26.2 or later. It ships the iOS 26.5 SDK and Swift 6.3, takes deployment targets iOS 15–26.5, and supports on-device debugging on "iOS 15 or later".
- **Xcode 27.x** needs macOS 26.6 or later. Source: Apple's [Xcode support page](https://developer.apple.com/support/xcode/).
- **Free Personal Team:**
  - provisioning profiles expire 7 days after issue, after which the app must be rebuilt and reinstalled
  - at most 3 apps per device
  - App IDs are limited and also expire after 7 days

  Source: Apple's [Choosing a Membership](https://developer.apple.com/support/compare-memberships) page. Apple publishes no complete list of capabilities a free team cannot use `[Unverified]`. Encore needs none of the paid ones (no push, iCloud, App Groups).
- **Per-app language** has lived in the system Settings app since iOS 13. Apps can deep-link there with `UIApplication.openSettingsURLString`, and the app relaunches in the new language. Source: WWDC19 session 403, *Creating Great Localized Experiences with Xcode 11*. Whether the row appears depends on the app shipping more than one localization `[Unverified, third-party sources]`.
- **Download size risk:** a developer-forum report puts the base Xcode 26 download at about 2.7 GB. The "iOS platform support" component, which includes the Simulator and is reportedly needed for building against the SDK, is about 10.3 GB ([forum thread](https://developer.apple.com/forums/thread/817687)) `[Unverified]`. **Treat installing Xcode as a Wi-Fi task, not mobile data.**

---

## 1. Android → iOS mapping

Rule applied everywhere: Apple frameworks first. **This plan adds no third-party dependencies.**

| Component | Android choice | iOS choice | Reason | Risk |
|---|---|---|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 | Swift 6 (language mode 6), SwiftUI | Native toolkit; Material 3 is not an iOS idiom | Low |
| Min / target OS | minSdk 26, target 37 | Deployment target **iOS 17.0**, built with the iOS 26.5 SDK (Xcode 26.6) | 17 is the floor for `@Observable` and `sensoryFeedback` and matches the Package.swift platforms you gave. No feature planned needs 18 | iPhone OS version unknown (Q2) |
| Module structure | Single `:app`, packages by feature | **`EncoreCore` Swift package** (domain, stores, screen models) plus a thin `Encore` app target (views, resources) | Pure logic builds and tests with `swift test` before Xcode exists; the app target stays small | Screen models written before their views may need small reshaping |
| Screen state | `ViewModel` + `StateFlow`, sealed `UiState` | `@Observable @MainActor` model classes (Observation only, no SwiftUI import) + an `enum` for Loading / Empty / Error / Offline / Content | Observation macros ship in the CLT (verified), so screen models are unit-testable pre-Xcode | Low |
| Async streams | Kotlin `Flow`, `mapLatest`, `stateIn` | `AsyncStream` from store actors; `Task` cancellation for "latest wins"; `.task(id:)` in views | Structured concurrency is the native equivalent | Medium: Swift 6 strict concurrency needs care with `Sendable` |
| DI | Manual `AppContainer` | Manual `AppContainer`, passed via SwiftUI `.environment(_:)` | Same approach, no framework | Low |
| Catalog storage | Room `catalog.db` (rebuildable), join tables, indexed `LIKE` queries | **Validated JSON file → immutable in-memory `CatalogSnapshot`** with precomputed indices (search keys, per-sort order arrays, genre → ids). The downloaded file is persisted atomically in Application Support | 9,919 movies fit comfortably in memory. Genie already loads every movie's features (Android does too). All queries become pure Swift, testable pre-Xcode, with no SQL to keep in sync | JSON decode on every cold start. Measured in Stage 5; above the threshold, fall back to a binary cache (I3) |
| User data storage | Room `user.db`: `ratings`, `rating_events`, real migrations, exported schema | **SQLite via Apple's built-in `libsqlite3`** (`import SQLite3`), file `user.sqlite`: the same two tables, `BEGIN IMMEDIATE` transactions, WAL, migrations via `PRAGMA user_version`, no destructive fallback | 1:1 with Android's transactional undo. Runs under `swift test` (verified). SwiftData's macros don't compile without Xcode (verified) and its rollback semantics are less explicit. GRDB would be nicer but is a dependency for two tables | About 150 lines of hand-written SQL wrapper; covered by tests on file-backed DBs |
| Settings storage | DataStore Preferences | `UserDefaults` via `@AppStorage` (theme, sound) | The platform standard for small preferences | Low |
| Navigation | Navigation 3, `NavigationSuiteScaffold` (bar < 600 dp, rail ≥ 600 dp), a back stack per tab | `TabView` with 4 tabs, each with its own `NavigationStack(path:)`. Explore and Genie use `NavigationSplitView` (list-detail), which collapses to a stack in compact width. iPad gets the system tab bar / sidebar | HIG: the tab bar is the primary navigation; split view is the system list-detail | Re-tap-to-pop-root is not automatic in SwiftUI; it is built with a selection proxy `[Unverified]` |
| Settings entry | Top-bar action on every destination | Toolbar button (gear SF Symbol) on each tab root, presenting **Settings as a sheet** | HIG: modal sheet for app settings that are not a primary destination | Low |
| Images | Coil 3 + OkHttp, 150 MB disk cache, custom User-Agent | In-house `PosterLoader` actor: `URLSession` with its own `URLCache` (150 MB disk in Caches), the same descriptive User-Agent, ImageIO downsampling to cell size, an `NSCache` for decoded images. Offline: retry with `.returnCacheDataDontLoad` | `AsyncImage` has no controllable disk cache, can't set headers, and doesn't downsample. Kingfisher or Nuke would add a dependency for about 120 lines of code | Medium: memory on a 9,919-poster grid needs downsampling (planned) |
| Procedural poster | Gradient, hue = `floorMod(id.hashCode(), 360)`, HSL(h, .55, .32) → HSL(h+40, .6, .16), Spark viewfinder, title | Same formula, with **Java's `String.hashCode` reimplemented over UTF-16**, so a movie gets the same colours on both platforms. Swift's `hashValue` is randomized per launch and must not be used | Cross-platform visual parity, no artwork fabricated | Low |
| Bundled catalog | Gradle `CopyCatalogTask` → generated asset dir (nothing under `src/`); `-Pencore.bundleCatalog=false` | Xcode **Run Script build phase** copies `$(ENCORE_CATALOG_SOURCE)` straight into the built product's Resources (nothing written into the source tree). A missing source file means no bundled catalog. `ENCORE_BUNDLE_CATALOG=NO` forces the download path | Same behaviour as Android; works with user-script sandboxing when the input and output files are declared | Path outside `SRCROOT` must be declared as an input `[Unverified until Stage 5]` |
| Catalog download | `HttpURLConnection` GET; debug `http://127.0.0.1:3000` via `adb reverse`; release public HTTPS | `URLSession` GET. **Debug default: the public HTTPS API**; an optional debug-only local URL (Mac LAN IP) set in an untracked xcconfig. Release: public HTTPS only | iOS has no `adb reverse`. A physical iPhone reaches the Mac only over the LAN, which needs an ATS exception plus the Local Network permission prompt | LAN path untested on a hotspot network (Q4) |
| Cleartext policy | Debug-only `network_security_config` | Debug-only Info.plist ATS exception + `NSLocalNetworkUsageDescription`, only if the LAN path is enabled | ATS is the iOS equivalent | Low |
| Localization | `values{,-es,-de,-pt}/strings_<feature>.xml`, AppCompat per-app locales, lint `MissingTranslation` | **String Catalog** `Localizable.xcstrings` (en, es, de, pt) with plural variations, plus `InfoPlist.xcstrings`. A Swift script `scripts/check-strings.swift` fails when any key lacks one of the 4 languages | The native format; one file instead of 4 × N XML files | One catalog file means sub-agents can't split strings per feature. Translations run sequentially at each gate |
| In-app language | AppCompat `setApplicationLocales` + a picker | **Settings row "Language" → deep link to the system per-app language page** (no in-app picker) | Apple's supported mechanism since iOS 13. In-app overrides (`AppleLanguages` hacks) need a relaunch and are unsupported | Behaviour differs from Android by design (A-2) |
| Fonts | Poppins static + Montserrat variable TTFs in `res/font`, OFL in assets | The **same TTF files, copied from `android/app/src/main/res/font/`** (no download) + `UIAppFonts`; `Font.custom(_:size:relativeTo:)` so they scale with Dynamic Type; OFL texts in About | Offline, parity | Montserrat variable weights via `Font.weight` `[Unverified]`; fallback is static instances |
| Icons | Custom VectorDrawables per ICONS.md | **SF Symbols** for system actions (search, settings, undo, history, back, pause, sound, heart, hand.thumbsdown, eye.slash, gamecontroller, sparkles). Custom symbols only where the brand needs it | SF Symbols scale with Dynamic Type, match text weight and carry accessibility; HIG prefers them | Brand amber detail lost on nav icons (as on Android, which is single-colour too) |
| App icon | Adaptive icon XML from `design/spark/android/` | Single-size 1024 px `spark-app-icon-1024.png` in `Assets.xcassets` | No extra tool download (Icon Composer not required) | No Liquid Glass layered icon (I7) |
| Splash | `core-splashscreen` | `UILaunchScreen` Info.plist key (background colour + Spark image) | Native; storyboards not needed | Low |
| Haptics | Light haptic, respects system setting | `.sensoryFeedback(.impact(weight: .light), trigger:)` | iOS honours the system haptics setting itself | Low |
| Game sound | `ToneGenerator` | `AVAudioEngine` + `AVAudioSourceNode` generating short tones in code, session category `.ambient` | No audio asset files; `.ambient` respects the Silent switch and mixes with other audio | Low |
| Reduced motion | Animator duration scale | `@Environment(\.accessibilityReduceMotion)` → no fling or tilt animation | Native setting | Low |
| Accessibility actions | TalkBack custom actions, merged nodes | `.accessibilityAction(named:)`, `.accessibilityElement(children: .combine)`, `AccessibilityNotification.Announcement` | VoiceOver equivalents | Verified by ear on the iPhone in Stage 9 |
| Undo feedback | M3 Snackbar | Transient bottom toast overlay + a VoiceOver announcement | iOS has no snackbar component | Low |
| Backup | Auto Backup: `user.db` + settings only | Default iCloud device backup covers Application Support + UserDefaults. The **downloaded catalog file is marked `isExcludedFromBackup`**; the poster cache lives in Caches, which is never backed up | Same outcome as O5 | Low |
| State across process death | `rememberNavBackStack` (serializable keys) | `@SceneStorage` for the selected tab and Codable navigation paths | Native restoration | Low |
| Logging | `Log` tags (`EncoreGenie`) | `os.Logger` (subsystem = bundle id, one category per feature) | Native | Low |
| Unit tests | JVM JUnit + Room instrumented tests on the emulator | Swift Testing in `EncoreCore` via `swift test` (pre-Xcode); after Xcode, also `xcodebuild test` on the iPhone for app-hosted tests | Pre-Xcode testability is the main design driver | UI test runner counts as an extra app against the 3-app limit `[Assumption]` |
| Build tooling | Gradle wrapper, version catalog | SwiftPM (`Package.swift`) + an Xcode project created in Xcode 26.6 | No XcodeGen or Tuist: a dependency and an extra tool | `.pbxproj` diffs are noisy (accepted) |
| Device runs | Emulator `Encore_Lite` + Studio | Physical iPhone, free Personal Team. Claude builds with `xcodebuild` and installs/launches with `xcrun devicectl`; you check visually and send screenshots | No Simulator planned; the Simulator tool cannot drive a physical iPhone | `devicectl` flags to confirm in Stage 4 `[Unverified]` |

---

## 2. Decisions

### 2.1 Carried over unchanged (product parity)

| # | Rule | Android source |
|---|---|---|
| C1 | App name **Encore**; Spark brand, palette, Poppins + Montserrat, shapes | D1, B1–B4, DESIGN.md |
| C2 | Four primary destinations: Explore, Swipe, Genie, Arcade. Settings is global | B5 |
| C3 | Ratings LIKE / DISLIKE / UNSEEN. LIKE and DISLIKE feed scoring. UNSEEN is saved and excluded from the swipe queue and recommendations | D7 |
| C4 | `RatingRepository` is the only writer. Each change and its undo event are written in one transaction. Undo is global and LIFO, persisted across restarts. It restores the previous value **with its original timestamp**, or removes a first rating. Re-picking the current value is a no-op with no undo step. Undo history is unbounded | Stage 4 |
| C5 | Recommendation weights: genre 1, director 3, actor 2; symmetric negative for dislikes; only score > 0; full tie-break rules in §3.4 | D6, Stage 5 |
| C6 | Offline first. Catalog sources in order: imported → bundled → download → "Initial data setup needed" + Retry. Whole-catalog and per-record validation as in `CatalogParser` | D9, Stage 2 |
| C7 | Never fabricate posters or ratings: missing values are hidden, never shown as 0. Supplied URLs with a procedural fallback. Source links (`sourceUrl`, `datasetSourceUrl`) plus a poster-rights note in Details. About lists the sources, the CC BY-SA notice and the font OFL licences | D10, Stage 3 |
| C8 | UI in EN / ES / DE / PT, aligned. Genre names, trivia text and person names stay as in the source (English) | D8, Stage 3 |
| C9 | Do not commit catalog data, posters, archives, generated catalog assets, secrets or machine config. Screenshots showing third-party posters are not committed | D11, Stage 3 |
| C10 | Ratings of movies removed by a catalog update are kept but hidden from the UI and from scoring and counts | O6 |
| C11 | Explore: search by title **or director** (case- and accent-insensitive); genre chips; 4 sorts; a daily featured movie; an adaptive grid | Stage 3 |
| C12 | Swipe: right = Like, left = Dislike, down = Unseen. Up never chooses. Buttons + accessibility actions. Release thresholds 30% width / 20% height. The next card is promoted synchronously | B6, Stage 4 |
| C13 | Genie: up to 50 picks; header "Based on N likes and M dislikes"; states Get started / No picks yet / Error | Stage 5 |
| C14 | Trivia and Puzzle rules as in the Android plan (§3.5) | Android plan, Games |
| C15 | Leave-game confirmation; game state preserved | P9 |
| C16 | Contrast rules P2–P4, dark palette P1, Montserrat metadata P5, icon semantics P6 (Unseen = eye-off, Arcade = controller), thumbs-down for Dislike | DECISIONS §3, Stage 4 |
| C17 | Process: one stage at a time, owner gate, one commit per approved stage, no AI attribution, no push, `main` only | A9, D3, D4 |
| C18 | Tests use small committed **fictional** fixtures; previews use fictional data | Android plan |
| C19 | Debug builds only (workshop); no store listing or distribution signing | A7 |
| C20 | `versionName 0.<stage>.0`; build number increments per approved stage | Android plan |

### 2.2 Adapted (same intent, different mechanism)

| # | Android | iOS | Why |
|---|---|---|---|
| A-1 | Room for the catalog | In-memory snapshot from a validated, persisted JSON file | Removes the SQL layer and keeps the catalog logic pure; fits memory easily |
| A-2 | In-app language picker (AppCompat) | Settings row that opens the system per-app language page | Apple's supported mechanism; no relaunch hacks |
| A-3 | Bottom bar / rail at 600 dp | System `TabView`: tab bar on iPhone, sidebar-adaptable on iPad | HIG; Liquid Glass on iOS 26 comes for free |
| A-4 | Custom active-pill nav styling (DESIGN.md §7) | System tab bar tinted with the brand blue; no custom pill | Custom tab bars break Dynamic Type, VoiceOver and Liquid Glass. Brand lives in content, colour and type |
| A-5 | Games pushed onto the Arcade back stack, Back intercepted | Games presented as **`fullScreenCover`** with a Close button + `confirmationDialog`, `.interactiveDismissDisabled()` | HIG: an immersive, self-contained task is a modal. This avoids fighting the edge-swipe back gesture |
| A-6 | Snackbar for Undo | Toast overlay + VoiceOver announcement | No snackbar on iOS |
| A-7 | Debug catalog URL via `adb reverse` | Debug downloads from the public HTTPS API; LAN URL optional | No `adb reverse` on iOS |
| A-8 | Custom VectorDrawables | SF Symbols, custom symbols only where needed | Platform icon system |
| A-9 | 48 dp touch targets | 44 × 44 pt minimum (HIG) | Platform guideline |
| A-10 | 200% font scale check | Dynamic Type up to **AX5** (largest accessibility size) | iOS text-size model |
| A-11 | Emulator screenshots by Claude | Claude builds, installs and launches on the iPhone; you check visually and send screenshots, or Claude uses XCUITest attachments if Q8 allows | No Simulator; physical device only |
| A-12 | Strings split per feature file for parallel agents | One String Catalog; translations done once per gate by one sub-agent | `.xcstrings` is one JSON file |

### 2.3 New iOS-only decisions

| # | Decision | Status |
|---|---|---|
| I1 | Same repo as Android (`movie-app-course`), under `ios/`: `ios/EncoreCore/` (Swift package), `ios/Encore/` (Xcode project), `ios/scripts/`. Shared docs stay at the root; reports go to `validation/ios-stage-N.md` | **Decided** by owner 2026-11-18 |
| I2 | `EncoreCore` platforms `[.macOS(.v14), .iOS(.v17)]`, tools version 6.0, Swift 6 language mode, no dependencies, links `sqlite3` | From your brief |
| I3 | Catalog cold-load budget: **≤ 1.0 s on the iPhone** from launch to Explore content `[Assumption]`. If exceeded, cache the validated snapshot as a binary property list next to the JSON, or move the catalog into a second SQLite file | `[Assumption]` |
| I4 | String ordering pinned for parity: ids and names compared by **UTF-16 code units** (as Kotlin/JVM `String.compareTo`); title sort uses ASCII-only case folding (as SQLite `COLLATE NOCASE`). All 9,919 current ids are ASCII (max 27 chars, verified) | Parity requirement |
| I5 | App-target default actor isolation: MainActor (the Xcode 26 template default) `[Unverified until project creation]`. `EncoreCore` stays nonisolated, with actors for stores | `[Assumption]` |
| I6 | Bundle identifier: see Q1 | Open |
| I7 | App icon: a flat 1024 px PNG now; a layered Icon Composer icon is out of scope | `[Assumption]` |
| I8 | Supported devices: universal (iPhone + iPad) code, **verified on iPhone only**; iPad layouts are built with `NavigationSplitView` but untested without a Simulator or iPad | `[Assumption]`, see Q6 |
| I9 | Auto-pause games when the scene leaves `.active` (app switcher, call, lock) | `[Assumption]` |
| I10 | Never run the Android emulator, Android Studio or Gradle daemons while Xcode is building or installing. Run `./gradlew --stop` and quit Studio first (8 GB RAM; the Android reports document System UI ANRs under memory pressure) | From your brief |
| I11 | Do not opt out of Liquid Glass (`UIDesignRequiresCompatibility`); test the iOS 26 look as shipped | `[Assumption]` |

---

## 3. Architecture

### 3.1 Layout

```text
movie-app-course/                     # this repo (renamed from movie-app-android)
  AGENTS.md, CLAUDE.md, README.md, DESIGN.md, ICONS.md, DECISIONS.md, .gitignore   # shared
  api/, data/ (ignored catalog), design/, slides/, reference/                       # shared
  android/                            # unchanged
  docs/IOS-PLAN.md
  validation/android-stage-N.md, validation/ios-stage-N.md
  ios/
  EncoreCore/                         # Swift package, testable with `swift test`
    Package.swift
    Sources/EncoreCore/
      Model/          Movie, Trivia, RatingValue, Rating, RatingEvent
      Catalog/        CatalogParser, CatalogSnapshot (queries), SearchText, Featured,
                      CatalogSource (bundled/persisted/download), CatalogLoader (state machine)
      Rating/         RatingStore protocol, SQLiteRatingStore, InMemoryRatingStore, RatingRepository
      Recommendation/ RecommendationEngine
      Games/          TriviaSession, PuzzleBoard
      Screens/        ExploreModel, DetailsModel, SwipeModel, HistoryModel, GenieModel,
                      TriviaModel, PuzzleModel, SettingsModel  (@Observable, no SwiftUI)
      Support/        SourceLink, JavaStringHash, Clock, SeededRandom, Logging
    Tests/EncoreCoreTests/  (+ Fixtures/*.json, fictional)
  Encore/                             # Xcode project, created in Stage 4
    Encore.xcodeproj
    Encore/ App/, Features/<feature>/, DesignSystem/, Resources/{Fonts,Assets.xcassets,
            Localizable.xcstrings,InfoPlist.xcstrings,Licenses/}
    EncoreTests/ (app-hosted, optional)
  scripts/test-core.sh                # wraps the verified `swift test` command
  scripts/check-strings.swift         # fails on any key missing es/de/pt
  (EncoreCore/, Encore/ and scripts/ all sit under ios/)
  validation/ios-stage-N.md
```

### 3.2 Catalog

- **Load order on every launch:**
  1. **Persisted catalog**: `Application Support/Catalog/movies.json` plus `meta.json`, which is written last, so its presence means the import completed.
  2. **Bundled resource** `catalog.json`.
  3. **Download**, then persist atomically and exclude from backup.
  4. **SetupNeeded(problem)**, with problems ranked OFFLINE < SERVER_ERROR < INVALID_DATA < STORAGE, exactly as on Android.
- **States:** `Checking → Loading(progress?) → Ready(count) / SetupNeeded`. `Checking` renders only the background, so returning users see no setup flash. `Loading` is set *before* each source is opened. Retry while an attempt is running is ignored.
- **Update catalog** (Stage 7): download → validate → atomic file replace → swap the snapshot → clear Genie's feature cache. Ratings are untouched (C10). A size cap on the download addresses the open Android review point (no cap) `[Assumption: 50 MB]`.
- **Snapshot:** precomputed search keys, four sort orders, genre → ids, the featured pool, swipe order, and recommendation features. All read-only and `Sendable`.

### 3.3 Ratings

- **Tables** (matching Android `user.db` v1):
  - `ratings(movieId TEXT PRIMARY KEY, value TEXT, updatedAt INTEGER)`
  - `rating_events(id INTEGER PRIMARY KEY AUTOINCREMENT, movieId, previous NULL, previousAt NULL, value, createdAt)`
  - Enums are stored by name, timestamps in epoch milliseconds.
- **Ordering:** History lists ratings by `updatedAt DESC, movieId`; the latest event is `ORDER BY id DESC LIMIT 1`.
- **Store:** `SQLiteRatingStore` is an `actor`. `RatingRepository` publishes an `AsyncStream<[Rating]>` and `canUndo` after each committed transaction. It is the only writer, so it needs no DB triggers.

### 3.4 Exact parity rules (ported tests assert these)

| Area | Rule |
|---|---|
| Catalog whole-file | Malformed JSON or a wrong JSON type → whole catalog invalid (MALFORMED). Missing `movies` / `trivia` arrays → MISSING_*. No valid movie → EMPTY_MOVIES. Duplicate ids among valid movies → DUPLICATE_MOVIE_IDS |
| Movie record | `id`, `title` trimmed and non-empty, else rejected and counted. `year`, `runtime`, `voteCount` ≤ 0 → nil. `rating`, `popularity` ≤ 0 → nil. Blank strings → nil. `synopsis` trimmed (may be empty). `genres` and `cast` trimmed, blanks removed, de-duplicated in order. Explicit JSON `null` = default |
| Trivia record | Unique id (first wins), trimmed `movieId` that exists, non-blank `kind` and `question`, exactly 4 non-blank options, `answerIndex` in 0..<4. Otherwise rejected and counted |
| Search | NFD → strip nonspacing marks (`\p{Mn}`) → lowercase (root locale). Contains-match on title **or** director. Empty query = no filter |
| Sorts (missing values last) | Popular: popularity desc, id. Highest rated: rating desc, voteCount desc, id. Newest: year desc, releaseDate desc, id. Title A–Z: title (ASCII case-fold), id |
| Genres list | Most common first, then name |
| Featured | Pool: the top 30 by popularity with a poster and a non-empty synopsis. Pick `pool[floorMod(epochDay, pool.count)]`, epochDay from the local calendar date. Shown only when unfiltered |
| Swipe queue | All ids by popularity desc (nil last), then id, minus rated ids, minus pending writes. A failed write puts the card back and shows "Couldn't save that" |
| Recommendation | affinity(f) = #liked with f − #disliked with f. score = 1·Σgenre + 3·director + 2·Σactor. Names trimmed, blanks skipped, a repeated genre or actor counts once. Every rated movie excluded (UNSEEN included). No likes → empty list. Only score > 0. Order: score desc, popularity desc (nil last), id. Reasons: top 2 positive contributions by points desc, then kind (director < actor < genre), then name. Counts include only movies still in the catalog. Limit 50, clamped ≥ 0 |
| Source links | Only absolute http(s) URLs with a host; duplicates removed |
| Rating display | Source rating hidden when nil; vote count shown when present; "not your own rating" caption |

### 3.5 Games (Android plan rules; Android has not built them yet)

- **Trivia:**
  - Rounds of 5 questions.
  - 15 s countdown per question.
  - 100 points per correct answer, plus a 50-point streak bonus from the 3rd consecutive correct answer. `[Assumption]`: every correct answer while the streak is ≥ 3 gets the bonus.
  - One 50:50 per round, which removes two wrong options. `[Assumption]`: chosen at random.
  - Pause hides the question and freezes the timer.
  - After each answer, feedback (icon + text, never colour alone) auto-advances after 2.5 s; press-and-hold keeps it on screen.
  - Results screen: play again, or back to Arcade.
  - `[Assumption]`: a timeout counts as wrong and resets the streak.
  - Questions are drawn without repeats within a round. Clock and RNG are injected for tests.
- **Poster Puzzle:**
  - 3×3 tap-to-swap; the starting board is never already solved.
  - Progress = correct tiles / 9.
  - Completion reveals the poster and offers "New puzzle".
  - VoiceOver: each tile is labelled with its row, column and piece number, with custom actions "Select" and "Swap with selected".
  - Prefers posters already cached when offline; otherwise uses the procedural poster.

If Android Stage 7/8 changes any of these rules during review, iOS follows the reviewed Android version (Q7).

---

## 4. Stages

Order: everything that runs without Xcode comes first (Stages 0–3). Each stage ends with tests green, the four locales aligned for the strings it adds, a report `validation/ios-stage-N.md` (what changed, how it works under the hood, commands run, actual results, limitations), then **stop for owner review**. A stage is committed only after your approval; the next starts only on your go.

| Stage | Deliverable | Android counterpart | Xcode needed? |
|---|---|---|---|
| 0 | Repo prep, no app code | 0 (prep) | No |
| 1 | Core: catalog model, parser, snapshot queries, loader; Explore and Details screen models | 2, 3 (logic) | No |
| 2 | Core: SQLite rating store, repository + persisted undo; Swipe and History screen models | 4 (logic) | No |
| 3 | Core: recommendation engine, Genie model; Trivia and Puzzle engines + models | 5, 7, 8 (logic) | No |
| 4 | Hello Encore: Xcode project, theme, fonts, tabs, Settings sheet stub, 4 locales, **first iPhone run** | 0, 1 | Yes |
| 5 | Catalog in the app + Explore + Details (posters, procedural fallback, attribution, split view) | 2, 3 (UI) | Yes |
| 6 | Swipe + History + rating row in Details | 4 (UI) | Yes |
| 7 | Genie + Settings (theme, sound, language, About, Update catalog) | 5, 6 (UI) | Yes |
| 8 | Arcade hub, Trivia, Poster Puzzle | 7, 8 (UI) | Yes |
| 9 | Hardening and full validation | 9 | Yes |

### Stage 0: Prep (no app code)

- **Deliverable:**
  - Root `.gitignore` gets the iOS entries **before** anything generated exists. `data/*`, `*.zip`, `.DS_Store` and `**/build/` are already there. New:
    - `ios/**/.build/`, `ios/**/DerivedData/`, `xcuserdata/`, `*.xcuserstate`
    - `ios/Encore/Config/Local.xcconfig`
    - any generated catalog copy under `ios/`, such as `ios/**/catalog.json` except `ios/EncoreCore/Tests/Fixtures/`
  - The shared `AGENTS.md` gets an iOS section: Xcode instead of the Gradle wrapper, the physical-device workflow, the iOS catalog path. The product rules stay a single shared list. This is tracked config, so I ask before committing.
  - `DECISIONS.md` gets an iOS section (C*, A-*, I*).
  - `ios/scripts/test-core.sh`.
  - Toolchain report.
- **Tests:** none. The probe command is already verified.
- **iPhone:** none. You report the iPhone model and iOS version (Q2).
- **Before Xcode:** all of it.

### Stage 1: Core catalog

- **Deliverable:**
  - `Movie`, `Trivia`, `CatalogParser` (Codable DTOs with `decodeIfPresent`), and `CatalogSnapshot` with search, sorts, genres, featured, swipe order and features.
  - `CatalogSource` protocol (bundled, persisted, `URLSession` download) and `CatalogLoader` (states, problem ranking, retry de-duplication, atomic persist).
  - `SourceLink`, `JavaStringHash`.
  - `ExploreModel`: 300 ms debounce, latest-wins search, featured only when unfiltered.
  - `DetailsModel`: Loading / Content / NotFound / Error.
- **Tests (written first, red confirmed):** ports of the Android suites `CatalogParserTest` (15), `CatalogRepositoryTest` (14), `SearchTextTest` (4), `FeaturedPickTest` (4), `ExploreViewModelTest` (7), `MovieDetailsViewModelTest` (3), `SourceLinkTest` (5), plus the snapshot query cases from the instrumented `RoomMovieCatalogTest` (9 + 2), plus `JavaStringHash` against known JVM values. Fixtures are fictional JSON.
- **iPhone:** none.
- **Extra check (local only, nothing committed):** parse the real `data/movies.json` in a test-only run. Expect 9,919 movies and 1,109 trivia, 0 rejected, matching the Android import log. Time it on the Mac as a first `[Estimate]` for I3.
- **Before Xcode:** all of it.

### Stage 2: Core ratings and undo

- **Deliverable:**
  - `RatingStore` protocol, `SQLiteRatingStore` (actor, WAL, `user_version` = 1, no destructive fallback), `InMemoryRatingStore` (same rollback semantics, used by screen-model tests), `RatingRepository`.
  - `SwipeModel`: queue rules, synchronous promotion, put-back on failure.
  - `HistoryModel`: live list, filters with counts, removed movies hidden, 500-id chunking dropped because there is no SQL `IN`.
- **Tests (written first):** ports of `RatingRepositoryTest` (10), `SwipeViewModelTest` (9), `HistoryViewModelTest` (6), the Details rating additions (+2), and the instrumented `RoomRatingStoreTest` (3) on **file-backed** temp DBs: survive close/reopen, rollback of both tables mid-transaction, observation follows changes and undo. Plus a migration test harness (v1 baseline).
- **iPhone:** none.
- **Before Xcode:** all of it.
- **Gate:** `code-reviewer` agent pass (correctness of transactions and concurrency).

### Stage 3: Core recommendations and games

- **Deliverable:**
  - `RecommendationEngine`.
  - `GenieModel`: features loaded once and not cancelled by a rating change, recompute on rating changes, the four states, counts.
  - `TriviaSession` and `PuzzleBoard` (pure state machines with an injected clock and RNG), with `TriviaModel` and `PuzzleModel`.
- **Tests (written first):** ports of `RecommendationEngineTest` (16) and `GenieViewModelTest` (12). New Trivia tests: scoring, streak bonus, 50:50 once per round, timeout, pause freezing the timer, auto-advance and hold, round end. New Puzzle tests: shuffle never solved, swap, progress, completion.
- **Optional parity check (needs your OK, Q7):** run the Swift and Kotlin engines on the real catalog with the same fixed ratings and diff the top 50.
- **iPhone:** none.
- **Before Xcode:** all of it.
- **Gate:** `code-reviewer` pass.

### Stage 4: Hello Encore (Xcode required)

- **Deliverable:**
  - Xcode 26.6 project `Encore` that depends on the local `EncoreCore` package.
  - Bundle id (Q1); Personal Team signing set by you in Xcode (Claude does not handle your Apple ID).
  - `EncoreTheme`: P1 light/dark colours as asset-catalog colour sets, typography tokens from DESIGN.md §4 via `Font.custom(relativeTo:)`, shapes.
  - Poppins and Montserrat copied from `android/app/src/main/res/font/`, with OFL texts from `android/app/src/main/assets/licenses/`. The copies are needed because each platform bundles its own resources.
  - App icon from `design/spark/png/spark-app-icon-1024.png`; launch screen.
  - `TabView` with 4 tabs (SF Symbols, labels), each with a `NavigationStack` and placeholder content.
  - Settings gear → sheet stub.
  - `Localizable.xcstrings` en/es/de/pt; `scripts/check-strings.swift`.
  - Debug and Release configurations; `Local.xcconfig` (ignored).
- **Tests:** `swift test` (core) still green; `xcodebuild build` for the device; strings check passes.
- **iPhone verification:**
  - One-time setup, done by you:
    1. Sign in to Xcode.
    2. Enable Developer Mode on the iPhone (iOS 16+, requires a restart).
    3. Trust the developer certificate in Settings › General › VPN & Device Management.
  - Claude:
    - builds with `xcodebuild -allowProvisioningUpdates`
    - installs and launches with `xcrun devicectl` (exact subcommands confirmed here)
    - checks that the process is running and there are no crash logs
  - You check:
    - light and dark mode
    - tab switching
    - the Settings sheet
    - Spanish via the system per-app language
    - the largest Dynamic Type size
  - Screenshots are sent by you.
- **Before Xcode:** colour and typography tokens as plain Swift constants in `EncoreCore` (testable for contrast), the String Catalog keys drafted as JSON, and the check script (it runs with `swift scripts/check-strings.swift`).

### Stage 5: Catalog offline + Explore + Details

- **Deliverable:**
  - Run Script phase bundling the catalog (I-path from `ENCORE_CATALOG_SOURCE`, default `$(SRCROOT)/../../data/movies.json`, which is the repo's `data/movies.json`; `ENCORE_BUNDLE_CATALOG=NO` for the download path).
  - Catalog gate screens (loading %, setup needed, error + Try again).
  - Explore:
    - `.searchable` with autocorrection and autocapitalization off (the Android autocorrect bug)
    - genre chips
    - sort `Menu` with a checkmark
    - featured card
    - `LazyVGrid` with adaptive columns
    - empty and error states
  - `PosterLoader` + `ProceduralPoster`.
  - Details: poster, metadata, source rating + votes caption, genres, synopsis, director, cast, localized release date, Sources cards opening links, poster-rights note.
  - `NavigationSplitView` for Explore; re-tap tab pops to root.
- **Tests:** core green; app-hosted test that the bundled resource parses (if no catalog is bundled, the test is skipped and the skip is reported).
- **iPhone verification:**
  1. Clean install with the bundled catalog: count shown; cold-load time measured against I3 (`os_signpost` or a Logger timestamp, read from the device console).
  2. Relaunch: no re-import.
  3. Build with `ENCORE_BUNDLE_CATALOG=NO`, Airplane Mode: setup-needed screen.
  4. Same build, online: download from the public API (about 10 MB of mobile data, once) and persist.
  5. Airplane Mode after import: browsing works, posters fall back to procedural.
  6. Search "menendez" finds the movie directed by "Ramón Menéndez".
  7. ES + dark mode formatting ("9.919 películas", "★ 7,1").
  8. Landscape (split view only appears on Plus/Max models in landscape `[Unverified]`).
- **Before Xcode:** none beyond Stages 1–4.

### Stage 6: Swipe + History + rating in Details

- **Deliverable:**
  - Swipe card: `DragGesture`, translation and tilt, stamp overlay, thresholds, snap-back.
  - Three buttons, plus card accessibility actions Like / Dislike / Not seen.
  - Tap opens Details; light haptic on each rating.
  - Undo and History in the toolbar; undo toast + VoiceOver announcement.
  - Done state ("You've rated every movie") and error state.
  - History list with filter chips and counts.
  - Details "Your rating" segmented row (live).
  - Card sizing for landscape and AX sizes (the Android Stage 4 fix: up to square, compact footer).
- **Tests:** core green.
- **iPhone verification:**
  - each gesture direction, plus short, upward and centre releases
  - Undo of a first rating and of a change
  - force-quit, relaunch, Undo continues
  - History filters
  - change a rating from Details
  - ES + dark
  - largest Dynamic Type and landscape
  - VoiceOver: Swipe actions rotor
- **Before Xcode:** none beyond Stage 2.

### Stage 7: Genie + Settings

- **Deliverable:**
  - Genie list (merged accessibility element per row, unlimited lines), header counts, Get started (Start swiping → Swipe tab, Browse → Explore), No picks yet, Error; split view.
  - Settings sheet:
    - Appearance (System / Light / Dark via `preferredColorScheme`)
    - Game sound toggle
    - **Language** → system per-app page
    - About: data sources, CC BY-SA notice, font OFL, poster-rights note
    - Update catalog: progress, success or error, ratings untouched
- **Tests:** core green; the Settings persistence round trip as a unit test on `SettingsModel` with an injected `UserDefaults` suite.
- **iPhone verification:**
  - Genie updates live after rating, Undo and UNSEEN
  - dislikes only → No picks yet
  - theme switch applies to the sheet and the app
  - language change relaunches in DE and PT and is checked visually (first time for those locales)
  - Update catalog online and offline
- **Before Xcode:** none beyond Stage 3.

### Stage 8: Arcade, Trivia, Poster Puzzle

- **Deliverable:**
  - Arcade hub with 2 cards.
  - Trivia as a full-screen cover: progress "Question n of 5", ring timer, score, 50:50, pause, answer tiles with icon + text feedback, press-and-hold, results.
  - Poster Puzzle as a full-screen cover: 3×3 grid, selection highlight, progress, completion reveal, New puzzle, accessible tile actions.
  - Leave confirmation on Close; auto-pause on background (I9).
  - Tones gated by the sound setting.
- **Tests:** core green.
- **iPhone verification:**
  - full Trivia round including timeout, 50:50 and pause
  - backgrounding mid-question pauses
  - Close asks to confirm
  - Silent switch mutes tones
  - Puzzle offline with a cached poster and with none (procedural)
  - VoiceOver on puzzle tiles
- **Before Xcode:** none beyond Stage 3.

### Stage 9: Hardening

- **Deliverable:**
  - Accessibility audit: VoiceOver on every screen, Dynamic Type AX5, Bold Text, Increase Contrast, Reduce Motion, 44 pt targets.
  - Contrast re-check of dark values marked `[Assumption]`.
  - DE truncation pass.
  - Memory check on the Explore grid (Xcode memory gauge).
  - `xcodebuild -configuration Release build` (no local-network exception present).
  - `validation/ios.md` with actual results and limitations.
- **Tests:**
  - all core tests
  - app-hosted tests
  - the strings check
  - `git status` confirms no catalog, posters or archives are staged
- **iPhone verification:** the full smoke list from Stages 4–8, re-run on one build.
- **Before Xcode:** none.

---

## 5. Process and agent roles

- **Gates:** sequential, on `main`, one commit per approved stage, message without AI attribution, no push or remote (AGENTS.md).
- **Opus (main session):** orchestrates and writes all of `EncoreCore` (correctness-critical, test-first with the `tdd` skill).
- **Sonnet sub-agents:** SwiftUI views on the screen-model contracts (one at a time), and translations of each stage's new strings into es/de/pt before the gate.
- **Haiku sub-agents:** run `scripts/test-core.sh`, `xcodebuild` and the strings check, and summarize the output; copy fonts and icons.
- **`code-reviewer`:** a pass at the Stage 2, 3, 5 and 9 gates. These are **not skippable**, unlike Android Stages 3–4.
- **Memory rule (I10):** iOS stages never overlap with Android work in the same session. Quit Android Studio and stop the Gradle daemons before Stage 4+ builds.
- **Re-signing:** the free profile expires every 7 days. If the app stops launching between stages, rebuild and reinstall it from Xcode. This is expected, not a bug.

## 6. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| iPhone runs iOS 27 | Apple lists device support "iOS 15 or later" for Xcode 26.6, but deploying to a newer major iOS from an older Xcode is `[Unverified]` in practice | Confirm in Stage 4. Fallback: update macOS to 26.6 and use Xcode 27 (deployment target unchanged) |
| iPhone runs iOS < 17 | Deployment target too high | Q2; lower to 16 only if needed (would lose `@Observable`, a big rewrite), so check first |
| Xcode + iOS platform download (≈ 13 GB reported) on mobile data | Cost, time | Install on Wi-Fi; Stages 0–3 need no download |
| Disk: 51 GB free | Xcode install plus DerivedData can take 20+ GB `[Estimate]` | Skip the Simulator runtime; clean DerivedData between stages if needed |
| JSON cold load exceeds I3 | Slow launch | Measure in Stage 5; binary cache fallback |
| 3-app limit | The UI test runner app may take a slot `[Assumption]` | Prefer unit and screen-model tests; XCUITest only if Q8 says yes |
| Android Stage 7/8 rules change after iOS Stage 3 | Divergence | Q7: re-sync rule; the game engines are small |

## 7. Open questions (each with a recommendation)

| # | Question | Recommendation |
|---|---|---|
| Q1 | Bundle identifier? Generic ids like `com.encore.app` are probably already registered by someone else, and a free team can't use a taken id | Use a reverse domain you control plus `.encore`, e.g. `com.<yourdomain>.encore`. If you want parity with Android A1, try `com.encore.developer` first and fall back if Xcode rejects it |
| Q2 | Which iPhone model and iOS version? | Tell me before Stage 4. Deployment target iOS 17 if the phone is on 17–26.x; if it's on 27, we test Xcode 26.6 first and fall back to the macOS 26.6 + Xcode 27 path |
| Q3 | ~~Separate iOS repo?~~ | **Resolved 2026-11-18:** same repo, `ios/` folder, renamed to `movie-app-course` |
| Q4 | Debug catalog download: public HTTPS API only, or also the local Node API over the LAN? | Public HTTPS only. The bundled catalog covers normal runs; the LAN path adds an ATS exception and the Local Network prompt, and is unproven on a hotspot network |
| Q5 | Ship ES, DE and PT from Stage 4, or follow the Android EN+ES-only interim? | All four from Stage 4. AGENTS.md requires it, String Catalogs make gaps visible, and it costs one Sonnet pass per gate |
| Q6 | Universal app (iPhone + iPad) or iPhone-only? | Universal code with split views, verified on iPhone only, with iPad listed as an untested limitation. iPhone-only would contradict the brief's adaptive requirement |
| Q7 | Build Trivia and Puzzle logic now (Stage 3) from the Android plan, ahead of Android Stage 7/8? | Yes. It's pre-Xcode work and the rules are written down; iOS re-syncs if Android's review changes them. The optional Kotlin↔Swift Genie diff needs a temporary local JVM test in the Android repo: skip it unless you want it |
| Q8 | Screenshots: you take them on the iPhone, or I add an XCUITest target that captures them (uses one of the 3 app slots)? | You take them through Stage 8; decide on XCUITest at Stage 9 |
| Q9 | ~~Catalog source for the iOS bundle~~ | **Resolved by Q3:** the shared `data/movies.json`, copied into the app bundle at build time, never committed |
| Q10 | ~~Copy shared docs?~~ | **Resolved by Q3:** shared docs stay at the root; only fonts and the app-icon PNG are copied into `ios/`, because each platform bundles its own resources |
| Q11 | Commit message prefix, so the shared history tells the platforms apart | `Android:` / `iOS:` prefixes from now on (e.g. `iOS: Stage 1 core catalog`). Past Android commits stay as they are |

## Sources

- Apple, [Xcode support / minimum requirements](https://developer.apple.com/support/xcode/), checked 2026-11-18.
- Apple, [Xcode 26.6 release notes](https://developer.apple.com/documentation/xcode-release-notes/xcode-26_6-release-notes).
- Apple, [Choosing a Membership](https://developer.apple.com/support/compare-memberships).
- Apple, WWDC19 session 403, [Creating Great Localized Experiences with Xcode 11](https://developer.apple.com/wwdc19/403).
- Apple Developer Forums, [iOS SDK component download for Xcode 26.1](https://developer.apple.com/forums/thread/817687) (size figures are user reports).
- Local verification on this Mac (toolchain, SQLite, Observation, `swift test` command), 2026-11-18.
