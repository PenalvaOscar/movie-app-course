# Upstream reference: Lumio (workshop instructor's app)

**Reference only. Not part of Encore.** Nothing here is read by the Encore build, and nothing here overrides Encore's own `AGENTS.md`, `README.md`, `DESIGN.md`, `DECISIONS.md` or `ICONS.md`.

## Source

- Repository: `firtman/agentic-mobile` (the Master.dev workshop repository this project was forked from)
- Commit: `1c0f47c` ("Refine Lumio discovery and Arcade experiences", 2026-11-18)
- Fork point of this repository: `7f80051` ("Add workshop slides and README link")
- Fetched through the GitHub API on 2026-11-18, without cloning and without adding a remote.
- The upstream repository declares no license. Rights stay with its author; this copy is for study within the workshop.

## What is included

Every file that upstream added or changed outside `android/` between `7f80051` and `1c0f47c`, at the same relative paths:

| Path | Content |
|---|---|
| `AGENTS.upstream.md` | Upstream `AGENTS.md`, renamed so agents don't load it as live rules |
| `README.md` | Upstream README at `1c0f47c` |
| `gitignore.upstream` | Upstream `.gitignore`, renamed so it doesn't change what git ignores here |
| `design/` | Lumio design system: branding decisions, icons (Lucide), colors and typography, HTML previews, trivia concept art (AI-generated, per its own README) |
| `docs/` | Lumio's Android plan and refinements |
| `validation/` | Lumio's validation report and Stage 0 screenshots (no posters) |

## What is not included

- **`android/`**: upstream's Lumio Android app. Encore's `android/` is unchanged.
- **`api/`, `data/`, `slides/`, `validation/starter.md`**: unchanged upstream since the fork point, so they're identical to this repository's copies at the root.
- **Upstream commit history**: this is a snapshot, not a merge.

To compare with a later upstream state, read `repos/firtman/agentic-mobile/compare/1c0f47c...main` through the GitHub API.
