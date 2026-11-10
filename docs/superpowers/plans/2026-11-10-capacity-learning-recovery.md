# Capacity learning recovery implementation plan

> Use Superpowers subagent-driven development for the isolated learner and its review.

## Behavior

- Keep the established capacity after one or two divergent valid measurements. Three consecutive eligible estimates outside the existing 40% band replace it when their maximum is at most 1.15 times their minimum. Use the existing drop-weighted average. An agreeing measurement clears the alternate series. An incompatible alternate starts a new series.
- The normal validity rules remain. A measurement uses two resting points, at least 20 percentage points of voltage SoC drop, positive net discharge and at most 5% missing telemetry. A still-growing segment retains its anchor. Each held divergent estimate advances the anchor so it cannot count twice.
- Persist the alternate series and latest decision with the existing energy row. Preserve old capacity, counter and resistance during the 29-to-30 migration. Reset clears the diagnostics and alternate series as well.
- The Energy editor shows the latest decision, including waiting for additional drop, rejected telemetry, a divergent estimate awaiting confirmation, or recovery. It uses live data for the currently connected vehicle and stored data offline.
- Entered capacity retains priority. Preserve the four-worker and four-processor limits.

## Tasks

- [x] Implement and test the pure learner decisions and recovery rule.
- [x] Carry decisions through the tracker, persistence and Energy editor.
- [x] Verify the upgrade, tracker behavior and complete Android test suite.

## Verification

The upgrade test seeds a real schema-29 snapshot and checks the old estimate, resistance and charge counter after migration. A tracker test feeds three separate rest-to-rest connections and carries the pending evidence between them. The editor component tests that live decisions belong to the edited vehicle and that offline mode uses the stored reason. The final Android unit-test run reported 6,608 tests with no failures and two skips. SQLDelight migration verification passed. The Compose layout still needs device verification.
