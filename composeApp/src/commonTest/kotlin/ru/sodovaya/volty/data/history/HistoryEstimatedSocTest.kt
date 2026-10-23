package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class HistoryEstimatedSocTest {
    private fun vehicle(type: BmsType?, cellCount: Int? = null) = Vehicle(id = "v", name = "V", iconKey = "unicycle",
        packs = listOfNotNull(type?.let { Pack(index = 0, label = "A", bmsType = it, bmsAddress = "AA", cellCount = cellCount) }),
        // Without a pack, the rider's T4 shape: the wheel's own controller provides the battery.
        controllers = if (type != null) emptyList() else listOf(Controller(0, "T4", ControllerType.BEGODE, "AA", providesDerivedBattery = true)),
        topology = PackTopology.PARALLEL, chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
    private fun branch(voltage: Double?, soc: TelemetryReading = TelemetryReading(TelemetryMetric.SOC, null, TelemetryUnknown.UNAVAILABLE)) =
        TelemetryObservation(TelemetryOrigin.BATTERY, 0, listOf(
            voltage?.let { TelemetryReading.measured(TelemetryMetric.VOLTAGE, it) }
                ?: TelemetryReading(TelemetryMetric.VOLTAGE, null, TelemetryUnknown.PARTIAL_SOURCE), soc))
    private fun TelemetryObservation.soc() = readings.single { it.metric == TelemetryMetric.SOC }

    @Test fun aWheelWithoutAFuelGaugeRecordsTheDashboardsEstimate() {
        // 90.8 V over a 24S count learned from the wheel's own frames: the same number the dashboard shows.
        val live = ru.sodovaya.volty.domain.stats.VoltageSocEstimator.withEstimatedSoc(
            BmsData(voltage = 90.8f, socKnown = false), vehicle(BmsType.BEGODE, 24), 0)
        val recorded = withHistoryEstimatedSoc(branch(90.8), vehicle(null), BmsType.BEGODE) { 24 }.soc()
        assertEquals(live.soc.toDouble(), assertNotNull(recorded.value), 1e-4)
        // A typed count wins over a learned one, as on the dashboard.
        assertEquals(recorded.value, withHistoryEstimatedSoc(branch(90.8), vehicle(BmsType.BEGODE, 24), BmsType.BEGODE) { 30 }.soc().value)
    }

    @Test fun aReportedChargeIsNeverReplacedEvenAtZero() {
        // Deliberately incoherent: a JK reporting 0 % at a voltage the estimate would call half full.
        val zero = TelemetryReading.measured(TelemetryMetric.SOC, 0.0)
        assertEquals(0.0, withHistoryEstimatedSoc(branch(90.8, zero), vehicle(BmsType.JK_BMS, 24), BmsType.JK_BMS) { 24 }.soc().value)
        // A producer that already put a charge on a gauge-less type (Veteran's own voltage curve) keeps it.
        val own = TelemetryReading.measured(TelemetryMetric.SOC, 77.0)
        assertEquals(77.0, withHistoryEstimatedSoc(branch(90.8, own), vehicle(BmsType.BEGODE, 24), BmsType.BEGODE) { 24 }.soc().value)
        // An unknown charge from a type that claims its own gauge stays unknown rather than guessed.
        assertNull(withHistoryEstimatedSoc(branch(90.8), vehicle(BmsType.JK_BMS, 24), BmsType.JK_BMS) { 24 }.soc().value)
    }

    @Test fun nothingHonestToDivideLeavesTheChargeUnknown() {
        assertNull(withHistoryEstimatedSoc(branch(90.8), vehicle(null), BmsType.BEGODE) { null }.soc().value, "count unknown")
        assertNull(withHistoryEstimatedSoc(branch(null), vehicle(null), BmsType.BEGODE) { 24 }.soc().value, "voltage not certified")
        assertNull(withHistoryEstimatedSoc(branch(90.8), null, BmsType.BEGODE) { 24 }.soc().value, "no vehicle, no chemistry")
    }
}
