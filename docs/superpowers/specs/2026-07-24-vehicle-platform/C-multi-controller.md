# Part C — Multi-controller, CAN forwarding & the head-unit gateway

| Field | Value |
|---|---|
| Part | C |
| Depends on | A, B |
| Blocks | — (E reuses its independent-links path) |
| Hardware | his scooter: nyxdash head unit + 2×uBox on CAN + ANT. Also validatable with any VESC master + one CAN slave. |

> Read `00-overview.md`, `01-linking.md`, `A-foundation.md`, `B-vesc-dashboard.md`
> first. Part C makes a **single BLE link carry several controller sources and a
> hosted/forwarded battery** (his whole scooter through one head-unit link), and
> aggregates **independent** controller links (dual-VESC-BLE / FarDriver AWD).
> The multi-controller *aggregation* already exists (`MotionAggregator`, Part A);
> C adds the **transport** and the **gateway multiplexer**.

## 1. Scope
**In:** the CAN-forwarding transport (`PING_CAN`, `FORWARD_CAN`); a
`VescGatewayProtocol` that multiplexes several CAN controllers + a hosted battery
over one link; the **VESC-BMS decoder** (`COMM_BMS_GET_VALUES`); latent
controllers that appear when a sleeping uBox wakes; independent controller links;
the **alias-path handoff** policy (release the ANT to the head unit, read the
hosted BMS) using per-link disconnect (`A §4.6`) + alias collapse (`A §3.1`).
**Out:** FarDriver frames (E — but its AWD reuses C's independent-links path);
composer UI + duplicate warnings (G); motion alerts (F).

## 2. CAN transport primitives (`data/bms/vesc/`)
Pinned opcodes (VESC Tool `datatypes.h`): `COMM_FORWARD_CAN = 34`,
`COMM_PING_CAN = 62`, `COMM_BMS_GET_VALUES = 96`, `COMM_GET_VALUES = 4`,
`COMM_GET_VALUES_SETUP = 47`. CAN status broadcast ids (for reference/optional
passive read): `CAN_PACKET_STATUS = 9`, `_2 = 14`, `_3 = 15`, `_4 = 16`,
`_5 = 27`, `_6 = 58`; BMS broadcast `CAN_PACKET_BMS_V_TOT = 38 … _SOC_SOH_TEMP_STAT = 45`.

- **`PING_CAN` (62)** → the gateway replies with the list of CAN ids present on
  the bus. Used to discover which controllers/BMS exist behind a gateway.
- **`FORWARD_CAN` (34)** — payload `[34, targetCanId, innerCommandBytes…]`. The
  gateway relays the inner command to the CAN node and forwards its reply back
  over BLE. Wrap `GET_VALUES`/`GET_VALUES_SETUP`/`GET_MCCONF` to read a specific
  uBox. (Confirm the exact reply framing — whether it arrives as a plain
  `COMM_GET_VALUES` response or wrapped — against a live capture; pin at
  implementation.)
- **`BMS_GET_VALUES` (96)** to the gateway → the head-unit's hosted battery (§4).

## 3. `VescGatewayProtocol` — the multiplexer
One `BmsProtocol + MotionSource` object per gateway link, decoding several
sources. It supersedes the single `VescProtocol` when a link owns >1 source or a
CAN source.

- **Ownership** comes from the extended `LinkSpec` (see §6): a list of owned
  controllers (each with a `canId`) and owned battery sources (a hosted VESC-BMS,
  or a CAN-forwarded one).
- **Poll loop** round-robins per owned source, respecting a total BLE budget:
  - each controller `canId` → `FORWARD_CAN(canId, GET_VALUES)` (per-unit temps,
    duty, currents, rpm, tach, fault). Optionally one `FORWARD_CAN(canId,
    GET_VALUES_SETUP)` per controller for a unit-computed speed (REPORTED); else
    derive speed from rpm + that controller's `MotorConfig`.
  - hosted battery → `BMS_GET_VALUES` (no forwarding — the gateway answers it).
- **Decode**: each forwarded reply is decoded by the VESC decoder from Part B and
  routed to its **global controller index** via `MotionSource.latestMotion`; the
  BMS reply is decoded (§4) and exposed via `latestData(packIndex)`.
- **`controllerCount` / `packCount`** reflect the owned sources. A uBox that is
  asleep (no `FORWARD_CAN` reply, or absent from `PING_CAN`) simply produces no
  new sample → its controller goes **offline/latent** via the existing staleness
  sweep, while the hosted battery keeps reporting. This is the "24/7 head unit,
  sleeping controllers" behaviour, for free.

## 4. VESC-BMS decoder (`COMM_BMS_GET_VALUES`, 96)
Field order (pinned from `commands.cpp`): v_tot(d32/1e6), v_charge(d32/1e6),
i_in(d32/1e6), i_in_ic(d32/1e6), ah_cnt(d32/1e3), wh_cnt(d32/1e3),
`cells`(u8) then cells×v_cell(d16/1e3), cells×is_balancing(u8),
`sensors`(u8) then sensors×temp(d16/1e2), temp_ic(d16/1e2), temp_hum(d16/1e2),
humidity(d16/1e2), temp_cells_highest(d16/1e2), soc(d16/1e3), soh(d16/1e3),
can_id(u8), [totals ah/wh chg/dis (d32auto ×4), pressure, data_version, status
string — parse defensively by remaining size].

Map → `BmsData`: `voltage = v_tot`, `current` from `i_in` (**reconcile sign** —
BmsData is + = charging; the head unit sets `is_charging = current > 0.05`, so its
`i_in` is + = charging already; confirm on capture), `soc = soc*100`,
`socKnown = true` (VESC-BMS coulomb-counts), `cellVoltages = v_cells`,
`temperatures = temps`, balancing flags, `numCycles` n/a. `BmsType.VESC_BMS`.

> This is the path that reads the user's ANT **through** the head unit — Volty
> does not re-parse ANT here; the head unit already translated ANT → VESC
> `bms_values`. The direct-ANT decoder (existing `AntBmsProtocol`) is the *other*
> alias path (`§5`).

## 5. Alias-path handoff (his scooter, riding vs parked)
His ANT battery has two paths in one vehicle config, sharing an `aliasGroup`
(`A §2.1`, `01-linking §4`):
- **direct ANT** — `AntBmsProtocol` over the ANT's own BLE address (parked).
- **hosted VESC-BMS** — via the head-unit link (riding; the head unit owns the
  ANT's single central slot).

Policy:
- `PackAggregator` alias-collapse (`A §3.1`) already guarantees the battery is
  counted once and stays visible on whichever path is online.
- **Contention avoidance**: when the head-unit link is up and delivering the
  hosted battery, the app **releases the direct ANT link**
  (`disconnectLink(antAddress)`, `A §4.6`) so the head unit can hold the ANT.
  When the head-unit link drops (parked, head unit chosen by VESC Tool), the app
  re-raises the direct ANT link. Expose a per-vehicle toggle **"yield BMS to head
  unit while riding"** (default on when an alias group spans a direct BMS + a
  gateway-hosted BMS).
- This keeps the dashboard/alerts on one seamless battery across the ride.

## 6. `LinkSpec` extension (CAN sources carry a kind)
Part A left `OwnedSource(globalIndex, canId)` and asserted `canId == null`. C
lifts that:
```kotlin
data class OwnedSource(
    val globalIndex: Int,
    val canId: Int? = null,
    val kind: ProtocolKind? = null   // for CAN/hosted sources whose decoder differs
)                                    // from the gateway link's own kind
```
A gateway `LinkSpec` (kind `VESC`) can own controllers (`kind = VESC`, various
`canId`) and a battery (`kind = VESC_BMS`, hosted). `planLinks` now accepts
`canId != null`, grouping by gateway `address` and tagging each owned source with
its `kind`. The gateway multiplexer reads these to know what to poll and how to
decode. `LinkPlanTest` extends for his scooter's one-link, three-source plan.

## 7. Independent controller links (dual-VESC-BLE, FarDriver AWD)
No CAN: two separate BLE links, each a single-controller `VescProtocol` (Part B)
or `FarDriverProtocol` (Part E). Multi-link orchestration already raises both;
`MotionAggregator` (Part A) folds them (speed/duty max, current/power sum, temps
max, odo/trip max). Nothing new in the transport — this path is just A + B ×2.
FarDriver reuses it verbatim.

## 8. Testing
- `PingCanTest` / `ForwardCanTest` — build/parse `PING_CAN` id lists; wrap
  `FORWARD_CAN(canId, GET_VALUES)` and unwrap the reply to the right global index.
- `VescBmsDecoderTest` — decode a captured `COMM_BMS_GET_VALUES` frame (ideally a
  real one off his head unit) → `BmsData`; sign, scales, cell/temp counts,
  defensive trailing-field parse.
- `VescGatewayProtocolTest` — one link, two controllers + one hosted BMS:
  round-robin polling, per-source routing to global indices, a sleeping
  controller goes offline while the BMS stays online, a waking controller
  materialises (latent).
- Aggregation — his scooter: 2 controllers fold (current/power sum, speed/duty
  max, temps max); alias battery counted once across direct+hosted.
- Handoff — with an alias group spanning direct ANT + hosted VESC-BMS, raising the
  head-unit link releases the direct ANT link; dropping it re-raises. No battery
  gap in the aggregate across the swap.

## 9. Open questions
1. **Forwarded-reply framing** — whether `FORWARD_CAN GET_VALUES` replies arrive
   as a plain `COMM_GET_VALUES` response or wrapped with the source id. Pin from a
   live capture off the head unit.
2. **Controller vs BMS classification** on the bus — a `PING_CAN` id that answers
   `GET_VALUES` is a controller; the hosted BMS answers `BMS_GET_VALUES` to the
   gateway. Non-VESC ids (the head unit's own id, loggers) don't answer and are
   skipped after a timeout. Confirm the timeout budget doesn't starve the poll.
3. **Speed for slaves** — forward `GET_VALUES_SETUP` per uBox for a reported
   speed, or derive from rpm? Reported is cleaner but costs an extra request per
   unit per cycle. Default: one SETUP to the primary uBox for vehicle speed +
   GET_VALUES to all for per-unit current/temp/duty.
4. **Handoff aggressiveness** — release the direct ANT immediately when the head
   unit link is up, or only when the hosted BMS actually reports? Prefer the
   latter (no battery gap if the head unit link is up but the hosted BMS is
   momentarily silent).
