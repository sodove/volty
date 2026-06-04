package ru.sodovaya.volty.data.demo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/**
 * Unit tests for the pure [DemoBmsSimulator.sampleAt] generator. We assert the
 * invariants the rest of the pipeline relies on (SOC range, cell count, voltage
 * = sum of cells) and the phase-dependent current sign that flips the dashboard
 * hero between discharge and charge.
 */
@OptIn(ExperimentalTime::class)
class DemoBmsSimulatorTest {

    private val sim = DemoBmsSimulator()

    // Tick indices (700 ms cadence) chosen to land squarely inside each phase.
    // Riding 0..100s, stopped 100..120s, charging 120..180s.
    private fun tickForSecond(sec: Double): Int = (sec * 1000.0 / DemoBmsSimulator.TICK_INTERVAL_MS).toInt()

    @Test
    fun `soc stays within 0 to 100 across a full cycle`() {
        // Sample densely across more than one full ~180s cycle.
        for (tick in 0 until tickForSecond(400.0)) {
            val soc = sim.sampleAt(tick).soc
            assertTrue(soc in 0f..100f, "SOC out of range at tick=$tick: $soc")
        }
    }

    @Test
    fun `every sample has 16 cells`() {
        for (tick in 0 until tickForSecond(200.0) step 7) {
            assertEquals(16, sim.sampleAt(tick).cellVoltages.size, "cell count wrong at tick=$tick")
        }
    }

    @Test
    fun `pack voltage equals sum of cell voltages`() {
        for (tick in listOf(0, 50, 120, 200, 350)) {
            val s = sim.sampleAt(tick)
            val sum = s.cellVoltages.sum()
            assertTrue(
                abs(s.voltage - sum) < 0.001f,
                "voltage ${s.voltage} != cell sum $sum at tick=$tick"
            )
        }
    }

    @Test
    fun `charging phase has positive current`() {
        // ~150 s into the cycle is inside phase C (charging, 120..180s).
        val s = sim.sampleAt(tickForSecond(150.0))
        assertTrue(s.current > 0f, "charging current should be positive but was ${s.current}")
    }

    @Test
    fun `riding phase has negative current`() {
        // ~50 s into the cycle is inside phase A (riding) and away from the
        // regen windows (30-33s, 70-72s), so current is a discharge (negative).
        val s = sim.sampleAt(tickForSecond(50.0))
        assertTrue(s.current < 0f, "riding current should be negative but was ${s.current}")
    }

    @Test
    fun `demo data is clean with both mosfets enabled and no faults`() {
        val s = sim.sampleAt(tickForSecond(50.0))
        assertTrue(s.bmsFaults.isEmpty(), "demo should have no faults")
        assertTrue(s.chargeEnabled, "charge mosfet should be on")
        assertTrue(s.dischargeEnabled, "discharge mosfet should be on")
        assertTrue(s.isConnected, "demo sample should report connected")
        assertEquals(DemoBmsSimulator.CAPACITY_AH, s.capacity)
        assertEquals(DemoBmsSimulator.NUM_CYCLES, s.numCycles)
        assertEquals(4, s.temperatures.size)
    }

    @Test
    fun `charge tracks soc against capacity`() {
        val s = sim.sampleAt(0)
        val expected = DemoBmsSimulator.CAPACITY_AH * s.soc / 100f
        assertTrue(abs(s.charge - expected) < 0.01f, "charge ${s.charge} != expected $expected")
    }
}
