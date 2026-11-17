# Android Stage 0 verification

Date: 2026-11-17

Scope: Compose app shell under `android/`. Encore light theme (DESIGN.md §3), four-destination bottom bar (Explore, Swipe, Genie, Arcade) with placeholder content, EN/ES/DE/PT strings, Spark adaptive launcher icon. No data, ratings, API access, fonts or dark theme yet.

Toolchain: Gradle wrapper 9.9.1, AGP 9.5.1, Kotlin Compose plugin 2.4.21, Compose BOM 2026.09.00, compileSdk/targetSdk 37, minSdk 26, JDK = Android Studio bundled JBR.

Results:
- `./gradlew assembleDebug lintDebug testDebugUnitTest`: BUILD SUCCESSFUL, no compiler warnings.
- Lint: 1 warning, DataExtractionRules (`android:allowBackup` deprecated on API 31+). Not fixed; backup rules come in with the persistence work.
- Unit tests: the task runs, but no tests exist yet.
- Emulator `Medium_Phone_API_37.0` (API 37, arm64): the APK installs, `am start` returns `Status: ok`, `MainActivity` is the top resumed activity, and the crash buffer has no app entries. The UI Automator dump shows the labels Explore, Swipe, Genie, Arcade and "Explore · Coming soon".

- Second emulator `Encore_Lite` (local AVD cloned from the same API 37 image: 720x1280 at 320 dpi, 2 GB RAM, swiftshader_indirect, 4 GB data partition because the host had about 9.8 GB of free disk): it boots and renders the app correctly.
- Visually verified: the Encore background and surface colors, the bottom bar with an active pill, switching to Genie (UI dump shows "Genio · Próximamente"), and the Spanish per-app locale (`cmd locale set-app-locales`).
- Bug found and fixed: status-bar icons were white on the light background. `enableEdgeToEdge` now forces `SystemBarStyle.light` until a dark theme exists. Rebuild, lint and launch were rerun after the fix.

Limitations:
- `Medium_Phone_API_37.0` can't render on this 8 GB host (System UI ANRs, black screen). Use `Encore_Lite` or a physical device.
- German and Portuguese were not checked visually (strings are present). Rotation, TalkBack and 200% font scale were not exercised.
- No instrumentation tests.
