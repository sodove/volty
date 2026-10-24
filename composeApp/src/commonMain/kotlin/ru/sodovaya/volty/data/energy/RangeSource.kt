package ru.sodovaya.volty.data.energy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import ru.sodovaya.volty.domain.energy.RangeEstimate
import ru.sodovaya.volty.domain.energy.RangeEstimator
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.VehicleData
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
    /** Called once per real computation; tests count it to prove the throttle sits BEFORE the work. */
    private val onCompute: () -> Unit = {},
) : RangeSource {
    private val session = SessionSamples()

    private companion object {
        /**
         * Range moves slowly; the combine fires twice per motion tick (`activeMotion` and
         * `motionSamples` both derive from the same motion flow) and the computation makes several
         * passes over a buffer of up to 4 h, so the throttle sits before it. About once a second
         * is plenty.
         */
        const val RECOMPUTE_INTERVAL_MILLIS = 1_000L
    }

    /** Cheap snapshot of the inputs: no filtering or building happens before the throttle. */
    private class Inputs(
        val vehicleData: VehicleData,
        val motion: ControllerData,
        val samples: List<ControllerData>,
        val vehicleId: String?,
        val connected: Boolean,
    )

    override val range: StateFlow<RangeEstimate?> = combine(
        bmsRepository.activeVehicleData,
        bmsRepository.activeMotion,
        bmsRepository.motionSamples(RideEnergy.SESSION_WINDOW),
        bmsRepository.activeVehicle,
        bmsRepository.connectionState,
    ) { vehicleData, motion, samples, vehicle, connectionState ->
        Inputs(vehicleData, motion, samples, vehicle?.id, connectionState is ConnectionState.Connected)
    }.sample(RECOMPUTE_INTERVAL_MILLIS)
        .map { inputs ->
            onCompute()
            val sessionSamples = session.of(
                vehicleId = inputs.vehicleId,
                connected = inputs.connected,
                motion = inputs.motion,
                samples = inputs.samples,
            )
            RangeEstimator.estimate(
                energy = inputs.vehicleData.energy,
                consumption = ConsumptionEvidenceBuilder.build(inputs.motion, sessionSamples),
                recentWhPerKm = ConsumptionEvidenceBuilder.recentWhPerKm(sessionSamples, RangeEstimator.RECENT_KM),
            )
        }
        .stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = null)
}
