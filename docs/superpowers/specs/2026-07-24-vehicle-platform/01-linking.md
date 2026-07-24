# 01 — Linking, addressing & transport topologies

| Field | Value |
|---|---|
| Kind | Shared reference (read by A, B, C, D, E, G) |
| Status | Draft — pending user review |

How a vehicle's abstract **sources** (packs + controllers) map onto BLE links,
how CAN-forwarded and gateway-hosted sources are reached, the catalog of real
transport archetypes, and the rules that resolve the messy cases (separate BMS,
alternate-path batteries, cross-address identity). Every part that touches
connection or composition references this file.

## 0. Gateway kinds (terminology)

A **gateway** is a BLE endpoint that fronts other sources on a CAN bus. Two kinds
behave differently and the specs must not conflate them:

- **Controller-gateway** — a real VESC motor controller with its own BLE (a uBox
  with a BLE module). Its own `GET_VALUES`/`GET_VALUES_SETUP` are real (it is a
  controller), and SETUP aggregates the whole CAN bus.
- **Head-unit gateway** — a dashboard / head unit running VESC Express (the
  user's *nyxdash*). It is **not a motor controller**, so its own
  `GET_VALUES`/`SETUP` are empty — it has no direct UART to a motor. It reaches
  controllers **only by CAN-forwarding**, and it **hosts** a battery by answering
  `COMM_BMS_GET_VALUES` with values it derives from an ANT BMS it reads over its
  own BLE-client link. It is powered **24/7** (reachable even while the
  controllers sleep) and doubles as the BLE module for VESC Tool — so only **one
  BLE central at a time** (VESC Tool *or* the app).

## 1. Addressing model

Every source — a `Pack` or a `Controller` — is reached through exactly one BLE
endpoint, optionally with a CAN id behind it:

- **`address: String`** — the BLE endpoint you connect to: the source device
  itself, or a gateway (controller-gateway or head unit) that fronts it.
- **`canId: Int? = null`** — `null`: the source *is* that BLE device (direct), or
  is **hosted by** the gateway you connected to (a head unit answering
  `COMM_BMS_GET_VALUES` for its ANT-derived battery). Non-null: the source lives
  on the CAN bus behind `address`, reached by CAN-forwarding.

`planLinks` groups sources by `address`. One link per BLE endpoint; a link owns
every source at its address, each tagged with its `canId`. So one BLE link to a
gateway can own CAN slave controllers (`canId=41,42…`) plus a hosted/forwarded
battery — all over one connection. The concrete request per source is the
decoder's job (Part B/C): `COMM_GET_VALUES`/`SETUP` for a controller,
`COMM_BMS_GET_VALUES` for a VESC-BMS, `COMM_FORWARD_CAN` to reach a CAN id.

> Why not a `SourceLocator` sealed type? "address = the BLE endpoint you connect
> to" holds for direct, hosted, and CAN sources alike; only "which node behind
> it" is new, and a nullable `canId` expresses that with near-zero churn.

**Boundary:** the addressing *model* lands in Part A. The wire-level gateway
transport (`PING_CAN` enumerate, `FORWARD_CAN` wrap, `BMS_GET_VALUES`, demux) is
**Part C**. In Part A every source is direct (`canId == null`, no forwarding).

## 2. Speed provenance & the access modes

`ControllerData.speedKmh` is filled two ways, tracked by `speedSource`:

- **REPORTED** — the device computes speed. `COMM_GET_VALUES_SETUP` (opcode 47)
  carries **speed in m/s** (`vbPopFrontDouble32(1e3)`) and a **battery_level
  0..1**, from the unit's own motor/battery config.
- **DERIVED** — only eRPM available (plain `COMM_GET_VALUES` (4) has `rpm`, no
  speed). `speed = eRPM ÷ polePairs ÷ gearRatio × wheelCircumference`, needs
  `MotorConfig`.
- **NONE** — neither → `speedKnown = false`, UI shows a dash.

Access modes, and which apply to which gateway:

| Mode | Applies to | Data | Speed |
|---|---|---|---|
| **Own SETUP** — one `GET_VALUES_SETUP` to the endpoint | direct VESC, or a **controller-gateway** (aggregates its CAN bus) | combined currents/Ah/Wh + speed + battery_level; no per-unit temps/duty | REPORTED |
| **CAN forwarding** — `FORWARD_CAN` + `GET_VALUES`(/`SETUP`) per CAN id | any gateway, **required for a head unit** (its own values are empty) | **full per-unit** MC_VALUES (temps, duty, currents, tach, fault); forwarded SETUP also gives per-unit speed | forwarded SETUP → REPORTED; forwarded GET_VALUES → DERIVED |
| **Hosted BMS** — `COMM_BMS_GET_VALUES` to the endpoint | a **head unit** hosting an ANT-derived battery | battery only (v/i/cells/temps/soc) | n/a |
| **Passive CAN** — listen to `CAN_PACKET_STATUS` broadcasts | on-bus sniffers only (not the phone) | STATUS_1 (eRPM/current/duty) always; STATUS_2..6 if enabled | DERIVED |

**Rule for the user's scooter (head-unit gateway):** the head unit gives **no
speed of its own** — motion comes **only from CAN forwarding** to each uBox
(`FORWARD_CAN` + `GET_VALUES`, derive speed from rpm + `MotorConfig`; or forward
`GET_VALUES_SETUP` for a uBox-computed speed). Battery comes from the hosted
`COMM_BMS_GET_VALUES` and is available even while the uBoxes sleep. `MotorConfig`
is the fallback; Part B/G can read a uBox `mcconf` (poles / wheel diameter /
gearing) over forwarding to auto-fill it. Duty is always device-reported and
stays the primary safety signal.

## 3. Transport archetype catalog

"ctrl" = controller source, "batt" = battery pack source.

| # | Transport | Links | Sources / access | Notes |
|---|---|---|---|---|
| 1 | **His scooter, via head unit** (nyxdash / VESC-Express): 2×uBox on CAN + ANT | **1** | L1(head unit BLE) = ctrl0,ctrl1 via `FORWARD_CAN`+`GET_VALUES`; batt via hosted `BMS_GET_VALUES` | Head unit is on 24/7: **battery always online; controllers online only while the uBoxes are awake** (latent/offline sources on the one link until they wake). One BLE central at a time (VESC Tool vs app). |
| 1b | His scooter, direct: uBox BLE + 2×ANT direct | 3 | L1(uBox controller-gateway) = ctrl0 + ctrl1 (CAN-fwd, own SETUP for speed); L2,L3 = ANT batt (direct BLE) | Parked / head unit not used. The direct ANT is an **alternate path** to the same battery as #1 (§4). |
| 2 | Dual VESC independent BLE + BMS | 2+N | one ctrl per VESC link; batteries on own links | No CAN; ctrl links aggregate in Part C. |
| 3 | **EUC w/ smart BMS in wheel** (Begode/Veteran) | 1 | L1 = Begode ctrl0 + batt[0,1] (same address) | Wheels-with-smart-BMS are almost always one link. |
| 4 | EUC + enthusiast-added separate BMS | 2 | L1 = wheel (ctrl + branches); L2 = added smart BMS | Composer marks the added BMS authoritative, turns the wheel's derived battery off. |
| 5 | EUC without smart BMS | 1 | L1 = ctrl + synthesised batt[0,1] | Derived from wheel voltage. |
| 6 | VESC board, no BMS | 1 | L1 = ctrl + derived batt (SETUP battery_level or VoltageSocEstimator) | |
| 7 | VESC + VESC-BMS on CAN | 1 | L1(VESC) = ctrl `canId=null` + VESC-BMS batt `canId=X` | Battery + controller over one BLE link. |
| 8 | FarDriver AWD (no CAN) | 2(+BMS) | one ctrl per FarDriver BLE link | AWD is independent links, aggregated in Part C. |

## 4. Alternate paths, duplicates & the ride-time handoff

A single physical battery can be reachable more than one way. Two cases:

**Accidental duplicate** — two sources that are unknowingly the same pack. The
composer (Part G) warns: same series-cell count + voltage tracking within a tight
band (or identical reported serial). Keep one.

**Intentional alternate paths** — the user's ANT is reachable *directly over BLE*
(parked) AND *via the head unit as a VESC-BMS* (riding, because the head unit
grabs the ANT's single BLE central slot). Same physical battery, used at
different times. Model:

- `Pack.aliasGroup: String?` — packs sharing a non-null `aliasGroup` are the same
  physical pack via different paths. `PackAggregator` collapses an alias group to
  **one** online member (priority: lowest `index`, else most-recently-seen), so
  two-online never double-counts and either-online keeps the battery visible.
- **Ride-time handoff** (Part C policy): when the head unit needs the ANT, the app
  **releases just that link** (`disconnectLink(address)` — not the whole vehicle)
  and reads the battery via the head unit's hosted VESC-BMS instead. The
  `aliasGroup` makes the two paths one logical battery, so the dashboard/alerts
  never notice the swap.

**Never auto-add CAN/hosted sources.** Discovery surfaces them; the user
includes/excludes each in the composer. Attaching a real VESC-BMS on a CAN id the
head unit already emulates is a hardware conflict outside the app's control; the
"one logical battery" guidance (aliasGroup) steers around it.

## 5. Identity & recognition across addresses

A vehicle spans several source addresses. Contract (scan/compose specs own the
implementation):

- **Recognition:** a saved vehicle is "in range" if **any** of its links'
  addresses advertise — and for archetype #1 the always-on head unit is a
  reliable anchor. Scan/auto-connect match on the *set* of a vehicle's BLE
  endpoints. A `primaryAddress` (first controller's endpoint, else first pack's)
  drives the auto-connect label and pill identity.
- **Connection:** activating a vehicle raises **all** its links (multi-link
  already does this). Per-link status folds into `ConnectionState` unchanged.
- **Partial is normal:** up on some links/sources, not others — battery online
  but the (sleeping) controllers offline, or the reverse. Not an error; each
  screen renders what it has.
- **Which battery counts:** a real BMS beats a controller-derived battery for the
  same pack (composer sets `providesDerivedBattery=false`); alternate paths to one
  BMS collapse via `aliasGroup`.
- **Association is explicit:** the app never guesses a nearby BMS belongs to a
  nearby controller. The user composes the vehicle (Part G); a link is just a
  configured source. This is the whole answer to "separate transport + BMS":
  two sources of one vehicle, connected together, aggregated on their own axes.
