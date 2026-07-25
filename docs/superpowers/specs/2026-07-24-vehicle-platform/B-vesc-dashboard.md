# Part B — VESC protocol (single controller) + the Ride dashboard

| Field | Value |
|---|---|
| Part | B |
| Depends on | A |
| Blocks | C, D, F, G (they build on the VESC decoder / dashboard / tabs) |
| Hardware | a directly-connected VESC (a uBox with its own BLE, or a VESC ESC). His full 2×uBox-via-head-unit scooter needs Part C. |

> Read `00-overview.md`, `01-linking.md`, `A-foundation.md` first. Part B is the
> first real end-to-end slice: connect to **one** VESC over BLE, decode its
> telemetry into `ControllerData`, and render the **Ride dashboard**. CAN
> forwarding, multi-controller and the head-unit gateway are Part C.

## 1. Scope
**In:** VESC BLE transport + packet codec; `VescProtocol` (single controller,
direct BLE); derived battery; the Ride dashboard (Material 3 Expressive redesign
of the VESC RT-DATA concept); the Ride/Battery/Settings tab restructure; the
km/mi unit toggle; VESC detection in the scanner.
**Out:** CAN forwarding / `FORWARD_CAN` / `PING_CAN` / multi-controller /
head-unit hosted BMS / VESC-BMS decode (all Part C); Begode motion (D);
FarDriver (E); motion alerts (F); the composer (G).

## 2. VESC BLE transport (`data/ble/` or `data/bms/`)

- **GATT**: Nordic UART Service — service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`,
  write (RX) `6e400002-…`, notify (TX) `6e400003-…`. Reuse `BmsUuids`.
- **Packet framing** (VESC): `start` byte — `0x02` (1-byte length, payload ≤255)
  or `0x03` (2-byte length); `length`; `payload`; `CRC16` (CCITT/XMODEM, poly
  `0x1021`, init 0) over the payload; `stop` byte `0x03`. Reassemble across BLE
  notifications (mirror `ByteArrayAccumulator`; a payload spans several 20-byte
  MTU chunks). Provide `CrcUtils` CRC16-CCITT alongside the existing CRC helpers.
- Payload = `[COMM_PACKET_ID, data…]`. Opcodes used here (pinned from VESC Tool
  `datatypes.h` / `commands.cpp`): `COMM_GET_VALUES = 4`,
  `COMM_GET_VALUES_SETUP = 47`, optional `COMM_GET_MCCONF = 14` for auto motor
  config. All multi-byte integers are **big-endian**; scaled ints per the field
  tables below (VESC `vbPopFrontDouble16(scale)` = int16 BE ÷ scale;
  `Double32(scale)` = int32 BE ÷ scale).

## 3. `VescProtocol` (`data/bms/VescProtocol.kt`)
`class VescProtocol(deriveBattery: Boolean, motor: MotorConfig) : BmsProtocol(),
MotionSource` (see `A §4.1`).

- `handshakeCommands()` = empty (VESC is poll-based).
- `pollCommands()` = `[packet(COMM_GET_VALUES_SETUP)]` at ~5–10 Hz
  (`pollIntervalMs ≈ 100–200`). SETUP is preferred: it carries **speed** and
  **battery_level** the plain GET_VALUES lacks.
- `onNotification()` reassembles frames, verifies CRC, dispatches by opcode.
- `controllerCount = 1`; `latestMotion(0)` returns the decoded `ControllerData`.
- `packCount = if (deriveBattery) 1 else 0`; `latestData(0)` returns a synthesised
  `BmsData` (§5).

### 3.1 `COMM_GET_VALUES_SETUP` (47) → `ControllerData` (primary)
Field order (pinned): temp_mos(d16/10), temp_motor(d16/10), current_motor(d32/100),
current_in(d32/100), duty_now(d16/1000), rpm(d32/1), **speed(d32/1000, m/s)**,
v_in(d16/10), **battery_level(d16/1000, 0..1)**, amp_hours(d32/1e4),
amp_hours_charged(d32/1e4), watt_hours(d32/1e4), watt_hours_charged(d32/1e4),
tachometer(d32/1000, m), tachometer_abs(d32/1000, m), position(d32/1e6),
fault_code(i8), vesc_id(u8), [num_vescs, battery_wh, …trailing, parse defensively].

Mapping → `ControllerData`:
- `speedKmh = speed_m_s * 3.6`, `speedSource = REPORTED`.
- `dutyPercent = abs(duty_now) * 100`.
- `motorCurrentA = current_motor`, `batteryCurrentA = current_in`,
  `inputVoltageV = v_in`, `powerW = v_in * current_in`.
- `eRpm = rpm`; `escTempC = temp_mos`; `motorTempC = temp_motor`,
  `hasMotorTemp = temp_motor > -50`.
- `odometerKm = tachometer_abs / 1000`, `tripKm` from session-start delta of
  `tachometer_abs`.
- `consumedAh = amp_hours`, `consumedWh = watt_hours`, `regenAh =
  amp_hours_charged`, `regenWh = watt_hours_charged`.
- `faults = if (fault_code != 0) listOf(faultName(fault_code)) else emptyList()`.

### 3.2 `COMM_GET_VALUES` (4) fallback
Same fields **without** `speed`/`battery_level`; has `id`/`iq` extra. Used when a
firmware/setup doesn't answer SETUP. Then `speedKmh` is **DERIVED** from `rpm`:
`eRpm/polePairs/gearRatio × π × wheelDiameterMm/1e6 × 60`, `speedSource = DERIVED`
(NONE if `wheelDiameterMm == 0`).

## 4. Fault names
Port `mc_fault_code` → string from VESC `datatypes.h` (`faultToStr` in
`commands.cpp`). A small enum-to-label map; unknown codes → `"FAULT <n>"`.

## 5. Derived battery (`deriveBattery = true`)
Synthesise `BmsData` from the controller frame so the Battery tab and alerts work
for a VESC with no smart BMS:
- `voltage = inputVoltageV`, `current = -batteryCurrentA` (VESC input current is
  positive under discharge; `BmsData.current` is **+ = charging** — negate;
  confirm sign against a live capture),
- `power = -powerW` accordingly,
- `soc`: prefer `battery_level*100` from SETUP (VESC computes it from its battery
  cutoff config); else `VoltageSocEstimator` over the vehicle chemistry.
  `socKnown = true` when either is available.
- no per-cell data (`cellVoltages = emptyList()`), `temperatures =
  listOf(escTempC)` optionally. Marked as a derived pack via the latent-slot
  mechanism (`A §4.5`) so it appears only once the first frame lands.

## 6. Scanner / detection
`BmsTypeDetector` (and `DiscoveredDevice`) learn VESC: advertise/serviceUUID =
Nordic UART ⇒ candidate `ControllerType.VESC`. Because NUS is generic, detection
is best-effort — confirm by a successful GET_VALUES_SETUP handshake. The picker
shows it as a controller, not a BMS.

### 6.1 Creating a controller-bearing vehicle — DEFERRED TO PART G (decided 2026-07-25)
Detection above only *labels* a discovered VESC. **Nothing in Part B creates a
`Vehicle` that has `controllers`** — the picker's type sheet offers `BmsType`
only, so tapping a detected VESC still builds a single-pack BMS vehicle at that
address. Consequence: the whole VESC path (protocol, controller link planning,
derived-pack slots, the Ride dashboard) is exercised by the **demo vehicle and
tests only**; a real uBox cannot yet be added through the UI.

This was a scope omission in the B1 plan, surfaced by the B1 whole-branch review
and **explicitly deferred to Part G (the vehicle composer)** by the product
owner rather than patched into B. Part G owns:
- a `ControllerType` branch in the picker's type sheet and a controller-shaped
  vehicle builder (a vehicle may legitimately have zero packs);
- setting `Controller.providesDerivedBattery` at creation time (it defaults
  `false`; B1 made the "no battery source ⇒ derive" fallback live, but nothing
  sets the flag deliberately yet);
- **fixing the whole `packs.first()` shim family in ONE pass** — it must land
  with the creation flow, not piecemeal, because a zero-pack vehicle currently
  throws from: `PickerComponent.kt:79` and `ScanningComponent.kt:63`
  (`saved.associateBy { it.bmsAddress }` — takes down the entire Picker/Scanning
  screen, not just a tap), `PickerComponent.kt:141/178`,
  `VehicleEditComponent.kt:104/158`, `DashboardScreen.kt:102`,
  `VehicleSheet.kt:99`, `SettingsScreen.kt:298`, `AutoConnectScreen.kt:93`,
  `RootComponent.kt:393-394`. Four sites of this family were already fixed in B1
  (`connect`, `scanAll`, `doConnect`'s previous-address check, the cell-count
  auto-fill); these are the remainder.

## 7. Ride dashboard (`presentation/ride/`) — LOCKED design (2026-07-25)
New `RideDashboardComponent` + `RideDashboardScreen`, reading
`bmsRepository.activeMotion` (+ `activeVehicleData` for battery). Visual reference:
`docs/design/ride-dashboard-mockup.html` (directional — the Compose gauge
component lays out ticks / numbers / center readout with exact, collision-free
spacing the hand-authored SVG mockup could not).

**Two renderers, both Material You** (dynamic color on Android 12+, Volty fallback
palette otherwise). The renderer is chosen **per vehicle** (§7.3):
- **Clean** — Material 3 Expressive. Hero: a concentric radial gauge — **SPEED**
  on the outer arc (brand accent) + a **configurable inner ring** (§7.2). Below:
  a 2×2 metric-card cluster (Power, Battery, ESC °C, Motor °C), a consumption card
  with a sparkline, then the odo/trip/uptime strip. `VehiclePill` reused up top.
- **Classic VESC** — a skeuomorphic overlapping **dial cluster** echoing the
  native RT-DATA screen: top fan (Current · Power · Duty), a large SPEED dial with
  a BATTERY dial overlapping lower-right, bottom fan (ESC · Consumption · Motor),
  then the strip. Dials are **Material-You-tinted** (tinted faces, accent/semantic
  needles + swept arcs, red danger segments) — not the original grey/white.

### 7.1 Reusable gauge composables (`presentation/ride/gauge/`)
- `RadialGauge` — the Clean arc gauge (outer speed + inner secondary rings), Canvas.
- `DialGauge` — the Classic skeuomorphic dial: Canvas ticks (minor+major), sparse
  numeric labels placed collision-free around the rim, needle + hub, a swept value
  arc, an optional red danger segment, and a clean center readout (label/value/unit).
  Parameterised by min/max/value/majors/colors/danger. **This component is what
  makes the Classic view legible where the static mockup couldn't.**
- `ClusterLayout` — a custom `Layout` positioning the eight `DialGauge`s in the
  overlapping fan/nest composition, scaling to width (not absolute px).

### 7.2 Configurable inner / secondary gauge (per vehicle)
The rider picks what the secondary gauge shows — a wheel wants **Duty/ШИМ**, a
scooter **Battery**, a bike **Power** or **Motor °C**:
`enum SecondaryGauge { DUTY, BATTERY, POWER, CURRENT, MOTOR_TEMP, ESC_TEMP, CONSUMPTION }`.
In Clean it drives the hero inner ring; in Classic it emphasises the chosen dial.

### 7.3 Per-vehicle dashboard config + persistence
`enum DashboardStyle { CLEAN, CLASSIC }`. Persist `dashboardStyle` + `secondaryGauge`
**per vehicle** — add both as columns on `VehicleRow` with a **v4→v5 SQLDelight
migration** (`4.sqm`: `ALTER TABLE VehicleRow ADD COLUMN dashboardStyle TEXT`,
`... secondaryGauge TEXT`). The app-level default style lives in `AppPrefs`; a new
vehicle inherits it, then its per-vehicle value overrides. `Vehicle` gains the two
fields (or a small `DashboardConfig`); the composer (Part G) and a dashboard
long-press/settings entry edit them.

### 7.4 Duty color bands (shared with Part F)
green `<75` · amber `75–90` · red `>90`. A single `dutyLevel(percent)` helper in a
shared location feeds both the gauge coloring here and the Part F audible-alarm
thresholds, so they can never diverge.

- Metric set: speed (hero) · duty/ШИМ · power · battery · ESC/motor temp ·
  consumption (Wh/km + avg, moving-average) · odo/trip/uptime. Reuse
  `MetricCard`/`SparklineGraph`/`PowerRangeBar` where they fit.
- The Graph screen gains motion metrics (speed/duty/power) in its metric switcher.
- Speed shows a dash when `!speedKnown`; km/h or mph per the unit setting (§9).

## 8. Navigation / tabs (`presentation/root/`)
Restructure `RootComponent.Tab` from `Live/Graph/Settings` to
**`Ride / Battery / Settings`**:
- `Config.Ride` (new) → `RideDashboardComponent`. Home tab **when the vehicle has
  controllers**; otherwise Ride is hidden and Battery is home (pure-BMS vehicles
  are unchanged).
- `Config.Dashboard` (today's battery dashboard) is reachable as the **Battery**
  tab. Rename the tab label, not the component.
- `Graph` stays reachable (a button on Ride/Battery, or a sub-tab) — no longer a
  top-level tab. `onBack`/`onTab` updated accordingly.
- `PackDetail`, `VehicleEdit`, `Settings` unchanged.

## 9. Units (`data/prefs/`, `util/`)
`AppPrefs` gains `unit_system: enum { METRIC, IMPERIAL }` (default METRIC). A
`UnitFormatter` converts km/h↔mph, km↔mi for display only; `ControllerData` stays
canonical (km/h, km, °C). Settings gets a toggle.

## 10. Testing
- `VescProtocolTest` — decode captured SETUP and GET_VALUES frames (record from a
  real uBox or synthesise per the field tables) → assert every `ControllerData`
  field, scales, sign, speed provenance, fault mapping; partial/truncated frames
  degrade gracefully; CRC reject.
- `CrcUtils` CRC16-CCITT test vectors.
- Derived-battery test — SETUP battery_level path and VoltageSocEstimator path.
- `UnitFormatterTest` — km/h↔mph, km↔mi, rounding.
- Component tests — `RideDashboardComponent` maps `activeMotion` to UI state;
  hidden Ride tab for a controller-less vehicle; tab navigation.
- Demo — the demo vehicle (from A) drives the Ride dashboard end to end.

## 11. Decisions — RESOLVED (2026-07-25, user sign-off on the mockup)
1. **Two dashboard styles**, both Material You: **Clean** (M3 Expressive) +
   **Classic VESC** (skeuomorphic overlapping cluster). Selectable **per vehicle**
   (app default + per-vehicle override). Both ship in Part B; sequence Clean first
   (simpler, approved), Classic second (`DialGauge`/`ClusterLayout`).
2. **Secondary/inner gauge is configurable, per vehicle** — menu
   Duty · Battery · Power · Current · Motor °C · ESC °C · Consumption.
3. **Duty color bands**: green <75 / amber 75–90 / red >90 — the SAME thresholds
   the Part F audible alarm uses (one shared `dutyLevel` helper).
4. **Ride = home** when the vehicle has a controller; pure-BMS vehicles keep
   Battery as home (no Ride tab).
5. **SETUP is the primary poll** (speed + battery_level); GET_VALUES is the DERIVED
   fallback. ~5–10 Hz.
6. **Persistence**: per-vehicle `dashboardStyle` + `secondaryGauge` via a
   **v4→v5** SQLDelight migration (`4.sqm`).

Still deferred: Graph placement (a button off Ride/Battery, not a top tab —
confirm interaction at build); `COMM_GET_MCCONF` auto-read of `MotorConfig`
(nice-to-have; `MotorConfig` is only needed for the GET_VALUES DERIVED fallback,
may defer to G).

---

## 12. Debt carried out of B1/B2 (recorded at merge, 2026-07-25)

Part B shipped in two branches: **B1** (VESC decode + the Clean M3 dashboard,
merged `147958d`) and **B2** (the Classic eight-dial renderer, merged
`5095440`). Both passed a whole-branch review. What follows is what those
reviews found and we consciously did **not** fix — read this before touching
the Ride dashboard, and do not re-derive it.

### 12.1 Must happen before the migration ships
- **`:composeApp:verifyCommonMainVoltyDatabaseMigration` has never run
  green.** It fails locally with `org.sqlite.core.NativeDB._open_utf8` — an
  environment defect, confirmed by running it on an unmodified checkout. The
  **v4→v5** migration (`4.sqm`, `dashboardStyle` + `secondaryGauge`) was
  therefore verified only by a manual schema diff plus a hand-written v2→v5
  JDBC chain test. **Re-run this task on CI before shipping.** If it fails
  there, `.sq` and `.sqm` disagree and fresh installs will diverge from
  upgrades.

### 12.2 Untestable in this repo today
- **The Classic emphasis wiring is not covered.** `ClassicEmphasis`
  (`SecondaryGauge → ClusterSlot`) is tested, but deleting the
  `emphasized = spec.slot == emphasizedSlot` line in `ClassicRideCluster`
  leaves the whole suite green — i.e. the exact regression §7.2 guards
  against (the cluster ignoring `state.secondary`) is undetected. There is
  **no Compose UI-test dependency** in the project; adding one for a single
  line was judged not worth it at a merge gate. Whoever adds Compose UI
  tests first should claim this.
- **`DialGeometry.centreScale`'s acceptance test estimates glyph metrics.**
  Real text measurement needs Compose, so the test derives its inputs from
  the committed font fractions plus two documented estimate constants
  (`EstimatedAvgGlyphWidthEm`, `EstimatedLineHeightEm`) and asserts a
  *range* (0.75–0.99), not a pinned decimal. It proves "no collision, shrink
  stays far from its 0.5 floor" — it does not prove an exact number.

### 12.3 Known cosmetic compromises (safe to ship, worth a polish pass)
- **Imperial hero ticks are round but oddly stepped.** The display-unit max
  snaps up to a multiple of 5, so a 70 km/h floor becomes 45 mph → labels
  step by 9 (0, 9, 18, 27, 36, 45). Integer and evenly spaced, but no real
  speedometer steps by 9. Snapping to 10 would read better at the cost of
  dial arc. Metric is unaffected (already round).
- **Russian dial labels engage the shrink guard.** `ТЕМП. МОТОРА` (12 chars)
  on the small corner dial puts `centreScale` at ≈0.92 on a 360dp screen —
  visually fine (6%), but the "no shrink needed by construction" property
  from B2 Task 2 no longer strictly holds for RU. Shortening the dial-face
  labels (`Мотор`, `ESC`) would restore it and arguably suits skeuomorphic
  faces better; deliberately not done at the merge gate.
- **`CONSUMPTION` means slightly different things per style**: instant with
  a session fallback on Classic, instant-only on Clean. Pick one in F.
- Hoisting the Graph link out of the style switch made Clean ~26dp taller.

### 12.4 Latent, not currently reachable
- **`ClusterLayout` uses `placeRelative`**, so the Classic cluster mirrors
  under RTL — dial positions would flip. No RTL locale ships yet.
- **`DialGauge` reads a one-frame-lagged `radius`** (via `onSizeChanged`)
  while `center` reads live `size`. Inert in a fixed cluster; would show as
  a transient stale frame during continuous resize (desktop window).
- **Clean's hero ring has no `speedKnown` guard**, unlike Classic. Pre-dates
  B2 and belongs to whoever revisits the Clean hero.

### 12.5 Owed to Part G (already noted in `G-vehicle-composer.md`)
The `Vehicle.bmsType` / `bmsAddress` / `cellCount` shims still call
`packs.first()`. Four call sites were fixed in B1; the rest — including
`PickerComponent.kt` and `ScanningComponent.kt`, which take down a whole
screen rather than a single tap — must be fixed in one pass together with
the controller-vehicle creation flow, per §6.1.

---

## 13. Debt carried out of G1 (recorded at merge, 2026-07-25)

Part G1 (`d011987`) made controller-bearing vehicles creatable. What it
knowingly left behind:

- **`_activeVehicle` survives a failed connect.** The row is deleted but the
  field still points at it, and `_rideAvailable` flips true. Verified
  unreachable today for four independent reasons — `shouldLeaveRide` returns
  false for a controller vehicle, the tab bar is hidden on Picker, the
  picker's active-vehicle seed requires `Connected`/`Reconnecting`, and the
  only exit from Picker is a successful connect that overwrites the field.
  **Trigger to watch: one `nav.push(Config.Picker(...))` or one change to
  tab-bar visibility makes it live.**
- **Scope cancellation mid-connect skips the rollback**, leaving an orphan
  row. Narrow window, identical on the BMS path, pre-dates G1.
- **The unsupported-controller refusal is unlocalised**, consistent with
  every other picker error (they all render `exceptionOrNull()?.message`).
- **Controller picks ignore picker mode.** A `BmsType` pick in "guest"/"cold"
  stays unsaved; a controller pick always persists and routes to Vehicle
  Edit, because `connectGuest` takes a `BmsType` and cannot express a
  controller. A user-visible asymmetry, argued in the KDoc.
- **The anti-drift test does not cover `unsupportedControllerReason` itself.**
  It pins that `createProtocol` keeps consulting `controllerMotionProtocol`;
  re-hardcoding the picker's side as a separate `when` would leave the suite
  green.
- **`RideDashboardComponent.State.secondaryReadout` is dead** — written in
  six places, read by nothing since the screen recomputes it. One test still
  asserts on it.
- **Battery-centric vocabulary.** "Edit battery", "Pick a battery", "MY
  BATTERIES", "Add new battery" — now wrong for a vehicle with no battery at
  all. This is Part G2's job along with the composer proper; the entry point
  should ask what the vehicle *is*, not offer to add a battery.

### 13.1 Two lessons worth keeping
- **Compose Multiplatform does not process Android's backslash escapes in
  string resources.** `\'` and `\"` render literally. Four such defects
  shipped unnoticed because no test reads a rendered string and the Russian
  strings happened to use guillemets. Sweep both locale files after any
  string work.
- **A hand-written list of call sites in a spec is not a work queue.** G1's
  compiler-driven sweep found five sites `§6.1` had missed, including a file
  that was not mentioned at all. Delete the symbol and let the build
  enumerate.
