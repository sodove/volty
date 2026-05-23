package com.volty.app.presentation.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import com.volty.app.data.prefs.AppPrefs
import com.volty.app.permissions.PermissionsChecker
import com.volty.app.presentation.debug.DebugComponent
import com.volty.app.presentation.debug.DefaultDebugComponent
import com.volty.app.presentation.permissions.DefaultPermissionsGateComponent
import com.volty.app.presentation.permissions.PermissionsGateComponent
import com.volty.app.presentation.scanning.DefaultScanningComponent
import com.volty.app.presentation.scanning.ScanningComponent
import com.volty.app.presentation.welcome.DefaultWelcomeComponent
import com.volty.app.presentation.welcome.WelcomeComponent
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

interface RootComponent {
    val stack: Value<ChildStack<*, Child>>

    fun onBack()

    sealed interface Child {
        data class Welcome(val component: WelcomeComponent) : Child
        data class Permissions(val component: PermissionsGateComponent) : Child
        data class Scanning(val component: ScanningComponent) : Child
        data class AutoConnect(val component: AutoConnectStubComponent) : Child
        data class Picker(val component: PickerStubComponent) : Child
        data class Dashboard(val component: DebugComponent) : Child
        data class VehicleEdit(val component: VehicleEditStubComponent) : Child
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
}

class DefaultRootComponent(
    componentContext: ComponentContext
) : RootComponent, ComponentContext by componentContext, KoinComponent {

    private val nav = StackNavigation<Config>()

    override val stack: Value<ChildStack<*, RootComponent.Child>> = childStack(
        source = nav,
        serializer = Config.serializer(),
        initialConfiguration = Config.Dashboard, // Plan 2 Task 14 will compute this from saved-vehicles + permissions state
        handleBackButton = true,
        childFactory = ::createChild
    )

    override fun onBack() { nav.pop() }

    private fun createChild(config: Config, context: ComponentContext): RootComponent.Child =
        when (config) {
            is Config.Welcome -> RootComponent.Child.Welcome(
                DefaultWelcomeComponent(
                    componentContext = context,
                    onAddBatteryRequested = { nav.replaceAll(Config.Permissions) },   // Plan 2 Task 14 may refine to push Picker(add) after permissions
                    onQuickConnectRequested = { nav.replaceAll(Config.Picker(mode = "guest")) }
                )
            )
            is Config.Permissions -> RootComponent.Child.Permissions(
                DefaultPermissionsGateComponent(
                    componentContext = context,
                    checker = get<PermissionsChecker>(),
                    onAllGrantedRequested = { nav.replaceAll(Config.Scanning) }
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
            is Config.AutoConnect -> RootComponent.Child.AutoConnect(AutoConnectStubComponent(context, config.vehicleId))
            is Config.Picker -> RootComponent.Child.Picker(PickerStubComponent(context, config.mode))
            is Config.Dashboard -> RootComponent.Child.Dashboard(
                DefaultDebugComponent(
                    componentContext = context,
                    bmsRepository = get(),
                    vehicleRepository = get()
                )
            )
            is Config.VehicleEdit -> RootComponent.Child.VehicleEdit(VehicleEditStubComponent(context, config.vehicleId))
        }
}

// Stub components — replaced in Tasks 8..13
interface AutoConnectStubComponent { val label: String }
class AutoConnectStubComponentImpl(val vehicleId: String) : AutoConnectStubComponent { override val label = "AutoConnect[$vehicleId] — not implemented yet" }
@Suppress("FunctionName") fun AutoConnectStubComponent(ctx: ComponentContext, vehicleId: String): AutoConnectStubComponent = AutoConnectStubComponentImpl(vehicleId)

interface PickerStubComponent { val label: String }
class PickerStubComponentImpl(val mode: String) : PickerStubComponent { override val label = "Picker[$mode] — not implemented yet" }
@Suppress("FunctionName") fun PickerStubComponent(ctx: ComponentContext, mode: String): PickerStubComponent = PickerStubComponentImpl(mode)

interface VehicleEditStubComponent { val label: String }
class VehicleEditStubComponentImpl(val vehicleId: String?) : VehicleEditStubComponent { override val label = "VehicleEdit[${vehicleId ?: "new"}] — not implemented yet" }
@Suppress("FunctionName") fun VehicleEditStubComponent(ctx: ComponentContext, vehicleId: String?): VehicleEditStubComponent = VehicleEditStubComponentImpl(vehicleId)
