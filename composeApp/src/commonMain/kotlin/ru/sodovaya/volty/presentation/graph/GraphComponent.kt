package ru.sodovaya.volty.presentation.graph

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.NoOpRideHistoryRepository
import ru.sodovaya.volty.domain.repository.RideHistoryRepository
import ru.sodovaya.volty.domain.repository.RideSummary
import ru.sodovaya.volty.domain.stats.RideEnergy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

enum class GraphWindow(val label: String, val duration: Duration?) {
    M1("1m", 1.minutes),
    M5("5m", 5.minutes),
    M15("15m", 15.minutes),
    H1("1h", 1.hours),
    ALL("All", null)
}

interface GraphComponent {
    val state: StateFlow<State>
    fun onMetricSelected(metric: GraphMetric)
    fun onMetricAdded(metric: GraphMetric) {}
    fun onMetricRemoved(metric: GraphMetric) {}
    fun onWindowSelected(window: GraphWindow)
    fun onTimestampSelected(timestamp: Instant?) {}
    fun onComparisonRequested(x: GraphMetric, y: GraphMetric) {}
    fun onRideSelected(rideId: String?) {}
    fun onBack()

    data class State(
        val metric: GraphMetric = GraphMetric.POWER,
        val window: GraphWindow = GraphWindow.M5,
        val values: List<Float> = emptyList(),
        /** Timestamped series for every visible card. */
        val series: Map<GraphMetric, GraphSeries> = emptyMap(),
        val visibleMetrics: List<GraphMetric> = listOf(GraphMetric.POWER),
        val selectedTimestamp: Instant? = null,
        val selectedPoints: Map<GraphMetric, GraphPoint> = emptyMap(),
        val history: List<RideSummary> = emptyList(),
        val selectedRideId: String? = null,
        /** Null means no measured sample exists for this metric in the window. */
        val nowValue: Float? = null,
        val avg: Float? = null,
        val peak: Float? = null,
        val min: Float? = null,
        /** Null means no measured rate interval exists to integrate. */
        val used: Float? = null
    )
}

@OptIn(ExperimentalTime::class)
class DefaultGraphComponent(
    componentContext: ComponentContext,
    private val bmsRepository: BmsRepository,
    private val onBackRequested: () -> Unit,
    private val rideHistoryRepository: RideHistoryRepository = NoOpRideHistoryRepository
) : GraphComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(
        GraphComponent.State(nowValue = initialValueOf(bmsRepository.activeData.value, GraphMetric.POWER))
    )
    override val state: StateFlow<GraphComponent.State> = _state.asStateFlow()

    private var sampleJob: Job? = null
    private var latestSamples: List<BmsData> = emptyList()
    private var latestMotion: List<ControllerData> = emptyList()

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }
        scope.launch { loadHistory() }
        restartCollection()
    }

    private suspend fun loadHistory() {
        val vehicleId = bmsRepository.activeVehicle.value?.id
        _state.update { it.copy(history = rideHistoryRepository.listRides(vehicleId)) }
    }

    private fun restartCollection() {
        sampleJob?.cancel()
        val window = _state.value.window.duration ?: 6.hours  // ALL uses a large window
        sampleJob = scope.launch {
            combine(
                bmsRepository.samples(window),
                bmsRepository.motionSamples(window)
            ) { samples, motion -> samples to motion }.collect { (samples, motion) ->
                latestSamples = samples
                latestMotion = motion
                _state.update {
                    if (it.selectedRideId == null) computeStats(it, samples, motion) else it
                }
            }
        }
    }

    private fun computeStats(
        prev: GraphComponent.State,
        samples: List<BmsData>,
        motion: List<ControllerData>
    ): GraphComponent.State {
        val metric = prev.metric
        // Graphs are consumption-positive: discharge plots upward. The domain
        // convention is "+ = charging", so for POWER/CURRENT we negate the series
        // for display. Every derived stat (now/avg/peak/min/used) is then computed
        // from the negated values so "Peak" = peak consumption and "Used" is the
        // net Wh/Ah consumed over the window. SOC/VOLTAGE/TEMPERATURE are unchanged.
        val series = prev.visibleMetrics.associateWith { selected ->
            if (selected.source == GraphSource.BATTERY) {
                GraphTelemetryMapper.batterySeries(samples, selected)
            } else {
                GraphTelemetryMapper.motionSeries(motion, selected)
            }
        }
        return computeDerivedStats(prev, series, computeUsed(samples, metric))
    }

    private fun computeDerivedStats(
        prev: GraphComponent.State,
        series: Map<GraphMetric, GraphSeries>,
        used: Float? = null
    ): GraphComponent.State {
        val metric = prev.metric
        val activeSeries = series[metric]?.points.orEmpty()
        val values = activeSeries.map { it.value }
        val avg = values.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val peak = values.maxOrNull()
        val min = values.minOrNull()
        val selectedPoints = prev.selectedTimestamp?.let { timestamp ->
            series.mapNotNull { (selected, current) ->
                nearestPoint(current.points, timestamp)?.let { selected to it }
            }.toMap()
        }.orEmpty()
        return prev.copy(
            series = series,
            values = values,
            nowValue = values.lastOrNull(),
            avg = avg,
            peak = peak,
            min = min,
            used = used,
            selectedPoints = selectedPoints
        )
    }

    private fun extractValue(d: BmsData, metric: GraphMetric): Float? = extractValueOf(d, metric)

    /**
     * Wh (POWER) or Ah (CURRENT) over the window; 0 for the metrics that are not
     * rates and have no integral worth showing.
     *
     * The trapezoid itself lives in [RideEnergy.integrateHours], because the Ride
     * dashboard now integrates the same quantity for a wheel that keeps no energy
     * counters and two copies of this arithmetic would drift apart.
     *
     * **The negation stays HERE, and must not move into the shared integrator.**
     * [BmsData] is charge-positive (`+ = charging`), so a discharge integrates
     * negative and is flipped so the readout is consumption-positive, with
     * charging periods subtracting into a net figure. The other caller's samples
     * are [ru.sodovaya.volty.domain.model.ControllerData], whose `powerW` carries
     * VESC's *opposite* convention and therefore does not get flipped — see
     * [RideEnergy]'s KDoc.
     */
    private fun computeUsed(samples: List<BmsData>, metric: GraphMetric): Float? {
        if (metric != GraphMetric.POWER && metric != GraphMetric.CURRENT) return null
        val measured = samples.filter { extractValue(it, metric) != null }
        val integral = RideEnergy.integrateHours(
            measured,
            timestampOf = { it.timestamp }
        ) { requireNotNull(extractValue(it, metric)) } ?: return null
        return (-integral.toFloat()).takeUnless { it == 0f } ?: 0f
    }

    override fun onMetricSelected(metric: GraphMetric) {
        _state.update { current ->
            val metrics = if (metric in current.visibleMetrics) {
                current.visibleMetrics
            } else {
                listOf(metric) + current.visibleMetrics.drop(1)
            }
            val updated = current.copy(metric = metric, visibleMetrics = metrics)
            if (current.selectedRideId == null) {
                computeStats(updated, latestSamples, latestMotion)
            } else {
                computeDerivedStats(updated, updated.series, used = null)
            }
        }
        // The repository flows are hot and continue to emit; this merely keeps
        // the legacy method's contract of refreshing immediately after a tab tap.
        restartCollection()
    }

    override fun onMetricAdded(metric: GraphMetric) {
        _state.update { current ->
            if (metric in current.visibleMetrics) current
            else {
                val updated = current.copy(visibleMetrics = current.visibleMetrics + metric)
                if (current.selectedRideId == null) computeStats(updated, latestSamples, latestMotion)
                else computeDerivedStats(updated, updated.series + (metric to GraphSeries(metric, emptyList())))
            }
        }
    }

    override fun onMetricRemoved(metric: GraphMetric) {
        _state.update { current ->
            if (current.visibleMetrics.size <= 1 || metric !in current.visibleMetrics) return@update current
            val remaining = current.visibleMetrics - metric
            val updated = current.copy(
                    visibleMetrics = remaining,
                    metric = if (current.metric == metric) remaining.first() else current.metric
                )
            if (current.selectedRideId == null) computeStats(updated, latestSamples, latestMotion)
            else computeDerivedStats(updated, updated.series - metric)
        }
    }

    override fun onTimestampSelected(timestamp: Instant?) {
        _state.update { current ->
            val selected = timestamp?.let { target ->
                current.series.mapNotNull { (metric, series) ->
                    nearestPoint(series.points, target)?.let { metric to it }
                }.toMap()
            }.orEmpty()
            current.copy(selectedTimestamp = timestamp, selectedPoints = selected)
        }
    }

    override fun onRideSelected(rideId: String?) {
        scope.launch {
            if (rideId == null) {
                _state.update { current ->
                    current.copy(selectedRideId = null, selectedTimestamp = null, selectedPoints = emptyMap())
                }
                restartCollection()
                return@launch
            }
            val stored = rideHistoryRepository.loadRide(rideId) ?: return@launch
            val loaded = stored.points.mapNotNull { point ->
                val metric = runCatching { GraphMetric.valueOf(point.metricKey) }.getOrNull() ?: return@mapNotNull null
                if (!point.isKnown) return@mapNotNull null
                metric to GraphPoint(point.timestamp, point.value)
            }.groupBy({ it.first }, { it.second })
                .mapValues { (metric, points) -> GraphSeries(metric, points.sortedBy { it.timestamp }) }
            sampleJob?.cancel()
            _state.update { current ->
                val series = current.visibleMetrics.associateWith { metric -> loaded[metric] ?: GraphSeries(metric, emptyList()) }
                computeDerivedStats(
                    current.copy(selectedRideId = rideId, series = series, selectedTimestamp = null),
                    series,
                    used = null
                )
            }
        }
    }

    override fun onWindowSelected(window: GraphWindow) {
        _state.update { it.copy(window = window) }
        restartCollection()
    }

    override fun onBack() { onBackRequested() }
}

private fun extractValueOf(d: BmsData, metric: GraphMetric): Float? =
    GraphTelemetryMapper.battery(d, metric)?.value?.div(metric.displaySign)

/** The repository's default [BmsData] is a disconnected zero-shaped sentinel, not a sample. */
private fun initialValueOf(d: BmsData, metric: GraphMetric): Float? =
    extractValueOf(d, metric).takeIf { d.isConnected }
