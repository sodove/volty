# Port Xeno / LeaperKim diagnostics to 0.7.11

> Execute through subagent-driven development with Luna agents. Preserve all pre-existing worktrees and their dirty files. Do not commit or push.

## Goal
Port the already reviewed Xeno/LeaperKim telemetry and BLE Debug Settings work from the stale 0.7.8 checkout onto a clean 0.7.11 base, then set the next production version to 0.7.12 / versionCode 35.

## Authority
The approved behavior is in `docs/superpowers/specs/2026-09-27-xeno-leaperkim-debug-design.md` and the completed source implementation is in `C:\Users\sodovaya\git\volty`. Port existing behavior; introduce no new protocol assumptions.

## Global constraints
- Never write to Begode FFE1 or Veteran/LeaperKim command characteristics; keep these wheel links passive.
- Debug capture is explicit, off by default, memory-only, and capped at 200 raw notifications for every active BLE source.
- Preserve known/unknown flags and valid reported zero SoC. Do not invent a Xeno byte layout or hardware profile.
- Keep strings in both English and Russian resources. Do not change database schema.
- Leave the source 0.7.8 checkout and the existing 0.7.11 worktree (including its 8 dirty offline/social files) untouched. Do not copy generated/untracked directories.
- No tests, commits, or pushes. Parent will run production build and post-build checks.

> Retraction (2026-09-28): after implementation, the user explicitly authorized
> committing the complete work and merging it locally into `main`. This supersedes
> the no-commit part of the earlier implementer constraint. No push was requested.

## Task 1: Port the approved feature and bump release version
Port the scoped changes from the completed feature implementation onto this checkout's newer Settings/BLE code. Reconcile against the approved spec, adapt to current call sites, and set `appVersionName = "0.7.12"`, `appVersionCode = 35`. Preserve unrelated 0.7.11 source behavior.

### Acceptance
- Current `VeteranProtocol` has the reviewed independent field validation, speed/PWM/current/power mapping and missing/sentinel-only SoC fallback while preserving reported zero and known flags.
- `Debug` Settings tab works through current component/DI boundaries; diagnostic capture remains universal across active BLE sources, bounded to 200, and does no writes or persistence.
- English/Russian strings remain paired; only scoped files and version declarations change.
- No tests/build/commit/push are run by the implementer.

### Final integration verification (2026-09-28)
- User authorized a local commit and merge into `main`, superseding the earlier no-commit instruction above. No push was requested.
- Full `:composeApp:testDebugUnitTest --rerun-tasks`: 2,516 tests, 0 failures, 0 errors, 0 skipped.
- Updated stale `VeteranProtocolTest` expectations for controller-derived current/power and percentage-based SoC.
