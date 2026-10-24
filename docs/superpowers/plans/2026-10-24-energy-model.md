# Energy Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A state of charge that moves only as charge leaves the pack (no 60 → 15 % on a hard pull), an entered-or-learned pack capacity, and a "~42 км" range estimate on all three Ride dashboards.

**Architecture:** Five pure domain units (OCV curve, resistance fit, charge tracker, capacity learner, range estimator) under `domain/energy/`, composed by one stateful `VehicleEnergyTracker` that `VehicleConnection` runs over every vehicle snapshot before it is published — so `activeData`, the alert engine, the dashboards and navigation all read the same smoothed aggregate. Entered capacity lives on `Vehicle`; learned state in a new `VehicleEnergyRow` table.

**Tech Stack:** Kotlin Multiplatform (Android only), Compose Multiplatform, Decompose, Koin, SQLDelight, kotlin.test + Turbine.

**Spec:** `docs/superpowers/specs/2026-10-24-energy-model-design.md` (read it; its "Decisions after the builder check" section is binding).

**Repo / branch:** `C:\Users\sodovaya\git\volty-nearby`, a new branch `feat/energy-model` cut from `main` (@a0c0f045 or later). Suite: `.\gradlew.bat :composeApp:testDebugUnitTest`. Migrations: `.\gradlew.bat :composeApp:verifyCommonMainVoltyDatabaseMigration`.

## Global Constraints

- **Never write to a Begode's FFE1** (CLAUDE.md Global Constraint 5). This whole plan is read-only on every BLE link; no task adds a write.
- A pack whose `BmsType.reportsStateOfCharge` is true keeps its own SoC untouched; `withEstimatedSoc`'s identity guard stays.
- Remaining energy = SoC × usable Ah × **nominal** pack voltage (series cells × `Chemistry.nominalCellV`). Never × live voltage.
- Entered capacity is **nameplate**; usable Ah = nameplate Ah × `OcvCurve.usableFraction(chemistry, lowV, highV)`.
- Changing series cell count or chemistry resets learned capacity and resistance; changing the cell bounds does not.
- No range shown without a capacity (entered, BMS-reported or learned) and before it is earned.
- Compose is not unit-testable here: every decision is a pure function or component method with tests; `@Composable` code only renders.
- Fixtures for absent data are built deliberately incoherent (e.g. `hasBatteryCurrent = false, batteryCurrentA = 80f`).
- No `runTest` with an unbounded delayed loop.
- Strings in both `composeResources/values/strings.xml` and `values-ru/strings.xml`; no Android backslash escapes.
- Record retractions in KDoc ("an earlier revision claimed X; wrong because Y"), never silently rewrite.
- `N.sqm` migrates `N.db` to `(N+1).db`. The newest migration is `23.sqm`, the newest snapshot `24.db`, so this plan adds `24.sqm` and `25.db`.
- Each task: run the full suite before committing; every new test is shown failing first.

## Review Focus

1. **Reconnect mid-ride with the wheel under load** — the seed must not be taken from a sagged voltage; expect the persisted counter (if within tolerance of the load-compensated seed) or the compensated seed. Test: Task 3 `seedUnderLoadUsesCompensatedVoltage`.
2. **Battery current that stops arriving mid-ride** (0x07 frames cease, `hasBatteryCurrent` flips false) — expect no integration of the stale last value and fallback to the voltage path, not a frozen SoC. Test: Task 6 `aCurrentThatStopsIsNotIntegrated`.
3. **Regen down a long hill** — SoC may rise by the integrated regen, but never above 100 % and never by a rate-limit violation. Test: Task 3 `regenRaisesChargeButClampsAtFull`.
4. **A vehicle switched while connected / reconnect to a different vehicle** — learned state and counter belong to one vehicle id; no leakage. Test: Task 6 `stateIsPerVehicleId`.
5. **Clock jumps / out-of-order timestamps** (negative dt) — expect the sample to be skipped, never a negative integration step. Test: Task 3 `nonPositiveDtIsSkipped`.

---

### Task 1: OCV curve and the non-linear voltage SoC

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/energy/OcvCurve.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/VoltageSocEstimator.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/energy/OcvCurveTest.kt`, existing `VoltageSocEstimatorTest`

**Interfaces:**
- Produces:
  - `object OcvCurve { fun fraction(chemistry: Chemistry, cellV: Float): Float /* 0..1 of nameplate */; fun stretchedSocPercent(chemistry: Chemistry, cellV: Float, lowV: Float, highV: Float): Float /* 0..100, exact 0 at lowV, 100 at highV */; fun cellVoltageAt(chemistry: Chemistry, socPercent: Float, lowV: Float, highV: Float): Float /* inverse of stretchedSocPercent */; fun usableFraction(chemistry: Chemistry, lowV: Float, highV: Float): Float }`
  - `VoltageSocEstimator.boundsFor(chemistry: Chemistry, alert: AlertConfig): Pair<Float, Float>` (low, high) — the one place bounds are resolved.

Tables (cell resting voltage at SoC 0,10,…,100 %), piecewise-linear between points:
- `LI_ION_NMC`: 3.00, 3.45, 3.55, 3.62, 3.68, 3.74, 3.82, 3.90, 3.98, 4.08, 4.20
- `LIFEPO4`: 2.50, 3.00, 3.20, 3.25, 3.27, 3.28, 3.29, 3.30, 3.32, 3.35, 3.65
- `LEAD_ACID`: linear between `emptyCellV` and `defaultHighV` (no table).
Below the first point → 0; above the last → 1. Cite the sources used to sanity-check the tables in the KDoc.

- [ ] **Step 1: Write failing tests** — `boundsAreExactlyZeroAndHundred` (Li-ion, low 3.30, high 4.20 → 0f and 100f within 1e-4), `midVoltageIsNotLinear` (Li-ion 3.75 V stretched between 3.30/4.20 is > 50 + 3, i.e. not the linear 50 %), `monotoneAcrossRange` (every chemistry, 200 steps), `usableFractionLiIonDefaults` (3.30..4.20 → `1 - fraction(3.30)` ≈ 0.947 ± 0.005), `inverseRoundTrips` (`cellVoltageAt(stretched(v)) == v` ± 1 mV for 10 voltages), `degenerateBoundsGiveZero` (high ≤ low → 0f, no NaN).
- [ ] **Step 2: Run — expect compile failure / FAIL.** `.\gradlew.bat :composeApp:testDebugUnitTest --tests "*OcvCurveTest*"`
- [ ] **Step 3: Implement `OcvCurve`; route `VoltageSocEstimator.estimateFromAverageCellVoltage` through `OcvCurve.stretchedSocPercent`.** Add a marked retraction to the class KDoc: the "linear follows the rider's mental model" claim held for the endpoints only; the shape between them was wrong (T4 field report 2026-10-24). Update any existing `VoltageSocEstimatorTest` expectations that asserted linear midpoints, and say in the commit which ones and why.
- [ ] **Step 4: Full suite passes.**
- [ ] **Step 5: Commit** `feat(energy): non-linear OCV curve for the voltage SoC`

### Task 2: Pack resistance fit

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/energy/PackResistanceEstimator.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/energy/PackResistanceEstimatorTest.kt`

**Interfaces:**
- Produces: `class PackResistanceEstimator(initialOhm: Float? = null) { fun feed(packV: Float, dischargeA: Float, atMillis: Long); val ohm: Float? }`, constants `MIN_STEP_A = 10f`, `MAX_STEP_WINDOW_MS = 1_000L`, `MIN_OHM = 0.005f`, `MAX_OHM = 2.0f`, `WINDOW = 31`.

Method: compare each sample with the previous one; if `|ΔI| ≥ MIN_STEP_A` and `0 < Δt ≤ MAX_STEP_WINDOW_MS`, candidate `R = −ΔV/ΔI`; reject outside `[MIN_OHM, MAX_OHM]`; `ohm` = median of the last `WINDOW` accepted candidates (seeded with `initialOhm` as one candidate when given); null until one is accepted.

- [ ] **Step 1: Failing tests** — `recoversKnownResistance` (synthetic pack 100 V OCV, R = 0.08 Ω, current steps 0→60→120→30 A with ±0.2 V noise → ohm within 0.08 ± 0.01), `smallStepsAreIgnored` (ΔI 5 A only → null), `slowStepsAreIgnored` (Δt 1500 ms → null), `implausibleCandidatesRejected` (ΔV of the wrong sign → null), `oneOutlierDoesNotMoveMedian`, `initialValueSurvivesUntilEvidence`.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Suite passes.**
- [ ] **Step 5: Commit** `feat(energy): online pack resistance fit`

### Task 3: The charge tracker

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/energy/ChargeTracker.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/energy/ChargeTrackerTest.kt`

**Interfaces:**
- Consumes: `OcvCurve` (Task 1).
- Produces:
  - `data class ChargeInput(val atMillis: Long, val packV: Float, val cellCount: Int, val dischargeA: Float? /* null = not measured */, val resistanceOhm: Float?)`
  - `data class ChargeConfig(val chemistry: Chemistry, val lowV: Float, val highV: Float, val usableAh: Float? /* null = no capacity */)`
  - `class ChargeTracker(config: ChargeConfig, persisted: PersistedCharge? = null) { fun update(input: ChargeInput): Float? /* displayed SoC %, null until seeded */; val counterPercent: Float?; val integratedAh: Double /* since construction, discharge-positive */; val gapMillis: Long /* total skipped time */; val restVoltageSocPercent: Float? /* compensated-voltage SoC while at rest, else null — the learner's endpoints */ }`
  - `data class PersistedCharge(val percent: Float, val atMillis: Long)`

Pinned values (constants in the file, each with a one-line reason):
- `GAP_SKIP_MS = 2_000` (dt ≥ this is not integrated, added to `gapMillis`), `RESEED_GAP_MS = 30_000` (dt > this re-seeds).
- Seed: needs `SEED_STABLE_MS = 3_000` of samples (≥ 3) whose compensated pack voltage spread ≤ 1 %; seed value = `stretchedSocPercent` of compensated cell voltage (`(packV + dischargeA·R) / cellCount`, R = 0 when null). Use `persisted.percent` instead when `|persisted − seed| ≤ SEED_TOLERANCE_PCT = 10`.
- Rest: `|dischargeA| < REST_A = 1.5` continuously for `REST_MS = 5_000`.
- Correction towards compensated-voltage SoC per step: `error × dt / τ`, τ = `MOTION_TAU_S = 1_200` (×3 for `LIFEPO4`), at rest `REST_TAU_S = 60`.
- Display: follows the counter but moves at most `MAX_DOWN_PCT_PER_S = 0.5` down and `MAX_UP_PCT_PER_S = 0.2` up; exception: at rest with cell voltage ≥ `highV − 0.01` it snaps to 100.
- No capacity (`usableAh == null`) or `dischargeA == null`: displayed = compensated-voltage SoC through a first-order low-pass with `NO_COUNTER_TAU_S = 20`, same rate limits.
- Counter step: `percent −= dischargeA × dt_h / usableAh × 100`, clamped 0..100.

- [ ] **Step 1: Failing tests** (each asserts on the returned value, not on timing):
  - `hardPullDoesNotCrash` — seeded at 60 %, usableAh 30, 24S Li-ion, R 0.1 Ω; 10 s at 200 A with packV sagged by 20 V → displayed ≥ 58.0 and ≤ 60.
  - `steadyRideDecreasesSteadily` — 30 min at 20 A, no stops → monotone non-increasing, total drop = 20·0.5/30·100 ≈ 33.3 ± 3 %.
  - `voltageCorrectsSlowlyInMotion` — counter 10 % above truth, 10 min riding → error reduced but by < 50 %.
  - `restCorrectsFaster` — same error, 3 min at rest → error < 10 % of original.
  - `snapsToFullAtRest` — rest at 4.20 V/cell, counter 92 → 100.
  - `regenRaisesChargeButClampsAtFull` — negative dischargeA at 99 % → ≤ 100, rise ≤ 0.2 %/s.
  - `gapIsNotIntegrated` — a 5 s dt at 100 A changes nothing; `gapMillis` += 5000.
  - `longGapReseeds` — 60 s dt, then stable voltage → reseeded from voltage.
  - `seedUnderLoadUsesCompensatedVoltage` — first samples at 80 A with sag, R known → seed equals at-rest truth ± 2 %.
  - `persistedWithinToleranceWins` / `persistedOutsideToleranceLoses`.
  - `noCapacityFallsBackToFilteredVoltage` — `usableAh = null`, a 200 A pull for 2 s moves displayed < 5 %.
  - `unmeasuredCurrentFallsBack` — `dischargeA = null` with incoherent input `packV` sag: same as above.
  - `nonPositiveDtIsSkipped` — timestamps going backwards → no change, no exception.
  - `nullUntilSeeded` — fewer than 3 samples → null.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Suite passes. Mutant check:** set `MOTION_TAU_S` to 1 and confirm `hardPullDoesNotCrash` fails; revert. Record in the report.
- [ ] **Step 5: Commit** `feat(energy): coulomb-counting charge tracker with voltage correction`

### Task 4: Capacity learner

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/energy/CapacityLearner.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/energy/CapacityLearnerTest.kt`

**Interfaces:**
- Produces:
  - `data class LearnedCapacity(val usableAh: Float, val weight: Float, val rides: Int)`
  - `data class LearnSegment(val restSocStart: Float, val restSocEnd: Float, val dischargedAh: Double, val durationMillis: Long, val gapMillis: Long)`
  - `object CapacityLearner { const val MIN_DROP_PCT = 20f; const val MAX_GAP_FRACTION = 0.05f; fun accept(segment: LearnSegment): Float? /* usable Ah or null */; fun merge(current: LearnedCapacity?, segment: LearnSegment): LearnedCapacity? /* unchanged current when rejected */ }`

Merge: weight of a segment = its SoC drop in percent; result = weighted mean; `rides` += 1. A segment whose estimate differs from `current.usableAh` by more than 40 % when `current.rides ≥ 2` is rejected (one bad ride must not swing it).

- [ ] **Step 1: Failing tests** — `acceptsLargeDrop` (60→20 %, 12 Ah → 30 Ah), `rejectsSmallDrop` (60→45), `rejectsGappyRide` (gap 10 % of duration), `rejectsRiseOrZeroAh`, `weightedMerge` (30 Ah weight 40 + 34 Ah weight 20 → 31.33), `outlierRejectedOnceEstablished`, `firstLearnIsTakenEvenIfFar`.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Suite passes.**
- [ ] **Step 5: Commit** `feat(energy): learn usable capacity from rest-to-rest rides`

### Task 5: Persistence — entered capacity and learned state

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Vehicle.kt` (add `val batteryCapacity: BatteryCapacity? = null`)
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/BatteryCapacity.kt` — `data class BatteryCapacity(val value: Float, val unit: CapacityUnit)`, `enum class CapacityUnit { WH, AH }`, `fun BatteryCapacity.nameplateAh(seriesCells: Int, chemistry: Chemistry): Float?` (WH → value / (seriesCells × nominalCellV); null when seriesCells ≤ 0)
- Modify: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/VehicleRow.sq` (columns `capacityValue REAL`, `capacityUnit TEXT`)
- Create: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/VehicleEnergyRow.sq` — table `VehicleEnergyRow(vehicleId TEXT PRIMARY KEY, seriesCells INTEGER NOT NULL, chemistry TEXT NOT NULL, learnedUsableAh REAL, learnWeight REAL NOT NULL DEFAULT 0, learnRides INTEGER NOT NULL DEFAULT 0, resistanceOhm REAL, counterPercent REAL, counterAtMillis INTEGER)` with select/upsert/delete queries
- Create: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/24.sqm` and snapshot `composeApp/src/commonMain/sqldelight/databases/25.db` (generate the same way `24.db` was in commit 4c172d56)
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/energy/VehicleEnergyStore.kt` (interface) and `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleEnergyStore.kt`
- Modify: `SqlDelightVehicleRepository.kt` (read/write capacity), Koin module binding the store
- Test: `SqlDelightVehicleEnergyStoreTest.kt`, extend `SqlDelightVehicleRepository*Test`

**Interfaces:**
- Produces: `data class EnergyMemory(val learned: LearnedCapacity?, val resistanceOhm: Float?, val counter: PersistedCharge?)`; `interface VehicleEnergyStore { suspend fun load(vehicleId: String, seriesCells: Int, chemistry: Chemistry): EnergyMemory /* empty memory, and the row deleted, when stored seriesCells/chemistry differ */; suspend fun save(vehicleId: String, seriesCells: Int, chemistry: Chemistry, memory: EnergyMemory); suspend fun reset(vehicleId: String) }`

- [ ] **Step 1: Failing tests** — capacity round-trips (WH and AH, and null); `nameplateAh` for 24S Li-ion 1800 Wh → 20.27 Ah; energy memory round-trips; `cellCountChangeForgetsLearned`; `chemistryChangeForgetsLearned`; `boundsAreNotPartOfTheKey` (no such parameter exists — assert by saving, then loading with the same cells/chemistry after an `AlertConfig` change in the vehicle repo); `resetDeletes`.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement schema, migration, snapshot, store, repo mapping.**
- [ ] **Step 4: Suite passes and `.\gradlew.bat :composeApp:verifyCommonMainVoltyDatabaseMigration` passes.**
- [ ] **Step 5: Commit** `feat(energy): persist entered capacity and learned energy state`

### Task 6: Wire the tracker into the vehicle snapshot

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/energy/VehicleEnergyTracker.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/VehicleData.kt` (add `val energy: EnergyState? = null`)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/VehicleConnection.kt` (optional `energy: VehicleEnergyTracker?` constructor param; `emit()`/`submit()`/`submitMotion()` return and publish `energy?.apply(snapshot(), clock()) ?: snapshot()`)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt` (`buildOrchestrator` constructs the tracker for the vehicle; saves memory through `VehicleEnergyStore` on disconnect and at most every 30 s; store injected via constructor default `null` so existing tests compile)
- Test: `VehicleEnergyTrackerTest.kt`, a `VehicleConnection` test, a `KableBmsRepositoryDumbBegodeTest` case

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces:
  - `enum class CapacitySource { ENTERED, REPORTED, LEARNED, NONE }`
  - `data class EnergyState(val socPercent: Float?, val usableAh: Float?, val nominalPackV: Float?, val remainingWh: Float?, val capacitySource: CapacitySource, val learned: LearnedCapacity?, val resistanceOhm: Float?, val modelled: Boolean /* true when the tracker owns the SoC */)`
  - `class VehicleEnergyTracker(vehicle: Vehicle, memory: EnergyMemory) { fun apply(snapshot: VehicleData, now: Instant): VehicleData; fun memory(): EnergyMemory }`

Rules (from the spec, binding):
- Model owns SoC only when **no** online pack has `reportsStateOfCharge`. Then: aggregate `soc` = tracker's displayed SoC, `socKnown` = it is non-null; every no-SoC pack's `data.soc` likewise. All-reporting vehicles: SoC untouched, `energy` still filled (REPORTED capacity from the aggregate's `capacity`, remaining Wh from its SoC × capacity × nominal V). Mixed vehicles: model off for SoC (reporting packs' SoC stands), `energy.modelled = false`, range from the reporting packs' capacity only — record this ruling in the KDoc.
- Discharge current precedence: `snapshot.motion.isConnected && hasBatteryCurrent` → `motion.batteryCurrentA` (verify its sign against `BegodeProtocol` line ~767 `powerW = voltage × controllerBatteryCurrent`: discharge-positive); else sum of `−data.current` over online packs with `hasCurrent` (PARALLEL) or the first such pack (SERIES); else null.
- Series cells: vehicle pack `cellCount`, else the controller's typed `derivedCellCount`, else the cell list length; none → tracker not built (SoC passes through).
- Capacity: entered → `nameplateAh × usableFraction`; else learned `usableAh`; else null.
- Resistance: feed a `PackResistanceEstimator` (seeded from memory) with aggregate voltage and the chosen discharge current every sample; pass its `ohm` into `ChargeInput.resistanceOhm`.
- Inputs that are not evidence: an aggregate that is not `isConnected`, or whose voltage is ≤ 0 (the dumb-Begode "unscaled" sentinel), is passed through untouched and not fed to the tracker.
- Learn segments: the tracker opens a segment at the first rest after seed and closes it at each later rest, feeding `CapacityLearner.merge` with the segment since the opening rest.

- [ ] **Step 1: Failing tests** — `aT4HardPullKeepsTheAggregateSteady` (dumb-Begode fixture: a no-SoC pack + motion with battery current; 200 A pull → aggregate soc moves < 2 %); `alertsSeeTheSmoothedSoc` (`activeData.soc` equals the tracker's value); `aSmartBmsSocPassesThrough` (ANT-type pack soc 73 stays 73 during the same pull); `aCurrentThatStopsIsNotIntegrated` (incoherent fixture `hasBatteryCurrent = false, batteryCurrentA = 80f` → no integration); `stateIsPerVehicleId`; `memoryIsSavedOnDisconnect`; `enteredCapacityBeatsLearned`.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Suite passes. Mutant check:** make `apply` return the snapshot unchanged; `aT4HardPullKeepsTheAggregateSteady` and `alertsSeeTheSmoothedSoc` must fail; revert.
- [ ] **Step 5: Commit** `feat(energy): the vehicle snapshot carries the modelled charge`

### Task 7: Range estimate, and navigation on the same energy

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/energy/RangeEstimator.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/energy/RangeSource.kt` (StateFlow over `activeVehicleData` + `motionSamples(RideEnergy.SESSION_WINDOW)`, reusing `BmsNavigationEnergySource`'s consumption evidence — extract that function to a shared place rather than copy it)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/ArrivalEnergyEstimator.kt` (energy from `vehicleData.energy.remainingWh` / `usableAh × nominalPackV`; `CAPACITY_UNEARNED` when `energy?.usableAh == null`)
- Koin: bind `RangeSource`
- Test: `RangeEstimatorTest.kt`, update `ArrivalEnergyEstimatorTest`

**Interfaces:**
- Produces: `data class RangeEstimate(val km: Float)`; `object RangeEstimator { const val MIN_KM = 1.0; const val MIN_SAMPLES = 20; const val RECENT_KM = 3.0; fun blendedWhPerKm(tripWhPerKm: Double, tripKm: Double, recentWhPerKm: Double?): Double /* trip only below RECENT_KM of trip; else 0.5·trip + 0.5·recent */; fun estimate(energy: EnergyState?, consumption: ConsumptionEvidence?, recentWhPerKm: Double?): RangeEstimate? }`; `interface RangeSource { val range: StateFlow<RangeEstimate?> }`

- [ ] **Step 1: Failing tests** — `noCapacityNoRange`; `notEarnedBelowOneKm`; `notEarnedWithFewSamples`; `remainingOverConsumption` (remaining 900 Wh, 20 Wh/km → 45 km); `climbAfterGentleStartShowsUp` (trip 15 Wh/km over 10 km, recent 40 → blended 27.5); `liveVoltageIsNotUsed` (incoherent: aggregate voltage 60 V on a 100 V nominal pack → range unchanged); `arrivalUsesNominalEnergy` in `ArrivalEnergyEstimatorTest` (sagged voltage does not change the arrival %).
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Suite passes.**
- [ ] **Step 5: Commit** `feat(energy): range estimate; navigation reads the same remaining energy`

### Task 8: The Energy block in the vehicle editor

**Files:**
- Modify: `presentation/vehicle/VehicleEditComponent.kt` (state: `capacityText`, `capacityUnit`, `energy: EnergySummary?`; methods `onCapacityChanged(text: String)`, `onCapacityUnitChanged(unit: CapacityUnit)`, `onResetLearnedEnergy()`)
- Modify: `presentation/vehicle/VehicleEditPages.kt` — `BatteryPage` gets a separate "Энергия" block below the pack cards (not inside one), styled with the redesign's existing rows
- Strings: `values/strings.xml`, `values-ru/strings.xml`
- Test: `VehicleEditComponentTest`

**Interfaces:**
- Consumes: `VehicleEnergyStore` (Task 5), `Vehicle.batteryCapacity`.
- Produces: `data class EnergySummary(val learnedNameplateWh: Float?, val learnRides: Int, val resistanceMilliOhm: Int?, val avgWhPerKm: Float?)`.

Rows: capacity field (number, Wh/Ah segmented), caption "выучено: ~N Втч по K поездкам" / "учусь…" / hidden when entered and nothing learned; "Сопротивление ~N мОм"; "Средний расход ~N Втч/км"; "Сбросить выученное" (confirm dialog). `avgWhPerKm` comes from the live `RangeSource` consumption when this vehicle is the connected one, else null (not persisted). Learned is shown in nameplate Wh: `usableAh / usableFraction × seriesCells × nominalCellV`.

- [ ] **Step 1: Failing tests** — capacity text parses (comma and dot decimals), blank clears to null, non-positive rejected; unit switch converts nothing (keeps the typed number, changes the unit); the summary converts learned usable Ah to nameplate Wh; reset calls the store and clears the summary; save persists `batteryCapacity`.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement component, then the block, then strings (en + ru).**
- [ ] **Step 4: Suite passes.**
- [ ] **Step 5: Commit** `feat(vehicle): energy block — capacity, learned values, reset`

### Task 9: Dashboards

**Files:**
- Modify: `presentation/ride/RideDashboardComponent.kt` (`State.range: RangeEstimate?`, collected from `RangeSource`)
- Modify: `presentation/ride/LightDashboardMapper.kt`, `LightRideDashboard.kt` (`LightBatteryLine`: voltage 10 → 12 sp, SoC 11 → 13 sp, range "~42 км" 13 sp muted after " · ")
- Modify: `presentation/ride/SecondaryGaugeMapper.kt` / Clean renderer (BATTERY centre: range sub-label; otherwise range readout next to SoC in the bottom row)
- Modify: `presentation/ride/ClassicDialSpecs.kt` (battery dial sub-label)
- Test: mapper tests for each

**Interfaces:**
- Consumes: `RangeSource.range` (Task 7).
- Produces: `fun rangeLabel(range: RangeEstimate?, units: <the app's distance unit type used by RideDistanceMapper>): String?` in `presentation/ride/RangeLabel.kt` — "~42 км" / "~26 mi", null when range is null. Reuse `RideDistanceMapper`'s unit conversion.

- [ ] **Step 1: Failing tests** — `rangeLabel` null → null; 42.4 km → "~42 км"; miles; Light mapper emits the range readout only when non-null; Clean BATTERY-centre sub-label present/absent; Classic battery dial sub-label present/absent.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement mappers, then renderers (render only what the mapper hands them).**
- [ ] **Step 4: Suite passes.** State plainly in the report that layout on a device is unverified (Compose is not testable here).
- [ ] **Step 5: Commit** `feat(ride): range on all three dashboards; larger battery numbers on Light`

### Task 10: Field-check notes

**Files:**
- Create: `docs/superpowers/specs/2026-07-24-vehicle-platform/field-reports/2026-10-24-energy-model-checklist.md` — the spec's four verification steps, in Russian, as a checklist the rider can follow, plus what to send back (screen recording of a hard pull; SoC at stop vs after 30 min).

- [ ] **Step 1: Write it.**
- [ ] **Step 2: Commit** `docs: energy model field checklist`
