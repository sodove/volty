package ru.sodovaya.volty.domain.model

import ru.sodovaya.volty.data.ble.planLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Covers [controllerVehicle], the sibling factory to [singlePackVehicle] for
 * the controller-only shape (Part G1, Task 4). The load-bearing assertion is
 * [planLinks_returns_exactly_one_linkSpec_carrying_the_controller_and_zero_packs]:
 * that is the contract this builder must never violate, because
 * [ru.sodovaya.volty.data.ble.LinkPlan.planLinks] is what every connect path
 * runs the vehicle through.
 */
@OptIn(ExperimentalTime::class)
class VehicleBuildersTest {
    private val now = Clock.System.now()

    private fun build(motor: MotorConfig = MotorConfig()): Vehicle = controllerVehicle(
        id = "v-1",
        name = "My VESC",
        iconKey = "generic",
        controllerType = ControllerType.VESC,
        address = "AA:BB",
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = now,
        motor = motor
    )

    @Test fun builds_a_controller_only_vehicle_that_satisfies_Vehicle_init() {
        // Vehicle.init requires packs.isNotEmpty() || controllers.isNotEmpty().
        // Just constructing without throwing already proves this holds; the
        // shape assertions below pin down exactly how it holds.
        val v = build()
        assertEquals(emptyList(), v.packs)
        assertEquals(1, v.controllers.size)
    }

    @Test fun exactly_one_controller_at_index_zero_with_no_canId() {
        val v = build()
        val c = v.controllers.single()
        assertEquals(0, c.index)
        assertEquals(null, c.canId)
        assertEquals(ControllerType.VESC, c.controllerType)
        assertEquals("AA:BB", c.address)
    }

    @Test fun providesDerivedBattery_is_unconditionally_true() {
        assertTrue(build().controllers.single().providesDerivedBattery)
    }

    @Test fun motor_config_is_threaded_through_not_ignored() {
        val custom = MotorConfig(polePairs = 7, wheelDiameterMm = 254, gearRatio = 2.5f)
        assertEquals(custom, build(motor = custom).controllers.single().motor)
    }

    @Test fun needsDerivedBattery_is_true() {
        assertTrue(build().needsDerivedBattery)
    }

    @Test fun allAddresses_is_exactly_the_controller_address() {
        assertEquals(setOf("AA:BB"), build().allAddresses)
    }

    @Test fun dashboardStyle_defaults_null_and_secondaryGauge_is_duty() {
        val v = build()
        assertEquals(null, v.dashboardStyle)
        assertEquals(SecondaryGauge.DUTY, v.secondaryGauge)
    }

    @Test fun planLinks_returns_exactly_one_linkSpec_carrying_the_controller_and_zero_packs() {
        val v = build()
        val links = planLinks(v.packs, v.controllers)
        assertEquals(1, links.size, "expected exactly one link, got $links")
        val link = links.single()
        assertEquals("AA:BB", link.address)
        assertTrue(link.ownedPacks.isEmpty(), "link must own zero packs, got ${link.ownedPacks}")
        assertEquals(listOf(0), link.ownedControllers.map { it.globalIndex })
    }
}
