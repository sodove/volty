# Part K — A Device Is Its Address, Not Its Name: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop re-guessing from an advertised name what the app already knows from an
address, and remember a rider's correction so they make it once.

**Architecture:** Detection stays a hint and keeps its current rules. What changes is
precedence: a scanned address that a saved vehicle already claims takes that vehicle's
stored type, and an address a rider has explicitly typed keeps that choice across scans.
Nothing about connecting changes — a saved source has always been reached by address with
an explicit stored type.

**Tech Stack:** Kotlin Multiplatform (Android target), Compose Multiplatform, Decompose,
Koin, Kable (BLE), SQLDelight, kotlinx-coroutines, kotlin.test + Turbine.

---

## The problem, measured rather than assumed

**The binding is already by address everywhere it decides anything.** `ControllerRow` and
`PackRow` each store `address` plus an explicit type column, and a connection picks its
protocol from the stored type. A composed vehicle never consults the name detector again.
So this plan is not "switch to MAC" — it is "stop overriding what the address already told
us."

**But the scan list overrides it anyway.** `KableBmsRepository`'s scan calls
`BmsTypeDetector.detect` and `detectController` on **every** advertisement and puts the
results on `DiscoveredDevice.bmsType` / `.controllerType` — including for a device already
in a saved vehicle. The saved vehicle is fetched in the same function, three statements
earlier (`knownAddresses`), and carried on the same object as `knownVehicle`, where it
informs the label and nothing else. **The app has the answer in hand at the moment it
guesses.**

**What the guess gets wrong, concretely.** `0xFFE0` is claimed by three different devices —
JK, ANT and Begode — separated by advertised **name prefix**, with JK as the fallback for
anything unmatched. And `detectController` returns null for anything `detect` matched, so a
device misfiled as a battery is not merely mislabelled: it is offered the wrong adds.
Two live cases on this project's own hardware:

- **An ANT smart BMS that does not advertise an `ANT…` name arrives as a JK BMS.**
- **A stock VESC BLE module.** Nordic UART is checked *after* every BMS name prefix, and
  deliberately — the ordering protects a real case, a VESC retrofit inside a Begode shell
  named `GW-VESC`, which would otherwise be handed both a battery type and a controller
  type. The ordering is therefore not reversible; the fix has to come from precedence, not
  from reordering.

**Why names are the wrong key at all.** `VESC BLE UART` is the stock name for the commonest
module in this app's audience, and almost nobody renames it. A rule that keys on a string
the vendor chose and the rider never changes cannot distinguish two devices, and cannot
survive a vendor changing it.

## What this plan deliberately does not do

**It does not make the address decide what an unseen device is.** An OUI names the chip
vendor — Espressif is equally a head unit, a BMS bridge, or something unrelated — so it
identifies a manufacturer, not a role. The address is the right key for *remembering* an
answer, never for *deriving* one. Detection keeps its job; it just stops outranking
knowledge.

---

## Global Constraints

1. **Non-vacuity proven per assertion, in both directions.** Every mutation killed by some
   assertion, **and** every assertion killable by some implementation. Delete any that no
   implementation could falsify and say so.
2. **A zero-failure mutation run is not evidence unless the build compiled *and* the test
   count is non-zero and exactly right.** Four sweeps on this project reported false passes.
   Bytecode-changing control with a **fresh nonce per run**, results directory wiped, count
   asserted. Reuse the harness at
   `.superpowers/sdd/2026-07-30-vehicle-platform-I-field-fixes/t8sweep.ps1`, which was
   audited line by line against all four modes.
3. **Sweep your own additions.** Twelve implementers on this project have shipped guards
   indistinguishable from their absence, each finding it only this way.
4. **A fixture where every contributor is complete cannot see an incompleteness bug.** Where
   a contract concerns absent data, the fixture must be *deliberately incoherent*.
5. **Never write to Begode's FFE1 characteristic** — it is the wheel's command channel
   (light, pedal mode, tiltback). This part makes type precedence changeable, so it is the
   part most able to point the wrong protocol at a wheel. See Task 3.
6. **The battery path must not change behaviour** except where a task names it. The picker's
   own three-list routing in `DefaultPickerComponent` is the pinned battery path and is
   explicitly out of scope.
7. **`runTest` hazard:** a test starting an unbounded delayed loop makes virtual time advance
   forever and **wedges the build instead of failing**.
8. **Compose UI is not unit-testable here** (no Robolectric, no `compose-ui-test`, no
   instrumented source set). Every decision goes in pure/component code; the `@Composable`
   layer stays a thin renderer.
9. Russian UI strings in **both** `values/` and `values-ru/`. Compose Multiplatform does
   **not** process Android backslash escapes.
10. Run `.\gradlew.bat :composeApp:testDebugUnitTest` green before every commit.

**Address stability, stated so it is not assumed silently.** This plan treats a BLE address
as a stable identity. That holds for public and static-random addresses, which is what BMS
modules, VESC BLE modules and head units use. It does **not** hold for peripherals using
resolvable private addresses, which rotate — such a device would simply never match a
remembered entry and fall back to detection, which is the correct degradation, but no test
can prove the rotation case in this repo. Say so rather than implying coverage.

---

## File Structure

| File | Responsibility | Tasks |
|---|---|---|
| `data/ble/KableBmsRepository.kt` | the scan: prefer knowledge over the guess | 1 |
| `domain/repository/BmsRepository.kt` | `DiscoveredDevice` gains the provenance of its type | 1 |
| `presentation/picker/SourceScan.kt` | the adds a device offers follow the resolved type | 1 |
| `data/db/DeviceTypeMemoryRow.sq` (new) + migration + snapshot | remembered corrections | 2 |
| `data/db/SqlDelightVehicleRepository.kt`, `domain/repository/VehicleRepository.kt` | the accessor | 2 |
| `presentation/vehicle/VehicleEditComponent.kt`, `presentation/picker/…` | record the rider's choice | 2 |

**Migration numbering:** at the time of writing, migrations run `1.sqm`–`8.sqm` and
snapshots `1.db`–`9.db` (Part I Task 9 added the last pair). **Verify this before writing
one** — `N.sqm` migrates `N.db` to `(N+1).db`, and this is the single most common thing to
get wrong here. Note also that `DROP COLUMN` is unavailable: it needs SQLite 3.35 and this
app's `minSdk 26` ships 3.19, so schema removal is a table rebuild.

---

### Task 1 — a device a vehicle already claims is not a guess

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt`
  (the `scanAll` advertisement collector: `knownAddresses` is already built there)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/BmsRepository.kt`
  (`DiscoveredDevice`)
- Reference: `data/bms/BmsTypeDetector.kt`, `presentation/picker/SourceScan.kt`
- Test: the repository's scan tests, `SourceScanTest`, `BmsTypeDetectorTest`

**Interfaces:**
- Produces: `DiscoveredDevice` carrying the *resolved* type plus how it was resolved.

**This task needs no new storage.** Everything it uses is already loaded in the function
that currently ignores it.

- [x] **Step 1: Write the failing tests.**
      (a) an address belonging to a saved vehicle's controller reports that vehicle's stored
      `ControllerType`, even when the detector would have said something else — build the
      fixture so the two genuinely disagree, or the assertion cannot fail;
      (b) the same for a saved pack's `BmsType`;
      (c) a device a saved vehicle claims as a **controller** does not also arrive with a
      `bmsType`, and vice versa — the detector's mutual exclusion must survive;
      (d) an address no vehicle claims is unchanged from today, detector verbatim;
      (e) a vehicle claiming one address must not resolve a *different* address — the
      obvious wrong implementation is "any saved vehicle exists, so trust it".

- [x] **Step 2: Run them and watch them fail.**

- [x] **Step 3: Implement.** Resolve from `knownAddresses` first, fall back to the detector.
      Keep the detector call — it is the fallback and its rules do not change.

- [x] **Step 4: Carry the provenance, do not infer it.** `DiscoveredDevice` gains a field
      saying whether its type was *remembered* or *detected*. Task 3 needs it to decide what
      may be written to a device, and a renderer needs it to stop showing a guess as a fact.
      A consumer must not have to re-derive this by checking `knownVehicle != null`.

- [x] **Step 5: Follow it through the adds.** `addControllerType` / `addBmsType` currently
      fall back to `ControllerType.VESC` and `BmsType.JK_BMS` for an unrecognised device.
      A resolved type must reach them; the fallbacks stay for genuinely unknown devices.

- [x] **Step 6: Sweep, full suite, commit.**

```bash
git commit -m "fix(scan): the app already knew what that device was"
```

---

### Task 2 — a correction made once is made once

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/DeviceTypeMemoryRow.sq`
  plus the next migration and snapshot
- Modify: `data/db/SqlDelightVehicleRepository.kt`, `domain/repository/VehicleRepository.kt`
- Modify: the composer and picker sites where a rider picks a type
- Test: the repository tests, a migration test

**Why a second table rather than a column.** The subject is an *address*, not a vehicle: the
memory has to survive a device being removed from a vehicle, or never having been in one.
Part I Task 9 moved learned dial ranges out of the vehicle row for the same reason and is
the local precedent for shape, migration and accessor.

- [x] **Step 1: Write the failing tests.** A rider's explicit choice for an address is
      returned on the next scan; it outranks the detector; it is **outranked by** a saved
      vehicle's own row (Task 1's source), because a composed vehicle is a stronger statement
      than a sheet selection; removing the device from every vehicle leaves the memory
      intact; and the memory is per address, not per name.

- [x] **Step 2: Run them and watch them fail.**

- [x] **Step 3: Migrate.** New table keyed by address. **Check whether
      `PRAGMA foreign_keys` is enabled before relying on any cascade** — Part I Task 9 found
      it provably off, with stock `AndroidSqliteDriver`/`JdbcSqliteDriver` and no executable
      `PRAGMA` anywhere in the repository. If that is still true, there is nothing to cascade
      from here anyway: this table is deliberately not owned by a vehicle.

- [x] **Step 4: Record the choice where the rider makes it**, not where it is consumed.

- [x] **Step 5: Sweep, full suite, commit.**

```bash
git commit -m "feat(scan): remember what the rider told us about this address"
```

---

### Task 3 — precedence must not be able to point a protocol at the wrong wheel

**Files:**
- Modify: `data/bms/BegodeProtocol.kt` (the never-write rule's home), and whichever site
  Tasks 1-2 made capable of changing a type
- Test: the protocol tests and the new precedence tests

**Why this is its own task and not a step.** Constraint 5 exists because FFE1 is a Begode's
command channel — light, pedal mode, tiltback — and a stray write can reconfigure a wheel
under its rider. Until now the rule was safe structurally: `BegodeProtocol` returns empty
handshake and poll command lists, and nothing else spoke FFE0. Tasks 1 and 2 make the
*type* of an FFE0 device changeable by remembered state, which is exactly the mechanism that
could point a command-sending protocol at a wheel.

- [ ] **Step 1: Write the failing test.** A remembered or corrected type must never cause a
      write to FFE1 on a device whose live advertisement says Begode. State the rule where
      the write would happen, not only where the type is chosen — a guard in the chooser is
      a guard the next chooser will not have.

- [ ] **Step 2: Run it and watch it fail.**

- [ ] **Step 3: Implement the guard at the write.**

- [ ] **Step 4: Sweep it specifically.** This is the one guard in this plan whose absence is
      not visible in any readout: mutate it out and confirm a test dies. A guard that cannot
      be killed is not a guard.

- [ ] **Step 5: Full suite, commit.**

```bash
git commit -m "fix(begode): a remembered type must not become a write to the wheel"
```

---

## Open questions

1. **Should a remembered type be visible and clearable to the rider?** A wrong memory is
   worse than a wrong guess, because it does not go away by itself. Somewhere to see and
   forget it is probably required, but it is a UI decision and belongs in a brainstorm, not
   here.
2. **Should the connected-device seed participate?** `DefaultPickerComponent` seeds itself
   with the connected peripheral because it stops advertising. That path is the pinned
   battery path and is out of scope, but it is the one place a device appears without a
   fresh advertisement, so its type has no detector output at all.
