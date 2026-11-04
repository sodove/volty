package ru.sodovaya.volty.presentation.map

import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.presentation.ride.RideSpeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When Classic/Clean raise the location demand for GPS speed although no map is mounted (2026-11-04). */
class RideGpsSpeedDemandTest {

    @Test
    fun a_battery_only_vehicle_wants_gps_on_its_ride_screen_whatever_the_style() {
        assertTrue(rideGpsSpeedDemand(rideScreenActive = true, vehicleHasController = false,
            controllerSpeedKnown = false, controllerSpeedSeen = false))
        // Incoherent: a battery-only vehicle whose motion claims a known speed still has no controller to trust.
        assertTrue(rideGpsSpeedDemand(rideScreenActive = true, vehicleHasController = false,
            controllerSpeedKnown = true, controllerSpeedSeen = true))
    }

    @Test
    fun nothing_is_wanted_off_the_ride_screen() {
        listOf(true, false).forEach { controller ->
            listOf(true, false).forEach { seen ->
                assertFalse(rideGpsSpeedDemand(rideScreenActive = false, vehicleHasController = controller,
                    controllerSpeedKnown = false, controllerSpeedSeen = seen))
            }
        }
    }

    @Test
    fun a_vehicle_with_a_healthy_controller_never_asks_for_gps() {
        assertFalse(rideGpsSpeedDemand(rideScreenActive = true, vehicleHasController = true,
            controllerSpeedKnown = true, controllerSpeedSeen = true))
    }

    @Test
    fun connect_warm_up_and_a_controller_that_never_knew_its_speed_do_not_ask_for_gps() {
        // Before the first frame, and a VESC with no wheel geometry: the speed was never seen on this visit.
        assertFalse(rideGpsSpeedDemand(rideScreenActive = true, vehicleHasController = true,
            controllerSpeedKnown = false, controllerSpeedSeen = false))
    }

    @Test
    fun a_controller_that_dropped_out_mid_ride_asks_for_gps_until_its_speed_returns() {
        var seen = false
        val timeline = listOf(false, true, true, false, false, true)
        val wanted = timeline.map { known ->
            seen = rideControllerSpeedSeen(seen, known)
            rideGpsSpeedDemand(rideScreenActive = true, vehicleHasController = true,
                controllerSpeedKnown = known, controllerSpeedSeen = seen)
        }
        assertEquals(listOf(false, false, false, true, true, false), wanted)
    }

    @Test
    fun the_dropout_latch_reads_the_same_speed_rule_as_the_dashboards() {
        // A disconnected sample with a stale REPORTED speed is not a controller speed (RideSpeed's isConnected gate).
        val ghost = ControllerData(isConnected = false, speedKmh = 30f, speedSource = SpeedSource.REPORTED)
        assertFalse(RideSpeed.controllerSpeedKnown(ghost))
        assertTrue(rideGpsSpeedDemand(rideScreenActive = true, vehicleHasController = true,
            controllerSpeedKnown = RideSpeed.controllerSpeedKnown(ghost), controllerSpeedSeen = true))
    }

    @Test
    fun the_permission_prompt_stays_with_the_light_map() {
        DashboardStyle.entries.forEach { style ->
            assertEquals(style == DashboardStyle.LIGHT,
                rideMapHostState(rideAvailable = true, activeScreen = RideMapScreen.RIDE, activeStyle = style)
                    .requestLocationPermission, "$style")
        }
    }
}
