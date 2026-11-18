# iOS Stage 0 verification

Date: 2026-11-18

Scope: repo prep for the iOS app (docs/IOS-PLAN.md, Stage 0). No app code, no Swift package, no Xcode project.

## What changed

| File | Change |
|---|---|
| `.gitignore` | iOS block: `ios/**/.build/`, `ios/**/DerivedData/`, `xcuserdata/`, `*.xcuserstate`, `ios/Encore/Config/Local.xcconfig`, `ios/**/catalog.json` with `!ios/EncoreCore/Tests/Fixtures/**` re-included |
| `AGENTS.md` | New "iOS" section: layout, SwiftPM/xcodebuild instead of Gradle, `test-core.sh`, physical-device workflow, catalog bundling path, the no-Android-during-Xcode rule. Product rules stay one shared list |
| `DECISIONS.md` | New §6 iOS (C1–C20, A-1–A-12, I1–I11 with status); old §6 change log renumbered to §7 |
| `docs/IOS-PLAN.md` | Status line: draft → approved by the owner |
| `ios/scripts/test-core.sh` | Wraps `swift test` for `ios/EncoreCore` |

## How it works under the hood

- **Ignore rules come first.** Git ignores paths by pattern at `git add` time. The patterns exist before any generated file does, so a build cannot accidentally stage `.build/` output or a copied 10 MB catalog. The `!…/Fixtures/**` negation works because the parent directory `Fixtures/` itself is not ignored; only `catalog.json` files are. Git can re-include a file only when its parent directory is not excluded.
- **`test-core.sh` picks its mode from `xcode-select -p`.**
  - With the Command Line Tools selected, `Testing.framework` lives in `…/CommandLineTools/Library/Developer/Frameworks`. That is not on the compiler's default framework search path, so the script passes `-F` to both the compiler and the linker.
  - It also adds two runtime search paths (`rpath`), because the test bundle loads `Testing.framework` and `lib_TestingInterop.dylib` dynamically at launch. Without the second one the bundle crashes at load.
  - With full Xcode selected, those paths are already configured, so the script runs plain `swift test`.
  - Extra arguments pass through (e.g. `--filter`). `exec` hands the process over to `swift test`, so its exit code is the script's exit code.

## Toolchain (this Mac, 2026-11-18)

| Item | Value |
|---|---|
| macOS | 26.5.2 (25F84), arm64, 8 GB RAM |
| Free disk on `/` | 51 GiB |
| Developer dir | `/Library/Developer/CommandLineTools` (no Xcode in `/Applications`; `xcodebuild` not found) |
| Swift | 6.3.3 (swiftlang-6.3.3.1.3), target arm64-apple-macosx26.0 |
| Swift Testing | `Testing.framework` + `_Testing_*` overlays in the CLT; `lib_TestingInterop.dylib` in `…/usr/lib` |
| SQLite | 3.51.0 (CLI); `import SQLite3` links |

## Commands run and actual results

| Command | Result |
|---|---|
| `git check-ignore --no-index` on 8 sample paths | `.build/`, `DerivedData/`, `xcuserdata`, `.xcuserstate`, `Local.xcconfig`, `ios/Encore/Resources/catalog.json` → ignored. `ios/EncoreCore/Tests/Fixtures/catalog.json` and `ios/Encore/Config/Shared.xcconfig` → tracked |
| `bash -n ios/scripts/test-core.sh` | Syntax OK |
| `ios/scripts/test-core.sh` (no package yet) | Exit 2, "no Swift package at ios/EncoreCore (created in Stage 1)" |
| `test-core.sh` copied next to a throwaway probe package (tools 6.0, Swift 6 mode, macOS 14 / iOS 17, links `sqlite3`; one `@Observable` test, one `sqlite3_libversion` test) | 2 tests passed. `--filter sqliteLinks` ran 1 test, passed. The probe was in a temp dir outside the repo and was deleted afterwards |

## Limitations

- The script's Xcode branch (plain `swift test`) is untested: Xcode is not installed. It is first exercised in Stage 4.
- No iPhone work in this stage. Q2 (iPhone model and iOS version) is still needed before Stage 4.
- Open plan questions not decided by this stage: Q1 bundle id, Q2 iPhone, Q4 debug catalog source, Q5 locales from Stage 4, Q6 universal vs iPhone-only, Q7 games logic ahead of Android, Q8 screenshots, Q11 commit prefix. Q11 was deliberately left out of `AGENTS.md` until answered.
- Nothing committed. `AGENTS.md` is tracked config, so the commit waits for explicit approval.
