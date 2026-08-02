package ru.sodovaya.volty.presentation.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.bringToFront
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.navigate
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.bmsAddressOrNull
import ru.sodovaya.volty.domain.model.bmsTypeOrNull
import ru.sodovaya.volty.domain.model.hasControllers
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.model.isGuest
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.CanDiscovery
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.permissions.PermissionsChecker
import ru.sodovaya.volty.presentation.alerts.DefaultVehicleAlertsComponent
import ru.sodovaya.volty.presentation.alerts.VehicleAlertsComponent
import ru.sodovaya.volty.presentation.autoconnect.AutoConnectComponent
import ru.sodovaya.volty.presentation.autoconnect.DefaultAutoConnectComponent
import ru.sodovaya.volty.presentation.dashboard.DashboardComponent
import ru.sodovaya.volty.presentation.dashboard.DefaultDashboardComponent
import ru.sodovaya.volty.presentation.graph.DefaultGraphComponent
import ru.sodovaya.volty.presentation.graph.GraphComponent
import ru.sodovaya.volty.presentation.permissions.DefaultPermissionsGateComponent
import ru.sodovaya.volty.presentation.permissions.PermissionsGateComponent
import ru.sodovaya.volty.presentation.pack.DefaultPackDetailComponent
import ru.sodovaya.volty.presentation.pack.PackDetailComponent
import ru.sodovaya.volty.presentation.picker.DefaultPickerComponent
import ru.sodovaya.volty.presentation.picker.PickerComponent
import ru.sodovaya.volty.presentation.ride.DefaultRideDashboardComponent
import ru.sodovaya.volty.presentation.ride.RideDashboardComponent
import ru.sodovaya.volty.presentation.scanning.DefaultScanningComponent
import ru.sodovaya.volty.presentation.scanning.ScanningComponent
import ru.sodovaya.volty.presentation.settings.DefaultSettingsComponent
import ru.sodovaya.volty.presentation.settings.SettingsComponent
import ru.sodovaya.volty.presentation.vehicle.DefaultVehicleEditComponent
import ru.sodovaya.volty.presentation.vehicle.VehicleEditComponent
import ru.sodovaya.volty.presentation.welcome.DefaultWelcomeComponent
import ru.sodovaya.volty.presentation.welcome.WelcomeComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject

interface RootComponent {
    val stack: Value<ChildStack<*, Child>>

    /**
     * True when the active vehicle has a motor controller. Drives the Ride
     * tab's visibility: a pure-BMS vehicle never sees it, so its experience is
     * exactly the pre-Ride Battery + Settings one.
     */
    val rideAvailable: Value<Boolean>

    fun onBack()
    fun onTab(tab: Tab)

    enum class Tab { Ride, Battery, Settings }

    sealed interface Child {
        /** Transient cold-start state while we read the saved-vehicle DB. */
        data object Loading : Child
        data class Welcome(val component: WelcomeComponent) : Child
        data class Permissions(val component: PermissionsGateComponent) : Child
        data class Scanning(val component: ScanningComponent) : Child
        data class AutoConnect(val component: AutoConnectComponent) : Child
        data class Picker(val component: PickerComponent) : Child
        data class Ride(val component: RideDashboardComponent) : Child
        data class Dashboard(val component: DashboardComponent) : Child
        data class PackDetail(val component: PackDetailComponent) : Child
        data class VehicleEdit(val component: VehicleEditComponent) : Child
        data class VehicleAlerts(val component: VehicleAlertsComponent) : Child
        data class Graph(val component: GraphComponent) : Child
        data class Settings(val component: SettingsComponent) : Child
    }
}

@Serializable
sealed class Config {
    @Serializable data object Loading : Config()
    @Serializable data object Welcome : Config()
    @Serializable data object Permissions : Config()
    @Serializable data object Scanning : Config()
    @Serializable data class AutoConnect(val vehicleId: String) : Config()
    @Serializable data class Picker(val mode: String) : Config()
    /** The Ride dashboard — home for any vehicle that has a motor controller. */
    @Serializable data object Ride : Config()
    /** The battery dashboard — home for a pure-BMS vehicle, Battery tab otherwise. */
    @Serializable data object Dashboard : Config()
    @Serializable data class PackDetail(val packIndex: Int) : Config()
    @Serializable data class VehicleEdit(
        val vehicleId: String?,
        /**
         * When true and [vehicleId] is null, the edit form prefills BMS type /
         * address / name from the currently active connection (e.g. the user
         * tapped "+ Add" while a guest connection is live, so we can capture
         * the device they're already talking to).
         */
        val prefillFromActiveConnection: Boolean = false
    ) : Config()
    /**
     * The per-vehicle alert settings screen (F Task 9). Pushed from
     * [VehicleEdit], so [vehicleId] is always a saved vehicle — a vehicle that
     * does not exist yet has nothing to configure alerts on.
     */
    @Serializable data class VehicleAlerts(val vehicleId: String) : Config()
    @Serializable data object Graph : Config()
    @Serializable data object Settings : Config()
}

/**
 * The app's home destination for [vehicle] — the single routing rule behind
 * every post-connect landing, the back-out target of Graph/Settings, and the
 * Ride tab's visibility.
 *
 * A vehicle with a motor controller is a *vehicle*, so it lands on the Ride
 * dashboard. A pure-BMS vehicle (and "no vehicle at all") keeps the battery
 * dashboard it has always had.
 *
 * Extracted as a pure function so it can be unit-tested without standing up
 * Decompose's [ComponentContext] and Koin: see `RootNavigationTest`.
 */
internal fun homeConfigFor(vehicle: Vehicle?): Config =
    if (vehicle?.hasControllers == true) Config.Ride else Config.Dashboard

/**
 * Destination of each bottom-bar tab. Pure for the same reason as
 * [homeConfigFor] — the Battery tab must keep reaching the existing
 * [Config.Dashboard], and that is worth pinning in a test.
 */
internal fun configForTab(tab: RootComponent.Tab): Config = when (tab) {
    RootComponent.Tab.Ride -> Config.Ride
    RootComponent.Tab.Battery -> Config.Dashboard
    RootComponent.Tab.Settings -> Config.Settings
}

/**
 * True when the active vehicle no longer belongs on the Ride dashboard but
 * [stackConfigs] still holds one, so the root must re-route home.
 *
 * Inspects the WHOLE stack, not just its active entry: tapping Battery from
 * Ride leaves `[Ride, Dashboard]`, and switching to a controller-less vehicle
 * from the battery dashboard's own sheet would otherwise leave `Config.Ride`
 * buried underneath — one system back and the user is on a Ride dashboard with
 * no motion source and no Ride tab to escape by.
 *
 * A null [vehicle] is deliberately NOT a trigger. It is the transient gap
 * inside a vehicle switch (`disconnect()` clears `activeVehicle` before
 * `connect()` sets the next one) and the steady state after a real disconnect.
 * Reacting to it would bounce a Ride -> Ride vehicle switch onto the battery
 * dashboard, and would race the disconnect path, which routes to Scanning by
 * itself. Waiting for the real vehicle costs nothing: the guard only has to win
 * before the user presses back.
 */
internal fun shouldLeaveRide(vehicle: Vehicle?, stackConfigs: List<*>): Boolean {
    if (vehicle == null) return false
    return homeConfigFor(vehicle) !is Config.Ride && stackConfigs.any { it is Config.Ride }
}

/**
 * The stack after navigating to [config]: the entry moved to the top if it is
 * already in the stack, appended if it is not. **Never a duplicate.**
 *
 * That is not a nicety, it is the difference between navigating and crashing.
 * Decompose 3.4.0's `ChildrenNavigator.switchDefault` does
 * `check(newConfigurations.size == newStates.size) { "Configurations must be
 * unique: …" }`, and `DecomposeExperimentFlags.duplicateConfigurationsEnabled`
 * defaults to false and is set nowhere in this repo — so pushing a
 * configuration the stack already holds throws `IllegalStateException` and
 * takes the app down, along with whatever unsaved work was on screen.
 *
 * Until Part G2 that was unreachable by accident, because every `push` target
 * was a leaf nothing could navigate back out of *into itself*. G2 Task 3 gave
 * the vehicle form a link to **Settings** (`G §4`, the app-wide km/mi setting),
 * and the primary route to that form is *Settings → vehicle list → Edit* — so
 * `Config.Settings` is already one entry below when the link is tapped. One
 * tap, first try, no preconditions. Two more of the same shape follow from it
 * (Settings → "+ Add battery", and Settings → Edit the vehicle already open),
 * which is why every `push` in `createChild` now goes through here rather than
 * just the one that exposed it.
 *
 * Relocation, not replacement: Decompose keeps a retained instance for a
 * configuration that stays in the stack, so a half-filled form moved down the
 * stack is still alive and is one back-press away.
 *
 * Pure so it can be tested — see the note on [RootNavigationTest] about why a
 * live [DefaultRootComponent] is not reachable from `commonTest`.
 */
internal fun stackAfterGoTo(stack: List<Config>, config: Config): List<Config> =
    stack.filterNot { it == config } + config

/**
 * Whether system [onBack][DefaultRootComponent.onBack] should `pop()` the
 * current entry rather than collapse the whole stack with `replaceAll`.
 *
 * Graph and Settings are reached by [stackAfterGoTo] on top of some other
 * entry, so popping — exactly what those screens' own ‹ buttons already do
 * (`onBackRequested = { nav.pop() }`) — reveals that SAME component instance
 * instead of destroying and rebuilding it. That is worth pinning: `replaceAll`
 * was resetting live Ride state (session uptime, session max speed, the
 * sparkline) and the Battery tab's scroll position on every system back out of
 * Graph/Settings, while the in-screen button left all of it alone — the
 * disagreement this function removes.
 *
 * It used to say "only ever reached by `push` on top of a **home** entry (Ride
 * or Dashboard)". G2 Task 3 broke that: the vehicle form now links to Settings,
 * so `[Dashboard, VehicleEdit, Settings]` is reachable and the entry revealed
 * by a pop is the form, not a home screen. The rule below does not depend on
 * which — it only needs something underneath to pop to — but the invariant it
 * was written against is gone, and a later reader must not rely on it.
 *
 * `replaceAll(homeConfig())` remains the answer for every other case: from
 * any OTHER screen it is still correct to just pop, and the [stackSize] <= 1
 * branch is a defensive fallback for a Graph/Settings entry with nothing
 * beneath it to pop to (not reachable today — every push onto them lands on
 * top of a home entry — but it keeps [onBack][DefaultRootComponent.onBack]
 * total rather than relying on that invariant).
 *
 * This does NOT duplicate [shouldLeaveRide]'s job: that guard reacts to a
 * vehicle losing its controller and already collapses the stack the moment
 * it happens (see the `activeVehicle` collector in [DefaultRootComponent]),
 * independent of when — or whether — the user presses back. By the time
 * back is pressed, if the vehicle really left Ride, [shouldLeaveRide] will
 * already have replaced the stack; this function only decides HOW to leave
 * Graph/Settings in the ordinary case.
 */
internal fun shouldPopOnBack(current: Any?, stackSize: Int): Boolean =
    (current !is Config.Graph && current !is Config.Settings) || stackSize > 1

/**
 * Give a retained vehicle composer first refusal over an action that destroys
 * the root stack.
 *
 * The child index, rather than a configuration reconstructed from its fields,
 * is returned to [revealComposerAt]: the caller uses the matching retained
 * stack entry, so the exact component holding the draft is brought to the top.
 * Clean composers do not move and do not prompt.
 */
internal fun guardComposerDestruction(
    children: List<RootComponent.Child>,
    revealComposerAt: (Int) -> Unit,
    destroyStack: () -> Unit,
    approved: Set<VehicleEditComponent> = emptySet()
) {
    val index = children.indexOfLast {
        it is RootComponent.Child.VehicleEdit &&
            it.component.state.value.isDirty &&
            it.component !in approved
    }
    if (index < 0) {
        destroyStack()
        return
    }
    val editor = (children[index] as RootComponent.Child.VehicleEdit).component
    revealComposerAt(index)
    editor.requestExit {
        // A stack may retain editors for two different vehicles. Confirmation
        // approves this instance for THIS requested destruction only. Recurse
        // so every older dirty draft gets a visible decision; a dismissal ends
        // the closure chain and a later request starts with no approvals.
        guardComposerDestruction(children, revealComposerAt, destroyStack, approved + editor)
    }
}

/**
 * Leave the active vehicle editor through real Decompose navigation.
 *
 * A form pushed over another entry returns to it. Picker connection success,
 * however, deliberately replaces the stack with a one-entry editor; `pop()` is
 * inert there, so its actual exit is current home.
 */
internal fun leaveVehicleEdit(
    navigation: StackNavigation<Config>,
    stackSize: Int,
    home: Config
) {
    if (stackSize > 1) navigation.pop() else navigation.replaceAll(home)
}

class DefaultRootComponent(
    componentContext: ComponentContext
) : RootComponent, ComponentContext by componentContext, KoinComponent {

    private val nav = StackNavigation<Config>()

    private val vehicleRepository: VehicleRepository by inject()
    private val bmsRepository: BmsRepository by inject()
    private val permissionsChecker: PermissionsChecker by inject()

    // Lightweight scope for cold-start async work (DB reads). Previously these
    // ran via runBlocking on the UI thread — risky on slow devices.
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _rideAvailable =
        MutableValue(homeConfigFor(bmsRepository.activeVehicle.value) is Config.Ride)
    override val rideAvailable: Value<Boolean> = _rideAvailable

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }
    }

    override val stack: Value<ChildStack<*, RootComponent.Child>> = childStack(
        source = nav,
        serializer = Config.serializer(),
        initialConfiguration = computeInitialConfigSync(),
        handleBackButton = true,
        childFactory = ::createChild
    )

    init {
        // If we started on Loading (no synchronous answer was available),
        // resolve the real start destination off the UI thread.
        if (stack.value.active.configuration is Config.Loading) {
            scope.launch { resolveStartDestination() }
        }

        // The same rule as homeConfig(), followed live: switching vehicles from
        // either dashboard's sheet must show/hide the Ride tab without waiting
        // for a re-navigation. Declared after `stack` because it reads it.
        scope.launch {
            bmsRepository.activeVehicle.collect { v ->
                val home = homeConfigFor(v)
                _rideAvailable.value = home is Config.Ride
                // Switching to a controller-less vehicle must not leave a Ride
                // entry anywhere in the stack — see [shouldLeaveRide].
                if (shouldLeaveRide(v, stack.value.items.map { it.configuration })) {
                    replaceAll(home)
                }
            }
        }
    }

    /**
     * Navigate to [config] — **the only way this component adds an entry**, and
     * the replacement for every `nav.push`. See [stackAfterGoTo] for why a
     * second push of a live configuration is a crash rather than a duplicate,
     * and why a link from the vehicle form to Settings made that reachable in
     * one tap.
     *
     * On a stack that does not already hold [config] this is exactly `push`, so
     * no existing route changes shape.
     */
    private fun goTo(config: Config) {
        nav.navigate { stack -> stackAfterGoTo(stack, config) }
    }

    /** Every root stack replacement passes through the buried-composer guard. */
    private fun replaceAll(config: Config) {
        val entries = stack.value.items
        guardComposerDestruction(
            children = entries.map { it.instance },
            revealComposerAt = { index -> goTo(entries[index].configuration as Config.VehicleEdit) },
            destroyStack = { nav.replaceAll(config) }
        )
    }

    /**
     * Where "home" is right now. Every post-connect landing goes through here
     * so a controller vehicle lands on Ride and a pure-BMS one on the battery
     * dashboard, from one rule rather than five copies of it.
     */
    private fun homeConfig(): Config = homeConfigFor(bmsRepository.activeVehicle.value)

    override fun onBack() {
        val current = stack.value.active.configuration
        // Graph and Settings are leaves off a home screen (Ride or Dashboard) —
        // popping reveals that SAME home instance, matching their own ‹ buttons
        // (see Config.Graph / Config.Settings in createChild) and keeping live
        // Ride state alive. See [shouldPopOnBack] for why this doesn't need to
        // duplicate [shouldLeaveRide]'s job.
        if (shouldPopOnBack(current, stack.value.items.size)) nav.pop() else replaceAll(homeConfig())
    }

    override fun onTab(tab: RootComponent.Tab) {
        nav.bringToFront(configForTab(tab))
    }

    /**
     * Synchronous portion of start-destination resolution. Permissions are
     * cheap to check, so we do that inline. The DB read (saved-vehicle count)
     * is deferred to [resolveStartDestination] running in [scope] — until it
     * completes we render [Config.Loading] (a tiny splash).
     */
    private fun computeInitialConfigSync(): Config {
        if (permissionsChecker.missingPermissions().isNotEmpty()) return Config.Permissions
        return Config.Loading
    }

    private suspend fun resolveStartDestination() {
        val savedCount = vehicleRepository.vehicles.first().size
        replaceAll(if (savedCount == 0) Config.Welcome else Config.Scanning)
    }

    /**
     * Start "Try demo" mode from Welcome: spin up the simulated connection off
     * the UI thread, then replace the stack with the home screen so the user
     * lands straight on live (synthetic) data. The demo vehicle carries a
     * synthetic controller, so [homeConfig] puts it on Ride. Never persisted.
     */
    private fun startDemo(profile: DemoProfile) {
        scope.launch {
            bmsRepository.connectDemo(profile)
            replaceAll(homeConfig())
        }
    }

    private fun onPermissionsGranted() {
        // After permissions are granted, recompute the post-permissions route
        // (Welcome vs Scanning) off the UI thread. Show the loading splash in
        // the meantime so the screen isn't blank.
        replaceAll(Config.Loading)
        scope.launch { resolveStartDestination() }
    }

    @OptIn(DelicateDecomposeApi::class)
    private fun createChild(config: Config, context: ComponentContext): RootComponent.Child =
        when (config) {
            is Config.Loading -> RootComponent.Child.Loading
            is Config.Welcome -> RootComponent.Child.Welcome(
                DefaultWelcomeComponent(
                    componentContext = context,
                    // Welcome is only ever shown when permissions are already granted (gated by computeInitialConfig),
                    // so these buttons can route directly to the Picker without re-checking.
                    // push(), not replaceAll(): Welcome stays underneath so the
                    // add-picker's Cancel / system back (nav.pop()) has
                    // something to reveal instead of stranding the user — see
                    // the matching fix on Config.Picker's onAddNewBatteryRequested
                    // below, which had the identical dead-end bug.
                    onAddBatteryRequested = { goTo(Config.Picker(mode = "add")) },
                    onQuickConnectRequested = { replaceAll(Config.Picker(mode = "guest")) },
                    onTryDemoRequested = { profile -> startDemo(profile) }
                )
            )
            is Config.Permissions -> RootComponent.Child.Permissions(
                DefaultPermissionsGateComponent(
                    componentContext = context,
                    checker = get<PermissionsChecker>(),
                    onAllGrantedRequested = { onPermissionsGranted() }
                )
            )
            is Config.Scanning -> RootComponent.Child.Scanning(
                DefaultScanningComponent(
                    componentContext = context,
                    bmsRepository = get(),
                    vehicleRepository = get(),
                    appPrefs = get<AppPrefs>(),
                    onSingleKnown = { vehicleId -> replaceAll(Config.AutoConnect(vehicleId)) },
                    onMultipleOrNone = { replaceAll(Config.Picker(mode = "cold")) }
                )
            )
            is Config.AutoConnect -> RootComponent.Child.AutoConnect(
                DefaultAutoConnectComponent(
                    componentContext = context,
                    vehicleId = config.vehicleId,
                    bmsRepository = get(),
                    vehicleRepository = get(),
                    appPrefs = get<AppPrefs>(),
                    onConnected = { replaceAll(homeConfig()) },
                    onCancelled = { replaceAll(Config.Picker(mode = "cold")) }
                )
            )
            is Config.Picker -> RootComponent.Child.Picker(
                DefaultPickerComponent(
                    componentContext = context,
                    mode = config.mode,
                    bmsRepository = get(),
                    vehicleRepository = get(),
                    onConnectedKnown = { replaceAll(homeConfig()) },
                    onConnectedForEdit = { vehicleId -> replaceAll(Config.VehicleEdit(vehicleId)) },
                    onConnectedGuestNoSave = { replaceAll(homeConfig()) },
                    // push(), not replaceAll(): replaceAll wiped the whole stack
                    // down to this one entry, so onCancelled's nav.pop() below —
                    // and system back, which routes here through the same
                    // pop() (see shouldPopOnBack) — had nothing left to reveal
                    // and the user was stranded on the add-picker with a dead
                    // Cancel and a dead back button. Pushing keeps whatever
                    // picker/screen asked for "add new battery" underneath, so
                    // cancelling returns to it. The add-picker itself never
                    // offers this same button again (PickerScreen.kt only
                    // renders "+ Add new battery" when `state.mode != "add"`),
                    // so this can't be used to push an unbounded run of
                    // Picker(mode = "add") entries onto the stack.
                    onAddNewBatteryRequested = { goTo(Config.Picker(mode = "add")) },
                    onDemoConnected = { replaceAll(homeConfig()) },
                    onCancelled = { nav.pop() }
                )
            )
            is Config.Ride -> RootComponent.Child.Ride(
                DefaultRideDashboardComponent(
                    componentContext = context,
                    bmsRepository = get(),
                    vehicleRepository = get(),
                    appPrefs = get<AppPrefs>(),
                    onOpenGraphRequested = { goTo(Config.Graph) },
                    onOpenSettingsRequested = { goTo(Config.Settings) },
                    // Mirrors Config.Dashboard's onOpenAddBattery: the sheet's
                    // "+ Add" captures the live connection into a new vehicle.
                    onAddVehicleRequested = {
                        goTo(Config.VehicleEdit(vehicleId = null, prefillFromActiveConnection = true))
                    },
                    onDisconnectRequested = { replaceAll(Config.Scanning) }
                )
            )
            is Config.Dashboard -> RootComponent.Child.Dashboard(
                DefaultDashboardComponent(
                    componentContext = context,
                    bmsRepository = get(),
                    vehicleRepository = get(),
                    onOpenGraphRequested = { goTo(Config.Graph) },
                    onOpenSettings = { goTo(Config.Settings) },
                    onOpenAddBattery = {
                        goTo(Config.VehicleEdit(vehicleId = null, prefillFromActiveConnection = true))
                    },
                    onOpenPackDetail = { packIndex -> goTo(Config.PackDetail(packIndex)) },
                    onDisconnectRequested = { replaceAll(Config.Scanning) }
                )
            )
            is Config.PackDetail -> RootComponent.Child.PackDetail(
                DefaultPackDetailComponent(
                    componentContext = context,
                    packIndex = config.packIndex,
                    bmsRepository = get(),
                    onBackRequested = { nav.pop() }
                )
            )
            is Config.VehicleEdit -> {
                // Optionally prefill BMS type / address / name from the currently
                // active connection. Only applies when creating a new vehicle
                // (vehicleId == null) and the caller asked for it. Guest names
                // get the synthetic "Guest " prefix stripped (see KableBmsRepository).
                val prefillVehicle = if (config.vehicleId == null && config.prefillFromActiveConnection) {
                    // Never prefill from the demo connection — the synthetic
                    // "demo" device/address must not leak into a saved vehicle.
                    bmsRepository.activeVehicle.value?.takeUnless { it.isDemo }
                } else null
                val prefilledName = prefillVehicle?.name
                    ?.let { if (prefillVehicle.isGuest && it == "Guest BMS") null else it }
                RootComponent.Child.VehicleEdit(
                    DefaultVehicleEditComponent(
                        componentContext = context,
                        vehicleId = config.vehicleId,
                        vehicleRepository = get(),
                        bmsRepository = get(),
                        onSaved = { replaceAll(homeConfig()) },
                        onCancelled = {
                            leaveVehicleEdit(nav, stack.value.items.size, homeConfig())
                        },
                        onDeleted = { nav.pop() },
                        // Both prefills are already optional, so a source-less
                        // (controller-only) active connection simply prefills
                        // nothing instead of throwing.
                        prefilledBmsType = prefillVehicle?.bmsTypeOrNull,
                        prefilledBmsAddress = prefillVehicle?.bmsAddressOrNull,
                        prefilledName = prefilledName,
                        // push(), so the half-filled edit form stays alive
                        // underneath and comes back with its unsaved fields
                        // intact. Only reachable for a SAVED vehicle — the
                        // alerts screen persists onto a row that must exist, and
                        // VehicleEditScreen hides the entry while creating.
                        onOpenAlertsRequested = {
                            config.vehicleId?.let { id -> goTo(Config.VehicleAlerts(id)) }
                        },
                        // The km/mi setting is app-wide (B §9), so the composer
                        // links to the one screen that owns it instead of
                        // growing a per-vehicle copy (G §4). push() for the same
                        // reason as the alerts screen: the half-filled form
                        // stays alive underneath and comes back intact, and
                        // shouldPopOnBack() already pops a Settings entry that
                        // has something beneath it.
                        onOpenUnitsRequested = { goTo(Config.Settings) },
                        // The SAME instance the connection runs on — `appModule`
                        // binds KableBmsRepository to both interfaces, because a
                        // second one would have no live links to scan (G2 Task 5).
                        canDiscovery = get<CanDiscovery>()
                    )
                )
            }
            is Config.VehicleAlerts -> RootComponent.Child.VehicleAlerts(
                DefaultVehicleAlertsComponent(
                    componentContext = context,
                    vehicleId = config.vehicleId,
                    vehicleRepository = get(),
                    bmsRepository = get(),
                    appPrefs = get<AppPrefs>(),
                    alarmPreview = get(),
                    onSaved = { nav.pop() },
                    onBackRequested = { nav.pop() }
                )
            )
            is Config.Graph -> RootComponent.Child.Graph(
                DefaultGraphComponent(
                    componentContext = context,
                    bmsRepository = get(),
                    onBackRequested = { nav.pop() }
                )
            )
            is Config.Settings -> RootComponent.Child.Settings(
                DefaultSettingsComponent(
                    componentContext = context,
                    appPrefs = get<AppPrefs>(),
                    vehicleRepository = get(),
                    logExporter = get(),
                    onEditVehicleRequested = { id -> goTo(Config.VehicleEdit(id)) },
                    onAddBatteryRequested = {
                        goTo(Config.VehicleEdit(vehicleId = null, prefillFromActiveConnection = true))
                    },
                    onBackRequested = { nav.pop() }
                )
            )
        }
}
