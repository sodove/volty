# Vehicle Info Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** One screen per vehicle, «Всё о ТС», that shows everything the app knows about it — protocol-agnostic sections built from the shared models, plus an optional per-protocol «Особенности устройства» section — live while connected, persisted values when not.

**Why (rider, 2026-10-26):** the Energy block showed nothing after 6 km (resistance was never found — fixed in 0.7.52; capacity needs a ≥ 20 % drop; consumption is live-only), and the rider wants to see what the app computed. It is also the measurement tool for the next feature: whether an old Begode's live-frame bytes 14..15 track load (a candidate hardware PWM) before any synthetic PWM is built. **The app works with many protocols, not only Begode:** every section except «Особенности устройства» must come from shared models.

**Repo / branch:** `C:\Users\sodovaya\git\volty-nearby`, branch `feat/vehicle-info` from `main` @9c248aae. Suite: `.\gradlew.bat :composeApp:testDebugUnitTest`.

## Global Constraints
- Read-only: never write to any device; never write to a Begode's FFE1 (Global Constraint 5).
- Compose is not unit-testable here: the screen's content is a pure model built by a pure function / component and tested; composables render it.
- Strings in both `values/strings.xml` and `values-ru/strings.xml`; no Android backslash escapes. Protocols emit structured facts (stable keys + raw values); words come from resources.
- An absent value renders as «—» with, where known, a reason («колесо не отдаёт», «нет датчика», «не подключено»); never a fabricated 0.
- Fixtures for absent data are deliberately incoherent (e.g. `hasDuty = false, dutyPercent = 37f`).
- No `runTest` with an unbounded delayed loop.
- No DB migration in this plan.

## Existing pieces to reuse (read them)
- `domain/model/BleDiagnosticRecord.kt`: `BleDiagnosticsState.activeLinks: List<BleDiagnosticLink>` (address, name, protocol, controllerIdentity, hardwareCode, model, firmwareVersion, latest battery/controller samples per link), published by `KableBmsRepository.publishActiveDiagnosticLinks`; `data/bms/ProtocolDiagnostics.kt` (model/firmware/hardware code a protocol may expose; only Veteran implements it today).
- `domain/alert/MotionAlertAvailability.kt`: `availabilityFor(vehicle, latestMotion)` with `AlertAvailability.Unavailable(reason)`.
- `domain/energy/*`: `EnergyState` on `VehicleData.energy`; `VehicleEnergyStore.peek` (non-destructive); `RangeSource`.
- `BmsRepository.activeVehicle / activeVehicleData / activeMotion / connectionState`.

---

### Task 1: Facts from the pipeline — link stats, protocol facts, energy and range detail
**Files:** create `data/bms/ProtocolFacts.kt`; modify `domain/model/BleDiagnosticRecord.kt`, `data/ble/ConnectionSession.kt`, `data/ble/KableBmsRepository.kt` (publishActiveDiagnosticLinks), `domain/energy/ChargeTracker.kt` + `VehicleEnergyTracker.kt` (EnergyState detail), `data/energy/RangeSource.kt` (detail).
**Interfaces (produced):**
- `enum class DeviceFactKey` — stable keys, one per fact any protocol may report (start with the Begode and VESC ones from Task 2; add as protocols grow).
- `data class DeviceFact(val key: DeviceFactKey, val value: String, val unit: String? = null)` — `value` is already formatted numerically (no words).
- `interface ProtocolFacts { fun deviceFacts(): List<DeviceFact> }` — optional on a protocol, read after each decoded notification the same way `ProtocolDiagnostics` is (ConnectionSession → KableBmsRepository), published as `BleDiagnosticLink.facts: List<DeviceFact> = emptyList()`.
- `BleDiagnosticLink.framesPerSecond: Float?` (notifications/s over the last ~5 s, measured in ConnectionSession by notification timestamps) and `BleDiagnosticLink.reconnects: Int` (since the vehicle connected).
- `EnergyState.detail: EnergyDetail? = null` with `counterSocPercent: Float?`, `voltageSocPercent: Float?` (compensated), `packV: Float?`, `compensatedPackV: Float?`, `dischargeA: Float?`, `currentSource: CurrentSource` (`CONTROLLER, PACKS, NONE`), `integratedAh: Double`, `integratedWh: Double` (Ah × nominal V), `learnDropPercent: Float?` (rest SoC at the open segment's anchor minus the current compensated-voltage SoC; null with no anchor), `usableFraction: Float?`, `atRest: Boolean`.
- `RangeSource.detail: StateFlow<RangeDetail?>` with `tripWhPerKm`, `recentWhPerKm`, `blendedWhPerKm`, `tripKm`, `unearnedReason: RangeUnearnedReason?` (`NO_CAPACITY, TOO_SHORT, TOO_FEW_SAMPLES, SOC_UNKNOWN, NO_CONSUMPTION`).
- [ ] Failing tests: fps from a fake clock over notifications; facts published for a protocol implementing ProtocolFacts and empty otherwise; EnergyDetail fields for a modelled snapshot (incl. `currentSource` CONTROLLER vs PACKS vs NONE with an incoherent `hasBatteryCurrent=false, batteryCurrentA=80f` fixture); `learnDropPercent` after a rest anchor; RangeDetail reasons for each unearned case.
- [ ] Implement; suite; commit `feat(info): link stats, protocol facts and energy/range detail in the pipeline`.

### Task 2: Begode and VESC facts
**Files:** `data/bms/BegodeProtocol.kt`, `data/bms/VescProtocol.kt` (and `VescGatewayProtocol.kt` if it owns the CAN view), `DeviceFactKey` entries.
- Begode facts: lifetime odometer (km), power-on distance (km), tiltback speed (km/h), wheel alerts (the decoded labels, comma-joined), board (MPU) temperature (°C), live-frame bytes 14..15 RAW (the candidate hardware PWM: the raw integer and raw × 0.1 %), the 0x07 duty raw, derived series-cell count and how it was proven (cells / branch-ratio / none), smart BMS seen (yes/no), per-branch voltage/current where decoded. Note in KDoc why bytes 14..15 are surfaced RAW and still not used as duty (ET Max capture: constant 16.9 % on a standing wheel).
- VESC facts: controller id, firmware version (if decoded), CAN ids discovered, fault code(s) raw.
- [ ] Failing tests per protocol from existing captures/fixtures (BegodeDumpFixture, VESC fixtures) asserting the exact facts and values; a protocol without facts → empty.
- [ ] Implement; suite; commit `feat(info): Begode and VESC device facts`.

### Task 3: The screen model
**Files:** create `presentation/vehicle/info/VehicleInfoModel.kt` (pure) and `VehicleInfoComponent.kt` (Decompose component + Default impl).
**Interfaces:** `fun vehicleInfoOf(vehicle: Vehicle, connected: Boolean, data: VehicleData?, motion: ControllerData?, links: List<BleDiagnosticLink>, stored: EnergyRecord?, range: RangeDetail?, availability: Map<MotionAlertKind, AlertAvailability>, units: UnitSystem): VehicleInfo` where `VehicleInfo(sections: List<InfoSection>)`, `InfoSection(kind: InfoSectionKind, title args, rows: List<InfoRow>)`, `InfoRow(label: InfoLabel, value: String?, reason: InfoAbsence?)` — labels and absence reasons are enums mapped to resources by the screen.
Sections (in order): VEHICLE (type, topology, chemistry, cell bounds, series cells + source), CONNECTIONS (per link: address, protocol, model, firmware, fps, reconnects, via head unit), BATTERIES (per pack: voltage, current, temps, cells min/max/delta, SoC + who computes it, capacity, cycles, charge/discharge switches, BMS faults), CONTROLLERS (per controller: each metric reported or not with reason — speed+source, duty, phase/battery current, motor/ESC temp, energy counters, odometer/trip, faults), ENERGY (EnergyState + EnergyDetail + stored learned/resistance when not connected), RANGE (RangeDetail), ALERTS (armed / unavailable with reason / currently active), DEVICE FACTS (per link, only when non-empty).
- [ ] Failing tests: a disconnected vehicle shows persisted energy values and «не подключено» for live rows; a Begode T4 fixture (no duty, no motor temp) shows those rows absent WITH the reason; an ANT pack shows SoC «считает BMS», a T4 pack «считает модель»; incoherent `hasDuty=false, dutyPercent=37f` never shows 37; a link with no facts has no facts section.
- [ ] Component collects the flows (throttle UI updates to ≤ 2 Hz), reads `peek` once on open and on disconnect; suite; commit `feat(info): the vehicle info model`.

### Task 4: The screen and its entry
**Files:** create `presentation/vehicle/info/VehicleInfoScreen.kt`; RootComponent (new `Config.VehicleInfo(vehicleId)` reached by `goTo`), vehicle editor hub entry «Всё о ТС» next to «Тревоги», strings (en + ru).
- Grouped list with section headers using the redesigned settings row styles; absent values «—» with the reason as a caption; Device-facts labels from resources by `DeviceFactKey`.
- [ ] Component/navigation tests (entry opens the screen for a saved vehicle; back returns to the editor); suite; commit `feat(info): «Всё о ТС» screen`.
- [ ] Report plainly: layout unverified on a device.

---

## Extension (rider, 2026-10-26): device facts for every other protocol

Same contract as Task 2 for each protocol below: implement `ProtocolFacts` with a CACHED list rebuilt only on change; surface only what the decoder ACTUALLY decodes but does not already publish through BmsData/ControllerData (read the decoder; never invent a field, never re-decode a value the common sections already show); new `DeviceFactKey` entries get en + ru strings through the exhaustive mappings in `presentation/vehicle/info/VehicleInfoText.kt`, and unit symbols must map in `factUnit` (FactUnitCoverageTest must keep covering every emitted symbol); tests from the protocol's existing fixtures/captures assert exact values; a protocol with nothing extra returns an empty list (and says why in KDoc). Text a device sends as labels (fault names, model strings) is data, shown verbatim.

### Task 5: Smart BMS — ANT, JK, JBD, Daly
**Files:** `data/bms/AntBmsProtocol.kt`, `JkBmsProtocol.kt`, `JbdBmsProtocol.kt`, `DalyBmsProtocol.kt`, `domain/model/DeviceFact.kt`, `VehicleInfoText.kt`, strings, tests.
Candidates (only if decoded): balancing state/current, MOSFET temperature, design/nominal capacity, protection/warning bitmask raw, hardware/software version strings, cell-count reported by the BMS, state of health.
- [ ] Failing tests per protocol; implement; suite; commit `feat(info): smart BMS device facts`.

### Task 6: Wheels — Veteran/LeaperKim, KingSong, InMotion, Ninebot, Ninebot legacy
**Files:** `VeteranProtocol.kt` (also serves LeaperKim; already implements ProtocolDiagnostics), `KingSongProtocol.kt`, `InMotionProtocol.kt`, `NinebotProtocol.kt`, `NinebotLegacyProtocol.kt`, DeviceFact keys, `VehicleInfoText.kt`, strings, tests.
Candidates (only if decoded): lifetime odometer, tiltback/max-speed setting, pedal/ride mode, wheel alarm/state flags, board temperature, PWM raw, firmware/model already in ProtocolDiagnostics (don't duplicate).
- [ ] Failing tests per protocol; implement; suite; commit `feat(info): wheel device facts`.

### Task 7: Controllers — Kelly, FarDriver
**Files:** `KellyProtocol.kt`, `FarDriverProtocol.kt`, keys, text, strings, tests.
Candidates (only if decoded): controller model/version, throttle/brake raw, gear/mode, error code raw, motor RPM/eRPM if not already shown.
- [ ] Failing tests per protocol; implement; suite; commit `feat(info): controller device facts`.
