package ru.sodovaya.volty.presentation.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.navigate
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
@OptIn(ExperimentalTime::class, DelicateDecomposeApi::class)
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
    fun home_is_ride_for_a_Kelly_controller_only_vehicle() {
        // Task H's controller-only picker shape has no persisted battery. Keep
        // Kelly explicit: a branch that accidentally routes this controller to
        // Dashboard would still satisfy the generic VESC test above.
        val kelly = vesc.copy(controllerType = ControllerType.KELLY)
        assertEquals(Config.Ride, homeConfigFor(vehicle(controllers = listOf(kelly), packs = emptyList())))
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

    // --- shouldPopOnBack: system back must agree with the screens' own ‹
    // buttons (both nav.pop()) whenever there is a home entry to reveal, so a
    // system back out of Graph/Settings does not destroy and rebuild the Ride
    // (or Dashboard) child underneath — see RootComponent.kt's onBack().

    @Test
    fun back_from_graph_pops_when_a_home_entry_is_underneath() {
        assertTrue(shouldPopOnBack(Config.Graph, stackSize = 2))
    }

    @Test
    fun back_from_settings_pops_when_a_home_entry_is_underneath() {
        assertTrue(shouldPopOnBack(Config.Settings, stackSize = 2))
    }

    @Test
    fun back_from_graph_or_settings_falls_back_to_replaceAll_with_no_entry_to_pop_to() {
        // Defensive only — no push onto Graph/Settings today ever leaves it as
        // the stack's sole entry — but onBack() must still be total.
        assertFalse(shouldPopOnBack(Config.Graph, stackSize = 1))
        assertFalse(shouldPopOnBack(Config.Settings, stackSize = 1))
    }

    @Test
    fun back_from_any_other_screen_always_pops() {
        assertTrue(shouldPopOnBack(Config.Dashboard, stackSize = 1))
        assertTrue(shouldPopOnBack(Config.Ride, stackSize = 5))
        assertTrue(shouldPopOnBack(Config.PackDetail(0), stackSize = 2))
    }

    // --- Final fixes, Fix 1: "+ Add new battery" used to nav.replaceAll()
    // Config.Picker(mode = "add") — wiping the whole stack down to one entry
    // — so neither the add-picker's own Cancel (nav.pop()) nor system back
    // (which also pops for a Picker: shouldPopOnBack above is true whenever
    // current isn't Graph/Settings, regardless of stack size) had anything
    // left to reveal. DefaultRootComponent.createChild now nav.push()es
    // instead, for both call sites that used to replaceAll() into the
    // add-picker (Config.Picker's own onAddNewBatteryRequested, and
    // Config.Welcome's onAddBatteryRequested).
    //
    // DefaultRootComponent itself cannot be instantiated in this test source
    // set to drive that wiring directly. It is a KoinComponent resolving
    // BmsRepository / VehicleRepository / PermissionsChecker via `by inject()`
    // / `get()` — already the reason RootNavigationTest's own class doc above
    // extracted homeConfigFor/shouldPopOnBack/shouldLeaveRide as pure
    // functions instead. PermissionsChecker makes it worse than "add
    // koin-test": it is an `expect class` whose Android `actual`
    // (AndroidPermissionsChecker) wraps a real android.content.Context and is
    // resolved EAGERLY in DefaultRootComponent's own init (computeInitialConfigSync,
    // called while building the `stack` property), before any lambda under
    // test would even run. There is no mocking library, no Robolectric and no
    // koin-test dependency in this project's test source sets (see
    // composeApp/build.gradle.kts), so faking that Context is not possible
    // without adding new test infrastructure. So: not reachable from a unit
    // test here, plainly.
    //
    // What IS reachable, and is more than shouldPopOnBack alone can prove
    // (that only pins that back POPS — it says nothing about push vs.
    // replaceAll, which is the actual bug): the real Config sealed class and
    // the real Decompose StackNavigation/childStack DefaultRootComponent
    // itself uses underneath, driven through the exact call sequence
    // createChild's lambdas now perform.

    /** Builds a bare stack over [Config] the same way DefaultRootComponent's
     * `stack` property does (StackNavigation + childStack), minus the Koin
     * child factory — the child instance IS the configuration, since only the
     * stack SHAPE is under test here. */
    private fun buildStack(initial: Config): Pair<StackNavigation<Config>, Value<ChildStack<Config, Config>>> {
        val ctx = DefaultComponentContext(LifecycleRegistry())
        val nav = StackNavigation<Config>()
        val stack = ctx.childStack(
            source = nav,
            serializer = Config.serializer(),
            initialConfiguration = initial,
            handleBackButton = true,
            childFactory = { config, _ -> config }
        )
        return nav to stack
    }

    @Test
    fun cancelling_the_add_picker_reveals_the_picker_that_requested_it() {
        val (nav, stack) = buildStack(Config.Picker(mode = "cold"))

        // Mirrors the fixed onAddNewBatteryRequested.
        nav.push(Config.Picker(mode = "add"))
        assertEquals(Config.Picker(mode = "add"), stack.value.active.configuration)

        // Mirrors onCancelled (and, via shouldPopOnBack, system back too).
        nav.pop()
        assertEquals(
            Config.Picker(mode = "cold"),
            stack.value.active.configuration,
            "cancelling the add-picker must return to the picker that requested it, not strand the user"
        )
    }

    @Test
    fun first_add_from_welcome_enters_the_setup_wizard() {
        val (nav, stack) = buildStack(Config.Welcome)

        // Mirrors Welcome's production callback: first creation is a draft-owned
        // wizard, not the legacy one-device picker.
        nav.push(Config.SetupWizard(prefillFromActiveConnection = false))
        assertEquals(
            Config.SetupWizard(prefillFromActiveConnection = false),
            stack.value.active.configuration
        )
    }

    @Test
    fun create_prefill_ignores_a_saved_active_vehicle_but_keeps_a_guest_connection() {
        val saved = vehicle()
        val guest = saved.copy(id = "guest:AA:BB", name = "Guest BMS")

        assertNull(
            activeVehicleForCreatePrefill(saved, enabled = true),
            "adding a vehicle must not clone the saved vehicle that is currently active"
        )
        assertEquals(
            guest,
            activeVehicleForCreatePrefill(guest, enabled = true),
            "an unsaved guest connection is still a valid starting point for the wizard"
        )
        assertNull(activeVehicleForCreatePrefill(guest, enabled = false))
    }

    @Test
    fun the_old_replaceAll_wiring_would_have_left_cancel_and_back_dead() {
        // Documents the bug this fix removes, on the real stack primitives:
        // replaceAll collapses the stack to ONE entry, so the very next pop()
        // has nothing to reveal and the active configuration does not move —
        // exactly the "Cancel does nothing, system back does nothing" report.
        val (nav, stack) = buildStack(Config.Picker(mode = "cold"))

        nav.replaceAll(Config.Picker(mode = "add"))
        assertEquals(1, stack.value.items.size, "replaceAll collapses the whole stack to the add-picker alone")

        nav.pop()
        assertEquals(
            Config.Picker(mode = "add"),
            stack.value.active.configuration,
            "pop() on a single-entry stack is a no-op — the dead-end this task fixes"
        )
    }

    @Test
    fun deleting_a_picker_created_editor_replaces_it_with_home_so_its_save_is_gone() {
        val (nav, stack) = buildStack(Config.Picker(mode = "cold"))
        val editor = Config.VehicleEdit(vehicleId = "v1")

        // Picker connection success deliberately replaces its route with the
        // editor. That leaves exactly one entry, which is the shape deletion
        // must exit: keeping this editor active leaves its Save control able
        // to recreate the row which was just deleted.
        nav.replaceAll(editor)
        assertEquals(listOf(editor), stack.value.items.map { it.configuration })

        // Mirrors RootComponent's onDeleted callback. Its exit is deliberately
        // the same total operation as Cancel, rather than a bare `pop()`.
        leaveVehicleEdit(nav, stack.value.items.size, Config.Dashboard)

        assertEquals(
            Config.Dashboard,
            stack.value.active.configuration,
            "deletion must remove the picker-created editor, not leave its Save control live"
        )
        assertEquals(listOf(Config.Dashboard), stack.value.items.map { it.configuration })
    }

    // ----- G2 Task 3: navigating to somewhere already in the stack -----

    /**
     * **The crash this rule exists to prevent, demonstrated rather than
     * asserted.** Decompose 3.4.0 `check`s that configurations are unique
     * (`ChildrenNavigator.switchDefault`) and
     * `DecomposeExperimentFlags.duplicateConfigurationsEnabled` is false and set
     * nowhere in this repo, so a second `push` of a live configuration throws
     * and takes the app — and any unsaved form on it — down.
     *
     * The stack built here is the exact one the composer's units link produced:
     * Settings → vehicle list → Edit is the **primary** route to that form, so
     * `Config.Settings` is already one entry below when the link is tapped.
     */
    @Test
    fun a_second_push_of_a_live_configuration_throws() {
        val (nav, stack) = buildStack(Config.Dashboard)
        nav.push(Config.Settings)
        nav.push(Config.VehicleEdit(vehicleId = "v1"))
        assertEquals(3, stack.value.items.size)

        assertFailsWith<IllegalStateException>("pushing a duplicate must be what kills the app") {
            nav.push(Config.Settings)
        }
    }

    /**
     * The same tap through [stackAfterGoTo] — every `push` in
     * `DefaultRootComponent.createChild` now goes through it. The entry is
     * relocated, the form underneath survives, and one back reveals it again.
     */
    @Test
    fun navigating_to_a_live_configuration_relocates_it_and_keeps_what_is_underneath() {
        val (nav, stack) = buildStack(Config.Dashboard)
        nav.navigate { stackAfterGoTo(it, Config.Settings) }
        nav.navigate { stackAfterGoTo(it, Config.VehicleEdit(vehicleId = "v1")) }

        // The units link, on the stack that used to crash.
        nav.navigate { stackAfterGoTo(it, Config.Settings) }
        assertEquals(
            listOf(Config.Dashboard, Config.VehicleEdit(vehicleId = "v1"), Config.Settings),
            stack.value.items.map { it.configuration },
            "Settings moves to the top; the half-filled form stays in the stack"
        )

        nav.pop()
        assertEquals(
            Config.VehicleEdit(vehicleId = "v1"),
            stack.value.active.configuration,
            "and back reveals the form again, not a home screen"
        )
    }

    /** The rule itself: append when absent, relocate when present, never duplicate. */
    @Test
    fun the_navigation_rule_appends_when_absent_and_relocates_when_present() {
        assertEquals(
            listOf(Config.Dashboard, Config.Settings),
            stackAfterGoTo(listOf(Config.Dashboard), Config.Settings),
            "absent: exactly what push() did, so no existing route changes shape"
        )
        assertEquals(
            listOf(Config.Dashboard, Config.Graph, Config.Settings),
            stackAfterGoTo(listOf(Config.Dashboard, Config.Settings, Config.Graph), Config.Settings),
            "present: moved to the top, and everything else keeps its order"
        )
        assertEquals(
            listOf(Config.Settings, Config.Graph, Config.Dashboard),
            stackAfterGoTo(listOf(Config.Dashboard, Config.Settings, Config.Graph), Config.Dashboard),
            "including the entry at the bottom — the Dashboard a tab would bring forward"
        )
    }
}
