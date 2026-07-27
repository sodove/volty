package ru.sodovaya.volty.presentation.welcome

import com.arkivanov.decompose.ComponentContext
import ru.sodovaya.volty.domain.model.DemoProfile

interface WelcomeComponent {
    fun onAddBattery()
    fun onQuickConnect()

    /**
     * Start "Try demo" for [profile]. Two profiles, one affordance per profile:
     * a wheel's dashboard is a different set of dials from a scooter's, so
     * somebody evaluating volty for a EUC has to be able to see the wheel one.
     */
    fun onTryDemo(profile: DemoProfile)
}

class DefaultWelcomeComponent(
    componentContext: ComponentContext,
    private val onAddBatteryRequested: () -> Unit,
    private val onQuickConnectRequested: () -> Unit,
    private val onTryDemoRequested: (DemoProfile) -> Unit
) : WelcomeComponent, ComponentContext by componentContext {
    override fun onAddBattery() { onAddBatteryRequested() }
    override fun onQuickConnect() { onQuickConnectRequested() }
    override fun onTryDemo(profile: DemoProfile) { onTryDemoRequested(profile) }
}
