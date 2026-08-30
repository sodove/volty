package ru.sodovaya.volty.domain.navigation

import kotlinx.coroutines.flow.StateFlow

interface NavigationEnergySource {
    val evidence: StateFlow<NavigationEnergyEvidence>
}
