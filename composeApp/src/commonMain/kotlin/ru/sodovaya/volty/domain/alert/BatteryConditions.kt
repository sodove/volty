package ru.sodovaya.volty.domain.alert

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.usecase.AlertKind
import ru.sodovaya.volty.domain.usecase.AlertSeverity
import ru.sodovaya.volty.domain.usecase.ResolvedAlertConfig

/**
 * One battery alert's verdict for one sample: whether it [triggered], whether it [recovered]
 * (the release band, deliberately wider than the trigger), and how severe it is.
 * [value] is the reading that describes it (volts for cells, millivolts for CELL_DELTA, degrees C,
 * percent). A kind that is [enabled] = false must be forgotten by the consumer.
 */
internal data class BatteryCondition(
    val kind: AlertKind,
    val enabled: Boolean,
    val value: Float,
    val triggered: Boolean,
    val recovered: Boolean,
    val severity: AlertSeverity
)

/**
 * The battery alerts' trigger and release bands, guards and severities - **the single copy**.
 * `AlertEngine` (notifications) and `ActiveAlertTracker` (dashboard banner) both read it, so the
 * banner and the notification cannot disagree about when a battery alert is on (rider alerts,
 * Task 4 review I-1). CHARGE_COMPLETE and the fault kinds are not conditions of this kind and
 * stay in the engine.
 *
 * [temp] is the **confirmed** max-sensor temperature ([TemperatureGate]); null means the gate does
 * not believe the reading yet and the two temperature kinds are left out, so the consumer holds
 * its previous decision. Engage on [TemperatureGate.Confirmed.min], release on `.max`.
 */
internal fun batteryConditions(
    data: BmsData,
    cfg: ResolvedAlertConfig,
    temp: TemperatureGate.Confirmed?
): List<BatteryCondition> {
    val cells = data.cellVoltages
    val maxCell = cells.maxOrNull() ?: 0f
    val minCell = cells.minOrNull() ?: 0f
    val deltaMv = ((maxCell - minCell) * 1000f).toInt()
    val out = ArrayList<BatteryCondition>(8)

    out += BatteryCondition(AlertKind.CELL_HIGH, cfg.cellHighEnabled, maxCell,
        triggered = maxCell > cfg.cellHighV, recovered = maxCell < cfg.cellHighV - 0.05f,
        severity = AlertSeverity.CRITICAL)
    if (minCell > 0.1f) {
        out += BatteryCondition(AlertKind.CELL_LOW, cfg.cellLowEnabled, minCell,
            triggered = minCell < cfg.cellLowV, recovered = minCell > cfg.cellLowV + 0.05f,
            severity = AlertSeverity.CRITICAL)
    }
    if (cells.isNotEmpty()) {
        out += BatteryCondition(AlertKind.CELL_DELTA, cfg.cellDeltaEnabled, deltaMv.toFloat(),
            triggered = deltaMv > cfg.cellDeltaMv, recovered = deltaMv < cfg.cellDeltaMv - 30,
            severity = AlertSeverity.WARNING)
    }
    if (temp != null) {
        // Two tiers off the SAME max-sensor reading. WARN fires only in the band [warn, high) so a
        // rising trend escalates warn -> high instead of emitting both at once; HIGH owns
        // everything at/above the critical threshold (where the BMS trips its own protection).
        out += BatteryCondition(AlertKind.TEMPERATURE_WARN, cfg.temperatureWarnEnabled, temp.latest,
            triggered = temp.min > cfg.temperatureWarnC && temp.min < cfg.temperatureHighC,
            recovered = temp.max < cfg.temperatureWarnC - 3f,
            severity = AlertSeverity.WARNING)
        out += BatteryCondition(AlertKind.TEMPERATURE_HIGH, cfg.temperatureHighEnabled, temp.latest,
            triggered = temp.min > cfg.temperatureHighC, recovered = temp.max < cfg.temperatureHighC - 3f,
            severity = AlertSeverity.CRITICAL)
    }
    // The SoC kinds are the ONLY ones gated on socKnown: a dumb Begode with no cell count publishes
    // soc = 0 meaning "unknown", not "empty". A genuine 0 % arrives with socKnown = true.
    if (data.socKnown) {
        out += BatteryCondition(AlertKind.SOC_LOW, cfg.socLowEnabled, data.soc,
            triggered = data.soc.toInt() < cfg.socLowPercent,
            recovered = data.soc.toInt() > cfg.socLowPercent + 3,
            severity = AlertSeverity.WARNING)
        cfg.socCutoffPercent?.let { cutoff ->
            out += BatteryCondition(AlertKind.SOC_CUTOFF, cfg.socCutoffEnabled, data.soc,
                triggered = data.soc.toInt() < cutoff, recovered = data.soc.toInt() > cutoff + 2,
                severity = AlertSeverity.CRITICAL)
        }
    }
    return out
}
