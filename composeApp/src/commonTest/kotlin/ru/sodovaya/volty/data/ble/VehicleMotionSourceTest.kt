package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.history.HistorySourceKind
import ru.sodovaya.volty.domain.history.historyRecordsGpsSpeed
import ru.sodovaya.volty.domain.model.*
import ru.sodovaya.volty.presentation.map.RideGpsSpeedLatch
import ru.sodovaya.volty.presentation.map.rideGpsSpeedWanted
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Review I2 (2026-11-04): the dashboard's "has a motion source" and the recorder's must be ONE rule. A Begode or a
 * Leaperkim configured as a battery pack has no controllers, yet decodes a CONTROLLER source; it is not battery-only.
 */
@OptIn(ExperimentalTime::class)
class VehicleMotionSourceTest {
    /** A vehicle with packs only. A wheel type gets as many packs at its one address as its decoder plan owns. */
    private fun packOnly(type: BmsType, address: String = "AA:BB:CC:DD:EE:0${type.ordinal}"): Vehicle {
        val count = batteryDecoderPlan(type).packCount.coerceAtLeast(1)
        return Vehicle(id = "v-$type", name = "$type", iconKey = "generic",
            packs = List(count) { Pack(index = it, label = "P${it + 1}", bmsType = type, bmsAddress = address, cellCount = 20) },
            chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
    }

    @Test fun decodes_motion_is_exactly_the_battery_decoder_plans_with_a_controller_source() {
        BmsType.entries.forEach { type ->
            val planned = batteryDecoderPlan(type).sources.any { it.origin == TelemetryOrigin.CONTROLLER }
            assertEquals(planned, type.decodesMotion, "$type")
        }
    }

    @Test fun the_dashboard_and_the_recorder_agree_on_every_pack_only_vehicle() {
        BmsType.entries.filter { it != BmsType.VESC_BMS }.forEach { type ->
            val vehicle = packOnly(type)
            val registry = planLinks(vehicle.packs, vehicle.controllers)
                .flatMap { nativeHistorySourcesForSelectedLink(it, vehicle).values }
            assertEquals(vehicle.hasMotionSource, !historyRecordsGpsSpeed(registry), "$type: ${registry.map { it.kind }}")
        }
    }

    @Test fun a_battery_configured_begode_that_decodes_motion_is_not_battery_only() {
        val wheel = packOnly(BmsType.BEGODE)
        assertFalse(wheel.hasControllers, "the fixture is the pack-only route")
        assertTrue(wheel.hasMotionSource)
        val registry = planLinks(wheel.packs, wheel.controllers).flatMap { nativeHistorySourcesForSelectedLink(it, wheel).values }
        assertTrue(registry.any { it.kind == HistorySourceKind.CONTROLLER }, "it decodes a controller")
        // Warm-up and a healthy wheel: no GPS. Before the fix, hasControllers made this true for the whole Ride screen.
        val latch = RideGpsSpeedLatch().next(wheel.id, ControllerData())
        assertFalse(rideGpsSpeedWanted(true, true, wheel, ControllerData(), latch))
        val moving = ControllerData(isConnected = true, speedKmh = 30f, speedSource = SpeedSource.REPORTED)
        val seen = latch.next(wheel.id, moving)
        assertFalse(rideGpsSpeedWanted(true, true, wheel, moving, seen))
        // Its dropout exception still applies, as for any controller.
        assertTrue(rideGpsSpeedWanted(true, true, wheel, ControllerData(), seen.next(wheel.id, ControllerData())))
    }

    @Test fun a_jk_battery_only_vehicle_wants_gps_and_only_on_its_ride_screen() {
        val bike = packOnly(BmsType.JK_BMS)
        assertFalse(bike.hasMotionSource)
        val latch = RideGpsSpeedLatch().next(bike.id, ControllerData())
        assertTrue(rideGpsSpeedWanted(true, true, bike, ControllerData(), latch))
        assertFalse(rideGpsSpeedWanted(false, true, bike, ControllerData(), latch), "another screen")
        assertFalse(rideGpsSpeedWanted(true, false, bike, ControllerData(), latch), "ride unavailable")
        assertFalse(rideGpsSpeedWanted(true, true, null, ControllerData(), latch), "no vehicle (a guest link)")
    }

    @Test fun the_latch_is_earned_per_vehicle() {
        val a = packOnly(BmsType.BEGODE, "AA:00:00:00:00:01").copy(id = "a")
        val b = packOnly(BmsType.BEGODE, "AA:00:00:00:00:02").copy(id = "b")
        val moving = ControllerData(isConnected = true, speedKmh = 20f, speedSource = SpeedSource.REPORTED)
        val seenOnA = RideGpsSpeedLatch().next("a", moving)
        assertTrue(seenOnA.seen)
        // Switching to b resets it: b never showed a speed, so b's warm-up is not a dropout.
        val onB = seenOnA.next("b", ControllerData())
        assertFalse(onB.seen)
        assertFalse(rideGpsSpeedWanted(true, true, b, ControllerData(), onB))
        // A stale latch from a is not b's, even if passed by mistake.
        assertFalse(rideGpsSpeedWanted(true, true, b, ControllerData(), seenOnA))
        assertTrue(rideGpsSpeedWanted(true, true, a, ControllerData(), seenOnA))
    }
}
