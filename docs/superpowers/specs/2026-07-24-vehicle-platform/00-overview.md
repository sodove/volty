# Volty → Vehicle Telemetry Platform — Program Overview

| Field | Value |
|---|---|
| Date | 2026-07-24 |
| Status | Draft — pending user review |
| Author | sodove + Claude (brainstorm) |
| Kind | Program-level design (spawns per-part specs A–G) |

This is the umbrella design for turning **Volty** from a Bluetooth-BMS monitor
into a full **personal-EV telemetry app** (custom scooters, EUCs/monowheels,
e-bikes, DIY builds). It is deliberately split across several files so a coding
subagent reads only the part it implements. Each part (A–G) becomes its own
spec → plan → implementation cycle, exactly as `multi-pack-foundation` and
`multi-link-orchestration` were done before.

**Read this file first**, then `01-linking.md` (the shared addressing / topology
reference), then the part you are working on. The shared domain vocabulary, the
decomposition, sequencing, and the cross-cutting decisions live here and are NOT
repeated in each part.

---

## 1. What changes and why

Today `Vehicle` effectively *is* a battery: `packs: List<Pack>`, a chemistry, an
alert config. The pivot: **`Vehicle` becomes a transport with a set of data
sources.** A source is one of:

- a **battery source** — a smart BMS (JK/JBD/ANT/Daly/VESC-BMS) or a battery the
  protocol synthesises (a dumb Begode branch, or a controller-derived pack). This
  is today's `Pack`. A battery source may be a direct BLE device or reached over
  CAN through a controller (see `01-linking.md`).
- a **controller source** — a motor controller (VESC/uBox, FarDriver, Kelly KLS,
  a Begode mainboard) that reports *motion & thermal* telemetry: speed, duty
  (ШИМ), motor/battery current, ESC/motor temperature, odometer, consumption.
  (Not every controller reports every channel — Kelly KLS, for instance, has no
  duty.)

A single physical device (one BLE address) may be **both**: a Begode wheel
streams two battery branches *and* mainboard motion over one link; a lone VESC
reports motion *and* can back a voltage-derived battery. This "one device →
several logical sources" pattern is already proven on the battery side (Begode,
`packCount = 2`), so we extend it, we do not reinvent it.

### Positioning
Volty stops being "a BMS app" and becomes "a ride app that also has the best BMS
view". The three primary screens the product is organised around:

1. **Ride** — the dashboard (speed / duty / power / battery / temps), a Material
   3 Expressive redesign of the native VESC RT-DATA screen concept.
2. **Battery** — today's full BMS view (packs, cells, sections). Unchanged in
   substance; becomes a tab rather than the home screen.
3. **Settings** — app + per-vehicle configuration, incl. the new **vehicle
   composer** (add controllers/BMS, assign roles, motor & wheel config).

---

## 2. The Source model (the load-bearing decision)

We add a **parallel motion path** that mirrors the existing battery path
one-to-one, and leave `BmsData` and the battery pipeline untouched.

| Battery side (exists) | Motion side (new, mirrors it) |
|---|---|
| `Pack` (config: index, label, type, address, cellCount) | `Controller` (config: index, label, type, address, motor/wheel config) |
| `BmsData` (per-pack telemetry) | `ControllerData` (per-controller telemetry) |
| `PackState` (pack + data + online + lastSeen) | `ControllerState` (controller + data + online + lastSeen) |
| `PackAggregator` → `VehicleData.aggregate: BmsData` | `MotionAggregator` → `VehicleData.motion: ControllerData` |
| `BmsProtocol.latestData(packIndex)` / `packCount` | `ControllerProtocol.latestMotion(ctrlIndex)` / `controllerCount` |

Key invariant: **one BLE link, keyed by address, may own both battery pack
indices and controller indices** (`LinkSpec` grows `ownedControllers` alongside
`ownedPacks`, each owned source tagged with an optional `canId`). `planLinks`
merges a vehicle's packs *and* controllers by address into links. A source's
`address` is the BLE endpoint you connect to (the device itself, or a CAN master
that forwards it); `canId` picks which node behind that endpoint. This is what
makes every real-world topology fall out of the same machinery — the full
catalog, including CAN-forwarded batteries and separate-BMS wheels, is in
`01-linking.md §3`. A few illustrative rows:

| Real setup | Devices (BLE links) | Sources per link |
|---|---|---|
| Scooter: 2×uBox on CAN + 2×ANT | 3 links | master link → 2 controllers (1 local + 1 CAN-forwarded); each ANT → 1 battery |
| VESC + VESC-BMS on CAN | 1 link | 1 controller (local) + 1 battery (CAN-forwarded) |
| Begode EUC with smart BMS | 1 link | 1 controller + 2 batteries (same address) |
| EUC + enthusiast-added BMS | 2 links | wheel (controller + branches) + separate BMS |
| VESC skateboard, no BMS | 1 link | 1 controller + 1 derived battery |

### Alternatives considered (and rejected)
- **Unify Pack/Controller into one generic `Source` with typed channels.** More
  elegant on paper, but it rewrites `PackAggregator`, `VehicleConnection`,
  `planLinks` and every multi-pack/multi-link test — shipped, load-bearing,
  well-tested code — for no user-visible gain. High regression risk. Rejected.
- **Fold speed/duty/temps into `BmsData`.** Conflates battery and motion,
  pollutes the battery aggregator, breaks the honest per-pack model. Rejected.
- **Parallel motion path (chosen).** Zero change to the battery path; reuses the
  exact proven patterns (address-keyed links, single-funnel `submit`,
  per-source staleness sweep, latent slots, an aggregator). Same shape the team
  already knows.

---

## 3. Decomposition (parts A–G)

Each part is a separate spec file in this folder and a separate implementation
cycle. Dependencies point "up" the list.

| Part | File | Deliverable | Depends on |
|---|---|---|---|
| **A** | `A-foundation.md` | Domain + motion telemetry model, the `canId` addressing field, `MotionAggregator`, `LinkSpec`/`planLinks`/`VehicleConnection`/`ConnectionSession` generalised to carry motion, DB migration, demo motion simulator. No real controller protocol, no CAN transport. Validated by tests + demo. | — |
| **B** | `B-vesc-dashboard.md` | VESC BLE protocol (single controller) + the Ride dashboard + Ride/Battery/Settings tab restructure + units. First real end-to-end slice, runs on a single uBox. | A |
| **C** | `C-multi-controller.md` | Multi-controller aggregation + the CAN-forwarding transport: one link → N controllers **and** CAN-forwarded batteries (VESC-BMS), plus independent controller links and duplicate-source handling. Completes the 2×uBox + 2×ANT scooter. | A, B |
| **D** | `D-begode-controller.md` | Surface motion (speed/duty/mileage/temp) from the existing Begode frames; the wheel becomes controller + batteries over one link. | A, B |
| **E** | `E-fardriver.md` | FarDriver controller protocol (needs hardware + reverse-engineering references); AWD via independent links (no CAN). | A, B, C |
| **F** | `F-alerts-sound.md` | Safety alerts with **escalating tones + vibration**: duty/ШИМ, speed, motor/ESC temperature, controller fault. New continuous audible-alarm component (not one-shot notifications). | A, B |
| **G** | `G-vehicle-composer.md` | Vehicle composer UI: build a vehicle from N devices, assign roles, motor/wheel config, derived-battery rule; scan/type detection for VESC/FarDriver/Kelly. | A, B |
| **H** | `H-kelly-kls.md` | Kelly KLS controller via the ETS protocol, vendored from the user's own `kelly-connect/protocol` KMP module. Low-risk (working source exists). No duty telemetry. | A, B |

**Recommended build order:** A → B → (C ∥ D ∥ H ∥ F) → G → E. C, D, H and F all
sit on A+B and are largely independent of each other, so they can be built in any
order or in parallel by separate subagents. H (Kelly) is low-risk — the protocol
already exists in the user's own repo. G is most useful once there is more than
one source type to compose. E (FarDriver) is last — hardware-gated and needs
reverse-engineering — but scheduled right after the rest.

---

## 4. Cross-cutting decisions (apply to every part)

- **Platform:** Android-only, unchanged. Compose Multiplatform structure stays.
- **Aesthetic:** Material 3 Expressive (already the app's theme). The Ride
  dashboard keeps the *concept* of the native VESC RT-DATA screen (radial
  speedo as hero, gauge cluster, odo/trip/uptime strip) but re-skins it —
  dynamic color, expressive shapes/motion — no skeuomorphic dials. Exact gauge
  layout is mocked visually during Part B.
- **Units:** default **km/h + km + °C**; a `mph`/`miles` toggle in app prefs.
  All speed/distance rendering routes through a unit formatter. `ControllerData`
  always stores SI-ish canonical units (km/h, km, °C, A, V, W); conversion is a
  presentation concern.
- **Alert sound modality:** **tones/beepers (escalating near threshold) +
  vibration**. **No TTS** — we do not add a text-to-speech dependency.
- **Trip history / persistence:** **out of scope for this program.** Live ring
  buffer only, as today. A future "ride logging" sub-project owns on-disk trips
  (distance, max speed/current, Wh/km, history screen).
- **Read-only:** Volty does not *write* to controllers (no throttle/config
  writes). Telemetry read only, same stance as the BMS side.
- **Language of specs:** English (matches repo code + existing specs).

---

## 5. Shared glossary

- **Duty / ШИМ** — controller duty cycle, `|duty|·100 %`. The single most
  safety-critical number on a EUC: it is the headroom before the motor can no
  longer hold the rider up. Wheel riders need an *audible* warning as it climbs.
- **eRPM** — electrical RPM from the controller. Mechanical RPM = eRPM ÷
  motor pole-pairs; ground speed derives from mechanical RPM + wheel diameter
  (+ gear ratio, default 1).
- **Derived battery** — a battery `Pack` synthesised by a controller protocol
  from pack voltage/current when no smart BMS covers that source (reuses
  `VoltageSocEstimator`, the dumb-Begode mechanism).
- **CAN-forwarding** — a VESC master relays `GET_VALUES` from slave controllers
  on its CAN bus over one BLE link. One link, N controller sources.
- **Link** — one BLE connection to one address (`PackLink`), owning a set of
  battery and/or controller source indices.

---

## 6. Program-level risks

- **VESC firmware field-layout drift.** `COMM_GET_VALUES` payload layout varies
  across bldc firmware versions. Mitigation: pin the struct against a target
  firmware version at Part B implementation, parse defensively (length-checked),
  degrade unknown trailing fields to absent rather than crashing.
- **FarDriver has no official protocol.** Part E leans on community reverse-
  engineering and needs real hardware to validate. Kept last and isolated.
- **Audible alarms vs Android audio focus / Doze.** The tone alarm must sound
  with the screen off and the app backgrounded — it runs inside the existing
  foreground service and must handle audio focus and Doze. Detailed in Part F.
- **Composer complexity.** Building a 4-source vehicle by hand is fiddly. Part G
  must make the common case (one controller, or one controller + one BMS)
  trivial and the exotic case (2×uBox + 2×ANT) possible.
- **Duplicate / emulated sources.** The same physical battery can appear twice —
  a native ANT over BLE and an ESP32 dashboard re-exposing it as a VESC-BMS on
  CAN. Auto-adding both double-counts capacity/current. Mitigation: CAN sources
  are never auto-added, and the composer warns on likely duplicates
  (`01-linking.md §4`).
- **Scope creep back into one giant change.** Enforced by the part split: each
  part ships and is testable on its own; A and D are demo/simulator-validated
  without hardware.
