package com.volty.app.presentation.root

import com.arkivanov.decompose.ComponentContext
import com.volty.app.presentation.debug.DebugComponent
import com.volty.app.presentation.debug.DefaultDebugComponent
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

interface RootComponent {
    val debug: DebugComponent
}

class DefaultRootComponent(
    componentContext: ComponentContext
) : RootComponent, ComponentContext by componentContext, KoinComponent {

    override val debug: DebugComponent = DefaultDebugComponent(
        componentContext = componentContext,
        bmsRepository = get(),
        vehicleRepository = get()
    )
}
