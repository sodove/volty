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
**Superseded by B3 (2026-07-25) — see §15.1.** This section originally specced `DialGauge` (a
single skeuomorphic dial: ticks, needle + hub, a swept value arc, an optional red danger segment)
and `ClusterLayout` (positioning eight of them). B2 shipped exactly that and the product owner
rejected it on a device: right composition, wrong dial — the needle ran from the centre through the
readout, the value text was half the intended size, and an invented red danger wedge doesn't exist
on the real instrument. What actually ships instead, both Compose-free-and-tested where the
arithmetic lives and Canvas-only where it draws:
- `RadialGauge` — the Clean arc gauge (outer speed + inner secondary rings), Canvas. Unchanged.
- `VescDialGauge` (Canvas) + `VescDialGeometry` / `VescDialMetrics` / `VescNibShading` (pure
  geometry, radius fractions and Qt-compatible colour math) — a faithful, line-by-line port of
  VESC Tool's own `mobile/CustomGauge.qml`. The needle is a short blade riding at the rim
  (`0.73R..0.95R`) on a rotation origin pushed down to the dial's centre, so it can never cover the
  centre readout **by geometry**, not by z-order or a hub cap; the dial face is the theme
  background (not a separate plate), so overlapping dials read as one instrument instead of eight
  floating discs; there is **no red danger wedge** — VESC has none, severity is carried by the
  needle's colour alone (`nibColor`, §7.4).
- `VescClusterLayout` (a custom `Layout`) + `VescClusterGeometry` (pure placement) — the eight
  dials' overlapping fan/nest composition, matching `RtDataSetup.qml`'s own nested offsets
  (Current→Duty→Power, ESC→Motor→Consumption, Speed→Battery) exactly rather than approximately.

### 7.2 Configurable inner / secondary gauge (per vehicle)
The rider picks what the secondary gauge shows — a wheel wants **Duty/ШИМ**, a
scooter **Battery**, a bike **Power** or **Motor °C**:
`enum SecondaryGauge { DUTY, BATTERY, POWER, CURRENT, MOTOR_TEMP, ESC_TEMP, CONSUMPTION }`.
**In Clean it drives the hero inner ring. It does NOT apply to Classic — reversed by B3
(2026-07-25), see §15.1.** The original plan (and B2) applied the setting to Classic too, drawing
the chosen dial at `1.12×` and painting it front-most. That fought the composition it was supposed
to live inside: Classic shows all eight VESC dials at once, so there is nothing to single out, and
the emphasis routinely covered Power's own caption while fighting the QML's own hero ordering
(Power is the top trio's hero, painted last — and therefore in front — by the QML itself). Vehicle
Edit's "Inner gauge" control stays visible and enabled for a Classic vehicle rather than being
hidden (a rider who switches a vehicle **to** Clean should not find their earlier choice silently
gone); it is simply inert until they do.

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
   (simpler, approved), Classic second (`DialGauge`/`ClusterLayout` — the component
   names as planned here; B3 replaced both with a faithful port under new names,
   see §15.1, after B2 shipped this plan literally and the product owner rejected
   the result on a device).
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
- **RESOLVED by B3 (2026-07-25) — see §15.2.** ~~The Classic emphasis wiring is not
  covered.~~ `ClassicEmphasis` and the `emphasized` concept it wired up are deleted
  entirely, not merely re-covered: `VescClusterGeometry.place` takes no "which dial is
  emphasised" parameter at all, so the untestable gap this bullet described (a one-line
  deletion in `ClassicRideCluster` leaving the suite green) cannot recur structurally,
  regardless of whether a Compose UI-test dependency ever gets added.
- **RESOLVED, in altered form, by B3 — see §15.2 and new debt item 4 in §15.3.**
  ~~`DialGeometry.centreScale`'s acceptance test estimates glyph metrics.~~ `DialGeometry`
  itself is deleted along with the B2 renderer. Its successor, `VescDialGeometry.centerTextScale`
  / `centerTextMaxWidth`, DOES now have a pinned-decimal test using real per-character Roboto
  advances (not a range estimate) — but the two-pass measurement that feeds it
  (`VescDialGauge.fitCenterTextLine`) still runs through Compose's `TextMeasurer` and so still has
  **zero** test coverage, for the same "no Compose UI-test dependency" reason as the bullet above.
  The specific complaint (an estimate instead of a measurement) is fixed; the underlying gap (the
  measuring code itself is untested) persists under a new name — see §15.3 item 4.

### 12.3 Known cosmetic compromises (safe to ship, worth a polish pass)
- **RESOLVED by B3 (2026-07-25) — see §15.2.** ~~Imperial hero ticks are round but oddly
  stepped.~~ `ClassicDialSpecs.heroLabelStep` no longer accepts whatever step the QML's own
  snap-to-5 happens to produce; it tries the QML's preferred step first and, if that would not
  divide the snapped span evenly, falls back through a coarsest-first list of genuinely round
  steps (`HERO_FALLBACK_LABEL_STEPS = [50, 25, 20, 10]`). A 70 km/h floor now labels its 50 mph
  imperial scale by 10 (0, 10, 20, 30, 40, 50), not 9. Pinned by
  `the_hero_tick_labels_stay_round_in_imperial_too`.
- **Likely resolved by B3, not one of the three the task-7 review named — flagged here rather
  than silently dropped.** ~~Russian dial labels engage the shrink guard.~~ `centreScale` (the B2
  property this bullet names) is deleted along with the rest of `DialGeometry`. Its B3 successor,
  `VescDialGeometry.centerTextScale`, is exercised against the actual shipped captions —
  `МОЩНОСТЬ`, `РАСХОД`, `МОТОРА` (the split half of `ТЕМП. МОТОРА` this bullet was about),
  `CONSUMPTION` — by `every_shipped_caption_fits_its_dial_at_full_size_with_the_margin_recorded`,
  which asserts a scale of exactly `1.0` (no shrink at all) for all four, at both real cluster
  radii, with the tightest using ~79% of its budget. Recorded as "likely" rather than flatly
  resolved because B3's caption budget (`0.66R` chord, §12.2/15.3 item 4) is a different formula
  from B2's, not merely a re-measurement of the same one — worth a device check, not just a test
  read, before fully retiring this line.
- **`CONSUMPTION` means slightly different things per style**: instant with
  a session fallback on Classic, instant-only on Clean. Pick one in F.
- Hoisting the Graph link out of the style switch made Clean ~26dp taller.

### 12.4 Latent, not currently reachable
- **Carried forward, retargeted, by B3 — see new debt item 5 in §15.3.** `ClusterLayout` is
  deleted; its successor `VescClusterLayout` still uses `placeRelative` at line 100, so the same
  RTL mirroring risk applies to the new component under its new name. No RTL locale ships yet.
- **RESOLVED by B3 (2026-07-25) — see §15.2.** ~~`DialGauge` reads a one-frame-lagged
  `radius`~~ (via `onSizeChanged`) while `center` reads live `size`. `DialGauge` is deleted;
  its successor `VescClusterLayout`/`VescDialGauge` derive every dial's size from
  `VescClusterGeometry.fit(width, height)` at layout time — a single measurement pass, not two
  properties fed by two different update mechanisms — so there is no second, lagging `radius` to
  disagree with `center`.
- **Clean's hero ring has no `speedKnown` guard**, unlike Classic. Pre-dates
  B2 and belongs to whoever revisits the Clean hero. Untouched by B3 (Classic-only rewrite).

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

---

## 14. Gauge ranges are hardcoded; VESC computes them (found 2026-07-25)

**What we ship.** `ClassicDialSpecs` hardcodes `CURRENT_MAX_A = 60`, `POWER_MAX_W = 10000`
and `0..100 °C` on both temperature dials. Only the speed dial adapts, from the
session maximum. These are VESC Tool's *default* numbers, transcribed from the
gauge declarations in `RtDataSetup.qml`.

**What VESC actually does.** Every range is recomputed at runtime from the
controller's motor configuration, in the data handler further down the same file
(`RtDataSetup.qml:665-763`) — which is why reading only the declarations misses it:

```qml
currentMaxRound = ceil(l_current_max / 5) * 5 * values.num_vescs
powerMax        = min(v_in * min(l_in_current_max, l_current_max), l_watt_max) * num_vescs
escTempGauge.maximumValue       = ceil(l_temp_fet_end / 5) * 5
escTempGauge.throttleStartValue = ceil(l_temp_fet_start / 5) * 5
speedFact       = (si_motor_poles/2 * 60 * si_gear_ratio) / (si_wheel_diameter * PI)
```

Three consequences we do not currently reproduce:
1. **`× num_vescs`** — limits scale with the number of controllers on the bus. A
   2×uBox scooter's current dial should be twice a single controller's.
2. **The temperature warning thresholds come from the controller** —
   `l_temp_fet_start` is where the ESC begins cutting power, and it is what
   colours the needle. We use our own `TempBands` (70/40), shared with Part F's
   alarms, so our needle colour does not mean what VESC's means on the same
   hardware.
3. **Speed's maximum is derived from motor geometry**, not from the session peak.

**Decision (2026-07-25, product owner delegated the sequencing):**
- **Now:** apply the session-maximum auto-scale — already built and tested for the
  speed dial — to the current and power dials, with today's constants as floors.
  A deliberate divergence from the port: VESC scales from configuration, we scale
  from what we have seen. Better than a dial that pegs at 60 A on a 500 A scooter.
- **With Part C:** read the real limits via `COMM_GET_MCCONF` and reproduce the
  formulas above, including `× num_vescs`. The multiplier only has meaning once
  multiple controllers exist, which is Part C's subject — pulling `GET_MCCONF`
  earlier would mean writing a config parser with nowhere to apply its main term.
- **Open when that lands:** whether the ESC/motor needle colour should follow
  `l_temp_fet_start` (VESC's meaning) or stay on our `TempBands` (shared with the
  audible alarms). Two sources of truth for "when is it hot" is a defect; pick one.

---

## 15. Debt carried out of B3 (recorded at merge, 2026-07-25)

Part B shipped a third branch, **B3** (`feat/classic-faithful-port`), because the product owner
ran B2's Classic renderer on a device and rejected it: the composition (§7's eight-dial fan/nest
layout) was right, but the dial itself was wrong in ways a screenshot review never caught — a
needle drawn from the centre through the readout instead of a short blade at the rim, half-size
value text, an invented red danger wedge VESC's real instrument does not have. B3 deleted the B2
renderer (`DialGauge`, `ClusterLayout`, `ClassicEmphasis`, `DialGeometry`) entirely and replaced it
with a line-by-line port of VESC Tool's own `mobile/CustomGauge.qml` (`VescDialGauge`,
`VescDialGeometry`, `VescDialMetrics`, `VescNibShading`, `VescClusterLayout`,
`VescClusterGeometry`). §7.1 and §7.2 above now describe what actually ships; this section is what
changed as a RESULT of that rewrite, plus what a whole-branch review + merge-gate pass (task-7)
found and fixed, or chose not to, on 2026-07-25. As with §12/§13, this records superseded decisions
rather than erasing them — read it before touching the Classic renderer again.

### 15.1 What replaced what
| B1/B2 (deleted) | B3 (ships) |
|---|---|
| `DialGauge` (Canvas dial: ticks, needle+hub, danger wedge, centre stack) | `VescDialGauge` + `VescDialGeometry`/`VescDialMetrics`/`VescNibShading` |
| `ClusterLayout` (custom `Layout`) | `VescClusterLayout` + `VescClusterGeometry` |
| `ClassicEmphasis` (`SecondaryGauge → ClusterSlot`, the "Inner gauge" setting applied to Classic) | deleted outright — Classic has no emphasis concept; §7.2's setting now applies to Clean only |
| `DialGeometry.centreScale` (glyph-estimate acceptance test) | `VescDialGeometry.centerTextScale`/`centerTextMaxWidth` (real per-character advances in its test) |

The §7.2 reversal is the one worth restating outside the table: **B1/B2 had "Inner gauge" apply to
both styles** (Clean's hero ring AND a `1.12×` emphasised Classic dial). B3 makes it Clean-only,
deliberately — Classic shows all eight metrics at once, so there is nothing to single out, and the
emphasis cue covered Power's caption while fighting the QML's own hero ordering. Vehicle Edit's
copy and the setting's enabled/visible state did not change (see §7.2); only what Classic does with
the value did.

### 15.2 Resolved / removable from §12 (see the inline markers at each bullet, above)
- §12.2 "Classic emphasis wiring is not covered" — **resolved structurally**, not just re-tested:
  the untestable concept (`ClassicEmphasis`) is gone, not merely covered better.
- §12.2 "`DialGeometry.centreScale`'s acceptance test estimates glyph metrics" — **resolved in
  altered form**: the estimate is gone (real advances now), but the underlying "the actual measuring
  code has zero coverage" gap persists under a new name, `fitCenterTextLine` — see item 4 below.
- §12.3 "Imperial hero ticks are round but oddly stepped" — **resolved**: `heroLabelStep`'s
  coarsest-first fallback list replaces the QML's bare snap-to-5 with a genuinely round step.
- §12.4 "`DialGauge` reads a one-frame-lagged `radius`" — **resolved**: sizes now come from one
  `VescClusterGeometry.fit` call at layout time, not two independently-updated properties.
- §12.4's `placeRelative` RTL bullet and §12.3's Russian-shrink-guard / CONSUMPTION-differs-by-style
  bullets are **NOT resolved** — the first is carried forward as item 5 below (same defect, new
  class name); the others are untouched by a Classic-only rewrite and still stand as originally
  recorded. §12.4's Clean-hero `speedKnown` bullet likewise stands, untouched.

### 15.3 New debt (found at the B3 merge gate, task-7, 2026-07-25)
1. **The session auto-scale's ceiling and outlier-rejection were added at this same merge gate, so
   most of what this item would have said is fixed rather than owed — but the ceiling itself was
   originally wired into Current and Power only, and this item's own justification for leaving
   Speed out was wrong.** ~~Speed's own `sessionMaxSpeedKmh` tracker (`RideDashboardScreen`) still
   commits on a single sample with no analogous ceiling derivation, so a corrupted speed frame could
   still transiently distort the hero scale — just not into a ragged ring, since `heroLabelStep`'s
   fallback steps keep any magnitude round (§12.3, resolved above).~~ **That claim was false and is
   corrected here rather than silently rewritten:** `heroLabelStep`'s fallback list only guarantees
   the label step it picks divides the (snapped) span evenly — it does nothing about
   `VescGaugeRange.tickmarkCount`'s own `min(MAX_TICKMARK_COUNT, naturalCount)` cap. A single bad
   speed sample (e.g. a display max of 2000 km/h-or-mph: labelStep 20, natural count 101, capped to
   100, `tickmarkSectionValue = 2000/99 = 20.2…`) produced exactly the ragged ring this bullet
   claimed could not happen — on the hero, the largest dial in the cluster, from a single sample,
   permanent for the rest of the session. Worse than Current/Power's original version of the same
   defect, because `vehicleMaxSpeed` feeds BOTH renderers (Clean's hero ring and Classic's hero
   dial), not Classic alone. **Fixed** (task-7 addendum, same date): `ClassicDialSpecs.heroDisplayMax`
   now clamps to `tickCapCeiling(HERO_SNAP_STEP)` (990 km/h or mph) exactly as Current/Power already
   did, `tickCapCeiling` itself generalized to document why the same bound holds for the hero's
   unipolar range with `heroLabelStep`'s fallback — see `ClassicDialSpecsTest.kt`'s
   `the_hero_ceiling_sits_exactly_where_the_tick_cap_would_start_ragging_the_ring`, which pins the
   990 boundary and shows 1010/2000 both clamp back to it.

   **What genuinely remains deferred**, now that all three runtime scales share one ceiling: it
   is a Now-divergence in the same spirit as §14's floors — `GET_MCCONF` supersedes it the day
   Part C lands, same as the floors — and `SessionPeakTracker`'s 3-consecutive-sample debounce is
   still wired to Current and Power only. Speed's own `sessionMaxSpeedKmh` tracker
   (`RideDashboardScreen`) still commits on a single sample, so one corrupted speed frame can still
   move the hero scale within the now-clamped `0..990` range — it can no longer push the scale past
   that ceiling, and (per the correction above) it was never true that this could produce a ragged
   ring either way; what is left is ordinary transient distortion within a bounded, still-round
   range, the same category of risk Current/Power carried before `SessionPeakTracker` existed for
   them.
2. **"Session" means two different things on this screen, spelled the same way.** The dial
   auto-scale trackers (`RideDashboardScreen`'s `sessionMaxSpeedKmh`/`currentPeakTracker`/
   `powerPeakTracker`) key their reset on the VEHICLE — they reset when the rider switches vehicles,
   and survive a BLE reconnect to the same one. The uptime clock (`RideDashboardComponent`'s
   `sessionStartedAt`/`uptimeSeconds`) keys its reset on the CONNECTION — it resets on every
   transition away from `Connected`, including a reconnect to the same vehicle. Both are correct for
   what they are individually documented to do; nothing enforces that a reader picks the right
   mental model for which "session" a given piece of state means.
3. **`VescDialGeometry.valueToAngle` had no degenerate-range guard.** Found and fixed within this
   same merge gate: the predecessor this file replaced (`DialGeometry.fraction`) guarded
   `max == min` and had a test pinning it; both vanished in the rewrite. No range this project
   actually builds is degenerate today, so this was latent, not reachable — restored anyway (the
   guard now short-circuits to `minAngle` instead of dividing by zero) along with its test,
   rather than left as a second silent gap alongside item 4 below.
4. **The caption-fit tests model Roboto advances and a 1.17 line height rather than measuring, and
   the two-pass measurement it stands in for has no coverage at all.** `VescDialGeometryTest`'s
   `captionBudget`/`width` helpers hand-model Roboto Regular's per-character advances (real numbers,
   not the estimate constants §12.2 used to flag) to keep the arithmetic Compose-free and testable —
   but `VescDialGauge.fitCenterTextLine`, the actual two-pass `TextMeasurer` call the renderer
   draws with, is exercised by nothing, for the same "no Compose UI-test dependency in this project"
   reason §12.2 gave for the emphasis-wiring gap. Whoever adds Compose UI tests first should claim
   this alongside that one.
5. **RTL: `VescClusterLayout.kt:100` uses `placeRelative`, so the Classic cluster mirrors under an
   RTL locale.** Carried forward from §12.4's identical note about the deleted `ClusterLayout`,
   retargeted to its successor — the defect moved house, unchanged, across the rewrite. No RTL
   locale ships yet.
6. **Imperial consumption is converted inconsistently between the two styles.** Classic's
   consumption dial (`ClassicDialSpecs.build`, via `UnitFormatter.consumptionValue`/
   `consumptionUnit`) genuinely converts to Wh/mi for an imperial rider. Clean's consumption card
   (`RideDashboardScreen.ConsumptionCard`) hardcodes the literal `" Wh/km"` suffix regardless of
   `state.units` — an imperial rider reading Clean sees a km/h speedometer next to a Wh/km
   consumption card. Distinct from §12.3's already-recorded "CONSUMPTION means slightly different
   things per style" bullet (that one is about instant-vs-session-fallback semantics, not units);
   both are open, both belong to whoever picks one behaviour for F.
7. **`ClassicDialLabels`' default captions are unpinned.** `default_labels_match_the_
   pre_localization_english_faces` — the test that once pinned `ClassicDialLabels()`'s defaults
   (`"CURRENT"`, `"POWER"`, `"DUTY"`, …) against the pre-localization English dial faces — was
   deleted with the B2 renderer (`b1bffba`) and never replaced. The defaults still exist (every
   `ClassicRideCluster` call site supplies its own localized labels, so they are not exercised in
   production) and only really matter as a fallback for a test fixture or a future non-Compose
   caller, but nothing stops one of them drifting from the English face it is supposed to match.
