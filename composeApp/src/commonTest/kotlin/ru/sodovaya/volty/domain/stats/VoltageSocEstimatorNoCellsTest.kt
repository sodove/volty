package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.model.withCellCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A Begode without a smart BMS streams no cells at all — only a pack voltage
 * (scaled upstream from the live frame by the repository). When the profile
 * knows the cell count, the average cell voltage is `voltage / cellCount` and
 * the same linear map the cell-based estimate uses applies. Everything else —
 * no cell count, no voltage, a BMS that reports its own SoC — must pass
 * through untouched, exactly as [VoltageSocEstimatorTest] pins for cells.
 */
@OptIn(ExperimentalTime::class)
class VoltageSocEstimatorNoCellsTest {

    private fun vehicle(cellCount: Int?, bmsType: BmsType = BmsType.BEGODE) = singlePackVehicle(
        id = "test",
        name = "Test wheel",
        iconKey = "wheel",
        bmsType = bmsType,
        bmsAddress = "AA:BB",
        chemistry = Chemistry.LI_ION_NMC,
        cellCount = cellCount,
        createdAt = Instant.fromEpochMilliseconds(0)
    )

    @Test
    fun packVoltageDividedByTheKnownCellCountDrivesTheEstimate() {
        // A dumb 40S Begode at 147.3 V (the scaled ET Max live reading):
        // 147.3 / 40 = 3.6825 V/cell -> (3.6825 - 3.30) / 0.90 = 42.5 %.
        val sample = BmsData(voltage = 147.3f, isConnected = true)
        val out = VoltageSocEstimator.withEstimatedSoc(sample, vehicle(cellCount = 40), packIndex = 0)
        assertEquals(42.5f, out.soc, 0.05f)
        assertEquals(sample.voltage, out.voltage, 0f, "only soc may change")
    }

    @Test
    fun aCellLessSecondWheelBranchEstimatesFromItsOwnConfiguredCount() {
        val wheel = vehicle(cellCount = null).copy(
            packs = listOf(
                Pack(index = 0, label = "Branch 1", bmsType = BmsType.BEGODE, bmsAddress = "AA:BB"),
                Pack(index = 1, label = "Branch 2", bmsType = BmsType.BEGODE, bmsAddress = "AA:BB")
            )
        ).withCellCount(40)
        val sample = BmsData(voltage = 147.3f, socKnown = false, isConnected = true)

        val out = VoltageSocEstimator.withEstimatedSoc(sample, wheel, packIndex = 1)

        assertEquals(42.5f, out.soc, 0.05f)
        assertTrue(out.socKnown, "the voltage estimate makes this branch's SoC known")
    }

    @Test
    fun aT4SizedPackEstimatesFromItsOwnCellCount() {
        // 24S at 88.38 V -> 3.6825 V/cell, the same 42.5 % — the estimate
        // follows the profile's cell count, not any hard-coded pack size.
        val sample = BmsData(voltage = 88.38f, isConnected = true)
        val out = VoltageSocEstimator.withEstimatedSoc(sample, vehicle(cellCount = 24), packIndex = 0)
        assertEquals(42.5f, out.soc, 0.05f)
    }

    @Test
    fun noCellCountMeansNothingHonestToEstimate() {
        val sample = BmsData(voltage = 147.3f, isConnected = true)
        val out = VoltageSocEstimator.withEstimatedSoc(sample, vehicle(cellCount = null), packIndex = 0)
        assertSame(sample, out, "no cell count — the sample must pass through untouched")
        assertEquals(0f, out.soc, 0f)
    }

    @Test
    fun anUnknownVoltageMeansNothingHonestToEstimate() {
        // voltage = 0 is the "needs scaling but no cell count was known"
        // sentinel from the Begode live path: dividing it would proclaim an
        // exact 0 % on a wheel whose charge is simply unknown.
        val sample = BmsData(voltage = 0f, current = -3.5f, isConnected = true)
        val out = VoltageSocEstimator.withEstimatedSoc(sample, vehicle(cellCount = 40), packIndex = 0)
        assertSame(sample, out)
    }

    @Test
    fun theVoltageDerivedEstimateMarksTheSocAsKnown() {
        // The synthetic no-BMS pack arrives with socKnown = false. Once a
        // cell count makes an estimate possible, the SoC IS known — the
        // estimate is the fuel gauge — and SoC alerts must work off it.
        val sample = BmsData(voltage = 147.3f, socKnown = false, isConnected = true)
        val out = VoltageSocEstimator.withEstimatedSoc(sample, vehicle(cellCount = 40), packIndex = 0)
        assertEquals(42.5f, out.soc, 0.05f)
        assertTrue(out.socKnown, "an estimated SoC is a known SoC")
    }

    @Test
    fun anUnestimatableSampleKeepsItsUnknownSoc() {
        // No cell count: the pass-through is the identity, so the producer's
        // socKnown = false survives to gate the SoC alerts downstream.
        val sample = BmsData(voltage = 0f, socKnown = false, isConnected = true)
        val out = VoltageSocEstimator.withEstimatedSoc(sample, vehicle(cellCount = null), packIndex = 0)
        assertSame(sample, out)
        assertFalse(out.socKnown)
    }

    @Test
    fun theCellBasedEstimateAlsoMarksTheSocAsKnown() {
        // Belt and braces for the cell path: even if a producer ever flags a
        // cell-bearing sample unknown, estimating from those cells makes it
        // known again.
        val sample = BmsData(cellVoltages = List(40) { 4.09f }, socKnown = false, isConnected = true)
        val out = VoltageSocEstimator.withEstimatedSoc(sample, vehicle(cellCount = 40), packIndex = 0)
        assertEquals(87.78f, out.soc, 0.05f)
        assertTrue(out.socKnown)
    }

    @Test
    fun aPartialCellPageWithNonZeroVoltageAndPowerIsNotEstimatedAsAFullPack() {
        // Deliberately incoherent: a partial page carries non-zero voltage,
        // current and power-like fields, but the producer explicitly says its
        // SoC is not known. The estimator must not turn that page into a full
        // pack estimate just because some cells are present.
        val sample = BmsData(
            voltage = 147.2f,
            current = -8.8f,
            hasCurrent = true,
            power = -1295.36f,
            hasPower = true,
            soc = 0f,
            socKnown = false,
            cellVoltages = List(8) { 3.71f },
            isConnected = true
        )

        val out = VoltageSocEstimator.withEstimatedSoc(sample, vehicle(cellCount = 40), packIndex = 0)

        assertSame(sample, out, "a partial page is not a complete pack estimate")
        assertFalse(out.socKnown)
    }

    @Test
    fun presentCellsStillWinOverTheVoltageDerivedPath() {
        // Cells are the better evidence and must keep driving the estimate
        // exactly as before this fallback existed: 40 cells at 4.09 V give
        // 87.78 %, not the 76.9 % the (deliberately inconsistent) pack
        // voltage field would suggest.
        val sample = BmsData(voltage = 160.0f, cellVoltages = List(40) { 4.09f }, isConnected = true)
        val out = VoltageSocEstimator.withEstimatedSoc(sample, vehicle(cellCount = 40), packIndex = 0)
        assertEquals(87.78f, out.soc, 0.05f)
    }

    @Test
    fun aSocReportingBmsIsNeverEstimatedEvenWithoutCells()  {
        // Same identity guard as the cell path: a JK mid-boot sample with no
        // cells yet carries a real coulomb-counted SoC field and must never
        // be replaced by a voltage guess.
        val sample = BmsData(voltage = 58.4f, soc = 0f, isConnected = true)
        val out = VoltageSocEstimator.withEstimatedSoc(
            sample, vehicle(cellCount = 16, bmsType = BmsType.JK_BMS), packIndex = 0
        )
        assertSame(sample, out)
    }
}
