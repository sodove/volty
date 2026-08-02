# Part L — The Setup Wizard: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A rider builds a vehicle of any shape — wheel, scooter, bicycle, gateway with CAN
slaves — from a first-run wizard that scans, explains, and shows them what they have built,
without ever needing a second app to identify a device.

**Architecture:** One `VehicleDraft` owned from the first step instead of after the first
successful connect. Stages are a Decompose child stack over that one draft; every stage is a
pure projection of it, and the wizard's final act is the only write. A diagram of the draft —
phone, links, and what hangs off each link — is a pure function rendered at the end and
reachable from any stage.

**Tech Stack:** Kotlin Multiplatform (Android target), Compose Multiplatform, Decompose, Koin,
Kable (BLE), SQLDelight, kotlinx-coroutines, kotlin.test + Turbine.

**Evidence base:** `field-reports/2026-08-01-second-hardware-test.md` §O1, and the rider's own
words: *"надо нормальный многоэтапная 'первая' настройка, где отдельно настраиваешь контры,
отдельно добавляешь бмс (или жмешь что тянуть с контра)"* — and, on the current one,
*"кнопку я не увидел, заныкано жестко"*.

---

## Why this is mostly wiring, and where the real work is

**Every mechanism already exists, is tested, and is pure.** The 2026-08-01 trace established
this specifically: `DeviceScanSheet` is a complete BLE picker; `SourceScan.kt` has role
labelling, per-device add offers and scan-hit folding; `VehicleDraft` has
`addController`/`addPack`/`addWheel`/`addCanController`, `validate()` and `issuesBySource`;
CAN discovery works and is wired; there are 26 scan/add tests already.

**The gap is structural, and it is one line wide:** `canComposeSources = isEditing`. The scan,
both add buttons, the CAN section and the whole source band are gated on editing an
*already-created* vehicle. A rider building one from scratch reaches a create form whose
address is a read-only row — so the first place they can name a second device is a free-text
field, which is what sent them to nRF Connect.

**So the real work is three things, none of them the add mechanics:**

1. **A create path that owns a draft** — the wizard, replacing `singlePackVehicle` as the way
   a new vehicle comes into being.
2. **Making a device identifiable** — an address shown wherever a device is chosen, because
   the picker currently truncates it to four characters and that alone is a standing reason to
   open nRF Connect.
3. **Making the draft legible** — the visualisation the rider asked for, so that "what did I
   just build" has an answer that is not a list of cards.

---

## Global Constraints

1. **Non-vacuity proven per assertion, in both directions.** Every mutation killed by some
   assertion, **and** every assertion killable by some implementation.
2. **A zero-failure mutation run is not evidence unless the build compiled *and* the test count
   is non-zero and exactly right.** Four sweeps on this project reported false passes. Reuse
   the harness at `.superpowers/sdd/2026-07-30-vehicle-platform-I-field-fixes/t11sweep.ps1`,
   audited three times.
3. **Sweep your own additions.**
4. **Where a contract concerns absent data, the fixture must be deliberately incoherent.**
5. **Never write to Begode's FFE1 characteristic.** A wizard that offers to "test" a device
   must not turn that into a write on a wheel.
6. **The battery path must not change behaviour** except where a task names it.
7. **`runTest` hazard:** an unbounded delayed loop wedges the build instead of failing. A
   wizard that holds a live scan is exposed to this.
8. **Compose UI is not unit-testable here** (no Robolectric, no `compose-ui-test`, no
   instrumented source set). **This binds this plan harder than any before it**, because the
   deliverable is a UI. Every decision — which stage is reachable, what a stage may do, what
   the diagram contains — goes in the component or in a pure function. The `@Composable` layer
   renders a model it is handed and holds no rule. Where something can only be judged on a
   device, say so plainly rather than writing a test that dresses it up.
9. Russian UI strings in **both** `values/` and `values-ru/`. Compose Multiplatform does
   **not** process Android backslash escapes.
10. Run `.\gradlew.bat :composeApp:testDebugUnitTest` green before every commit.

---

## The shape, decided

### Stages

**A rider can leave at any stage and come back; nothing is written until Save.** Part G2 Task 9
("an unsaved composer must not vanish silently") is a prerequisite and its draft-survival
mechanism is what this relies on — do not build a second one.

| # | Stage | What it does | Skippable |
|---|---|---|---|
| 1 | **What are we building** | Wheel / scooter / bicycle / custom. Sets defaults and the wording of later stages. **A hint, never a lock** — every later stage offers everything regardless. | yes → custom |
| 2 | **Controllers** | Scan, pick, name. Per controller: wheel geometry, and — if the device is a gateway — CAN discovery for the nodes behind it. | yes → no controller |
| 3 | **Battery** | Three exits: scan and pick a BMS; **"take it from the controller"** (the derived pack); or "this device is both" for a wheel. | yes → no battery |
| 4 | **Review** | The diagram. Issues from `validate()` shown against the thing they belong to. | no |

**Stage 1 is a hint and this must be enforceable.** The failure mode to design against is a
wizard that decides a rider with a "scooter" cannot add a second battery. Every stage offers
every add; stage 1 only changes defaults and copy.

### The three battery exits, and why they are three

- **A BMS of its own** — a separate BLE link, the bicycle's ANT.
- **Derived from the controller** — `Controller.providesDerivedBattery`. This already exists
  and is what Part I Task 5 spent itself on. The rider named this exit themselves.
- **The device is both** — one address, two roles: `addWheel`, `G §3` flow 3, the EUC.

These are not a radio-button preference; they produce structurally different drafts. The
wizard's job is to make that difference visible before it is saved, which is stage 4's job.

### The visualisation

A tree, because that is what the domain is: **phone → BLE links → sources on each link**, with
CAN-forwarded nodes as children of their gateway. It must show, per node: the role
(controller / battery / both), the type, **the address**, and any `ComposerIssue` attached to
it.

**It is a pure function of the draft** — `fun draftDiagram(draft: VehicleDraft): DiagramNode` —
and that is what makes it testable at all under constraint 8. The composable walks the tree
and draws it; every decision about what appears, what nests under what, and what is flagged
lives in the function.

**It is not decoration.** It is the answer to "did I just build one vehicle with two links, or
two vehicles?", which is exactly the confusion the current cards cannot resolve.

---

## File Structure

| File | Responsibility | Tasks |
|---|---|---|
| `presentation/vehicle/wizard/SetupWizardComponent.kt` (new) | the stage stack over one draft | 2 |
| `presentation/vehicle/wizard/SetupWizardScreen.kt` (new) | renderer only | 2, 3, 4 |
| `presentation/vehicle/DraftDiagram.kt` (new) | `draftDiagram(draft)` — pure | 5 |
| `presentation/vehicle/VehicleComposer.kt` | draft algebra; `newVehicleFromDraft` | 1 |
| `presentation/vehicle/VehicleEditComponent.kt` | `canComposeSources` stops meaning `isEditing` | 1 |
| `presentation/picker/PickerScreen.kt` | show the address | 3 |
| `RootComponent.kt` | the create entry points route to the wizard | 2 |

---

### Task 1 — a new vehicle is a draft, not a special case

**Files:**
- Modify: `presentation/vehicle/VehicleComposer.kt`, `presentation/vehicle/VehicleEditComponent.kt`
- Test: `VehicleComposerTest`, `VehicleEditComponentTest`

**Why first.** Every other task in this plan is blocked on it, and on its own it already fixes
the reported defect: the scan becomes reachable when creating.

- [ ] **Step 1: Write the failing tests.** A draft with two links on two addresses projects to
      a vehicle with both; `canComposeSources` is true on a create path; the create path can
      add a scanned device without an address being typed anywhere. Include the case the old
      code could produce: **a create that saves with a blank address must now be impossible**,
      not merely discouraged — `BlankAddress` stops being non-blocking on this path.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement `newVehicleFromDraft`** beside `singlePackVehicle`, and flip
      `canComposeSources` off `isEditing`. Keep `singlePackVehicle` for the picker's
      one-tap-connect path — deleting it is a separate change with its own risk.
- [ ] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "fix(composer): a vehicle being created is a draft like any other"
```

---

### Task 2 — the wizard stack

**Files:**
- Create: `presentation/vehicle/wizard/SetupWizardComponent.kt`, `SetupWizardScreen.kt`
- Modify: `RootComponent.kt`
- Test: a new `SetupWizardComponentTest`

**Interfaces:**
- Consumes: `VehicleDraft` and its algebra; `onStartDeviceScan` / `onAddScannedDevice` from
  `VehicleEditComponent`, which should be **lifted into something both can use** rather than
  duplicated.

- [ ] **Step 1: Write the failing tests.** Stage order and reachability; back and forward
      preserve the draft; **skipping a stage leaves the draft valid**; a rider who picks
      "scooter" at stage 1 can still add a second battery at stage 3 (the hint-not-lock rule,
      and the one most likely to be got wrong); the wizard writes nothing until Save; leaving
      mid-wizard and returning restores the draft (via G2 Task 9's mechanism, not a new one).
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement the stack.** Decompose child stack, one component per stage, all over
      one draft held by the parent.
- [ ] **Step 4: Route the create entry points to it** — the three that currently land on the
      create form.
- [ ] **Step 5: Sweep, full suite, commit.**

```bash
git commit -m "feat(composer): a first setup that starts with a scan, not with a MAC"
```

---

### Task 3 — a device you can actually identify

**Files:**
- Modify: `presentation/picker/PickerScreen.kt`, the wizard's device list
- Test: the picker tests, `SourceScanTest`

**Why this is its own task.** It is the half of the reported defect that has nothing to do with
the wizard: *"пришлось лезть в nrf connect копировать mac бмски"*. Even with a perfect wizard,
a rider choosing between three unnamed devices needs the address, and the picker currently
renders `BMS ` + the last four characters. The composer's own scan sheet already shows the full
address with a comment saying it is what the rider would otherwise have to type — **the two
screens disagree, and the wrong one is the one riders see first.**

- [ ] **Step 1: Write the failing tests** for the pure label function: a named device shows its
      name and address; an unnamed one shows the full address; two devices differing only in
      address are distinguishable by what the function returns. That last one is the assertion
      that actually encodes the defect.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement**, and show RSSI as proximity — a rider identifying one of three
      identical BMS boxes does it by walking closer, and the app has that number already.
- [ ] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "fix(picker): the one fact that tells two devices apart was truncated"
```

---

### Task 4 — the three battery exits

**Files:**
- Modify: the wizard's battery stage; `VehicleComposer.kt` if `addWheel` needs a companion
- Test: `SetupWizardComponentTest`, `VehicleComposerTest`

- [ ] **Step 1: Write the failing tests.** Each exit produces the draft it should: a separate
      link; `providesDerivedBattery` on the controller with no second link; one address in two
      roles. And the negative: choosing "derive from controller" **with no controller in the
      draft** must be unreachable rather than producing a vehicle with a battery that nothing
      backs.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Strings in both locales** for the three exits and their explanations. The
      derived exit needs a sentence a rider can act on — it is the one that trades a real fuel
      gauge for a computed one, and Part I Task 5's warm-up means it can be briefly absent at
      connect.
- [ ] **Step 5: Sweep, full suite, commit.**

```bash
git commit -m "feat(composer): three ways to have a battery, and they are not the same vehicle"
```

---

### Task 5 — the diagram

**Files:**
- Create: `presentation/vehicle/DraftDiagram.kt`
- Modify: the wizard's review stage; the edit screen (reachable from there too)
- Test: a new `DraftDiagramTest`

- [ ] **Step 1: Write the failing tests** against `draftDiagram(draft)`: a gateway's CAN nodes
      nest under it; a wheel appears once with both roles rather than twice; two links on two
      addresses are siblings; an issue attaches to the node it belongs to; **a draft with a
      duplicate address renders the duplication visibly** rather than silently merging.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement the pure function.**
- [ ] **Step 4: Render it.** The composable walks the returned tree and holds no rule.
      Per constraint 8, say plainly in the report that the rendering itself needs a device.
- [ ] **Step 5: Sweep, full suite, commit.**

```bash
git commit -m "feat(composer): show the rider what they just built"
```

---

## Prerequisites and ordering

- **Part G2 Task 9** (unsaved composer must not vanish) is a **prerequisite of Task 2** — the
  wizard's back/leave behaviour rests on it.
- **Part G2 Task 8** (the vocabulary is battery-centric and lies) should land **before Task 3**,
  or Task 3 will write labels that Task 8 then rewrites.
- **Part K** (device identity: knowledge outranks detection, corrections remembered) is
  **complementary and independent**. Task 3 makes a device identifiable *by address*; Part K
  makes the app stop re-guessing its type. The rider's ANT arriving as a JK is Part K's, not
  this plan's — but a wizard that hides the type behind a guess would make it worse, so
  **stage 2 and stage 3 must both let the rider change the type**, which is Part K's "detection
  is a hint" rule applied here.

## Out of scope, deliberately

- **Reworking the edit screen's source cards.** They work. The wizard is for creation; editing
  keeps its current shape plus the diagram.
- **Deleting `singlePackVehicle`.** The picker's one-tap connect is a pinned battery path.
- **Any change to detection.** Part K owns that.
- **A "test this device" button** that would connect during the wizard. Tempting, and it
  collides with Global Constraint 5 and with single-central peripherals; it wants its own
  design.
