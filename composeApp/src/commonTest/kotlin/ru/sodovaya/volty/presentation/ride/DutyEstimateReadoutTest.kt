package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DutySource
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.presentation.ride.gauge.VescClusterSlot
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every dashboard writes a duty through the one function [MotionReadings.dutyText], so an ESTIMATED duty is marked
 * "~" and a ceiling one reads "≥100" on all three faces, and a MEASURED duty reads exactly as before.
 *
 * Fixtures are deliberately incoherent where the contract is about absent data: `hasDuty = false` beside a number
 * and an ESTIMATED source, a combination no producer emits.
 */
class DutyEstimateReadoutTest {

    private fun motion(
        duty: Float = 72.4f, has: Boolean = true,
        source: DutySource = DutySource.MEASURED, ceiling: Boolean = false,
    ) = ControllerData(
        dutyPercent = duty, hasDuty = has, dutySource = source, dutyAtCeiling = ceiling,
        speedKmh = 40f, isConnected = true
    )

    private val battery = BmsData(voltage = 80f, soc = 80f, socKnown = true, isConnected = true)

    @Test fun `a measured duty reads as a plain rounded number`() {
        assertEquals("72", MotionReadings.dutyText(motion()))
    }

    @Test fun `an estimated duty is marked with a tilde`() {
        assertEquals("~72", MotionReadings.dutyText(motion(source = DutySource.ESTIMATED)))
    }

    @Test fun `an estimate at the ceiling says at least 100 and never a number`() {
        assertEquals("≥100", MotionReadings.dutyText(motion(duty = 100f, source = DutySource.ESTIMATED, ceiling = true)))
        // Incoherent on purpose: the ceiling flag with a lower number still says ≥100 - the flag is the claim.
        assertEquals("≥100", MotionReadings.dutyText(motion(duty = 63f, source = DutySource.ESTIMATED, ceiling = true)))
    }

    @Test fun `a ceiling flag on a MEASURED duty is ignored - only an estimate can be at its ceiling`() {
        assertEquals("100", MotionReadings.dutyText(motion(duty = 100f, source = DutySource.MEASURED, ceiling = true)))
    }

    @Test fun `no duty is no text, whatever the source and the number say`() {
        assertNull(MotionReadings.dutyText(motion(duty = 72f, has = false, source = DutySource.ESTIMATED, ceiling = true)))
        assertNull(MotionReadings.dutyText(motion(duty = 72f, has = false)))
    }

    @Test fun `the Light HUD shows the marker`() {
        fun duty(m: ControllerData) = LightDashboardMapper.map(m, battery, UnitSystem.METRIC).duty
        assertEquals("72", duty(motion()).value)
        assertEquals("~72", duty(motion(source = DutySource.ESTIMATED)).value)
        assertEquals("≥100", duty(motion(duty = 100f, source = DutySource.ESTIMATED, ceiling = true)).value)
        assertEquals("—", duty(motion(has = false, source = DutySource.ESTIMATED)).value)
    }

    @Test fun `the Light HUD keeps the marker through the smoother`() {
        val smoother = LightTelemetrySmoother()
        val first = LightDashboardMapper.values(motion(duty = 70f, source = DutySource.ESTIMATED), battery)
        val second = LightDashboardMapper.values(motion(duty = 80f, source = DutySource.ESTIMATED, ceiling = true), battery)
        smoother.advance(first, 16L)
        val mid = smoother.advance(second, 16L)
        assertEquals(true, mid.dutyEstimated)
        assertEquals(true, mid.dutyAtCeiling)
        assertEquals("≥100", LightDashboardMapper.map(mid, UnitSystem.METRIC).duty.value)
    }

    @Test fun `the Light graph peak label is marked for an estimate`() {
        assertEquals("max 72%", LightDashboardMapper.dutyPeakLabel(72.4f, estimated = false))
        assertEquals("max ~72%", LightDashboardMapper.dutyPeakLabel(72.4f, estimated = true))
    }

    @Test fun `the Clean secondary gauge shows the marker`() {
        fun duty(m: ControllerData) = SecondaryGaugeMapper.map(SecondaryGauge.DUTY, m, battery, UnitSystem.METRIC)
        assertEquals("72", duty(motion()).value)
        assertEquals("~72", duty(motion(source = DutySource.ESTIMATED)).value)
        assertEquals("≥100", duty(motion(duty = 100f, source = DutySource.ESTIMATED, ceiling = true)).value)
        assertEquals("—", duty(motion(has = false, source = DutySource.ESTIMATED)).value)
    }

    @Test fun `the Classic duty dial shows the marker and keeps the needle on the number`() {
        fun dial(m: ControllerData) = ClassicDialSpecs.build(m, battery, UnitSystem.METRIC, 70f)
            .first { it.slot == VescClusterSlot.DUTY }
        assertNull(dial(motion()).valueTextOverride)
        assertEquals("~72", dial(motion(source = DutySource.ESTIMATED)).valueTextOverride)
        assertEquals("≥100", dial(motion(duty = 100f, source = DutySource.ESTIMATED, ceiling = true)).valueTextOverride)
        assertEquals("—", dial(motion(has = false, source = DutySource.ESTIMATED)).valueTextOverride)
        assertEquals(72.4f, dial(motion(source = DutySource.ESTIMATED)).value)
    }
}
