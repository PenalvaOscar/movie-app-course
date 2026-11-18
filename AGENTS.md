# Agent instructions

- Read README.md for UX context. Do not create a development plan or start app implementation until the owner authorizes it. The app is named Encore (chosen by the project owner); confirm other branding decisions with the owner.
- Preserve the initial-state branch unchanged. Use main for ongoing development and future commits. Never force-push or move the baseline branch.
- The repository contains a UX brief and a local API. Android and iOS are independent native implementations using their respective SDKs. Follow the owner’s implementation plan.
- This repository holds both apps: Android under android/, iOS under ios/. For iOS work, follow docs/IOS-PLAN.md. origin (PenalvaOscar/movie-app-course) is the only remote; there is no upstream.
- Do not commit movie/trivia data, posters, downloaded archives, generated Android or iOS catalog assets, secrets, or machine configuration.
- Fetch local data with npm --prefix api run setup; run the server with npm --prefix api start.
- Preserve local ratings and undo. LIKE/DISLIKE influence scoring; UNSEEN is saved and excluded from the swipe queue and recommendations. Undo restores the previous value or removes a first rating.
- Recommendation weights are genre 1, director 3, actor 2, with equal negative weights for dislikes.
- Keep English, Spanish, German, and Portuguese UI strings aligned.
- Preserve offline use with a locally generated, ignored catalog asset (Android asset, iOS bundle resource) or a persisted downloaded catalog. Add the asset path to .gitignore before generating it.
- Use supplied poster URLs and procedural fallbacks. Never fabricate artwork URLs or ratings. Preserve downloaded-source attribution.
- Use the Gradle wrapper. Keep android/local.properties ignored. Record actual validation results and limitations.
- No GitHub publication, remote creation, or push is authorized until the owner explicitly requests it.

## iOS

The product rules above apply to both apps; this section covers only iOS tooling and workflow.

- Layout: ios/EncoreCore/ (Swift package: domain, stores, screen models), ios/Encore/ (Xcode project, from Stage 4), ios/scripts/. Reports go to validation/ios-stage-N.md.
- Build tooling is SwiftPM and Xcode (xcodebuild), not Gradle. No third-party dependencies.
- Run core tests with ios/scripts/test-core.sh. It works with Command Line Tools only (adds the Testing.framework paths) and with full Xcode.
- Devices: a physical iPhone with a free Personal Team. The agent builds with xcodebuild and installs/launches with xcrun devicectl; the owner signs in to Xcode, handles signing and trust, checks visually and sends screenshots. The agent never handles the owner's Apple ID.
- Catalog: a build phase copies data/movies.json into the built app's Resources; nothing is written into the source tree. ENCORE_BUNDLE_CATALOG=NO forces the download path. ios/Encore/Config/Local.xcconfig holds machine-specific settings and stays ignored.
- Never run the Android emulator, Android Studio or Gradle daemons while Xcode builds or installs (run ./gradlew --stop first).
