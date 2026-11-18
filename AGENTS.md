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
