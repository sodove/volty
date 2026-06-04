package ru.sodovaya.volty.domain.usecase

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.notification.Notifier
import ru.sodovaya.volty.util.formatFixed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class AlertEngine(
    private val bmsRepository: BmsRepository,
    private val notifier: Notifier,
    private val clock: () -> Instant = { Clock.System.now() }
) {

    private val lastFired = mutableMapOf<Pair<String, AlertKind>, Instant>()
    private val armed = mutableMapOf<Pair<String, AlertKind>, Boolean>()

    private val debounce = 3.seconds
    private var alertCounter = 100

    fun start(scope: CoroutineScope) {
        scope.launch {
            bmsRepository.activeData
                .combine(bmsRepository.activeVehicle) { d, v -> d to v }
                .distinctUntilChanged()
                .collect { (data, vehicle) -> evaluate(data, vehicle) }
        }
    }

    fun evaluateForTest(data: BmsData, vehicle: Vehicle?) = evaluate(data, vehicle)

    private fun evaluate(data: BmsData, vehicle: Vehicle?) {
        if (vehicle == null || !data.isConnected) return
        val cfg = resolveAlertConfig(vehicle.alertConfig, vehicle.chemistry)
        val now = clock()

        val cells = data.cellVoltages
        val maxCell = cells.maxOrNull() ?: 0f
        val minCell = cells.minOrNull() ?: 0f
        val deltaMv = ((maxCell - minCell) * 1000f).toInt()
        val maxTemp = data.temperatures.maxOrNull() ?: 0f

        fire(AlertKind.CELL_HIGH, vehicle, now,
            triggered = maxCell > cfg.cellHighV,
            recovered = maxCell < cfg.cellHighV - 0.05f,
            severity = AlertSeverity.CRITICAL,
            title = "Cell voltage high",
            text = "Max cell ${formatV(maxCell)} V on ${vehicle.name}"
        )

        if (minCell > 0.1f) {
            fire(AlertKind.CELL_LOW, vehicle, now,
                triggered = minCell < cfg.cellLowV,
                recovered = minCell > cfg.cellLowV + 0.05f,
                severity = AlertSeverity.CRITICAL,
                title = "Cell voltage low",
                text = "Min cell ${formatV(minCell)} V on ${vehicle.name}"
            )
        }

        if (cells.isNotEmpty()) {
            fire(AlertKind.CELL_DELTA, vehicle, now,
                triggered = deltaMv > cfg.cellDeltaMv,
                recovered = deltaMv < cfg.cellDeltaMv - 30,
                severity = AlertSeverity.WARNING,
                title = "Cell imbalance",
                text = "Δ ${deltaMv} mV on ${vehicle.name}"
            )
        }

        if (data.temperatures.isNotEmpty()) {
            fire(AlertKind.TEMPERATURE_HIGH, vehicle, now,
                triggered = maxTemp > cfg.temperatureHighC,
                recovered = maxTemp < cfg.temperatureHighC - 3f,
                severity = AlertSeverity.CRITICAL,
                title = "Temperature high",
                text = "${maxTemp.toInt()}°C on ${vehicle.name}"
            )
        }

        fire(AlertKind.SOC_LOW, vehicle, now,
            triggered = data.soc.toInt() < cfg.socLowPercent,
            recovered = data.soc.toInt() > cfg.socLowPercent + 3,
            severity = AlertSeverity.WARNING,
            title = "Battery low",
            text = "${data.soc.toInt()}% on ${vehicle.name}"
        )

        cfg.socCutoffPercent?.let { cutoff ->
            fire(AlertKind.SOC_CUTOFF, vehicle, now,
                triggered = data.soc.toInt() < cutoff,
                recovered = data.soc.toInt() > cutoff + 2,
                severity = AlertSeverity.CRITICAL,
                title = "Discharge cutoff",
                text = "${data.soc.toInt()}% — stop now (${vehicle.name})"
            )
        }

        if (cfg.chargeCompleteNotify) {
            val isFull = data.soc >= 99.9f && abs(data.current) < 0.1f
            fire(AlertKind.CHARGE_COMPLETE, vehicle, now,
                triggered = isFull,
                recovered = data.soc < 98f,
                severity = AlertSeverity.INFO,
                title = "Charge complete",
                text = "${vehicle.name} reached 100%"
            )
        }
    }

    private fun fire(
        kind: AlertKind, vehicle: Vehicle, now: Instant,
        triggered: Boolean, recovered: Boolean,
        severity: AlertSeverity, title: String, text: String
    ) {
        val key = vehicle.id to kind
        val isArmed = armed.getOrPut(key) { true }
        if (recovered && !isArmed) armed[key] = true
        if (!triggered || !isArmed) return
        val last = lastFired[key]
        if (last != null && (now - last) < debounce) return
        lastFired[key] = now
        armed[key] = false
        notifier.showAlert(title, text, severity, alertCounter++)
    }

    private fun formatV(v: Float): String = formatFixed(v, 2)
}
