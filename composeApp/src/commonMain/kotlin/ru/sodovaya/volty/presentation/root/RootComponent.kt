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
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageRepository
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.bmsAddressOrNull
import ru.sodovaya.volty.domain.model.bmsTypeOrNull
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.model.isGuest
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.CanDiscovery
import ru.sodovaya.volty.data.bms.ControllerConfigSource
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.repository.RideHistoryRepository
import ru.sodovaya.volty.permissions.PermissionsChecker
import ru.sodovaya.volty.presentation.alerts.DefaultVehicleAlertsComponent
import ru.sodovaya.volty.presentation.alerts.VehicleAlertsComponent
import ru.sodovaya.volty.presentation.autoconnect.AutoConnectComponent
import ru.sodovaya.volty.presentation.autoconnect.DefaultAutoConnectComponent
import ru.sodovaya.volty.presentation.dashboard.DashboardComponent
import ru.sodovaya.volty.presentation.dashboard.DefaultDashboardComponent
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
import ru.sodovaya.volty.presentation.nearby.DefaultVoiceSpacesNearbyComponent
import ru.sodovaya.volty.presentation.root.nearby.NearbyRootPresentation
import ru.sodovaya.volty.presentation.root.nearby.NearbyRootPresenter
import ru.sodovaya.volty.presentation.navigation.DefaultLightNavigationComponent
import ru.sodovaya.volty.presentation.navigation.LightNavigationComponent
import ru.sodovaya.volty.presentation.vehicle.DefaultVehicleEditComponent
import ru.sodovaya.volty.presentation.vehicle.DraftExitComponent
import ru.sodovaya.volty.presentation.vehicle.VehicleDraft
import ru.sodovaya.volty.presentation.vehicle.VehicleEditComponent
import ru.sodovaya.volty.presentation.vehicle.draftOf
import ru.sodovaya.volty.presentation.vehicle.liveLinkAddresses
import ru.sodovaya.volty.presentation.vehicle.VehicleEditSeed
import ru.sodovaya.volty.presentation.vehicle.wizard.DefaultSetupWizardComponent
import ru.sodovaya.volty.presentation.vehicle.wizard.WizardHandoff
import ru.sodovaya.volty.presentation.vehicle.wizard.SetupWizardComponent
import ru.sodovaya.volty.presentation.welcome.DefaultWelcomeComponent
import ru.sodovaya.volty.presentation.welcome.WelcomeComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject

interface RootComponent {
    val stack: Value<ChildStack<*, Child>>

    /** One navigation owner retained while the root switches dashboard tabs. */
    val navigation: LightNavigationComponent

    /** True when an active vehicle can show the Ride dashboard. */
    val rideAvailable: Value<Boolean>
    /** False while no vehicle is connected: the tab bar then offers Connect instead of the dashboards. */
    val vehicleConnected: Value<Boolean>

    /** Root borrows the process conversation; navigation never owns voice or sharing. */
    val nearby: NearbyRootPresentation
    /** Listening volume (master and per participant) for the conversation panel. */
    val voiceListen: StateFlow<ru.sodovaya.volty.domain.audio.VoiceListenSettings>
    fun setParticipantGain(actorId: String, db: Float)

    fun onBack()
    fun onTab(tab: Tab)
    fun onOpenGroupMap()
    fun onOpenHistory()

    enum class Tab { Ride, Battery, Nearby, Settings, Connect }

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
        data class SetupWizard(val component: SetupWizardComponent) : Child
        data class VehicleEdit(val component: VehicleEditComponent) : Child
        data class VehicleAlerts(val component: VehicleAlertsComponent) : Child
        data class VehicleInfo(val component: ru.sodovaya.volty.presentation.vehicle.info.VehicleInfoComponent) : Child
        data class PwmCalibration(val component: ru.sodovaya.volty.presentation.vehicle.pwm.PwmCalibrationComponent) : Child
        data class VehicleOverview(val component: ru.sodovaya.volty.presentation.vehicle.VehicleOverviewComponent) : Child
        data class History(val component: ru.sodovaya.volty.presentation.history.HistoryComponent,
            val vehicleNames: kotlinx.coroutines.flow.Flow<Map<String, String>> = kotlinx.coroutines.flow.flowOf(emptyMap())) : Child
        data class Nearby(val component: DefaultVoiceSpacesNearbyComponent) : Child
        /** Retained channel map; serialized GroupMap route name remains compatible. */
        data object GroupMap : Child
        data class Settings(val component: SettingsComponent) : Child
    }
}

@Serializable
sealed class Config {
    @Serializable data object Loading : Config()
    @Serializable data object Welcome : Config()
    @Serializable data object Permissions : Config()
    @Serializable data object Scanning : Config()
    @Serializable data class AutoConnect(
        val vehicleId: String,
        /** Cold start already proved this is the only saved vehicle in range. */
        val startImmediately: Boolean = false
    ) : Config()
    @Serializable data class Picker(val mode: String) : Config()
    /** The Ride dashboard — home for any connected vehicle source. */
    @Serializable data object Ride : Config()
    /** The battery dashboard — secondary surface reached from Ride. */
    @Serializable data object Dashboard : Config()
    @Serializable data class PackDetail(val packIndex: Int) : Config()
    @Serializable data class SetupWizard(
        val prefillFromActiveConnection: Boolean = true
    ) : Config()
    @Serializable data class VehicleEdit(
        val vehicleId: String?,
        /**
         * When true and [vehicleId] is null, the edit form prefills BMS type /
         * address / name from the currently active connection (e.g. the user
         * tapped "+ Add" while a guest connection is live, so we can capture
         * the device they're already talking to).
         */
        val prefillFromActiveConnection: Boolean = false,
        /**
         * Names the wizard draft this create-editor was opened for. Part of the configuration so
         * Decompose, which matches children by configuration equality, can never hand a new Skip
         * to an older create-editor that happens to be buried in the stack.
         */
        val seedId: String? = null
    ) : Config()
    /**
     * The per-vehicle alert settings screen (F Task 9). Pushed from
     * [VehicleEdit], so [vehicleId] is always a saved vehicle — a vehicle that
     * does not exist yet has nothing to configure alerts on.
     */
    @Serializable data class VehicleAlerts(val vehicleId: String) : Config()
    /** «Всё о ТС»: everything the app knows about one saved vehicle. Pushed from [VehicleEdit]. */
    @Serializable data class VehicleInfo(val vehicleId: String) : Config()
    /** The PWM calibration wizard for one saved vehicle. Pushed from [VehicleEdit], whose form stays alive beneath it. */
    @Serializable data class PwmCalibration(val vehicleId: String) : Config()
    /** A saved vehicle seen without connecting: last recorded state, rides, settings. */
    @Serializable data class VehicleOverview(val vehicleId: String) : Config()
    /** History, opened on [rideId] when given, else on the ride being recorded or the newest. */
    @Serializable data class History(val rideId: String? = null) : Config()
    @Serializable data object Nearby : Config()
    @Serializable data object GroupMap : Config()
    @Serializable data object Settings : Config()
}

/** The info screen reads a SAVED vehicle; an editor that is still creating one has no id to open it on. */
internal fun configForVehicleInfo(vehicleId: String?): Config? = vehicleId?.let { Config.VehicleInfo(it) }

/** Same rule for the PWM wizard: it calibrates a SAVED vehicle's row, so a form still creating one has no id to open it on. */
internal fun configForPwmCalibration(vehicleId: String?): Config? = vehicleId?.let { Config.PwmCalibration(it) }

/** The three root surfaces whose create action opens the same retained wizard. */
internal enum class CreateVehicleEntry { RIDE, DASHBOARD, SETTINGS }

/** A create action always starts the wizard; the full editor is for saved rows. */
internal fun configForCreateVehicle(entry: CreateVehicleEntry): Config = when (entry) {
    CreateVehicleEntry.RIDE,
    CreateVehicleEntry.DASHBOARD,
    CreateVehicleEntry.SETTINGS -> Config.SetupWizard(prefillFromActiveConnection = true)
}

/**
 * Only a transient guest connection is safe to use as the starting point for
 * a new vehicle. A saved active vehicle must never be copied into the create
 * flow: that turns "add" into an accidental duplicate of the vehicle in use.
 */
internal fun activeVehicleForCreatePrefill(
    activeVehicle: Vehicle?,
    enabled: Boolean
): Vehicle? = activeVehicle?.takeIf { enabled && it.isGuest }

/**
 * The app's home destination for [vehicle] — the single routing rule behind
 * every post-connect landing, the back-out target of Graph/Settings, and the
 * Ride tab's visibility.
 *
 * Any active vehicle lands on the Ride dashboard. A BMS-only vehicle uses
 * the same surface with GPS speed and BMS telemetry while controller-only
 * metrics remain unavailable. With no active vehicle, home is the garage
 * ([configAfterDisconnect]).
 *
 * RETRACTION: an earlier revision said the battery dashboard "remains the safe
 * fallback" with no vehicle. That predated the garage; it opened "No active
 * vehicle" with no tab highlighted after leaving the setup wizard (emulator,
 * 2026-10-24). The Battery TAB still reaches [Config.Dashboard] ([configForTab]).
 *
 * Extracted as a pure function so it can be unit-tested without standing up
 * Decompose's [ComponentContext] and Koin: see `RootNavigationTest`.
 */
internal fun homeConfigFor(vehicle: Vehicle?): Config =
    if (vehicle != null) Config.Ride else configAfterDisconnect()

/**
 * Destination of each bottom-bar tab. Pure for the same reason as
 * [homeConfigFor] — the Battery tab must keep reaching the existing
 * [Config.Dashboard], and that is worth pinning in a test.
 */
/** Past the ride's own absence window, so the summary reads the ride already closed. */
internal const val ABSENT_SUMMARY_DELAY_MILLIS = ru.sodovaya.volty.domain.history.RIDE_ABSENCE_MILLIS + 5_000L

/** Only a dashboard is replaced by the summary; Nearby, maps, settings or history stay where the rider put them. */
internal fun absentSummaryApplies(active: Config): Boolean = active is Config.Ride || active is Config.Dashboard

/**
 * The stack once an absent vehicle's summary is due, or null when no dashboard is in it.
 * Every dashboard is swapped for the summary in place: a screen the rider chose stays on top, and
 * back lands on the card instead of frozen numbers. An earlier revision only looked at the top
 * entry, so a rider in Settings swiped back into a dead dashboard.
 *
 * Retraction: a dashboard on top used to be COVERED ([Ride] -> [Ride, card]). Back from the card
 * revealed the frozen dashboard, and back from that one-entry stack went nowhere, so the rider had
 * to restart the app (field report 2026-10-26). The dead dashboard is now replaced there too, and
 * a card left at the bottom gets the garage under it - where a disconnect lands anyway.
 */
internal fun stackAfterAbsence(stack: List<Config>, vehicleId: String): List<Config>? {
    if (stack.none(::absentSummaryApplies)) return null
    val summary = Config.VehicleOverview(vehicleId)
    var placed = summary in stack
    val swapped = stack.flatMap { config ->
        when {
            !absentSummaryApplies(config) -> listOf(config)
            placed -> emptyList()
            else -> { placed = true; listOf(summary) }
        }
    }
    return if (swapped.first() == summary) listOf(configAfterDisconnect()) + swapped else swapped
}

/**
 * The vehicle is back: the summary that stood in for its dashboard gives its place back to [home],
 * on top or buried. (A card on top used to be popped, revealing the dashboard it covered; it no
 * longer covers one.)
 */
internal fun stackAfterReturn(stack: List<Config>, vehicleId: String, home: Config): List<Config>? {
    val summary = Config.VehicleOverview(vehicleId)
    if (summary !in stack || home in stack) return null
    return stack.map { if (it == summary) home else it }
}

/**
 * A rider who pressed Disconnect lands in the garage, which marks nearby vehicles but never connects
 * on its own. Retraction: this used to be Config.Scanning, the cold-start screen, which connects at
 * once to a lone saved vehicle in range: disconnecting from a wheel next to a scooter connected the
 * scooter's BMS and recorded a ten-second ride. Auto-connect stays a cold-start behaviour only.
 */
internal fun configAfterDisconnect(): Config = Config.Picker(mode = "cold")

internal fun configForTab(tab: RootComponent.Tab): Config = when (tab) {
    RootComponent.Tab.Ride -> Config.Ride
    RootComponent.Tab.Battery -> Config.Dashboard
    RootComponent.Tab.Nearby -> Config.Nearby
    RootComponent.Tab.Settings -> Config.Settings
    // Without a vehicle there is no dashboard to return to: the cold picker is "home".
    RootComponent.Tab.Connect -> Config.Picker(mode = "cold")
}

/**
 * True when the active vehicle no longer belongs on the Ride dashboard but
 * [stackConfigs] still holds one, so the root must re-route home.
 *
 * Inspects the WHOLE stack, not just its active entry: tapping Battery from
 * Ride leaves `[Ride, Dashboard]`, and switching to a source-less vehicle
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
    (current !is Config.Settings && current !is Config.History) || stackSize > 1

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
    approved: Set<DraftExitComponent> = emptySet()
) {
    val index = children.indexOfLast { child ->
        child.draftExitComponent()?.let { it.hasUnsavedDraft && it !in approved } == true
    }
    if (index < 0) {
        destroyStack()
        return
    }
    val editor = children[index].draftExitComponent() ?: return
    revealComposerAt(index)
    editor.requestExit {
        // A stack may retain editors for two different vehicles. Confirmation
        // approves this instance for THIS requested destruction only. Recurse
        // so every older dirty draft gets a visible decision; a dismissal ends
        // the closure chain and a later request starts with no approvals.
        guardComposerDestruction(children, revealComposerAt, destroyStack, approved + editor)
    }
}

private fun RootComponent.Child.draftExitComponent(): DraftExitComponent? = when (this) {
    is RootComponent.Child.SetupWizard -> component
    is RootComponent.Child.VehicleEdit -> component
    else -> null
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

/**
 * Put home in front of setup without destroying setup itself.
 *
 * A confirmed leave is not a second kind of discard: the wizard draft remains
 * in its retained Decompose child and every create entry relocates that same
 * configuration back to the front through [stackAfterGoTo].
 */
internal fun leaveSetupWizard(
    navigation: StackNavigation<Config>,
    home: Config
) {
    navigation.navigate { stack -> stackAfterGoTo(stack, home) }
}

/**
 * The stack after the wizard's "Skip": the wizard hands its draft to a new vehicle editor and
 * is gone, rather than lingering underneath as a second copy of the same draft. Everything else
 * keeps its place. A buried create-editor is NOT replaced: the new one carries its own seedId, so
 * Decompose cannot match it to the old one, and Back from the new editor reveals the old one.
 * (An earlier revision of this comment said the buried one was replaced; the seedId fix changed that.)
 */
internal fun stackAfterWizardSkip(stack: List<Config>, seedId: String): List<Config> =
    stack.filterNot { it is Config.SetupWizard } + Config.VehicleEdit(vehicleId = null, seedId = seedId)

/** Takes (removes) the handoff a create-editor configuration was opened for; null for any other. */
internal fun consumeEditorSeed(
    seeds: MutableMap<String, WizardHandoff>,
    config: Config.VehicleEdit
): WizardHandoff? = if (config.vehicleId == null) config.seedId?.let { seeds.remove(it) } else null

class DefaultRootComponent(
    componentContext: ComponentContext
) : RootComponent, ComponentContext by componentContext, KoinComponent {

    private val nav = StackNavigation<Config>()

    private val vehicleRepository: VehicleRepository by inject()
    private val bmsRepository: BmsRepository by inject()
    private val permissionsChecker: PermissionsChecker by inject()
    private val locationRepository: ru.sodovaya.volty.domain.location.RideLocationRepository by inject()

    // Lightweight scope for cold-start async work (DB reads) and the active
    // vehicle key consumed by the retained navigation component.
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val nearbyPresenter = NearbyRootPresenter(scope,get(),get(),get())
    override val nearby: NearbyRootPresentation = nearbyPresenter
    override val voiceListen: StateFlow<ru.sodovaya.volty.domain.audio.VoiceListenSettings> = get<AppPrefs>().voiceListenSettings
    override fun setParticipantGain(actorId: String, db: Float) { scope.launch { get<AppPrefs>().setParticipantGainDb(actorId, db) } }
    private val navigationVehicleId: StateFlow<String?> = bmsRepository.activeVehicle
        .map { it?.id }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            bmsRepository.activeVehicle.value?.id,
        )

    override val navigation: LightNavigationComponent = DefaultLightNavigationComponent(
        componentContext = componentContext,
        navigationRepository = get(),
        locationRepository = locationRepository,
        energySource = get(),
        navigationPreferences = get<AppPrefs>(),
        activeVehicleId = navigationVehicleId,
    )

    private var lastNavigationVehicleId: String? = bmsRepository.activeVehicle.value?.id

    private val _rideAvailable =
        MutableValue(homeConfigFor(bmsRepository.activeVehicle.value) is Config.Ride)
    override val rideAvailable: Value<Boolean> = _rideAvailable
    private val _vehicleConnected = MutableValue(bmsRepository.activeVehicle.value != null)
    override val vehicleConnected: Value<Boolean> = _vehicleConnected

    init {
        lifecycle.doOnDestroy {
            scope.coroutineContext[Job]?.cancel()
            pendingEditorSeeds.clear()
            nearbyPresenter.close()
            navigation.close()
        }
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
                if (v?.id != lastNavigationVehicleId) {
                    lastNavigationVehicleId = v?.id
                    // The component survives tab changes, but a route must
                    // never survive a vehicle switch or disconnect.
                    navigation.onStopNavigation()
                }
                val home = homeConfigFor(v)
                _rideAvailable.value = home is Config.Ride
                _vehicleConnected.value = v != null
                // Switching to a source-less vehicle must not leave a Ride
                // entry anywhere in the stack — see [shouldLeaveRide].
                if (shouldLeaveRide(v, stack.value.items.map { it.configuration })) {
                    replaceAll(home)
                }
            }
        }

        // A vehicle switched off: its dashboard would only show frozen numbers, so after the ride
        // has been closed show what it recorded. Coming back returns to the dashboard by itself.
        scope.launch {
            combine(bmsRepository.connectionState, bmsRepository.activeVehicle) { state, vehicle ->
                (state is ConnectionState.Connected) to vehicle?.takeUnless { it.isDemo || it.isGuest }?.id
            }.distinctUntilChanged().collectLatest { (connected, vehicleId) ->
                if (connected) { leaveAbsentSummary(); return@collectLatest }
                if (vehicleId == null) return@collectLatest
                delay(ABSENT_SUMMARY_DELAY_MILLIS)
                val configs = stack.value.items.map { it.configuration as Config }
                if (stackAfterAbsence(configs, vehicleId) != null) {
                    absentSummaryVehicleId = vehicleId
                    nav.navigate { current -> stackAfterAbsence(current, vehicleId) ?: current }
                }
            }
        }
    }

    /** Set only while the summary was opened for an absent vehicle, so a rider-opened card is never closed. */
    private var absentSummaryVehicleId: String? = null

    private fun leaveAbsentSummary() {
        val id = absentSummaryVehicleId ?: return
        absentSummaryVehicleId = null
        val home = homeConfigFor(bmsRepository.activeVehicle.value)
        nav.navigate { current -> stackAfterReturn(current, id, home) ?: current }
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
            revealComposerAt = { index -> goTo(entries[index].configuration as Config) },
            destroyStack = { nav.replaceAll(config) }
        )
    }

    /**
     * Where "home" is right now. Every post-connect landing goes through here
     * so every active vehicle lands on Ride, from one rule rather than five
     * copies of it.
     */
    private fun homeConfig(): Config = homeConfigFor(bmsRepository.activeVehicle.value)

    /**
     * Every composer terminal callback takes the same total route. This covers
     * wizard/editor cancellation and deletion, including the one-entry stack
     * left after picker connection where a plain `pop()` would be inert.
     */
    private fun leaveActiveComposer() {
        leaveVehicleEdit(nav, stack.value.items.size, homeConfig())
    }

    /** Skip's draft, waiting for the editor it opens; read once by [Config.VehicleEdit]'s factory. */
    private val pendingEditorSeeds = mutableMapOf<String, WizardHandoff>()

    private fun openEditorFromWizard(handoff: WizardHandoff) {
        val seedId = "seed-${kotlin.random.Random.nextLong()}"
        pendingEditorSeeds[seedId] = handoff
        nav.navigate { stack -> stackAfterWizardSkip(stack, seedId) }
    }

    private fun leaveActiveSetupWizard() {
        leaveSetupWizard(nav, homeConfig())
    }

    override fun onBack() {
        val current = stack.value.active.configuration
        // Graph and Settings are leaves off a home screen (Ride or Dashboard) —
        // popping reveals that SAME home instance, matching their own ‹ buttons
        // (see Config.Graph / Config.Settings in createChild) and keeping live
        // Ride state alive. See [shouldPopOnBack] for why this doesn't need to
        // duplicate [shouldLeaveRide]'s job.
        if (current is Config.GroupMap || shouldPopOnBack(current, stack.value.items.size)) {
            nav.pop()
        } else {
            replaceAll(homeConfig())
        }
    }

    override fun onTab(tab: RootComponent.Tab) {
        nav.bringToFront(configForTab(tab))
    }

    override fun onOpenHistory() { goTo(Config.History()) }

    private fun vehicleHistoryReader(): ru.sodovaya.volty.presentation.vehicle.VehicleHistoryReader {
        val clock = get<ru.sodovaya.volty.data.history.HistoryRecordingClock>()
        return ru.sodovaya.volty.presentation.vehicle.VehicleHistoryReader(
            ru.sodovaya.volty.data.history.PreparedHistoryReadRepository(get(), clock::prepare), clock::readTick)
    }

    override fun onOpenGroupMap() {
        goTo(Config.GroupMap)
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
                    // Start first-time setup in the short scan-and-connect flow.
                    // The full draft wizard remains available from the picker
                    // for riders who need to configure a multi-source vehicle.
                    onAddBatteryRequested = {
                        replaceAll(Config.Picker(mode = "add"))
                    },
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
                    onSingleKnown = { vehicleId ->
                        replaceAll(Config.AutoConnect(vehicleId, startImmediately = true))
                    },
                    onMultipleOrNone = { replaceAll(Config.Picker(mode = "cold")) }
                )
            )
            is Config.AutoConnect -> RootComponent.Child.AutoConnect(
                DefaultAutoConnectComponent(
                    componentContext = context,
                    vehicleId = config.vehicleId,
                    startImmediately = config.startImmediately,
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
                    // The picker is the short add flow. Its advanced action
                    // enters the draft-owned setup wizard when a rider needs
                    // multiple controllers, batteries, or CAN wiring.
                    onAddNewBatteryRequested = {
                        goTo(Config.SetupWizard(prefillFromActiveConnection = false))
                    },
                    onDemoConnected = { replaceAll(homeConfig()) },
                    onOpenSavedRequested = { id -> goTo(Config.VehicleOverview(id)) },
                    onConnectSavedRequested = { id -> replaceAll(Config.AutoConnect(id)) },
                    loadGlances = { ids ->
                        val reader = vehicleHistoryReader()
                        reader.newestRides(ids, perVehicle = 1).mapNotNull { (id, rides) ->
                            rides.firstOrNull()?.let { ride -> id to reader.glance(ride) }
                        }.toMap()
                    },
                    onCancelled = { nav.pop() }
                )
            )
            is Config.Ride -> RootComponent.Child.Ride(
                DefaultRideDashboardComponent(
                    componentContext = context,
                    bmsRepository = get(),
                    vehicleRepository = get(),
                    appPrefs = get<AppPrefs>(),
                    onOpenGraphRequested = { goTo(Config.History()) },
                    onOpenSettingsRequested = { goTo(Config.Settings) },
                    onEditVehicleRequested = { id -> goTo(Config.VehicleEdit(id)) },
                    // Mirrors Config.Dashboard's onOpenAddBattery: the sheet's
                    // "+ Add" captures the live connection into a new vehicle.
                    onAddVehicleRequested = {
                        goTo(configForCreateVehicle(CreateVehicleEntry.RIDE))
                    },
                    onDisconnectRequested = { replaceAll(configAfterDisconnect()) },
                    rangeSource = get<ru.sodovaya.volty.data.energy.RangeSource>(),
                    calibrationMute = get()
                )
            )
            is Config.Dashboard -> RootComponent.Child.Dashboard(
                DefaultDashboardComponent(
                    componentContext = context,
                    bmsRepository = get(),
                    vehicleRepository = get(),
                    onOpenGraphRequested = { goTo(Config.History()) },
                    onOpenSettings = { goTo(Config.Settings) },
                    onOpenAddBattery = {
                        goTo(configForCreateVehicle(CreateVehicleEntry.DASHBOARD))
                    },
                    onOpenPackDetail = { packIndex -> goTo(Config.PackDetail(packIndex)) },
                    onDisconnectRequested = { replaceAll(configAfterDisconnect()) }
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
            is Config.SetupWizard -> {
                val prefillVehicle = activeVehicleForCreatePrefill(
                    activeVehicle = bmsRepository.activeVehicle.value,
                    enabled = config.prefillFromActiveConnection
                )
                val initialName = prefillVehicle?.name
                    ?.let { if (prefillVehicle.isGuest && it == "Guest BMS") "" else it }
                    .orEmpty()
                RootComponent.Child.SetupWizard(
                    DefaultSetupWizardComponent(
                        componentContext = context,
                        initialDraft = prefillVehicle?.let(::draftOf) ?: VehicleDraft(),
                        initialName = initialName,
                        scanAll = bmsRepository::scanAll,
                        canDiscovery = get<CanDiscovery>(),
                        liveAddresses = combine(
                            bmsRepository.activeVehicle,
                            bmsRepository.connectionState,
                            ::liveLinkAddresses
                        ),
                        saveVehicle = vehicleRepository::upsert,
                        rememberDeviceType = vehicleRepository::rememberDeviceType,
                        connectVehicle = bmsRepository::connect,
                        onSkipToEditor = ::openEditorFromWizard,
                        onCancelled = ::leaveActiveSetupWizard,
                        onShowVehicleList = { replaceAll(Config.Picker(mode = "cold")) },
                        onConnected = { replaceAll(homeConfig()) }
                    )
                )
            }
            is Config.VehicleEdit -> {
                // Optionally prefill BMS type / address / name from the currently
                // active connection. Only applies when creating a new vehicle
                // (vehicleId == null) and the caller asked for it. Guest names
                // get the synthetic "Guest " prefix stripped (see KableBmsRepository).
                val prefillVehicle = activeVehicleForCreatePrefill(
                    activeVehicle = bmsRepository.activeVehicle.value,
                    enabled = config.vehicleId == null && config.prefillFromActiveConnection
                )
                val prefilledName = prefillVehicle?.name
                    ?.let { if (prefillVehicle.isGuest && it == "Guest BMS") null else it }
                // The wizard's Skip: take the draft once; a rebuilt editor must not replay it.
                val seed = consumeEditorSeed(pendingEditorSeeds, config)
                RootComponent.Child.VehicleEdit(
                    DefaultVehicleEditComponent(
                        componentContext = context,
                        vehicleId = config.vehicleId,
                        vehicleRepository = get(),
                        bmsRepository = get(),
                        onSaved = { replaceAll(homeConfig()) },
                        onCancelled = ::leaveActiveComposer,
                        // A picker-created editor is the sole entry after its
                        // successful connection. Deleting it must therefore
                        // use the same total exit as Cancel: `pop()` alone is
                        // inert there and would leave the now-deleted form's
                        // Save control able to recreate the vehicle.
                        onDeleted = ::leaveActiveComposer,
                        // Both prefills are already optional, so a source-less
                        // (controller-only) active connection simply prefills
                        // nothing instead of throwing.
                        seed = seed?.let {
                            VehicleEditSeed(it.draft, it.name, it.iconKey, it.secondaryGauge, it.topology)
                        },
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
                        // «Всё о ТС», pushed like the alerts screen; saved vehicles only.
                        onOpenInfoRequested = { configForVehicleInfo(config.vehicleId)?.let(::goTo) },
                        // The PWM calibration wizard, pushed like the info screen so the form keeps its edits.
                        onOpenPwmWizardRequested = { configForPwmCalibration(config.vehicleId)?.let(::goTo) },
                        // The SAME instance the connection runs on — `appModule`
                        // binds KableBmsRepository to both interfaces, because a
                        // second one would have no live links to scan (G2 Task 5).
                        canDiscovery = get<CanDiscovery>(),
                        controllerConfigSource = get<ControllerConfigSource>(),
                        learnedCellCountSource = get<ru.sodovaya.volty.data.bms.LearnedCellCountSource>(),
                        energyStore = get<ru.sodovaya.volty.domain.energy.VehicleEnergyStore>(),
                        rangeSource = get<ru.sodovaya.volty.data.energy.RangeSource>()
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
            is Config.PwmCalibration -> RootComponent.Child.PwmCalibration(
                ru.sodovaya.volty.presentation.vehicle.pwm.DefaultPwmCalibrationComponent(
                    componentContext = context,
                    parentScope = scope,
                    vehicleId = config.vehicleId,
                    vehicles = get(),
                    bms = get(),
                    onBackRequested = { nav.pop() },
                    calibrationMute = get(),
                )
            )
            is Config.VehicleInfo -> RootComponent.Child.VehicleInfo(
                ru.sodovaya.volty.presentation.vehicle.info.DefaultVehicleInfoComponent(
                    componentContext = context,
                    parentScope = scope,
                    vehicleId = config.vehicleId,
                    vehicles = get(),
                    bms = get(),
                    diagnostics = get(),
                    energyStore = get(),
                    range = get(),
                    unitSystem = get<AppPrefs>().unitSystem,
                    onBackRequested = { nav.pop() },
                )
            )
            is Config.VehicleOverview -> {
                RootComponent.Child.VehicleOverview(ru.sodovaya.volty.presentation.vehicle.DefaultVehicleOverviewComponent(
                    context, scope, config.vehicleId, get(), vehicleHistoryReader(),
                    onConnectRequested = { id -> replaceAll(Config.AutoConnect(id)) },
                    onOpenRideRequested = { rideId -> goTo(Config.History(rideId)) },
                    onEditRequested = { id -> goTo(Config.VehicleEdit(id)) },
                    onAlertsRequested = { id -> goTo(Config.VehicleAlerts(id)) },
                    onBackRequested = { nav.pop() },
                ))
            }
            is Config.History -> {
                val clock = get<ru.sodovaya.volty.data.history.HistoryRecordingClock>()
                val reads = ru.sodovaya.volty.data.history.PreparedHistoryReadRepository(get(), clock::prepare)
                RootComponent.Child.History(ru.sodovaya.volty.presentation.history.DefaultHistoryComponent(
                    context, scope, reads, clock::readTick, ::onBack,
                    presetStore = get(), imageLoader = get(), favoriteStore = get(),
                    mediaRepository = get(), deletionRepository = get(), exportRepository = get(),
                    recordingRepository = get(), openLatestOnLoad = true, totalsRepository = reads,
                    initialRideId = config.rideId, defaultMetrics = true).also { it.refresh() },
                    combine(vehicleRepository.vehicles, bmsRepository.activeVehicle) { saved, current ->
                        (saved + listOfNotNull(current)).associate { it.id to it.name }
                    })
            }
            is Config.Nearby -> {
                lateinit var nearby: DefaultVoiceSpacesNearbyComponent
                nearby = DefaultVoiceSpacesNearbyComponent(context,scope,get(),get(),
                    nextId = {get<ru.sodovaya.volty.presentation.root.nearby.NearbyOperationIds>().next()},
                    connectChannel = { account, channel ->
                        val browser = nearby.servers.value.presenter?.state?.value?.browser
                        val detail = browser?.detail
                        val row = detail?.channels?.firstOrNull {it.id == channel}
                        if (browser != null && detail != null && !browser.detailLoading && detail.community.id == browser.selectedCommunity && row != null)
                            nearbyPresenter.connectChannel(account,channel,row.name)
                    },
                    call = nearbyPresenter::callFriend, onBack = ::onBack)
                RootComponent.Child.Nearby(nearby)
            }
            // The native map surface is hosted once by RootScreen. This child
            // is an explicit route marker for its chrome/back-stack state; it
            // intentionally carries no second map instance or local data.
            is Config.GroupMap -> RootComponent.Child.GroupMap
            is Config.Settings -> RootComponent.Child.Settings(
                DefaultSettingsComponent(
                    componentContext = context,
                    appPrefs = get<AppPrefs>(),
                    vehicleRepository = get(),
                    offlineRegionsRepository = get<OfflineRegionPackageRepository>(),
                    offlinePreparationCoordinator = get(),
                    offlineStorageMeter = get<ru.sodovaya.volty.domain.navigation.region.OfflineStorageMeter>(),
                    logExporter = get(),
                    bleDiagnosticsRepository = get(),
                    bmsRepository = bmsRepository,
                    onEditVehicleRequested = { id -> goTo(Config.VehicleEdit(id)) },
                    onAddBatteryRequested = {
                        goTo(configForCreateVehicle(CreateVehicleEntry.SETTINGS))
                    },
                    onBackRequested = { nav.pop() }
                )
            )
        }
}
