package ru.sodovaya.volty.presentation.graph

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.stats.BmsReadings
import ru.sodovaya.volty.domain.stats.RideEnergy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

enum class GraphMetric(val label: String, val unit: String) {
    SOC("SOC", "%"),
    POWER("Power", "W"),
    CURRENT("Current", "A"),
    VOLTAGE("Volt", "V"),
    TEMPERATURE("Temp", "°C")
}

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
    fun onWindowSelected(window: GraphWindow)
    fun onBack()

    data class State(
        val metric: GraphMetric = GraphMetric.POWER,
        val window: GraphWindow = GraphWindow.M5,
        val values: List<Float> = emptyList(),
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
    private val onBackRequested: () -> Unit
) : GraphComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(
        GraphComponent.State(
            nowValue = extractValueOf(bmsRepository.activeData.value, GraphMetric.POWER)
        )
    )
    override val state: StateFlow<GraphComponent.State> = _state.asStateFlow()

    private var sampleJob: Job? = null

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }
        restartCollection()
    }

    private fun restartCollection() {
        sampleJob?.cancel()
        val window = _state.value.window.duration ?: 6.hours  // ALL uses a large window
        sampleJob = scope.launch {
            bmsRepository.samples(window).collect { samples ->
                _state.update { computeStats(it, samples) }
            }
        }
    }

    private fun computeStats(prev: GraphComponent.State, samples: List<BmsData>): GraphComponent.State {
        val metric = prev.metric
        // Graphs are consumption-positive: discharge plots upward. The domain
        // convention is "+ = charging", so for POWER/CURRENT we negate the series
        // for display. Every derived stat (now/avg/peak/min/used) is then computed
        // from the negated values so "Peak" = peak consumption and "Used" is the
        // net Wh/Ah consumed over the window. SOC/VOLTAGE/TEMPERATURE are unchanged.
        val displaySign = if (metric == GraphMetric.POWER || metric == GraphMetric.CURRENT) -1f else 1f
        val values = samples.mapNotNull { sample ->
            extractValue(sample, metric)?.let { value ->
                (displaySign * value).takeUnless { it == 0f } ?: 0f
            }
        }
        val avg = values.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val peak = values.maxOrNull()
        val min = values.minOrNull()
        val used = computeUsed(samples, metric)
        return prev.copy(
            values = values,
            nowValue = values.lastOrNull(),
            avg = avg,
            peak = peak,
            min = min,
            used = used
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
        _state.update { it.copy(metric = metric) }
        restartCollection()
    }

    override fun onWindowSelected(window: GraphWindow) {
        _state.update { it.copy(window = window) }
        restartCollection()
    }

    override fun onBack() { onBackRequested() }
}

private fun extractValueOf(d: BmsData, metric: GraphMetric): Float? = when (metric) {
    GraphMetric.SOC -> d.soc.takeIf { d.socKnown }
    GraphMetric.POWER -> BmsReadings.power(d)
    GraphMetric.CURRENT -> BmsReadings.current(d)
    GraphMetric.VOLTAGE -> d.voltage.takeIf { it > 0f }
    GraphMetric.TEMPERATURE -> d.temperatures.maxOrNull()
}
