# Part I — What The First Ride Found: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix the eight confirmed defects that the first real-hardware test of the
vehicle platform exposed, so that a Begode wheel and a VESC scooter both show real
speed, voltage, power and battery instead of blanks and inverted numbers.

**Architecture:** Three independent fault domains, no shared code between them.
(1) The Begode decoder publishes a signed speed where every consumer wants a
magnitude, and withholds its rail voltage because the one number it needs — the pack's
series-cell count — is unreachable to the rider and unfillable on a wheel without a
smart BMS. (2) The VESC gateway asks its one speed-bearing request of the one node
that cannot answer it, and adding a CAN controller deletes the battery slot instead of
filling it. (3) The motion aggregator's known-flag contract exists on paper but reaches
exactly one of fourteen value folds, and no VESC producer can clear a flag at all.

**Tech Stack:** Kotlin Multiplatform (Android target), Compose Multiplatform,
Decompose, Koin, Kable (BLE), SQLDelight, kotlinx-coroutines, kotlin.test + Turbine.

**Evidence base:** `docs/superpowers/specs/2026-07-24-vehicle-platform/field-reports/2026-07-30-first-hardware-test.md`.
Every defect below is marked CONFIRMED there or in the three diagnostic traces behind
it. **Where this plan and an older spec's reasoning disagree, this plan wins** — it is
measurement, the specs were inference from one stationary capture and a reading of
WheelLog that turned out to be partial.

**Sequencing:** starts after Part G2 Task 7 (`ClassicDialSpecs` is in flight there).
Part G2's Tasks 8 and 9 follow this part, not precede it.

## Global Constraints

1. **Non-vacuity proven per assertion, in both directions.** Every mutation killed by
   some assertion, **and** every assertion killable by some implementation. Delete any
   that no implementation could falsify and say so.
2. **A zero-failure mutation run is not evidence unless the build compiled *and* the
   test count is non-zero and exactly right.** Four sweeps on this project reported
   false passes: one never started Gradle (a `cmd /c gradlew.bat` call Windows resolved
   against `PATH`), one scored stale XML, two were served cached/`UP-TO-DATE` results
   for a control. Bytecode-changing control with a **fresh nonce per run**, results
   directory wiped, count asserted. Never two sweeps at once.
3. **Sweep your own additions.** Twelve implementers on this project have shipped
   guards indistinguishable from their absence, each finding it only this way.
4. **A fixture where every contributor is complete cannot see an incompleteness bug.**
   This part exists partly because that was true of the aggregator's whole test suite.
   Where a contract concerns absent data, the fixture must be *deliberately
   incoherent* (`powerW = 4200f, hasPower = false`) — a combination no producer emits,
   which is exactly why it separates the contract from the producers' habits.
5. **Never write to Begode's FFE1 characteristic.** It is the wheel's command channel
   (light, pedal mode, tiltback); a stray write could reconfigure a wheel under its
   rider. `handshakeCommands()` and `pollCommands()` return empty lists as a
   requirement, not an oversight. This forbids WheelLog's model-string probe.
6. **The battery path must not change behaviour** except where a task names it.
   Riders depend on it.
7. **`runTest` hazard:** a test starting an unbounded delayed loop makes virtual time
   advance forever and **wedges the build instead of failing**.
8. Compose UI is not unit-testable here (no Robolectric, no `compose-ui-test`, no
   instrumented source set). Every decision goes in pure/component code; the
   `@Composable` layer stays a thin renderer. Do not write a test that dresses up an
   unverifiable claim — say plainly what needs a device.
9. Russian UI strings in **both** `values/` and `values-ru/`. Compose Multiplatform
   does **not** process Android backslash escapes.
10. The suite stands at **1448 tests, 0 failures** before this part. Every task runs
    `.\gradlew.bat :composeApp:testDebugUnitTest` green before committing.

---

## File Structure

| File | Responsibility | Tasks |
|---|---|---|
| `data/bms/BegodeProtocol.kt` | wheel decode: speed magnitude, derived cell count, rail-voltage precedence | 1, 2 |
| `presentation/vehicle/VehicleSourceCards.kt` | the cell-count input widget deleted in `9277097` | 3 |
| `presentation/vehicle/VehicleComposer.kt` | `PackDraft.cellCountEdited`, the not-overwritten flag | 3 |
| `data/ble/KableBmsRepository.kt` | `maybePersistCellCount` must respect an edited value; `motionSamples()` accessor | 3, 8 |
| `data/bms/vesc/VescGatewayProtocol.kt` | SETUP per controller, not from `primary`; the opcode-96 ask | 4, 5 |
| `data/ble/ControllerProtocols.kt` | `deriveBattery` must survive the gateway branch | 5 |
| `presentation/vehicle/VehicleEditComponent.kt` | motor config for a CAN-discovered controller | 6 |
| `domain/stats/MotionAggregator.kt` | the per-field known-flag fold rules | 7 |
| `data/bms/vesc/VescValues.kt` | VESC producers must be *able* to clear a flag | 7 |
| `domain/stats/RideEnergy.kt` (new) | trapezoidal Wh integration, provenance-marked | 8 |

---

## Tasks

### Task 1 — the wheel's speed is a magnitude, taken at decode

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/BegodeProtocol.kt:982`
  (the decode), `:283-289` (the KDoc that currently promises a sign), `:1320-1349`
  (`SPEED_KMH_PER_UNIT`'s KDoc — the moving-capture gap it describes is now closed)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/BegodeMotionProtocolTest.kt`
  (`:201`, `:208-215`, `:289`, `:465`, `:611`, `:802`, `:1087`)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/stats/` — the
  cross-layer regression named in Step 5

**Interfaces:**
- Produces: `fun speedKmh(): Float?` — unchanged signature, now **non-negative**;
  `internal fun signedSpeedKmh(): Float?` — new, keeps the direction for any later
  consumer, same "decoded but unsurfaced" shape as `powerOnDistanceMeters()` and
  `tiltbackSpeed()`.

**The decision, so the implementer does not re-litigate it.** Forward reads negative on
two different wheels (field report S1). Three candidate fixes: negate, take `abs`, or
expose WheelLog's per-wheel three-way preference. **Take `abs`.** Reasons in order of
weight:

- **Every consumer treats speed as a non-negative magnitude compared against upper
  thresholds** — `AlarmController.kt:174`, `RideDashboardScreen.kt:116`'s session max,
  `MotionAggregator.kt:43`'s `maxOf` fold, and both dial renderers as a fraction of
  full scale. This is the *identical* argument this same file already made for duty,
  which takes `abs` at decode in `parseMotionFrame` rather than in `rebuildMotion`,
  "so the two protocols agree on what the shared field means". Speed is the same
  shape and gets the same treatment in the same place.
- **The polarity is not universal.** WheelLog exposes it as a per-wheel preference
  precisely because it varies by firmware and motor wiring, and its **default is
  `Math.abs`** (`GotwayAdapter.java:145-156`, default `"0"`). Negation fixes these two
  wheels and silently breaks the next rider's — and it breaks it into the worst
  failure mode there is, because a negative speed kills instant consumption, silences
  the speed alarm and freezes the session peak, all without an error.
- **Nothing consumes the direction.** There is no `abs(speedKmh)` anywhere in
  `commonMain` and no reverse indicator in any UI, so the sign the KDoc promises is
  unconsumed. It is kept on `signedSpeedKmh()` rather than discarded, so a later part
  that wants a reverse indicator starts from a decode that works.

- [ ] **Step 1: Write the failing test.** In `BegodeMotionProtocolTest.kt`, beside the
      existing reverse test at `:208`:

```kotlin
@Test
fun forwardMotionPublishesPositiveSpeedWhateverTheFieldsSign() {
    val protocol = BegodeProtocol()
    // The rider's wheels report forward as NEGATIVE in bytes 4..5 (field report S1).
    protocol.onNotification(liveFrame(speedRaw = -1000))
    assertEquals(36.0f, protocol.speedKmh()!!, 1e-3f)
    // The direction is kept, just not published as the speed.
    assertEquals(-36.0f, protocol.signedSpeedKmh()!!, 1e-3f)
}
```

- [ ] **Step 2: Run it and watch it fail.** `.\gradlew.bat :composeApp:testDebugUnitTest --tests "*BegodeMotionProtocolTest*"`.
      Expected: FAIL — `speedKmh()` returns `-36.0`, and `signedSpeedKmh` does not exist.

- [ ] **Step 3: Implement.** At `BegodeProtocol.kt:982`, keep the raw signed value in a
      new `private var signedSpeedKmhValue: Float` and publish the magnitude:

```kotlin
signedSpeedKmhValue = speedRaw * SPEED_KMH_PER_UNIT
speedKmhValue = abs(signedSpeedKmhValue)
```

      `abs` is already imported (`:7`). Clear `signedSpeedKmhValue` in `reset()` beside
      `speedKmhValue` (`:803`). Rewrite the `speedKmh()` KDoc at `:283-289`: it
      currently promises "NEGATIVE while the wheel rolls backwards", which becomes a
      lie — replace it with the magnitude contract and the reason, and point at
      `signedSpeedKmh()`.

- [ ] **Step 4: Fix the tests that merely restated the old convention.** `:215`
      (`syntheticReverseMotionReadsNegativeNotTwoThousandKmh`) asserts `-18.828f` for
      `speedRaw = -523` and must now assert `+18.828f` — **rename it too**, since its
      name states the old contract. `:201`, `:289`, `:465`, `:611`, `:802`, `:1087`
      assert positive values for positive raw and pass unchanged. Do not touch the
      stationary-capture assertions (`:520`, `:605`, `KableBmsRepositoryBegodeFunnelTest.kt:387`)
      — they assert `0f` and are sign-agnostic.

- [ ] **Step 5: Write the cross-layer regression that would have caught this.** The
      unit tests could not: the ET Max capture reads `00 00` in bytes 4..5 across all 38
      live frames, so every non-zero speed assertion is synthetic and restates the
      decoder's own assumption. What the suite lacked is a test that a *forward-moving
      wheel* produces a usable consumption reading. Add one that drives a Begode sample
      with a forward speed and a real power through `RideMetrics.instantWhPerKm` and
      asserts a non-null result — it fails today for the whole forward ride because
      `RideMetrics.kt:11` nulls out below `MIN_SPEED_KMH = 0.5f` and every negative
      speed is below it.

- [ ] **Step 6: Sweep.** Revert `abs(...)` to the bare product and confirm a named test
      fails. Revert `signedSpeedKmhValue`'s `reset()` line and confirm a named test
      fails — if none does, the reset is unproven and needs a reconnect test.

- [ ] **Step 7: Full suite, then commit.**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/BegodeProtocol.kt composeApp/src/commonTest
git commit -m "fix(begode): forward is forward, whatever sign the wheel puts on it"
```

---

### Task 2 — the rail voltage comes from the cells when the wheel has them

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/BegodeProtocol.kt`
  — `inputVoltageOrNull()` (`:713-718`), `reset()` (`:795-836`), a new derived-count
  field, and `parseCells`/`parseBmsTelemetry` where the count is learned
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/BegodeProtocolTest.kt`,
  `BegodeMotionProtocolTest.kt`

**Interfaces:**
- Produces: `internal fun derivedCellCount(): Int?` — the series-cell count proven by
  decoded cells, null until proven. `inputVoltageOrNull()` keeps its signature.

**Why.** The ET Max has a smart BMS: its branch cells are already decoded and its two
branches are **parallel**, so a branch's cell-summed voltage *is* the rail voltage —
available with no vehicle profile at all. Today `inputVoltageOrNull()` ignores all of
it and returns null because the constructor's `cellCount` is null, which is why the
rider saw no power on a wheel that was reporting everything needed to compute it.
WheelLog does exactly this (`GotwayAdapter.java:212`, gated on
`getAutoVoltage() = pref && smartBmsCells > 0` at `:701-702`, defaulting on).

**Precedence, in this order:**
1. **Branch 0's cell-summed voltage**, when the cell set is proven complete.
2. **`scaleLiveVoltage(liveVoltageRaw * 0.01f, cellCount)`** with the profile's count
   (today's only path).
3. **Unknown** — `inputVoltageV = 0f`, `hasInputVoltage = false`.

- [ ] **Step 1: Write the failing test.**

```kotlin
@Test
fun smartBmsCellsSupplyTheRailVoltageWithNoProfileCellCount() {
    val protocol = BegodeProtocol(cellCount = null) // nothing from the profile
    feedEtMaxSmartBmsCapture(protocol)              // existing fixture helper
    val motion = protocol.latestMotion(0)!!
    assertTrue(motion.hasInputVoltage)
    assertEquals(147.2f, motion.inputVoltageV, 0.5f)
    assertEquals(40, protocol.derivedCellCount())
}
```

- [ ] **Step 2: Run it and watch it fail.** Expected: `hasInputVoltage` is false and
      `inputVoltageV` is `0f` — today's behaviour, and the rider's.

- [ ] **Step 3: Implement the derived count, completeness-gated.** `contiguousCells`
      legitimately returns a truncated run mid-stream — 8 of 40 while packets arrive —
      and a naive `cells.size` would derive 8S and scale the live frame to ~29.5 V.
      **Reuse the completeness test `branchVoltage` already uses** (`cellSum >=
      frameVoltage * CELL_SUM_COMPLETE_RATIO`, `:1270`/`:1514`), not `cells.size`
      alone. Then cross-check against the live frame, which is a free second witness:
      `cellSum / (liveVoltageRaw * 0.01f)` must be within tolerance of
      `count * 4.2f / 67.2f`. Refuse the count when the two disagree; a wheel that
      contradicts itself gets an honest absence.

- [ ] **Step 4: Implement the precedence in `inputVoltageOrNull()`.** Do **not** reuse
      the `smartBmsSeen` gate. That gate (`liveVoltageOn672ScaleV()` at `:273-274`,
      `retireSyntheticPack()` at `:1212`) exists to stop the synthetic *pack*
      overriding real branches, and `inputVoltageOrNull` deliberately does not take it
      because a controller's rail is the wheel's own measurement. What this needs is
      the **opposite** ordering — prefer branch data when it exists, fall back to the
      scaled live frame when it does not — which is a new rule, not that gate reversed.
      Say so in the KDoc.

- [ ] **Step 5: Clear the derived count in `reset()`.** This is the trap most likely to
      be missed, because the constructor `val` never needed it: a reconnect may face a
      *different wheel*, and inheriting the previous one's series count mis-scales it
      silently. Put the clear beside `sawTrueDuty` (`:827`) and `sawMotorTempEvidence`
      (`:823`), with the same one-line reason.

- [ ] **Step 6: Record the known discontinuity.** The 0x01 frame's pack-voltage field
      is ~0.1009 V/unit, not the 0.1 `parseBmsTelemetry` decodes it at (`:1158`,
      measured, `:1250-1256`). So while cells are still arriving the branch-derived
      rail reads ~0.9 % low, then steps up when the cell sum takes over — visible in
      voltage *and* in power. WheelLog inherits the same error. Acceptable versus
      absent; state it in the KDoc rather than hiding it.

- [ ] **Step 7: Sweep, full suite, commit.** Mutate the completeness gate to
      `cells.size` and confirm a named test fails (that is the 8S-mid-stream bug).
      Mutate the cross-check tolerance to infinity and confirm a named test fails.

```bash
git commit -m "fix(begode): a wheel that counts its own cells needs no cell count"
```

---

### Task 3 — the rider can type the cell count again

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/vehicle/VehicleSourceCards.kt:213-221`
  (`ReadOnlyRow` → an input, mirroring the field deleted in commit `9277097`)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/vehicle/VehicleComposer.kt:112-158`
  (`PackDraft`: add `cellCountEdited`, mirroring the existing `aliasEdited` at `:143,157`)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt:661-688`
  (`maybePersistCellCount` must not overwrite an edited value)
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml` and
  `values-ru/strings.xml` — `vehicle_field_cell_count` already survives at `:198` as
  an orphan; **verify it exists in both locales** and reword it: it is no longer
  "optional" on a wheel with no smart BMS, it is the only way to get a voltage.
- Test: the composer component tests, and a repository test for the no-overwrite rule

**Interfaces:**
- Consumes: `Pack.cellCount: Int?` (`domain/model/Pack.kt:27`), `Vehicle.withCellCount`
  (`domain/model/Vehicle.kt:154-155`)
- Produces: `PackDraft.cellCountEdited: Boolean` — true once the rider typed a count,
  which `maybePersistCellCount` must respect.

**Why this reverses an earlier decision.** `9277097` deleted the input on the premise
that "the cell count is auto-filled from telemetry" (the comment at
`VehicleSourceCards.kt:213-217` still says so). The EXN proves the premise false in the
one case that matters: **a wheel with no smart BMS sends no cell voltages, so
`maybePersistCellCount` sees `n == 0` and returns forever** — and that is precisely the
wheel that cannot derive a voltage any other way (Task 2 covers the wheels that can).
The premise held for every source Volty spoke to when it was written; it does not hold
for a dumb EUC.

- [ ] **Step 1: Write the failing test — the rider's value survives telemetry.**

```kotlin
@Test
fun anEditedCellCountIsNotOverwrittenByTheAutoFill() {
    // Rider typed 30S; the wheel then streams three stable 40-cell samples.
    val vehicle = wheelVehicle(name = "EXN", address = ADDRESS)
        .withCellCount(30, edited = true)
    repository.connect(vehicle)
    feedThreeStableSamples(cellVoltages = List(40) { 4.1f })
    assertEquals(30, savedVehicle().packs.first().cellCount)
}
```

`withCellCount` (`Vehicle.kt:154-155`) takes only a count today — widen it, or set the
flag on the `Pack` and leave the helper alone; either is fine, but pick one and use it
in both the composer and the repository so there is one spelling. `wheelVehicle` is
`VehicleBuilders.kt:105`; `feedThreeStableSamples` does not exist — the existing
repository tests have an equivalent driver, reuse it rather than writing a second.

- [ ] **Step 2: Run it and watch it fail** — the auto-fill overwrites after 3 stable
      samples (`cellCountStableSamples = 3`, `KableBmsRepository.kt:649`).

- [ ] **Step 3: Add the persisted `edited` flag.** Follow `aliasEdited`'s existing
      shape exactly (`VehicleComposer.kt:143,157`) rather than inventing a second
      pattern. The flag needs a column: SQLDelight migration + snapshot, and the
      snapshot is **not optional** — a migration without one makes `verifyMigrations`
      vacuous. Check the highest existing `N.sqm` and `N.db` before numbering: as of
      Part G2 Task 7 the migrations run to `7.sqm` and the snapshots to `8.db`.

- [ ] **Step 4: Restore the input.** An `OutlinedTextField` with `onCellCountChanged`,
      keyboard type number, blank → null (not 0 — `inputVoltageOrNull` treats
      `cells <= 0` as unknown, and the column has no `CHECK` to stop a stored 0).
      Replace the now-false comment at `:213-217` with the reason it is editable again.

- [ ] **Step 5: Both locales, and mind the escapes.** Compose Multiplatform does not
      process Android backslash escapes — no `\n`, no `\'`.

- [ ] **Step 6: Sweep, full suite, commit.** Mutate the `edited` guard away and confirm
      the Step-1 test fails.

```bash
git commit -m "fix(composer): the wheel that can't count its cells needs someone who can"
```

---

### Task 4 — SETUP is asked of every controller, not of `primary`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/vesc/VescGatewayProtocol.kt:232`
  (the `primary` target), with reference to the machinery that already works:
  `:293` (request assembly), `:300-301` (the `FORWARD_CAN` wrapper), `:317` (the
  `c.globalIndex` reply key), `:399` (the per-cycle walk)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/vesc/VescGatewayProtocolTest.kt`

**Interfaces:**
- Consumes: the existing per-controller poll walk and reply demultiplexer, both
  confirmed sound — the forward is correctly wrapped and the reply is keyed to the
  right controller index.
- Produces: `COMM_GET_VALUES_SETUP` replies attributed per controller instead of one
  reply attributed to the vehicle.

**Why.** `primary = controllers.firstOrNull()`, and on the rider's scooter controller 0
is the head unit — a VESC Express bridge with no motor, whose firmware does not handle
opcode 47 at all (it falls through to `default: break;` and never builds a reply).
`GET_VALUES_SETUP` is the frame carrying **speed, trip, odometer and battery level**, so
all four are pinned at 0 no matter how well the real uBox answers everything else. This
is the single highest-value fix in the part: it restores the rider's speed without
needing any motor configuration at all, because SETUP reports ground speed directly.

- [ ] **Step 1: Write the failing test — the rider's exact vehicle.**

```kotlin
@Test
fun setupIsReadFromWhicheverControllerAnswersIt() {
    // Controller 0 is a head unit: answers nothing. Controller 1 is a real uBox.
    val protocol = gatewayProtocol(controllers = listOf(headUnit(), uBox(canId = 24)))
    protocol.onNotification(setupReply(canId = 24, speedMs = 11.67f, odometerKm = 812f))
    val motion = protocol.latestMotion(1)!!
    assertEquals(42.0f, motion.speedKmh, 0.1f)
    assertEquals(SpeedSource.REPORTED, motion.speedSource)
    assertEquals(812f, motion.odometerKm, 0.1f)
}
```

- [ ] **Step 2: Run it and watch it fail** — no SETUP request is ever addressed to
      controller 1, so nothing decodes the reply.

- [ ] **Step 3: Implement.** Issue the SETUP request inside the same per-cycle walk
      that already issues `GET_VALUES` for each controller, wrapped in `FORWARD_CAN`
      by the same code path, and key the reply by the same `c.globalIndex`. **Delete
      the `primary` concept for SETUP** rather than re-electing a better primary — a
      re-election needs a liveness signal that does not exist and would leave the same
      class of bug for the next topology.

- [ ] **Step 4: Account for the traffic.** This adds one frame per controller per poll
      cycle. State the new frame count per cycle in the report and check it against the
      existing poll interval and the BLE MTU budget — a poll cycle that no longer fits
      in its interval is a new defect, not a fix. If it does not fit, say so and stop:
      the interval is a product decision, not an implementation one.

- [ ] **Step 5: Sweep, full suite, commit.** Mutate the per-controller target back to
      `firstOrNull()` and confirm the Step-1 test fails.

```bash
git commit -m "fix(vesc): asking the dashboard how fast it is going was never going to work"
```

---

### Task 5 — adding a CAN controller must not delete the battery

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/ControllerProtocols.kt:95-100`
  (`deriveBattery` silently dropped on the gateway branch)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/vesc/VescGatewayProtocol.kt:542`
  (`packCount = packs.size`), `:293`/`:339` (where the opcode-96 ask belongs and where
  its only sender is built)
- Reference: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt:1198`
  (bails on `counts[i] == 0`), `domain/model/VehicleBuilders.kt:54-63` (the
  picker-built vehicle has zero packs)
- Test: `VescGatewayProtocolTest.kt`, and a repository-level test that the vehicle
  connects with a battery at all

**Why.** This is the literal mechanism behind *"это ничем не помогло"*: the rider added
the CAN controller to fix the battery, and the act of adding it **removed the battery
slot**. `deriveBattery` is dropped on the gateway branch, `packCount` becomes
`packs.size` = 0, and the repository bails out on the zero count. Separately, opcode 96
(`COMM_BMS_GET_VALUES`) is never sent, because its only sender is constructed per owned
pack and there are none — so even the head unit's working, correctly-emulating ANT
bridge is never asked anything.

- [ ] **Step 1: Write the two failing tests.** (a) a gateway vehicle plus a
      CAN-discovered controller still has a derived battery — assert `packCount >= 1`
      and that a `BmsData` arrives; (b) a vehicle whose gateway hosts a BMS emits
      opcode `96` in its poll — assert the opcode appears in the recorded writes.

- [ ] **Step 2: Run them and watch both fail.**

- [ ] **Step 3: Fix the drop.** Carry `deriveBattery` through the gateway branch. This
      is the third instance on this project of a branch silently losing a field the
      other branch keeps (`G §8`'s rebuild-on-save was the first two) — so fix it in
      the polarity that fails safe: derive the pack list from the source of truth and
      `copy()` what differs, rather than re-listing fields per branch.

- [ ] **Step 4: Send the ask.** Wire opcode 96 at `:293` for a hosted BMS, decoded by
      the existing `VescBmsValues` path. Do not invent a `CAN_PACKET_*` reader — those
      frames never cross BLE and Volty is right to have none.

- [ ] **Step 5: Sweep, full suite, commit.**

```bash
git commit -m "fix(vesc): adding the controller must not remove the battery"
```

---

### Task 6 — a controller with no motor config reads as unknown, not as zero

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/vehicle/VehicleEditComponent.kt:836`
  (a CAN-discovered controller is created with a bare `MotorConfig()`)
- Reference: `domain/model/Controller.kt:12` (`wheelDiameterMm` default 0),
  `data/bms/vesc/VescValues.kt:162` (the eRPM fallback that needs it),
  `domain/model/ControllerData.kt:152` (`speedKnown`)
- Test: the composer component tests and `VescValuesTest`

**Why.** A CAN-discovered controller gets `wheelDiameterMm = 0`, so the eRPM→speed
fallback is unavailable. After Task 4 this is no longer the *only* road to a speed —
SETUP reports ground speed directly — but it remains the road for any controller that
answers `GET_VALUES` and not SETUP, and a zero diameter must never render as a
confident `0 km/h`.

- [ ] **Step 1: Write the failing tests.** (a) a CAN-discovered controller inherits the
      gateway's motor config rather than a bare default, or is flagged as needing one;
      (b) a sample whose `speedSource` is `NONE` reports `speedKnown == false`, and the
      Ride mapper renders `—` rather than `0` for it.

- [ ] **Step 2: Run them and watch them fail.**

- [ ] **Step 3: Implement.** Two halves, both required: the composer must carry a motor
      config for a CAN controller (inheriting the gateway's is the sane default —
      slaves on one vehicle almost always share a wheel), and the renderers must treat
      an unconfigured controller as unknown. The second half is Part G2 Task 6's
      contract applied one field over; reuse `MotionReadings`/`MotionReadoutText`
      rather than adding a fourth spelling of "unknown".

- [ ] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "fix(composer): a controller nobody measured a wheel for does not know its speed"
```

---

### Task 7 — the known-flag contract reaches the fold

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/MotionAggregator.kt`
  — `:37` (`speedSource`), `:43` (`speedKmh`), `:45` (`dutyPercent`), `:68`
  (`inputVoltageV`, the one fold that already consults its flag), `:77` (`powerW`),
  `:92-93` (the temperatures), `:97-100` (the four counters), `:116`
  (`batteryLevelFraction`), `:124` (`timestamp`)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/vesc/VescValues.kt`
  — `:94` (`speedSource` hardcoded `REPORTED`), `:98` (`hasInputVoltage` never
  assigned), `:112` (`batteryLevelFraction = 0f` meaning unknown), `:152`
  (`isConnected` unconditional)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/ControllerData.kt:163`
  (`hasEscTemp` as a post-fold getter)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/stats/MotionAggregatorTest.kt`

**Why.** Of fourteen value folds, **one** consults its known-flag. `G §9.3` is written
up as fixed-by-flag but the fix is unreachable for VESC: no VESC producer can clear
`hasInputVoltage` — the default is `true` and nothing assigns it — so `BegodeProtocol`
is the only producer in the app that ever clears it. None of this caused the rider's
symptoms (the head unit's sample never arrives; the timeout path correctly drops it),
but a head unit that *did* answer with zeros would be believed, and that firmware is
one update away.

**The per-field rule, so the implementer is not guessing:**

| fold | field(s) | rule |
|---|---|---|
| `average` | `inputVoltageV` | filter contributors by flag; unknown if none remain |
| `maxOf` | `speedKmh`, `dutyPercent`, `powerW`, temps | filter by flag — a hollow 0 must not win a `maxOf` over an absent sensor, and a **signed** field's `maxOf` is floored at 0 by a hollow contributor |
| `sum` | the four energy counters | choose explicitly and say which: skip-and-flag-partial, or blank the total. Today they silently blank, throwing away a real measurement |
| `union` | `faults` | already correct — the only honest fold |
| getter | `hasEscTemp` | must not be computed after a `maxOf` that destroyed the sentinel; exclude the contributor instead of outvoting it |
| `mapNotNull` | `batteryLevelFraction` | `0f` is unknown, not empty — `VescProtocol.kt:208` one file over already knows the test is `> 0f` |

- [ ] **Step 1: Write the failing tests, and build the fixtures the wrong way on
      purpose.** Every existing hollow fixture is hand-built with `has*` explicitly
      `false` (`MotionAggregatorTest.kt:249-255`) — a shape **no VESC producer can
      emit** — so the suite tests the producers' current habits, not the contract.
      Required new fixtures: a hollow contributor with flags left at their `true`
      defaults; a negative real speed beside a hollow 0; a `REPORTED`-with-0 hollow
      beside a `NONE` real; `batteryLevelFraction = 0f`; a hollow ESC temp above the
      `-50f` sentinel.

- [ ] **Step 2: Run them and watch them fail.** Expect several: this step is the
      evidence that the contract was decorative.

- [ ] **Step 3: Make the flags assignable at the source.** A contract no producer can
      exercise is not a contract. `VescValues` must be able to clear
      `hasInputVoltage`/`hasPower`/`hasDuty`/`hasEnergyCounters` and must not hardcode
      `speedSource = REPORTED` on a reply that carried no speed.

- [ ] **Step 4: Implement the table.** One fold at a time, each with its own named test.

- [ ] **Step 5: Close the structural gap.** Add the test nobody wrote: fold **real
      `VescGatewayProtocol` output** through `MotionAggregator`. Today the only
      connection between the two is a prose mention in a comment
      (`VescGatewayProtocolTest.kt:446`), which is why a cross-layer disagreement was
      invisible to 1448 tests.

- [ ] **Step 6: Sweep, full suite, commit.**

```bash
git commit -m "fix(aggregate): one fold of fourteen was keeping the promise"
```

---

### Task 8 — consumption may be synthesised, but never disguised

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/RideEnergy.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/BmsRepository.kt:88`
  (add a `motionSamples(window)` beside the `BmsData` one), and
  `data/ble/KableBmsRepository.kt:380` (the `motionRingBuffer` that is written at
  `:1438`/`:889`, cleared at `:843`/`:1903`/`:2360`, and **read nowhere**)
- Modify: `domain/stats/MotionReadings.kt:103-105` (the `hasEnergyCounters` gate),
  `presentation/ride/RideDashboardComponent.kt:116,134`
- Test: a new `RideEnergyTest`, plus the readings tests

**Why, and why last.** A Begode reports no Wh or Ah counters at all, so session
consumption is absent by construction. The arithmetic to fix it already exists and
works: `GraphComponent.kt:112-127` integrates `power × dt` into Wh, sign-corrected, and
on the ET Max the Graph screen's "used Wh" is **already correct today** — it simply
never reaches the Ride dashboard. The missing pieces are an accessor for the
write-only motion ring buffer and somewhere for the accumulated Wh to live.

This task is last because it is the only one whose absence leaves a *blank* rather than
a *lie*, and because its inputs are the other tasks' outputs: with a null cell count it
integrates a constant 0 W, and with Task 1 unfixed it divides by a negative distance.

- [ ] **Step 1: Write the failing test.** A synthetic ride — constant 600 W for 60 s
      over 1 km — yields 10 Wh and 10 Wh/km, and the result is marked **synthesised**.

- [ ] **Step 2: Run it and watch it fail.**

- [ ] **Step 3: Implement the integrator.** Trapezoidal, over the motion samples'
      `timestamp` (`ControllerData.kt:150`, stamped at arrival in
      `ConnectionSession.kt:209-212`). Copy the arithmetic from
      `GraphComponent.kt:112-127` — or better, extract it so there is one integrator
      rather than two. Skip samples whose `hasPower` is false: integrating an unknown
      as 0 W is the same defect this whole part is about.

- [ ] **Step 4: Keep the provenance.** **Do not set `hasEnergyCounters = true`** for a
      synthesised figure. A derived number presented as a measurement is exactly what
      `G §9`'s contract forbids; add a separate provenance so a renderer can mark it
      (an "≈" or a footnote is a product call — surface the flag and say so in the
      report).

- [ ] **Step 5: Sweep, full suite, commit.**

```bash
git commit -m "feat(ride): a wheel that counts no watt-hours can still be measured"
```

---

## Out of scope

- **Reading `GET_MCCONF`** (`B §14`). Part G2 Task 7 made gauge ranges self-learning,
  so mcconf is a future *seed*, not a dependency.
- **Begode's model/firmware string.** WheelLog learns it by writing `"N"` to FFE1;
  Global Constraint 5 forbids the write, and WheelLog does not use the string for the
  voltage scale anyway.
- **A per-wheel speed-polarity preference.** Task 1's `abs` is polarity-proof, which is
  the whole point of choosing it over negation; a preference would be UI for a problem
  that no longer exists.
- **A ride recorder / session persistence.** There is no ride or sample table in the
  schema and Task 8 does not add one — its integration is in-memory, per session.
- **Parts E (FarDriver) and H (Kelly).** Unchanged by this part.

## Open, carried from Part G2 Task 7's review

- **A learned gauge range grows and never shrinks.** There is no decay, no
  shrinking, and no rider-facing reset — only the automatic clear when the
  vehicle's controller set changes. A corrupt reading that survives the
  median-of-five filter (three adjacent corrupt frames passing both the
  `5A5A5A5A` tail check and the zero-payload gate) widens the dial permanently,
  and the rider has no way to undo it. Refused deliberately in that task rather
  than grown into it: a reset is UI, a decay is a policy nobody has evidence for,
  and the filter closes the plausible case. Whoever adds the vehicle-settings
  screen should add the reset there.

## Open, and needing the rider rather than an implementer

- **The bicycle's controller type**, which decides whether Part H or Part E comes next.
- **A moving Begode capture beside a reference speed** would upgrade
  `SPEED_KMH_PER_UNIT` from "corroborated twice in one file and once by eye" to
  measured. Deliberately not blocking anything: Task 1 makes the sign safe regardless,
  and the scale is inside its useful tolerance.
