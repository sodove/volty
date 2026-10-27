# Ride Fixes after 0.7.54 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Fix what the rider found on 0.7.54 (2026-10-27): the estimated PWM and the Begode power are missing from ride history; «Всё о ТС» shows the trip distance as a range and raw tokens; the calibration wizard lets the alarms fire and pile up in the banner; and the banner's linger should differ by kind and severity.

**Repo / branch:** `C:\Users\sodovaya\git\volty-nearby`, new branch `fix/ride-fixes` from `main` @22925e94 (0.7.54). Suite: `.\gradlew.bat :composeApp:testDebugUnitTest`.

## Global Constraints
- Read-only on every device; never write to a Begode's FFE1 (Global Constraint 5).
- Compose not unit-testable: decisions in pure functions/components with tests; composables render.
- Strings en + ru; no Android backslash escapes; Russian natural, lower-case choice values.
- History records what was measured; an estimate recorded into history is marked as one wherever the history model allows (see Task 1).
- Incoherent fixtures for absent data; no unbounded `runTest` loops; record retractions in KDoc.

---

### Task 1: Ride history gets the Begode power and the estimated PWM
**Why:** `data/bms/BegodeTelemetryObservation.kt` records `CONTROLLER_POWER` as unknown (`PARTIAL_SOURCE`) on both 0x00 and 0x07 frames — "a receipt-aware join belongs to the source runtime" — and that join was never built. Duty in history comes from the per-link protocol observation (the raw constant 0 on a T4); the estimate is computed later, on the vehicle-level snapshot, and never reaches history.
**Files:** `data/bms/BegodeTelemetryObservation.kt` (+ the Begode protocol state it reads), `data/ble/HistoryTelemetryIngress.kt` (an enrichment hook like the existing `withHistoryEstimatedSoc`), the PWM estimator/repository side that holds the latest estimate, history metric registry if a flag is needed; tests.
- Power: on the 0x07 frame (battery current refreshed) record `CONTROLLER_POWER = V × I` when a live-frame voltage was decoded within the last 1 000 ms of wheel frames (the protocol's own receipt state); otherwise keep the honest unknown. Document the join and its staleness bound; retraction note on the old comment.
- Estimated duty: when the vehicle's PwmEvidence is NEVER_REPORTED and the live estimator has an estimate, the history observation's DUTY reading is replaced by the estimate (an enrichment provider read at observation time; no estimate → the reading is recorded as unknown, never the raw constant 0). If the history model can carry an "estimated" marker per reading without a schema migration, mark it; if it cannot, record a KDoc note and leave the marker for later — do NOT add a migration in this task.
- [ ] Failing tests: a T4-shaped frame sequence (0x00 then 0x07) records power = V × I; a 0x07 with no recent 0x00 records power unknown; NEVER_REPORTED + estimate → DUTY = estimate; NEVER_REPORTED without estimate → DUTY unknown (not 0); a wheel with real duty → raw duty unchanged.
- [ ] Implement; suite; commit `fix(history): Begode power joined across frames; the estimated PWM is recorded`.

### Task 2: «Всё о ТС» corrections
**Files:** `presentation/vehicle/info/VehicleInfoModel.kt`, `VehicleInfoText.kt`, strings.
- The RANGE section labels `tripKm` as «Range at trip consumption» — it is the trip distance. Show: trip distance (labelled as such), and the range in km (from the range estimate) — the row «Range estimate: estimated» must carry the km value, or «—» with the unearned reason.
- Device facts: boolean values (`true`/`false`) and enum tokens (`branch_ratio`, `cells`, `none`) render as localized words via the info enums (no raw tokens on screen); the second LIVE_PWM_RAW row (the ×0.1 % form) gets its own label «… (%)».
- [ ] Failing tests on the model/text mapping; implement; suite; commit `fix(info): range rows, localized fact values, PWM raw labels`.

### Task 3: The calibration wizard silences duty/speed alarms
**Files:** the PWM wizard component, `domain/alert/AlarmDriver.kt` / `AlarmController` path, `AlertEngine`, `ActiveAlertTracker` / banner; a small shared "calibration in progress" signal (e.g. a StateFlow on BmsRepository or a dedicated holder in Koin).
- While the wizard is between INTRO-started and RESULT/Cancel/BLOCKED (i.e. the rider is spinning the lifted wheel), duty and speed alarms (sound), their notifications, the banner rows for duty/speed and the wheel's own PWM-alarm row are suppressed; temperature, battery and fault alerts are not. Leaving the wizard (any exit, incl. process death/back) clears the signal; a stuck signal must be impossible (time-box it, e.g. auto-clear after 3 minutes, documented).
- [ ] Failing tests: during calibration no duty/speed alarm level, no notification, no banner row; after exit they work; temperature alert still fires during calibration; the time-box clears a stuck signal.
- [ ] Implement; suite; commit `feat(pwm): the calibration wizard silences duty and speed alarms`.

### Task 4: Banner linger per kind and severity
**Files:** `data/prefs/AppPrefs.kt` (three prefs: faults, critical alerts, warning alerts; keep the existing key as the faults pref so its value carries over), settings screen/component (three choice rows using the existing linger options), `presentation/ride/RideDashboardComponent.kt` / `FaultEpisodes.kt` (pick the linger by row kind and the episode's worst severity), strings.
- Defaults: faults 60 s (existing value carries over), critical 30 s, warnings 10 s. A row escalated warning→critical within an episode uses the critical linger. The 2 s minimum hold at linger 0 stays.
- [ ] Failing tests: a warning duty row disappears 10 s after recovery while a fault stays 60 s; an escalated row uses 30 s; changing a pref re-renders with the new value; old installs keep their faults value.
- [ ] Implement; suite; commit `feat(ride): banner linger per kind and severity`.
