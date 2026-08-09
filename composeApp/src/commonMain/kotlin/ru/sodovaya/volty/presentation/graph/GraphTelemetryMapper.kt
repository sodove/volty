package ru.sodovaya.volty.presentation.graph

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.stats.BmsReadings
import kotlin.time.ExperimentalTime

/** Evidence-aware mapping from decoded domain samples to graph points. */
object GraphTelemetryMapper {
    @OptIn(ExperimentalTime::class)
    fun battery(data: BmsData, metric: GraphMetric): GraphPoint? {
        if (metric.source != GraphSource.BATTERY) return null
        val raw = when (metric) {
            GraphMetric.SOC -> data.soc.takeIf { data.socKnown }
            GraphMetric.POWER -> BmsReadings.power(data)
            GraphMetric.CURRENT -> BmsReadings.current(data)
            GraphMetric.VOLTAGE -> data.voltage.takeIf { it > 0f }
            GraphMetric.TEMPERATURE -> data.temperatures.maxOrNull()
            GraphMetric.CELL_MIN_V -> data.cellVoltages.minOrNull()
            GraphMetric.CELL_MAX_V -> data.cellVoltages.maxOrNull()
            GraphMetric.CELL_DELTA_MV -> cellDeltaMv(data.cellVoltages)
            else -> null
        } ?: return null
        val displayed = raw * metric.displaySign
        return GraphPoint(data.timestamp, if (displayed == 0f) 0f else displayed)
    }

    @OptIn(ExperimentalTime::class)
    fun motion(data: ControllerData, metric: GraphMetric): GraphPoint? {
        if (metric.source != GraphSource.MOTION) return null
        val raw = when (metric) {
            GraphMetric.SPEED -> data.speedKmh.takeIf { data.speedKnown }
            GraphMetric.DUTY -> data.dutyPercent.takeIf { data.hasDuty }
            GraphMetric.MOTOR_CURRENT -> data.motorCurrentA
            GraphMetric.INPUT_VOLTAGE -> data.inputVoltageV.takeIf { data.hasInputVoltage }
            GraphMetric.MOTOR_POWER -> data.powerW.takeIf { data.hasPower }
            GraphMetric.ERPM -> data.eRpm
            GraphMetric.ESC_TEMPERATURE -> data.escTempC.takeIf { data.hasEscTemp }
            GraphMetric.MOTOR_TEMPERATURE -> data.motorTempC.takeIf { data.hasMotorTemp }
            else -> null
        } ?: return null
        val displayed = raw * metric.displaySign
        return GraphPoint(data.timestamp, if (displayed == 0f) 0f else displayed)
    }

    @OptIn(ExperimentalTime::class)
    fun batterySeries(samples: List<BmsData>, metric: GraphMetric): GraphSeries =
        GraphSeries(metric, samples.mapNotNull { battery(it, metric) })

    @OptIn(ExperimentalTime::class)
    fun motionSeries(samples: List<ControllerData>, metric: GraphMetric): GraphSeries =
        GraphSeries(metric, samples.mapNotNull { motion(it, metric) })

    private fun cellDeltaMv(cells: List<Float>): Float? {
        if (cells.isEmpty()) return null
        return (cells.maxOrNull()!! - cells.minOrNull()!!) * 1000f
    }
}
