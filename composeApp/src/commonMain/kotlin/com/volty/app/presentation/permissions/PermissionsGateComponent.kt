package com.volty.app.presentation.permissions

import com.arkivanov.decompose.ComponentContext
import com.volty.app.permissions.PermissionsChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface PermissionsGateComponent {
    val state: StateFlow<State>
    val requiredPermissions: List<String>
    fun onPermissionResult(grantedMap: Map<String, Boolean>)

    data class State(
        val missing: List<String> = emptyList(),
        val allGranted: Boolean = false
    )
}

class DefaultPermissionsGateComponent(
    componentContext: ComponentContext,
    private val checker: PermissionsChecker,
    private val onAllGrantedRequested: () -> Unit
) : PermissionsGateComponent, ComponentContext by componentContext {

    private val _state = MutableStateFlow(PermissionsGateComponent.State(missing = checker.missingPermissions()))
    override val state: StateFlow<PermissionsGateComponent.State> = _state.asStateFlow()

    override val requiredPermissions: List<String> = checker.requiredPermissions()

    override fun onPermissionResult(grantedMap: Map<String, Boolean>) {
        val missing = checker.missingPermissions()
        _state.value = PermissionsGateComponent.State(missing = missing, allGranted = missing.isEmpty())
        if (missing.isEmpty()) onAllGrantedRequested()
    }
}
