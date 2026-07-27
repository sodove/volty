package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test fun faults_labelled_only_when_more_than_one_online() {
        val a = ControllerData(faults = listOf("OVERTEMP"), isConnected = true)
        val one = MotionAggregator.aggregate(listOf(state(0, a)))
        assertEquals(listOf("OVERTEMP"), one.faults)
        val two = MotionAggregator.aggregate(listOf(state(0, a),
            state(1, ControllerData(faults = listOf("HALL"), isConnected = true))))
        assertEquals(listOf("c0: OVERTEMP", "c1: HALL"), two.faults)
    }
}
