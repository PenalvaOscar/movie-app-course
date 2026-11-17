# Encore: Design Guide (mobile)

Source: Google Stitch export `stitch_encore_android_app_design_system.zip` (10 HTML screens, no standalone brand doc) plus the Spark logo set in `design/spark/`. Values below are read from those files. Anything not in the source is labelled `[Assumption]` and needs owner approval. Contrast ratios were computed (WCAG 2.x formula); they are measurements, not a compliance claim.

Screens are extracted under `design/stitch/screens/<name>/code.html` (reference only, Tailwind web mockups, not production code).

## 1. Brand personality

Warm, friendly cinema. Rounded and geometric, blue-led with one amber accent. Playful in Swipe/Arcade, calm in Explore/Details. Never fabricate artwork or ratings (see AGENTS.md).

## 2. Logo: Spark

- Mark: four blue viewfinder corners (`#1D4ED8`) around an amber four-point spark (`#F5A524`).
- Assets: `design/spark/svg|png|android/` (primary, compact, duotone, app icon, adaptive-icon XML, notification icon `ic_stat_spark`).
- Use `compact` below ~48 dp, `primary` (with wordmark) in headers and onboarding, `duotone-on-dark` on dark surfaces, `spark-app-icon*` for the launcher.
- Clear space and minimum size: `[Assumption]` clear space = spark width/4; minimum 24 dp. Needs approval.

## 3. Color

### Light theme (from source)

| Role | Token | Hex | Use |
|---|---|---|---|
| Primary | `primary` | `#1D4ED8` | Logo, active nav label, links, key icons |
| Primary container | `primaryContainer` | `#2563EB` | Filled buttons (white label) |
| Primary tint | `primarySoft` | `#DBEAFE` | Chips, active nav pill, borders (`blue-100/200`) |
| Primary wash | `primaryWash` | `#EFF6FF` | Card footers, subtle fills (`blue-50`) |
| Accent | `accent` | `#F5A524` | Spark, ratings star, highlights, focus details |
| Accent container | `accentSoft` | `#FEF3C7` / text `#78350F` | Rating badge, XP chips |
| Background | `background` | `#F0F5FD` | App background |
| Surface | `surface` | `#FFFFFF` | Cards, top/bottom bars |
| Ink | `onSurface` | `#0F172A` | Primary text |
| Secondary text | `onSurfaceVariant` | `#475569` | Body secondary (`slate-600`) |
| Muted | `muted` | `#64748B` | Inactive icons, captions |
| Outline | `outline` | `#DBEAFE` / `#BFDBFE` | Card and divider borders |
| Like | `like` | `#10B981` (text `#047857`) | Like action |
| Dislike | `dislike` | `#EF4444` (text `#DC2626`) | Dislike action |
| Unseen | `unseen` | `#1D4ED8` | Unseen action (primary blue) |
| Logo ink | `ink` | `#16161A` | Icon strokes, dark surface base |

### Dark theme `[Assumption]`

The file named "explore_dark_mode" is actually light-themed. No dark palette exists in the source. Proposal, derived from `#16161A` and the Spark dark variant:

| Role | Hex |
|---|---|
| Background | `#16161A` |
| Surface | `#1E1E24` `[Assumption]` |
| On surface | `#E2E8F0` |
| Muted | `#94A3B8` |
| Primary | `#60A5FA` |
| Accent | `#F5A524` |

### Contrast (measured)

| Pair | Ratio | Verdict |
|---|---|---|
| `#0F172A` on `#F0F5FD` | 16.31 | Body OK |
| `#1D4ED8` on `#FFFFFF` / `#F0F5FD` | 6.70 / 6.12 | Text OK |
| `#FFFFFF` on `#2563EB` | 5.17 | Button label OK |
| `#475569` on `#F0F5FD` | 6.92 | OK |
| `#64748B` on `#F0F5FD` / `#FFFFFF` | 4.35 / 4.76 | Fails 4.5:1 on background for small text. Use `#475569` for text, keep `#64748B` for icons only |
| `#F5A524` on `#FFFFFF` | 2.04 | **Never** amber text or icons on white; amber is decorative or sits on ink |
| `#0F172A` on `#F5A524` | 8.75 | Dark label on amber fill OK |
| `#78350F` on `#FEF3C7` | 8.15 | Rating badge OK |
| `#10B981` on `#FFFFFF` | 2.54 | Fill and icon only; use `#047857` (5.48) for text |
| `#EF4444` on `#FFFFFF` | 3.76 | Icon only; use `#DC2626` (4.83) for text |
| Dark: `#E2E8F0` / `#94A3B8` / `#60A5FA` / `#F5A524` on `#16161A` | 14.64 / 7.04 / 7.10 / 8.84 | OK |

Status colors must never be the only signal: pair with icon and label (Like/Dislike/Unseen have distinct glyphs and text).

## 4. Typography

Fonts (both SIL OFL 1.1, Google Fonts): [Poppins](https://fonts.google.com/specimen/Poppins) for display, titles, buttons, nav-active label; [Montserrat](https://fonts.google.com/specimen/Montserrat) for body, labels, metadata. Bundle as app resources (`res/font`) for offline use; do not load from a CDN.

| Token (Material 3) | Family | Size / line | Weight |
|---|---|---|---|
| Display Large | Poppins | 32 / 40 sp | 800 |
| Headline Medium | Poppins | 24 / 32 sp | 700 |
| Title Medium | Poppins | 16 / 24 sp | 600 |
| Body Large | Montserrat | 14 / 20 sp | 400 |
| Label Medium | Montserrat | 12 / 16 sp | 600 |
| Label Small | Montserrat | 10 / 14 sp | 500 |

Notes: the source mockups also use monospace for metadata (`Dir. … • 2023`); replace with Montserrat Label Small for consistency `[Assumption]`. Source claims "verified at 200% text scale" but that was not independently tested. Labels at 10 sp are small: layouts must reflow at 200% and the four languages (EN/ES/DE/PT, DE runs longest) must not truncate controls.

## 5. Shape, spacing, elevation

| Item | Value (from source) |
|---|---|
| Card / hero corner | 24 dp (`rounded-3xl`); movie card 24 dp |
| Button, input, segmented | 12 dp (`rounded-xl`) |
| Inner tiles | 16 dp (`rounded-2xl`) |
| Chips, badges, icon buttons | full pill / circle |
| Screen padding | 16 to 20 dp horizontal |
| Vertical rhythm | 4 dp grid; section gap 16 dp |
| Borders | 1 dp `#DBEAFE`; cards use border plus soft shadow |
| Shadow | Light: blue-tinted (`rgba(37,99,235,.06)`); Swipe card elevated (`shadow-xl`) |

## 6. Iconography

Rounded 2 dp stroke, 24 dp grid, round caps and joins, blue stroke with an amber detail dot or accent. Inactive nav icons `#64748B`; active icon sits in a `#DBEAFE` pill (64x32 dp) with blue stroke. Required set (README): Explore, Swipe, Genie, Arcade, Settings, Search, Filter, Like, Dislike, Unseen, Undo, History, Back, Sound, Pause, Trivia, Poster Puzzle. Existing glyphs in the screens: Explore (circle), Swipe (stacked cards plus arrow), Genie (four-point spark), Arcade (rounded rectangle), Undo, History (clock), Dislike (X), Unseen (down arrow), Like (heart). No text inside icons. Every icon button needs a `contentDescription`.

## 7. Components

- **Top app bar**: 16 dp padding, white surface, 1 dp bottom border, 32 dp circular icon badge + Poppins Bold 14 sp title + mono-style 10 sp subtitle (use Label Small).
- **Bottom navigation**: 80 dp, white 95% surface, top border, 4 items (Explore, Swipe, Genie, Arcade). Active: pill 64x32 dp `#DBEAFE`, Poppins Bold 11 sp blue label. Inactive: 48x32 dp target, Montserrat Medium 11 sp `#475569`. Settings is global (top bar action).
- **Swipe card**: 3:4, 24 dp radius, white, 1 dp border, genre chip (top-left), rating badge amber (top-right), faint viewfinder watermark at 10% opacity, footer block `#EFF6FF` with director/year, Poppins Bold 24 sp title, 2-line synopsis. Poster fills the card when a supplied URL exists; procedural fallback otherwise.
- **Swipe actions**: Dislike 56 dp circle (rose border), Unseen 48 dp (blue), Like 56 dp (emerald). Gestures: left = dislike, right = like, down = unseen. Always keep the buttons as the accessible alternative. Undo and History in the top bar (36 dp circles; enlarge to 48 dp touch targets).
- **Buttons**: Primary = `#2563EB` fill, white Poppins Bold 12 to 14 sp, 12 dp radius. Secondary = white, `#DBEAFE` border, `#172554` text.
- **Chips**: pill, `#DBEAFE` fill, `#1D4ED8` text, 1 dp border; rating chip amber container.
- **Segmented control** (Arcade Games/Trivia): white, border, 12 dp, active segment `#2563EB`.
- **Arcade cards**: 24 dp, white, 1 dp border; category chip, XP/progress in accent or green text, full-width action button.
- **Trivia**: progress ring and "Question n of 5", score, 50:50 lifeline, pause; answer tiles 16 dp with correct (green) and wrong (rose) feedback plus icon.

## 8. Screen inventory (source screens)

| Screen | Source folder | Form factor |
|---|---|---|
| Explore | `explore_dark_mode` (light), `explore_tablet_wide_view`, `explore_desktop_web` | phone, tablet, web |
| Movie details | `movie_details`, `movie_details_desktop_web` | phone, web |
| Swipe | `swipe_taste_engine`, `swipe_taste_engine_desktop_web` | phone, web |
| Genie (empty state) | `genie_recommendations_empty_state` | phone |
| Arcade hub and Trivia | `arcade_hub_trivia_game` | phone |
| Typography spec | `encore_typography_brand_design_poppins_montserrat` | reference |

Mobile scope: phone is the target; tablet uses a wider layout (navigation rail at >= 600 dp `[Assumption]`). The "desktop web" screens are out of scope for the Android app.

**Not designed in the source (gaps):** Settings, preference history, Poster Puzzle, Trivia results, loading / error / offline states, a real dark theme, Spanish/German/Portuguese text layouts.

## 9. Content and behavior rules

- Placeholder titles, directors, and ratings in the mockups (e.g. "The Labyrinth of Glass", 8.7) are fictional: never ship them. Use catalog data; show no rating when none exists.
- Keep EN/ES/DE/PT strings aligned; allow ~30% text growth.
- Recommendation weights, undo, and UNSEEN behavior follow AGENTS.md; the UI must reflect them (Undo restores the previous value or removes a first rating).
- Minimum touch target 48 dp; respect system font scale and reduced motion; Back returns from details/games, with leave-game confirmation `[Assumption]`.

## 10. Decisions needing owner approval

1. Dark palette (section 3) and surface color.
2. Replace monospace metadata with Montserrat.
3. Logo clear space and minimum size.
4. Use of `#475569` for secondary text and `#047857`/`#DC2626` for status text.
5. Tablet navigation rail breakpoint.
6. Designs for the gaps listed in section 8.
