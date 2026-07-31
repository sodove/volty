package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * **`I` Task 8 — a wheel that counts no watt-hours can still be measured.**
 *
 * A Begode's frames carry no energy counters at all, so the Ride dashboard's
 * session consumption was blank *by construction* for a whole ride — while the
 * Graph screen integrated the identical quantity, correctly, the entire time.
 * [RideEnergy] is that arithmetic extracted so there is one integrator, and this
 * file is the contract it now owes both callers.
 *
 * Two things here are load-bearing beyond the arithmetic:
 *
 *  - **the sign is asserted, not assumed.** The two callers hold OPPOSITE
 *    conventions ([ControllerData.powerW] is discharge-positive, `BmsData.power`
 *    is charge-positive), so a shared integrator that quietly baked in either
 *    one would be worse than the two integrators it replaced;
 *  - **the fixtures are deliberately incoherent where the contract is about
 *    absence.** `powerW = 4200f, hasPower = false` is a pair no producer emits,
 *    which is exactly why it separates "the integrator skipped the unmeasured
 *    term" from "the integrator added it, and every producer happens to write 0
 *    there".
 */
@OptIn(ExperimentalTime::class)
class RideEnergyTest {

    private fun at(seconds: Long) = Instant.fromEpochSeconds(seconds)

    /**
     * The window's watt-hours alone — the numerator half of
     * [RideEnergy.windowedRide], which is the only way production reads it.
     */
    private fun wh(samples: List<ControllerData>, since: Instant? = null) =
        RideEnergy.windowedRide(samples, since)?.wh

    /**
     * One motion sample of a **counterless** wheel — `hasEnergyCounters = false`
     * is the fixture's whole reason for existing, and leaving it at
     * `ControllerData`'s default `true` would send every assertion below down
     * the MEASURED branch of [MotionReadings.sessionConsumption] and read the
     * wheel's permanent `consumedWh = 0f` as a real `0.0 Wh/km` — which is the
     * `G §9.1` defect itself, arriving through a test fixture.
     */
    private fun sample(
        seconds: Long,
        powerW: Float,
        hasPower: Boolean = true,
        tripKm: Float = 0f
    ) = ControllerData(
        powerW = powerW,
        hasPower = hasPower,
        hasEnergyCounters = false,
        tripKm = tripKm,
        isConnected = true,
        timestamp = at(seconds)
    )

    // -----------------------------------------------------------------------------
    // The brief's synthetic ride
    // -----------------------------------------------------------------------------

    /**
     * 600 W held for 60 s over 1 km: 600 W × (1/60) h = **10 Wh**, and 10 Wh/km —
     * and the figure is marked as a reconstruction, not as a reading.
     */
    @Test fun a_constant_600_W_minute_over_a_kilometre_is_ten_watt_hours_and_says_it_was_derived() {
        val ride = listOf(sample(0, 600f, tripKm = 0f), sample(60, 600f, tripKm = 1f))

        val ridden = assertNotNull(RideEnergy.windowedRide(ride, since = null))
        assertEquals(10f, ridden.wh, 0.0001f, "600 W for a minute")
        assertEquals(1f, ridden.km, 0.0001f, "and the kilometre that same minute covered")

        val reading = assertNotNull(
            MotionReadings.sessionConsumption(
                ride.last(), RideEnergy.synthesisedWhPerKm(ride, since = null)
            )
        )
        assertEquals(10f, reading.whPerKm, 0.0001f, "10 Wh over 1 km")
        assertTrue(reading.synthesised, "integrated from power, not read off a counter")
    }

    /**
     * The same ride, on hardware that keeps counters: the counter wins and the
     * figure is NOT marked.
     *
     * The pair matters more than either half. A synthesis that quietly replaced a
     * VESC's own coulomb counting would swap a number the firmware stands behind
     * for one reconstructed from BLE arrival gaps, and would still have satisfied
     * every assertion above.
     */
    @Test fun a_protocol_that_keeps_counters_is_believed_over_the_reconstruction() {
        val counting = sample(60, 600f, tripKm = 1f).copy(consumedWh = 42f, hasEnergyCounters = true)

        val reading = assertNotNull(MotionReadings.sessionConsumption(counting, synthesisedWhPerKm = 10f))
        assertEquals(42f, reading.whPerKm, 0.0001f, "the counter's 42 Wh, not the integral's 10")
        assertTrue(!reading.synthesised)
    }

    // -----------------------------------------------------------------------------
    // Sign — the one thing a shared integrator must not decide for its callers
    // -----------------------------------------------------------------------------

    /**
     * **[RideEnergy.integrateHours] preserves the samples' own sign.**
     *
     * The same physical minute, expressed in both of the app's conventions,
     * integrates to numbers of opposite sign. An integrator that negated (to suit
     * the Graph screen) or took an absolute value (to make consumption "always
     * positive") would fail exactly one of these two.
     */
    @Test fun the_integrator_negates_nothing_so_each_convention_keeps_its_own_sign() {
        // ControllerData: discharge-POSITIVE (VESC's `v_in * current_in`).
        val controller = listOf(sample(0, 600f), sample(60, 600f))
        assertEquals(
            10.0,
            assertNotNull(RideEnergy.integrateHours(controller, { it.timestamp }) { it.powerW }),
            0.0001
        )

        // BmsData: charge-positive (`+ = charging`), so the SAME discharge is -600 W.
        val battery = listOf(
            BmsData(power = -600f, timestamp = at(0)),
            BmsData(power = -600f, timestamp = at(60))
        )
        assertEquals(
            -10.0,
            assertNotNull(RideEnergy.integrateHours(battery, { it.timestamp }) { it.power }),
            0.0001,
            "the Graph screen's own negation is what flips this, and it stays at that call site"
        )
    }

    /**
     * [RideEnergy.sessionWh] is the caller that must NOT negate, and a ride
     * therefore reports positive watt-hours drawn.
     */
    @Test fun a_ride_draws_positive_watt_hours_and_regen_subtracts_from_them() {
        val drawing = listOf(sample(0, 600f), sample(60, 600f))
        assertTrue(assertNotNull(wh(drawing)) > 0f)

        // A descent that puts more back than it took: the NET figure is negative,
        // and that is the honest answer rather than a magnitude.
        val descending = listOf(sample(0, -600f), sample(60, -600f))
        assertEquals(-10f, assertNotNull(wh(descending)), 0.0001f)
    }

    // -----------------------------------------------------------------------------
    // The arithmetic
    // -----------------------------------------------------------------------------

    /**
     * Trapezoidal, not a rectangle on either end. A ramp from 0 W to 1000 W over
     * an hour is 500 Wh; a left-hand rectangle reads 0 and a right-hand one 1000.
     */
    @Test fun the_rule_is_trapezoidal_so_a_ramp_is_its_mean_and_not_either_end() {
        val ramp = listOf(sample(0, 0f), sample(3600, 1000f))
        assertEquals(500f, assertNotNull(wh(ramp)), 0.0001f)
    }

    /**
     * dt comes from each sample's own timestamp, so uneven BLE arrival gaps are
     * weighted by the time they actually spanned rather than by sample count.
     */
    @Test fun uneven_arrival_gaps_are_weighted_by_time_and_not_by_sample_count() {
        // A 10-minute burst followed by 110 minutes of cruising:
        //   0 s .. 600 s   (1/6 h)     mean (900 + 300)/2 = 600 W  ->  100 Wh
        //   600 s .. 7200 s (11/6 h)   mean 300 W                  ->  550 Wh
        // The two implementations this rejects: averaging the samples and
        // multiplying by the span answers 1000 Wh, and assuming a uniform dt of
        // span/(n-1) answers 900 Wh.
        val uneven = listOf(sample(0, 900f), sample(600, 300f), sample(7200, 300f))
        assertEquals(650f, assertNotNull(wh(uneven)), 0.01f)
    }

    /** Two samples at the same instant span no time and add no energy. */
    @Test fun samples_sharing_an_instant_span_no_time_and_add_nothing() {
        assertEquals(0f, assertNotNull(wh(listOf(sample(0, 900f), sample(0, 900f)))), 0f)
    }

    // -----------------------------------------------------------------------------
    // Absence — the half the incoherent fixture exists for
    // -----------------------------------------------------------------------------

    /**
     * **A sample whose power nobody measured is DROPPED, never integrated as 0 W.**
     *
     * The middle sample carries `powerW = 4200f` behind `hasPower = false` — a
     * pair no producer emits, and the only fixture that can tell the two failures
     * apart. If the integrator read the flag but summed the value, this reads
     * ~2.5x too high; if it read the value as 0 W because it skipped the flag, it
     * reads low. Only "dropped" gives the same answer as the two-sample ride
     * without it.
     *
     * This is live rather than hypothetical since `I` Task 7: a VESC node
     * answering with `v_in = 0` genuinely clears `hasPower` today.
     */
    @Test fun an_unmeasured_power_is_dropped_and_is_never_read_as_zero_watts() {
        val withHole = listOf(
            sample(0, 600f),
            sample(30, 4200f, hasPower = false),
            sample(60, 600f)
        )
        val withoutIt = listOf(sample(0, 600f), sample(60, 600f))

        assertEquals(
            assertNotNull(wh(withoutIt)),
            assertNotNull(wh(withHole)),
            0.0001f,
            "the hole is bridged between the measured neighbours"
        )
        // Both wrong answers, named, so a regression cannot be mistaken for noise:
        // integrating the placeholder gives 40 Wh, reading it as 0 W gives 5 Wh.
        assertEquals(10f, assertNotNull(wh(withHole)), 0.0001f)
    }

    /**
     * The measured half of the same rule: a genuine 0 W IS a reading and must be
     * integrated. An implementation that dropped every falsy power would satisfy
     * the test above and halve every coasting stretch of a real ride.
     */
    @Test fun a_measured_zero_watts_is_a_reading_and_still_carries_its_interval() {
        val coast = listOf(sample(0, 1000f), sample(3600, 0f), sample(7200, 0f))
        assertEquals(
            500f,
            assertNotNull(wh(coast)),
            0.0001f,
            "the second hour is a measured 0 W, so it adds nothing but is not skipped"
        )
    }

    /**
     * Fewer than two measured samples is **null**, not `0`.
     *
     * The distinction the whole part is about, one layer down: `0 Wh` is what a
     * balanced interval integrates to, and "there was nothing to integrate" must
     * not reach a gauge wearing that number.
     */
    @Test fun nothing_to_integrate_is_an_absence_and_not_a_zero() {
        assertNull(wh(emptyList()))
        assertNull(wh(listOf(sample(0, 600f))))
        assertNull(
            wh(
                listOf(sample(0, 600f), sample(60, 4200f, hasPower = false)),
                since = null
            ),
            "one measured sample beside one unmeasured one is still one measured sample"
        )
        // …and a real integral that happens to come out at zero is NOT an absence.
        assertEquals(0f, assertNotNull(wh(listOf(sample(0, 0f), sample(60, 0f)))), 0f)
    }

    /**
     * A consumption figure the integrator refused to produce leaves the readout
     * exactly as blank as it was before this task — never a synthesised zero.
     */
    @Test fun a_refused_integral_leaves_the_session_reading_absent() {
        val counterless = sample(60, 600f, tripKm = 12f)
        assertNull(RideEnergy.synthesisedWhPerKm(listOf(counterless), since = null))
        assertNull(MotionReadings.sessionConsumption(counterless, synthesisedWhPerKm = null))
    }

    // -----------------------------------------------------------------------------
    // The DIVISOR — windowed, like the numerator it is paired with
    // -----------------------------------------------------------------------------

    /**
     * **The distance is a delta across the retained window, not the session
     * total.**
     *
     * The buffer evicts (on age, and on a 60 000-sample hard cap that a Begode's
     * 17 Hz notification rate reaches in a couple of hours) while `tripKm` keeps
     * counting the whole ride. Here the vehicle has already travelled 100 km when
     * the retained window opens, and covers 1 km inside it: pairing the window's
     * 10 Wh with the session's 101 km reads **0.099 Wh/km**, off by a factor of a
     * hundred, with nothing but the tilde to say so.
     */
    @Test fun the_distance_is_the_windows_own_and_not_the_rides_running_total() {
        val afterAHundredKm = listOf(
            sample(0, 600f, tripKm = 100f),
            sample(60, 600f, tripKm = 101f)
        )
        val ridden = assertNotNull(RideEnergy.windowedRide(afterAHundredKm, since = null))
        assertEquals(10f, ridden.wh, 0.0001f)
        assertEquals(1f, ridden.km, 0.0001f, "101 - 100, not 101")
        assertEquals(
            10f,
            assertNotNull(RideEnergy.synthesisedWhPerKm(afterAHundredKm, since = null)),
            0.0001f,
            "a session divisor would answer 0.099 here and drift further every kilometre"
        )
    }

    /**
     * **Eviction does not change the answer on a steady ride** — which is the
     * property that makes the windowed average worth having rather than merely
     * cheaper. The same 10 Wh/km whether the buffer holds the whole hour or only
     * its last minute.
     */
    @Test fun dropping_the_oldest_samples_leaves_a_steady_rides_figure_where_it_was() {
        val whole = (0..60).map { sample(it * 60L, 600f, tripKm = it.toFloat()) }
        val evicted = whole.takeLast(2)

        assertEquals(
            assertNotNull(RideEnergy.synthesisedWhPerKm(whole, since = null)),
            assertNotNull(RideEnergy.synthesisedWhPerKm(evicted, since = null)),
            0.0001f
        )
        assertEquals(10f, assertNotNull(RideEnergy.synthesisedWhPerKm(whole, since = null)), 0.0001f)
    }

    /**
     * A window the vehicle stood still through is not a distance, so there is no
     * figure — the same refusal [RideMetrics.sessionWhPerKm] already makes for
     * the measured branch, reached through the same call.
     */
    @Test fun a_window_that_covered_no_ground_yields_no_consumption() {
        val parked = listOf(sample(0, 600f, tripKm = 12f), sample(60, 600f, tripKm = 12f))
        assertEquals(0f, assertNotNull(RideEnergy.windowedRide(parked, since = null)).km, 0f)
        assertNull(
            RideEnergy.synthesisedWhPerKm(parked, since = null),
            "a wheel balancing in place draws watts over no kilometres"
        )
    }

    /**
     * **The divisor spans the same samples the numerator did.**
     *
     * The first two samples have no measured power, so the integral starts at the
     * third — and the distance must start there too. Counting the 5 km covered
     * before the voltage scale existed would charge them against energy nobody
     * measured: 10 Wh over 6 km instead of over 1.
     */
    @Test fun the_distance_starts_where_the_measured_energy_starts() {
        val scaleArrivesLate = listOf(
            sample(0, 4200f, hasPower = false, tripKm = 0f),
            sample(30, 4200f, hasPower = false, tripKm = 5f),
            sample(60, 600f, tripKm = 5f),
            sample(120, 600f, tripKm = 6f)
        )
        val ridden = assertNotNull(RideEnergy.windowedRide(scaleArrivesLate, since = null))
        assertEquals(10f, ridden.wh, 0.0001f, "the measured minute only")
        assertEquals(1f, ridden.km, 0.0001f, "6 - 5, not 6 - 0")
        assertEquals(
            10f,
            assertNotNull(RideEnergy.synthesisedWhPerKm(scaleArrivesLate, since = null)),
            0.0001f
        )
    }

    /** `since` bounds the distance too, not just the energy. */
    @Test fun the_session_boundary_bounds_the_distance_as_well_as_the_energy() {
        val acrossAReconnect = listOf(
            sample(0, 3600f, tripKm = 40f),   // the previous leg, still retained
            sample(3600, 3600f, tripKm = 80f),
            sample(3660, 600f, tripKm = 0f),  // this connection: tripKm restarted
            sample(3720, 600f, tripKm = 1f)
        )
        val ridden = assertNotNull(RideEnergy.windowedRide(acrossAReconnect, since = at(3660)))
        assertEquals(1f, ridden.km, 0.0001f, "not 1 - 40, which is a distance travelled backwards")
        assertEquals(
            10f,
            assertNotNull(RideEnergy.synthesisedWhPerKm(acrossAReconnect, since = at(3660))),
            0.0001f
        )
    }

    // -----------------------------------------------------------------------------
    // The session boundary
    // -----------------------------------------------------------------------------

    /**
     * **[RideEnergy.sessionWh] integrates only from `since`.**
     *
     * The motion ring buffer deliberately survives a reconnect to the same
     * address so the graph keeps its history — but `tripKm`, the divisor waiting
     * downstream, is a SESSION delta that restarts with the protocol. Without the
     * bound, the previous leg's watt-hours would be charged against the new leg's
     * kilometres.
     */
    @Test fun energy_from_before_this_connection_is_not_charged_against_its_distance() {
        val acrossAReconnect = listOf(
            sample(0, 3600f),      // the previous leg: 1 h at 3600 W = 3600 Wh
            sample(3600, 3600f),
            sample(3660, 600f),    // this connection starts here
            sample(3720, 600f)
        )
        assertEquals(
            10f,
            assertNotNull(wh(acrossAReconnect, at(3660))),
            0.0001f,
            "only the minute at 600 W"
        )
        assertTrue(
            assertNotNull(wh(acrossAReconnect)) > 3000f,
            "…and without a boundary the whole retained buffer is integrated, which is why one is passed"
        )
    }

    /** The boundary is inclusive: the session's own first sample is in it. */
    @Test fun the_first_sample_of_the_session_is_inside_the_session() {
        val ride = listOf(sample(3660, 600f), sample(3720, 600f))
        assertEquals(10f, assertNotNull(wh(ride, at(3660))), 0.0001f)
    }
}
