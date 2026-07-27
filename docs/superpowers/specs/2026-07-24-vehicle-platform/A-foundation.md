# Part A — Foundation: motion telemetry & the source model

| Field | Value |
|---|---|
| Part | A (foundation) |
| Depends on | — |
| Blocks | B, C, D, E, F, G |
| Hardware needed | none — validated by unit tests + demo simulator |

> Read `00-overview.md` and `01-linking.md` first. This part adds the **motion
> path** that mirrors the battery path, and generalises the orchestration so a
> link can carry motion sources and (in the config model) CAN-forwarded sources.
> It ships **no real controller protocol** — that is Part B. Goal: after Part A
> a demo/simulated controller drives a `VehicleData.motion` aggregate through
> the full pipeline, with the battery path byte-for-byte unchanged.

## 1. Scope

**In:**
- New domain types: `Controller`, `ControllerType`, `MotorConfig`,
  `ControllerData`, `SpeedSource`, `ControllerState`; `VehicleData` gains
  `controllers` + `motion`.
- `canId` + `aliasGroup` on `Pack`, `canId` on `Controller` (addressing +
  alternate-path model, see `01-linking.md §1, §4`).
- `BmsType.VESC_BMS` (battery source; direct BLE or CAN-forwarded/hosted).
- `MotionAggregator` (pure), mirroring `PackAggregator`; a small `PackAggregator`
  change for alias-group collapse.
- Per-link disconnect (`disconnectLink(address)`) — the handoff primitive.
- `MotionSource` capability interface for protocols; a `MotionSampleGate`.
- `ConnectionSession`, `routeControllerSamples`, `LinkSpec`, `planLinks`,
  `VehicleConnection` generalised to route + fold motion samples.
- Repository surface: `VehicleData.motion` flows through; `activeMotion`
  convenience StateFlow; motion retained in the ring buffer.
- Persistence: `Vehicle.controllers` + `canId` stored; SQLDelight migration.
- Demo: the simulated vehicle emits synthetic motion.
- The **derived-battery mechanism** (a controller protocol MAY expose a
  synthesised `Pack`); concrete synthesis-from-frames is per-protocol (B/D).

**Out:**
- Any real BLE controller protocol (Part B/D/E).
- **CAN-forwarding transport** — the wire-level enumerate/wrap/demux. Part A
  models `canId` and plans links with it, but only *direct* sources
  (`canId == null`) are exercised. Part C implements forwarding.
- Ride dashboard UI + tab restructure (Part B). Motion alerts (Part F). Composer
  UI (Part G).

## 2. Domain model (`domain/model/`)

### 2.1 `Pack` — add `canId`
Additive field, default `null` (every existing pack is direct BLE):
```kotlin
data class Pack(
    val index: Int,
    val label: String,
    val bmsType: BmsType,
    val bmsAddress: String,          // BLE endpoint (device, or CAN gateway)
    val cellCount: Int? = null,
    val canId: Int? = null,          // non-null → CAN-forwarded via bmsAddress (Part C)
    /**
     * Packs sharing a non-null aliasGroup are the SAME physical pack reached by
     * different paths (direct ANT over BLE vs the head unit's VESC-BMS over CAN —
     * see 01-linking §4). The aggregator counts exactly one online member of a
     * group, so alternate paths never double-count and failover is seamless.
     */
    val aliasGroup: String? = null
)
```
`BmsType` gains `VESC_BMS("VESC BMS", reportsStateOfCharge = true)`.

### 2.2 `Controller.kt` (new)
```kotlin
enum class ControllerType(val label: String) {
    VESC("VESC"),        // incl. uBox and other VESC-based ESCs
    FARDRIVER("FarDriver"),
    KELLY("Kelly KLS"),  // ETS protocol; see Part H. No duty/ШИМ telemetry.
    BEGODE("Begode")     // wheel mainboard; same device also yields batteries
}

/** Config of one controller source. Persisted. Address-keyed like [Pack]. */
data class Controller(
    val index: Int,                 // 0-based, vehicle-global; SEPARATE index space from Pack
    val label: String,
    val controllerType: ControllerType,
    val address: String,            // BLE endpoint (device, or CAN gateway)
    val canId: Int? = null,         // non-null → CAN-forwarded via address (Part C)
    val motor: MotorConfig = MotorConfig(),
    /**
     * When true and no battery source covers this controller's pack, the
     * protocol synthesises a voltage-derived [Pack] (reuses VoltageSocEstimator,
     * the dumb-Begode path). Composer sets false when a smart BMS is present.
     */
    val providesDerivedBattery: Boolean = false
)

/**
 * FALLBACK config for turning eRPM into ground speed — used ONLY when the
 * controller cannot report speed itself and does not sit behind a master that
 * does (see 01-linking §2). A VESC reports speed via GET_VALUES_SETUP and can
 * have these auto-read from its mcconf, so most users never set them.
 */
data class MotorConfig(
    val polePairs: Int = 15,
    val wheelDiameterMm: Int = 0,   // 0 = unknown → DERIVED speed unavailable
    val gearRatio: Float = 1f
)
```

### 2.3 `ControllerData.kt` (new — per-controller AND aggregate)
Canonical units: km/h, %, A, V, W, °C, km.
```kotlin
enum class SpeedSource { REPORTED, DERIVED, NONE }

data class ControllerData(
    val speedKmh: Float = 0f,
    val speedSource: SpeedSource = SpeedSource.NONE,
    val speedKnown: Boolean = false,   // == speedSource != NONE
    val dutyPercent: Float = 0f,       // |duty|*100 — the ШИМ, always device-reported
    val motorCurrentA: Float = 0f,
    val batteryCurrentA: Float = 0f,   // input current
    val inputVoltageV: Float = 0f,
    val powerW: Float = 0f,            // Vin * Ibatt
    val eRpm: Float = 0f,
    val escTempC: Float = 0f,
    val motorTempC: Float = 0f,
    val hasMotorTemp: Boolean = false, // many builds have no motor sensor
    val odometerKm: Float = 0f,        // lifetime distance
    val tripKm: Float = 0f,            // session distance
    val consumedAh: Float = 0f,
    val consumedWh: Float = 0f,
    val regenAh: Float = 0f,
    val regenWh: Float = 0f,
    val faults: List<String> = emptyList(),
    val isConnected: Boolean = false,
    val timestamp: Instant = Clock.System.now()
)
```

### 2.4 `ControllerState.kt` (new — mirrors `PackState`)
```kotlin
data class ControllerState(
    val controller: Controller,
    val data: ControllerData,
    val isOnline: Boolean = false,
    val lastSeenAt: Instant? = null
)
```

### 2.5 `VehicleData.kt` — extend (battery fields unchanged)
```kotlin
data class VehicleData(
    val packs: List<PackState> = emptyList(),
    val aggregate: BmsData = BmsData(),
    val topology: PackTopology = PackTopology.PARALLEL,
    val isPartial: Boolean = false,           // battery partial (unchanged)
    val controllers: List<ControllerState> = emptyList(),   // new
    val motion: ControllerData = ControllerData(),          // new
    val motionPartial: Boolean = false                      // new
)
```

### 2.6 `Vehicle.kt` — add `controllers`, relax invariant
```kotlin
val controllers: List<Controller> = emptyList()
// init: require(packs.isNotEmpty() || controllers.isNotEmpty()) { "Vehicle needs a source" }
```
Keep `singlePackVehicle` and every `primaryPack`/`bmsType`/`bmsAddress` shim.
Add `primaryController`/`hasControllers` where useful, and a `primaryAddress`
(first controller's address, else first pack's) used by scan/identity
(`01-linking §5`). A vehicle may store **zero packs** (a lone VESC whose battery
is derived at runtime) — the relaxed invariant allows it; the derived pack
materialises via the latent-slot mechanism (§4.5).

## 3. `MotionAggregator` (`domain/stats/`)

Pure, mirrors `PackAggregator.build/aggregate`. Reducers over **online**
controllers (all-offline → `ControllerData(isConnected=false)`):

| Field | Reducer | Why |
|---|---|---|
| speedKmh | **max** | same ground speed; max robust to a lagging/zero source |
| dutyPercent | **max** | worst-case headroom — the safety-relevant number |
| motorCurrentA | **sum** | total motor draw |
| batteryCurrentA | **sum** | total input current |
| inputVoltageV | **avg** | shared pack ≈ equal; avg robust to a zero source |
| powerW | **sum** | total power |
| eRpm | **max** | same wheel speed in AWD |
| escTempC / motorTempC | **max** | hottest wins |
| odometerKm / tripKm | **max** | same ground distance — **never sum** |
| consumedAh/Wh, regenAh/Wh | **sum** | total energy |
| faults | **union**, labelled by controller when >1 online | |
| speedSource | REPORTED if any online is REPORTED, else DERIVED if any, else NONE | prefer a real reported speed |
| speedKnown | speedSource != NONE | |
| isConnected | any online | a dual-drive with one ESC down is still moving |

`motionPartial = controllers.isNotEmpty() && controllers.any { !it.isOnline }`.
**No series topology** for controllers — motion is always "parallel-like". Keep
`MotionAggregator` free of `PackTopology`.

### 3.1 `PackAggregator` — alias-group collapse (battery side)
One targeted change to the existing `PackAggregator` (otherwise untouched):
before aggregating, **collapse each `aliasGroup`** to a single online member —
priority lowest `Pack.index`, else most-recently-seen (`lastSeenAt`). Packs with
`aliasGroup == null` are always independent. This makes the direct-ANT and
head-unit-VESC-BMS paths one logical battery (`01-linking §4`): two-online never
double-counts capacity/current, either-online keeps the battery visible, and the
ride-time handoff is invisible to the aggregate. Covered by an aggregator test.

## 4. Orchestration changes (`data/ble/`, `data/bms/`)

### 4.1 `MotionSource` capability (recommended: additive interface)
Do **not** rename `BmsProtocol`. Add an optional capability a protocol MAY
implement; battery-only protocols untouched.
```kotlin
interface MotionSource {
    val controllerCount: Int
    fun latestMotion(controllerIndex: Int): ControllerData?
}
```
- `VescProtocol : BmsProtocol(), MotionSource` — `controllerCount = 1` (N under
  CAN, Part C); `packCount = if (deriveBattery) 1 else 0`, `latestData`
  returning the synthesised `BmsData`.
- `BegodeProtocol` (Part D) additionally implements `MotionSource`.
- JK/JBD/ANT/Daly: unchanged, no `MotionSource`.

> Alternative: rename `BmsProtocol` → `DeviceProtocol` with `open` motion
> defaults. Cleaner name, mechanical rename across 5 protocols + factory + tests,
> no behavioural gain. **Chosen: additive interface** (shipped battery classes
> literally unchanged).

### 4.2 `MotionSampleGate`
Copy `PackSampleGate` for `ControllerData` — dedups identical re-decodes per
controller index so a silent controller can go stale. Same
identity-before-copy contract.

### 4.3 `ConnectionSession`
Add a second sample callback and route motion after `onNotification`:
```kotlin
private val onMotionSample: (ctrlIndex: Int, data: ControllerData) -> Unit
// in observe collect{}:
protocol.onNotification(data)
val batteryAlive = routePackSamples(protocol, sampleGate) { i, bms, sec -> onSample(...) }
val motionAlive = if (protocol is MotionSource)
    routeControllerSamples(protocol, motionGate) { i, m -> onMotionSample(i, m.copy(timestamp = now())) }
    else false
if (batteryAlive || motionAlive) lastSampleAtMs = now()   // watchdog liveness
```
`routeControllerSamples` mirrors `routePackSamples` (iterate
`0 until controllerCount`, gate, emit; return link-liveness). **Link liveness is
battery-OR-motion** — a controller-only VESC has no packs; its liveness comes
from motion or the watchdog would tear it down.

### 4.4 `LinkSpec` / `planLinks`
Group by `address` (unchanged), but a link now owns both index spaces, each
owned source tagged with its `canId`:
```kotlin
enum class ProtocolKind { JK, JBD, ANT, DALY, BEGODE, VESC, VESC_BMS, FARDRIVER, KELLY }
fun BmsType.protocolKind(): ProtocolKind
fun ControllerType.protocolKind(): ProtocolKind

data class OwnedSource(val globalIndex: Int, val canId: Int? = null)

data class LinkSpec(
    val address: String,
    val protocolKind: ProtocolKind,          // was bmsType; the link's gateway decoder
    val ownedPacks: List<OwnedSource> = emptyList(),        // was ownedIndices
    val ownedControllers: List<OwnedSource> = emptyList()
) {
    fun globalPackIndex(local: Int) = ownedPacks[local].globalIndex
    fun globalControllerIndex(local: Int) = ownedControllers[local].globalIndex
}

fun planLinks(packs: List<Pack>, controllers: List<Controller>): List<LinkSpec>
```
`planLinks` groups packs + controllers by `address` (LinkedHashMap,
first-appearance order). For each address, direct sources (`canId == null`) must
resolve to **one** `ProtocolKind` — a Begode pack (`BmsType.BEGODE`) and a
Begode controller (`ControllerType.BEGODE`) both → `ProtocolKind.BEGODE`, so a
wheel is one link owning `packs=[0,1]` + `controllers=[0]`. Conflicting **direct**
kinds at one address → `IllegalArgumentException` (composer prevents it).

> **CAN sources (`canId != null`)**: `planLinks` records them on the gateway's
> link with their `canId`, but a CAN source may have a *different* kind than the
> gateway (a VESC master forwarding a VESC-BMS). Per-source kind for CAN sources
> and the demux are **Part C**; Part A asserts every planned source is direct
> (`canId == null`) and defers a mixed CAN link to C. The plan shape is already
> forward-compatible.

> This renames `LinkSpec.bmsType`→`protocolKind`, `ownedIndices`→`ownedPacks`
> (now `OwnedSource`), touching `KableBmsRepository` and multi-link tests —
> contained, mechanical. **Chosen** over keeping `bmsType` + a nullable
> `controllerType`, which leaves two half-used fields and a nullable type on
> controller-only links.

### 4.5 `VehicleConnection` — fold motion too
Add controller states beside pack states and a `submitMotion` mirroring `submit`
(same single-funnel thread-safety contract, same staleness sweep against
`BleConfig.packOfflineAfterMs`, same latent-slot mechanism):
```kotlin
private val ctrlStates: MutableList<ControllerState>   // from vehicle.controllers
private val latentCtrl: MutableList<Controller>        // CAN-discovered / derived
fun submitMotion(controllerIndex: Int, data: ControllerData): VehicleData
```
`snapshot()`:
```kotlin
val battery = PackAggregator.build(states, topology)   // unchanged
val motion  = MotionAggregator.build(ctrlStates)
VehicleData(
    packs = battery.packs, aggregate = battery.aggregate,
    topology = topology, isPartial = battery.isPartial,
    controllers = ctrlStates.toList(), motion = motion.aggregate,
    motionPartial = motion.partial
)
```
**Latent slots** cover two new cases with the *existing* machinery: (1)
CAN-forwarding discovers slave controllers only when they first answer (Part C)
— a latent controller materialises on its first `submitMotion`, like a latent
Begode pack; (2) a derived battery pack is latent until the controller's first
frame produces it.

### 4.6 `KableBmsRepository`
- Wire each session's `onMotionSample` into the **same** vehicle-global funnel
  that already serialises battery samples (single consumer — do NOT add a second
  funnel; see the thread-safety note atop `VehicleConnection`) →
  `VehicleConnection.submitMotion`.
- `activeVehicleData` already emits the whole `VehicleData`, so `.motion` /
  `.controllers` ride along. Add `val activeMotion: StateFlow<ControllerData>`
  (map of `activeVehicleData`) as the motion sibling of `activeData`.
- Ring buffer: retain the motion aggregate alongside the battery aggregate (for
  Part B's speed/duty sparkline). Store combined `VehicleData` snapshots or add a
  parallel `SampleRingBuffer<ControllerData>` — smallest diff wins.
- Link-status fold (`ConnectionState`) unchanged — folds over `PackLink`
  statuses regardless of what a link carries.
- **Per-link disconnect** — add `suspend fun disconnectLink(address: String)` to
  the repository: tear down one link (its `PackLink` session + reconnect job) and
  drop it from the fold, leaving the vehicle's other links live. This is the
  primitive the ride-time ANT→head-unit handoff needs (`01-linking §4`); the
  *policy* of when to hand off is Part C, but the capability is foundational and
  small (the multi-link teardown already exists per link — this exposes it).

## 5. Persistence (`data/db/`)
`Vehicle.controllers` and the new `canId` must persist. Mirror **exactly** how
packs are stored in `SqlDelightVehicleRepository` (read it first): child table →
add a `controllerEntity` table + a `canId` column on the pack table; JSON column
→ add a `controllers` column + `canId` into the pack JSON. Add a SQLDelight
**migration** (`.sqm`) defaulting existing rows to zero controllers / null
`canId`. Round-trip test required (§7).

## 6. Demo (`data/demo/`)
Extend the demo so the synthetic vehicle exposes motion: the demo protocol
implements `MotionSource` and emits a believable ride curve (speed ramps, duty
tracks speed, temps drift, odo/trip accumulate, `speedSource = REPORTED`). Lets
Part B build/demo the Ride dashboard with **no hardware** and gives Part A an
end-to-end integration test through the real funnel.

## 7. Testing
Pure/unit, following existing test style:
- `MotionAggregatorTest` — every reducer; single-controller identity; one-offline
  partial; all-offline disconnected; odo/trip = max not sum; faults labelled only
  when >1 online; speedSource precedence.
- `LinkPlanTest` (extend) — controller-only address → controller-only link;
  Begode address (pack+controller) → one BEGODE link owning both spaces;
  2×uBox independent + 2×ANT → 4 links; conflicting **direct** kinds throw; a
  planned source with `canId != null` is rejected in Part A (deferred to C).
- `VehicleConnection` — `submitMotion` staleness sweep; latent controller
  materialises on first motion sample; motion + battery interleave through the
  funnel; snapshot carries both aggregates.
- `MotionSampleGate` — mirrors `PackSampleGateTest`.
- `SampleRoutingTest` (extend) — a `MotionSource` protocol routes motion; link
  liveness is battery-OR-motion; a controller-only protocol keeps the link alive
  with no packs.
- Persistence round-trip — a vehicle with controllers + `canId` saves/loads;
  migration from a controller-less DB yields `controllers = emptyList()`,
  `canId = null`.
- Demo — `DemoBmsSimulatorTest` extended: motion advances and aggregates.

## 8. Open questions / decisions to confirm
1. **Separate index spaces** for `Pack` and `Controller` (both 0-based,
   independent) — a Begode is pack `[0,1]` + controller `[0]` at one address.
   Confirm.
2. **Ring buffer**: combined `VehicleData` snapshots vs a parallel motion
   buffer. Decide at implementation by smallest diff; either serves B.
3. **`activeMotion` StateFlow** vs reading `activeVehicleData.motion`. Recommend
   the convenience flow to match `activeData`.
4. **Derived-battery default**: `providesDerivedBattery` defaults **false**; a
   lone VESC added by quick-scan (no composer pass) still needs a battery — Part
   B/G define that default (likely: true when the vehicle has no other battery
   source).

---

## The aggregator silently drops `batteryLevelFraction` (2026-07-27)

Found in Part D Task 5, while proving that a single-controller fold is the
identity — the proof failed on exactly one field.

`ControllerData.batteryLevelFraction` is the controller-computed battery level
from `COMM_GET_VALUES_SETUP`, used to seed a derived battery's SoC when no smart
BMS is present. **`MotionAggregator.aggregate` never copies it**, so the
vehicle-level `activeMotion` always publishes `null` for it, no matter what the
controller reported.

Harmless today only because both VESC decoders read the field upstream of the
fold. It becomes a silent `null` the moment anything reads it off `activeMotion`
— and "silent" is the operative word: nothing throws, the number is simply
absent, and a derived battery would fall back to whatever it does without a seed.

**Not fixed in Part D**, which had no business changing the aggregator's contract,
and deliberately **not pinned by a test asserting the current behaviour** —
pinning it would turn a one-line fix into a test edit and make the defect look
intentional. Fix it where the fold lives, and add the field to whatever test
proves the single-controller fold is the identity.

One caution learned in the same task: **a stationary fixture cannot prove a fold
is the identity.** Zeroing `speedKmh` in the aggregator passed both
capture-based identity tests, because 13 seconds of a parked wheel leaves speed,
trip, eRPM and every energy counter at zero. Any identity proof needs a fixture
with all fields distinct and non-zero.
