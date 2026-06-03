package ru.sodovaya.volty.presentation.welcome

import com.arkivanov.decompose.ComponentContext

interface WelcomeComponent {
    fun onAddBattery()
    fun onQuickConnect()
}

class DefaultWelcomeComponent(
    componentContext: ComponentContext,
    private val onAddBatteryRequested: () -> Unit,
    private val onQuickConnectRequested: () -> Unit
) : WelcomeComponent, ComponentContext by componentContext {
    override fun onAddBattery() { onAddBatteryRequested() }
    override fun onQuickConnect() { onQuickConnectRequested() }
}
