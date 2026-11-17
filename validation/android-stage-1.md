# Android Stage 1 verification

Date: 2026-11-17 · versionName 0.1.0 (versionCode 2)

Scope ("Hello Encore", built on the Stage 0 shell):
- Dark theme (P1 palette, DESIGN.md §3) following system night mode, with matching window background (`values-night`) and system-bar icons.
- Typography scale (DESIGN.md §4) and shapes (§5) wired into `EncoreTheme`.
- Adaptive shell: `NavigationSuiteScaffold` (bottom bar below 600 dp, rail from 600 dp, P8).
- Top bar on every destination with a Settings action; a Settings placeholder screen with Back (system Back also closes it).
- Custom vector icons per ICONS.md: `ic_explore` (compass), `ic_swipe`, `ic_genie`, `ic_arcade` (controller, P6), `ic_settings`, `ic_back` (auto-mirrored). Monochrome and theme-tinted.
- Strings: EN and ES only, per owner instruction. DE/PT keep their Stage 0 strings and miss the 3 new keys; `MissingTranslation` is lowered to a warning in `app/build.gradle.kts` until they catch up.

Results:
- `./gradlew assembleDebug lintDebug testDebugUnitTest`: BUILD SUCCESSFUL.
- Lint: 3 MissingTranslation warnings (de/pt, expected) + the existing DataExtractionRules warning.
- Unit tests: none yet.
- Emulator `Encore_Lite` (API 37): installs and launches, no crash-buffer entries. Light and dark rendering checked by screenshot. ES per-app locale: UI dump shows Explorar, Deslizar, Genio, Arcade, Próximamente and the "Ajustes" content description. At 1280x800 / 160 dpi the rail replaces the bottom bar.

Limitations:
- Poppins and Montserrat are not bundled; both families fall back to system sans-serif (`ui/theme/Type.kt`). Downloading the TTFs needs owner approval.
- Icons are single-color; the amber detail from ICONS.md §1 is not drawn (a tinted vector can't carry two colors without a second layer).
- Dark container/outline colors not in the P1 proposal are marked `[Assumption]` in `Theme.kt`.
- Settings tap-through and rail on a real tablet AVD were not exercised; System UI ANRs on this 8 GB host during `wm size` changes.
- Package stays `com.encore.app` from Stage 0; the plan's D13 says `com.encore.developer`. Not changed here.
