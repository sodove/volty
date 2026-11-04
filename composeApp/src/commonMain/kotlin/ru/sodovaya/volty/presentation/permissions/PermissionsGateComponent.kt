package ru.sodovaya.volty.presentation.permissions

import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.model.DemoProfile

internal const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"
private const val LEGACY_BLE_LOCATION_PERMISSION = "android.permission.ACCESS_FINE_LOCATION"

/** Notifications improve monitoring but never authorize or gate a Bluetooth connection. */
internal fun missingBluetoothPermissions(missing: List<String>): List<String> =
    missing.filterNot { it == NOTIFICATION_PERMISSION }

interface PermissionsGateComponent {
    val state: StateFlow<State>
    fun onRequestPermissions(): List<String>
    fun onPermissionResult(grantedMap: Map<String, Boolean>, permanentlyDenied: Boolean = false)
    fun onRefreshPermissions()
    fun onSkipNotifications()
    fun onShowDemo()
    fun onDismissDemo()
    suspend fun onTryDemo(profile: DemoProfile)

    enum class Step { BLUETOOTH, NOTIFICATIONS }

    data class State(
        val step: Step = Step.BLUETOOTH,
        val usesLegacyLocation: Boolean = false,
        val hasNotificationStep: Boolean = false,
        val denied: Boolean = false,
        val openSettings: Boolean = false,
        val showDemoPicker: Boolean = false,
        val demoStarting: Boolean = false,
        val demoFailed: Boolean = false,
    )
}

class DefaultPermissionsGateComponent(
    componentContext: ComponentContext,
    requiredPermissions: List<String>,
    private val missingPermissions: () -> List<String>,
    private val onContinueRequested: () -> Unit,
    private val startDemo: suspend (DemoProfile) -> Result<Unit>,
) : PermissionsGateComponent, ComponentContext by componentContext {

    private val required = requiredPermissions.toSet()
    private fun missing() = missingPermissions().filter { it in required }
    private val _state = MutableStateFlow(
        PermissionsGateComponent.State(
            step = if (missingBluetoothPermissions(missing()).isEmpty())
                PermissionsGateComponent.Step.NOTIFICATIONS else PermissionsGateComponent.Step.BLUETOOTH,
            usesLegacyLocation = LEGACY_BLE_LOCATION_PERMISSION in required,
            hasNotificationStep = NOTIFICATION_PERMISSION in required,
        )
    )
    override val state = _state.asStateFlow()
    private var requestedStep: PermissionsGateComponent.Step? = null
    private var finished = false

    override fun onRequestPermissions(): List<String> {
        if (finished || state.value.demoStarting || requestedStep != null) return emptyList()
        onRefreshPermissions()
        if (finished) return emptyList()
        val step = state.value.step
        val request = when (step) {
            PermissionsGateComponent.Step.BLUETOOTH -> missingBluetoothPermissions(missing())
            PermissionsGateComponent.Step.NOTIFICATIONS -> missing().filter { it == NOTIFICATION_PERMISSION }
        }
        if (request.isNotEmpty()) requestedStep = step
        return request
    }

    override fun onPermissionResult(grantedMap: Map<String, Boolean>, permanentlyDenied: Boolean) {
        if (finished) return
        val step = requestedStep ?: return
        requestedStep = null
        val missing = missing()
        if (step == PermissionsGateComponent.Step.NOTIFICATIONS && missingBluetoothPermissions(missing).isEmpty()) {
            // A notification denial is a complete answer, not another mandatory gate.
            continueOnce()
        } else if (missingBluetoothPermissions(missing).isNotEmpty()) {
            _state.value = state.value.copy(
                step = PermissionsGateComponent.Step.BLUETOOTH,
                denied = true,
                openSettings = permanentlyDenied,
            )
        } else {
            onRefreshPermissions()
        }
    }

    override fun onRefreshPermissions() {
        // ON_RESUME can precede the activity-result callback. Keep the visible
        // step paired with its outstanding request until that callback consumes it.
        if (finished || state.value.demoStarting || requestedStep != null) return
        val missing = missing()
        when {
            missingBluetoothPermissions(missing).isNotEmpty() ->
                _state.value = state.value.copy(step = PermissionsGateComponent.Step.BLUETOOTH)
            NOTIFICATION_PERMISSION in missing ->
                _state.value = state.value.copy(step = PermissionsGateComponent.Step.NOTIFICATIONS, denied = false, openSettings = false)
            else -> continueOnce()
        }
    }

    override fun onSkipNotifications() {
        if (!finished && state.value.step == PermissionsGateComponent.Step.NOTIFICATIONS && missingBluetoothPermissions(missing()).isEmpty()) {
            continueOnce()
        }
    }

    override fun onShowDemo() {
        if (!finished && !state.value.demoStarting) _state.value = state.value.copy(showDemoPicker = true, demoFailed = false)
    }

    override fun onDismissDemo() {
        if (!state.value.demoStarting) _state.value = state.value.copy(showDemoPicker = false, demoFailed = false)
    }

    override suspend fun onTryDemo(profile: DemoProfile) {
        if (finished || !state.value.showDemoPicker || state.value.demoStarting) return
        _state.value = state.value.copy(demoStarting = true, demoFailed = false)
        try {
            val result = startDemo(profile)
            if (result.isSuccess) finished = true
            _state.value = state.value.copy(demoStarting = false, demoFailed = result.isFailure)
        } catch (cancel: CancellationException) {
            _state.value = state.value.copy(demoStarting = false)
            throw cancel
        } catch (_: Exception) {
            _state.value = state.value.copy(demoStarting = false, demoFailed = true)
        }
    }

    private fun continueOnce() {
        if (finished) return
        finished = true
        onContinueRequested()
    }
}
