package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Clean's two motion cards, tested as the pure strings they now are.
 *
 * They were the one renderer of the three deciding inside a `@Composable`, and
 * Compose is not unit-testable in this repo — which is why `G §9`'s **"0.0 kW"**
 * and `§9.1`'s **"avg 0.0 Wh/km"** survived every review that read the mappers.
 */
class CleanMetricMapperTest {

    private val vesc = ControllerData(
        speedKmh = 42f,
        speedSource = SpeedSource.REPORTED,
        batteryCurrentA = 53.8f,
        inputVoltageV = 78f,
        powerW = 4200f,
        consumedWh = 980f,
        tripKm = 58f,
        isConnected = true
    )

    @Test fun power_is_kilowatts_to_one_decimal() {
        assertEquals("4.2 kW", CleanMetricMapper.powerValue(vesc))
        assertEquals("-1.5 kW", CleanMetricMapper.powerValue(vesc.copy(powerW = -1500f)), "regen")
    }

    @Test fun an_unavailable_voltage_scale_shows_a_bare_dash_and_not_a_dashed_unit() {
        val noScale = vesc.copy(inputVoltageV = 0f, hasInputVoltage = false, powerW = 0f, hasPower = false)
        assertEquals(UNKNOWN_READOUT, CleanMetricMapper.powerValue(noScale))
        // "— kW" would read as a measurement of zero kilowatts, which is the exact
        // claim this contract exists to stop making. The BATTERY card beside it has
        // dropped its unit the same way since Part B.
        assertEquals("0.0 kW", CleanMetricMapper.powerValue(vesc.copy(powerW = 0f)), "a measured 0 keeps its unit")
    }

    @Test fun the_current_sub_line_is_always_signed_and_never_gated() {
        assertEquals("+53.8 A", CleanMetricMapper.powerSub(vesc))
        assertEquals("-12.0 A", CleanMetricMapper.powerSub(vesc.copy(batteryCurrentA = -12f)))
        val noScale = vesc.copy(hasInputVoltage = false, hasPower = false)
        assertEquals("+53.8 A", CleanMetricMapper.powerSub(noScale), "amps survive a missing scale")
    }

    @Test fun instant_consumption_dashes_when_it_is_not_a_measurement() {
        assertEquals("100.0", CleanMetricMapper.instantConsumptionValue(vesc))
        assertEquals(
            UNKNOWN_READOUT,
            CleanMetricMapper.instantConsumptionValue(vesc.copy(speedKmh = 0f)),
            "standing still"
        )
        assertEquals(
            UNKNOWN_READOUT,
            CleanMetricMapper.instantConsumptionValue(vesc.copy(powerW = 0f, hasPower = false)),
            "no voltage scale"
        )
        assertEquals(
            "0.0",
            CleanMetricMapper.instantConsumptionValue(vesc.copy(powerW = 0f)),
            "coasting with regen balancing the draw is a real 0.0"
        )
    }

    // ---------------------------------------------------------------------------
    // The hero — Part I Task 6. Its ARC was the last §9 leak: the readout dashed,
    // the ring beside it drew a confident zero.
    // ---------------------------------------------------------------------------

    @Test fun the_hero_readout_is_the_speed_in_the_riders_own_units() {
        assertEquals("42", CleanMetricMapper.heroSpeedValue(vesc, UnitSystem.METRIC))
        assertEquals("26", CleanMetricMapper.heroSpeedValue(vesc, UnitSystem.IMPERIAL))
    }

    @Test fun the_hero_ring_fills_with_the_speed_and_stops_at_the_scale() {
        assertEquals(0.6f, CleanMetricMapper.heroSpeedFraction(vesc, 70f))
        assertEquals(
            1f,
            CleanMetricMapper.heroSpeedFraction(vesc.copy(speedKmh = 120f), 70f),
            "a scale the rider has outrun draws a full ring, not an arc past its own end"
        )
        assertEquals(
            0f,
            CleanMetricMapper.heroSpeedFraction(vesc, 0f),
            "a collapsed scale is a zero ring, not a NaN sweep angle"
        )
    }

    @Test fun the_hero_dashes_and_parks_a_speed_nobody_measured() {
        // speedKmh is 42 AND the source is NONE — a pair no decoder emits, which is
        // why it separates the flag from the field. See UnknownMotionRenderingTest.
        val noWheel = vesc.copy(speedSource = SpeedSource.NONE)
        assertEquals(UNKNOWN_READOUT, CleanMetricMapper.heroSpeedValue(noWheel, UnitSystem.METRIC))
        assertEquals(0f, CleanMetricMapper.heroSpeedFraction(noWheel, 70f))
        assertEquals(
            "0",
            CleanMetricMapper.heroSpeedValue(vesc.copy(speedKmh = 0f), UnitSystem.METRIC),
            "a reported standstill still prints a number"
        )
    }

    @Test fun the_session_average_chip_is_hidden_rather_than_dashed() {
        assertEquals("16.9", CleanMetricMapper.sessionConsumptionValue(980f / 58f))
        assertNull(CleanMetricMapper.sessionConsumptionValue(null))
        assertEquals("0.0", CleanMetricMapper.sessionConsumptionValue(0f), "a measured 0 still shows")
    }
}
