package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.energy.RangeEstimate
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.presentation.ride.gauge.VescClusterSlot
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RangeLabelTest {
    private val battery = BmsData(voltage = 78.2f, soc = 84f, socKnown = true, isConnected = true)
    private val motion = ControllerData(isConnected = true)

    @Test fun null_range_has_no_label() {
        assertNull(rangeLabel(null, UnitSystem.METRIC, "км", "mi"))
    }

    @Test fun metric_label_rounds_to_whole_km() {
        assertEquals("~42 км", rangeLabel(RangeEstimate(42.4f, 20.0), UnitSystem.METRIC, "км", "mi"))
    }

    @Test fun imperial_label_converts_to_miles() {
        assertEquals("~26 mi", rangeLabel(RangeEstimate(42.4f, 20.0), UnitSystem.IMPERIAL, "км", "mi"))
    }

    @Test fun unit_words_are_caller_supplied() {
        assertEquals("~42 km", rangeLabel(RangeEstimate(42.4f, 20.0), UnitSystem.METRIC, kmUnit = "km", miUnit = "mi"))
    }

    // Light
    @Test fun light_battery_line_enlarged_and_range_after_separator() {
        val spec = LightDashboardMapper.batteryLine(battery, "~42 км")
        assertEquals(12f, spec.voltageSp)
        assertEquals(13f, spec.socSp)
        assertEquals(13f, spec.rangeSp)
        assertEquals("~42 км · ", spec.rangeText)
    }

    @Test fun light_battery_line_has_no_range_text_when_unearned() {
        assertNull(LightDashboardMapper.batteryLine(battery, null).rangeText)
    }

    // Clean hero (BATTERY centre)
    @Test fun clean_battery_gauge_carries_range_sub_label() {
        val r = SecondaryGaugeMapper.map(
            SecondaryGauge.BATTERY, motion, battery, UnitSystem.METRIC, rangeLabel = "~42 км"
        )
        assertEquals("~42 км", r.subLabel)
    }

    @Test fun clean_battery_gauge_without_range_has_no_sub_label() {
        assertNull(SecondaryGaugeMapper.map(SecondaryGauge.BATTERY, motion, battery, UnitSystem.METRIC).subLabel)
    }

    @Test fun clean_non_battery_gauge_never_takes_the_range() {
        val r = SecondaryGaugeMapper.map(
            SecondaryGauge.DUTY, motion, battery, UnitSystem.METRIC, rangeLabel = "~42 км"
        )
        assertNull(r.subLabel)
    }

    // Clean bottom row
    @Test fun clean_card_sub_adds_range_when_battery_is_not_the_hero() {
        assertEquals(
            "78.2 V · ~42 км",
            CleanMetricMapper.batteryCardSub(battery, "~42 км", SecondaryGauge.DUTY)
        )
    }

    @Test fun clean_card_sub_omits_range_when_battery_is_the_hero() {
        assertEquals(
            "78.2 V",
            CleanMetricMapper.batteryCardSub(battery, "~42 км", SecondaryGauge.BATTERY)
        )
    }

    @Test fun clean_card_sub_without_range_is_plain_voltage() {
        assertEquals("78.2 V", CleanMetricMapper.batteryCardSub(battery, null, SecondaryGauge.DUTY))
    }

    // Classic
    @Test fun classic_battery_dial_carries_range_sub_label_only_when_given() {
        val with = ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, 60f, rangeLabel = "~42 км")
        val without = ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, 60f)
        assertEquals("~42 км", with.single { it.slot == VescClusterSlot.BATTERY }.subLabel)
        assertNull(without.single { it.slot == VescClusterSlot.BATTERY }.subLabel)
        assertEquals(1, with.count { it.subLabel != null })
    }

    // An earned range never sits beside a charge the model has not earned. Deliberately incoherent
    // fixtures: a range handed over together with an unknown SoC (or a dead link) - a combination the
    // producers do not emit, which is what separates the mappers' own gate from the producers' habits.
    private val unknownSoc = BmsData(voltage = 78.2f, soc = 84f, socKnown = false, isConnected = true)
    private val offline = BmsData(voltage = 78.2f, soc = 84f, socKnown = true, isConnected = false)

    @Test fun no_mapper_shows_a_range_beside_an_unknown_soc_or_a_dead_link() {
        for (b in listOf(unknownSoc, offline)) {
            assertNull(LightDashboardMapper.batteryLine(b, "~42 км").rangeText)
            assertNull(SecondaryGaugeMapper.map(SecondaryGauge.BATTERY, motion, b, UnitSystem.METRIC, rangeLabel = "~42 км").subLabel)
            assertNull(
                ClassicDialSpecs.build(motion, b, UnitSystem.METRIC, 60f, rangeLabel = "~42 км")
                    .single { it.slot == VescClusterSlot.BATTERY }.subLabel
            )
            val sub = CleanMetricMapper.batteryCardSub(b, "~42 км", SecondaryGauge.DUTY)
            assertEquals(CleanMetricMapper.batteryVoltageValue(b), sub, "no range in the card sub line")
        }
    }
}
