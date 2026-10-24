package ru.sodovaya.volty.data.energy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import ru.sodovaya.volty.domain.energy.RangeEstimate
import ru.sodovaya.volty.domain.energy.RangeEstimator
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.navigation.ConsumptionEvidenceBuilder
import ru.sodovaya.volty.domain.navigation.SessionSamples
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.stats.RideEnergy

interface RangeSource {
    /** Null until the range is earned (a capacity, ~1 km and a minimum sample count behind it). */
    val range: StateFlow<RangeEstimate?>
}

@OptIn(FlowPreview::class)
class BmsRangeSource(
    bmsRepository: BmsRepository,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : RangeSource {
    private val session = SessionSamples()

    private companion object {
        /**
         * Range moves slowly; the combine fires twice per motion tick (`activeMotion` and
         * `motionSamples` both derive from the same motion flow) and each fire makes several
         * passes over a buffer of up to 4 h. About once a second is plenty.
         */
        const val RECOMPUTE_INTERVAL_MILLIS = 1_000L
    }

    override val range: StateFlow<RangeEstimate?> = combine(
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
        RangeEstimator.estimate(
            energy = vehicleData.energy,
            consumption = ConsumptionEvidenceBuilder.build(motion, sessionSamples),
            recentWhPerKm = ConsumptionEvidenceBuilder.recentWhPerKm(sessionSamples, RangeEstimator.RECENT_KM),
        )
    }.sample(RECOMPUTE_INTERVAL_MILLIS)
        .stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = null)
}
