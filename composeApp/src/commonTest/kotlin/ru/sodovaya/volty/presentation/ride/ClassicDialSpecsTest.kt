package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.presentation.ride.gauge.ClusterSlot
import ru.sodovaya.volty.presentation.ride.gauge.DialGeometry
import ru.sodovaya.volty.util.UnitSystem
import kotlin.math.abs
import kotlin.math.roundToInt
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

    // Regression for the CRITICAL bug: valueText/unit went through UnitFormatter (so "29 mph")
    // but `value` and `scale` stayed in raw km/h, so the needle sat between ticks labelled in
    // km/h under an mph readout. Both the needle's own value AND the ring it moves against must
    // convert together — asserting only valueText/unit (as the test did before this fix) passes
    // even when the scale is silently left in the wrong unit, which is exactly what shipped.
    @Test fun the_hero_scale_converts_to_the_same_unit_as_the_readout() {
        val metricHero = specs(u = UnitSystem.METRIC)[ClusterSlot.HERO]!!
        assertEquals(70f, metricHero.scale.max)
        assertTrue(abs(metricHero.value - 47f) < 0.01f)

        val imperialHero = specs(u = UnitSystem.IMPERIAL)[ClusterSlot.HERO]!!
        // 70 km/h / 1.609344 = 43.496..., snapped UP to the next round mph number (45) so
        // pickMajorTicks — now correctly consulted in DISPLAY units, not km/h — has a clean
        // divisor. The snap changes the scale's ceiling, not the reading: `value` below is
        // still the exact, unsnapped conversion.
        assertTrue(
            abs(imperialHero.scale.max - 45f) < 0.05f,
            "expected hero.scale.max ~= 45 (43.5 snapped up to a round mph number), was ${imperialHero.scale.max}"
        )
        // 47 km/h / 1.609344 = 29.204... — the same conversion the "29" valueText above came from.
        assertTrue(abs(imperialHero.value - 29.2f) < 0.05f)
    }

    // The hero is the only dial with a runtime (not fixed-constant) max, so it is the only one
    // where majorTicks = 7 can stop dividing evenly. 70/7 = 10 happened to work; 80, 100, 120 do
    // not, and used to produce non-round tick labels (e.g. 0, 11, 23, 34, 46, 57, 69, 80).
    @Test fun the_hero_tick_labels_stay_round_above_70_kmh() {
        for (max in listOf(70f, 80f, 100f, 120f)) {
            val hero = ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, maxSpeedKmh = max)
                .first { it.slot == ClusterSlot.HERO }
            assertEquals(max, hero.scale.max)
            val majors = DialGeometry.majorValues(hero.scale)
            val interval = max / hero.scale.majorTicks
            assertTrue(
                abs(interval - interval.roundToInt()) < 0.01f,
                "majorTicks=${hero.scale.majorTicks} does not divide max=$max evenly (interval=$interval)"
            )
            // Every consecutive pair of ROUNDED major labels must differ by the same whole
            // number of km/h — the failure mode this guards against is ticks that are evenly
            // spaced in theory but jitter once each is independently rounded to 0 decimals.
            val roundedSteps = majors.map { it.roundToInt() }.zipWithNext { a, b -> b - a }
            assertEquals(1, roundedSteps.distinct().size, "uneven rounded tick steps: $roundedSteps")
        }
    }

    // Regression for a real bug in the fix above: pickMajorTicks was called on `heroMaxKmh`
    // (canonical km/h) while `scale.max` — the number DialGauge actually divides into printed
    // tick labels — is in DISPLAY units. Metric never noticed (same value both ways), but a
    // 70 km/h floor is 43.5 mph, which has no clean small-integer divisor at all: picking a tick
    // count from 70 (km/h) and applying it to a scale printing 43.5's ticks reproduces exactly
    // the ragged-tick defect this file's other test above guards against, just relocated into
    // imperial. The fix snaps the DISPLAY max up to the next round number (nearest 5) before
    // picking ticks — metric's already-round max is unaffected (asserted above); this test is
    // imperial's half of the same coverage.
    @Test fun the_hero_tick_labels_stay_round_in_imperial_too() {
        // 70/80/100/120 km/h, each converted to mph and snapped UP to the next round mph value:
        // 70 / 1.609344 = 43.496 -> 45; 80 -> 49.710 -> 50; 100 -> 62.137 -> 65; 120 -> 74.565 -> 75.
        val expectedScaleMax = mapOf(70f to 45f, 80f to 50f, 100f to 65f, 120f to 75f)
        for ((maxKmh, expected) in expectedScaleMax) {
            val hero = ClassicDialSpecs.build(motion, battery, UnitSystem.IMPERIAL, maxSpeedKmh = maxKmh)
                .first { it.slot == ClusterSlot.HERO }
            assertTrue(
                abs(hero.scale.max - expected) < 0.01f,
                "for maxSpeedKmh=$maxKmh expected scale.max ~= $expected, was ${hero.scale.max}"
            )
            val majors = DialGeometry.majorValues(hero.scale)
            val interval = hero.scale.max / hero.scale.majorTicks
            assertTrue(
                abs(interval - interval.roundToInt()) < 0.01f,
                "majorTicks=${hero.scale.majorTicks} does not divide max=${hero.scale.max} evenly (interval=$interval)"
            )
            val roundedSteps = majors.map { it.roundToInt() }.zipWithNext { a, b -> b - a }
            assertEquals(1, roundedSteps.distinct().size, "uneven rounded tick steps: $roundedSteps")
        }
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

    // Item 5: ClassicDialSpecs stays Compose-free (no stringResource access), so the composable
    // hands in already-resolved label text. Defaults keep every test above compiling unchanged,
    // but the wiring itself — that each caller-supplied label lands on the RIGHT slot, not a
    // neighbour's — needs its own coverage.
    @Test fun each_supplied_label_lands_on_its_own_slot() {
        val labels = ClassicDialLabels(
            current = "TOK", power = "MOSHCH", duty = "SHIM", speed = "SKOR",
            battery = "BAT", esc = "ESK", consumption = "RASHOD", motor = "MOTR"
        )
        val built = ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, 70f, labels).associateBy { it.slot }
        assertEquals("TOK", built[ClusterSlot.TOP_LEFT]!!.label)
        assertEquals("MOSHCH", built[ClusterSlot.TOP_CENTRE]!!.label)
        assertEquals("SHIM", built[ClusterSlot.TOP_RIGHT]!!.label)
        assertEquals("SKOR", built[ClusterSlot.HERO]!!.label)
        assertEquals("BAT", built[ClusterSlot.HERO_INSET]!!.label)
        assertEquals("ESK", built[ClusterSlot.BOTTOM_LEFT]!!.label)
        assertEquals("RASHOD", built[ClusterSlot.BOTTOM_CENTRE]!!.label)
        assertEquals("MOTR", built[ClusterSlot.BOTTOM_RIGHT]!!.label)
    }

    @Test fun default_labels_match_the_pre_localization_english_faces() {
        val built = ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, 70f).associateBy { it.slot }
        assertEquals("CURRENT", built[ClusterSlot.TOP_LEFT]!!.label)
        assertEquals("POWER", built[ClusterSlot.TOP_CENTRE]!!.label)
        assertEquals("DUTY", built[ClusterSlot.TOP_RIGHT]!!.label)
        assertEquals("SPEED", built[ClusterSlot.HERO]!!.label)
        assertEquals("BATTERY", built[ClusterSlot.HERO_INSET]!!.label)
        assertEquals("ESC", built[ClusterSlot.BOTTOM_LEFT]!!.label)
        assertEquals("CONSUMPTION", built[ClusterSlot.BOTTOM_CENTRE]!!.label)
        assertEquals("MOTOR", built[ClusterSlot.BOTTOM_RIGHT]!!.label)
    }
}
