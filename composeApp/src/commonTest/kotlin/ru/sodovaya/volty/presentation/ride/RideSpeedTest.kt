package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.location.LocationSource
import ru.sodovaya.volty.domain.location.RideLocationFix
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.SpeedUnknownReason
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.presentation.navigation.LocationUiStatus
import ru.sodovaya.volty.presentation.ride.gauge.VescClusterSlot
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The GPS speed fallback of all three Ride renderers (investigation `gps-speed-battery-only.md`, 2026-11-04).
 *
 * Fixtures are deliberately incoherent where the contract is about absent data (CLAUDE.md rule 2): a controller that
 * says it is disconnected while carrying a REPORTED 42 km/h, a FRESH status over a fix with no speed. No producer emits
 * those, which is what separates the rule from the producers' habits. Every renderer is asserted on what it would
 * DRAW (readout, needle, caption), never on an intermediate.
 */
class RideSpeedTest {
    private val now = 1_800_000_000_000L

    private fun fix(ageMillis: Long, speedMps: Double?, accuracy: Double = 5.0) = RideLocationFix(
        coordinate = GeoCoordinate(55.75, 37.62),
        accuracyMeters = accuracy,
        speedMetersPerSecond = speedMps,
        bearingDegrees = null,
        capturedAtEpochMillis = now - ageMillis,
        elapsedRealtimeMillis = null,
        source = LocationSource.GPS,
    )

    // ---- freshGpsSpeedKmh: the derivation lifted out of RootScreen ----

    @Test
    fun a_fresh_fix_with_speed_converts_metres_per_second_to_kmh() {
        assertEquals(36f, RideSpeed.freshGpsSpeedKmh(LocationUiStatus.FRESH, fix(500, 10.0), now)!!, 1e-4f)
        assertEquals(0f, RideSpeed.freshGpsSpeedKmh(LocationUiStatus.FRESH, fix(0, 0.0), now))
    }

    @Test
    fun a_fix_without_speed_has_no_gps_speed_even_when_the_status_is_fresh() {
        // Incoherent on purpose: FRESH is about the coordinate, the speed is a separate claim the fix did not make.
        assertNull(RideSpeed.freshGpsSpeedKmh(LocationUiStatus.FRESH, fix(500, null), now))
    }

    @Test
    fun a_stale_fix_has_no_gps_speed_whatever_the_status_says() {
        // The status was FRESH when the fix arrived; the stream then stopped. RootScreen's old rule kept this forever.
        assertNull(RideSpeed.freshGpsSpeedKmh(LocationUiStatus.FRESH, fix(5_001, 10.0), now))
        assertEquals(36f, RideSpeed.freshGpsSpeedKmh(LocationUiStatus.FRESH, fix(5_000, 10.0), now)!!, 1e-4f)
        // And the status itself, when it says STALE over a young fix, is obeyed too.
        assertNull(RideSpeed.freshGpsSpeedKmh(LocationUiStatus.STALE, fix(100, 10.0), now))
    }

    @Test
    fun only_the_fresh_status_admits_a_speed() {
        LocationUiStatus.entries.filter { it != LocationUiStatus.FRESH }.forEach { status ->
            assertNull(RideSpeed.freshGpsSpeedKmh(status, fix(100, 10.0), now), "$status")
        }
        assertNull(RideSpeed.freshGpsSpeedKmh(LocationUiStatus.FRESH, null, now))
    }

    @Test
    fun a_future_dated_or_inaccurate_fix_has_no_gps_speed() {
        assertNull(RideSpeed.freshGpsSpeedKmh(LocationUiStatus.FRESH, fix(-1, 10.0), now))
        assertNull(RideSpeed.freshGpsSpeedKmh(LocationUiStatus.FRESH, fix(100, 10.0, accuracy = 50.1), now))
        assertEquals(36f, RideSpeed.freshGpsSpeedKmh(LocationUiStatus.FRESH, fix(100, 10.0, accuracy = 50.0), now)!!, 1e-4f)
    }

    @Test
    fun nan_or_negative_speed_is_refused_by_the_fix_and_by_the_rule() {
        assertFailsWith<IllegalArgumentException> { fix(100, Double.NaN) }
        assertFailsWith<IllegalArgumentException> { fix(100, -1.0) }
        assertNull(RideSpeed.freshGpsSpeedKmh(LocationUiStatus.FRESH, now - 100, 5.0, Double.NaN, now))
        assertNull(RideSpeed.freshGpsSpeedKmh(LocationUiStatus.FRESH, now - 100, 5.0, -0.5, now))
        assertNull(RideSpeed.freshGpsSpeedKmh(LocationUiStatus.FRESH, now - 100, 5.0, Double.POSITIVE_INFINITY, now))
        assertNull(RideSpeed.freshGpsSpeedKmh(LocationUiStatus.FRESH, now - 100, Double.NaN, 3.0, now))
    }

    @Test
    fun the_expiry_delay_lands_one_millisecond_after_the_fix_stops_being_fresh() {
        assertEquals(4_501L, RideSpeed.gpsExpiresInMillis(now - 500, now))
        assertEquals(0L, RideSpeed.gpsExpiresInMillis(now - 9_000, now))
        val expiresAt = now + RideSpeed.gpsExpiresInMillis(now, now)
        assertNull(RideSpeed.freshGpsSpeedKmh(LocationUiStatus.FRESH, now, 5.0, 10.0, expiresAt))
    }

    // ---- the dashboards' speed choice: Light, Classic and Clean must agree ----

    private data class Drawn(val light: String, val clean: String, val cleanFraction: Float,
        val classicText: String?, val classicValue: Float, val classicCaption: String, val source: RideSpeedSource?)

    private fun drawn(motion: ControllerData, gps: Float?): Drawn {
        val classic = ClassicDialSpecs.build(motion, BmsData(), UnitSystem.METRIC, maxSpeedKmh = 70f, gpsSpeedKmh = gps)
            .single { it.slot == VescClusterSlot.SPEED }
        return Drawn(
            light = LightDashboardMapper.map(motion, BmsData(), UnitSystem.METRIC, gps).speed.value,
            clean = CleanMetricMapper.heroSpeedValue(motion, UnitSystem.METRIC, gps),
            cleanFraction = CleanMetricMapper.heroSpeedFraction(motion, 70f, gps),
            classicText = classic.valueTextOverride,
            classicValue = classic.value,
            classicCaption = classic.caption,
            source = CleanMetricMapper.heroSpeed(motion, gps)?.source,
        )
    }

    private val noWheel = ControllerData(isConnected = true, speedKmh = 0f, speedSource = SpeedSource.NONE,
        speedUnknownReason = SpeedUnknownReason.NO_WHEEL_GEOMETRY)

    @Test
    fun a_connected_controller_with_unknown_speed_falls_back_to_gps_on_every_renderer() {
        val d = drawn(noWheel, 23.5f)
        assertEquals(listOf("24", "24"), listOf(d.light, d.clean))
        assertNull(d.classicText, "Classic prints the GPS number, not a dash")
        assertEquals(23.5f, d.classicValue)
        assertEquals(23.5f / 70f, d.cleanFraction)
        assertEquals("SPEED\nGPS", d.classicCaption, "a GPS speed is captioned as GPS")
        assertEquals(RideSpeedSource.GPS, d.source)
        // The reason line is an excuse for a missing number; with a GPS number on screen it is not drawn.
        assertNull(CleanMetricMapper.speedUnknownReasonKey(noWheel, 23.5f))
        assertEquals(CleanMetricMapper.SpeedUnknownReasonKey.NO_WHEEL_GEOMETRY, CleanMetricMapper.speedUnknownReasonKey(noWheel, null))
    }

    @Test
    fun a_connected_controller_with_known_speed_wins_over_gps() {
        val wheel = ControllerData(isConnected = true, speedKmh = 11f, speedSource = SpeedSource.REPORTED)
        val d = drawn(wheel, 23.5f)
        assertEquals(listOf("11", "11"), listOf(d.light, d.clean))
        assertEquals(11f, d.classicValue)
        assertEquals("SPEED", d.classicCaption)
        assertEquals(RideSpeedSource.CONTROLLER, d.source)
        // A controller standing still is a measured 0, and still beats a GPS that thinks the rider is moving.
        val stopped = drawn(wheel.copy(speedKmh = 0f), 23.5f)
        assertEquals(listOf("0", "0"), listOf(stopped.light, stopped.clean))
        assertEquals(0f, stopped.cleanFraction)
        assertNull(stopped.classicText)
    }

    @Test
    fun a_battery_only_vehicle_uses_gps_and_without_it_shows_a_dash_not_a_zero() {
        // A BMS-only vehicle's motion is the default sample: no controller, isConnected false.
        val d = drawn(ControllerData(), 31f)
        assertEquals(listOf("31", "31"), listOf(d.light, d.clean))
        assertEquals(31f, d.classicValue)
        assertEquals(RideSpeedSource.GPS, d.source)
        val none = drawn(ControllerData(), null)
        assertEquals(listOf(UNKNOWN_READOUT, UNKNOWN_READOUT), listOf(none.light, none.clean))
        assertEquals(UNKNOWN_READOUT, none.classicText)
        assertEquals(0f, none.cleanFraction)
        assertNull(none.source)
    }

    @Test
    fun a_disconnected_controller_carrying_a_reported_speed_is_not_shown() {
        // Incoherent: no producer publishes a REPORTED speed on a disconnected sample (MotionAggregator returns
        // ControllerData(isConnected = false)). Classic and Clean used to print this 42; now all three agree with Light.
        val ghost = ControllerData(isConnected = false, speedKmh = 42f, speedSource = SpeedSource.REPORTED)
        val dash = drawn(ghost, null)
        assertEquals(listOf(UNKNOWN_READOUT, UNKNOWN_READOUT), listOf(dash.light, dash.clean))
        assertEquals(UNKNOWN_READOUT, dash.classicText)
        assertEquals(0f, dash.cleanFraction)
        val gps = drawn(ghost, 17f)
        assertEquals(listOf("17", "17"), listOf(gps.light, gps.clean))
        assertEquals(17f, gps.classicValue)
        assertEquals(RideSpeedSource.GPS, gps.source)
    }

    @Test
    fun a_placeholder_speed_without_a_source_never_drives_a_gauge() {
        // Incoherent: 88 km/h written into speedKmh with SpeedSource.NONE. Neither the needle nor the number may use it.
        val placeholder = ControllerData(isConnected = true, speedKmh = 88f, speedSource = SpeedSource.NONE)
        val d = drawn(placeholder, 12f)
        assertEquals(listOf("12", "12"), listOf(d.light, d.clean))
        assertEquals(12f / 70f, d.cleanFraction)
        assertEquals(12f, d.classicValue)
    }

    @Test
    fun an_invalid_gps_number_is_not_a_speed() {
        listOf(Float.NaN, -3f, Float.POSITIVE_INFINITY).forEach { bad ->
            val d = drawn(ControllerData(), bad)
            assertEquals(UNKNOWN_READOUT, d.clean, "$bad")
            assertEquals(UNKNOWN_READOUT, d.classicText, "$bad")
            assertEquals(0f, d.cleanFraction, "$bad")
        }
    }

    @Test
    fun the_fraction_is_parked_on_a_degenerate_scale() {
        assertEquals(0f, RideSpeed.fraction(ControllerData(), 0f, 20f))
        assertEquals(0f, RideSpeed.fraction(ControllerData(), Float.NaN, 20f))
        assertEquals(1f, RideSpeed.fraction(ControllerData(), 10f, 20f))
    }

    @Test
    fun controller_speed_known_needs_both_a_link_and_a_source() {
        assertEquals(false, RideSpeed.controllerSpeedKnown(ControllerData()))
        assertEquals(false, RideSpeed.controllerSpeedKnown(noWheel))
        assertEquals(false, RideSpeed.controllerSpeedKnown(ControllerData(speedKmh = 5f, speedSource = SpeedSource.REPORTED)))
        assertEquals(true, RideSpeed.controllerSpeedKnown(ControllerData(isConnected = true, speedSource = SpeedSource.REPORTED)))
    }
}
