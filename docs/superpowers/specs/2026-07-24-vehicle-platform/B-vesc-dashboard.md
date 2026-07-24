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

## 7. Ride dashboard (`presentation/ride/`)
New `RideDashboardComponent` + `RideDashboardScreen`, reading
`bmsRepository.activeMotion` (+ `activeVehicleData` for battery). Material 3
Expressive redesign of the native VESC RT-DATA concept (radial gauges kept as the
concept; dynamic color, expressive shapes/motion; no skeuomorphic dials). Exact
gauge layout is mocked visually at implementation (frontend-design / visual
companion). Composition, top→bottom:
1. `VehiclePill` (reused) — identity + connection/partial state.
2. **Hero radial speedo** — big `speedKmh` (km/h or mph), arc scaled to a
   per-vehicle max; a dash when `!speedKnown`.
3. **Gauge cluster** (cards/mini-gauges): **Duty %** (the ШИМ — prominent, colored
   by proximity to limit), **Power kW**, **Battery %/V** (from aggregate), **ESC
   °C** / **Motor °C**.
4. **Consumption**: Wh/km (session), avg — reuse moving-average.
5. **Strip**: odometer / trip / uptime.
- Reuse `MetricCard`, `SparklineGraph` (speed or duty sparkline), `PowerRangeBar`.
- The Graph screen gains motion metrics (speed/duty/power) in its metric switcher.

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

## 11. Decisions / open questions
1. **SETUP as the primary poll.** Gives speed + battery_level in one request;
   GET_VALUES is the fallback. Confirm 5–10 Hz poll is comfortable over BLE.
2. **Ride is home only with controllers.** A pure-BMS vehicle keeps today's UX
   (Battery home, no Ride tab). Confirm.
3. **Graph demotion** from a top tab to a button — confirm the interaction.
4. Motor-config auto-read (`COMM_GET_MCCONF`) — nice-to-have; may defer to G.
   In B, `MotorConfig` is only needed for the GET_VALUES DERIVED fallback.
