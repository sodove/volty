package ru.sodovaya.volty.presentation.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.bringToFront
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
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
 * Whether system [onBack][DefaultRootComponent.onBack] should `pop()` the
 * current entry rather than collapse the whole stack with `replaceAll`.
 *
 * Graph and Settings are only ever reached by `push` on top of a home entry
 * (Ride or Dashboard), so popping — exactly what those screens' own ‹ buttons
 * already do (`onBackRequested = { nav.pop() }`) — reveals that SAME home
 * component instance instead of destroying and rebuilding it. That is worth
 * pinning: `replaceAll` was resetting live Ride state (session uptime,
 * session max speed, the sparkline) and the Battery tab's scroll position on
 * every system back out of Graph/Settings, while the in-screen button left
 * all of it alone — the disagreement this function removes.
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
                    nav.replaceAll(home)
                }
            }
        }
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
        if (shouldPopOnBack(current, stack.value.items.size)) nav.pop() else nav.replaceAll(homeConfig())
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
        nav.replaceAll(if (savedCount == 0) Config.Welcome else Config.Scanning)
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
            nav.replaceAll(homeConfig())
        }
    }

    private fun onPermissionsGranted() {
        // After permissions are granted, recompute the post-permissions route
        // (Welcome vs Scanning) off the UI thread. Show the loading splash in
        // the meantime so the screen isn't blank.
        nav.replaceAll(Config.Loading)
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
                    onAddBatteryRequested = { nav.push(Config.Picker(mode = "add")) },
                    onQuickConnectRequested = { nav.replaceAll(Config.Picker(mode = "guest")) },
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
                    onSingleKnown = { vehicleId -> nav.replaceAll(Config.AutoConnect(vehicleId)) },
                    onMultipleOrNone = { nav.replaceAll(Config.Picker(mode = "cold")) }
                )
            )
            is Config.AutoConnect -> RootComponent.Child.AutoConnect(
                DefaultAutoConnectComponent(
                    componentContext = context,
                    vehicleId = config.vehicleId,
                    bmsRepository = get(),
                    vehicleRepository = get(),
                    appPrefs = get<AppPrefs>(),
                    onConnected = { nav.replaceAll(homeConfig()) },
                    onCancelled = { nav.replaceAll(Config.Picker(mode = "cold")) }
                )
            )
            is Config.Picker -> RootComponent.Child.Picker(
                DefaultPickerComponent(
                    componentContext = context,
                    mode = config.mode,
                    bmsRepository = get(),
                    vehicleRepository = get(),
                    onConnectedKnown = { nav.replaceAll(homeConfig()) },
                    onConnectedForEdit = { vehicleId -> nav.replaceAll(Config.VehicleEdit(vehicleId)) },
                    onConnectedGuestNoSave = { nav.replaceAll(homeConfig()) },
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
                    onAddNewBatteryRequested = { nav.push(Config.Picker(mode = "add")) },
                    onDemoConnected = { nav.replaceAll(homeConfig()) },
                    onCancelled = { nav.pop() }
                )
            )
            is Config.Ride -> RootComponent.Child.Ride(
                DefaultRideDashboardComponent(
                    componentContext = context,
                    bmsRepository = get(),
                    vehicleRepository = get(),
                    appPrefs = get<AppPrefs>(),
                    onOpenGraphRequested = { nav.push(Config.Graph) },
                    onOpenSettingsRequested = { nav.push(Config.Settings) },
                    // Mirrors Config.Dashboard's onOpenAddBattery: the sheet's
                    // "+ Add" captures the live connection into a new vehicle.
                    onAddVehicleRequested = {
                        nav.push(Config.VehicleEdit(vehicleId = null, prefillFromActiveConnection = true))
                    },
                    onDisconnectRequested = { nav.replaceAll(Config.Scanning) }
                )
            )
            is Config.Dashboard -> RootComponent.Child.Dashboard(
                DefaultDashboardComponent(
                    componentContext = context,
                    bmsRepository = get(),
                    vehicleRepository = get(),
                    onOpenGraphRequested = { nav.push(Config.Graph) },
                    onOpenSettings = { nav.push(Config.Settings) },
                    onOpenAddBattery = {
                        nav.push(Config.VehicleEdit(vehicleId = null, prefillFromActiveConnection = true))
                    },
                    onOpenPackDetail = { packIndex -> nav.push(Config.PackDetail(packIndex)) },
                    onDisconnectRequested = { nav.replaceAll(Config.Scanning) }
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
                        onSaved = { nav.replaceAll(homeConfig()) },
                        onCancelled = { nav.pop() },
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
                            config.vehicleId?.let { id -> nav.push(Config.VehicleAlerts(id)) }
                        }
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
                    onEditVehicleRequested = { id -> nav.push(Config.VehicleEdit(id)) },
                    onAddBatteryRequested = {
                        nav.push(Config.VehicleEdit(vehicleId = null, prefillFromActiveConnection = true))
                    },
                    onBackRequested = { nav.pop() }
                )
            )
        }
}
