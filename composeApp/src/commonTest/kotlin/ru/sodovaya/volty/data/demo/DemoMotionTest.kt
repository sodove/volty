package ru.sodovaya.volty.data.demo

import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.stats.MotionAggregator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/**
 * Unit tests for [DemoBmsSimulator.motionAt] — task 12's synthetic ride curve
 * for "Try demo" mode's controller. Modeled on [DemoBmsSimulatorTest]: pure,
 * tick-indexed samples, no coroutines needed to exercise the curve itself.
 */
@OptIn(ExperimentalTime::class)
class DemoMotionTest {

    private val sim = DemoBmsSimulator()

    // Tick indices (700 ms cadence) chosen to land squarely inside each phase.
    // Riding 0..100s, stopped 100..120s, charging 120..180s.
    private fun tickForSecond(sec: Double): Int = (sec * 1000.0 / DemoBmsSimulator.TICK_INTERVAL_MS).toInt()

    @Test
    fun `motion speed is always reported and controller stays connected`() {
        for (tick in 0 until tickForSecond(400.0) step 11) {
            val m = sim.motionAt(tick)
            assertEquals(SpeedSource.REPORTED, m.speedSource, "speedSource wrong at tick=$tick")
            assertTrue(m.isConnected, "demo controller should report connected at tick=$tick")
        }
    }

    @Test
    fun `dutyPercent rises as speedKmh rises during acceleration`() {
        // Both ticks land inside the first ~8s acceleration ramp of the riding
        // phase (0..100s), where speed climbs monotonically from 0.
        val early = sim.motionAt(tickForSecond(2.0))
        val later = sim.motionAt(tickForSecond(5.0))

        assertTrue(later.speedKmh > early.speedKmh, "speed should have risen: ${early.speedKmh} -> ${later.speedKmh}")
        assertTrue(later.dutyPercent > early.dutyPercent, "duty should track rising speed: ${early.dutyPercent} -> ${later.dutyPercent}")
    }

    @Test
    fun `speed and duty are zero while stopped and while charging`() {
        val stopped = sim.motionAt(tickForSecond(110.0))
        val charging = sim.motionAt(tickForSecond(150.0))

        assertEquals(0f, stopped.speedKmh)
        assertEquals(0f, stopped.dutyPercent)
        assertEquals(0f, charging.speedKmh)
        assertEquals(0f, charging.dutyPercent)
    }

    @Test
    fun `odometerKm and tripKm are monotonic non-decreasing across ticks`() {
        var prevOdometer = -1f
        var prevTrip = -1f
        for (tick in 0 until tickForSecond(400.0) step 7) {
            val m = sim.motionAt(tick)
            assertTrue(m.odometerKm >= prevOdometer, "odometer decreased at tick=$tick: $prevOdometer -> ${m.odometerKm}")
            assertTrue(m.tripKm >= prevTrip, "trip decreased at tick=$tick: $prevTrip -> ${m.tripKm}")
            prevOdometer = m.odometerKm
            prevTrip = m.tripKm
        }
    }

    @Test
    fun `demo_emits_advancing_motion`() {
        // Step the demo N ticks; assert the produced ControllerData has
        // speedSource == REPORTED, non-zero duty tracking speed, and odometer
        // that only increases. Assert MotionAggregator over the demo
        // controller is connected.
        var prevOdometer = -1f
        var sawNonZeroSpeed = false
        for (tick in 0 until tickForSecond(200.0)) {
            val m = sim.motionAt(tick)
            assertEquals(SpeedSource.REPORTED, m.speedSource)
            assertTrue(m.odometerKm >= prevOdometer)
            prevOdometer = m.odometerKm
            if (m.speedKmh > 0f) sawNonZeroSpeed = true
        }
        assertTrue(sawNonZeroSpeed, "riding phase should have produced non-zero speed at some tick")

        val motion = sim.motionAt(tickForSecond(30.0))
        val controller = Controller(index = 0, label = "Demo motor", controllerType = ControllerType.VESC, address = "demo")
        val aggregate = MotionAggregator.aggregate(listOf(ControllerState(controller, motion, isOnline = true)))
        assertTrue(aggregate.isConnected, "aggregate over the demo controller should be connected")
    }
}
