package ru.sodovaya.volty.data.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.navigation.ConsumptionEvidenceBuilder
import ru.sodovaya.volty.domain.navigation.NavigationEnergyEvidence
import ru.sodovaya.volty.domain.navigation.NavigationEnergySource
import ru.sodovaya.volty.domain.navigation.SessionSamples
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.stats.RideEnergy
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class BmsNavigationEnergySource(
    bmsRepository: BmsRepository,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : NavigationEnergySource {
    private val session = SessionSamples()

    override val evidence: StateFlow<NavigationEnergyEvidence> = combine(
        bmsRepository.activeVehicleData,
        bmsRepository.activeMotion,
        bmsRepository.motionSamples(RideEnergy.SESSION_WINDOW),
        bmsRepository.activeVehicle,
        bmsRepository.connectionState,
    ) { vehicleData, motion, samples, vehicle, connectionState ->
        val sessionSamples = session.of(
            vehicleId = vehicle?.id,
            connected = connectionState is ConnectionState.Connected,
            motion = motion,
            samples = samples,
        )
        NavigationEnergyEvidence(
            vehicleData = vehicleData,
            motion = motion,
            consumption = ConsumptionEvidenceBuilder.build(motion, sessionSamples),
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = NavigationEnergyEvidence(
            vehicleData = ru.sodovaya.volty.domain.model.VehicleData(),
            motion = ControllerData(),
            consumption = null,
        ),
    )
}
