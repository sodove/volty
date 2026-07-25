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

---

## 10. Open questions — ANSWERED FROM SOURCE (2026-07-25)

Pinned by reading two real implementations rather than a capture:
- **Gateway side** (what we talk to): `E:\sodovaya\nyxdash\firmware\components\vesc_express\src\`
  — `commands.c`, `comm_can.c`, `bms.c`, `ant_bms.c`, plus `vesc_core/src/buffer.c`.
- **Client side** (known-good): VESC Tool `commands.cpp`.

### 10.1 §9.1 Forwarded-reply framing — **BARE, and forwarding MUST be serialised**

`commands.c:369-372` handles `COMM_FORWARD_CAN` by storing the caller's reply
function in a **single global** `send_func_can_fwd` and relaying the inner bytes
with `send=0`. The remote node answers as if asked directly; the gateway passes
those bytes back through `commands_send_packet_can_last` (`commands.c:1091-1099`)
**untouched** — no `COMM_FORWARD_CAN` re-wrap, no source-id byte.

VESC Tool confirms it: `processPacket` has **no** `COMM_FORWARD_CAN` case at all.
It wraps on the way out (`emitData`, `commands.cpp:2357-2382`) using client-side
state `mSendCan`/`mCanId`, and parses replies purely by opcode.

**Therefore: exactly one forwarded request in flight.** Not a style choice —
four independent reasons:
1. the binary protocol has no transaction id (`packet.c:41-73` is length + CRC only);
2. two forwarded replies to the same inner opcode are byte-identical, so only
   arrival order distinguishes them;
3. the gateway keeps ONE `send_func_can_fwd` and ONE `rx_buffer_last_id`
   (`comm_can.c:93`) plus 3 shared reassembly buffers — a second forward before
   the first reply **races state on the gateway**, whatever the client does;
4. VESC Tool's own `mCanId` is a single field, i.e. "I am talking to node N",
   never "N1 and N2 are outstanding".

Poll loop consequence: target → wrapped request → **await the bare reply, matched
by expected opcode with a timeout** → only then the next. No pipelining. The
round-robin budget in §3 must be sized against this.

### 10.2 §9.2 `PING_CAN` — blocking ~2.55 s, and a second one is silently dropped

`commands.c:151-165`: reply is `[62][id]…` — one raw byte per responding id,
**no count prefix, no terminator**; the count comes from the packet length.
Scans ids 0..254 ascending; 255 is never probed.

Each probe waits **10 ms** for a PONG (`comm_can.c:1106-1124`) with no early
exit, so the scan is ~2.55 s in essentially every case. It runs in the separate
`block_task`, so other I/O keeps flowing — but `is_blocking` is set for the whole
duration and a second `PING_CAN` arriving meanwhile is **silently discarded with
no error reply** (`commands.c:1064-1075`). A client that retries on timeout gets
silence, not a second answer. VESC Tool allows 5 s (`commands.cpp:1821-1832`).

### 10.3 §9.3/§9.4 unchanged
The §9.3 default (one SETUP to the primary uBox + GET_VALUES to all) and §9.4
preference (release the direct ANT only once the hosted BMS actually reports)
stand. §10.1 makes §9.3 more attractive still: every extra forwarded request is
a full serialised round-trip.

### 10.4 §4 BMS field list — confirmed field-by-field, with three traps

`bms.c:298-360` matches §4 exactly for fields 1-18. Three things §4 did not say:

- **`pressure` uses scale `1e-1`**, i.e. decode = `raw × 10` — the inverse of
  every other d16 in the frame. Trivial to implement backwards.
- **`can_id` is an `int` truncated to one byte and initialised to `-1`**
  (`bms.c:53`), so "no BMS data yet" arrives as **`0xFF`**, not `0`. On
  disconnect the firmware memsets the struct and restores `-1`
  (`ant_bms.c:901-907`).
- **Trailing fields are gated on remaining byte count**, and the four
  charge/discharge totals are **all-or-nothing** (`>= 16` bytes, four
  `float32_auto` — a bit-packed custom float, *not* a fixed divisor). Parse
  defensively in VESC Tool's exact order (`commands.cpp:709-772`).

**Sign convention resolved: `i_in > 0` means CHARGING** (`ant_bms.c:658,685`,
`is_charging = current > 0.05`). That already matches `BmsData`'s convention —
no flip needed, and §4's "reconcile sign" caveat is discharged.

### 10.5 What an ANT battery behind the gateway actually populates

`ant_bms.c:653-689` fills: `v_tot`, `i_in`/`i_in_ic`, `soc`, `soh`, `ah_cnt`,
`wh_cnt`, per-cell voltages, balancing flags, per-sensor temps, `temp_ic`,
`temp_max_cell`, `is_charging`, `can_id`.

**Always zero/empty on the ANT path** — must not be surfaced as real data:
`v_charge` (explicitly zeroed), `humidity`, `pressure`, `status`, all four
chg/dis totals, `data_version`. The firmware's own CAN re-broadcast helper says
as much: *"skips empty STATUS_1..5, CHG/DIS totals"*.

This is the user's own battery path, so it is the case that matters: the decoder
must distinguish "field absent" from "field is zero", or the Battery screen will
report a real 0.0 V charge voltage and 0% humidity.

---

## 11. Debt carried out of Part C (recorded at merge, 2026-07-26)

Merged at `65c62cb`, 822 tests. Every open question in §9 was pinned from the
gateway firmware before implementation (§10). What follows is what the reviews
found and we consciously did **not** fix — read it before touching this area.

### 11.1 There is no CI, and three parts now depend on one
`B-vesc-dashboard.md §12.1`, `G1`'s register and this part all say "CI must run
`verifyCommonMainVoltyDatabaseMigration` before shipping". **That instruction is
currently unexecutable**: the repo has no `.github/` and no CI configuration of
any kind. Worse, even once the local `NativeDB._open_utf8` failure is fixed, the
task has nothing to migrate *from* — `composeApp/build.gradle.kts` sets no
`schemaOutputDirectory` and no `.db` snapshots are committed.

The schema is now at **v6** with five migrations. They are checked by hand-written
JDBC chain tests, which catch a missing or misnamed column but **cannot** catch
`NOT NULL`-ness, `DEFAULT`s or declared-type drift, and whose frozen base DDL is
hand-copied rather than produced by running the migrations.

**Prerequisite, not a follow-up:** wire `schemaOutputDirectory`, commit the
snapshots, stand up any CI, then run the verifier.

### 11.2 The oversized-plan guard fails in the direction it was meant to prevent
`VescGatewayProtocol` throws at construction when a plan exceeds ~10 sources,
because the serialised poll cycle could not meet the watchdog budget. On the
**reconnect** path `connectLinkAttempt` catches that into a FAILED link whose loop
retries a construction that can never succeed — "reconnect forever", exactly the
outcome the guard exists to prevent. Unreachable today (nothing can author an
11-source plan; `VehicleEditComponent` never writes a `canId`), and it surfaces as
`ConnectionState.Failed` with an actionable message rather than a crash.
Prefer clamping `replyTimeoutMs` to fit the budget, or moving the check to
`planLinks`/vehicle-save so it fails at authoring rather than on the road.
`KableBmsRepository.kt:1923`'s "constructing it this early cannot fail" is now false.

### 11.3 Timing constants are reasoned, never measured
Every number in the poll loop — reply timeout, pacing, the silence budget, the
handoff hold-down — was derived from the firmware's behaviour, not from a capture.
`WATCHDOG_SILENCE_BUDGET_MS` also duplicates `BleConfig.staleSampleMs` (equal
today) with nothing pinning them together; a one-line `assertEquals` in commonTest
would remove the drift risk.

### 11.4 Smaller, all confirmed by review
- **`ah_cnt` is unmapped on purpose.** nyxdash means *remaining* Ah, stock VESC
  means *cumulative*, and nothing on the wire identifies the firmware (§10.5
  zeroes every field that could). `BmsData.cycleCapacityAh` is the stock-VESC
  destination if a discriminator ever appears. Deciding a vehicle's head-unit
  firmware is composer work (G2).
- **No derived battery on a gateway link** — deriving from one uBox would halve
  the current. A head-unit vehicle with no BMS shows no battery until G2.
- **The "yield BMS to head unit" toggle has no UI.** It is persisted and honoured,
  but `VehicleEditScreen.onSave` would collapse the vehicle's two packs and
  destroy the alias group, so exposing it there would break the configuration
  underneath it. G2 owns the screen that can.
- **`parsePingCan` has no production caller.** CAN discovery is G2's composer
  feature; the codec is spec'd (§2) and tested, waiting for it.
- **`VescBmsValues.BMS_TYPE` has no reader** — remove it, or reintroduce it with
  the consumer that needs it.
- **Flapping is damped but the damper rode in on a merge-fix wave** without its own
  review. A 60 s hold-down; failure direction is safe (both links up = battery
  present) and it clears on plan reset.
- **`≥10` consecutive silent sources × 500 ms would exceed `staleSampleMs`** with
  no guard. Fine at 4; unguarded as plans grow.
- **Vehicle Edit describes one pack and one controller** for a mixed vehicle (G2).
- **"Device not found" is hardcoded English** in an otherwise localized UI.

### 11.5 What only the real head unit can settle
1. the forwarded-reply timing budget against a live capture;
2. whether `PING_CAN`'s ~2.55 s block disturbs the poll loop in practice;
3. which fields an ANT behind the gateway actually populates;
4. release/re-raise and flapping behaviour on real radios;
5. that a live connect really selects `VescGatewayProtocol` with real data;
6. `ah_cnt`'s meaning on the actual firmware.
