# Encore: Icons

Companion to [DESIGN.md](DESIGN.md) (section 6). Source: the Stitch screens in `design/stitch/screens/` and the Spark logo set in `design/spark/`. Anything not in those sources is `[Assumption]`. The Stitch export has no standalone icon sheet, so this file consolidates what the screens actually use and lists what is still missing.

## 1. Style rules

| Property | Rule |
|---|---|
| Grid | 24 dp canvas, ~2 dp safe padding |
| Stroke | 2 dp (2.2 to 2.5 dp for the three swipe actions), round caps and joins |
| Corners | Rounded, ~2.5 to 3 dp radius on rectangles (echoes Spark's 7.5-unit corners) |
| Color | Blue `#1D4ED8` stroke, one amber `#F5A524` detail per icon (dot, arrow, spark). Ink `#16161A` allowed for secondary strokes |
| Inactive | Single stroke `#64748B`, no fill, no amber |
| Active (nav) | Blue stroke plus amber detail, inside a 64x32 dp `#DBEAFE` pill |
| Text in icons | Never |
| Touch target | 48 dp minimum, even when the glyph is 18 to 24 dp (the mockups use 36 dp circles for Undo and History: enlarge) |
| Amber | Decorative only. `#F5A524` on white is 2.04:1, so never the sole carrier of meaning |
| Format | Android `VectorDrawable` XML; single-color icons tintable via theme |
| Launcher | `design/spark/android/ic_launcher*.xml` (background, foreground, monochrome) and `ic_stat_spark.xml` for notifications |

## 2. Icon inventory (README required set)

Status: **Drawn** = glyph exists in a Stitch screen; **Needed** = not designed yet.

| Icon | Used in | Glyph (from source) | Status | Accessible label (EN) | Ambiguity risk |
|---|---|---|---|---|---|
| Explore | Bottom nav | Circle (outline) | Drawn | Explore | Medium: a plain circle reads as "radio button" or "record". Consider a compass or aperture reticle |
| Swipe | Bottom nav | Two stacked cards plus arrow | Drawn | Swipe | Low |
| Genie | Bottom nav | Four-point spark | Drawn | Genie recommendations | Medium: same shape as the logo's spark and as "AI/sparkle" icons. Keep it, but do not reuse elsewhere |
| Arcade | Bottom nav | Rounded rectangle | Drawn | Arcade | High: a bare rectangle is not a game symbol. Needs a controller or joystick |
| Settings | Top bar | Present in screens (`aria-label="Settings"`) | Drawn | Settings | Low |
| Search | Explore | Not captured | Needed | Search | Low |
| Filter | Explore | Not captured | Needed | Filter | Low (avoid confusing with Sort) |
| Sort | Explore | `aria-label="Sort catalog order"` | Drawn | Sort catalog order | Medium vs Filter |
| Like | Swipe, details | Heart, green `#10B981` fill | Drawn | Like movie | Medium: details screen also has "Favorite film" (heart). Likes and favorites must not share one glyph; confirm Favorite is in scope |
| Dislike | Swipe | X, rose `#EF4444` | Drawn | Dislike movie | Medium: an X also reads as "close". Use a thumbs-down or keep X only inside the swipe action cluster |
| Unseen | Swipe | Down arrow, blue | Drawn | Mark unseen | High: a down arrow means "download/collapse". Strongest candidate for redesign (eye-off or crossed eye) |
| Undo | Swipe top bar | Counter-clockwise arrow, amber dot | Drawn | Undo previous swipe decision | Low |
| History | Swipe top bar | Clock | Drawn | Open rating history | Low |
| Back | Details, games | Not captured | Needed | Back to explore (context-specific) | Low |
| Sound on / off | Arcade | Not captured | Needed | Turn sound on / off | Low; state must be conveyed by the glyph and label, not color |
| Pause | Trivia | `aria-label="Pause game"` | Drawn | Pause game | Low |
| Trivia | Arcade | Not captured | Needed | Trivia | Medium |
| Poster Puzzle | Arcade | Not captured | Needed | Poster Puzzle | Medium |
| 50:50 lifeline | Trivia | Chip with label | Drawn | Use 50:50 lifeline | Low: text chip, not an icon |
| Source link | Details | `aria-label="Open source link and metadata"` | Drawn | Open source link and metadata | Low (attribution must be preserved) |

## 3. Navigation states

| State | Glyph | Label | Container |
|---|---|---|---|
| Inactive | `#64748B` 2 dp outline | Montserrat Medium 11 sp, `#475569` | 48x32 dp, none |
| Active | Blue stroke plus amber detail | Poppins Bold 11 sp, `#1D4ED8` | 64x32 dp pill `#DBEAFE` with `#BFDBFE` border |
| Dark theme `[Assumption]` | Inactive `#94A3B8`, active `#60A5FA` plus amber | Same sizes | Pill `#1E3A8A` at ~40% `[Assumption]` |

## 4. Swipe action cluster

| Action | Size | Border | Glyph | Gesture |
|---|---|---|---|---|
| Dislike | 56 dp | rose `#FCA5A5` | X, `#EF4444` | Swipe left |
| Unseen | 48 dp | blue `#93C5FD` | Down arrow, `#1D4ED8` | Swipe down |
| Like | 56 dp | emerald `#6EE7B7` | Heart, `#10B981` | Swipe right |

Color is never the only differentiator: each action has a distinct glyph and a text label in the gesture hint row ("Left: Dislike", "Down: Unseen", "Right: Like"). Localize those four-language strings (DE is longest).

## 5. Implementation notes (when authorized)

- Ship as vector drawables in `res/drawable`, one file per icon, names `ic_<name>` (`ic_explore`, `ic_swipe`, `ic_unseen`, ...) and `ic_<name>_filled` only if a filled variant exists.
- Every icon-only control gets a `contentDescription` from `strings.xml`, in EN, ES, DE and PT. Decorative icons get `null`.
- Tint via theme colors (`primary`, `muted`, `like`, `dislike`), not hard-coded hex, so dark theme works.
- Do not commit generated catalog assets; icon vectors are source files and may be committed.

## 6. Open items

1. Redesign Unseen (down arrow) and Arcade (rectangle); confirm Explore glyph.
2. Draw the missing icons: Search, Filter, Back, Sound on/off, Trivia, Poster Puzzle.
3. Decide whether "Favorite film" (details screen) is in scope; it is not in the README icon list or in AGENTS.md rating rules.
4. Produce a labeled overview sheet and light/dark nav-state previews (README prompt "Iconography").
5. Add ES/DE/PT accessible labels.
