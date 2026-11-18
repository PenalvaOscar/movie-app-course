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

## 6. Change log

| Date | Change |
|---|---|
| 2026-11-17 | Stitch zip extracted to `design/stitch/screens/`; DESIGN.md, ICONS.md, DECISIONS.md created. Nothing committed |
| 2026-11-18 | D2 changed: iOS joins this repo under `ios/`; repo renamed to `movie-app-course`; iOS plan in `docs/IOS-PLAN.md` |
