# Estimated PWM with Per-Wheel Calibration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Wheels that report no PWM (the rider's Begode T4) get an estimated duty «~ШИМ», calibrated per wheel — by a guided calibration wizard and, while riding, by the wheel's own high-power alarm — never by a constant baked into the app.

**Why (rider, 2026-10-26/27, two BLE dumps of a T4 free-spin):**
- The T4 has no real PWM: live-frame bytes 14..15 stay `0x0080` (12.8 %) from 0 to 81 km/h; the 0x07 duty field stays 0.
- Its frame 0x04 alert byte (14) bit 0 — WheelLog's "HighPower", fed to `setWheelAlarm` — is the wheel's own PWM alarm; the settings word `0x2800` is alarm mode 2, which WheelLog's adapter labels "80% PWM only". In a slow free-spin the bit switched on at **68.4 km/h at 96.8 V** (off at 67.0, flickered on/off around 68.4–68.5), and the free-spin plateau was **81.5–81.8 km/h at 96.5 V** (first dump: 81 at 96.0). Onset / plateau ≈ 0.835.
- The rider rejected a global Kv constant: motors differ even between revisions of one model (newer T4s have a lower Kv and reach speed by **field weakening**, which breaks speed ∝ voltage near the top).
- WheelLog's `calculatePwm` = speed / (rotationSpeed/rotationVoltage × V × 0.9) ignores load; a measured free-spin constant must NOT be multiplied by 0.9 (the plateau would read 111 %).

**Repo / branch:** `C:\Users\sodovaya\git\volty-nearby`, branch `feat/vehicle-info` (contains the vehicle-info screen and the dump-share work; DB newest `25.sqm` / `databases/26.db`). Suite: `.\gradlew.bat :composeApp:testDebugUnitTest`; migrations `.\gradlew.bat :composeApp:verifyCommonMainVoltyDatabaseMigration`.

## Global Constraints
- **Rider rule (2026-10-27): calibration and the estimate exist ONLY for wheels proven not to report PWM.** "Proven" = a per-vehicle, persisted evidence state `PwmEvidence { UNKNOWN, NEVER_REPORTED, REPORTS }`: any measured duty sample that is non-zero (or that ever differs from its first value) at any time moves it to `REPORTS` permanently (one-way latch — e.g. the ET Max reads 2 % standing); it becomes `NEVER_REPORTED` only after the wheel has been seen moving (speed ≥ 10 km/h for ≥ 5 s, cumulatively) while every duty sample stayed exactly the same value (the T4: always 0). The `hasDuty` flag alone is NOT evidence: the T4 publishes `hasDuty = true` with a constant 0. Wizard entry hidden/disabled with a reason unless `NEVER_REPORTED`; the estimate never runs unless `NEVER_REPORTED`; a later `REPORTS` immediately stops the estimate and hides the wizard (calibration kept but inert).
- Read-only on every device; never write to a Begode's FFE1 (Global Constraint 5). The wizard only listens.
- No PWM constant per motor/model in code. Per-vehicle values come from the wizard, riding, or the rider's manual entry.
- An estimate is always marked as one (`~`, «оценка»); a measured duty is never replaced or mixed with it. A wheel that reports real duty never shows an estimate.
- Without a calibration, no estimate is shown (dash «—»), but the wheel's own alarm bit is still surfaced.
- Above 100 % the estimate shows «≥100 %» (field weakening); it never extrapolates a number past 100.
- The model: `pwm = (k × speedKmh + R × phaseCurrentA) / packV`, with `k` in V per km/h and `R` in ohms; `R` defaults to 0 until learned. Threshold of the wheel's alarm: per-vehicle setting, default 80 %.
- Compose not unit-testable: decisions in pure functions/components with tests. Strings en + ru. Incoherent fixtures for absent data. No unbounded `runTest` loops. Record retractions in KDoc (e.g. the BegodeProtocol KDoc that says no derivation is offered for duty, and the «Wheel alarm» label).

---

### Task 1: The PWM model and its learner (pure)
**Files:** create `domain/pwm/PwmModel.kt`, `domain/pwm/PwmCalibrationLearner.kt`; tests.
**Interfaces:**
- `data class PwmCalibration(val k: Float, val r: Float, val points: Int, val source: PwmCalibrationSource /* WIZARD, RIDING, MANUAL */, val spread: Float?)`
- `object PwmModel { fun estimatePercent(cal: PwmCalibration, speedKmh: Float, packV: Float, phaseA: Float?): Float? /* null on non-finite or V<=0; clamped at 100 with an `atCeiling` flag via a result type */ }` — return `PwmEstimate(percent: Float, atCeiling: Boolean)`.
- `data class AlarmOnset(val speedKmh: Float, val packV: Float, val phaseA: Float?, val atMillis: Long)`
- `object PwmCalibrationLearner { fun fromOnsets(onsets: List<AlarmOnset>, thresholdPercent: Float): PwmCalibration?; fun fromFreeSpin(plateauKmh: Float, packV: Float): PwmCalibration /* k = V / plateau, r = 0, treated as 100 % at the plateau */; fun merge(current: PwmCalibration?, onset: AlarmOnset, thresholdPercent: Float): PwmCalibration? }`
- Fit: each onset gives `threshold/100 × V = k × speed + R × I`. With < 3 onsets or phase-current spread < 10 A: `R = 0`, `k` = median of `threshold/100 × V / speed`. Otherwise least squares for (k, R) with R constrained to [0, 0.5] Ω; reject onsets below 10 km/h; `spread` = relative std-dev of per-onset k.
- [ ] Failing tests with the rider's measured numbers: onset 68.4 km/h @ 96.8 V, threshold 80 → k = 0.8 × 96.8 / 68.4 = 1.1322 V/(km/h) (±0.001); that calibration estimates the plateau 81.7 km/h @ 96.5 V at 95.9 % (±0.5); free-spin calibration from 81.7 @ 96.5 → k = 1.1812, estimates 81.7 @ 96.5 = 100 % and atCeiling false at exactly 100, true above; two loaded onsets with distinct currents recover a synthetic (k, R); an onset below 10 km/h is ignored; NaN inputs → null; field-weakening speed above the plateau → atCeiling.
- [ ] Implement; suite; commit `feat(pwm): per-wheel PWM model and calibration learner`.

### Task 2: The wheel's own alarm as data; persistence of the calibration
**Files:** `domain/model/ControllerData.kt` (`val wheelPwmAlarm: Boolean? = null` — null = the device has no such signal), `data/bms/BegodeProtocol.kt` (set from alert bit 0 once an 0x04 frame was seen; rename the label from «Wheel alarm» to a PWM-alarm wording, with a KDoc retraction; WHEEL_ALERTS fact unchanged in meaning), `domain/model/Vehicle.kt` (`pwmAlarmThresholdPercent: Int = 80`, `pwmManual: PwmManual? = null` where `PwmManual(freeSpinKmh: Float, atVoltage: Float)`), persistence of `PwmCalibration` per vehicle (extend `VehicleEnergyRow` or a new `VehiclePwmRow` — your call, documented), `26.sqm` + `databases/27.db`, repository mapping, tests (incl. migration verify).
- Also persist `PwmEvidence` per vehicle (same row as the calibration) and implement the pure evidence reducer `fun PwmEvidence.next(sample: ControllerData, movingMillis…): PwmEvidence` in `domain/pwm/` with tests: T4 samples (duty 0 while moving 6 s at 30 km/h) → NEVER_REPORTED; ET Max (duty 2 standing) → REPORTS; REPORTS never reverts; a standing wheel with duty 0 stays UNKNOWN; incoherent `hasDuty=false, dutyPercent=37f` → no evidence either way.
- [ ] Failing tests: Begode publishes `wheelPwmAlarm = true` exactly while bit 0 is set (frames from the rider's dump — build them from the hex in the plan's Why section: the 0x04 frame `55 AA 00 2A DC AD 28 00 1C 20 00 C8 00 00 01 00 00 12 04 18 5A 5A 5A 5A` has bit 0 set), null before any 0x04 frame; other protocols leave it null; calibration round-trips; vehicle fields round-trip with defaults for old rows.
- [ ] Implement; suite + migration verify; commit `feat(pwm): the wheel's PWM alarm as data; calibration persisted per vehicle`.

### Task 3: The estimate in the live pipeline, and learning while riding
**Files:** create `domain/pwm/PwmEstimator.kt` (stateful per connection, like `VehicleEnergyTracker`), wire into `VehicleConnection.publish` after the energy tracker (once per published snapshot), `ControllerData` (`val dutySource: DutySource = MEASURED` with `MEASURED, ESTIMATED`), `KableBmsRepository` (load/save calibration like the energy memory: load when connecting, save on change and on disconnect; reuse the energy pattern's ownership/failure rules), tests.
Rules: only when the vehicle's `PwmEvidence == NEVER_REPORTED` (fed live from motion samples here, persisted; the `hasDuty` flag alone is not the condition — the T4 reports a constant 0 with `hasDuty = true`); estimate needs a calibration (wizard/riding/manual — manual converts with `fromFreeSpin`), speed known, pack voltage known (aggregate battery voltage or controller input voltage); publish `dutyPercent = estimate`, `hasDuty = true`, `dutySource = ESTIMATED` (so the existing duty alarms/dials work), clamped at 100 with the ceiling flag carried for display. Learning: on each rising edge of `wheelPwmAlarm` with speed ≥ 10 km/h, `merge` the onset and persist. Disconnect / vehicle switch resets edge state.
- [ ] Failing tests: T4-shaped fixture (no duty, alarm bit) without calibration → `hasDuty=false`; with the measured calibration at 60 km/h @ 96 V → ESTIMATED ~70.6 %; a wheel with real duty (ET Max) → MEASURED untouched even with a calibration present; a rising edge teaches; a held bit does not re-teach every frame; incoherent `hasDuty=false, dutyPercent=37f` never leaks 37.
- [ ] Implement; suite; commit `feat(pwm): estimated duty in the pipeline, learned from the wheel's alarm`.

### Task 4: The calibration wizard and settings
**Files:** create `presentation/vehicle/pwm/PwmCalibrationComponent.kt` + `PwmCalibrationScreen.kt`; vehicle editor entry («Калибровка ШИМ») and fields (alarm threshold %, manual free-spin km/h at V); RootComponent config; strings en+ru.
Entry visible only when `PwmEvidence == NEVER_REPORTED`; otherwise the editor row shows the reason («колесо отдаёт свой ШИМ» / «ещё не известно: проедь немного»). Flow (component state machine, pure and tested): INTRO («подними колесо…») → THRESHOLD (live speed/V; captures the first rising edge of `wheelPwmAlarm`; for wheels with `wheelPwmAlarm == null` this step is skipped) → PLATEAU (optional: records the max sustained speed over ≥ 2 s with |Δspeed| < 1 km/h) → repeat up to 3 runs → RESULT (median k; if both threshold and plateau exist, shows onset/plateau ratio and warns when outside 0.75–0.92) → Save / Cancel. Saving writes `PwmCalibration(source = WIZARD)`.
- [ ] Failing tests driving the state machine with the rider's dump numbers (threshold 68.4@96.8, plateau 81.7@96.5 → ratio 0.837, no warning); a wheel without the alarm signal goes INTRO → PLATEAU; cancel writes nothing; disconnect mid-run returns to INTRO with a reason.
- [ ] Implement; suite; commit `feat(pwm): calibration wizard`.

### Task 5: Surfaces
**Files:** dashboards (Light/Clean/Classic duty readouts show «~» for ESTIMATED and «≥100» at ceiling), alert screen (duty rule available for an estimated duty with a caption «оценка, под нагрузкой занижает»), alert banner (the wheel's own `wheelPwmAlarm` as a row «ШИМ ≥ порога (колесо)»), «Всё о ТС» rows (k, R, points, spread, source, threshold, wheel alarm state, estimate), strings en+ru.
- «Всё о ТС» also shows the PwmEvidence state with its reason.
- [ ] Failing tests in mappers/components; implement; suite; commit `feat(pwm): estimated PWM on the dashboards, alerts and info screen`.
