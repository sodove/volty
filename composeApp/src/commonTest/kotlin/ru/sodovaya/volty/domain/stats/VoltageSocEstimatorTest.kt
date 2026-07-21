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
 * falling back to the chemistry's usable range emptyCellV..defaultHighV).
 * It exists for devices that report no coulomb-counted SoC at all — a Begode
 * wheel gives voltage and cells only — and must never touch a sample from a
 * BMS type that reports its own SoC, not even a genuine 0 % on a flat pack.
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
        // NMC usable-range floor is emptyCellV = 3.30 V — the point where an
        // EUC has no usable energy left — NOT defaultLowV = 2.80 V, which is
        // the cell-damage threshold behind the low-cell alert.
        assertEquals(0f, estimate(List(40) { 3.30f }), 0.01f)
    }

    @Test
    fun midpointGives50() {
        // (3.30 + 4.20) / 2 = 3.75 V.
        assertEquals(50f, estimate(List(16) { 3.75f }), 0.01f)
    }

    @Test
    fun aboveRangeClampsTo100() {
        assertEquals(100f, estimate(listOf(4.35f, 4.35f)), 0.0f)
    }

    @Test
    fun belowRangeClampsTo0() {
        // 3.00 V is below the 3.30 V usable floor: 0 %, not negative. The
        // low-cell alert (2.80 V) has not even fired yet — SoC reaches empty
        // first, by design.
        assertEquals(0f, estimate(listOf(3.00f, 3.00f)), 0.0f)
    }

    @Test
    fun configuredBoundsOverrideChemistryDefaults() {
        // User narrowed the window to 3.00..4.10 V: a 4.10 V cell is now full
        // and 3.55 V is the midpoint, regardless of the NMC 3.30/4.20 defaults.
        // A user-configured cellLowV wins over emptyCellV — the bounds the
        // user configured ARE their 0 % and 100 %.
        val config = AlertConfig(cellHighV = 4.10f, cellLowV = 3.00f)
        assertEquals(100f, estimate(List(20) { 4.10f }, config = config), 0.01f)
        assertEquals(0f, estimate(List(20) { 3.00f }, config = config), 0.01f)
        assertEquals(50f, estimate(List(20) { 3.55f }, config = config), 0.01f)
    }

    @Test
    fun lifepo4DefaultsAreHonoured() {
        // LiFePO4 usable range: emptyCellV 3.00 .. defaultHighV 3.65 V.
        // Midpoint 3.325 V -> 50 %.
        assertEquals(50f, estimate(List(4) { 3.325f }, chemistry = Chemistry.LIFEPO4), 0.01f)
    }

    @Test
    fun emptyCellListYieldsZeroNotNaN() {
        val result = estimate(emptyList())
        assertFalse(result.isNaN(), "empty cell list must not produce NaN")
        assertEquals(0f, result, 0.0f)
    }

    @Test
    fun equalBoundsYieldZeroNotNaN() {
        // Degenerate window: high == low -> span is 0, division would be NaN.
        val config = AlertConfig(cellHighV = 3.50f, cellLowV = 3.50f)
        val result = estimate(List(20) { 3.50f }, config = config)
        assertFalse(result.isNaN(), "zero-span bounds must not produce NaN")
        assertEquals(0f, result, 0.0f)
    }

    @Test
    fun invertedBoundsYieldZero() {
        // Misconfigured window: high below low -> negative span. The mapping
        // is meaningless, so the estimator refuses with 0 rather than
        // extrapolating a negative or inverted percentage.
        val config = AlertConfig(cellHighV = 3.00f, cellLowV = 4.00f)
        assertEquals(0f, estimate(List(20) { 3.50f }, config = config), 0.0f)
    }

    @Test
    fun fieldReadingEtMax40CellsAt409() {
        // Real field reading: Begode ET Max, 40 cells at 4.09 V, Li-ion NMC
        // defaults. (4.09 - 3.30) / (4.20 - 3.30) = 87.78 % — about 88 %,
        // down from 92 % under the old 2.80 V damage-threshold floor.
        assertEquals(87.78f, estimate(List(40) { 4.09f }), 0.05f)
    }

    @Test
    fun fieldReadingEtMaxAt38825() {
        // Second reading from the same wheel: 3.8825 V per cell.
        // (3.8825 - 3.30) / 0.90 = 64.72 % — within a few points of the 68 %
        // another app showed at this exact voltage. The old 2.80 V floor gave
        // an inflated 77 %.
        assertEquals(64.72f, estimate(List(40) { 3.8825f }), 0.05f)
    }

    // --- The guarded application to a sample ---

    @OptIn(ExperimentalTime::class)
    private fun vehicle(
        config: AlertConfig = nmcDefaults,
        bmsType: BmsType = BmsType.BEGODE
    ) = singlePackVehicle(
        id = "test",
        name = "Test wheel",
        iconKey = "wheel",
        bmsType = bmsType,
        bmsAddress = "AA:BB",
        chemistry = Chemistry.LI_ION_NMC,
        alertConfig = config,
        createdAt = Instant.fromEpochMilliseconds(0)
    )

    @Test
    fun zeroSocSampleWithCellsGetsEstimated() {
        val sample = BmsData(voltage = 163.6f, cellVoltages = List(40) { 4.09f }, isConnected = true)
        val enriched = VoltageSocEstimator.withEstimatedSoc(sample, vehicle(), packIndex = 0)
        assertEquals(87.78f, enriched.soc, 0.05f)
        // Everything else passes through untouched.
        assertEquals(sample.voltage, enriched.voltage, 0f)
        assertEquals(sample.cellVoltages, enriched.cellVoltages)
    }

    @Test
    fun reportedSocIsNeverOverwritten() {
        // A JK sample carries a real, coulomb-counted SoC. The voltage
        // estimate must never replace it — even when cells are present and
        // would estimate a very different number.
        val sample = BmsData(soc = 37f, cellVoltages = List(16) { 4.09f }, isConnected = true)
        val out = VoltageSocEstimator.withEstimatedSoc(
            sample, vehicle(bmsType = BmsType.JK_BMS), packIndex = 0
        )
        assertSame(sample, out, "sample with a reported SoC must pass through untouched")
        assertEquals(37f, out.soc, 0.0f)
    }

    @Test
    fun jkReportingGenuineZeroSocIsNotOverwritten() {
        // THE regression this guard exists to prevent: a smart BMS reporting
        // a genuine 0 % on a flat pack, cells present (they always are on
        // those devices). A value-based `soc == 0` guard cannot distinguish
        // this from "no SoC reported" and would overwrite the true zero with
        // a reassuring voltage estimate — here ~64.7 %, because the BMS's
        // firmware empty cutoff has no reason to coincide with the estimator's
        // floor — at the exact moment the rider needs to be told to stop.
        // The identity-based guard (BmsType.reportsStateOfCharge) passes it
        // through untouched.
        val sample = BmsData(soc = 0f, cellVoltages = List(20) { 3.8825f }, isConnected = true)
        val out = VoltageSocEstimator.withEstimatedSoc(
            sample, vehicle(bmsType = BmsType.JK_BMS), packIndex = 0
        )
        assertSame(sample, out, "a genuine 0 % from a coulomb-counting BMS must survive")
        assertEquals(0f, out.soc, 0.0f)
    }

    @Test
    fun zeroSocWithoutCellsIsLeftAlone() {
        // Nothing to estimate from — a fabricated number would be worse than 0.
        val sample = BmsData(voltage = 147.2f, isConnected = true)
        val out = VoltageSocEstimator.withEstimatedSoc(sample, vehicle(), packIndex = 0)
        assertEquals(0f, out.soc, 0.0f)
        assertSame(sample, out)
    }

    @Test
    fun nullVehicleLeavesSampleUntouched() {
        // No vehicle -> no chemistry or thresholds to estimate against.
        val sample = BmsData(cellVoltages = List(40) { 4.09f }, isConnected = true)
        assertSame(sample, VoltageSocEstimator.withEstimatedSoc(sample, null, packIndex = 0))
    }

    @Test
    fun unknownPackIndexLeavesSampleUntouched() {
        // The orchestrator tolerates unknown pack indices; so must the
        // estimator — no pack means no BmsType to decide by.
        val sample = BmsData(cellVoltages = List(40) { 4.09f }, isConnected = true)
        assertSame(sample, VoltageSocEstimator.withEstimatedSoc(sample, vehicle(), packIndex = 7))
    }

    @Test
    fun estimatorHonoursVehicleConfiguredBounds() {
        val sample = BmsData(cellVoltages = List(20) { 3.55f }, isConnected = true)
        val v = vehicle(AlertConfig(cellHighV = 4.10f, cellLowV = 3.00f))
        assertEquals(50f, VoltageSocEstimator.withEstimatedSoc(sample, v, packIndex = 0).soc, 0.01f)
    }
}
