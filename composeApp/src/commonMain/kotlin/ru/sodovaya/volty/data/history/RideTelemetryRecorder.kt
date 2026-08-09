package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.RideHistoryRepository
import ru.sodovaya.volty.domain.repository.RidePoint
import ru.sodovaya.volty.domain.repository.RideSummary
import ru.sodovaya.volty.presentation.graph.GraphMetric
import ru.sodovaya.volty.presentation.graph.GraphPoint
import ru.sodovaya.volty.presentation.graph.GraphSource
import ru.sodovaya.volty.presentation.graph.GraphTelemetryMapper
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Turns the repository's retained aggregate streams into bounded ride history.
 * It never participates in the BLE callback transaction: each flow collector
 * sends only immutable decoded points to the history repository.
 */
@OptIn(ExperimentalTime::class)
class RideTelemetryRecorder(
    private val activeVehicle: StateFlow<Vehicle?>,
    private val connectionState: StateFlow<ConnectionState>,
    private val batterySamples: (Duration) -> Flow<List<BmsData>>,
    private val motionSamples: (Duration) -> Flow<List<ControllerData>>,
    private val history: RideHistoryRepository,
    private val context: kotlin.coroutines.CoroutineContext,
    private val now: () -> Instant = { Clock.System.now() },
    private val bucket: Duration = 5.seconds,
    private val maxRides: Int = 50
) {
    private val scope = CoroutineScope(context + SupervisorJob())
    private var stateJob: Job? = null
    private var batteryJob: Job? = null
    private var motionJob: Job? = null
    private var active: ActiveRide? = null
    private var sequence = 0L

    private data class PendingKey(val metricKey: String, val cellIndex: Int?)

    private data class PendingPoint(val bucket: Long, val point: RidePoint)

    private class ActiveRide(
        val summary: RideSummary,
        val pending: MutableMap<PendingKey, PendingPoint> = mutableMapOf(),
        var lastBatteryTimestamp: Instant? = null,
        var lastMotionTimestamp: Instant? = null
    )

    fun start(): Job {
        if (stateJob != null) return requireNotNull(stateJob)
        stateJob = scope.launch {
            combine(activeVehicle, connectionState) { vehicle, state -> vehicle to state }
                .collect { (vehicle, state) -> handleConnection(vehicle, state) }
        }
        batteryJob = scope.launch {
            batterySamples(4.hours).collect { samples ->
                val ride = active ?: return@collect
                samples.filter { sample ->
                    ride.lastBatteryTimestamp?.let { sample.timestamp > it } ?: true
                }.forEach { sample ->
                    ride.lastBatteryTimestamp = sample.timestamp
                    recordBattery(sample)
                }
            }
        }
        motionJob = scope.launch {
            motionSamples(4.hours).collect { samples ->
                val ride = active ?: return@collect
                samples.filter { sample ->
                    ride.lastMotionTimestamp?.let { sample.timestamp > it } ?: true
                }.forEach { sample ->
                    ride.lastMotionTimestamp = sample.timestamp
                    recordMotion(sample)
                }
            }
        }
        return requireNotNull(stateJob)
    }

    suspend fun stop() {
        finishActive(now())
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun handleConnection(vehicle: Vehicle?, state: ConnectionState) {
        if (state is ConnectionState.Connected) {
            val connectedVehicle = state.vehicle ?: vehicle ?: return
            if (active?.summary?.vehicleId != connectedVehicle.id) {
                finishActive(now())
                val summary = RideSummary(
                    id = "ride-${now().toEpochMilliseconds()}-${sequence++}",
                    vehicleId = connectedVehicle.id,
                    startedAt = now(),
                    endedAt = null
                )
                history.startRide(summary)
                active = ActiveRide(summary)
            }
        } else if (state is ConnectionState.Idle ||
            state is ConnectionState.Disconnected ||
            state is ConnectionState.Failed
        ) {
            finishActive(now())
        }
    }

    private suspend fun recordBattery(sample: BmsData) {
        GraphMetric.values().filter { it.source == GraphSource.BATTERY }.forEach { metric ->
            GraphTelemetryMapper.battery(sample, metric)?.let { point -> append(metric, point) }
        }
    }

    private suspend fun recordMotion(sample: ControllerData) {
        GraphMetric.values().filter { it.source == GraphSource.MOTION }.forEach { metric ->
            GraphTelemetryMapper.motion(sample, metric)?.let { point -> append(metric, point) }
        }
    }

    private suspend fun append(metric: GraphMetric, point: GraphPoint) {
        val ride = active ?: return
        val bucketSeconds = bucket.inWholeSeconds.coerceAtLeast(1L)
        val bucketIndex = point.timestamp.epochSeconds / bucketSeconds
        val key = PendingKey(metric.name, null)
        val previous = ride.pending[key]
        if (previous != null && previous.bucket != bucketIndex) {
            history.appendPoint(ride.summary.id, previous.point)
        }
        ride.pending[key] = PendingPoint(
            bucket = bucketIndex,
            point = RidePoint(metric.name, point.timestamp, point.value)
        )
    }

    private suspend fun finishActive(endedAt: Instant) {
        val ride = active ?: return
        ride.pending.values.forEach { pending -> history.appendPoint(ride.summary.id, pending.point) }
        history.finishRide(ride.summary.id, endedAt)
        history.pruneOldest(maxRides)
        active = null
    }
}

/** Adapter used by Kable's existing repository constructor when persistence is absent. */
class RepositoryRideTelemetryRecorder(
    repository: BmsRepository,
    history: RideHistoryRepository,
    context: kotlin.coroutines.CoroutineContext
) {
    @OptIn(ExperimentalTime::class)
    val delegate = RideTelemetryRecorder(
        activeVehicle = repository.activeVehicle,
        connectionState = repository.connectionState,
        batterySamples = repository::samples,
        motionSamples = repository::motionSamples,
        history = history,
        context = context
    )
}
