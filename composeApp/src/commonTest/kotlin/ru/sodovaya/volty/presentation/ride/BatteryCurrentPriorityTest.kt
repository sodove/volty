package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.*
import ru.sodovaya.volty.util.UnitSystem
import ru.sodovaya.volty.presentation.ride.gauge.VescClusterSlot
import kotlin.test.*

class BatteryCurrentPriorityTest {
    @Test fun genericDashboardCurrentUsesBmsFirstAndOneDischargePositiveSign() {
        val motion = ControllerData(batteryCurrentA = 8f, isConnected = true)
        val battery = BmsData(current = -4f, isConnected = true)
        assertEquals(4f, LightDashboardMapper.batteryCurrentA(motion, battery))
        assertEquals(4f, ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, 70f)
            .first { it.slot == VescClusterSlot.CURRENT }.value)
        assertEquals("4", SecondaryGaugeMapper.map(SecondaryGauge.CURRENT, motion, battery, UnitSystem.METRIC).value)
    }
}
