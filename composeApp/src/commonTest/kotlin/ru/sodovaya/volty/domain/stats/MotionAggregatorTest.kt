package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.alert.valueFor
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionAggregatorTest {
    private fun ctrl(i: Int) = Controller(i, "c$i", ControllerType.VESC, "A$i")
    private fun state(i: Int, d: ControllerData, online: Boolean = true) =
        ControllerState(ctrl(i), d, isOnline = online)

    @Test fun single_online_controller_is_identity() {
        val d = ControllerData(speedKmh = 30f, speedSource = SpeedSource.REPORTED,
            dutyPercent = 40f, batteryCurrentA = 10f, powerW = 700f, isConnected = true)
        val agg = MotionAggregator.aggregate(listOf(state(0, d)))
        assertEquals(30f, agg.speedKmh); assertEquals(40f, agg.dutyPercent)
        assertEquals(10f, agg.batteryCurrentA); assertEquals(700f, agg.powerW)
    }

    @Test fun two_controllers_sum_current_power_max_speed_duty_temp() {
        val a = ControllerData(speedKmh = 30f, dutyPercent = 50f, batteryCurrentA = 10f,
            motorCurrentA = 20f, powerW = 700f, escTempC = 40f, odometerKm = 100f, consumedWh = 500f,
            speedSource = SpeedSource.REPORTED, isConnected = true)
        val b = ControllerData(speedKmh = 29f, dutyPercent = 55f, batteryCurrentA = 12f,
            motorCurrentA = 22f, powerW = 800f, escTempC = 45f, odometerKm = 100f, consumedWh = 480f,
            speedSource = SpeedSource.REPORTED, isConnected = true)
        val agg = MotionAggregator.aggregate(listOf(state(0, a), state(1, b)))
        assertEquals(30f, agg.speedKmh)          // max
        assertEquals(55f, agg.dutyPercent)       // max
        assertEquals(22f, agg.batteryCurrentA)   // sum 10+12
        assertEquals(42f, agg.motorCurrentA)     // sum 20+22
        assertEquals(1500f, agg.powerW)          // sum
        assertEquals(45f, agg.escTempC)          // max
        assertEquals(100f, agg.odometerKm)       // MAX, not sum
        assertEquals(980f, agg.consumedWh)       // sum
    }

    @Test fun offline_controllers_excluded_and_partial_flagged() {
        val on = ControllerData(speedKmh = 20f, speedSource = SpeedSource.REPORTED, isConnected = true)
        val off = ControllerData(speedKmh = 99f, speedSource = SpeedSource.REPORTED)
        val res = MotionAggregator.build(listOf(state(0, on), state(1, off, online = false)))
        assertEquals(20f, res.aggregate.speedKmh)
        assertTrue(res.partial)
        assertTrue(res.aggregate.isConnected)
    }

    @Test fun all_offline_is_disconnected() {
        val res = MotionAggregator.build(listOf(state(0, ControllerData(), online = false)))
        assertFalse(res.aggregate.isConnected)
    }

    @Test fun speedSource_prefers_reported_then_derived_then_none() {
        val reported = ControllerData(speedSource = SpeedSource.REPORTED, isConnected = true)
        val derived = ControllerData(speedSource = SpeedSource.DERIVED, isConnected = true)
        assertEquals(SpeedSource.REPORTED,
            MotionAggregator.aggregate(listOf(state(0, derived), state(1, reported))).speedSource)
        assertEquals(SpeedSource.DERIVED,
            MotionAggregator.aggregate(listOf(state(0, derived))).speedSource)
    }

    @Test fun hasDuty_folds_with_any_so_one_measuring_controller_carries_the_vehicle() {
        // The fold that keeps a MIXED vehicle's duty alarm alive. `dutyPercent`
        // is folded with maxOf, so a VESC beside a Begode whose truePWM latch
        // is still open contributes the only real reading — and the flag has to
        // travel with it. Folding with `all` (or leaving the flag unfolded and
        // inheriting the default) would lose duty availability outright, which
        // is a worse bug than the one the flag exists for.
        val measuring = ControllerData(dutyPercent = 62f, isConnected = true)
        val notMeasuring = ControllerData(dutyPercent = 0f, hasDuty = false, isConnected = true)

        val mixed = MotionAggregator.aggregate(listOf(state(0, measuring), state(1, notMeasuring)))
        assertTrue(mixed.hasDuty, "one controller that measures duty is enough")
        assertEquals(62f, mixed.dutyPercent, "…and it is that controller's reading the fold carries")

        // Order must not matter — `any` over the whole list, not the first.
        assertTrue(MotionAggregator.aggregate(listOf(state(0, notMeasuring), state(1, measuring))).hasDuty)

        // The negative: with nobody measuring, the vehicle measures nothing.
        assertFalse(
            MotionAggregator.aggregate(listOf(state(0, notMeasuring), state(1, notMeasuring))).hasDuty,
            "an aggregate that claims duty nobody measured re-arms the dead alarm"
        )
        // Offline controllers are not evidence either way.
        assertFalse(
            MotionAggregator.aggregate(
                listOf(state(0, notMeasuring), state(1, measuring, online = false))
            ).hasDuty,
            "an offline controller's duty is not an observation"
        )
        // And the default carries through untouched for every other decoder.
        assertTrue(MotionAggregator.aggregate(listOf(state(0, measuring))).hasDuty)
    }

    // ---------------------------------------------------------------------------------
    // G §9.3 — the fold learns the unknown-vs-zero contract. The rule these four tests
    // pin, stated once: **a known-flag folds the way its field's arithmetic does.**
    // `maxOf`/`average` fields take `any` (one real reading is the vehicle's); a SUM
    // takes `all` (a total with an unobserved term is an understatement, not a
    // measurement).
    // ---------------------------------------------------------------------------------

    @Test fun an_unknown_voltage_is_skipped_by_the_average_not_folded_into_it() {
        // The exact shape G §9.3 reports: a three-controller scooter whose head-unit
        // row answers a 0 V rail. Averaging all three reported TWO THIRDS of the pack.
        val ubox = ControllerData(inputVoltageV = 78f, isConnected = true)
        val headUnit = ControllerData(inputVoltageV = 0f, hasInputVoltage = false, isConnected = true)

        val agg = MotionAggregator.aggregate(
            listOf(state(0, ubox), state(1, ubox), state(2, headUnit))
        )
        assertEquals(78f, agg.inputVoltageV, "the phantom row must not be averaged in")
        assertTrue(agg.hasInputVoltage, "two controllers measured the rail")

        // Order must not matter, and the negative must be reachable.
        assertEquals(
            78f,
            MotionAggregator.aggregate(listOf(state(0, headUnit), state(1, ubox))).inputVoltageV
        )
        val nobodyMeasures = MotionAggregator.aggregate(listOf(state(0, headUnit), state(1, headUnit)))
        assertFalse(nobodyMeasures.hasInputVoltage, "no measurement anywhere is not a measurement")

        // Two REAL rails still average, so the skip is a filter on the flag and not on
        // the value: a fold that took `maxOf` or `first` would pass everything above.
        val other = ControllerData(inputVoltageV = 80f, isConnected = true)
        assertEquals(
            79f,
            MotionAggregator.aggregate(listOf(state(0, ubox), state(1, other))).inputVoltageV,
            "measured rails are still averaged with each other"
        )
    }

    @Test fun a_summed_field_needs_every_term_so_its_flag_folds_with_all() {
        val measuring = ControllerData(powerW = 4000f, consumedWh = 500f, isConnected = true)
        // The placeholders are deliberately NON-zero, and deliberately incoherent
        // with their own flags. Every producer that sets these flags false also
        // writes 0, so a fixture that copied that invariant could not tell "the fold
        // flags an unmeasured term" from "the fold DROPS an unmeasured term" — and
        // dropping it is the plausible wrong fix, because it makes the total look
        // tidy. The flag is what changes; the arithmetic must not.
        val notMeasuring = ControllerData(
            powerW = 1500f, hasPower = false,
            consumedWh = 250f, hasEnergyCounters = false,
            isConnected = true
        )

        val mixed = MotionAggregator.aggregate(listOf(state(0, measuring), state(1, notMeasuring)))
        assertFalse(mixed.hasPower, "a sum missing a term is not the vehicle's power")
        assertFalse(mixed.hasEnergyCounters, "…and the same for the energy counters")
        assertEquals(5500f, mixed.powerW, "the sum itself is unchanged — only the claim about it")
        assertEquals(750f, mixed.consumedWh, "…and the energy sum, likewise")

        // Order-independent, and the positive is reachable: with everyone measuring the
        // vehicle measures. A fold hardcoded to `false` would pass the assertions above.
        assertFalse(
            MotionAggregator.aggregate(listOf(state(0, notMeasuring), state(1, measuring))).hasPower
        )
        val allMeasuring = MotionAggregator.aggregate(listOf(state(0, measuring), state(1, measuring)))
        assertTrue(allMeasuring.hasPower)
        assertTrue(allMeasuring.hasEnergyCounters)
        assertEquals(8000f, allMeasuring.powerW)

        // Offline controllers are not evidence either way — an offline non-measuring
        // row must not cost an all-VESC vehicle its power.
        assertTrue(
            MotionAggregator.aggregate(
                listOf(state(0, measuring), state(1, notMeasuring, online = false))
            ).hasPower,
            "an offline controller's missing power is not the vehicle's"
        )
    }

    @Test fun the_two_voltage_derived_flags_fold_differently_on_purpose() {
        // Why hasInputVoltage and hasPower are separate fields even though every
        // decoder sets them together: on the SAME mixed vehicle the honest answers
        // disagree. Collapsing them into one flag makes the aggregate lie about one.
        val real = ControllerData(inputVoltageV = 78f, powerW = 4000f, isConnected = true)
        val phantom = ControllerData(
            inputVoltageV = 0f, hasInputVoltage = false,
            powerW = 0f, hasPower = false,
            isConnected = true
        )
        val agg = MotionAggregator.aggregate(listOf(state(0, real), state(1, phantom)))
        assertTrue(agg.hasInputVoltage, "the rail was measured — the average is over the measurers")
        assertFalse(agg.hasPower, "the total was not — a term is missing from the sum")
    }

    @Test fun a_lone_controller_folds_to_itself_even_when_its_flags_and_numbers_disagree() {
        // The one case the voltage filter could have broken: with NOBODY measuring, a
        // filtered average has an empty list. Returning 0 there would break the
        // single-controller identity that KableBmsRepositoryBegodeFunnelTest proves,
        // on a fixture whose flag and value are deliberately incoherent.
        val incoherent = ControllerData(inputVoltageV = 147.2f, hasInputVoltage = false, isConnected = true)
        val agg = MotionAggregator.aggregate(listOf(state(0, incoherent)))
        assertEquals(147.2f, agg.inputVoltageV, "with nothing to prefer, the fold keeps what it has")
        assertFalse(agg.hasInputVoltage)
    }

    @Test fun batteryLevelFraction_is_folded_instead_of_being_silently_dropped() {
        // A-foundation's "the aggregator silently drops batteryLevelFraction": the
        // field was never copied, so activeMotion published null whatever the
        // controller reported.
        val seeded = ControllerData(batteryLevelFraction = 0.84f, isConnected = true)
        val unseeded = ControllerData(batteryLevelFraction = null, isConnected = true)

        assertEquals(0.84f, MotionAggregator.aggregate(listOf(state(0, seeded))).batteryLevelFraction)
        assertNull(MotionAggregator.aggregate(listOf(state(0, unseeded))).batteryLevelFraction)

        // Averaged over the controllers that have one — the same fold, and the same
        // reasoning, as inputVoltageV: one pack, so any controller's level is the
        // vehicle's, and a null must not be counted as a flat battery.
        val other = ControllerData(batteryLevelFraction = 0.80f, isConnected = true)
        assertEquals(
            0.82f,
            MotionAggregator.aggregate(listOf(state(0, seeded), state(1, other))).batteryLevelFraction
        )
        assertEquals(
            0.84f,
            MotionAggregator.aggregate(listOf(state(0, seeded), state(1, unseeded))).batteryLevelFraction,
            "an absent level is not a 0 % one"
        )
    }

    /**
     * **The gateway shape, folded here rather than reasoned about in a comment.**
     *
     * `COMM_GET_VALUES_SETUP` reports the whole SETUP, not one unit: VESC has
     * already summed across every CAN node and divided the tachometer by the
     * number of VESCs before answering. Since `I` Task 4 that frame is asked of
     * **every** controller instead of one nominated one, so a two-uBox scooter
     * hands this fold the SAME vehicle odometer, the SAME trip and the SAME
     * pack level twice.
     *
     * `maxOf` and `average` are what make that harmless. Turn any of the three
     * into a sum — the instinct four other fields in this object correctly
     * follow — and the dashboard reads twice the distance the vehicle has ever
     * travelled and a 168 % battery. This is the test that says so; the comment
     * beside each fold points here.
     */
    @Test fun one_vehicle_odometer_reported_by_two_controllers_is_not_doubled() {
        // What a gateway publishes: identical setup scalars on both units,
        // different per-unit numbers underneath them.
        val setupScalars = ControllerData(
            speedKmh = 42f, speedSource = SpeedSource.REPORTED,
            odometerKm = 812f, tripKm = 12.5f, batteryLevelFraction = 0.84f,
            isConnected = true
        )
        val front = setupScalars.copy(dutyPercent = 50f, batteryCurrentA = 30f)
        val rear = setupScalars.copy(dutyPercent = 25f, batteryCurrentA = 28f)

        val agg = MotionAggregator.aggregate(listOf(state(0, front), state(1, rear)))

        assertEquals(812f, agg.odometerKm, "one vehicle's odometer, not 1624 km")
        assertEquals(12.5f, agg.tripKm, "one vehicle's trip, not 25 km")
        assertEquals(0.84f, agg.batteryLevelFraction, "one pack's level, not 168 %")
        assertEquals(42f, agg.speedKmh, "and one ground speed, not 84 km/h")
        // The per-unit numbers underneath still fold the way they always did —
        // this is the separation `VescGatewayProtocol` keeps the overlay for.
        assertEquals(58f, agg.batteryCurrentA, "per-unit currents are genuinely summed")
        assertEquals(50f, agg.dutyPercent, "and per-unit duty is genuinely a max")
    }

    /**
     * **The fold change must not alter what the alarm sees** (Part F owns that, and
     * this task owns the display and the fold).
     *
     * Not a tautology: the obvious wrong fix for §9.3 is to drop unknown-voltage
     * controllers from the fold entirely, which would also drop their speed, duty and
     * temperatures — the four quantities `AlarmController` compares thresholds against
     * — and silently disarm a wheel's ШИМ alarm on a mixed vehicle. This pins that the
     * phantom row changes NOTHING the alarm reads.
     */
    @Test fun a_phantom_zero_volt_row_changes_nothing_the_alarm_reads() {
        val ubox = ControllerData(
            speedKmh = 43f, speedSource = SpeedSource.REPORTED, dutyPercent = 71f,
            inputVoltageV = 78f, powerW = 4000f, escTempC = 62f, motorTempC = 88f,
            hasMotorTemp = true, isConnected = true
        )
        // Answers a 0 V rail and measures nothing else either — the head-unit row.
        val headUnit = ControllerData(
            speedSource = SpeedSource.NONE, dutyPercent = 0f, hasDuty = false,
            inputVoltageV = 0f, hasInputVoltage = false,
            powerW = 0f, hasPower = false,
            escTempC = -60f, motorTempC = -60f, hasMotorTemp = false,
            isConnected = true
        )

        val without = MotionAggregator.aggregate(listOf(state(0, ubox)))
        val with = MotionAggregator.aggregate(listOf(state(0, ubox), state(1, headUnit)))

        for (kind in MotionAlertKind.entries) {
            assertEquals(
                without.valueFor(kind), with.valueFor(kind),
                "$kind is what the alarm compares a threshold against"
            )
        }
        assertEquals(without.hasDuty, with.hasDuty, "duty availability, layer 2")
        assertEquals(without.hasMotorTemp, with.hasMotorTemp)
        assertEquals(without.hasEscTemp, with.hasEscTemp)

        // …and the thing that DID change is the one this task is about.
        assertEquals(78f, with.inputVoltageV, "the rail is the uBox's, not two thirds of it")
        assertFalse(with.hasPower, "the vehicle's power total is no longer a claim")
    }

    @Test fun faults_labelled_only_when_more_than_one_online() {
        val a = ControllerData(faults = listOf("OVERTEMP"), isConnected = true)
        val one = MotionAggregator.aggregate(listOf(state(0, a)))
        assertEquals(listOf("OVERTEMP"), one.faults)
        val two = MotionAggregator.aggregate(listOf(state(0, a),
            state(1, ControllerData(faults = listOf("HALL"), isConnected = true))))
        assertEquals(listOf("c0: OVERTEMP", "c1: HALL"), two.faults)
    }
}
