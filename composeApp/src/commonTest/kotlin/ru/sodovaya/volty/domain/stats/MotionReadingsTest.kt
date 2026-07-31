package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The unknown-vs-zero contract (`G §9`), tested where every decision it makes
 * lives — a pure object, because Compose is not unit-testable in this repo.
 *
 * Each quantity is asserted in BOTH directions on purpose: an implementation
 * that answered null for everything would satisfy "unknown reads as null" and
 * be useless, and it is the *known* half that stops a rider's real 0 % from
 * being hidden behind a dash.
 */
class MotionReadingsTest {

    /** A moving VESC: every flag true, every number distinct and non-zero. */
    private val vesc = ControllerData(
        speedKmh = 42f,
        speedSource = SpeedSource.REPORTED,
        dutyPercent = 61f,
        inputVoltageV = 78.4f,
        powerW = 4200f,
        escTempC = 51f,
        motorTempC = 66f,
        hasMotorTemp = true,
        consumedWh = 980f,
        tripKm = 58f,
        isConnected = true
    )

    /**
     * The same wheel with nothing observed: a Begode with no cell count whose
     * truePWM latch has never closed. Every number is the placeholder its
     * non-nullable field is forced to carry — which is precisely what makes
     * these cases indistinguishable without the flags.
     */
    private val begode = vesc.copy(
        dutyPercent = 0f, hasDuty = false,
        inputVoltageV = 0f, hasInputVoltage = false,
        powerW = 0f, hasPower = false,
        consumedWh = 0f, hasEnergyCounters = false,
        motorTempC = -50.1f, hasMotorTemp = false,
        escTempC = -60f
    )

    @Test fun a_measured_reading_is_returned_as_the_number_it_is() {
        assertEquals(42f, MotionReadings.speedKmh(vesc))
        assertEquals(61f, MotionReadings.dutyPercent(vesc))
        assertEquals(78.4f, MotionReadings.inputVoltageV(vesc))
        assertEquals(4200f, MotionReadings.powerW(vesc))
        assertEquals(66f, MotionReadings.motorTempC(vesc))
        assertEquals(51f, MotionReadings.escTempC(vesc))
    }

    @Test fun an_unobserved_reading_is_null_and_never_its_placeholder_zero() {
        assertNull(MotionReadings.speedKmh(vesc.copy(speedSource = SpeedSource.NONE)))
        assertNull(MotionReadings.dutyPercent(begode))
        assertNull(MotionReadings.inputVoltageV(begode))
        assertNull(MotionReadings.powerW(begode))
        assertNull(MotionReadings.motorTempC(begode))
        assertNull(MotionReadings.escTempC(begode))
    }

    /**
     * The half that makes the contract worth having: a real zero is a reading.
     * A rider coasting at 0 % duty, a stationary vehicle drawing 0 W and a pack
     * at 0 V must all still print numbers — an implementation that dashed
     * anything falsy would pass every "unknown reads as null" assertion above.
     */
    @Test fun a_genuine_zero_is_a_measurement_and_stays_a_number() {
        val coasting = vesc.copy(dutyPercent = 0f, powerW = 0f, inputVoltageV = 0f, escTempC = 0f)
        assertEquals(0f, MotionReadings.dutyPercent(coasting))
        assertEquals(0f, MotionReadings.powerW(coasting))
        assertEquals(0f, MotionReadings.inputVoltageV(coasting))
        assertEquals(0f, MotionReadings.escTempC(coasting))
    }

    @Test fun instant_consumption_needs_an_observed_power_not_merely_a_moving_wheel() {
        // The §9 defect in one assertion: RideMetrics.instantWhPerKm(0f, 42f)
        // is a perfectly well-formed 0f, so a missing voltage scale used to
        // arrive at the gauge as a confident 0.0 Wh/km.
        assertEquals(0f, RideMetrics.instantWhPerKm(begode.powerW, begode.speedKmh))
        assertNull(MotionReadings.instantWhPerKm(begode))
        assertEquals(100f, MotionReadings.instantWhPerKm(vesc), "4200 W at 42 km/h")
    }

    @Test fun instant_consumption_needs_an_observed_speed_too() {
        assertNull(MotionReadings.instantWhPerKm(vesc.copy(speedSource = SpeedSource.NONE)))
    }

    @Test fun instant_consumption_still_defers_to_the_standing_still_guard() {
        // The pre-existing RideMetrics rule survives the new flag: below
        // MIN_SPEED_KMH the division is a blow-up whatever the flags say.
        assertNull(MotionReadings.instantWhPerKm(vesc.copy(speedKmh = 0.4f)))
    }

    @Test fun session_consumption_is_absent_when_the_protocol_keeps_no_counters() {
        // §9.1 in one assertion: tripKm > 0, so RideMetrics answers a
        // well-formed 0.0 — for the whole ride, on every Begode.
        val movedButCounterless = begode.copy(tripKm = 12f)
        assertEquals(0f, RideMetrics.sessionWhPerKm(movedButCounterless.consumedWh, movedButCounterless.tripKm))
        assertNull(MotionReadings.sessionWhPerKm(movedButCounterless))
        assertEquals(980f / 58f, MotionReadings.sessionWhPerKm(vesc))
    }

    @Test fun session_consumption_still_needs_a_trip_to_divide_by() {
        assertNull(MotionReadings.sessionWhPerKm(vesc.copy(tripKm = 0f)))
    }

    @Test fun the_gauge_figure_prefers_the_live_one_and_falls_back_to_the_session() {
        assertEquals(100f, MotionReadings.whPerKm(vesc), "live wins while moving")
        val parked = vesc.copy(speedKmh = 0f)
        assertEquals(980f / 58f, MotionReadings.whPerKm(parked), "session answers when standing still")
        assertNull(MotionReadings.whPerKm(begode.copy(tripKm = 12f)), "neither is observed")
    }

    @Test fun a_zero_consumption_that_was_actually_measured_survives_the_fallback() {
        // Regen-balanced coasting: 0 W at speed is a real 0.0 Wh/km and must
        // not be swallowed by the session fallback or turned into an absence.
        val coasting = vesc.copy(powerW = 0f)
        assertEquals(0f, MotionReadings.whPerKm(coasting))
    }

    // -------------------------------------------------------------------------
    // `I` Task 8 — the session figure may be SYNTHESISED, but never disguised
    // -------------------------------------------------------------------------

    /**
     * The provenance is a **separate** signal, and the sample is left alone.
     *
     * The temptation this rejects: flipping `hasEnergyCounters` to true for a
     * synthesised figure would make the whole thing work with no new field and
     * no new parameter — and would tell every consumer of the sample, including
     * the aggregator's `all` fold and any future one, that a Begode keeps
     * counters. A derived number presented as a measurement is the exact defect
     * this contract exists to prevent.
     */
    @Test fun a_synthesised_session_figure_is_marked_and_leaves_the_counter_flag_alone() {
        val counterless = begode.copy(tripKm = 2f)
        val reading = assertNotNull(MotionReadings.sessionConsumption(counterless, synthesisedWh = 30f))

        assertEquals(15f, reading.whPerKm, "30 Wh over 2 km")
        assertTrue(reading.synthesised)
        // The half that would survive if the flag were flipped instead:
        assertFalse(counterless.hasEnergyCounters, "the sample still says the wheel counts nothing")
        assertNull(
            MotionReadings.sessionWhPerKm(counterless),
            "and the measurement-only accessor still refuses, for every consumer that has not heard of synthesis"
        )
    }

    /** A measurement always wins, and is never marked. */
    @Test fun a_measured_session_figure_beats_a_synthesised_one_and_carries_no_marker() {
        val reading = assertNotNull(MotionReadings.sessionConsumption(vesc, synthesisedWh = 1f))
        assertEquals(980f / 58f, reading.whPerKm, "the counter's figure, not 1 Wh / 58 km")
        assertFalse(reading.synthesised)
    }

    /**
     * Nothing to synthesise from is the pre-existing blank — never a zero, and
     * never a chip claiming to be an approximation of nothing.
     */
    @Test fun a_session_with_nothing_to_synthesise_from_stays_absent() {
        assertNull(MotionReadings.sessionConsumption(begode.copy(tripKm = 12f), synthesisedWh = null))
    }

    /**
     * The synthesised branch goes through the SAME divisor guard as the measured
     * one: a trip that has not started is not a distance.
     */
    @Test fun a_synthesised_figure_cannot_invent_a_division_the_measured_one_refuses() {
        assertNull(MotionReadings.sessionConsumption(begode.copy(tripKm = 0f), synthesisedWh = 30f))
        assertNull(MotionReadings.sessionConsumption(vesc.copy(tripKm = 0f), synthesisedWh = 30f))
    }

    /**
     * A synthesised **zero** is a figure. The integral of a balanced interval is
     * a real 0.0 Wh/km, and an implementation that treated a falsy Wh as
     * "nothing to offer" would swallow it — the same confident-zero-vs-absence
     * confusion, arriving from the other side.
     */
    @Test fun a_synthesised_zero_is_still_a_figure_and_is_still_marked() {
        val reading = assertNotNull(
            MotionReadings.sessionConsumption(begode.copy(tripKm = 2f), synthesisedWh = 0f)
        )
        assertEquals(0f, reading.whPerKm)
        assertTrue(reading.synthesised)
    }

    @Test fun the_disconnected_placeholder_claims_nothing_it_has_not_seen() {
        // ControllerData()'s defaults are what `activeMotion` emits with nothing
        // connected. hasDuty/hasPower/hasInputVoltage default TRUE (see their
        // KDoc: a decoder that says nothing keeps its old behaviour), so this
        // documents that the placeholder is NOT self-describing and callers
        // gate on isConnected — the same guard availabilityFor already applies.
        val placeholder = ControllerData()
        assertNull(MotionReadings.speedKmh(placeholder), "speed alone is honest by default")
        assertNull(MotionReadings.motorTempC(placeholder))
        assertNotNull(MotionReadings.dutyPercent(placeholder))
        assertNull(MotionReadings.whPerKm(placeholder), "nothing moving, nothing travelled")
    }
}
