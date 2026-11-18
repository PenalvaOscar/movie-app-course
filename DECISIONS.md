# Encore: Decisions log

Companion to [DESIGN.md](DESIGN.md) and [ICONS.md](ICONS.md). Records what is decided, what is proposed and awaiting owner approval, and what is out of scope. Owner = project owner. Nothing here authorizes starting app implementation (see AGENTS.md).

Status values: **Decided** (owner or repo rule), **Source** (taken from the Stitch export, not yet explicitly confirmed), **Proposed** (my suggestion, needs approval), **Open** (no answer yet).

## 1. Decided

| # | Decision | Source |
|---|---|---|
| D1 | App name is **Encore** | AGENTS.md |
| D2 | One repo (`movie-app-course`, renamed from `movie-app-android`) for both apps: Android under `android/`, iOS under `ios/` (plan: `docs/IOS-PLAN.md`) | Owner, 2026-11-18 (supersedes "iOS in its own repo") |
| D3 | `main` for development; `initial-state` baseline never changed or force-pushed | AGENTS.md |
| D4 | No push, remote creation or GitHub publication until the owner asks | AGENTS.md |
| D5 | No implementation or development plan until the owner authorizes it | AGENTS.md |
| D6 | Recommendation weights: genre 1, director 3, actor 2; equal negative weights for dislikes | AGENTS.md |
| D7 | LIKE/DISLIKE affect scoring; UNSEEN is saved and excluded from queue and recommendations; Undo restores the previous value or removes a first rating | AGENTS.md |
| D8 | UI languages: English, Spanish, German, Portuguese, kept aligned | AGENTS.md |
| D9 | Offline: ignored local catalog asset or persisted downloaded catalog; add the asset path to `.gitignore` first | AGENTS.md |
| D10 | Never fabricate poster URLs or ratings; use supplied URLs plus procedural fallbacks; keep source attribution | AGENTS.md |
| D11 | Do not commit movie/trivia data, posters, archives, generated catalog assets, secrets, machine config | AGENTS.md |
| D12 | Use the Gradle wrapper; `android/local.properties` stays ignored; record real validation results | AGENTS.md |

## 2. Brand and design: from the Stitch export (not yet confirmed)

| # | Decision | Status | Where |
|---|---|---|---|
| B1 | Logo direction **Spark** (viewfinder corners plus amber four-point spark); assets in `design/spark/` | Source | DESIGN.md s2 |
| B2 | Color: blue `#1D4ED8` primary, amber `#F5A524` accent, background `#F0F5FD`, ink `#0F172A` | Source | DESIGN.md s3 |
| B3 | Type: Poppins (display/titles/buttons) plus Montserrat (body/labels), both OFL, bundled locally | Source | DESIGN.md s4 |
| B4 | Shape: 24 dp cards, 12 dp buttons, pill chips | Source | DESIGN.md s5 |
| B5 | Four primary destinations: Explore, Swipe, Genie, Arcade; Settings global | Source (README plus screens) | DESIGN.md s7 |
| B6 | Swipe gestures: left dislike, right like, down unseen | Source (matches D7) | ICONS.md s4 |
| B7 | "Desktop web" Stitch screens are reference only, not part of the Android app | Proposed | DESIGN.md s8 |

## 3. Proposals awaiting approval

| # | Proposal | Why | Cost if rejected |
|---|---|---|---|
| P1 | Add a dark theme: bg `#16161A`, surface `#1E1E24`, on-surface `#E2E8F0`, primary `#60A5FA` | Source has none (the "dark mode" file is light); README asks for light/dark | Light-only; accept the tradeoff explicitly |
| P2 | Secondary text uses `#475569`; `#64748B` icons only | `#64748B` on `#F0F5FD` is 4.35:1 | Small muted text fails 4.5:1 |
| P3 | Status text uses `#047857` (green) and `#DC2626` (red); brighter greens/reds for fills and icons only | 2.54:1 and 3.76:1 on white otherwise | Low-contrast status text |
| P4 | Amber never used for text or icons on white | 2.04:1 | Same |
| P5 | Replace monospace metadata with Montserrat Label Small | One fewer font to bundle and license | Third font in the app |
| P6 | Redesign Unseen (down arrow reads as "download") and Arcade (bare rectangle) icons | Ambiguous symbols | Misread controls |
| P7 | Logo clear space = spark width / 4; minimum size 24 dp | Not in source | Unconstrained logo use |
| P8 | Navigation rail at >= 600 dp width; bottom bar below | Tablet variant exists only as a mockup | Bottom bar stretched on tablets |
| P9 | Confirm before leaving an active game; preserve state | README navigation prompt | Lost progress on Back |
| P10 | Minimum touch target 48 dp (enlarge the 36 dp Undo/History circles) | Accessibility | Small targets |

## 4. Open questions

| # | Question | Blocking |
|---|---|---|
| Q1 | Is "Favorite film" (details screen) in scope? It is not in the README icon list or AGENTS.md rating model | Details screen design, data model |
| Q2 | Are Settings, preference history, Poster Puzzle, Trivia results and loading/error/offline screens to be designed in Stitch first? | Screen set completeness |
| Q3 | Is the Stitch "Verified at 200% text scale" claim acceptable, or should it be re-tested in the app? | Accessibility sign-off |
| Q4 | Poster Puzzle and Trivia content: what data source and attribution? | Arcade design |
| Q5 | When is Android implementation authorized, and with which toolchain (Android CLI plugin, Compose)? | All code work |

## 5. Rejected or out of scope

| Item | Reason |
|---|---|
| Fabricated titles, ratings and posters from the mockups (e.g. "The Labyrinth of Glass", 8.7) | Violates D10; placeholders only |
| Tailwind/HTML from the mockups as production code | Reference only; Android uses Compose |
| Loading fonts from Google's CDN at runtime | Offline requirement (D9) |
| Web/desktop layouts | Mobile apps only (D2) |

## 6. iOS

Full rationale, risks and sources: [docs/IOS-PLAN.md](docs/IOS-PLAN.md) §1–§2 and §7. Plan approved by the owner on 2026-11-18. Numbering follows the plan: **C** = carried over from Android unchanged, **A-** = same intent with an iOS mechanism, **I** = iOS-only.

### 6.1 Carried over (product parity)

| # | Rule |
|---|---|
| C1 | Encore name, Spark brand, palette, Poppins + Montserrat, shapes (D1, B1–B4) |
| C2 | Four destinations (Explore, Swipe, Genie, Arcade); Settings global (B5) |
| C3 | LIKE / DISLIKE score; UNSEEN saved, excluded from queue and recommendations (D7) |
| C4 | `RatingRepository` is the only writer; change + undo event in one transaction; global LIFO undo persisted across restarts, restores the previous value with its original timestamp or removes a first rating; re-picking the current value is a no-op; history unbounded |
| C5 | Weights genre 1, director 3, actor 2, symmetric negatives; only score > 0; tie-breaks in plan §3.4 (D6) |
| C6 | Offline first: persisted → bundled → download → "Initial data setup needed" + Retry; validation as in `CatalogParser` (D9) |
| C7 | Never fabricate posters or ratings; procedural fallback; source links and poster-rights note; About lists sources, CC BY-SA and OFL (D10) |
| C8 | UI in EN / ES / DE / PT, aligned; genre, trivia and person names stay in the source language (D8) |
| C9 | Never commit catalog data, posters, archives, generated catalog assets, secrets, machine config, or screenshots showing third-party posters (D11) |
| C10 | Ratings of movies removed by a catalog update are kept but hidden from UI, scoring and counts |
| C11 | Explore: title-or-director search (case- and accent-insensitive), genre chips, 4 sorts, daily featured movie, adaptive grid |
| C12 | Swipe: right Like, left Dislike, down Unseen, up never chooses; buttons + accessibility actions; thresholds 30% width / 20% height (B6) |
| C13 | Genie: up to 50 picks; "Based on N likes and M dislikes"; Get started / No picks yet / Error |
| C14 | Trivia and Puzzle rules as in plan §3.5 |
| C15 | Confirm before leaving a game; state preserved (P9) |
| C16 | Contrast P2–P4, dark palette P1, Montserrat metadata P5, icon semantics P6, thumbs-down for Dislike |
| C17 | One stage at a time, owner gate, one commit per approved stage, no AI attribution, no push, `main` only (D3, D4) |
| C18 | Tests and previews use small committed fictional fixtures |
| C19 | Debug builds only; no store listing or distribution signing |
| C20 | Version `0.<stage>.0`; build number increments per approved stage |

### 6.2 Adapted to iOS

| # | Android | iOS |
|---|---|---|
| A-1 | Room catalog DB | Validated JSON file → immutable in-memory `CatalogSnapshot` |
| A-2 | In-app language picker | Settings row opening the system per-app language page |
| A-3 | Bottom bar / rail at 600 dp | System `TabView` (tab bar on iPhone, sidebar-adaptable on iPad) |
| A-4 | Custom active-pill nav | System tab bar tinted brand blue |
| A-5 | Games on the Arcade back stack | `fullScreenCover` + Close + `confirmationDialog`, interactive dismiss disabled |
| A-6 | Snackbar for Undo | Toast overlay + VoiceOver announcement |
| A-7 | Debug catalog via `adb reverse` | Debug downloads from the public HTTPS API; LAN URL optional |
| A-8 | Custom VectorDrawables | SF Symbols; custom symbols only where needed |
| A-9 | 48 dp targets | 44 × 44 pt (HIG) |
| A-10 | 200% font scale | Dynamic Type up to AX5 |
| A-11 | Emulator screenshots by the agent | Physical iPhone; agent builds/installs/launches, owner checks and sends screenshots |
| A-12 | Strings split per feature | One String Catalog, translated once per gate |

### 6.3 iOS-only

| # | Decision | Status |
|---|---|---|
| I1 | iOS in this repo under `ios/` (`EncoreCore/`, `Encore/`, `scripts/`); reports in `validation/ios-stage-N.md` | Decided (owner, 2026-11-18) |
| I2 | `EncoreCore`: platforms macOS 14 / iOS 17, tools 6.0, Swift 6 mode, no dependencies, links `sqlite3` | Decided (owner brief) |
| I3 | Catalog cold-load budget ≤ 1.0 s on the iPhone; binary cache fallback if exceeded | Proposed |
| I4 | Parity string ordering: UTF-16 code-unit compare for ids/names; ASCII-only case fold for title sort | Decided (parity requirement) |
| I5 | App target default actor isolation MainActor; `EncoreCore` nonisolated with actors for stores | Proposed (confirm at project creation) |
| I6 | Bundle identifier | Open (plan Q1) |
| I7 | Flat 1024 px app icon; no layered Icon Composer icon | Proposed |
| I8 | Universal code, verified on iPhone only; iPad untested | Proposed (plan Q6) |
| I9 | Auto-pause games when the scene leaves `.active` | Proposed |
| I10 | Never run Android emulator, Studio or Gradle daemons during Xcode builds or installs | Decided (owner brief) |
| I11 | Keep Liquid Glass (no `UIDesignRequiresCompatibility` opt-out) | Proposed |

Open iOS questions Q1, Q2, Q4–Q8 and Q11 are tracked in the plan §7 (their numbers are the plan's, not §4 above).

## 7. Change log

| Date | Change |
|---|---|
| 2026-11-17 | Stitch zip extracted to `design/stitch/screens/`; DESIGN.md, ICONS.md, DECISIONS.md created. Nothing committed |
| 2026-11-18 | D2 changed: iOS joins this repo under `ios/`; repo renamed to `movie-app-course`; iOS plan in `docs/IOS-PLAN.md` |
| 2026-11-18 | iOS plan approved by the owner; §6 iOS added (C1–C20, A-1–A-12, I1–I11) in iOS Stage 0 |
