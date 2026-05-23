package com.volty.app.presentation.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.bringToFront
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import com.volty.app.data.prefs.AppPrefs
import com.volty.app.domain.repository.VehicleRepository
import com.volty.app.permissions.PermissionsChecker
import com.volty.app.presentation.autoconnect.AutoConnectComponent
import com.volty.app.presentation.autoconnect.DefaultAutoConnectComponent
import com.volty.app.presentation.cells.CellsComponent
import com.volty.app.presentation.cells.DefaultCellsComponent
import com.volty.app.presentation.dashboard.DashboardComponent
import com.volty.app.presentation.dashboard.DefaultDashboardComponent
import com.volty.app.presentation.graph.DefaultGraphComponent
import com.volty.app.presentation.graph.GraphComponent
import com.volty.app.presentation.permissions.DefaultPermissionsGateComponent
import com.volty.app.presentation.permissions.PermissionsGateComponent
import com.volty.app.presentation.picker.DefaultPickerComponent
import com.volty.app.presentation.picker.PickerComponent
import com.volty.app.presentation.scanning.DefaultScanningComponent
import com.volty.app.presentation.scanning.ScanningComponent
import com.volty.app.presentation.settings.DefaultSettingsComponent
import com.volty.app.presentation.settings.SettingsComponent
import com.volty.app.presentation.vehicle.DefaultVehicleEditComponent
import com.volty.app.presentation.vehicle.VehicleEditComponent
import com.volty.app.presentation.welcome.DefaultWelcomeComponent
import com.volty.app.presentation.welcome.WelcomeComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject

interface RootComponent {
    val stack: Value<ChildStack<*, Child>>

    fun onBack()
    fun onTab(tab: Tab)

    enum class Tab { Live, Cells, Graph, Settings }

    sealed interface Child {
        data class Welcome(val component: WelcomeComponent) : Child
        data class Permissions(val component: PermissionsGateComponent) : Child
        data class Scanning(val component: ScanningComponent) : Child
        data class AutoConnect(val component: AutoConnectComponent) : Child
        data class Picker(val component: PickerComponent) : Child
        data class Dashboard(val component: DashboardComponent) : Child
        data class VehicleEdit(val component: VehicleEditComponent) : Child
        data class Cells(val component: CellsComponent) : Child
        data class Graph(val component: GraphComponent) : Child
        data class Settings(val component: SettingsComponent) : Child
    }
}

@Serializable
sealed class Config {
    @Serializable data object Welcome : Config()
    @Serializable data object Permissions : Config()
    @Serializable data object Scanning : Config()
    @Serializable data class AutoConnect(val vehicleId: String) : Config()
    @Serializable data class Picker(val mode: String) : Config()
    @Serializable data object Dashboard : Config()
    @Serializable data class VehicleEdit(val vehicleId: String?) : Config()
    @Serializable data object Cells : Config()
    @Serializable data object Graph : Config()
    @Serializable data object Settings : Config()
}

class DefaultRootComponent(
    componentContext: ComponentContext
) : RootComponent, ComponentContext by componentContext, KoinComponent {

    private val nav = StackNavigation<Config>()

    private val vehicleRepository: VehicleRepository by inject()
    private val permissionsChecker: PermissionsChecker by inject()

    override val stack: Value<ChildStack<*, RootComponent.Child>> = childStack(
        source = nav,
        serializer = Config.serializer(),
        initialConfiguration = computeInitialConfig(),
        handleBackButton = true,
        childFactory = ::createChild
    )

    override fun onBack() {
        val current = stack.value.active.configuration
        when (current) {
            is Config.Cells, is Config.Graph, is Config.Settings -> nav.replaceAll(Config.Dashboard)
            else -> nav.pop()
        }
    }

    override fun onTab(tab: RootComponent.Tab) {
        val target = when (tab) {
            RootComponent.Tab.Live -> Config.Dashboard
            RootComponent.Tab.Cells -> Config.Cells
            RootComponent.Tab.Graph -> Config.Graph
            RootComponent.Tab.Settings -> Config.Settings
        }
        nav.bringToFront(target)
    }

    private fun computeInitialConfig(): Config {
        if (permissionsChecker.missingPermissions().isNotEmpty()) return Config.Permissions
        val savedCount = runBlocking { vehicleRepository.vehicles.first().size }
        return if (savedCount == 0) Config.Welcome else Config.Scanning
    }

    @OptIn(DelicateDecomposeApi::class)
    private fun createChild(config: Config, context: ComponentContext): RootComponent.Child =
        when (config) {
            is Config.Welcome -> RootComponent.Child.Welcome(
                DefaultWelcomeComponent(
                    componentContext = context,
                    // Welcome is only ever shown when permissions are already granted (gated by computeInitialConfig),
                    // so these buttons can route directly to the Picker without re-checking.
                    onAddBatteryRequested = { nav.replaceAll(Config.Picker(mode = "add")) },
                    onQuickConnectRequested = { nav.replaceAll(Config.Picker(mode = "guest")) }
                )
            )
            is Config.Permissions -> RootComponent.Child.Permissions(
                DefaultPermissionsGateComponent(
                    componentContext = context,
                    checker = get<PermissionsChecker>(),
                    onAllGrantedRequested = {
                        // After granting, recompute the post-permissions initial route:
                        // Welcome (0 saved vehicles) or Scanning (>=1 saved vehicle).
                        val savedCount = runBlocking { vehicleRepository.vehicles.first().size }
                        nav.replaceAll(if (savedCount == 0) Config.Welcome else Config.Scanning)
                    }
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
                    onConnected = { nav.replaceAll(Config.Dashboard) },
                    onCancelled = { nav.replaceAll(Config.Picker(mode = "cold")) }
                )
            )
            is Config.Picker -> RootComponent.Child.Picker(
                DefaultPickerComponent(
                    componentContext = context,
                    mode = config.mode,
                    bmsRepository = get(),
                    vehicleRepository = get(),
                    onConnectedKnown = { nav.replaceAll(Config.Dashboard) },
                    onConnectedForEdit = { vehicleId -> nav.replaceAll(Config.VehicleEdit(vehicleId)) },
                    onConnectedGuestNoSave = { nav.replaceAll(Config.Dashboard) },
                    onAddNewBatteryRequested = { nav.replaceAll(Config.Picker(mode = "add")) },
                    onCancelled = { nav.pop() }
                )
            )
            is Config.Dashboard -> RootComponent.Child.Dashboard(
                DefaultDashboardComponent(
                    componentContext = context,
                    bmsRepository = get(),
                    vehicleRepository = get(),
                    onOpenCells = { nav.push(Config.Cells) },
                    onOpenGraph = { nav.push(Config.Graph) },
                    onOpenSettings = { nav.push(Config.Settings) },
                    onOpenAddBattery = { nav.push(Config.VehicleEdit(null)) },
                    onDisconnectRequested = { nav.replaceAll(Config.Scanning) }
                )
            )
            is Config.VehicleEdit -> RootComponent.Child.VehicleEdit(
                DefaultVehicleEditComponent(
                    componentContext = context,
                    vehicleId = config.vehicleId,
                    vehicleRepository = get(),
                    onSaved = { nav.replaceAll(Config.Dashboard) },
                    onCancelled = { nav.pop() },
                    onDeleted = { nav.pop() }
                )
            )
            is Config.Cells -> RootComponent.Child.Cells(
                DefaultCellsComponent(
                    componentContext = context,
                    bmsRepository = get(),
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
                    onEditVehicleRequested = { id -> nav.push(Config.VehicleEdit(id)) },
                    onAddBatteryRequested = { nav.push(Config.VehicleEdit(null)) },
                    onBackRequested = { nav.pop() }
                )
            )
        }
}
