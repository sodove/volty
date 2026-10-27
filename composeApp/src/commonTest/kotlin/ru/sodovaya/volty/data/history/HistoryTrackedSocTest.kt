package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.energy.*
import ru.sodovaya.volty.domain.model.*
import ru.sodovaya.volty.domain.stats.VoltageSocEstimator
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** What a ride records as the charge when the energy model owns it (see [withHistoryEstimatedSoc], "Tracker first"). */
@OptIn(ExperimentalTime::class)
class HistoryTrackedSocTest {
    private fun vehicle(type: BmsType) = Vehicle(id = "v", name = "V", iconKey = "unicycle",
        packs = listOf(Pack(index = 0, label = "A", bmsType = type, bmsAddress = "AA", cellCount = 24)),
        controllers = emptyList(), topology = PackTopology.PARALLEL, chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
    private fun branch(voltage: Double, soc: TelemetryReading = TelemetryReading(TelemetryMetric.SOC, null, TelemetryUnknown.UNAVAILABLE)) =
        TelemetryObservation(TelemetryOrigin.BATTERY, 0, listOf(TelemetryReading.measured(TelemetryMetric.VOLTAGE, voltage), soc))
    private fun TelemetryObservation.soc() = readings.single { it.metric == TelemetryMetric.SOC }.value

    private fun record(o: TelemetryObservation, type: BmsType, view: HistorySocView?) =
        withHistoryEstimatedSoc(o, vehicle(type), type, null, { view }) { 24 }.soc()

    @Test fun aSeededTrackersChargeReplacesTheVoltageEstimate() {
        val estimate = record(branch(90.8), BmsType.BEGODE, null)
        assertNotNull(estimate)
        assertEquals(63.5, record(branch(90.8), BmsType.BEGODE, HistorySocView("v", 63.5f)))
        assertNotEquals(estimate, 63.5)
    }

    @Test fun anUnseededTrackerRecordsTodaysEstimate() {
        val today = record(branch(90.8), BmsType.BEGODE, null)
        assertEquals(today, record(branch(90.8), BmsType.BEGODE, HistorySocView("v", null)))
        // Another vehicle's view is not this one's.
        assertEquals(today, record(branch(90.8), BmsType.BEGODE, HistorySocView("other", 12f)))
    }

    @Test fun aChargeTheBmsReportsStaysMeasuredEvenWithATrackerValue() {
        // Deliberately incoherent: a view carrying a charge for a pack whose BMS reports its own.
        val own = TelemetryReading.measured(TelemetryMetric.SOC, 77.0)
        assertEquals(77.0, record(branch(90.8, own), BmsType.JK_BMS, HistorySocView("v", 10f)))
        assertEquals(77.0, record(branch(90.8, own), BmsType.BEGODE, HistorySocView("v", 10f)), "a producer's own charge is kept")
        assertNull(record(branch(90.8), BmsType.JK_BMS, HistorySocView("v", 10f)), "a reporting type's unknown stays unknown")
    }

    @Test fun onlyAModelledSnapshotPublishesAChargeToHistory() {
        fun energy(modelled: Boolean, soc: Float?) = EnergyState(socPercent = soc, usableAh = null, nominalPackV = 90f,
            remainingWh = null, capacitySource = CapacitySource.NONE, learned = null, resistanceOhm = null, modelled = modelled)
        assertEquals(55f, historySocView("v", energy(true, 55f)).percent)
        // Incoherent: a reported/mixed snapshot that nevertheless carries a socPercent is the BMS's, not the model's.
        assertNull(historySocView("v", energy(false, 55f)).percent)
        assertNull(historySocView("v", energy(true, null)).percent)
        assertNull(historySocView("v", energy(true, Float.NaN)).percent)
        assertNull(historySocView("v", null).percent)
    }

    @Test fun aSagUnderLoadIsNotASocDipInTheRecordedSeriesOnceTheTrackerIsSeeded() {
        val v = vehicle(BmsType.BEGODE)
        val (low, high) = VoltageSocEstimator.boundsFor(v.chemistry, v.alertConfig)
        val tracker = ChargeTracker(ChargeConfig(v.chemistry, low, high, 20f)) // a known capacity: the coulomb counter, not the voltage-only fallback
        // (The fallback, with no capacity, low-passes the voltage over 20 s: it smooths a sag but still follows it.)
        var t = 0L
        val untracked = ArrayList<Double>(); val tracked = ArrayList<Double>()
        fun step(volts: Float, amps: Float) {
            t += 1000
            val shown = tracker.update(ChargeInput(t, volts, 24, amps, 0f))
            val o = branch(volts.toDouble())
            untracked += withHistoryEstimatedSoc(o, v, BmsType.BEGODE, null, { null }) { 24 }.soc()!!
            tracked += withHistoryEstimatedSoc(o, v, BmsType.BEGODE, null, { HistorySocView("v", shown) }) { 24 }.soc()!!
        }
        repeat(60) { step(90.8f, -0.5f) }
        assertNotNull(tracker.counterPercent, "precondition: the tracker seeded at rest")
        val rest = tracked.last()
        repeat(4) { step(80.0f, 45f) } // a pull: the terminal voltage sags ~11 V
        repeat(20) { step(90.7f, -0.5f) }
        assertTrue(untracked.min() < rest - 10, "precondition: the voltage estimate dips on the sag (${untracked.min()} vs $rest)")
        assertTrue(tracked.min() > rest - 3, "the tracker's series does not: ${tracked.min()} vs $rest")
    }
}
