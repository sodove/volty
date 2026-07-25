package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.presentation.ride.gauge.ClusterSlot
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClassicDialSpecsTest {

    private val motion = ControllerData(
        speedKmh = 47f, speedSource = SpeedSource.REPORTED, dutyPercent = 76f,
        batteryCurrentA = 52.4f, inputVoltageV = 78.2f, powerW = 4098f,
        escTempC = 52f, motorTempC = 68f, hasMotorTemp = true,
        consumedWh = 980f, tripKm = 58f, isConnected = true
    )
    private val battery = BmsData(voltage = 78.2f, soc = 84f, socKnown = true, isConnected = true)

    private fun specs(m: ControllerData = motion, b: BmsData = battery, u: UnitSystem = UnitSystem.METRIC) =
        ClassicDialSpecs.build(m, b, u, maxSpeedKmh = 70f).associateBy { it.slot }

    @Test fun all_eight_slots_are_filled_exactly_once() {
        val built = ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, 70f)
        assertEquals(ClusterSlot.entries.size, built.size)
        assertEquals(built.size, built.map { it.slot }.distinct().size)
    }

    @Test fun the_hero_is_speed_and_honours_the_unit_setting() {
        assertEquals("47", specs()[ClusterSlot.HERO]!!.valueText)
        assertEquals("29", specs(u = UnitSystem.IMPERIAL)[ClusterSlot.HERO]!!.valueText)
        assertEquals("mph", specs(u = UnitSystem.IMPERIAL)[ClusterSlot.HERO]!!.unit)
    }

    @Test fun an_unknown_speed_reads_as_a_dash() {
        val stopped = motion.copy(speedSource = SpeedSource.NONE)
        assertEquals("—", specs(m = stopped)[ClusterSlot.HERO]!!.valueText)
    }

    @Test fun duty_carries_its_shared_severity_and_danger_band() {
        val duty = specs()[ClusterSlot.TOP_RIGHT]!!
        assertEquals("76", duty.valueText)
        assertEquals(DutyLevel.WARN, duty.severity)
        assertEquals(90f, duty.dangerFrom)
    }

    @Test fun temperatures_use_the_shared_bands_and_dash_when_unreported() {
        val hot = motion.copy(motorTempC = 105f)
        assertEquals(DutyLevel.CRITICAL, specs(m = hot)[ClusterSlot.BOTTOM_RIGHT]!!.severity)
        val noSensor = motion.copy(hasMotorTemp = false)
        assertEquals("—", specs(m = noSensor)[ClusterSlot.BOTTOM_RIGHT]!!.valueText)
        assertNotNull(specs()[ClusterSlot.BOTTOM_LEFT]!!.dangerFrom)
    }

    @Test fun battery_dashes_when_the_state_of_charge_is_unknown() {
        val unknown = battery.copy(socKnown = false)
        assertEquals("—", specs(b = unknown)[ClusterSlot.HERO_INSET]!!.valueText)
    }

    @Test fun consumption_dashes_while_standing_still() {
        val stopped = motion.copy(speedKmh = 0f, consumedWh = 0f, tripKm = 0f)
        assertEquals("—", specs(m = stopped)[ClusterSlot.BOTTOM_CENTRE]!!.valueText)
    }

    @Test fun non_safety_dials_stay_neutral() {
        assertEquals(DutyLevel.NORMAL, specs()[ClusterSlot.TOP_LEFT]!!.severity)   // current
        assertEquals(DutyLevel.NORMAL, specs()[ClusterSlot.TOP_CENTRE]!!.severity) // power
        assertEquals(DutyLevel.NORMAL, specs()[ClusterSlot.HERO_INSET]!!.severity) // battery
        assertNull(specs()[ClusterSlot.TOP_CENTRE]!!.dangerFrom)
    }

    @Test fun the_hero_scale_never_collapses_to_zero() {
        val built = ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, maxSpeedKmh = 0f)
        val hero = built.first { it.slot == ClusterSlot.HERO }
        assertTrue(hero.scale.max > hero.scale.min)
    }
}
