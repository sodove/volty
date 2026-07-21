# Multi-Pack Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Сделать батарейный пакет сущностью домена, чтобы транспорт мог состоять из нескольких пакетов, — не меняя при этом наблюдаемого поведения приложения.

**Architecture:** `Vehicle` владеет списком `Pack` и топологией; агрегат (общий ток, SoC, напряжение) вычисляется чистой функцией `PackAggregator`, а не хранится. `ConnectionSession` перестаёт владеть местом назначения сэмплов и отдаёт их наружу через `onSample`; новый `VehicleConnection` держит список линий (по одной на BLE-устройство), сводит их состояния и кормит агрегатор. При одном пакете все формулы вырождаются в тождество, поэтому UI и БД ведут себя ровно как раньше.

**Tech Stack:** Kotlin Multiplatform (только androidTarget), Compose Multiplatform, Decompose, Koin, Kable (BLE), SQLDelight, kotlinx.coroutines, kotlin.test + Turbine.

**Спека:** `docs/superpowers/specs/2026-07-21-multi-pack-bms-design.md`

**Что НЕ входит:** `BegodeProtocol`, определение топологии в рантайме, UI блока «Ветки», группы независимых BMS. Это шаги 6–9 спеки, они получат отдельные планы. Шаг 6 заблокирован дампом с Begode T4.

## Global Constraints

- Пакет исходников: `ru.sodovaya.volty`. Модуль один: `composeApp`.
- Общий код — в `composeApp/src/commonMain/kotlin/`, тесты — в `composeApp/src/commonTest/kotlin/`, зеркалируя пакет.
- Компиляция: `./gradlew :composeApp:compileDebugKotlinAndroid`
- Тесты: `./gradlew :composeApp:testDebugUnitTest --tests "<полное.имя.Класса>"`
- Весь набор тестов: `./gradlew :composeApp:testDebugUnitTest`
- `kotlin.time.Instant` и `kotlin.time.Clock` требуют `@OptIn(ExperimentalTime::class)` на классе или функции — в этом проекте так везде.
- Комментарии в коде — на английском (как во всём существующем коде). Текст в UI — через `stringResource`, строки в `composeResources/values/strings.xml` и `values-ru/strings.xml`.
- **После каждой задачи весь набор тестов должен быть зелёным**, а не только новые тесты.
- **Наблюдаемое поведение приложения не меняется ни в одной задаче этого плана.** Если задача что-то меняет для пользователя — она сделана неправильно.

---

### Task 1: Доменные типы пакета и агрегатор

Чистый домен: новые файлы, ни один существующий не трогается. Здесь же — вся математика агрегации, полностью покрытая тестами без BLE и без БД.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Pack.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/VehicleData.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/PackAggregator.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/stats/PackAggregatorTest.kt`

**Interfaces:**
- Consumes: `ru.sodovaya.volty.domain.model.BmsData`, `BmsType` (существуют).
- Produces: `Pack(index, label, bmsType, bmsAddress, cellCount)`, `PackTopology.{PARALLEL, SERIES}`, `SectionState(index, voltage, temperatures)`, `PackState(pack, data, sections, isOnline, lastSeenAt)`, `VehicleData(packs, aggregate, topology, isPartial)`, `PackAggregator.aggregate(packs: List<PackState>, topology: PackTopology): BmsData`, `PackAggregator.build(packs: List<PackState>, topology: PackTopology): VehicleData`.

- [ ] **Step 1: Создать доменные типы пакета**

Создать `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Pack.kt`:

```kotlin
package ru.sodovaya.volty.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * How a vehicle's packs are wired together. Drives both the aggregation
 * formulas and how a missing pack is treated: in parallel a pack can be
 * switched out on purpose, in series a missing pack makes the aggregate
 * physically meaningless.
 */
enum class PackTopology { PARALLEL, SERIES }

/**
 * Configuration of a single battery pack: where to read it from. Persisted.
 *
 * Packs of one Begode wheel share a single [bmsAddress] — the wheel exposes
 * both of them over one BLE link. A group of independent BMS has a distinct
 * address per pack.
 */
data class Pack(
    /** 0-based; defines ordering in the UI. */
    val index: Int,
    val label: String,
    val bmsType: BmsType,
    val bmsAddress: String,
    val cellCount: Int? = null
)

/**
 * A physically replaceable assembly inside a pack (Begode: four 12S/20S
 * assemblies wired 2S2P). Empty when the BMS reports no such breakdown,
 * which is every BMS volty supports today.
 */
data class SectionState(
    val index: Int,
    val voltage: Float,
    val temperatures: List<Float> = emptyList()
)

/** Live state of one pack. */
@OptIn(ExperimentalTime::class)
data class PackState(
    val pack: Pack,
    val data: BmsData,
    val sections: List<SectionState> = emptyList(),
    val isOnline: Boolean = false,
    val lastSeenAt: Instant? = null
)
```

- [ ] **Step 2: Создать `VehicleData`**

Создать `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/VehicleData.kt`:

```kotlin
package ru.sodovaya.volty.domain.model

/**
 * What the UI observes for the active vehicle.
 *
 * [aggregate] is derived from [packs] by
 * [ru.sodovaya.volty.domain.stats.PackAggregator] — never stored, never
 * written to by anything but the aggregator.
 */
data class VehicleData(
    val packs: List<PackState> = emptyList(),
    val aggregate: BmsData = BmsData(),
    val topology: PackTopology = PackTopology.PARALLEL,
    /** true when some packs are offline and [aggregate] covers only the rest. */
    val isPartial: Boolean = false
)
```

- [ ] **Step 3: Написать падающие тесты агрегатора**

Создать `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/stats/PackAggregatorTest.kt`:

```kotlin
package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class PackAggregatorTest {

    private fun pack(index: Int) = Pack(
        index = index,
        label = "P$index",
        bmsType = BmsType.JK_BMS,
        bmsAddress = "AA:0$index"
    )

    private fun state(
        index: Int,
        voltage: Float,
        current: Float,
        soc: Float = 50f,
        charge: Float = 10f,
        capacity: Float = 20f,
        cells: List<Float> = listOf(4.0f, 4.1f),
        temps: List<Float> = listOf(25f),
        cycles: Int = 3,
        faults: List<String> = emptyList(),
        online: Boolean = true
    ) = PackState(
        pack = pack(index),
        data = BmsData(
            voltage = voltage,
            current = current,
            power = voltage * current,
            soc = soc,
            charge = charge,
            capacity = capacity,
            numCycles = cycles,
            cellVoltages = cells,
            temperatures = temps,
            bmsFaults = faults,
            isConnected = online
        ),
        isOnline = online
    )

    // --- The case that covers ~99% of users: one pack must be a no-op ---

    @Test
    fun singlePackParallelIsIdentity() {
        val only = state(0, voltage = 100.8f, current = 12.5f, soc = 87f)
        val agg = PackAggregator.aggregate(listOf(only), PackTopology.PARALLEL)
        assertEquals(only.data.voltage, agg.voltage)
        assertEquals(only.data.current, agg.current)
        assertEquals(only.data.soc, agg.soc)
        assertEquals(only.data.charge, agg.charge)
        assertEquals(only.data.capacity, agg.capacity)
        assertEquals(only.data.temperatures, agg.temperatures)
        assertTrue(agg.isConnected)
    }

    @Test
    fun singlePackSeriesIsIdentity() {
        val only = state(0, voltage = 100.8f, current = 12.5f, soc = 87f)
        val agg = PackAggregator.aggregate(listOf(only), PackTopology.SERIES)
        assertEquals(only.data.voltage, agg.voltage)
        assertEquals(only.data.current, agg.current)
        assertEquals(only.data.soc, agg.soc)
    }

    // --- Parallel ---

    @Test
    fun parallelAveragesVoltageAndSumsCurrent() {
        val packs = listOf(
            state(0, voltage = 100.6f, current = 12.0f),
            state(1, voltage = 100.8f, current = 12.4f)
        )
        val agg = PackAggregator.aggregate(packs, PackTopology.PARALLEL)
        assertEquals(100.7f, agg.voltage, absoluteTolerance = 0.001f)
        assertEquals(24.4f, agg.current, absoluteTolerance = 0.001f)
    }

    @Test
    fun parallelSumsChargeAndCapacityAndWeightsSoc() {
        val packs = listOf(
            state(0, 100f, 1f, soc = 100f, charge = 20f, capacity = 20f),
            state(1, 100f, 1f, soc = 0f, charge = 0f, capacity = 20f)
        )
        val agg = PackAggregator.aggregate(packs, PackTopology.PARALLEL)
        assertEquals(20f, agg.charge, absoluteTolerance = 0.001f)
        assertEquals(40f, agg.capacity, absoluteTolerance = 0.001f)
        assertEquals(50f, agg.soc, absoluteTolerance = 0.001f)
    }

    @Test
    fun powerIsSummedInBothTopologies() {
        val packs = listOf(
            state(0, voltage = 50f, current = 2f),   // 100 W
            state(1, voltage = 50f, current = 3f)    // 150 W
        )
        assertEquals(250f, PackAggregator.aggregate(packs, PackTopology.PARALLEL).power, absoluteTolerance = 0.01f)
        assertEquals(250f, PackAggregator.aggregate(packs, PackTopology.SERIES).power, absoluteTolerance = 0.01f)
    }

    // --- Series ---

    @Test
    fun seriesSumsVoltageAndAveragesCurrent() {
        val packs = listOf(
            state(0, voltage = 50.4f, current = 12.0f),
            state(1, voltage = 50.2f, current = 12.4f)
        )
        val agg = PackAggregator.aggregate(packs, PackTopology.SERIES)
        assertEquals(100.6f, agg.voltage, absoluteTolerance = 0.001f)
        assertEquals(12.2f, agg.current, absoluteTolerance = 0.001f)
    }

    @Test
    fun seriesTakesWorstChargeCapacityAndSoc() {
        val packs = listOf(
            state(0, 50f, 1f, soc = 80f, charge = 16f, capacity = 20f),
            state(1, 50f, 1f, soc = 60f, charge = 12f, capacity = 18f)
        )
        val agg = PackAggregator.aggregate(packs, PackTopology.SERIES)
        assertEquals(12f, agg.charge, absoluteTolerance = 0.001f)
        assertEquals(18f, agg.capacity, absoluteTolerance = 0.001f)
        assertEquals(60f, agg.soc, absoluteTolerance = 0.001f)
    }

    // --- Shared rules ---

    @Test
    fun cellVoltagesAreNeverMerged() {
        val packs = listOf(state(0, 50f, 1f), state(1, 50f, 1f))
        assertTrue(PackAggregator.aggregate(packs, PackTopology.PARALLEL).cellVoltages.isEmpty())
        assertTrue(PackAggregator.aggregate(packs, PackTopology.SERIES).cellVoltages.isEmpty())
    }

    @Test
    fun temperaturesAreUnioned() {
        val packs = listOf(
            state(0, 50f, 1f, temps = listOf(25f, 26f)),
            state(1, 50f, 1f, temps = listOf(30f))
        )
        val agg = PackAggregator.aggregate(packs, PackTopology.PARALLEL)
        assertEquals(listOf(25f, 26f, 30f), agg.temperatures)
    }

    @Test
    fun cyclesTakeTheMaximum() {
        val packs = listOf(state(0, 50f, 1f, cycles = 3), state(1, 50f, 1f, cycles = 11))
        assertEquals(11, PackAggregator.aggregate(packs, PackTopology.PARALLEL).numCycles)
    }

    @Test
    fun faultsArePrefixedWithPackLabel() {
        val packs = listOf(
            state(0, 50f, 1f, faults = listOf("Overtemp")),
            state(1, 50f, 1f, faults = listOf("Cell undervoltage"))
        )
        val agg = PackAggregator.aggregate(packs, PackTopology.PARALLEL)
        assertEquals(listOf("P0: Overtemp", "P1: Cell undervoltage"), agg.bmsFaults)
    }

    @Test
    fun singlePackFaultsAreNotPrefixed() {
        val packs = listOf(state(0, 50f, 1f, faults = listOf("Overtemp")))
        assertEquals(listOf("Overtemp"), PackAggregator.aggregate(packs, PackTopology.PARALLEL).bmsFaults)
    }

    // --- Offline packs ---

    @Test
    fun offlinePacksAreExcludedFromParallelAggregate() {
        val packs = listOf(
            state(0, voltage = 100.6f, current = 12.0f),
            state(1, voltage = 100.8f, current = 12.4f, online = false)
        )
        val agg = PackAggregator.aggregate(packs, PackTopology.PARALLEL)
        assertEquals(100.6f, agg.voltage, absoluteTolerance = 0.001f)
        assertEquals(12.0f, agg.current, absoluteTolerance = 0.001f)
        assertTrue(agg.isConnected)
    }

    @Test
    fun seriesIsDisconnectedWhenAnyPackIsOffline() {
        val packs = listOf(
            state(0, 50f, 1f),
            state(1, 50f, 1f, online = false)
        )
        assertFalse(PackAggregator.aggregate(packs, PackTopology.SERIES).isConnected)
    }

    @Test
    fun allPacksOfflineYieldsDisconnectedZeroes() {
        val packs = listOf(state(0, 50f, 1f, online = false))
        val agg = PackAggregator.aggregate(packs, PackTopology.PARALLEL)
        assertFalse(agg.isConnected)
        assertEquals(0f, agg.voltage)
    }

    @Test
    fun emptyPackListYieldsDisconnectedZeroes() {
        val agg = PackAggregator.aggregate(emptyList(), PackTopology.PARALLEL)
        assertFalse(agg.isConnected)
        assertEquals(0f, agg.voltage)
    }

    // --- build() ---

    @Test
    fun buildFlagsPartialWhenSomePackIsOffline() {
        val packs = listOf(state(0, 50f, 1f), state(1, 50f, 1f, online = false))
        val vd = PackAggregator.build(packs, PackTopology.PARALLEL)
        assertTrue(vd.isPartial)
        assertEquals(2, vd.packs.size)
        assertEquals(PackTopology.PARALLEL, vd.topology)
    }

    @Test
    fun buildIsNotPartialWhenAllPacksAreOnline() {
        val packs = listOf(state(0, 50f, 1f), state(1, 50f, 1f))
        assertFalse(PackAggregator.build(packs, PackTopology.PARALLEL).isPartial)
    }
}
```

- [ ] **Step 4: Запустить тест и убедиться, что он падает**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.stats.PackAggregatorTest"`
Expected: FAIL — компиляция не проходит, `Unresolved reference: PackAggregator`.

- [ ] **Step 5: Реализовать агрегатор**

Создать `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/PackAggregator.kt`:

```kotlin
package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.VehicleData
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Derives a vehicle-level [BmsData] from its packs. Pure — no BLE, no clock
 * reads beyond the fallback timestamp, no state. All multi-pack maths lives
 * here so it can be tested without a radio.
 *
 * Deliberately NOT aggregated: [BmsData.cellVoltages]. Concatenating cells
 * across packs would make "worst cell #14" point at an index that exists in
 * neither pack. Per-cell data is read from [PackState.data] instead.
 */
@OptIn(ExperimentalTime::class)
object PackAggregator {

    fun build(packs: List<PackState>, topology: PackTopology): VehicleData = VehicleData(
        packs = packs,
        aggregate = aggregate(packs, topology),
        topology = topology,
        isPartial = packs.isNotEmpty() && packs.any { !it.isOnline }
    )

    fun aggregate(packs: List<PackState>, topology: PackTopology): BmsData {
        val online = packs.filter { it.isOnline }
        if (online.isEmpty()) return BmsData(isConnected = false)

        val data = online.map { it.data }
        val labelled = online.size > 1

        val voltage = when (topology) {
            PackTopology.PARALLEL -> data.map { it.voltage }.average().toFloat()
            PackTopology.SERIES -> data.sumOf { it.voltage.toDouble() }.toFloat()
        }
        val current = when (topology) {
            PackTopology.PARALLEL -> data.sumOf { it.current.toDouble() }.toFloat()
            PackTopology.SERIES -> data.map { it.current }.average().toFloat()
        }
        // Total power is the sum of pack powers under BOTH topologies:
        // parallel  P = V * SUM(I) = SUM(P_i)
        // series    P = SUM(V_i) * I = SUM(P_i)
        val power = data.sumOf { it.power.toDouble() }.toFloat()

        val charge: Float
        val capacity: Float
        val soc: Float
        when (topology) {
            PackTopology.PARALLEL -> {
                charge = data.sumOf { it.charge.toDouble() }.toFloat()
                capacity = data.sumOf { it.capacity.toDouble() }.toFloat()
                // Capacity-weighted average of the packs' reported SoC —
                // reduces to the identity for a single pack. Falls back to
                // the plain mean when no pack reports capacity.
                soc = if (capacity > 0f)
                    (data.sumOf { (it.soc * it.capacity).toDouble() } / capacity).toFloat()
                else data.map { it.soc }.average().toFloat()
            }
            PackTopology.SERIES -> {
                // A series string can only deliver as much as its weakest link.
                charge = data.minOf { it.charge }
                capacity = data.minOf { it.capacity }
                soc = data.minOf { it.soc }
            }
        }

        val cycleAh = when (topology) {
            PackTopology.PARALLEL -> data.sumOf { it.cycleCapacityAh.toDouble() }.toFloat()
            PackTopology.SERIES -> data.maxOf { it.cycleCapacityAh }
        }

        return BmsData(
            voltage = voltage,
            current = current,
            power = power,
            soc = soc,
            charge = charge,
            capacity = capacity,
            numCycles = data.maxOf { it.numCycles },
            cycleCapacityAh = cycleAh,
            cellVoltages = emptyList(),
            temperatures = online.flatMap { it.data.temperatures },
            chargeEnabled = data.all { it.chargeEnabled },
            dischargeEnabled = data.all { it.dischargeEnabled },
            bmsFaults = online.flatMap { p ->
                p.data.bmsFaults.map { if (labelled) "${p.pack.label}: $it" else it }
            },
            // In series a missing pack makes the aggregate physically wrong,
            // so the vehicle only counts as connected when every pack is up.
            isConnected = when (topology) {
                PackTopology.PARALLEL -> true
                PackTopology.SERIES -> online.size == packs.size
            },
            timestamp = data.maxOfOrNull { it.timestamp } ?: Clock.System.now()
        )
    }
}
```

- [ ] **Step 6: Запустить тест и убедиться, что он проходит**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.stats.PackAggregatorTest"`
Expected: PASS, все 18 тестов.

- [ ] **Step 7: Прогнать весь набор тестов**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: PASS — новые файлы ни на что не влияют.

- [ ] **Step 8: Коммит**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Pack.kt \
        composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/VehicleData.kt \
        composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/PackAggregator.kt \
        composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/stats/PackAggregatorTest.kt
git commit -m "feat(domain): pack model and aggregation"
```

---

### Task 2: `Vehicle` владеет списком пакетов

`Vehicle` получает `packs` и `topology`. `bmsType`, `bmsAddress` и `cellCount` **не удаляются**, а становятся вычисляемыми свойствами над `packs[0]` — тогда все ~20 мест, которые их читают, компилируются без единой правки, и менять надо только 6 мест конструирования. БД в этой задаче не трогается: репозиторий по-прежнему пишет одну строку, разворачивая её в один пакет.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Vehicle.kt` (целиком)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleRepository.kt:32-89`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt:83-92, 227-231, 324-335`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/picker/PickerComponent.kt:164-174`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/vehicle/VehicleEditComponent.kt:133-145`
- Modify: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryCellCountTest.kt:58-66`
- Modify: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryDisconnectRaceTest.kt:60-68`
- Modify: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryOnAppResumedTest.kt:54-62`
- Modify: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleRepositoryTest.kt:27-35`
- Modify: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/model/VehicleGuestTest.kt:17-25`
- Modify: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/usecase/AlertEngineTest.kt:55-63`
- Modify: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/picker/PickerComponentTest.kt:63-67`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/model/VehiclePacksTest.kt`

**Interfaces:**
- Consumes: `Pack`, `PackTopology` из Task 1.
- Produces: `Vehicle(id, name, iconKey, packs: List<Pack>, topology: PackTopology, chemistry, averagingWindowMin, alertConfig, createdAt, lastConnectedAt, isPinned)`; фабрика `singlePackVehicle(id, name, iconKey, bmsType, bmsAddress, chemistry, cellCount, averagingWindowMin, alertConfig, createdAt, lastConnectedAt, isPinned): Vehicle`; вычисляемые `Vehicle.bmsType: BmsType`, `Vehicle.bmsAddress: String`, `Vehicle.cellCount: Int?`, `Vehicle.isMultiPack: Boolean`; `Vehicle.withCellCount(n: Int): Vehicle`.

- [ ] **Step 1: Написать падающий тест на новую форму `Vehicle`**

Создать `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/model/VehiclePacksTest.kt`:

```kotlin
package ru.sodovaya.volty.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class VehiclePacksTest {

    private fun single() = singlePackVehicle(
        id = "v1",
        name = "Scooter",
        iconKey = "scooter",
        bmsType = BmsType.JK_BMS,
        bmsAddress = "AA:BB:CC:DD:EE:FF",
        chemistry = Chemistry.LI_ION_NMC,
        cellCount = 16,
        createdAt = Clock.System.now()
    )

    @Test
    fun singlePackFactoryProducesExactlyOnePack() {
        val v = single()
        assertEquals(1, v.packs.size)
        assertEquals(0, v.packs[0].index)
        assertEquals(PackTopology.PARALLEL, v.topology)
        assertFalse(v.isMultiPack)
    }

    @Test
    fun legacyAccessorsReadThroughToTheFirstPack() {
        val v = single()
        assertEquals(BmsType.JK_BMS, v.bmsType)
        assertEquals("AA:BB:CC:DD:EE:FF", v.bmsAddress)
        assertEquals(16, v.cellCount)
    }

    @Test
    fun withCellCountUpdatesOnlyTheFirstPack() {
        val two = single().let { v ->
            v.copy(packs = v.packs + Pack(1, "P1", BmsType.ANT_BMS, "AA:02", cellCount = 24))
        }
        val updated = two.withCellCount(20)
        assertEquals(20, updated.packs[0].cellCount)
        assertEquals(24, updated.packs[1].cellCount)
        assertTrue(updated.isMultiPack)
    }

    @Test
    fun vehicleWithoutPacksIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            Vehicle(
                id = "v1",
                name = "Broken",
                iconKey = "generic",
                packs = emptyList(),
                topology = PackTopology.PARALLEL,
                chemistry = Chemistry.LI_ION_NMC,
                createdAt = Clock.System.now()
            )
        }
    }

    @Test
    fun guestSentinelStillWorks() {
        val guest = singlePackVehicle(
            id = "${GUEST_VEHICLE_ID_PREFIX}AA:BB",
            name = "Guest BMS",
            iconKey = "battery",
            bmsType = BmsType.JK_BMS,
            bmsAddress = "AA:BB",
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Clock.System.now()
        )
        assertTrue(guest.isGuest)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.model.VehiclePacksTest"`
Expected: FAIL — `Unresolved reference: singlePackVehicle`.

- [ ] **Step 3: Переписать `Vehicle.kt`**

Заменить содержимое `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Vehicle.kt` целиком:

```kotlin
package ru.sodovaya.volty.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class Vehicle(
    val id: String,
    val name: String,
    val iconKey: String,
    /** Never empty. Single-pack batteries — the overwhelming majority — hold exactly one. */
    val packs: List<Pack>,
    val topology: PackTopology = PackTopology.PARALLEL,
    val chemistry: Chemistry,
    val averagingWindowMin: Int = 5,
    val alertConfig: AlertConfig = AlertConfig(),
    val createdAt: Instant,
    val lastConnectedAt: Instant? = null,
    val isPinned: Boolean = false
) {
    init {
        require(packs.isNotEmpty()) { "Vehicle must have at least one pack" }
    }
}

/**
 * Builds a conventional one-BMS vehicle. Keeps the pre-multi-pack parameter
 * names so call sites read the same as before.
 */
@OptIn(ExperimentalTime::class)
fun singlePackVehicle(
    id: String,
    name: String,
    iconKey: String,
    bmsType: BmsType,
    bmsAddress: String,
    chemistry: Chemistry,
    cellCount: Int? = null,
    averagingWindowMin: Int = 5,
    alertConfig: AlertConfig = AlertConfig(),
    createdAt: Instant,
    lastConnectedAt: Instant? = null,
    isPinned: Boolean = false
): Vehicle = Vehicle(
    id = id,
    name = name,
    iconKey = iconKey,
    packs = listOf(
        Pack(index = 0, label = name, bmsType = bmsType, bmsAddress = bmsAddress, cellCount = cellCount)
    ),
    topology = PackTopology.PARALLEL,
    chemistry = chemistry,
    averagingWindowMin = averagingWindowMin,
    alertConfig = alertConfig,
    createdAt = createdAt,
    lastConnectedAt = lastConnectedAt,
    isPinned = isPinned
)

/**
 * Primary-pack shortcuts. Every consumer that predates multi-pack support
 * reads these and keeps working unchanged: for a one-pack vehicle they are
 * the whole truth, and for a multi-pack one they describe the pack the
 * vehicle is identified and connected by.
 */
val Vehicle.primaryPack: Pack get() = packs.first()
val Vehicle.bmsType: BmsType get() = primaryPack.bmsType
val Vehicle.bmsAddress: String get() = primaryPack.bmsAddress
val Vehicle.cellCount: Int? get() = primaryPack.cellCount
val Vehicle.isMultiPack: Boolean get() = packs.size > 1

/** Cell count is auto-filled from live telemetry — see KableBmsRepository. */
fun Vehicle.withCellCount(count: Int): Vehicle =
    copy(packs = packs.mapIndexed { i, p -> if (i == 0) p.copy(cellCount = count) else p })

/**
 * Marker for transient (guest) vehicles synthesized by [BmsRepository.connectGuest].
 * Their [Vehicle.id] uses the sentinel prefix `guest:` so they are never confused
 * with persisted vehicles and never touched in the saved-vehicle store.
 */
const val GUEST_VEHICLE_ID_PREFIX: String = "guest:"

/**
 * True when this vehicle is a transient guest, not persisted in the
 * [ru.sodovaya.volty.domain.repository.VehicleRepository].
 */
val Vehicle.isGuest: Boolean get() = id.startsWith(GUEST_VEHICLE_ID_PREFIX)

/**
 * Sentinel id for the simulated "Try demo" vehicle synthesized by
 * [ru.sodovaya.volty.domain.repository.BmsRepository.connectDemo]. Like a guest,
 * it is never written to the saved-vehicle store.
 */
const val DEMO_VEHICLE_ID: String = "demo"

/**
 * True when this vehicle is the simulated demo battery (see [DEMO_VEHICLE_ID]).
 * Demo is non-persistent like a guest, but distinct: it has no real BLE device
 * behind it at all.
 */
val Vehicle.isDemo: Boolean get() = id == DEMO_VEHICLE_ID
```

- [ ] **Step 4: Починить 6 мест конструирования в main**

Везде замена одна и та же: `Vehicle(` → `singlePackVehicle(`, набор именованных аргументов не меняется. Добавить импорт `ru.sodovaya.volty.domain.model.singlePackVehicle`.

1. `KableBmsRepository.kt:83` — `val DEMO_VEHICLE: Vehicle = Vehicle(` → `singlePackVehicle(`
2. `KableBmsRepository.kt:326` — в `buildGuestVehicle`: `return Vehicle(` → `return singlePackVehicle(`
3. `PickerComponent.kt:165` — `val v = Vehicle(` → `val v = singlePackVehicle(`
4. `VehicleEditComponent.kt:134` — `Vehicle(` → `singlePackVehicle(`

В `KableBmsRepository.kt:231` заменить `copy` на новый хелпер:

```kotlin
// было: vehicleRepository.upsert(vehicle.copy(cellCount = n))
vehicleRepository.upsert(vehicle.withCellCount(n))
```

Добавить импорт `ru.sodovaya.volty.domain.model.withCellCount`.

В `VehicleEditComponent.kt:143` аргумент `cellCount = existing?.cellCount` остаётся как есть — он передаётся в `singlePackVehicle`, у которой такой параметр есть.

- [ ] **Step 5: Починить репозиторий БД**

В `SqlDelightVehicleRepository.kt` функция `toDomain()` теперь строит односоставный транспорт. Заменить её целиком:

```kotlin
@OptIn(ExperimentalTime::class)
private fun VehicleRow.toDomain(): Vehicle = singlePackVehicle(
    id = id,
    name = name,
    iconKey = iconKey,
    bmsType = BmsType.valueOf(bmsType),
    bmsAddress = bmsAddress,
    chemistry = Chemistry.valueOf(chemistry),
    cellCount = cellCount?.toInt(),
    averagingWindowMin = averagingWindowMin.toInt(),
    alertConfig = AlertConfig(
        cellHighV = cellHighV?.toFloat(),
        cellLowV = cellLowV?.toFloat(),
        cellDeltaMv = cellDeltaMv?.toInt(),
        temperatureWarnC = temperatureWarnC?.toFloat(),
        temperatureHighC = temperatureHighC?.toFloat(),
        socLowPercent = socLowPercent?.toInt(),
        socCutoffPercent = socCutoffPercent?.toInt(),
        disconnectNotify = disconnectNotify == 1L,
        chargeCompleteNotify = chargeCompleteNotify == 1L
    ),
    createdAt = Instant.parse(createdAt),
    lastConnectedAt = lastConnectedAt?.let { Instant.parse(it) },
    isPinned = isPinned == 1L
)
```

`upsert` не меняется вообще: `vehicle.bmsType` и `vehicle.bmsAddress` теперь вычисляемые свойства с теми же типами. Добавить импорты `singlePackVehicle`, `bmsType`, `bmsAddress`, `cellCount`.

- [ ] **Step 6: Починить 7 мест конструирования в тестах**

В каждом файле замена та же: `Vehicle(` → `singlePackVehicle(` плюс импорт.

- `KableBmsRepositoryCellCountTest.kt:58`
- `KableBmsRepositoryDisconnectRaceTest.kt:60`
- `KableBmsRepositoryOnAppResumedTest.kt:54`
- `SqlDelightVehicleRepositoryTest.kt:27`
- `VehicleGuestTest.kt:17`
- `AlertEngineTest.kt:55`
- `PickerComponentTest.kt:63`

- [ ] **Step 7: Скомпилировать**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL. Если есть ошибки `Unresolved reference: bmsType` — не хватает импорта вычисляемого свойства в этом файле.

- [ ] **Step 8: Прогнать весь набор тестов**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: PASS. Существующие тесты не меняли смысла — только форму конструктора.

- [ ] **Step 9: Коммит**

```bash
git add -A
git commit -m "refactor(domain): vehicle owns a list of packs"
```

---

### Task 3: Пакеты в базе

Схема переезжает на версию 3: появляется `PackRow`, `VehicleRow` теряет `bmsType`/`bmsAddress`/`cellCount` и получает `topology`. Существующие транспорты мигрируют ровно в один пакет.

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/PackRow.sq`
- Create: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/2.sqm`
- Modify: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/VehicleRow.sq` (целиком)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleRepository.kt` (целиком)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleRepositoryTest.kt` (дополнить)

**Interfaces:**
- Consumes: `Vehicle`, `Pack`, `PackTopology`, `singlePackVehicle` из Task 2.
- Produces: схема БД версии 3; `SqlDelightVehicleRepository` читает и пишет `Vehicle.packs` целиком.

**Важно:** файл миграции называется `2.sqm`, потому что SQLDelight именует миграцию по версии, **из** которой она ведёт. Существующий `1.sqm` вёл из v1 в v2, значит текущая схема — v2, и новый файл `2.sqm` ведёт из v2 в v3.

- [ ] **Step 1: Написать падающие тесты БД**

Дописать в `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleRepositoryTest.kt` (сохранив существующие тесты и их хелперы):

```kotlin
    @Test
    fun roundTripsAMultiPackVehicle() = runTest {
        val repo = newRepo()
        val v = Vehicle(
            id = "wheel-1",
            name = "T4",
            iconKey = "wheel",
            packs = listOf(
                Pack(0, "Branch 1", BmsType.ANT_BMS, "AA:01", cellCount = 24),
                Pack(1, "Branch 2", BmsType.ANT_BMS, "AA:02", cellCount = 24)
            ),
            topology = PackTopology.PARALLEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Clock.System.now()
        )
        repo.upsert(v)

        val got = repo.get("wheel-1")!!
        assertEquals(2, got.packs.size)
        assertEquals("Branch 2", got.packs[1].label)
        assertEquals("AA:02", got.packs[1].bmsAddress)
        assertEquals(24, got.packs[1].cellCount)
        assertEquals(PackTopology.PARALLEL, got.topology)
    }

    @Test
    fun packsComeBackOrderedByIndex() = runTest {
        val repo = newRepo()
        val v = Vehicle(
            id = "wheel-2",
            name = "Wheel",
            iconKey = "wheel",
            packs = listOf(
                Pack(1, "Second", BmsType.JK_BMS, "AA:02"),
                Pack(0, "First", BmsType.JK_BMS, "AA:01")
            ),
            topology = PackTopology.SERIES,
            chemistry = Chemistry.LIFEPO4,
            createdAt = Clock.System.now()
        )
        repo.upsert(v)

        val got = repo.get("wheel-2")!!
        assertEquals(listOf(0, 1), got.packs.map { it.index })
        assertEquals("First", got.packs[0].label)
        assertEquals(PackTopology.SERIES, got.topology)
    }

    @Test
    fun shrinkingThePackListRemovesTheOrphanedRow() = runTest {
        val repo = newRepo()
        val two = Vehicle(
            id = "wheel-3",
            name = "Wheel",
            iconKey = "wheel",
            packs = listOf(
                Pack(0, "First", BmsType.JK_BMS, "AA:01"),
                Pack(1, "Second", BmsType.JK_BMS, "AA:02")
            ),
            topology = PackTopology.PARALLEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Clock.System.now()
        )
        repo.upsert(two)
        repo.upsert(two.copy(packs = two.packs.take(1)))

        assertEquals(1, repo.get("wheel-3")!!.packs.size)
    }

    @Test
    fun deletingAVehicleLeavesNoOrphanPacks() = runTest {
        val repo = newRepo()
        repo.upsert(sampleVehicle())
        repo.delete(sampleVehicle().id)
        assertNull(repo.get(sampleVehicle().id))
    }
```

Добавить импорты `Pack`, `PackTopology`, `assertNull`.

- [ ] **Step 2: Запустить тесты и убедиться, что они падают**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.db.SqlDelightVehicleRepositoryTest"`
Expected: FAIL — многопакетный транспорт возвращается с одним пакетом (репозиторий пока пишет только `packs[0]`).

- [ ] **Step 3: Переписать `VehicleRow.sq`**

Заменить блок `CREATE TABLE` и запросы (остальные запросы сохранить):

```sql
-- Vehicle profile table. One row per saved vehicle. Named VehicleRow to avoid
-- collision with the domain class ru.sodovaya.volty.domain.model.Vehicle.
-- Per-pack data (which BMS, at which address, how many cells) lives in PackRow.

CREATE TABLE VehicleRow (
    id                       TEXT NOT NULL PRIMARY KEY,
    name                     TEXT NOT NULL,
    iconKey                  TEXT NOT NULL,
    topology                 TEXT NOT NULL DEFAULT 'PARALLEL',  -- enum name: PARALLEL | SERIES
    chemistry                TEXT NOT NULL,   -- enum name: LI_ION_NMC | LIFEPO4 | LEAD_ACID
    averagingWindowMin       INTEGER NOT NULL DEFAULT 5,
    cellHighV                REAL,
    cellLowV                 REAL,
    cellDeltaMv              INTEGER,
    temperatureWarnC         REAL,
    temperatureHighC         REAL,
    socLowPercent            INTEGER,
    socCutoffPercent         INTEGER,
    disconnectNotify         INTEGER NOT NULL DEFAULT 1,
    chargeCompleteNotify     INTEGER NOT NULL DEFAULT 1,
    createdAt                TEXT NOT NULL,    -- ISO-8601
    lastConnectedAt          TEXT,             -- ISO-8601 or NULL
    isPinned                 INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX VehicleRow_pinned_recent ON VehicleRow(isPinned DESC, lastConnectedAt DESC, createdAt DESC);

selectAll:
SELECT * FROM VehicleRow ORDER BY isPinned DESC, COALESCE(lastConnectedAt, createdAt) DESC;

selectById:
SELECT * FROM VehicleRow WHERE id = :id;

upsert:
INSERT OR REPLACE INTO VehicleRow(
    id, name, iconKey, topology, chemistry, averagingWindowMin,
    cellHighV, cellLowV, cellDeltaMv, temperatureWarnC, temperatureHighC, socLowPercent, socCutoffPercent,
    disconnectNotify, chargeCompleteNotify, createdAt, lastConnectedAt, isPinned
) VALUES (
    :id, :name, :iconKey, :topology, :chemistry, :averagingWindowMin,
    :cellHighV, :cellLowV, :cellDeltaMv, :temperatureWarnC, :temperatureHighC, :socLowPercent, :socCutoffPercent,
    :disconnectNotify, :chargeCompleteNotify, :createdAt, :lastConnectedAt, :isPinned
);

delete:
DELETE FROM VehicleRow WHERE id = :id;

touch:
UPDATE VehicleRow SET lastConnectedAt = :now WHERE id = :id;
```

- [ ] **Step 4: Создать `PackRow.sq`**

Создать `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/PackRow.sq`:

```sql
-- One row per battery pack. A conventional battery has exactly one; a Begode
-- wheel has two sharing a single bmsAddress; a manually assembled group has
-- one per BLE device.

CREATE TABLE PackRow (
    vehicleId   TEXT NOT NULL,
    packIndex   INTEGER NOT NULL,
    label       TEXT NOT NULL,
    bmsType     TEXT NOT NULL,   -- enum name: JK_BMS | JBD_BMS | ANT_BMS | DALY_BMS
    bmsAddress  TEXT NOT NULL,
    cellCount   INTEGER,
    PRIMARY KEY (vehicleId, packIndex),
    FOREIGN KEY (vehicleId) REFERENCES VehicleRow(id) ON DELETE CASCADE
);

CREATE INDEX PackRow_vehicle ON PackRow(vehicleId, packIndex);

selectAll:
SELECT * FROM PackRow ORDER BY vehicleId, packIndex;

selectByVehicle:
SELECT * FROM PackRow WHERE vehicleId = :vehicleId ORDER BY packIndex;

upsert:
INSERT OR REPLACE INTO PackRow(vehicleId, packIndex, label, bmsType, bmsAddress, cellCount)
VALUES (:vehicleId, :packIndex, :label, :bmsType, :bmsAddress, :cellCount);

deleteByVehicle:
DELETE FROM PackRow WHERE vehicleId = :vehicleId;

deleteFromIndex:
DELETE FROM PackRow WHERE vehicleId = :vehicleId AND packIndex >= :fromIndex;
```

- [ ] **Step 5: Написать миграцию `2.sqm`**

Создать `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/2.sqm`:

```sql
-- v2 -> v3: packs become first-class. Per-pack columns move out of VehicleRow
-- into PackRow, and VehicleRow gains the wiring topology.
--
-- Columns are removed by recreating the table rather than with DROP COLUMN:
-- SQLite only learned DROP COLUMN in 3.35, and older engines are still in the
-- field on Android.

CREATE TABLE PackRow (
    vehicleId   TEXT NOT NULL,
    packIndex   INTEGER NOT NULL,
    label       TEXT NOT NULL,
    bmsType     TEXT NOT NULL,
    bmsAddress  TEXT NOT NULL,
    cellCount   INTEGER,
    PRIMARY KEY (vehicleId, packIndex),
    FOREIGN KEY (vehicleId) REFERENCES VehicleRow(id) ON DELETE CASCADE
);

CREATE INDEX PackRow_vehicle ON PackRow(vehicleId, packIndex);

-- Every existing vehicle becomes a one-pack vehicle. The pack is labelled
-- after the vehicle, which is what singlePackVehicle() does for new ones.
INSERT INTO PackRow(vehicleId, packIndex, label, bmsType, bmsAddress, cellCount)
SELECT id, 0, name, bmsType, bmsAddress, cellCount FROM VehicleRow;

CREATE TABLE VehicleRow_new (
    id                       TEXT NOT NULL PRIMARY KEY,
    name                     TEXT NOT NULL,
    iconKey                  TEXT NOT NULL,
    topology                 TEXT NOT NULL DEFAULT 'PARALLEL',
    chemistry                TEXT NOT NULL,
    averagingWindowMin       INTEGER NOT NULL DEFAULT 5,
    cellHighV                REAL,
    cellLowV                 REAL,
    cellDeltaMv              INTEGER,
    temperatureWarnC         REAL,
    temperatureHighC         REAL,
    socLowPercent            INTEGER,
    socCutoffPercent         INTEGER,
    disconnectNotify         INTEGER NOT NULL DEFAULT 1,
    chargeCompleteNotify     INTEGER NOT NULL DEFAULT 1,
    createdAt                TEXT NOT NULL,
    lastConnectedAt          TEXT,
    isPinned                 INTEGER NOT NULL DEFAULT 0
);

INSERT INTO VehicleRow_new(
    id, name, iconKey, topology, chemistry, averagingWindowMin,
    cellHighV, cellLowV, cellDeltaMv, temperatureWarnC, temperatureHighC,
    socLowPercent, socCutoffPercent, disconnectNotify, chargeCompleteNotify,
    createdAt, lastConnectedAt, isPinned
)
SELECT
    id, name, iconKey, 'PARALLEL', chemistry, averagingWindowMin,
    cellHighV, cellLowV, cellDeltaMv, temperatureWarnC, temperatureHighC,
    socLowPercent, socCutoffPercent, disconnectNotify, chargeCompleteNotify,
    createdAt, lastConnectedAt, isPinned
FROM VehicleRow;

DROP INDEX VehicleRow_pinned_recent;
DROP TABLE VehicleRow;
ALTER TABLE VehicleRow_new RENAME TO VehicleRow;

-- Recreated AFTER the rename: dropping the old table takes its index with it.
CREATE INDEX VehicleRow_pinned_recent ON VehicleRow(isPinned DESC, lastConnectedAt DESC, createdAt DESC);
```

- [ ] **Step 6: Переписать `SqlDelightVehicleRepository`**

Заменить содержимое `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleRepository.kt`:

```kotlin
package ru.sodovaya.volty.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepository(provider: VoltyDatabaseProvider) : VehicleRepository {

    private val queries = provider.database.vehicleRowQueries
    private val packQueries = provider.database.packRowQueries

    private val vehicleRows: Flow<List<VehicleRow>> = queries.selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)

    private val packRows: Flow<List<PackRow>> = packQueries.selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)

    override val vehicles: Flow<List<Vehicle>> =
        combine(vehicleRows, packRows) { rows, packs ->
            val byVehicle = packs.groupBy { it.vehicleId }
            // A vehicle with no packs cannot be constructed (Vehicle.init
            // requires at least one), and would mean a broken migration —
            // drop it rather than crash the whole list.
            rows.mapNotNull { row ->
                val own = byVehicle[row.id].orEmpty()
                if (own.isEmpty()) null else row.toDomain(own)
            }
        }.flowOn(Dispatchers.Default)

    override suspend fun get(id: String): Vehicle? {
        val row = queries.selectById(id).executeAsOneOrNull() ?: return null
        val packs = packQueries.selectByVehicle(id).executeAsList()
        if (packs.isEmpty()) return null
        return row.toDomain(packs)
    }

    override suspend fun upsert(vehicle: Vehicle) {
        val a = vehicle.alertConfig
        queries.upsert(
            id = vehicle.id,
            name = vehicle.name,
            iconKey = vehicle.iconKey,
            topology = vehicle.topology.name,
            chemistry = vehicle.chemistry.name,
            averagingWindowMin = vehicle.averagingWindowMin.toLong(),
            cellHighV = a.cellHighV?.toDouble(),
            cellLowV = a.cellLowV?.toDouble(),
            cellDeltaMv = a.cellDeltaMv?.toLong(),
            temperatureWarnC = a.temperatureWarnC?.toDouble(),
            temperatureHighC = a.temperatureHighC?.toDouble(),
            socLowPercent = a.socLowPercent?.toLong(),
            socCutoffPercent = a.socCutoffPercent?.toLong(),
            disconnectNotify = if (a.disconnectNotify) 1L else 0L,
            chargeCompleteNotify = if (a.chargeCompleteNotify) 1L else 0L,
            createdAt = vehicle.createdAt.toString(),
            lastConnectedAt = vehicle.lastConnectedAt?.toString(),
            isPinned = if (vehicle.isPinned) 1L else 0L
        )
        // Packs are stored by index, so a shrinking list would otherwise leave
        // the tail behind: drop everything at or past the new size first.
        packQueries.deleteFromIndex(vehicleId = vehicle.id, fromIndex = vehicle.packs.size.toLong())
        vehicle.packs.forEach { p ->
            packQueries.upsert(
                vehicleId = vehicle.id,
                packIndex = p.index.toLong(),
                label = p.label,
                bmsType = p.bmsType.name,
                bmsAddress = p.bmsAddress,
                cellCount = p.cellCount?.toLong()
            )
        }
    }

    override suspend fun delete(id: String) {
        // Explicit rather than relying on ON DELETE CASCADE: foreign keys are
        // off by default in SQLite unless PRAGMA foreign_keys is enabled.
        packQueries.deleteByVehicle(id)
        queries.delete(id)
    }

    override suspend fun touch(id: String) {
        queries.touch(now = Clock.System.now().toString(), id = id)
    }
}

@OptIn(ExperimentalTime::class)
private fun VehicleRow.toDomain(packRows: List<PackRow>): Vehicle = Vehicle(
    id = id,
    name = name,
    iconKey = iconKey,
    packs = packRows.sortedBy { it.packIndex }.map { p ->
        Pack(
            index = p.packIndex.toInt(),
            label = p.label,
            bmsType = BmsType.valueOf(p.bmsType),
            bmsAddress = p.bmsAddress,
            cellCount = p.cellCount?.toInt()
        )
    },
    topology = PackTopology.valueOf(topology),
    chemistry = Chemistry.valueOf(chemistry),
    averagingWindowMin = averagingWindowMin.toInt(),
    alertConfig = AlertConfig(
        cellHighV = cellHighV?.toFloat(),
        cellLowV = cellLowV?.toFloat(),
        cellDeltaMv = cellDeltaMv?.toInt(),
        temperatureWarnC = temperatureWarnC?.toFloat(),
        temperatureHighC = temperatureHighC?.toFloat(),
        socLowPercent = socLowPercent?.toInt(),
        socCutoffPercent = socCutoffPercent?.toInt(),
        disconnectNotify = disconnectNotify == 1L,
        chargeCompleteNotify = chargeCompleteNotify == 1L
    ),
    createdAt = Instant.parse(createdAt),
    lastConnectedAt = lastConnectedAt?.let { Instant.parse(it) },
    isPinned = isPinned == 1L
)
```

- [ ] **Step 7: Запустить тесты БД**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.db.SqlDelightVehicleRepositoryTest"`
Expected: PASS.

- [ ] **Step 8: Прогнать весь набор тестов**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Проверить миграцию на живом устройстве**

Установить на устройство/эмулятор, где уже стоит **предыдущая** версия приложения с сохранёнными батареями:

Run: `./gradlew :composeApp:installDebug`

Открыть приложение и убедиться: сохранённые батареи на месте, у каждой прежние имя, иконка, тип BMS, адрес и число ячеек. Если список пуст — миграция потеряла данные, откатывать и чинить `2.sqm`.

- [ ] **Step 10: Коммит**

```bash
git add -A
git commit -m "feat(db): store packs in their own table (schema v3)"
```

---

### Task 4: `ConnectionSession` отдаёт сэмплы наружу

Сессия перестаёт знать про `activeData` и ring buffer. Чистый рефакторинг: репозиторий передаёт колбэк, который делает ровно то, что раньше делала сессия.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/ConnectionSession.kt:46-56, 144-161`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt:371-384`

**Interfaces:**
- Consumes: ничего нового.
- Produces: `ConnectionSession(parentScope, peripheral, protocol, vehicle, connectionState, onSample: (packIndex: Int, BmsData) -> Unit, onDropDetected: suspend (String) -> Unit)`.

- [ ] **Step 1: Поменять конструктор сессии**

В `ConnectionSession.kt` заменить параметры `ringBuffer` и `activeData` на колбэк:

```kotlin
internal class ConnectionSession(
    private val parentScope: CoroutineScope,
    private val peripheral: Peripheral,
    private val protocol: BmsProtocol,
    private val vehicle: Vehicle?,
    private val connectionState: MutableStateFlow<ConnectionState>,
    /**
     * Called for every parsed sample. The session does not own where samples
     * go: with more than one pack behind a link there is no single
     * destination, so routing and aggregation belong to the caller.
     */
    private val onSample: (packIndex: Int, data: BmsData) -> Unit,
    /** Callback when a link drop is detected (state event or watchdog). */
    private val onDropDetected: suspend (reason: String) -> Unit
) {
```

Убрать импорты `SampleRingBuffer` и `MutableStateFlow`-специфичные, если стали не нужны (`MutableStateFlow` остаётся — его использует `connectionState`).

- [ ] **Step 2: Поменять обработку нотификаций**

В `ConnectionSession.kt` заменить тело коллектора (было `protocol.latestData()?.let { ... }`):

```kotlin
                ).collect { data ->
                    protocol.onNotification(data)
                    var got = false
                    for (packIndex in 0 until protocol.packCount) {
                        val bms = protocol.latestData(packIndex) ?: continue
                        onSample(packIndex, bms.copy(timestamp = Clock.System.now()))
                        got = true
                    }
                    if (got) {
                        lastSampleAtMs = Clock.System.now().toEpochMilliseconds()
                        sampleCount++
                        if (sampleCount % 50 == 0) {
                            println("[VOLTY-BLE] sample #$sampleCount lastSampleAtMs=$lastSampleAtMs")
                        }
                    }
                }
```

**Примечание:** `protocol.packCount` и `latestData(packIndex)` появляются в Step 3 — до него код не компилируется, это нормально.

- [ ] **Step 3: Расширить `BmsProtocol`**

В `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/BmsProtocol.kt` заменить объявление `latestData`:

```kotlin
    /**
     * How many logical packs this one link carries. One for every BMS volty
     * talks to directly; a Begode wheel multiplexes two over a single link.
     */
    open val packCount: Int get() = 1

    /** Latest fully-parsed data for [packIndex], or null if nothing parsed yet. */
    abstract fun latestData(packIndex: Int): BmsData?

    /** Convenience for the single-pack case. */
    fun latestData(): BmsData? = latestData(0)
```

В каждом из `JkBmsProtocol.kt`, `JbdBmsProtocol.kt`, `AntBmsProtocol.kt`, `DalyBmsProtocol.kt` поменять сигнатуру существующего переопределения:

```kotlin
    // было: override fun latestData(): BmsData? = latest
    override fun latestData(packIndex: Int): BmsData? = latest
```

(Имя приватного поля в каждом протоколе своё — менять только сигнатуру, тело оставить как есть.)

- [ ] **Step 4: Подставить колбэк в репозитории**

В `KableBmsRepository.kt` в `doConnect` заменить создание сессии:

```kotlin
            val session = ConnectionSession(
                parentScope = scope,
                peripheral = peripheral,
                protocol = protocol,
                vehicle = vehicle,
                connectionState = _connectionState,
                onSample = { _, sample ->
                    // Single-link, single-pack for now: the pack index is
                    // ignored until VehicleConnection lands. Order matters —
                    // the graph collector maps over _activeData and reads the
                    // ring buffer, so a sample must be in the buffer before
                    // activeData announces it, or every graph emit lags by one.
                    ringBuffer.push(sample)
                    _activeData.value = sample
                },
                onDropDetected = { reason ->
                    onSessionDrop(reason, vehicle, address, type)
                }
            )
```

- [ ] **Step 5: Скомпилировать**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Прогнать весь набор тестов**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: PASS. **Тесты протоколов (`JkBmsProtocolTest`, `JbdBmsProtocolTest`, `AntBmsProtocolTest`, `DalyBmsProtocolTest`) должны пройти без единой правки** — они зовут `latestData()` без аргумента, и это по-прежнему работает. Если понадобилось их править — сигнатура сделана неправильно.

- [ ] **Step 7: Коммит**

```bash
git add -A
git commit -m "refactor(ble): session reports samples instead of owning them"
```

---

### Task 5: `VehicleConnection` — линии, свёртка состояний, агрегация

Появляется оркестратор: список линий (по одной на BLE-устройство), воронка сэмплов через `Channel`, агрегация в `VehicleData`. При одной линии и одном пакете поведение прежнее.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/VehicleConnection.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/BmsRepository.kt:21-25`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt:114-116` (+ добавить `_activeVehicleData`)
- Modify: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/picker/PickerComponentTest.kt:37-51` (`FakeBmsRepo` реализует новое свойство)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/VehicleConnectionTest.kt`

**Interfaces:**
- Consumes: `PackAggregator.build`, `PackState`, `PackTopology`, `Pack`, `Vehicle.packs` из Task 1–2.
- Produces: `VehicleConnection(scope, vehicle, topology, onVehicleData: (VehicleData) -> Unit)` с методами `submit(packIndex: Int, data: BmsData)`, `markOffline(packIndex: Int)`, `markOnline(packIndex: Int)`, `snapshot(): VehicleData`; `BmsRepository.activeVehicleData: StateFlow<VehicleData>`.

- [ ] **Step 1: Написать падающий тест оркестратора**

Создать `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/VehicleConnectionTest.kt`:

```kotlin
package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.VehicleData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class VehicleConnectionTest {

    private val twoPacks = listOf(
        Pack(0, "Branch 1", BmsType.ANT_BMS, "AA:01"),
        Pack(1, "Branch 2", BmsType.ANT_BMS, "AA:02")
    )

    private fun conn(
        packs: List<Pack> = twoPacks,
        topology: PackTopology = PackTopology.PARALLEL,
        sink: MutableList<VehicleData> = mutableListOf()
    ) = VehicleConnection(packs = packs, topology = topology, onVehicleData = { sink += it })

    @Test
    fun startsWithEveryPackOfflineAndNoAggregate() {
        val c = conn()
        val snap = c.snapshot()
        assertEquals(2, snap.packs.size)
        assertTrue(snap.packs.none { it.isOnline })
        assertFalse(snap.aggregate.isConnected)
    }

    @Test
    fun aSubmittedSampleBringsItsPackOnline() {
        val c = conn()
        c.submit(0, BmsData(voltage = 100.6f, current = 12f, isConnected = true))
        val snap = c.snapshot()
        assertTrue(snap.packs[0].isOnline)
        assertFalse(snap.packs[1].isOnline)
        assertTrue(snap.isPartial)
        assertEquals(100.6f, snap.aggregate.voltage, absoluteTolerance = 0.001f)
    }

    @Test
    fun bothPacksOnlineClearPartialAndSumCurrent() {
        val c = conn()
        c.submit(0, BmsData(voltage = 100.6f, current = 12.0f, isConnected = true))
        c.submit(1, BmsData(voltage = 100.8f, current = 12.4f, isConnected = true))
        val snap = c.snapshot()
        assertFalse(snap.isPartial)
        assertEquals(24.4f, snap.aggregate.current, absoluteTolerance = 0.001f)
    }

    @Test
    fun markingOnePackOfflineKeepsTheOtherFeedingTheAggregate() {
        val c = conn()
        c.submit(0, BmsData(voltage = 100.6f, current = 12.0f, isConnected = true))
        c.submit(1, BmsData(voltage = 100.8f, current = 12.4f, isConnected = true))
        c.markOffline(1)
        val snap = c.snapshot()
        assertTrue(snap.isPartial)
        assertTrue(snap.packs[0].isOnline)
        assertEquals(12.0f, snap.aggregate.current, absoluteTolerance = 0.001f)
        assertTrue(snap.aggregate.isConnected)
    }

    @Test
    fun anOfflinePackKeepsItsLastData() {
        val c = conn()
        c.submit(1, BmsData(voltage = 100.8f, soc = 73f, isConnected = true))
        c.markOffline(1)
        assertEquals(73f, c.snapshot().packs[1].data.soc)
    }

    @Test
    fun emitsOnEverySubmit() {
        val sink = mutableListOf<VehicleData>()
        val c = conn(sink = sink)
        c.submit(0, BmsData(voltage = 100f, isConnected = true))
        c.submit(0, BmsData(voltage = 101f, isConnected = true))
        assertEquals(2, sink.size)
        assertEquals(101f, sink.last().aggregate.voltage, absoluteTolerance = 0.001f)
    }

    @Test
    fun seriesGoesDisconnectedWhenAPackDrops() {
        val c = conn(topology = PackTopology.SERIES)
        c.submit(0, BmsData(voltage = 50.4f, isConnected = true))
        c.submit(1, BmsData(voltage = 50.2f, isConnected = true))
        assertTrue(c.snapshot().aggregate.isConnected)
        c.markOffline(1)
        assertFalse(c.snapshot().aggregate.isConnected)
    }

    @Test
    fun submitForAnUnknownPackIndexIsIgnored() {
        val c = conn()
        c.submit(7, BmsData(voltage = 100f, isConnected = true))
        assertTrue(c.snapshot().packs.none { it.isOnline })
    }

    @Test
    fun singlePackBehavesExactlyLikeBefore() {
        val c = conn(packs = listOf(Pack(0, "Battery", BmsType.JK_BMS, "AA:01")))
        val sample = BmsData(voltage = 58.4f, current = 3.2f, soc = 91f, isConnected = true)
        c.submit(0, sample)
        val snap = c.snapshot()
        assertFalse(snap.isPartial)
        assertEquals(sample.voltage, snap.aggregate.voltage)
        assertEquals(sample.current, snap.aggregate.current)
        assertEquals(sample.soc, snap.aggregate.soc)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.VehicleConnectionTest"`
Expected: FAIL — `Unresolved reference: VehicleConnection`.

- [ ] **Step 3: Реализовать `VehicleConnection`**

Создать `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/VehicleConnection.kt`:

```kotlin
package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.stats.PackAggregator
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Holds the live state of every pack of one vehicle and derives the
 * vehicle-level view from it.
 *
 * Deliberately synchronous and free of coroutines: samples arrive from
 * several [ConnectionSession] coroutines at once, so the repository funnels
 * them through a single consumer and calls in here from that one place. That
 * keeps the shared state single-threaded by construction instead of by lock.
 */
@OptIn(ExperimentalTime::class)
internal class VehicleConnection(
    packs: List<Pack>,
    private val topology: PackTopology,
    private val onVehicleData: (VehicleData) -> Unit
) {

    private val states: MutableList<PackState> = packs
        .sortedBy { it.index }
        .map { PackState(pack = it, data = BmsData(), isOnline = false) }
        .toMutableList()

    /** Feed a freshly parsed sample for one pack. Unknown indices are ignored. */
    fun submit(packIndex: Int, data: BmsData) {
        val slot = states.indexOfFirst { it.pack.index == packIndex }
        if (slot < 0) return
        states[slot] = states[slot].copy(
            data = data,
            isOnline = true,
            lastSeenAt = Clock.System.now()
        )
        emit()
    }

    /**
     * Mark a pack as no longer reporting. Its last data is kept so the UI can
     * grey it out with the values it had, rather than blanking the card.
     */
    fun markOffline(packIndex: Int) {
        val slot = states.indexOfFirst { it.pack.index == packIndex }
        if (slot < 0 || !states[slot].isOnline) return
        states[slot] = states[slot].copy(isOnline = false)
        emit()
    }

    fun markOnline(packIndex: Int) {
        val slot = states.indexOfFirst { it.pack.index == packIndex }
        if (slot < 0 || states[slot].isOnline) return
        states[slot] = states[slot].copy(isOnline = true)
        emit()
    }

    fun snapshot(): VehicleData = PackAggregator.build(states.toList(), topology)

    private fun emit() = onVehicleData(snapshot())
}
```

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.VehicleConnectionTest"`
Expected: PASS, все 9 тестов.

- [ ] **Step 5: Добавить `activeVehicleData` в интерфейс репозитория**

В `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/BmsRepository.kt` добавить в интерфейс рядом с `activeData`:

```kotlin
interface BmsRepository {
    /**
     * Per-pack view of the active vehicle plus the derived aggregate.
     * [activeData] is the aggregate of this — kept as a separate property so
     * the dashboard, notification and alert engine need no changes.
     */
    val activeVehicleData: StateFlow<VehicleData>

    val activeData: StateFlow<BmsData>
```

Добавить импорт `ru.sodovaya.volty.domain.model.VehicleData`.

- [ ] **Step 6: Подключить оркестратор в `KableBmsRepository`**

Добавить поле рядом с `_activeData` (строка ~114):

```kotlin
    private val _activeVehicleData = MutableStateFlow(VehicleData())
    override val activeVehicleData: StateFlow<VehicleData> = _activeVehicleData.asStateFlow()

    /**
     * Orchestrator for the currently connected vehicle. Null when nothing is
     * connected. Written only under [sessionLock]; sample submission happens
     * from the single funnel below.
     */
    private var vehicleConnection: VehicleConnection? = null
```

В `doConnect`, сразу после `_activeVehicle.value = vehicle` (строка ~361), создать оркестратор:

```kotlin
            vehicleConnection = VehicleConnection(
                packs = vehicle?.packs ?: listOf(
                    Pack(index = 0, label = "Battery", bmsType = type, bmsAddress = address)
                ),
                topology = vehicle?.topology ?: PackTopology.PARALLEL,
                onVehicleData = { vd -> _activeVehicleData.value = vd }
            )
```

Заменить `onSample` из Task 4 на маршрутизацию через оркестратор:

```kotlin
                onSample = { packIndex, sample ->
                    vehicleConnection?.submit(packIndex, sample)
                    // Ring buffer before activeData: the graph collector maps
                    // over _activeData and reads the buffer, so announcing the
                    // sample first would make every graph emit lag by one.
                    val aggregate = vehicleConnection?.snapshot()?.aggregate ?: sample
                    ringBuffer.push(aggregate)
                    _activeData.value = aggregate
                },
```

В `disconnect()` рядом с `_activeData.value = BmsData()` (строка ~543) добавить:

```kotlin
        _activeVehicleData.value = VehicleData()
        vehicleConnection = null
```

В `connectDemo()` внутри `sessionLock.withLock { … }` добавить `vehicleConnection = null`, а в `demoJob` оставить прямую подачу в `_activeData` как есть — демо не проходит через оркестратор, у него нет линий.

Добавить импорты `Pack`, `PackTopology`, `VehicleData`.

- [ ] **Step 7: Починить фейковый репозиторий в тестах**

Расширение интерфейса `BmsRepository` ломает `FakeBmsRepo` в
`composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/picker/PickerComponentTest.kt:40`.
Добавить рядом с `activeData`:

```kotlin
        override val activeVehicleData = MutableStateFlow(VehicleData())
```

плюс импорт `ru.sodovaya.volty.domain.model.VehicleData`.

- [ ] **Step 8: Прогнать весь набор тестов**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: PASS. Если `KableBmsRepositoryCellCountTest` упал — вероятная причина в том, что `emitActiveDataForTest` пишет в `_activeData` мимо оркестратора; это ожидаемо и правильно, тест проверяет автозаполнение числа ячеек, а не агрегацию.

- [ ] **Step 9: Проверить на устройстве**

Run: `./gradlew :composeApp:installDebug`

Подключиться к реальной BMS и убедиться: дашборд показывает те же цифры, что и до рефакторинга; график рисуется без ступенек и без отставания; отключение и переподключение работают.

- [ ] **Step 10: Коммит**

```bash
git add -A
git commit -m "feat(ble): vehicle connection orchestrates packs and aggregation"
```

---

### Task 6: Дашборд читает `VehicleData`

Дашборд начинает получать по-пакетное состояние. При одном пакете он ничего нового не рисует — это подготовка почвы для блока «Ветки» из следующего плана.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/dashboard/DashboardComponent.kt:39-58, 100-118`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/dashboard/DashboardComponentPacksTest.kt`

**Interfaces:**
- Consumes: `BmsRepository.activeVehicleData` из Task 5.
- Produces: `DashboardComponent.State.packs: List<PackState>`, `DashboardComponent.State.isPartial: Boolean`.

- [ ] **Step 1: Написать падающий тест компонента**

Тест проверяет именно проводку: что `State.packs` и `State.isPartial` действительно
приходят из `BmsRepository.activeVehicleData`. Форма фейков скопирована с
`PickerComponentTest` — это принятый в проекте способ тестировать компоненты.

Создать `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/dashboard/DashboardComponentPacksTest.kt`:

```kotlin
package ru.sodovaya.volty.presentation.dashboard

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
import ru.sodovaya.volty.domain.stats.PackAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class DashboardComponentPacksTest {

    private class FakeBmsRepo : BmsRepository {
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override fun scanAll(): Flow<DiscoveredDevice> = emptyFlow()
        override suspend fun connect(vehicle: Vehicle): Result<Unit> = Result.success(Unit)
        override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> = Result.success(Unit)
        override suspend fun connectDemo(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override fun samples(window: Duration): Flow<List<BmsData>> = flowOf(emptyList())
        override fun movingAverage(window: Duration): Flow<MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() {}
    }

    private class FakeVehicleRepo : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
    }

    private fun component(repo: FakeBmsRepo): DefaultDashboardComponent =
        DefaultDashboardComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            bmsRepository = repo,
            vehicleRepository = FakeVehicleRepo(),
            onOpenGraph = {},
            onOpenSettings = {},
            onOpenAddBattery = {},
            onDisconnectRequested = {}
        )

    private fun packState(index: Int, voltage: Float, online: Boolean) = PackState(
        pack = Pack(index, "Branch ${index + 1}", BmsType.ANT_BMS, "AA:0$index"),
        data = BmsData(voltage = voltage, isConnected = online),
        isOnline = online
    )

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `packs and partial flag reach the state from activeVehicleData`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.activeVehicleData.value = PackAggregator.build(
            listOf(packState(0, 100.6f, online = true), packState(1, 100.8f, online = false)),
            PackTopology.PARALLEL
        )
        advanceUntilIdle()

        val s = c.state.value
        assertEquals(listOf("Branch 1", "Branch 2"), s.packs.map { it.pack.label })
        assertTrue(s.isPartial)
        assertFalse(s.packs[1].isOnline)
    }

    @Test
    fun `a single online pack is not flagged partial`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.activeVehicleData.value = PackAggregator.build(
            listOf(packState(0, 58.4f, online = true)),
            PackTopology.PARALLEL
        )
        advanceUntilIdle()

        val s = c.state.value
        assertEquals(1, s.packs.size)
        assertFalse(s.isPartial)
    }

    @Test
    fun `state starts with no packs before anything connects`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(FakeBmsRepo())
        advanceUntilIdle()

        assertTrue(c.state.value.packs.isEmpty())
        assertFalse(c.state.value.isPartial)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.dashboard.DashboardComponentPacksTest"`
Expected: FAIL — `Unresolved reference: packs` в `DashboardComponent.State`.

- [ ] **Step 3: Добавить поля в состояние дашборда**

В `DashboardComponent.State` добавить:

```kotlin
        /** Per-pack state. Exactly one entry for a conventional battery. */
        val packs: List<PackState> = emptyList(),
        /** true when some pack is offline and the aggregate covers only the rest. */
        val isPartial: Boolean = false,
```

Добавить в `init` отдельный коллектор рядом с существующими (не трогая
коллектор `activeData`/`activeVehicle`, чтобы не менять поведение остальных
полей):

```kotlin
        scope.launch {
            bmsRepository.activeVehicleData.collect { vd ->
                _state.update { it.copy(packs = vd.packs, isPartial = vd.isPartial) }
            }
        }
```

Добавить импорт `ru.sodovaya.volty.domain.model.PackState`.

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.dashboard.DashboardComponentPacksTest"`
Expected: PASS, все 3 теста.

- [ ] **Step 5: Скомпилировать и прогнать всё**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid && ./gradlew :composeApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, все тесты PASS.

- [ ] **Step 6: Проверить, что UI не изменился**

Run: `./gradlew :composeApp:installDebug`

Открыть дашборд с обычной одиночной BMS. Экран должен выглядеть **точно так же**, как до всего этого плана: те же плитки, та же секция ячеек, тот же график. Любое видимое отличие — баг.

- [ ] **Step 7: Коммит**

```bash
git add -A
git commit -m "feat(dashboard): expose per-pack state"
```

---

## Отклонения от спеки

Обнаружены при написании плана, спека обновлена под них:

1. **`Vehicle` не теряет `bmsType`/`bmsAddress`/`cellCount`.** Спека говорила их удалить. Подсчёт по коду: их читают ~20 мест, а конструируют `Vehicle` только 6 в main и 7 в тестах. Превращение их в вычисляемые свойства над `packs[0]` оставляет все чтения нетронутыми и сводит задачу к механической замене конструктора на фабрику. Семантика для односоставного транспорта не меняется; для многопакетного они описывают первый пакет — тот, по которому транспорт опознаётся и подключается.

2. **`power` суммируется при обеих топологиях.** В таблице агрегации спеки для `SERIES` стояло «среднее» — это ошибка: полная мощность равна сумме мощностей пакетов и в параллели (`V·ΣI`), и в последовательности (`ΣV_i·I`). Усреднение занижало бы мощность вдвое на двухпакетной последовательной сборке.

3. **Сопоставление сохранённого транспорта с найденным устройством** (`associateBy { it.bmsAddress }` в `KableBmsRepository:236`, `PickerComponent:76`, `ScanningComponent:62`) продолжает смотреть только на первый пакет. Для Begode это верно (оба пакета за одним адресом), для группы независимых BMS — нет: транспорт не найдётся, если в радиусе только второе устройство. Чинится в плане про группы, где появляется сопоставление по всем адресам пакетов.

## Проверка перед сдачей плана

- Все шаги 1–5 спеки покрыты: домен и агрегатор → Task 1, `Vehicle.packs` → Task 2, БД → Task 3, развязка сессии → Task 4, оркестратор → Task 5, UI при N=1 → Task 6.
- Ни одна задача не меняет наблюдаемого поведения. Task 3 и Task 5 требуют проверки на устройстве именно потому, что молчаливая регрессия там наиболее вероятна (потеря сохранённых батарей и отставание графика соответственно).
- Тесты протоколов JK/JBD/ANT/Daly не правятся ни в одной задаче — это встроенная проверка того, что рефакторинг не задел существующие BMS.
