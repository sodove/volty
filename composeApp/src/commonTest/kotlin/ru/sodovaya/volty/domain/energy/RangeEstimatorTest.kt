package ru.sodovaya.volty.domain.energy

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.navigation.ConsumptionEvidence
import ru.sodovaya.volty.domain.navigation.ConsumptionEvidenceBuilder
import ru.sodovaya.volty.domain.navigation.ConsumptionProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class RangeEstimatorTest {
    private val t0 = Instant.fromEpochSeconds(1_700_000_000L)

    @Test
    fun noCapacityNoRange() {
        assertNull(RangeEstimator.estimate(energy(remainingWh = null), consumption(), null))
        assertNull(RangeEstimator.estimate(null, consumption(), null))
    }

    @Test
    fun notEarnedBelowOneKm() {
        assertNull(RangeEstimator.estimate(energy(), consumption(distanceKm = 0.99), null))
        assertNotNull(RangeEstimator.estimate(energy(), consumption(distanceKm = 1.0), null))
    }

    @Test
    fun notEarnedWithFewSamples() {
        assertNull(RangeEstimator.estimate(energy(), consumption(samples = 19), null))
        assertNotNull(RangeEstimator.estimate(energy(), consumption(samples = 20), null))
        assertNull(RangeEstimator.estimate(energy(), null, null))
        assertNull(RangeEstimator.estimate(energy(), consumption(whPerKm = 0.0), null))
    }

    @Test
    fun remainingOverConsumption() {
        val r = assertNotNull(RangeEstimator.estimate(energy(remainingWh = 900f), consumption(whPerKm = 20.0), null))
        assertEquals(45f, r.km, 0.001f)
        assertEquals(20.0, r.whPerKm, 1e-9)
    }

    @Test
    fun climbAfterGentleStartShowsUp() {
        assertEquals(27.5, RangeEstimator.blendedWhPerKm(15.0, 10.0, 40.0), 1e-9)
        // Below RECENT_KM of trip, or without a recent figure, only the trip average counts.
        assertEquals(15.0, RangeEstimator.blendedWhPerKm(15.0, 2.9, 40.0), 1e-9)
        assertEquals(15.0, RangeEstimator.blendedWhPerKm(15.0, 10.0, null), 1e-9)
        val r = assertNotNull(RangeEstimator.estimate(energy(remainingWh = 550f), consumption(whPerKm = 15.0, distanceKm = 10.0), 40.0))
        assertEquals(20f, r.km, 0.001f)
        assertEquals(27.5, r.whPerKm, 1e-9)
    }

    @Test
    fun liveVoltageIsNotUsed() {
        // Incoherent on purpose: the live aggregate sags to 60 V on a 100 V nominal pack. The estimator
        // takes only EnergyState, so its range cannot follow the sag.
        val nominal = energy(remainingWh = 1000f, nominalV = 100f)
        val sagged = ru.sodovaya.volty.domain.model.VehicleData(
            aggregate = ru.sodovaya.volty.domain.model.BmsData(voltage = 60f, capacity = 10f),
            energy = nominal,
        )
        val live = RangeEstimator.estimate(sagged.energy, consumption(whPerKm = 20.0), null)
        val rest = RangeEstimator.estimate(
            sagged.copy(aggregate = sagged.aggregate.copy(voltage = 100f)).energy, consumption(whPerKm = 20.0), null,
        )
        assertEquals(50f, assertNotNull(live).km, 0.001f)
        assertEquals(live, rest)
    }

    @Test
    fun recentWindowFromCounters_isTheLastRecentKmOnly() {
        // 10 km, 1 Wh/km for the first 7 km, 40 Wh/km for the last 3 km.
        val samples = List(11) { i ->
            val km = i.toFloat()
            val wh = if (km <= 7f) km else 7f + (km - 7f) * 40f
            ControllerData(tripKm = km, consumedWh = wh, hasEnergyCounters = true, timestamp = t0 + (i * 60L).seconds)
        }
        assertEquals(40.0, assertNotNull(ConsumptionEvidenceBuilder.recentWhPerKm(samples, RangeEstimator.RECENT_KM)), 1e-6)
    }

    @Test
    fun recentWindowFromPower_isTheLastRecentKmOnly() {
        // Constant speed 60 km/h (1 km per minute): 600 W for 7 km, then 2400 W for 3 km.
        val samples = List(11) { i ->
            ControllerData(
                tripKm = i.toFloat(), powerW = if (i <= 7) 600f else 2400f, hasPower = true,
                hasEnergyCounters = false, timestamp = t0 + (i * 60L).seconds,
            )
        }
        // From km 7..10: trapezoid of 600->2400 then 2400, 2400: (1500+2400+2400)/60 h per min.
        val recent = assertNotNull(ConsumptionEvidenceBuilder.recentWhPerKm(samples, 3.0))
        assertEquals((1500.0 + 2400.0 + 2400.0) / 60.0 / 3.0, recent, 1e-6)
    }

    @Test
    fun recentWindowAbsentWithoutDistanceOrMeasurement() {
        val parked = List(5) { ControllerData(powerW = 500f, hasPower = true, hasEnergyCounters = false, timestamp = t0 + (it * 10L).seconds) }
        assertNull(ConsumptionEvidenceBuilder.recentWhPerKm(parked, 3.0))
        // Incoherent: a power value with no measurement flag must not be integrated.
        val unmeasured = List(5) { ControllerData(tripKm = it.toFloat(), powerW = 4200f, hasPower = false, hasEnergyCounters = false, timestamp = t0 + (it * 10L).seconds) }
        assertNull(ConsumptionEvidenceBuilder.recentWhPerKm(unmeasured, 3.0))
        assertNull(ConsumptionEvidenceBuilder.recentWhPerKm(emptyList(), 3.0))
    }

    // ---- assess(): why a range is withheld, and the figures behind it ----

    private fun reason(e: EnergyState?, c: ConsumptionEvidence?) = RangeEstimator.assess(e, c, null).detail.unearnedReason

    @Test
    fun everyUnearnedCaseNamesItsReason() {
        assertEquals(RangeUnearnedReason.NO_CAPACITY, reason(null, consumption()))
        // Incoherent on purpose: a SoC with no capacity behind it (usableAh null, remaining null).
        assertEquals(
            RangeUnearnedReason.NO_CAPACITY,
            reason(energy(remainingWh = null).copy(usableAh = null, socPercent = 50f), consumption()),
        )
        assertEquals(
            RangeUnearnedReason.SOC_UNKNOWN,
            reason(energy(remainingWh = null).copy(usableAh = 20f, socPercent = null), consumption()),
        )
        assertEquals(RangeUnearnedReason.NO_CONSUMPTION, reason(energy(), null))
        assertEquals(RangeUnearnedReason.NO_CONSUMPTION, reason(energy(), consumption(whPerKm = -3.0)))
        assertEquals(RangeUnearnedReason.TOO_SHORT, reason(energy(), consumption(distanceKm = 0.5)))
        assertEquals(RangeUnearnedReason.TOO_FEW_SAMPLES, reason(energy(), consumption(samples = 5)))
        assertNull(reason(energy(), consumption()))
    }

    @Test
    fun theDetailCarriesTheFiguresEvenWhenTheRangeIsNotEarned() {
        val a = RangeEstimator.assess(energy(), consumption(whPerKm = 20.0, distanceKm = 0.5, samples = 30), 40.0)
        assertNull(a.estimate)
        assertEquals(20.0, a.detail.tripWhPerKm)
        assertEquals(0.5, a.detail.tripKm)
        assertEquals(40.0, a.detail.recentWhPerKm)
        assertEquals(20.0, a.detail.blendedWhPerKm, "a trip under RECENT_KM is the trip average alone")
        assertEquals(RangeUnearnedReason.TOO_SHORT, a.detail.unearnedReason)
    }

    @Test
    fun anEarnedRangeAgreesWithEstimateAndBlendsLikeIt() {
        val c = consumption(whPerKm = 20.0, distanceKm = 5.0)
        val a = RangeEstimator.assess(energy(remainingWh = 900f), c, 40.0)
        assertEquals(RangeEstimator.estimate(energy(remainingWh = 900f), c, 40.0), a.estimate)
        assertEquals(30.0, a.detail.blendedWhPerKm)
        assertNull(a.detail.unearnedReason)
    }

    @Test
    fun noConsumptionLeavesTheFiguresAbsentNotZero() {
        val d = RangeEstimator.assess(energy(), null, null).detail
        assertNull(d.tripWhPerKm); assertNull(d.tripKm); assertNull(d.blendedWhPerKm); assertNull(d.recentWhPerKm)
    }

    private fun energy(remainingWh: Float? = 900f, nominalV: Float = 72f) = EnergyState(
        socPercent = 50f, usableAh = 20f, nominalPackV = nominalV, remainingWh = remainingWh,
        capacitySource = CapacitySource.ENTERED, learned = null, resistanceOhm = null, modelled = true,
    )

    private fun consumption(whPerKm: Double = 20.0, distanceKm: Double = 5.0, samples: Int = 30) = ConsumptionEvidence(
        whPerKm = whPerKm, distanceKm = distanceKm, durationMillis = 60_000L,
        measuredSampleCount = samples, provenance = ConsumptionProvenance.CONTROLLER_COUNTERS,
    )
}
