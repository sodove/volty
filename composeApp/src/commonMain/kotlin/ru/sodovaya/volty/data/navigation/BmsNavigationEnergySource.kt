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
import ru.sodovaya.volty.domain.navigation.ConsumptionEvidence
import ru.sodovaya.volty.domain.navigation.ConsumptionProvenance
import ru.sodovaya.volty.domain.navigation.NavigationEnergyEvidence
import ru.sodovaya.volty.domain.navigation.NavigationEnergySource
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.domain.stats.RideEnergy
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class BmsNavigationEnergySource(
    bmsRepository: BmsRepository,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : NavigationEnergySource {
    private var sessionKey: String? = null
    private var sessionStart: Instant? = null

    override val evidence: StateFlow<NavigationEnergyEvidence> = combine(
        bmsRepository.activeVehicleData,
        bmsRepository.activeMotion,
        bmsRepository.motionSamples(RideEnergy.SESSION_WINDOW),
        bmsRepository.activeVehicle,
        bmsRepository.connectionState,
    ) { vehicleData, motion, samples, vehicle, connectionState ->
        val connected = connectionState is ConnectionState.Connected
        val currentSessionKey = "${vehicle?.id ?: "none"}:$connected"
        if (currentSessionKey != sessionKey) {
            sessionStart = if (sessionKey == null) {
                samples.minOfOrNull { it.timestamp } ?: motion.timestamp
            } else {
                motion.timestamp
            }
            sessionKey = currentSessionKey
        }
        val sessionSamples = samples.filter { sample ->
            sessionStart?.let { sample.timestamp >= it } ?: true
        }
        NavigationEnergyEvidence(
            vehicleData = vehicleData,
            motion = motion,
            consumption = consumptionEvidence(motion, sessionSamples),
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

    private fun consumptionEvidence(
        motion: ControllerData,
        samples: List<ControllerData>,
    ): ConsumptionEvidence? {
        val measuredCounterConsumption = MotionReadings.sessionWhPerKm(motion)
        if (motion.hasEnergyCounters && measuredCounterConsumption != null) {
            val counterSamples = samples.filter { it.hasEnergyCounters }
            return ConsumptionEvidence(
                whPerKm = measuredCounterConsumption.toDouble(),
                distanceKm = motion.tripKm.toDouble(),
                durationMillis = durationMillis(counterSamples),
                measuredSampleCount = counterSamples.size,
                provenance = ConsumptionProvenance.CONTROLLER_COUNTERS,
            )
        }

        val measuredPowerSamples = samples.filter { MotionReadings.powerW(it) != null }
        val ride = RideEnergy.windowedRide(measuredPowerSamples, since = null) ?: return null
        val whPerKm = if (ride.km > 0f) ride.wh.toDouble() / ride.km.toDouble() else return null
        if (!whPerKm.isFinite()) return null
        return ConsumptionEvidence(
            whPerKm = whPerKm,
            distanceKm = ride.km.toDouble(),
            durationMillis = durationMillis(measuredPowerSamples),
            measuredSampleCount = measuredPowerSamples.size,
            provenance = ConsumptionProvenance.POWER_INTEGRAL,
        )
    }

    private fun durationMillis(samples: List<ControllerData>): Long {
        val first = samples.minOfOrNull { it.timestamp } ?: return 0L
        val last = samples.maxOfOrNull { it.timestamp } ?: return 0L
        return (last - first).inWholeMilliseconds.coerceAtLeast(0L)
    }
}
