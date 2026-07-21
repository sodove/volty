package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.singlePackVehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * [VoltageSocEstimator] maps average cell voltage linearly onto 0..100 %
 * between the vehicle's configured cell-voltage bounds (alert thresholds,
 * falling back to chemistry defaults). It exists for devices that report no
 * coulomb-counted SoC at all — a Begode wheel gives voltage and cells only —
 * and must never touch a sample whose BMS already reported a real SoC.
 */
@OptIn(ExperimentalTime::class)
class VoltageSocEstimatorTest {

    private val nmcDefaults = AlertConfig() // cellHighV/cellLowV null -> chemistry defaults

    private fun estimate(
        cells: List<Float>,
        chemistry: Chemistry = Chemistry.LI_ION_NMC,
        config: AlertConfig = nmcDefaults
    ): Float = VoltageSocEstimator.estimateSocPercent(cells, chemistry, config)

    // --- The pure mapping ---

    @Test
    fun fullCellVoltageGives100() {
        // NMC default high bound is 4.20 V.
        assertEquals(100f, estimate(List(40) { 4.20f }), 0.01f)
    }

    @Test
    fun emptyCellVoltageGives0() {
        // NMC default low bound is 2.80 V.
        assertEquals(0f, estimate(List(40) { 2.80f }), 0.01f)
    }

    @Test
    fun midpointGives50() {
        // (2.80 + 4.20) / 2 = 3.50 V.
        assertEquals(50f, estimate(List(16) { 3.50f }), 0.01f)
    }

    @Test
    fun aboveRangeClampsTo100() {
        assertEquals(100f, estimate(listOf(4.35f, 4.35f)), 0.0f)
    }

    @Test
    fun belowRangeClampsTo0() {
        assertEquals(0f, estimate(listOf(2.50f, 2.50f)), 0.0f)
    }

    @Test
    fun configuredBoundsOverrideChemistryDefaults() {
        // User narrowed the window to 3.00..4.10 V: a 4.10 V cell is now full
        // and 3.55 V is the midpoint, regardless of the NMC 2.80/4.20 defaults.
        val config = AlertConfig(cellHighV = 4.10f, cellLowV = 3.00f)
        assertEquals(100f, estimate(List(20) { 4.10f }, config = config), 0.01f)
        assertEquals(0f, estimate(List(20) { 3.00f }, config = config), 0.01f)
        assertEquals(50f, estimate(List(20) { 3.55f }, config = config), 0.01f)
    }

    @Test
    fun lifepo4DefaultsAreHonoured() {
        // LiFePO4: 2.50..3.65 V. Midpoint 3.075 V -> 50 %.
        assertEquals(50f, estimate(List(4) { 3.075f }, chemistry = Chemistry.LIFEPO4), 0.01f)
    }

    @Test
    fun emptyCellListYieldsZeroNotNaN() {
        val result = estimate(emptyList())
        assertFalse(result.isNaN(), "empty cell list must not produce NaN")
        assertEquals(0f, result, 0.0f)
    }

    @Test
    fun fieldReadingEtMax40CellsAt409() {
        // The real field reading that exposed the bug: Begode ET Max, 40 cells
        // at 4.09 V, Li-ion NMC defaults. (4.09 - 2.80) / (4.20 - 2.80) = 92.14 %.
        assertEquals(92.14f, estimate(List(40) { 4.09f }), 0.05f)
    }

    // --- The guarded application to a sample ---

    @OptIn(ExperimentalTime::class)
    private fun vehicle(config: AlertConfig = nmcDefaults) = singlePackVehicle(
        id = "test",
        name = "Test wheel",
        iconKey = "wheel",
        bmsType = BmsType.BEGODE,
        bmsAddress = "AA:BB",
        chemistry = Chemistry.LI_ION_NMC,
        alertConfig = config,
        createdAt = Instant.fromEpochMilliseconds(0)
    )

    @Test
    fun zeroSocSampleWithCellsGetsEstimated() {
        val sample = BmsData(voltage = 163.6f, cellVoltages = List(40) { 4.09f }, isConnected = true)
        val enriched = VoltageSocEstimator.withEstimatedSoc(sample, vehicle())
        assertEquals(92.14f, enriched.soc, 0.05f)
        // Everything else passes through untouched.
        assertEquals(sample.voltage, enriched.voltage, 0f)
        assertEquals(sample.cellVoltages, enriched.cellVoltages)
    }

    @Test
    fun reportedSocIsNeverOverwritten() {
        // A JK/ANT sample carries a real, coulomb-counted SoC. The voltage
        // estimate must never replace it — even when cells are present and
        // would estimate a very different number.
        val sample = BmsData(soc = 37f, cellVoltages = List(16) { 4.09f }, isConnected = true)
        val out = VoltageSocEstimator.withEstimatedSoc(sample, vehicle())
        assertSame(sample, out, "sample with a reported SoC must pass through untouched")
        assertEquals(37f, out.soc, 0.0f)
    }

    @Test
    fun zeroSocWithoutCellsIsLeftAlone() {
        // Nothing to estimate from — a fabricated number would be worse than 0.
        val sample = BmsData(voltage = 147.2f, isConnected = true)
        val out = VoltageSocEstimator.withEstimatedSoc(sample, vehicle())
        assertEquals(0f, out.soc, 0.0f)
        assertSame(sample, out)
    }

    @Test
    fun nullVehicleLeavesSampleUntouched() {
        // No vehicle -> no chemistry or thresholds to estimate against.
        val sample = BmsData(cellVoltages = List(40) { 4.09f }, isConnected = true)
        assertSame(sample, VoltageSocEstimator.withEstimatedSoc(sample, null))
    }

    @Test
    fun estimatorHonoursVehicleConfiguredBounds() {
        val sample = BmsData(cellVoltages = List(20) { 3.55f }, isConnected = true)
        val v = vehicle(AlertConfig(cellHighV = 4.10f, cellLowV = 3.00f))
        assertEquals(50f, VoltageSocEstimator.withEstimatedSoc(sample, v).soc, 0.01f)
    }
}
