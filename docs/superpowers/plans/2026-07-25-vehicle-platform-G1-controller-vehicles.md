# Part G1 — Controller-bearing vehicles (unblock real hardware) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make it possible to add a real controller (a uBox / VESC) through the UI and ride with it. Today the app detects a VESC and even labels it in the scan list, but every creation path builds a single-pack BMS vehicle — so Part B's protocol, dashboards and motion pipeline are reachable only from the demo vehicle and tests. After G1, tapping a discovered VESC creates a controller-bearing vehicle, connects, and lands on the Ride dashboard with live data.

**Architecture:** Two halves that must land in this order. **First** make a zero-pack `Vehicle` survivable everywhere — the domain already allows it (`Vehicle.init` requires "a source", not "a pack"; the DB and `KableBmsRepository` are already hardened) but the presentation layer is riddled with `packs.first()` shims that throw. **Then** add the creation path. Reversing this order bricks the app: two of the landmines fire during cold start, for every saved vehicle, not just the new one.

**Tech Stack:** Kotlin 2.3.20, Compose Multiplatform (Android target), Material 3, Decompose, Koin, SQLDelight.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-24-vehicle-platform/G-vehicle-composer.md`, plus the handover in `B-vesc-dashboard.md §6.1` and the debt list in `B-vesc-dashboard.md §12.5`. Read `01-linking.md §5` (recognising a saved vehicle by its address set) before Task 2.
- Branch `feat/vehicle-composer-g1`, off `main` (Part B closed at `2adda50`).
- Package root `ru.sodovaya.volty`; tests `kotlin.test` + Turbine. **536 tests are green at branch point — all of them stay green.**
- **No schema migration in G1.** `ControllerRow` already exists (`3.sqm`, schema v5) and `SqlDelightVehicleRepository.upsert` already writes controllers. If you find yourself writing a `.sqm`, stop — you have misread the task.
- **The existing BMS path must not change behaviourally.** A user with only BMS vehicles must see zero difference. Verify this cumulatively, not per-task.
- **No CAN in G1.** `planLinks` throws on any source with `canId != null` ("not supported until Part C") — never set `canId`, and do not add CAN-discovery UI. Part C owns it.
- This repo has **no Compose UI test harness**. All non-trivial logic goes in pure, tested functions; composables are compile-verified. State that plainly in reports rather than claiming UI coverage.
- `./gradlew :composeApp:testDebugUnitTest` and `./gradlew :composeApp:compileDebugKotlinAndroid` must pass before each commit.
- Commit after every task with the message shown in its final step.

## File Structure

**New:**
- `domain/model/VehicleSources.kt` — safe source accessors + `allAddresses` (the testable core of Task 1).
- `domain/model/VehicleBuilders.kt` — `controllerVehicle(...)` beside the existing `singlePackVehicle(...)` (move the latter here only if it is currently a loose top-level function; otherwise leave it in place).
- Tests under `commonTest/.../domain/model/`.

**Modified (Task 2 sweep — the compiler will produce the authoritative list):**
`presentation/scanning/ScanningComponent.kt`, `presentation/picker/PickerComponent.kt`, `presentation/picker/PickerScreen.kt`, `presentation/vehicle/VehicleEditComponent.kt`, `presentation/vehicle/VehicleEditScreen.kt`, `presentation/dashboard/DashboardScreen.kt`, `presentation/dashboard/VehicleSheet.kt`, `presentation/settings/SettingsScreen.kt`, `presentation/RootComponent.kt`, `domain/model/Vehicle.kt`.

---

### Task 1: Safe source accessors (pure domain, no UI)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Vehicle.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/VehicleSources.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/model/VehicleSourcesTest.kt`

**Context:** `Vehicle.kt:85-88` currently has four extension properties built on `packs.first()`. They throw `NoSuchElementException` for a controller-only vehicle. `Vehicle.primaryAddress` (`Vehicle.kt:42-43`) is already correct and must be left alone — it falls back to `primaryController?.address`.

**Interfaces to produce** (in `VehicleSources.kt`; **delete the four throwing properties from `Vehicle.kt` entirely** — do not deprecate them, do not keep a throwing overload; the whole point is that the compiler flags every call site in Task 2):

```kotlin
val Vehicle.primaryPackOrNull: Pack? get() = packs.firstOrNull()
val Vehicle.bmsTypeOrNull: BmsType? get() = primaryPackOrNull?.bmsType
val Vehicle.bmsAddressOrNull: String? get() = primaryPackOrNull?.bmsAddress
val Vehicle.cellCountOrNull: Int? get() = primaryPackOrNull?.cellCount

/** Every BLE address this vehicle can be recognised by — packs and controllers alike. */
val Vehicle.allAddresses: Set<String>
    get() = (packs.map { it.bmsAddress } + controllers.map { it.address }).toSet()

/** True when this vehicle has no battery source of its own and a controller must derive one. */
val Vehicle.needsDerivedBattery: Boolean get() = packs.isEmpty() && controllers.isNotEmpty()
```

**Why `allAddresses` matters** (`01-linking.md §5`): scan matching currently does `saved.associateBy { it.bmsAddress }`. That is wrong for a controller vehicle in two ways — it throws, and even fixed it would key the vehicle by a pack address it does not have, so the vehicle would never match its own advertisement. Task 2 replaces those maps with an address→vehicle index built from `allAddresses`.

- [ ] **Step 1: Write the failing test**

Cover, with a controller-only `Vehicle` (zero packs, one `Controller`), a pack-only vehicle, and a mixed one:
- each `*OrNull` accessor returns `null` on the controller-only vehicle and the pack's value otherwise;
- `allAddresses` returns both the pack and the controller address for a mixed vehicle, deduplicates when a pack and a controller share an address (the legal single-link case — see `planLinks`), and is never empty for a valid `Vehicle`;
- `needsDerivedBattery` is true only for controller-only.

Build the fixtures with the real constructors; do not stub `Vehicle`.

- [ ] **Step 2: Implement**

- [ ] **Step 3: Verify the test is non-vacuous** — temporarily make `allAddresses` return only pack addresses and confirm the mixed-vehicle test fails; restore. Record the observed failure in your report.

- [ ] **Step 4: Run `./gradlew :composeApp:testDebugUnitTest`** — note that Task 1 alone will NOT compile the app (Task 2 fixes the call sites). Run `:composeApp:compileDebugKotlinAndroid` anyway and **paste the list of unresolved-reference errors into your report** — that list is Task 2's work queue and is the most valuable artifact of this task.

- [ ] **Step 5: Commit** — `feat(vehicle): source accessors that survive a zero-pack vehicle`

  Commit even though the app does not compile: Task 2 is the other half of one atomic change, and the error list is worth preserving in history.

---

### Task 2: The compiler-driven sweep — make every screen survive a zero-pack vehicle

**Files:** every file the compiler names. Expect at minimum: `ScanningComponent.kt`, `PickerComponent.kt`, `PickerScreen.kt`, `VehicleEditComponent.kt`, `DashboardScreen.kt`, `VehicleSheet.kt`, `SettingsScreen.kt`, `RootComponent.kt`.

**Context — the known landmines and their blast radius.** Do not treat this as the complete list; the compiler's list governs. This is what a prior survey found, so you can recognise each shape:

| Site | Shape | What breaks |
|---|---|---|
| `ScanningComponent.kt:63` | `saved.associateBy { it.bmsAddress }` | throws in `scope.launch` at **every cold start** — the Scanning screen never appears |
| `PickerComponent.kt:79` | same | kills Picker init in all three modes |
| `PickerComponent.kt:90,98,101` | `activeVehicle.bmsAddress`/`.bmsType` | guest-fallback `DiscoveredDevice` construction |
| `PickerComponent.kt:141` | `vehicle.bmsAddress` in `onConnectKnown` | tapping a controller vehicle |
| `PickerScreen.kt:113,247` | list key + `bmsTypeLabel(vehicle.bmsType)` | throws **during composition** — whole Picker screen, not one row |
| `VehicleEditComponent.kt:104-105` | `v.bmsType`/`v.bmsAddress` in `initialize()` | Edit screen init |
| `DashboardScreen.kt:102` | `vehicle?.bmsType?.let{}` | the `?.` guards a null vehicle, **not** empty packs — Battery tab |
| `VehicleSheet.kt:99` | `bmsTypeLabel(v.bmsType)` under an unconditional `forEach` | whole "My batteries" sheet |
| `SettingsScreen.kt:290` | same, under `state.vehicles.forEach` | whole Settings vehicle list |

**Two rules for the fixes:**

1. **Address matching must use `allAddresses`, not a single address.** Replace `saved.associateBy { it.bmsAddress }` with an index built from every address, e.g.
   ```kotlin
   val byAddress: Map<String, Vehicle> = saved.flatMap { v -> v.allAddresses.map { it to v } }.toMap()
   ```
   Do this at *both* `ScanningComponent.kt:63` and `PickerComponent.kt:79`. Without it a controller vehicle can never be recognised from its own advertisement, which would make G1 pointless.

2. **Subtitles fall back to the controller.** `RideDashboardScreen.kt:136-137` already does this correctly:
   ```kotlin
   vehicle?.primaryController?.controllerType?.label
       ?: vehicle?.takeIf { it.packs.isNotEmpty() }?.let { bmsTypeLabel(it.bmsType) }
   ```
   **Extract that into one shared helper** (`vehicleSourceLabel(vehicle): String?` — put it next to `bmsTypeLabel`) and use it at every row/subtitle site above, including `RideDashboardScreen` itself. Do not copy the expression around; three divergent copies is exactly how the Clean/Classic parity bugs happened in Part B.

- [ ] **Step 1: Get the full list** — run `:composeApp:compileDebugKotlinAndroid` and record every unresolved reference. Work that list, not this plan's table.

- [ ] **Step 2: Fix each site**, applying the two rules. Where a `String?`/`BmsType?` now flows into UI, render the controller label or omit the segment — never render "null", an empty chip, or a placeholder dash where a real label belongs.

- [ ] **Step 3: Write regression tests for the two address-matching sites.** These are component tests over `ScanningComponent`/`PickerComponent` state (Turbine), asserting a saved controller-only vehicle **is matched** by an advertisement carrying its controller address. If a component cannot be driven from a unit test in this codebase, say so explicitly in your report and instead test the pure `byAddress` index construction as an extracted function — but prefer the component test.

- [ ] **Step 4: Verify the app compiles and all 536 existing tests still pass.**

- [ ] **Step 5: Prove no BMS-path regression** — list the behavioural diff for a pack-only vehicle at every site you touched and confirm it is empty. Any site where a pack-only vehicle now renders differently is a defect unless the plan asked for it.

- [ ] **Step 6: Commit** — `fix(vehicle): every screen survives a controller-only vehicle`

---

### Task 3: The type sheet learns controllers

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/picker/PickerScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/picker/PickerComponent.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml` + `values-ru/strings.xml`

**Context:** `PickerScreen.kt:185-217` renders a `ModalBottomSheet` listing `BmsType.entries` (with `VESC_BMS` filtered out — keep that filter, it has no protocol yet). `PickerComponent.onDeviceTapped` (`:153-156`) opens it for every device, and `onConnectWithType(device, type: BmsType)` is the only action. A detected VESC is *shown* as "VESC" in `DeviceRow` but there is no way to act on that.

**Interfaces:**
- Introduce a choice type the sheet can express both halves with:
  ```kotlin
  sealed interface SourceChoice {
      data class Battery(val type: BmsType) : SourceChoice
      data class Controller(val type: ControllerType) : SourceChoice
  }
  ```
  Put it in the picker package next to the component.
- `onConnectWithType(device: DiscoveredDevice, choice: SourceChoice)` replaces the `BmsType` parameter.

**Design (decided — do not re-litigate):** one sheet, two labelled sections — **Контроллер** first, then **Батарея (BMS)**. The section matching the device's detection (`device.controllerType != null` ⇒ controller) renders first and its matching entry carries the existing `primaryContainer` highlight, so the common case is one tap. Both sections always render, because detection is a hint and a user with an unrecognised device must still be able to choose. Reuse the existing clickable-`Row` pattern verbatim; do not invent a new list style.

- [ ] **Step 1: Write the failing test** — `PickerComponent` state test: tapping a device whose `controllerType == VESC` produces a sheet state whose pre-selected choice is `SourceChoice.Controller(VESC)`; tapping a device whose `bmsType == JK` pre-selects `SourceChoice.Battery(JK)`; tapping an unrecognised device pre-selects nothing but still offers both sections.

- [ ] **Step 2: Implement** — component state + sheet. `ControllerType.entries` for the controller section (all four are legal choices even where the protocol lands later; a `FARDRIVER` pick is a valid intent, and Task 4 decides what happens on connect).

- [ ] **Step 3: Localise** — new keys in **both** `values/strings.xml` and `values-ru/strings.xml`. Verify the two files have identical key sets (they were 208/208 at the end of Part B).

- [ ] **Step 4: Run tests + compile.**

- [ ] **Step 5: Commit** — `feat(picker): choose a controller, not just a BMS`

---

### Task 4: Build a controller-bearing vehicle

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/VehicleBuilders.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/model/VehicleBuildersTest.kt`

**Context:** `singlePackVehicle(...)` is the app's only vehicle factory and is used by both `PickerComponent.kt:168-176` and `VehicleEditComponent.kt:149-182`. It hardcodes the single-pack shape.

**Interfaces:**
```kotlin
fun controllerVehicle(
    id: String,
    name: String,
    iconKey: String,
    controllerType: ControllerType,
    address: String,
    chemistry: Chemistry,
    createdAt: Instant,
    motor: MotorConfig = MotorConfig(),
): Vehicle
```
Rules it must enforce:
- exactly one `Controller` at `index = 0`, `canId = null` (**never** set a canId — `planLinks` rejects it until Part C);
- `providesDerivedBattery = true`, because a controller-only vehicle has no other battery source (`G-vehicle-composer.md §6`). Do not read this from a parameter in G1 — the rule is derivable and G2 makes it an editable toggle;
- `packs = emptyList()`;
- `dashboardStyle = null` (per-vehicle default resolves downstream), `secondaryGauge = SecondaryGauge.DUTY`.

- [ ] **Step 1: Write the failing test** — the built vehicle satisfies `Vehicle.init`; `needsDerivedBattery` is true; `allAddresses == setOf(address)`; **`planLinks(v.packs, v.controllers)` returns exactly one `LinkSpec`** carrying that controller and zero packs, and does not throw. That last assertion is the one that matters — it is the contract G1 must never violate.

- [ ] **Step 2: Implement.**

- [ ] **Step 3: Verify non-vacuity** — temporarily set `canId = 1` and confirm the `planLinks` assertion fails with the "not supported until Part C" message; restore and record it.

- [ ] **Step 4: Run tests + compile.**

- [ ] **Step 5: Commit** — `feat(vehicle): controllerVehicle() builder`

---

### Task 5: Wire creation — picker tap to a live Ride dashboard

**Files:**
- Modify: `presentation/picker/PickerComponent.kt`
- Modify: `presentation/vehicle/VehicleEditComponent.kt`
- Modify: `presentation/vehicle/VehicleEditScreen.kt`

**Context:** `PickerComponent.onConnectWithType` in mode `"add"` does: build → `vehicleRepository.upsert(v)` → `bmsRepository.connect(v)` → on success `onConnectedForEdit(v.id)`; on failure `vehicleRepository.delete(v.id)`. Keep that exact shape — including the rollback — for the controller branch.

**Two things that must be fixed here, not deferred:**

1. **`VehicleEditComponent.onSave()` rebuilds via `singlePackVehicle(...)`** (`:149-182`). For a controller-only vehicle that would **fabricate a pack out of nothing** and silently convert it back into a BMS vehicle on the first save. Branch on the existing vehicle's shape: rebuild with `controllerVehicle(...)` when it has no packs, preserving `controllers`, `topology`, `dashboardStyle`, `secondaryGauge` exactly as the current code preserves them.
2. **`VehicleEditComponent.initialize()`** (`:104-105`) reads `bmsType`/`bmsAddress` for the read-only header row. For a controller vehicle show the controller type and its address instead — reuse Task 2's `vehicleSourceLabel`.

`RootComponent.homeConfigFor` already routes to Ride when the vehicle has controllers — confirm, do not rewrite.

- [ ] **Step 1: Write the failing test** — `VehicleEditComponent` round-trip: load a controller-only vehicle, call `onSave()` with an unchanged name, assert the persisted vehicle **still has zero packs and one controller**. Verify this test fails against the current `singlePackVehicle` path before you fix it, and record the failure — this is the highest-value assertion in the task.

- [ ] **Step 2: Implement the picker branch** — `SourceChoice.Controller` ⇒ `controllerVehicle(...)` ⇒ same upsert/connect/rollback sequence.

- [ ] **Step 3: Implement the VehicleEdit fixes.**

- [ ] **Step 4: Handle the protocol gap honestly.** `createProtocol` has no implementation for `FARDRIVER`/`KELLY`/`BEGODE` controllers (Parts D/E/H). Picking one must **not** crash: surface the same connection-failure path an unreachable device takes, with a message naming the unsupported type. Add a test that a `FARDRIVER` pick fails cleanly and **rolls back the persisted vehicle** (no orphan row). Do not silently hide those types from the sheet — a user seeing "FarDriver — not supported yet" learns more than a user seeing nothing.

- [ ] **Step 5: Run tests + compile.**

- [ ] **Step 6: Commit** — `feat(picker): tapping a VESC creates a controller vehicle`

---

### Task 6: Motor configuration in Vehicle Edit

**Files:**
- Modify: `presentation/vehicle/VehicleEditScreen.kt`, `presentation/vehicle/VehicleEditComponent.kt`
- Modify: both `strings.xml`

**Context:** `MotorConfig` (pole pairs, wheel diameter mm, gear ratio) is what turns ERPM into speed on the **DERIVED** path. `COMM_GET_VALUES_SETUP` reports speed directly (REPORTED), so a healthy VESC does not need this — but a controller answering only `GET_VALUES` produces no speed at all without it, and there is currently no way to enter these numbers. `G-vehicle-composer.md §4` wants a "read from controller" affordance via `COMM_GET_MCCONF`; **that is out of scope here** (B §11 lists it as deferred) — G1 ships manual entry only.

- [ ] **Step 1: Write the failing test** — component test: editing pole pairs / wheel diameter / gear ratio and saving persists them on `Controller.motor`; the fields are absent (not merely disabled) for a pack-only vehicle.

- [ ] **Step 2: Implement** — a "Мотор" section, visible only when the vehicle has controllers, using the existing `IntField`/`FloatField` components. Sensible defaults and input validation consistent with the alert-threshold fields already on that screen.

- [ ] **Step 3: Localise both files; verify key parity.**

- [ ] **Step 4: Run tests + compile.**

- [ ] **Step 5: Commit** — `feat(vehicle): motor configuration for controller vehicles`

---

### Task 7: Verification pass

**Files:** none expected. If this task needs a code change, it is a defect found late — fix it and say so prominently.

- [ ] **Step 1: Full suite + `assembleDebug`.**

- [ ] **Step 2: Emulator run — the regression half.** Install on an emulator. With **BMS vehicles only** (the demo vehicle and any you can create): cold start, Scanning, Picker in all three modes, the "My batteries" sheet, Settings' vehicle list, Battery tab, Vehicle Edit, both Ride styles. Confirm zero behavioural change from `main`. This is the half that protects existing users.

- [ ] **Step 3: Emulator run — the zero-pack half.** BLE scanning does not work on an emulator, so the creation flow itself cannot be exercised there. Instead **seed a controller-only vehicle directly into the database** (a debug-only insert, or a temporary call at startup — remove it before committing) and then re-walk every screen from Step 2. This is the only way to prove the Task 2 sweep is complete: the landmines fire on *screens listing vehicles*, not on the creation tap. Report exactly how you seeded it.

- [ ] **Step 4: Report what only real hardware can settle** — the actual BLE connect to a uBox, live telemetry on the Ride dashboard, and whether SETUP-reported speed arrives without `MotorConfig`. Do not claim these; list them as the handover test the product owner runs.

- [ ] **Step 5: Commit** — only if Steps 2-4 forced a fix.

---

## Self-Review (fill in before requesting final review)

- [ ] Does the app cold-start cleanly with a controller-only vehicle saved? (The single most important question in this plan.)
- [ ] Is a controller vehicle recognised from its own advertisement — i.e. did `allAddresses` actually reach both scan-matching sites?
- [ ] Does `onSave()` on a controller vehicle still yield zero packs?
- [ ] Does every unsupported controller type fail cleanly and roll back, leaving no orphan row?
- [ ] Is `vehicleSourceLabel` a single shared helper, or did copies of the fallback expression spread again?
- [ ] Zero `canId` writes; zero new `.sqm` files; `planLinks` never throws for anything G1 can build.
- [ ] EN/RU string key sets identical.
- [ ] Any claim of UI coverage — is it real, given this repo has no Compose UI test harness?
