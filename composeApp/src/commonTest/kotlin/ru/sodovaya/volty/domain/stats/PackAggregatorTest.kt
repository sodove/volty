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
        assertEquals(only.data.cellVoltages, agg.cellVoltages)
        assertTrue(agg.isConnected)
    }

    @Test
    fun singlePackSeriesIsIdentity() {
        val only = state(0, voltage = 100.8f, current = 12.5f, soc = 87f)
        val agg = PackAggregator.aggregate(listOf(only), PackTopology.SERIES)
        assertEquals(only.data.voltage, agg.voltage)
        assertEquals(only.data.current, agg.current)
        assertEquals(only.data.soc, agg.soc)
        assertEquals(only.data.charge, agg.charge)
        assertEquals(only.data.capacity, agg.capacity)
        assertEquals(only.data.temperatures, agg.temperatures)
        assertEquals(only.data.cellVoltages, agg.cellVoltages)
        assertTrue(agg.isConnected)
    }

    @Test
    fun singlePackParallelSocIdentitySurvivesInexactFloatProducts() {
        // Arithmetically hostile fixture: 19 x 13.6 is NOT exactly
        // representable as a Float x Float product. A Float multiply rounds
        // before widening to Double, so the weighted SoC average returns
        // ~18.999998f instead of 19f — which soc.toInt() truncates to 18%
        // in the persistent notification and can trip the low-SoC alert a
        // whole percentage point early. Do NOT "tidy" these numbers into
        // round ones (e.g. 87f / 20f): exactly representable products make
        // this test blind to the regression it exists to catch.
        val only = state(0, voltage = 84f, current = 1f, soc = 19f, charge = 2.6f, capacity = 13.6f)
        val agg = PackAggregator.aggregate(listOf(only), PackTopology.PARALLEL)
        // No absoluteTolerance here on purpose: a tolerance would hide
        // exactly the precision loss under test.
        assertEquals(only.data.soc, agg.soc)
        assertEquals(only.data.soc.toInt(), agg.soc.toInt())
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

    // JBD and Daly BMS commonly report SoC without capacity; the weighted
    // mean must fall back to a plain mean instead of collapsing to 0%.

    @Test
    fun parallelFallsBackToPlainMeanSocWhenNoPackReportsCapacity() {
        val packs = listOf(
            state(0, 50f, 1f, soc = 80f, charge = 0f, capacity = 0f),
            state(1, 50f, 1f, soc = 60f, charge = 0f, capacity = 0f)
        )
        val agg = PackAggregator.aggregate(packs, PackTopology.PARALLEL)
        assertEquals(70f, agg.soc, absoluteTolerance = 0.001f)
    }

    @Test
    fun singlePackWithZeroCapacityKeepsItsReportedSoc() {
        // The JBD/Daly single-battery case — the most common real setup.
        val only = state(0, 50f, 1f, soc = 87f, charge = 0f, capacity = 0f)
        val agg = PackAggregator.aggregate(listOf(only), PackTopology.PARALLEL)
        assertEquals(87f, agg.soc, absoluteTolerance = 0.001f)
    }

    @Test
    fun zeroCapacityPackContributesZeroWeightToParallelSoc() {
        val packs = listOf(
            state(0, 50f, 1f, soc = 80f, charge = 16f, capacity = 20f),
            state(1, 50f, 1f, soc = 10f, charge = 0f, capacity = 0f)
        )
        val agg = PackAggregator.aggregate(packs, PackTopology.PARALLEL)
        // Total capacity is 20, so the weighted branch is taken and the
        // capacity-less pack carries zero weight: (80*20 + 10*0) / 20 = 80.
        assertEquals(80f, agg.soc, absoluteTolerance = 0.001f)
        assertEquals(20f, agg.capacity, absoluteTolerance = 0.001f)
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
    fun cellVoltagesAreUnionedInPackOrderUnderBothTopologies() {
        // The alert engine reads min/max/spread off the aggregate, so every
        // cell of every online pack must be present — dropping them would
        // silently disable the cell alerts on multi-pack vehicles.
        val packs = listOf(
            state(0, 50f, 1f, cells = listOf(3.30f, 3.31f)),
            state(1, 50f, 1f, cells = listOf(3.32f, 3.45f, 3.29f))
        )
        val expected = listOf(3.30f, 3.31f, 3.32f, 3.45f, 3.29f)
        assertEquals(expected, PackAggregator.aggregate(packs, PackTopology.PARALLEL).cellVoltages)
        assertEquals(expected, PackAggregator.aggregate(packs, PackTopology.SERIES).cellVoltages)
    }

    @Test
    fun offlinePackCellsAreExcludedFromTheUnion() {
        // An offline pack's cells are stale — they must not drive an alert.
        // The offline pack here carries alarming values to make a leak fail loudly.
        val packs = listOf(
            state(0, 50f, 1f, cells = listOf(3.30f, 3.31f)),
            state(1, 50f, 1f, cells = listOf(2.10f, 4.90f), online = false)
        )
        val expected = listOf(3.30f, 3.31f)
        assertEquals(expected, PackAggregator.aggregate(packs, PackTopology.PARALLEL).cellVoltages)
        assertEquals(expected, PackAggregator.aggregate(packs, PackTopology.SERIES).cellVoltages)
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
    fun seriesIsConnectedWhenAllPacksAreOnline() {
        val packs = listOf(
            state(0, 50f, 1f),
            state(1, 50f, 1f)
        )
        assertTrue(PackAggregator.aggregate(packs, PackTopology.SERIES).isConnected)
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
