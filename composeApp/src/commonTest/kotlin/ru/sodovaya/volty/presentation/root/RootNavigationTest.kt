package ru.sodovaya.volty.presentation.root

import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Pins the app's top-level routing RULE.
 *
 * The brief sketched these three assertions against a live [DefaultRootComponent].
 * That isn't reachable from `commonTest` here: [DefaultRootComponent] is a
 * `KoinComponent` that resolves five dependencies via `by inject()` / `get()`,
 * and the project carries no koin-test dependency in its test source sets (no
 * existing test starts Koin). Worse, the interesting transitions — "a connect
 * finished" — are lambdas passed *into* child components by `createChild`, so
 * driving them would mean standing up a Picker/AutoConnect over fake BLE too.
 *
 * So the rule was extracted into pure functions — [homeConfigFor] and
 * [configForTab] — which every navigation call site now goes through, and those
 * are what this test pins. Decompose's plumbing (`replaceAll` / `bringToFront`
 * actually moving the stack) is the library's job, not this suite's.
 */
@OptIn(ExperimentalTime::class)
class RootNavigationTest {

    private fun vehicle(
        controllers: List<Controller> = emptyList(),
        packs: List<Pack> = listOf(Pack(index = 0, label = "Battery", bmsType = BmsType.JK_BMS, bmsAddress = "AA:BB"))
    ) = Vehicle(
        id = "v1",
        name = "Test vehicle",
        iconKey = "scooter",
        packs = packs,
        controllers = controllers,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Clock.System.now()
    )

    private val vesc = Controller(
        index = 0,
        label = "Main",
        controllerType = ControllerType.VESC,
        address = "CC:DD"
    )

    @Test
    fun home_is_ride_for_a_vehicle_with_a_controller() {
        assertEquals(Config.Ride, homeConfigFor(vehicle(controllers = listOf(vesc))))
    }

    @Test
    fun home_is_ride_for_a_controller_only_vehicle() {
        // No packs at all — a bare VESC build is still a vehicle.
        assertEquals(Config.Ride, homeConfigFor(vehicle(controllers = listOf(vesc), packs = emptyList())))
    }

    @Test
    fun home_is_battery_for_a_pure_bms_vehicle() {
        assertEquals(Config.Dashboard, homeConfigFor(vehicle()))
    }

    @Test
    fun home_is_battery_when_there_is_no_active_vehicle() {
        // Guest / cold-start: never strand the user on a Ride screen with no
        // motion source behind it.
        assertEquals(Config.Dashboard, homeConfigFor(null))
    }

    @Test
    fun the_battery_tab_reaches_the_existing_dashboard() {
        assertEquals(Config.Dashboard, configForTab(RootComponent.Tab.Battery))
    }

    @Test
    fun the_ride_and_settings_tabs_reach_their_own_destinations() {
        assertEquals(Config.Ride, configForTab(RootComponent.Tab.Ride))
        assertEquals(Config.Settings, configForTab(RootComponent.Tab.Settings))
    }

    // --- shouldLeaveRide: never leave a Ride entry behind for a vehicle that
    // has no controller. The stack is passed in as a plain list, so the whole
    // rule is reachable without Decompose.

    @Test
    fun a_buried_ride_entry_is_left_when_the_vehicle_loses_its_controller() {
        // Ride home, then the Battery tab: bringToFront leaves [Ride, Dashboard].
        // Switching to a pure-BMS vehicle from the BATTERY dashboard's sheet
        // means `active` is Dashboard — but Ride is still underneath, one system
        // back away. Inspecting only the active entry would miss this.
        assertTrue(shouldLeaveRide(vehicle(), listOf(Config.Ride, Config.Dashboard)))
    }

    @Test
    fun an_active_ride_entry_is_left_when_the_vehicle_loses_its_controller() {
        assertTrue(shouldLeaveRide(vehicle(), listOf(Config.Ride)))
    }

    @Test
    fun a_stack_without_ride_is_left_alone() {
        assertFalse(shouldLeaveRide(vehicle(), listOf(Config.Dashboard, Config.Graph)))
        assertFalse(shouldLeaveRide(vehicle(), emptyList<Config>()))
    }

    @Test
    fun switching_between_two_controller_vehicles_stays_on_ride() {
        assertFalse(shouldLeaveRide(vehicle(controllers = listOf(vesc)), listOf(Config.Ride)))
    }

    @Test
    fun the_transient_null_vehicle_inside_a_switch_does_not_leave_ride() {
        // disconnect() clears activeVehicle before connect() sets the next one.
        // Reacting to that gap would bounce every Ride -> Ride vehicle switch
        // onto the battery dashboard, and would race the disconnect path (which
        // routes to Scanning on its own).
        assertFalse(shouldLeaveRide(null, listOf(Config.Ride)))
    }
}
