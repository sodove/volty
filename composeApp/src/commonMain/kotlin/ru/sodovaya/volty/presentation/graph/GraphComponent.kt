package ru.sodovaya.volty.presentation.graph

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.repository.BmsRepository
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
        val nowValue: Float = 0f,
        val avg: Float = 0f,
        val peak: Float = 0f,
        val min: Float = 0f,
        val used: Float = 0f
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
        val values = samples.map { displaySign * extractValue(it, metric) }
        val avg = if (values.isEmpty()) 0f else values.average().toFloat()
        val peak = if (values.isEmpty()) 0f else values.max()
        val min = if (values.isEmpty()) 0f else values.min()
        val used = computeUsed(samples, metric)
        return prev.copy(
            values = values,
            nowValue = values.lastOrNull() ?: 0f,
            avg = avg,
            peak = peak,
            min = min,
            used = used
        )
    }

    private fun extractValue(d: BmsData, metric: GraphMetric): Float = extractValueOf(d, metric)

    private fun computeUsed(samples: List<BmsData>, metric: GraphMetric): Float {
        if (samples.size < 2) return 0f
        var acc = 0.0
        for (i in 1 until samples.size) {
            val dtHours = (samples[i].timestamp - samples[i - 1].timestamp).inWholeMilliseconds / 1000.0 / 3600.0
            val v = (extractValue(samples[i - 1], metric) + extractValue(samples[i], metric)) / 2.0
            acc += v * dtHours
        }
        // Consumption-positive: negate so a discharge session reports positive
        // Wh/Ah used (charging periods subtract -> net consumption).
        return when (metric) {
            GraphMetric.POWER -> -acc.toFloat()                 // Wh
            GraphMetric.CURRENT -> -acc.toFloat()               // Ah
            else -> 0f
        }
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

private fun extractValueOf(d: BmsData, metric: GraphMetric): Float = when (metric) {
    GraphMetric.SOC -> d.soc
    GraphMetric.POWER -> d.power
    GraphMetric.CURRENT -> d.current
    GraphMetric.VOLTAGE -> d.voltage
    GraphMetric.TEMPERATURE -> d.temperatures.firstOrNull() ?: 0f
}
