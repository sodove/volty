# Vehicle Platform — Part A (Foundation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a parallel **motion telemetry** path (controllers) mirroring the existing battery path, generalise the BLE orchestration to carry it, and persist it — with the battery pipeline byte-for-byte unchanged. No real controller protocol ships here (that is Part B); the demo simulator validates the whole pipeline end-to-end.

**Architecture:** For every battery abstraction there is a motion twin: `Pack→PackState→PackAggregator→VehicleData.aggregate` gains `Controller→ControllerState→MotionAggregator→VehicleData.motion`. A protocol optionally implements a new `MotionSource` interface; `ConnectionSession` routes its motion samples through the *same* single-consumer funnel that already serialises battery samples into `VehicleConnection`. One BLE link (keyed by `address`) may own both index spaces; `canId` is modelled now, its CAN transport deferred to Part C.

**Tech Stack:** Kotlin 2.3.20, Compose Multiplatform (Android target), Decompose, Koin, Kable (BLE), SQLDelight, kotlinx-coroutines, kotlin.test + Turbine.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-24-vehicle-platform/A-foundation.md`; shared context `00-overview.md`, `01-linking.md`. Read all three before starting.
- **Do not modify `BmsData` or the battery decode/aggregate path** except the one explicit `PackAggregator` alias-collapse change (Task 4). Zero regression to the shipped BMS app is a hard requirement.
- Package root: `ru.sodovaya.volty`. Tests use `kotlin.test` (`Test`, `assertEquals`, `assertTrue`, `assertFailsWith`) and Turbine where a Flow is asserted; match the existing test style in `composeApp/src/commonTest`.
- Timestamps use `kotlin.time.Instant` / `Clock.System.now()` under `@OptIn(ExperimentalTime::class)` (as existing models do).
- Canonical `ControllerData` units: km/h, %, A, V, W, °C, km. No unit conversion in the domain/data layer.
- CAN forwarding is **out of scope** here: `planLinks` must reject any planned source with `canId != null` (Part C lifts this). The `canId`/`aliasGroup` fields are persisted but only `canId == null` sources are exercised.
- `VehicleConnection` is single-consumer and NOT thread-safe by design — motion must enter through the existing sample funnel, never a second concurrent caller.
- SQLDelight schema is at **v3** (migrations `1.sqm`, `2.sqm`). This plan adds `3.sqm` (v3→v4). Do not renumber existing migrations.
- Commit after every task with the message shown in its final step.

## File Structure

**New files:**
- `domain/model/Controller.kt` — `ControllerType`, `MotorConfig`, `Controller`.
- `domain/model/ControllerData.kt` — `SpeedSource`, `ControllerData`.
- `domain/model/ControllerState.kt` — `ControllerState`.
- `domain/stats/MotionAggregator.kt` — pure motion aggregation.
- `data/bms/MotionSource.kt` — the capability interface.
- `data/ble/MotionSampleGate.kt` — motion dedup gate (mirrors `PackSampleGate`).
- `data/demo/DemoMotionProfile.kt` — synthetic ride curve (or fold into `DemoBmsSimulator`).
- `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/ControllerRow.sq` — controller table + queries.
- `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/3.sqm` — v3→v4 migration.
- Test files under `commonTest/.../{model,stats,ble,db,demo}`.

**Modified files:**
- `domain/model/Pack.kt` — add `canId`, `aliasGroup`.
- `domain/model/BmsType.kt` — add `VESC_BMS`.
- `domain/model/Vehicle.kt` — add `controllers`, relax invariant, add `primaryController`/`hasControllers`/`primaryAddress`.
- `domain/model/VehicleData.kt` — add `controllers`, `motion`, `motionPartial`.
- `domain/stats/PackAggregator.kt` — alias-group collapse.
- `data/bms/BmsProtocol.kt` — (unchanged) — `MotionSource` is a sibling interface, not a change here.
- `data/ble/ConnectionSession.kt` — motion sample callback + `routeControllerSamples`.
- `data/ble/LinkPlan.kt` — `ProtocolKind`, `OwnedSource`, generalised `LinkSpec`/`planLinks`.
- `data/ble/VehicleConnection.kt` — controller states + `submitMotion`.
- `data/ble/KableBmsRepository.kt` — sealed funnel sample, motion routing, `activeMotion`, motion ring buffer, `disconnectLink`.
- `domain/repository/BmsRepository.kt` — add `activeMotion`, `disconnectLink`.
- `data/memory/SampleRingBuffer.kt` — generalise to `SampleRingBuffer<T>`.
- `data/db/SqlDelightVehicleRepository.kt` — read/write controllers + new pack columns.
- `data/demo/DemoBmsSimulator.kt` — emit motion.

---

### Task 1: Controller / ControllerData / ControllerState domain types

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Controller.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/ControllerData.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/ControllerState.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/model/ControllerDataTest.kt`

**Interfaces:**
- Produces: `ControllerType { VESC, FARDRIVER, KELLY, BEGODE }`; `MotorConfig(polePairs=15, wheelDiameterMm=0, gearRatio=1f)`; `Controller(index, label, controllerType, address, canId=null, motor=MotorConfig(), providesDerivedBattery=false)`; `SpeedSource { REPORTED, DERIVED, NONE }`; `ControllerData(...)` (full field list below); `ControllerState(controller, data, isOnline=false, lastSeenAt=null)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControllerDataTest {
    @Test fun defaults_are_disconnected_and_speed_unknown() {
        val d = ControllerData()
        assertEquals(SpeedSource.NONE, d.speedSource)
        assertFalse(d.speedKnown)
        assertFalse(d.isConnected)
    }

    @Test fun speedKnown_tracks_speedSource() {
        assertTrue(ControllerData(speedKmh = 20f, speedSource = SpeedSource.REPORTED).speedKnown)
        assertTrue(ControllerData(speedKmh = 20f, speedSource = SpeedSource.DERIVED).speedKnown)
        assertFalse(ControllerData(speedSource = SpeedSource.NONE).speedKnown)
    }

    @Test fun controller_defaults() {
        val c = Controller(index = 0, label = "ESC", controllerType = ControllerType.VESC, address = "AA:BB")
        assertEquals(null, c.canId)
        assertFalse(c.providesDerivedBattery)
        assertEquals(15, c.motor.polePairs)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.model.ControllerDataTest"`
Expected: FAIL — unresolved references (`ControllerData`, `Controller`, `SpeedSource`).

- [ ] **Step 3: Write minimal implementation**

`ControllerData.kt`:
```kotlin
package ru.sodovaya.volty.domain.model

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class SpeedSource { REPORTED, DERIVED, NONE }

@OptIn(ExperimentalTime::class)
data class ControllerData(
    val speedKmh: Float = 0f,
    val speedSource: SpeedSource = SpeedSource.NONE,
    val dutyPercent: Float = 0f,
    val motorCurrentA: Float = 0f,
    val batteryCurrentA: Float = 0f,
    val inputVoltageV: Float = 0f,
    val powerW: Float = 0f,
    val eRpm: Float = 0f,
    val escTempC: Float = 0f,
    val motorTempC: Float = 0f,
    val hasMotorTemp: Boolean = false,
    val odometerKm: Float = 0f,
    val tripKm: Float = 0f,
    val consumedAh: Float = 0f,
    val consumedWh: Float = 0f,
    val regenAh: Float = 0f,
    val regenWh: Float = 0f,
    val faults: List<String> = emptyList(),
    val isConnected: Boolean = false,
    val timestamp: Instant = Clock.System.now()
) {
    val speedKnown: Boolean get() = speedSource != SpeedSource.NONE
}
```

`Controller.kt`:
```kotlin
package ru.sodovaya.volty.domain.model

enum class ControllerType(val label: String) {
    VESC("VESC"),
    FARDRIVER("FarDriver"),
    KELLY("Kelly KLS"),
    BEGODE("Begode")
}

data class MotorConfig(
    val polePairs: Int = 15,
    val wheelDiameterMm: Int = 0,
    val gearRatio: Float = 1f
)

data class Controller(
    val index: Int,
    val label: String,
    val controllerType: ControllerType,
    val address: String,
    val canId: Int? = null,
    val motor: MotorConfig = MotorConfig(),
    val providesDerivedBattery: Boolean = false
)
```

`ControllerState.kt`:
```kotlin
package ru.sodovaya.volty.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class ControllerState(
    val controller: Controller,
    val data: ControllerData,
    val isOnline: Boolean = false,
    val lastSeenAt: Instant? = null
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.model.ControllerDataTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Controller.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/ControllerData.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/ControllerState.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/model/ControllerDataTest.kt
git commit -m "feat(motion): controller telemetry domain types"
```

---

### Task 2: Extend Pack / BmsType / Vehicle / VehicleData

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Pack.kt` (the `Pack` data class)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/BmsType.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Vehicle.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/VehicleData.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/model/VehicleControllersTest.kt`

**Interfaces:**
- Consumes: `Controller` (Task 1), existing `Pack`, `Vehicle`, `VehicleData`.
- Produces: `Pack.canId: Int?`, `Pack.aliasGroup: String?`; `BmsType.VESC_BMS`; `Vehicle.controllers: List<Controller>`, `Vehicle.hasControllers`, `Vehicle.primaryController`, `Vehicle.primaryAddress`; `VehicleData.controllers`, `VehicleData.motion`, `VehicleData.motionPartial`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class VehicleControllersTest {
    private fun vec(packs: List<Pack> = emptyList(), controllers: List<Controller> = emptyList()) =
        Vehicle(id = "v", name = "n", iconKey = "generic", packs = packs, controllers = controllers,
            chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now())

    @Test fun controller_only_vehicle_is_allowed() {
        val v = vec(controllers = listOf(Controller(0, "ESC", ControllerType.VESC, "AA")))
        assertTrue(v.hasControllers)
        assertEquals("AA", v.primaryAddress)
    }

    @Test fun no_sources_is_rejected() {
        assertFailsWith<IllegalArgumentException> { vec() }
    }

    @Test fun primaryAddress_prefers_controller_then_pack() {
        val withBoth = vec(
            packs = listOf(Pack(0, "P", BmsType.ANT_BMS, "PACK")),
            controllers = listOf(Controller(0, "ESC", ControllerType.VESC, "CTRL"))
        )
        assertEquals("CTRL", withBoth.primaryAddress)
        val packOnly = vec(packs = listOf(Pack(0, "P", BmsType.ANT_BMS, "PACK")))
        assertEquals("PACK", packOnly.primaryAddress)
    }

    @Test fun vesc_bms_reports_soc() {
        assertTrue(BmsType.VESC_BMS.reportsStateOfCharge)
    }

    @Test fun pack_new_fields_default_null() {
        val p = Pack(0, "P", BmsType.ANT_BMS, "AA")
        assertEquals(null, p.canId)
        assertEquals(null, p.aliasGroup)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.model.VehicleControllersTest"`
Expected: FAIL — `controllers` param unknown, `hasControllers`/`primaryAddress` unresolved, `VESC_BMS` unresolved.

- [ ] **Step 3: Write minimal implementation**

In `Pack.kt`, add the two fields to the `Pack` data class (append after `cellCount`):
```kotlin
    val cellCount: Int? = null,
    val canId: Int? = null,
    val aliasGroup: String? = null
```

In `BmsType.kt`, add the enum entry (after `DALY_BMS`):
```kotlin
    VESC_BMS("VESC BMS", reportsStateOfCharge = true),
```

In `Vehicle.kt`, add `controllers` to the constructor, relax `init`, and add helpers:
```kotlin
    val packs: List<Pack>,
    val controllers: List<Controller> = emptyList(),
    // ...existing params...
) {
    init {
        require(packs.isNotEmpty() || controllers.isNotEmpty()) { "Vehicle needs a source" }
    }
}

val Vehicle.hasControllers: Boolean get() = controllers.isNotEmpty()
val Vehicle.primaryController: Controller? get() = controllers.minByOrNull { it.index }
val Vehicle.primaryAddress: String get() =
    primaryController?.address ?: packs.first().bmsAddress
```
(Keep `singlePackVehicle` and all existing `primaryPack`/`bmsType`/`bmsAddress` shims. `controllers` defaults to empty, so every existing call site compiles unchanged.)

In `VehicleData.kt`, add the three fields:
```kotlin
data class VehicleData(
    val packs: List<PackState> = emptyList(),
    val aggregate: BmsData = BmsData(),
    val topology: PackTopology = PackTopology.PARALLEL,
    val isPartial: Boolean = false,
    val controllers: List<ControllerState> = emptyList(),
    val motion: ControllerData = ControllerData(),
    val motionPartial: Boolean = false
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.model.VehicleControllersTest"`
Expected: PASS. Also run the full model test package to confirm no regression: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.model.*"`

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/ composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/model/VehicleControllersTest.kt
git commit -m "feat(motion): Vehicle/VehicleData/Pack/BmsType gain controller + canId/aliasGroup"
```

---

### Task 3: MotionAggregator

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/MotionAggregator.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/stats/MotionAggregatorTest.kt`

**Interfaces:**
- Consumes: `ControllerState`, `ControllerData`, `SpeedSource`.
- Produces: `MotionAggregator.build(controllers): MotionResult` where `data class MotionResult(val aggregate: ControllerData, val partial: Boolean)`; and `MotionAggregator.aggregate(controllers): ControllerData`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionAggregatorTest {
    private fun ctrl(i: Int) = Controller(i, "c$i", ControllerType.VESC, "A$i")
    private fun state(i: Int, d: ControllerData, online: Boolean = true) =
        ControllerState(ctrl(i), d, isOnline = online)

    @Test fun single_online_controller_is_identity() {
        val d = ControllerData(speedKmh = 30f, speedSource = SpeedSource.REPORTED,
            dutyPercent = 40f, batteryCurrentA = 10f, powerW = 700f, isConnected = true)
        val agg = MotionAggregator.aggregate(listOf(state(0, d)))
        assertEquals(30f, agg.speedKmh); assertEquals(40f, agg.dutyPercent)
        assertEquals(10f, agg.batteryCurrentA); assertEquals(700f, agg.powerW)
    }

    @Test fun two_controllers_sum_current_power_max_speed_duty_temp() {
        val a = ControllerData(speedKmh = 30f, dutyPercent = 50f, batteryCurrentA = 10f,
            motorCurrentA = 20f, powerW = 700f, escTempC = 40f, odometerKm = 100f, consumedWh = 500f,
            speedSource = SpeedSource.REPORTED, isConnected = true)
        val b = ControllerData(speedKmh = 29f, dutyPercent = 55f, batteryCurrentA = 12f,
            motorCurrentA = 22f, powerW = 800f, escTempC = 45f, odometerKm = 100f, consumedWh = 480f,
            speedSource = SpeedSource.REPORTED, isConnected = true)
        val agg = MotionAggregator.aggregate(listOf(state(0, a), state(1, b)))
        assertEquals(30f, agg.speedKmh)          // max
        assertEquals(55f, agg.dutyPercent)       // max
        assertEquals(22f, agg.batteryCurrentA)   // sum 10+12
        assertEquals(42f, agg.motorCurrentA)     // sum 20+22
        assertEquals(1500f, agg.powerW)          // sum
        assertEquals(45f, agg.escTempC)          // max
        assertEquals(100f, agg.odometerKm)       // MAX, not sum
        assertEquals(980f, agg.consumedWh)       // sum
    }

    @Test fun offline_controllers_excluded_and_partial_flagged() {
        val on = ControllerData(speedKmh = 20f, speedSource = SpeedSource.REPORTED, isConnected = true)
        val off = ControllerData(speedKmh = 99f, speedSource = SpeedSource.REPORTED)
        val res = MotionAggregator.build(listOf(state(0, on), state(1, off, online = false)))
        assertEquals(20f, res.aggregate.speedKmh)
        assertTrue(res.partial)
        assertTrue(res.aggregate.isConnected)
    }

    @Test fun all_offline_is_disconnected() {
        val res = MotionAggregator.build(listOf(state(0, ControllerData(), online = false)))
        assertFalse(res.aggregate.isConnected)
    }

    @Test fun speedSource_prefers_reported_then_derived_then_none() {
        val reported = ControllerData(speedSource = SpeedSource.REPORTED, isConnected = true)
        val derived = ControllerData(speedSource = SpeedSource.DERIVED, isConnected = true)
        assertEquals(SpeedSource.REPORTED,
            MotionAggregator.aggregate(listOf(state(0, derived), state(1, reported))).speedSource)
        assertEquals(SpeedSource.DERIVED,
            MotionAggregator.aggregate(listOf(state(0, derived))).speedSource)
    }

    @Test fun faults_labelled_only_when_more_than_one_online() {
        val a = ControllerData(faults = listOf("OVERTEMP"), isConnected = true)
        val one = MotionAggregator.aggregate(listOf(state(0, a)))
        assertEquals(listOf("OVERTEMP"), one.faults)
        val two = MotionAggregator.aggregate(listOf(state(0, a),
            state(1, ControllerData(faults = listOf("HALL"), isConnected = true))))
        assertEquals(listOf("c0: OVERTEMP", "c1: HALL"), two.faults)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.stats.MotionAggregatorTest"`
Expected: FAIL — `MotionAggregator` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class MotionResult(val aggregate: ControllerData, val partial: Boolean)

@OptIn(ExperimentalTime::class)
object MotionAggregator {

    fun build(controllers: List<ControllerState>): MotionResult = MotionResult(
        aggregate = aggregate(controllers),
        partial = controllers.isNotEmpty() && controllers.any { !it.isOnline }
    )

    fun aggregate(controllers: List<ControllerState>): ControllerData {
        val online = controllers.filter { it.isOnline }
        if (online.isEmpty()) return ControllerData(isConnected = false)
        val d = online.map { it.data }
        val labelled = online.size > 1
        val speedSource = when {
            d.any { it.speedSource == SpeedSource.REPORTED } -> SpeedSource.REPORTED
            d.any { it.speedSource == SpeedSource.DERIVED } -> SpeedSource.DERIVED
            else -> SpeedSource.NONE
        }
        return ControllerData(
            speedKmh = d.maxOf { it.speedKmh },
            speedSource = speedSource,
            dutyPercent = d.maxOf { it.dutyPercent },
            motorCurrentA = d.sumOf { it.motorCurrentA.toDouble() }.toFloat(),
            batteryCurrentA = d.sumOf { it.batteryCurrentA.toDouble() }.toFloat(),
            inputVoltageV = d.map { it.inputVoltageV }.average().toFloat(),
            powerW = d.sumOf { it.powerW.toDouble() }.toFloat(),
            eRpm = d.maxOf { it.eRpm },
            escTempC = d.maxOf { it.escTempC },
            motorTempC = d.maxOf { it.motorTempC },
            hasMotorTemp = d.any { it.hasMotorTemp },
            odometerKm = d.maxOf { it.odometerKm },
            tripKm = d.maxOf { it.tripKm },
            consumedAh = d.sumOf { it.consumedAh.toDouble() }.toFloat(),
            consumedWh = d.sumOf { it.consumedWh.toDouble() }.toFloat(),
            regenAh = d.sumOf { it.regenAh.toDouble() }.toFloat(),
            regenWh = d.sumOf { it.regenWh.toDouble() }.toFloat(),
            faults = online.flatMap { s ->
                s.data.faults.map { if (labelled) "${s.controller.label}: $it" else it }
            },
            isConnected = true,
            timestamp = d.maxOfOrNull { it.timestamp } ?: Clock.System.now()
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.stats.MotionAggregatorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/MotionAggregator.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/stats/MotionAggregatorTest.kt
git commit -m "feat(motion): MotionAggregator (max speed/duty, sum current/power, max odo)"
```

---

### Task 4: PackAggregator alias-group collapse

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/PackAggregator.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/stats/PackAggregatorAliasTest.kt`

**Interfaces:**
- Consumes: `PackState`, `Pack.aliasGroup` (Task 2), `PackTopology`.
- Produces: unchanged public API (`PackAggregator.build/aggregate`); behaviour: online members of one `aliasGroup` collapse to one before aggregation.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import kotlin.test.Test
import kotlin.test.assertEquals

class PackAggregatorAliasTest {
    private fun pack(i: Int, alias: String?) = Pack(i, "p$i", BmsType.ANT_BMS, "A$i", aliasGroup = alias)
    private fun state(i: Int, alias: String?, current: Float, online: Boolean = true) =
        PackState(pack(i, alias), BmsData(voltage = 50f, current = current, isConnected = true), isOnline = online)

    @Test fun two_online_alias_paths_count_once_lowest_index_wins() {
        // Same physical battery via two paths, both online → count only pack 0.
        val agg = PackAggregator.aggregate(
            listOf(state(0, "batt", current = 10f), state(1, "batt", current = 10f)),
            PackTopology.PARALLEL
        )
        assertEquals(10f, agg.current) // not 20f — collapsed
    }

    @Test fun alias_failover_keeps_battery_when_primary_offline() {
        val agg = PackAggregator.aggregate(
            listOf(state(0, "batt", current = 0f, online = false), state(1, "batt", current = 12f)),
            PackTopology.PARALLEL
        )
        assertEquals(12f, agg.current)
    }

    @Test fun null_aliasGroup_packs_stay_independent() {
        val agg = PackAggregator.aggregate(
            listOf(state(0, null, current = 10f), state(1, null, current = 12f)),
            PackTopology.PARALLEL
        )
        assertEquals(22f, agg.current)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.stats.PackAggregatorAliasTest"`
Expected: FAIL — `two_online_alias_paths_count_once` asserts 10 but current code sums to 20.

- [ ] **Step 3: Write minimal implementation**

In `PackAggregator`, add a private collapse applied at the top of `build` and `aggregate` (both should see the collapsed list — factor a helper and call it in both, or collapse inside `aggregate` and have `build` pass the raw list to `aggregate` while computing `isPartial` on the raw list). Keep `isPartial` semantics on the *raw* list (an offline alias sibling should not by itself flag partial — but a genuinely offline independent pack still should; simplest correct rule: compute `isPartial` from the collapsed list too, so a fully-covered alias group is not "partial"). Implement:

```kotlin
private fun collapseAliases(packs: List<PackState>): List<PackState> {
    val grouped = packs.groupBy { it.pack.aliasGroup }
    val result = ArrayList<PackState>(packs.size)
    for ((alias, members) in grouped) {
        if (alias == null) { result += members; continue }
        // One representative for the group: prefer an online member with the
        // lowest index; if none online, keep the lowest-index member so the
        // card stays visible (offline).
        val online = members.filter { it.isOnline }
        val chosen = (if (online.isNotEmpty()) online else members)
            .minByOrNull { it.pack.index }!!
        result += chosen
    }
    return result.sortedBy { it.pack.index }
}
```
Then in `build(packs, topology)` and `aggregate(packs, topology)` operate on `collapseAliases(packs)`. Update `build`'s `isPartial` to `collapsed.isNotEmpty() && collapsed.any { !it.isOnline }`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.stats.PackAggregator*"`
Expected: PASS (both the new alias test and the existing `PackAggregatorTest`, which uses no aliasGroups and is therefore unaffected).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/PackAggregator.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/stats/PackAggregatorAliasTest.kt
git commit -m "feat(motion): PackAggregator collapses aliasGroup to one online member"
```

---

### Task 5: MotionSource interface + MotionSampleGate

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/MotionSource.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/MotionSampleGate.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/MotionSampleGateTest.kt`

**Interfaces:**
- Produces: `interface MotionSource { val controllerCount: Int; fun latestMotion(controllerIndex: Int): ControllerData? }`; `class MotionSampleGate(controllerCount: Int) { fun advance(controllerIndex: Int, sample: ControllerData): Boolean }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.ControllerData
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionSampleGateTest {
    @Test fun same_instance_is_suppressed_new_instance_passes() {
        val gate = MotionSampleGate(1)
        val a = ControllerData(speedKmh = 10f)
        assertTrue(gate.advance(0, a))   // first sight
        assertFalse(gate.advance(0, a))  // same instance re-read
        val b = ControllerData(speedKmh = 10f)  // equal but new instance
        assertTrue(gate.advance(0, b))
    }

    @Test fun per_index_independent() {
        val gate = MotionSampleGate(2)
        val a = ControllerData()
        assertTrue(gate.advance(0, a))
        assertTrue(gate.advance(1, a))   // different index, first sight
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.MotionSampleGateTest"`
Expected: FAIL — unresolved `MotionSampleGate`.

- [ ] **Step 3: Write minimal implementation**

`MotionSource.kt`:
```kotlin
package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.ControllerData

/** Optional capability a [BmsProtocol] MAY also implement to emit motion. */
interface MotionSource {
    val controllerCount: Int
    fun latestMotion(controllerIndex: Int): ControllerData?
}
```

`MotionSampleGate.kt` (mirror `PackSampleGate`, identity discriminator):
```kotlin
package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.ControllerData

internal class MotionSampleGate(controllerCount: Int) {
    private val lastSeen = arrayOfNulls<ControllerData>(controllerCount)
    fun advance(controllerIndex: Int, sample: ControllerData): Boolean {
        if (lastSeen[controllerIndex] === sample) return false
        lastSeen[controllerIndex] = sample
        return true
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.MotionSampleGateTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/MotionSource.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/MotionSampleGate.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/MotionSampleGateTest.kt
git commit -m "feat(motion): MotionSource capability + MotionSampleGate"
```

---

### Task 6: ConnectionSession routes motion (routeControllerSamples)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/ConnectionSession.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/SampleRoutingTest.kt` (extend existing)

**Interfaces:**
- Consumes: `MotionSource`, `MotionSampleGate`, existing `routePackSamples`, `BmsProtocol`.
- Produces: `routeControllerSamples(protocol, gate, onNewMotion): Boolean` (link-liveness); `ConnectionSession` constructor gains `onMotionSample: (controllerIndex: Int, data: ControllerData) -> Unit` (default `{ _, _ -> }` so existing call sites compile), and its observe loop OR-folds battery + motion liveness into `lastSampleAtMs`.

- [ ] **Step 1: Write the failing test** (add to `SampleRoutingTest.kt`)

```kotlin
// A fake protocol that is both a BmsProtocol and a MotionSource.
private class FakeMotionProtocol(
    private var motion: ControllerData?
) : BmsProtocol(), ru.sodovaya.volty.data.bms.MotionSource {
    override val uuids = /* reuse any test UUIDs already used in this file */ TODO_REPLACE_WITH_EXISTING
    override fun handshakeCommands() = emptyList<ByteArray>()
    override fun pollCommands() = emptyList<ByteArray>()
    override fun onNotification(data: ByteArray) {}
    override val packCount = 0
    override fun latestData(packIndex: Int): BmsData? = null
    override fun reset() {}
    override val controllerCount = 1
    override fun latestMotion(controllerIndex: Int) = motion
}

@Test fun controller_only_protocol_keeps_link_alive_via_motion() {
    val gate = MotionSampleGate(1)
    val proto = FakeMotionProtocol(ControllerData(speedKmh = 5f))
    var got: ControllerData? = null
    val alive = routeControllerSamples(proto, gate) { _, d -> got = d }
    assertTrue(alive)
    assertEquals(5f, got?.speedKmh)
}

@Test fun no_motion_decode_reports_not_alive() {
    val alive = routeControllerSamples(FakeMotionProtocol(null), MotionSampleGate(1)) { _, _ -> }
    assertFalse(alive)
}
```
(Replace `TODO_REPLACE_WITH_EXISTING` with the `BmsUuids` instance the existing tests in this file already construct — read the top of `SampleRoutingTest.kt` first.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.SampleRoutingTest"`
Expected: FAIL — `routeControllerSamples` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add to `ConnectionSession.kt` (a top-level internal fun, next to `routePackSamples`):
```kotlin
internal fun routeControllerSamples(
    protocol: ru.sodovaya.volty.data.bms.MotionSource,
    gate: MotionSampleGate,
    onNewMotion: (controllerIndex: Int, data: ControllerData) -> Unit
): Boolean {
    var alive = false
    for (i in 0 until protocol.controllerCount) {
        val m = protocol.latestMotion(i) ?: continue
        alive = true
        if (!gate.advance(i, m)) continue
        onNewMotion(i, m)
    }
    return alive
}
```
Add the `onMotionSample` constructor param (default no-op) and, inside the `observe(...).collect { data -> ... }` block, after `routePackSamples(...)`:
```kotlin
val motionGate = /* create once alongside sampleGate: */ MotionSampleGate(
    (protocol as? ru.sodovaya.volty.data.bms.MotionSource)?.controllerCount ?: 0
)
// ...in collect:
val motionAlive = (protocol as? ru.sodovaya.volty.data.bms.MotionSource)?.let { ms ->
    routeControllerSamples(ms, motionGate) { i, m ->
        onMotionSample(i, m.copy(timestamp = Clock.System.now()))
    }
} ?: false
if (linkAlive || motionAlive) {
    lastSampleAtMs = Clock.System.now().toEpochMilliseconds()
    // ...existing sampleCount bookkeeping...
}
```
(`motionGate` is created once, alongside `sampleGate`, before the `observe` collect — hoist it there, not inside the lambda.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.SampleRoutingTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/ConnectionSession.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/SampleRoutingTest.kt
git commit -m "feat(motion): ConnectionSession routes motion samples, link liveness is battery-OR-motion"
```

---

### Task 7: LinkSpec / planLinks generalisation (ProtocolKind, OwnedSource)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/LinkPlan.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/LinkPlanTest.kt` (extend)

**Interfaces:**
- Consumes: `Pack` (canId), `Controller`, `BmsType`, `ControllerType`.
- Produces: `enum ProtocolKind { JK, JBD, ANT, DALY, BEGODE, VESC, VESC_BMS, FARDRIVER, KELLY }`; `BmsType.protocolKind()`, `ControllerType.protocolKind()`; `data class OwnedSource(globalIndex, canId=null)`; `LinkSpec(address, protocolKind, ownedPacks: List<OwnedSource>, ownedControllers: List<OwnedSource>)` with `globalPackIndex(local)`/`globalControllerIndex(local)`; `planLinks(packs, controllers): List<LinkSpec>`.

> **Migration note:** this renames `LinkSpec.bmsType`→`protocolKind`, `ownedIndices`→`ownedPacks` (now `OwnedSource`). `KableBmsRepository` and existing `LinkPlanTest` call sites must be updated to the new shape in this task (they are the only consumers — grep `LinkSpec(` and `.ownedIndices`/`.bmsType` / `planLinks(`). Keep a single-arg `planLinks(packs)` overload delegating to `planLinks(packs, emptyList())` so any battery-only caller compiles unchanged.

- [ ] **Step 1: Write the failing test** (extend `LinkPlanTest.kt`)

```kotlin
@Test fun controller_only_address_makes_a_controller_link() {
    val links = planLinks(emptyList(), listOf(
        Controller(0, "ESC", ControllerType.VESC, "AA")
    ))
    assertEquals(1, links.size)
    assertEquals(ProtocolKind.VESC, links[0].protocolKind)
    assertEquals(listOf(0), links[0].ownedControllers.map { it.globalIndex })
    assertTrue(links[0].ownedPacks.isEmpty())
}

@Test fun begode_pack_and_controller_share_one_link() {
    val links = planLinks(
        packs = listOf(Pack(0, "b0", BmsType.BEGODE, "WHEEL"), Pack(1, "b1", BmsType.BEGODE, "WHEEL")),
        controllers = listOf(Controller(0, "wheel", ControllerType.BEGODE, "WHEEL"))
    )
    assertEquals(1, links.size)
    assertEquals(ProtocolKind.BEGODE, links[0].protocolKind)
    assertEquals(listOf(0, 1), links[0].ownedPacks.map { it.globalIndex })
    assertEquals(listOf(0), links[0].ownedControllers.map { it.globalIndex })
}

@Test fun scooter_two_ubox_two_ant_is_four_links() {
    val links = planLinks(
        packs = listOf(Pack(0, "a0", BmsType.ANT_BMS, "ANT0"), Pack(1, "a1", BmsType.ANT_BMS, "ANT1")),
        controllers = listOf(Controller(0, "u0", ControllerType.VESC, "UBOX0"),
                             Controller(1, "u1", ControllerType.VESC, "UBOX1"))
    )
    assertEquals(4, links.size)
}

@Test fun conflicting_direct_kinds_at_one_address_throw() {
    assertFailsWith<IllegalArgumentException> {
        planLinks(
            packs = listOf(Pack(0, "p", BmsType.ANT_BMS, "SAME")),
            controllers = listOf(Controller(0, "c", ControllerType.VESC, "SAME"))
        )
    }
}

@Test fun can_forwarded_source_is_rejected_in_part_A() {
    assertFailsWith<IllegalArgumentException> {
        planLinks(emptyList(), listOf(Controller(0, "c", ControllerType.VESC, "GW", canId = 41)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.LinkPlanTest"`
Expected: FAIL — new symbols unresolved and old-shape assertions broken.

- [ ] **Step 3: Write minimal implementation**

Rewrite `LinkPlan.kt`:
```kotlin
package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack

enum class ProtocolKind { JK, JBD, ANT, DALY, BEGODE, VESC, VESC_BMS, FARDRIVER, KELLY }

fun BmsType.protocolKind(): ProtocolKind = when (this) {
    BmsType.JK_BMS -> ProtocolKind.JK
    BmsType.JBD_BMS -> ProtocolKind.JBD
    BmsType.ANT_BMS -> ProtocolKind.ANT
    BmsType.DALY_BMS -> ProtocolKind.DALY
    BmsType.BEGODE -> ProtocolKind.BEGODE
    BmsType.VESC_BMS -> ProtocolKind.VESC_BMS
}

fun ControllerType.protocolKind(): ProtocolKind = when (this) {
    ControllerType.VESC -> ProtocolKind.VESC
    ControllerType.FARDRIVER -> ProtocolKind.FARDRIVER
    ControllerType.KELLY -> ProtocolKind.KELLY
    ControllerType.BEGODE -> ProtocolKind.BEGODE
}

data class OwnedSource(val globalIndex: Int, val canId: Int? = null)

data class LinkSpec(
    val address: String,
    val protocolKind: ProtocolKind,
    val ownedPacks: List<OwnedSource> = emptyList(),
    val ownedControllers: List<OwnedSource> = emptyList()
) {
    fun globalPackIndex(local: Int): Int = ownedPacks[local].globalIndex
    fun globalControllerIndex(local: Int): Int = ownedControllers[local].globalIndex
}

fun planLinks(packs: List<Pack>): List<LinkSpec> = planLinks(packs, emptyList())

fun planLinks(packs: List<Pack>, controllers: List<Controller>): List<LinkSpec> {
    require(packs.all { it.canId == null } && controllers.all { it.canId == null }) {
        "CAN-forwarded sources (canId != null) are not supported until Part C"
    }
    data class Acc(val packs: MutableList<OwnedSource> = mutableListOf(),
                   val controllers: MutableList<OwnedSource> = mutableListOf(),
                   val kinds: MutableSet<ProtocolKind> = linkedSetOf())
    val byAddress = LinkedHashMap<String, Acc>()
    for (p in packs.sortedBy { it.index }) {
        val acc = byAddress.getOrPut(p.bmsAddress) { Acc() }
        acc.packs += OwnedSource(p.index, p.canId)
        acc.kinds += p.bmsType.protocolKind()
    }
    for (c in controllers.sortedBy { it.index }) {
        val acc = byAddress.getOrPut(c.address) { Acc() }
        acc.controllers += OwnedSource(c.index, c.canId)
        acc.kinds += c.controllerType.protocolKind()
    }
    return byAddress.map { (address, acc) ->
        require(acc.kinds.size == 1) {
            "Address $address resolves to conflicting protocol kinds ${acc.kinds}"
        }
        LinkSpec(address, acc.kinds.first(), acc.packs.toList(), acc.controllers.toList())
    }
}
```
Then update `KableBmsRepository` call sites of `LinkSpec`/`planLinks`/`.ownedIndices`/`.bmsType` to the new shape (see Task 9 — but the *compile* must succeed now; make the mechanical renames here and leave motion wiring to Task 9). Grep `ownedIndices`, `LinkSpec(`, `.bmsType` in `data/ble/`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.LinkPlanTest"` then a full compile `./gradlew :composeApp:compileDebugKotlin`.
Expected: PASS; project compiles (call-site renames done).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/LinkPlan.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/LinkPlanTest.kt
git commit -m "feat(motion): planLinks merges packs+controllers by address; ProtocolKind/OwnedSource"
```

---

### Task 8: VehicleConnection folds motion (submitMotion + latent controllers)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/VehicleConnection.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/VehicleConnectionMotionTest.kt`

**Interfaces:**
- Consumes: `ControllerState`, `MotionAggregator`, `Controller`, existing `PackAggregator`, `BleConfig.packOfflineAfterMs`.
- Produces: `VehicleConnection` constructor gains `controllers: List<Controller> = emptyList()` and `latentControllers: List<Controller> = emptyList()`; `fun submitMotion(controllerIndex, data: ControllerData): VehicleData`; `snapshot()` builds both aggregates into one `VehicleData`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class VehicleConnectionMotionTest {
    private var now = Instant.fromEpochMilliseconds(0)
    private fun conn(controllers: List<Controller>, latent: List<Controller> = emptyList()): VehicleConnection {
        var last: VehicleData? = null
        return VehicleConnection(
            packs = emptyList(),
            controllers = controllers,
            latentControllers = latent,
            topology = PackTopology.PARALLEL,
            onVehicleData = { last = it },
            clock = { now }
        )
    }
    private fun ctrl(i: Int) = Controller(i, "c$i", ControllerType.VESC, "A$i")

    @Test fun motion_sample_reaches_aggregate() {
        val c = conn(listOf(ctrl(0)))
        val vd = c.submitMotion(0, ControllerData(speedKmh = 25f, speedSource = SpeedSource.REPORTED, isConnected = true))
        assertEquals(25f, vd.motion.speedKmh)
        assertEquals(1, vd.controllers.size)
        assertTrue(vd.controllers[0].isOnline)
    }

    @Test fun latent_controller_materialises_on_first_sample() {
        val c = conn(controllers = emptyList(), latent = listOf(ctrl(1)))
        val before = c.snapshot()
        assertEquals(0, before.controllers.size)
        val vd = c.submitMotion(1, ControllerData(speedKmh = 5f, isConnected = true))
        assertEquals(1, vd.controllers.size)
        assertEquals(1, vd.controllers[0].controller.index)
    }

    @Test fun stale_controller_marked_offline_by_a_later_submit() {
        val c = conn(listOf(ctrl(0), ctrl(1)))
        c.submitMotion(0, ControllerData(speedKmh = 10f, isConnected = true))
        now += kotlin.time.Duration.parse("${BleConfig.packOfflineAfterMs + 100}ms")
        val vd = c.submitMotion(1, ControllerData(speedKmh = 11f, isConnected = true))
        val c0 = vd.controllers.first { it.controller.index == 0 }
        assertTrue(!c0.isOnline)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.VehicleConnectionMotionTest"`
Expected: FAIL — `controllers`/`submitMotion` unknown.

- [ ] **Step 3: Write minimal implementation**

Add controller bookkeeping to `VehicleConnection` mirroring the pack side: a `ctrlStates: MutableList<ControllerState>` built from `controllers` (sorted by index), a `latentCtrl: MutableList<Controller>` filtered like `latent`, a `materialiseLatentController(index)` mirroring `materialiseLatent`, and `submitMotion` mirroring `submit` (find slot / materialise / update state with `now`, sweep the other controllers for staleness against `BleConfig.packOfflineAfterMs`, then emit). Change `snapshot()`:
```kotlin
fun snapshot(): VehicleData {
    val battery = PackAggregator.build(states.toList(), topology)
    val motion = MotionAggregator.build(ctrlStates.toList())
    return VehicleData(
        packs = battery.packs, aggregate = battery.aggregate,
        topology = topology, isPartial = battery.isPartial,
        controllers = ctrlStates.toList(), motion = motion.aggregate,
        motionPartial = motion.partial
    )
}
```
`submit` (battery) keeps using `snapshot()`, which now carries motion too — so a battery sample and a motion sample each publish a complete `VehicleData`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.VehicleConnection*"`
Expected: PASS (new motion test + existing `VehicleConnectionTest`/`VehicleConnectionLatentPackTest` — the latter unaffected since controllers default empty).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/VehicleConnection.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/VehicleConnectionMotionTest.kt
git commit -m "feat(motion): VehicleConnection folds controller states via submitMotion"
```

---

### Task 9: KableBmsRepository — motion funnel, activeMotion, motion ring buffer

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/BmsRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/memory/SampleRingBuffer.kt` (generalise to `<T>`)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryMotionTest.kt`

**Interfaces:**
- Consumes: `VehicleConnection.submitMotion`, `ControllerData`, `MotionSource`, the existing sample funnel.
- Produces: `BmsRepository.activeMotion: StateFlow<ControllerData>`; the funnel carries a sealed `Sample { PackSample, MotionSample }`; `SampleRingBuffer<T>(timestampOf: (T) -> Instant)`.

- [ ] **Step 1: Write the failing test**

```kotlin
// Follow the setup already in KableBmsRepositoryDemoTest / ...MultiLinkTest for
// constructing the repository with fakes; assert that a connected vehicle whose
// (fake/demo) protocol is a MotionSource surfaces motion on activeMotion.
@Test fun demo_or_fake_motion_reaches_activeMotion() = runTest {
    // Arrange a repository + a fake vehicle whose session protocol implements
    // MotionSource emitting speed. Reuse the existing test scaffolding pattern.
    // Act: connect, feed one motion sample through the funnel.
    // Assert:
    repo.activeMotion.test {
        // ...drive one motion sample...
        assertEquals(SpeedSource.REPORTED, awaitItem().speedSource)  // after first motion
    }
}
```
(Model this test on the existing `KableBmsRepositoryMultiLinkTest`/`...DemoTest` scaffolding — read one first for how sessions/protocols are faked and how samples are injected. The concrete driving of a motion sample depends on that harness; keep the assertion: `activeMotion` reflects the injected `ControllerData`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.KableBmsRepositoryMotionTest"`
Expected: FAIL — `activeMotion` unresolved.

- [ ] **Step 3: Write minimal implementation**

1. Generalise `SampleRingBuffer` to `SampleRingBuffer<T>(private val maxAge: Duration = 4.hours, private val hardCap: Int = 60_000, private val timestampOf: (T) -> Instant)`, replacing `sample.timestamp`/`items.first().timestamp` with `timestampOf(...)`. Update the existing `ringBuffer = SampleRingBuffer()` construction to `SampleRingBuffer<BmsData> { it.timestamp }`; update `SampleRingBufferTest` accordingly.
2. In `BmsRepository`, add `val activeMotion: StateFlow<ControllerData>`.
3. In `KableBmsRepository`:
   - Add `private val _activeMotion = MutableStateFlow(ControllerData())` and expose it; set `_activeMotion.value = vd.motion` inside the existing `onVehicleData = { vd -> _activeVehicleData.value = vd }` lambda in `buildOrchestrator`.
   - Add `private val motionRingBuffer = SampleRingBuffer<ControllerData> { it.timestamp }`.
   - Make the funnel element a sealed type: `sealed interface Sample`; `data class PackSample(...) : Sample` (keep existing fields); `data class MotionSample(val globalControllerIndex: Int, val data: ControllerData) : Sample`. Change `Channel<PackSample>` → `Channel<Sample>`.
   - Pass `controllers = vehicle.controllers` (and latent controllers, analogous to latent packs) into `buildOrchestrator`'s `VehicleConnection(...)`.
   - In `makeLinkOnSample`/pipeline, also wire the session's `onMotionSample` to `trySend(MotionSample(globalControllerIndex, data))`, translating the link-local controller index via `LinkSpec.globalControllerIndex`.
   - In `launchSampleConsumer`, branch on `Sample` type: `PackSample` → existing `submit` + `ringBuffer.push` + `_activeData`; `MotionSample` → `vehicleConnection?.submitMotion(...)` + `motionRingBuffer.push(data)` (`_activeMotion` is updated via `onVehicleData`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.KableBmsRepository*"` and `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.memory.*"`
Expected: PASS (motion test + all existing repository/ring-buffer tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/BmsRepository.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/memory/SampleRingBuffer.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryMotionTest.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/memory/SampleRingBufferTest.kt
git commit -m "feat(motion): repository routes motion through the funnel, exposes activeMotion"
```

---

### Task 10: Per-link disconnect (disconnectLink)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/BmsRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryDisconnectLinkTest.kt`

**Interfaces:**
- Consumes: the existing `PackLink` map / per-link teardown in `KableBmsRepository`.
- Produces: `suspend fun BmsRepository.disconnectLink(address: String)` — tears down the link at `address` (its session + reconnect job), drops it from the fold, leaves other links live.

- [ ] **Step 1: Write the failing test**

```kotlin
// Using the multi-link test scaffolding (see KableBmsRepositoryMultiLinkTest):
// connect a two-link vehicle, then disconnectLink(one address); assert the other
// link stays Connected and still produces samples, while the dropped link's
// pack/controller goes offline.
@Test fun disconnectLink_drops_one_link_keeps_the_rest() = runTest {
    // connect a 2-link vehicle (two addresses)
    // repo.disconnectLink(addressA)
    // assert connectionState is still Connected (addressB up)
    // assert addressA's pack is offline / gone, addressB's still online
}
```
(Model on `KableBmsRepositoryMultiLinkTest`. Assert: after `disconnectLink`, `connectionState.value` is still `Connected` and the surviving link keeps emitting.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.KableBmsRepositoryDisconnectLinkTest"`
Expected: FAIL — `disconnectLink` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add `suspend fun disconnectLink(address: String)` to `BmsRepository`, and implement in `KableBmsRepository`: under `sessionLock`, find the `PackLink` for `address`, cancel its `reconnectJob` and `tearDown()` its `session`, remove it from the links collection, then recompute the folded `connectionState` from the remaining links (reuse the existing fold). Do not touch the sample funnel or `VehicleConnection` (the dropped link simply stops submitting; its sources go stale/offline via the existing sweep). If `address` is the only/last link, this degenerates to a normal `disconnect()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.KableBmsRepository*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/BmsRepository.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryDisconnectLinkTest.kt
git commit -m "feat(motion): per-link disconnect (handoff primitive)"
```

---

### Task 11: Persistence — controllers + canId/aliasGroup + migration

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/ControllerRow.sq`
- Create: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/3.sqm`
- Modify: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/PackRow.sq` (add `canId`, `aliasGroup` columns + upsert params)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleRepository.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleRepositoryControllersTest.kt`

**Interfaces:**
- Consumes: `Vehicle.controllers`, `Controller`, `Pack.canId/aliasGroup`.
- Produces: round-trippable persistence of controllers + the new pack columns; a v3→v4 migration defaulting existing rows to zero controllers / null new columns.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.data.db

import ru.sodovaya.volty.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepositoryControllersTest {
    // Build the repo over an in-memory JVM SQLDelight driver exactly as the
    // existing SqlDelightVehicleRepositoryTest does (copy its setup).
    @Test fun vehicle_with_controllers_round_trips() = runTest {
        val repo = newInMemoryRepo()  // per existing test's helper
        val v = Vehicle(
            id = "v1", name = "Scooter", iconKey = "scooter",
            packs = listOf(Pack(0, "ANT", BmsType.ANT_BMS, "ANT0", cellCount = 20, aliasGroup = "batt")),
            controllers = listOf(
                Controller(0, "uBox0", ControllerType.VESC, "UBOX0",
                    motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254, gearRatio = 1f)),
                Controller(1, "uBox1", ControllerType.VESC, "UBOX1", providesDerivedBattery = false)
            ),
            chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now()
        )
        repo.upsert(v)
        val back = repo.get("v1")!!
        assertEquals(2, back.controllers.size)
        assertEquals("UBOX0", back.controllers[0].address)
        assertEquals(254, back.controllers[0].motor.wheelDiameterMm)
        assertEquals("batt", back.packs[0].aliasGroup)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.db.SqlDelightVehicleRepositoryControllersTest"`
Expected: FAIL — controllers not persisted (empty on read).

- [ ] **Step 3: Write minimal implementation**

`ControllerRow.sq`:
```sql
CREATE TABLE ControllerRow (
    vehicleId          TEXT NOT NULL,
    controllerIndex    INTEGER NOT NULL,
    label              TEXT NOT NULL,
    controllerType     TEXT NOT NULL,   -- enum name: VESC | FARDRIVER | KELLY | BEGODE
    address            TEXT NOT NULL,
    canId              INTEGER,          -- NULL = direct
    polePairs          INTEGER NOT NULL DEFAULT 15,
    wheelDiameterMm    INTEGER NOT NULL DEFAULT 0,
    gearRatio          REAL NOT NULL DEFAULT 1.0,
    providesDerivedBattery INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (vehicleId, controllerIndex),
    FOREIGN KEY (vehicleId) REFERENCES VehicleRow(id) ON DELETE CASCADE
);
CREATE INDEX ControllerRow_vehicle ON ControllerRow(vehicleId, controllerIndex);

selectAll:
SELECT * FROM ControllerRow ORDER BY vehicleId, controllerIndex;
selectByVehicle:
SELECT * FROM ControllerRow WHERE vehicleId = :vehicleId ORDER BY controllerIndex;
upsert:
INSERT OR REPLACE INTO ControllerRow(vehicleId, controllerIndex, label, controllerType, address, canId, polePairs, wheelDiameterMm, gearRatio, providesDerivedBattery)
VALUES (:vehicleId, :controllerIndex, :label, :controllerType, :address, :canId, :polePairs, :wheelDiameterMm, :gearRatio, :providesDerivedBattery);
deleteByVehicle:
DELETE FROM ControllerRow WHERE vehicleId = :vehicleId;
```

`PackRow.sq`: add `canId INTEGER,` and `aliasGroup TEXT,` columns to the `CREATE TABLE`, and add both to the `upsert` column list + `VALUES`. (New DB installs get them from the create; existing DBs get them via the migration below.)

`3.sqm` (v3→v4):
```sql
-- v3 -> v4: controllers become first-class; packs gain canId + aliasGroup.
ALTER TABLE PackRow ADD COLUMN canId INTEGER;
ALTER TABLE PackRow ADD COLUMN aliasGroup TEXT;

CREATE TABLE ControllerRow (
    vehicleId          TEXT NOT NULL,
    controllerIndex    INTEGER NOT NULL,
    label              TEXT NOT NULL,
    controllerType     TEXT NOT NULL,
    address            TEXT NOT NULL,
    canId              INTEGER,
    polePairs          INTEGER NOT NULL DEFAULT 15,
    wheelDiameterMm    INTEGER NOT NULL DEFAULT 0,
    gearRatio          REAL NOT NULL DEFAULT 1.0,
    providesDerivedBattery INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (vehicleId, controllerIndex),
    FOREIGN KEY (vehicleId) REFERENCES VehicleRow(id) ON DELETE CASCADE
);
CREATE INDEX ControllerRow_vehicle ON ControllerRow(vehicleId, controllerIndex);
```
(`ALTER TABLE ADD COLUMN` is supported by every SQLite in the field — unlike DROP COLUMN — so no table recreate is needed here.)

`SqlDelightVehicleRepository`: add `private val controllerQueries = provider.database.controllerRowQueries`; a `controllerRows` flow combined into `vehicles`; write controllers in the `upsert` transaction (delete-by-vehicle then re-insert, mirroring packs, and include `p.canId`/`p.aliasGroup` in the pack upsert); read them in `toDomain` (take `controllerRows` param). Extend `get()` and `delete()` analogously.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.db.*"`
Expected: PASS (new controllers round-trip + existing `SqlDelightVehicleRepositoryTest`). If SQLDelight verifyMigration is wired, run `./gradlew :composeApp:verifySqlDelightMigration` (or the module's equivalent) to confirm `3.sqm` applies on top of the v3 schema.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/sqldelight/ composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleRepository.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleRepositoryControllersTest.kt
git commit -m "feat(motion): persist controllers + pack canId/aliasGroup (v3->v4 migration)"
```

---

### Task 12: Demo simulator emits motion

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/demo/DemoBmsSimulator.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/demo/DemoMotionTest.kt`

**Interfaces:**
- Consumes: `MotionSource`, `ControllerData`, the demo's existing feed path into the repository (`connectDemo`).
- Produces: the demo vehicle exposes a controller with a believable ride curve; `VehicleData.motion` advances end-to-end through the real pipeline.

- [ ] **Step 1: Write the failing test**

```kotlin
// Read DemoBmsSimulatorTest first for how the simulator is stepped/observed.
@Test fun demo_emits_advancing_motion() = runTest {
    // Step the demo simulator N ticks; assert the produced ControllerData has
    // speedSource == REPORTED, non-zero duty tracking speed, and odometer that
    // only increases. Assert MotionAggregator over the demo controller is connected.
}
```
(Model on `DemoBmsSimulatorTest`. Concrete assertions: `speedSource == REPORTED`; `dutyPercent` rises with `speedKmh`; `odometerKm` is monotonic non-decreasing across ticks.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.demo.DemoMotionTest"`
Expected: FAIL — no motion produced yet.

- [ ] **Step 3: Write minimal implementation**

Give the demo a synthetic controller: make the demo protocol/simulator implement `MotionSource` (`controllerCount = 1`) and produce a `ControllerData` each tick — a speed curve (e.g. a smooth ramp/oscillation), `dutyPercent ≈ f(speed)`, drifting `escTempC`, monotonic `odometerKm`/`tripKm`, `speedSource = REPORTED`, `isConnected = true`. Ensure the demo vehicle synthesised by `connectDemo` carries a `Controller` so the motion path is exercised, and that the demo feed routes motion into the same `_activeMotion`/`_activeVehicleData` as the real path (or, since demo bypasses the orchestrator per `doConnect`, set `_activeMotion.value` / build a `VehicleData` with `motion` directly in the demo feed loop — match whatever the demo battery path does today).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.demo.*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/demo/DemoBmsSimulator.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/demo/DemoMotionTest.kt
git commit -m "feat(motion): demo simulator emits a synthetic ride curve"
```

---

### Task 13: Full-suite green + DI check

**Files:**
- Possibly modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/di/AppModule.kt` (only if a new singleton needs registering — `MotionAggregator`/`MotionSource` are stateless and need none; verify nothing broke).

- [ ] **Step 1: Run the whole test suite**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: PASS — all pre-existing tests plus every test added in Tasks 1–12.

- [ ] **Step 2: Compile the app (catch any Android/actual gaps)**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid` (or `:composeApp:assembleDebug` if fast enough)
Expected: BUILD SUCCESSFUL. `activeMotion`/`disconnectLink` additions to `BmsRepository` must not break any other implementer (grep for `: BmsRepository` — e.g. `StubBmsRepository`/demo doubles — and add the trivial members).

- [ ] **Step 3: Fix any implementer gaps**

If a second `BmsRepository` implementation exists (stub/fake), add `override val activeMotion = MutableStateFlow(ControllerData()).asStateFlow()` and a no-op `override suspend fun disconnectLink(address: String) {}`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore(motion): full suite green; BmsRepository implementers updated"
```

---

## Self-Review

**Spec coverage (A-foundation.md §1 scope → task):**
- Controller/ControllerData/ControllerState/SpeedSource/MotorConfig → Task 1 ✓
- canId + aliasGroup on Pack, canId on Controller → Tasks 1, 2 ✓
- BmsType.VESC_BMS → Task 2 ✓; VehicleData motion fields → Task 2 ✓
- MotionAggregator → Task 3 ✓; PackAggregator alias-collapse → Task 4 ✓
- MotionSource + MotionSampleGate → Task 5 ✓
- ConnectionSession + routeControllerSamples → Task 6 ✓
- LinkSpec/OwnedSource/planLinks/ProtocolKind → Task 7 ✓
- VehicleConnection submitMotion + latent controllers → Task 8 ✓
- KableBmsRepository funnel + activeMotion + motion ring buffer → Task 9 ✓
- per-link disconnect → Task 10 ✓
- persistence + migration → Task 11 ✓
- demo motion → Task 12 ✓
- ControllerType.KELLY / ProtocolKind.KELLY present (Task 1/7) ✓ (H reuses)

**Placeholder scan:** Tasks 6, 9, 10, 12 contain test *sketches* that say "model on the existing harness" rather than full runnable code, because the repository/session/demo test harnesses are bespoke and must be read first. This is intentional and flagged inline — the assertions and the production code are fully specified; only the fixture wiring defers to the existing pattern. Every production step has complete code or an exact edit description.

**Type consistency:** `ControllerData` field names are identical across Tasks 1/3/6/8/12. `OwnedSource(globalIndex, canId)` and `LinkSpec(address, protocolKind, ownedPacks, ownedControllers)` identical across Tasks 7/9. `submitMotion(controllerIndex, data)` identical across Tasks 8/9. `MotionResult(aggregate, partial)` used in Tasks 3/8. `activeMotion`/`disconnectLink` identical across Tasks 9/10/13.

**Scope:** one sub-project (the foundation), 13 tasks, each independently testable; produces working software (demo drives the full motion pipeline) with the battery path unchanged.
