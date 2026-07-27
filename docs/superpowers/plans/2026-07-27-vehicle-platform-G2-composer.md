# Plan — Part G2: the vehicle composer

Spec: `docs/superpowers/specs/2026-07-24-vehicle-platform/G-vehicle-composer.md`
(§8/§8.1 and §9–§9.3 are binding — they carry defects found in Parts D and F.)

Branch: `feat/vehicle-composer`, forked from `main` at Part D's merge (`d025eb5`).

---

## Why this part, and why now

The product owner's scooter is **two uBox controllers on CAN plus two ANT packs,
read through a head-unit gateway**. Part C's engine handles exactly that, with
tests. **There is no way to describe it.** `VehicleEditComponent`'s own KDoc says
"G1 supports exactly one controller per vehicle", and its save path says
controllers and topology are "not editable from this screen yet". A rider can
create a one-source vehicle by tapping a discovered device; everything beyond
that exists only in tests and seeded databases.

So G2 is not the polish it looked like from the outside. It is the only thing
standing between a working multi-source engine and a rider who can use it.

## What G1 already delivered

Tapping a discovered device creates a vehicle with **one** controller, connects,
and opens Ride. Part D extended that to a wheel (`wheelVehicle` — one controller
beside one stored Begode pack at one address). The picker offers every
`ControllerType`; the support gate is *derived* from the protocol factory, so it
cannot offer a type the factory refuses.

## The defect this part must fix before it adds anything

`VehicleEditComponent.onSave()` **rebuilds the vehicle from scratch** with
`singlePackVehicle(...)` and hand-copies selected fields from the loaded one.
Every field not in that copy list is silently reset on any save — rename the
vehicle, change the secondary gauge, edit pole pairs, and the field is gone.

It has already eaten three: `controllers`/`topology`, then `yieldBmsToHeadUnit`,
then `motionAlerts`. Each was fixed by adding a line to the copy list. **The
polarity is the defect**: a new field is lost by default rather than preserved by
default, so every field this part adds is a data-loss bug until someone
remembers this file. Part D made it worse by navigating straight to this screen
after a Controller pick (§8.1).

Part F left the test that catches the *class*: `saving with nothing edited is an
identity on the whole vehicle`. Keep it, and know its one weakness — it is only
as strong as its fixture.

---

## Global Constraints

1. **Non-vacuity proven per assertion, in both directions.** Every mutation killed
   by some assertion, **and** every assertion killable by some implementation.
   Delete any that no implementation could falsify and say so.
2. **A zero-failure mutation run is not evidence unless the build compiled *and*
   the test count is non-zero and exactly right.** On this project one sweep ran
   Gradle through a call Windows resolved against `PATH` and reported "compiled,
   0 failures" across 26 runs **having never started Gradle**; another scored
   stale XML as passes; a third was served `UP-TO-DATE` for a control value
   reused in the same session; a fourth declared all 51 mutants surviving because
   Gradle never ran. Bytecode-changing control with a **fresh nonce per run**,
   results directory wiped, count asserted. Never two sweeps at once.
3. **Sweep your own additions.** Eleven implementers here have shipped guards
   indistinguishable from their absence, each finding it only this way.
4. **An equivalent-mutant verdict argued from one fixture shape is not a verdict.**
   Ask which *other* topology reaches the same line. Two such verdicts have been
   overturned on this project, one of them silently overwriting a VESC's telemetry.
5. **A fixture that leaves fields at zero cannot prove they are carried**, and a
   test comparing only terminal values cannot see loss.
6. **The composer must never emit a config `planLinks` rejects** — conflicting
   protocol kinds at one direct address (`A §4.4`). Prevent it in the UI, not by
   catching an exception.
7. **The battery path must not change behaviour.** Riders depend on it.
8. **`runTest` hazard:** a test starting an unbounded delayed loop makes virtual
   time advance forever and **wedges the build instead of failing**.
9. Compose UI is not unit-testable here (no Robolectric, no `compose-ui-test`, no
   instrumented source set). Every decision goes in component/pure code; the
   `@Composable` layer stays a thin renderer. Do not write a test that dresses up
   an unverifiable claim — say plainly what needs a device.
10. Russian UI strings in **both** `values/` and `values-ru/`. Compose
    Multiplatform does **not** process Android backslash escapes.

---

## Tasks

### Task 1 — update in place, not rebuild

Replace `onSave`'s rebuild with a `copy()` on the loaded `Vehicle`, so a field is
**preserved by default and lost only deliberately**. The create path still needs
a constructor, but editing must never go through it.

- Keep `saving with nothing edited is an identity on the whole vehicle` passing,
  and strengthen its fixture: every field non-default, so a new field with a
  default the fixture never overrides cannot slip through.
- Delete the copy list and the comment explaining it. If the comment is still
  needed after the rewrite, the rewrite is wrong.
- §8.1: the guard on `existing.packs.isEmpty()` must not drop a wheel's
  auto-filled second branch.

Nothing else in this part is safe until this lands.

### Task 2 — the composer's model and validation

A vehicle is **N sources**. Build the component state and the pure rules:

- add / remove / reorder controllers and packs; a vehicle must keep at least one
  source (`Vehicle`'s own `init` requires it — the UI must not be able to reach
  the exception);
- **validation that prevents `planLinks` rejection at the UI layer**: two
  different protocol kinds at one direct address is the named case (`A §4.4`);
  find the others by reading `planLinks` rather than trusting this list;
- **the derived-battery rule (§6)**: `providesDerivedBattery` defaults true for a
  controller **iff** no other battery source covers it, recomputed whenever the
  source set changes, surfaced as an editable toggle with that default. Adding a
  BMS turns a controller's derived battery off; removing it turns it back on
  **unless the rider overrode it** — decide how an override survives a recompute
  and state it, because "recompute" and "the rider chose" collide here.

### Task 3 — the composer screen

Per-source editing, following the existing Compose/Decompose patterns exactly:

- **controller**: type (auto-detected, editable), label, `MotorConfig`
  (pole pairs, wheel diameter, gear ratio), `providesDerivedBattery` toggle,
  `canId` (advanced);
- **pack**: type, label, cell count (auto-filled from telemetry — do not make the
  rider type what the app already learned), `canId`/hosted (advanced),
  `aliasGroup` (Task 4);
- **vehicle**: name, icon, `topology`, `chemistry`, and links to the existing
  alert and unit screens rather than duplicating them.

Advanced fields (`canId`, hosted, alias) stay behind a disclosure — the one-source
case must not get harder to serve the two-uBox case.

### Task 4 — alias groups and duplicate resolution (`01-linking §4`)

- **Explicit grouping** first: the rider marks two battery sources as one physical
  pack, which is what the head-unit + direct-ANT case needs. This is the part that
  matters; ship it even if the heuristic below is deferred.
- **Duplicate warning** (§5): flag when two battery sources look like the same
  physical pack — same series-cell count plus voltage tracking within a tight band
  over several samples, or an identical reported serial — and offer the grouping
  rather than double-counting. **Spec §9.2 flags the false-positive risk**: two
  genuinely similar packs must not be nagged about. Pick the window and the sample
  count deliberately, state them, and test both directions — a real duplicate
  caught, two similar-but-distinct packs left alone.
- The **"yield BMS to head unit while riding"** toggle (C §5) belongs to such a
  group and defaults on. It already exists on `Vehicle`; surface it here.

### Task 5 — CAN discovery

`PING_CAN` against a connected gateway lists slave controllers and hosted BMS.
Present them **friendly** ("Контроллер 2", "батарея за приборкой") with the raw
CAN ids behind an advanced view (§9.1).

- **Never auto-add.** The rider includes each device explicitly — the spec says so
  twice and it is the difference between a composer and a guess.
- Part C pinned the firmware behaviour from source: `PING_CAN` **blocks the head
  unit for about 2.5 s and silently swallows a second request**. The UI must not
  let a rider fire it twice, and must show that something is happening for the
  whole window.
- Discovery needs a live connection. Say plainly what the screen does when there
  is none.

### Task 6 — the unknown-vs-zero rendering contract (§9, §9.1, §9.3)

Part F taught the *alarm* that a value we have not observed is not a zero. The
dashboard and the aggregator never learned it. Three fields already show it:

- **duty** — `hasDuty` is read nowhere in `presentation/`, so a wheel whose
  firmware never reported a PWM shows a confident **0 %** on the dial while the
  alarm correctly refuses to arm;
- **power** — an unavailable voltage scale renders **"0.0 kW"**;
- **consumption** — `sessionWhPerKm` returns null only when `tripKm <= 0`, so a
  Begode reads **"0.0 Wh/km"** for the whole ride.

Fix the **contract once**, not the three gauges: a known-flag that reaches every
motion gauge, and a rendering for "unknown" visibly different from a real low
reading. §9.3: `MotionAggregator` folds `inputVoltageV` with `average()`, so a
`0`-meaning-unknown halves the rail voltage on a mixed vehicle — the fold needs
the same notion, or must skip unknowns.

### Task 7 — gauge ranges follow the vehicle (§9.2)

Classic's CURRENT dial floors at ±60 A and POWER at ±10 000 W — VESC's ranges. A
wheel cruises at ~6 A and ~571 W, so both needles live in the first tenth of
scale and never visibly move. VESC itself derives its ranges from `mcconf`
(`B §14`), which is the same question one layer down. Decide where the range
comes from and make it follow the vehicle.

### Task 8 — the vocabulary is battery-centric and lies

"+ Добавить батарею", "Ищем ваши батареи", "Новая батарея", "Мои батареи ·
рядом" — on a vehicle with no battery at all, every one of these is false. Rename
across both locales, and extend `IconKey` with vehicle archetypes (scooter, EUC,
bike) instead of the current battery icons (§9.3).

Mechanical, but it is the first thing a new rider reads, and it currently
describes a product this no longer is.

---

## Out of scope

The transports themselves (E, H); controller writes of any kind; the
`VehicleConnection` staleness sweep (`F §14`); `AlertEngine`'s re-arm on a
changed fault set (`F §16`); the aggregator's dropped `batteryLevelFraction`
(`A`) — except where Task 6's contract naturally covers it, in which case say so.

---

## Amendments after Task 3 (2026-07-27)

**Execution order changes: Task 5 runs before Task 4.** Task 3 shipped a screen on
which adding a second source means **typing a BLE MAC by hand** — the composer
cannot learn an address, because scanning and CAN discovery live in Task 5. That
makes spec §3's flow 2 (controller + BMS) painful and flow 3 ("+ Wheel" as one
add) impossible: the single add's whole value is that both sources land on **one
link**, and there is no link to give them. Task 4's alias grouping also assumes
both sources already exist. So discovery is not a garnish on the composer — it is
what makes it usable, and it comes first.

**Spec §3 flow 3 stays open.** Task 3 deferred it and the deferral is accepted,
but the reasoning was corrected in review: a "+ Wheel" that seeds two sources with
a shared blank link is not merely a tap saved, it **records that the two sources
are one device** — knowledge the rider has and the app cannot infer. Task 5 should
deliver it once an address exists.

### Task 9 — an unsaved composer must not vanish silently

Found in Task 3's review. `goTo` now keeps a buried form alive, but from a
relocated Settings the tab bar hides it with no sign it exists, and the
Dashboard's disconnect does `replaceAll(Config.Scanning)`, which **destroys the
whole stack including the unsaved edit**. One action away, silent.

`VehicleAlertsComponent` already has the shape this needs — an `isDirty` notion
and a discard prompt. `VehicleEdit` should have the same, and the composer's
unsaved work is worth more than a threshold edit's: it can be a dozen fields
across several sources.

Left out of Task 3 deliberately — every real fix crosses component boundaries,
and widening a screen task into a navigation task is how scope creep starts.

### Task 4 gains: the phantom head-unit controller (2026-07-27)

Found in Task 5, and its harm was understated there before review corrected it.

The picker creates a **controller row for the head unit** with `canId == null`. On
the product owner's scooter the head unit is a **display, not an ESC**, so that
row polls a motor that does not exist — and nothing prompts him to remove it.

It is not merely useless:

- `MotionAggregator` folds `inputVoltageV` with `average()`. If the Express
  answers `GET_VALUES` with a 0 V rail, a three-controller vehicle reports **two
  thirds of its real pack voltage** on the dashboard, and that number feeds the
  alert engine — `G §9.3`'s open defect class, reached by an ordinary setup.
- If it answers nothing it stays offline and is correctly excluded from the fold,
  but `MotionResult.partial` is permanently true, the gateway burns a reply
  timeout on it every cycle, and it becomes **a permanently-silent extra source
  on the head-unit link** — exactly the precondition a timed-out CAN scan needs
  to trip the 5 s stale-sample watchdog. The row *manufactures* that hazard.

So Task 4 adds an advisory to `validate`: a controller that is a gateway (null
`canId` on a link that carries CAN-addressed sources) and has never reported
motion is probably a display, not an ESC. Advisory, not blocking — a head unit
that *is* an ESC is a legitimate build.

**Before implementing, ask what the hardware actually does:** whether the head
unit answers `GET_VALUES` at all, and with what `inputVoltageV`. That decides
whether this is a wrong dashboard voltage or merely a wasted poll, and the answer
changes the wording of the advisory.
