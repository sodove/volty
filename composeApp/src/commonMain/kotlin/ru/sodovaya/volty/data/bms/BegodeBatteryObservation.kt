package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryReading
import ru.sodovaya.volty.domain.model.TelemetryUnknown

/** Only this accepted reply's assembly fields, never cached cells or another assembly's sensors. */
internal fun observedBegodeAssembly(frame: ByteArray, bootPlaceholder: Boolean,
    temperatureRange: IntRange): List<TelemetryReading> {
    val temperatures = listOf(frame.i16BE(10), frame.i16BE(12))
    val temperature = when {
        bootPlaceholder -> TelemetryReading(TelemetryMetric.BATTERY_TEMPERATURE, null, TelemetryUnknown.UNAVAILABLE)
        temperatures.any { it !in temperatureRange } ->
            TelemetryReading(TelemetryMetric.BATTERY_TEMPERATURE, null, TelemetryUnknown.PARTIAL_SOURCE)
        else -> TelemetryReading.measured(TelemetryMetric.BATTERY_TEMPERATURE, temperatures.max().toDouble())
    }
    // Retraction: the first version withheld this with the whole-branch ADC
    // calibration gate. The independent section field at 14 is already 0.1 V
    // per signed unit (WheelLog + ET Max section/cell-sum capture). It must not
    // borrow the whole-branch field at 6 or the 0x00 nominal-voltage scale.
    val rawVoltage = frame.i16BE(14)
    val voltage = when {
        bootPlaceholder -> TelemetryReading(TelemetryMetric.VOLTAGE, null, TelemetryUnknown.UNAVAILABLE)
        rawVoltage < 0 -> TelemetryReading(TelemetryMetric.VOLTAGE, null, TelemetryUnknown.INVALID_VALUE)
        else -> TelemetryReading.measured(TelemetryMetric.VOLTAGE, rawVoltage / 10.0)
    }
    return listOf(voltage, temperature)
}

internal fun observedBegodeBranch(frame: ByteArray, bootPlaceholder: Boolean,
    voltageCertified: Boolean = false): List<TelemetryReading> {
    val current = -(frame.i16BE(8) * 0.1)
    val voltage = frame.u16BE(6) * 0.1
    // Retraction: this frame's whole-pack voltage was never recorded, because nothing certified its
    // convention. Once the wheel's own live frame has agreed with it on a whole series count, it is
    // the pack voltage — the only one a wheel without cell pages has. Otherwise the old rule holds.
    // Not gated on the boot shape: a wheel without a smart BMS (the rider's T4) sends that all-zero
    // payload forever, with the pack voltage real beside it. The certification is what proves it.
    val certified = voltageCertified && voltage > 0
    // A boot-shaped (all-zero) payload carries no current and no power. Omit both rather than record an explicit unknown:
    // "no observation" is the contract for a frame that did not measure the metric ([TelemetryObservation]).
    //
    // Retraction (history flags): an earlier revision recorded CURRENT as UNAVAILABLE and POWER as PARTIAL_SOURCE on every
    // such frame. A wheel without a smart BMS (the rider's T4) sends this placeholder forever, so its pack `power_w` and
    // `current_a` were stored as 100 % unknown series: every bucket incomplete, an empty chart ("No data") that the
    // default chart selected, and the rider's 14-minute ride held 2753 of them. That was wrong for the same reason as
    // the controller-power fix (Task 6): an unknown is a claim about a sample, and the placeholder frame makes none.
    // A non-boot frame whose voltage is not yet certified still records POWER as PARTIAL_SOURCE (a real BMS, transient).
    return listOfNotNull(
    TelemetryReading.measured(TelemetryMetric.CURRENT, current).takeUnless { bootPlaceholder },
    if (certified) TelemetryReading.measured(TelemetryMetric.VOLTAGE, voltage)
    // The dashboard can substitute cached cell sums for nominal frame voltage. Neither certifies a fresh calibrated branch voltage here.
    else TelemetryReading(TelemetryMetric.VOLTAGE, null, TelemetryUnknown.PARTIAL_SOURCE),
    when {
        bootPlaceholder -> null
        certified -> TelemetryReading.measured(TelemetryMetric.POWER, voltage * current)
        else -> TelemetryReading(TelemetryMetric.POWER, null, TelemetryUnknown.PARTIAL_SOURCE)
    },
    TelemetryReading(TelemetryMetric.SOC, null, TelemetryUnknown.UNAVAILABLE),
    // Each frame refreshes only one assembly's two sensors, not the whole branch maximum.
    TelemetryReading(TelemetryMetric.BATTERY_TEMPERATURE, null,
        if (bootPlaceholder) TelemetryUnknown.UNAVAILABLE else TelemetryUnknown.PARTIAL_SOURCE),
    )
}

internal fun observedBegodeCells(frame: ByteArray, expectedCount: Int?, previouslySeen: Set<Int>): List<TelemetryReading> {
    val first = frame.u8(19) * 8
    val raw = (0 until 8).map { frame.u16BE(2 + it * 2) }
    val boot = raw.all { it == 0 }
    val cells = buildList {
        for (offset in 0 until 8) {
            val index = first + offset
            if (expectedCount != null && index >= expectedCount) continue
            when {
                boot && (expectedCount != null || index in previouslySeen) ->
                    add(TelemetryReading(TelemetryMetric.CELL, null, TelemetryUnknown.UNAVAILABLE, index))
                expectedCount != null || raw[offset] > 0 ->
                    add(TelemetryReading.measured(TelemetryMetric.CELL, raw[offset] / 1000.0, cellIndex = index))
                index in previouslySeen -> add(TelemetryReading(TelemetryMetric.CELL, null, TelemetryUnknown.UNAVAILABLE, index))
            }
        }
    }
    // Page positions do not identify a fresh complete acquisition round.
    val complete = !boot && first == 0 && expectedCount != null && expectedCount in 1..8 && cells.size == expectedCount
    val values = cells.mapNotNull { it.value }
    val minimum = values.minOrNull()
    val maximum = values.maxOrNull()
    // Audit note (telemetry plausibility, Task 6): this is the same SHAPE as the Begode 0x00 power artefact (a per-page
    // PARTIAL_SOURCE for a metric another page of the same source supplies), but it is NOT changed: the summary on a
    // page that carries only some cells is genuinely partial, and no test demonstrates the value/unknown alternation
    // emptying a chart here. Revisit only with such a test (a smart-BMS ride whose cell min/max overview collapses).
    fun summary(metric: TelemetryMetric, value: Double?) = if (complete) TelemetryReading(metric, checkNotNull(value))
        else TelemetryReading(metric, null, TelemetryUnknown.PARTIAL_SOURCE)
    return cells + listOf(summary(TelemetryMetric.CELL_MIN, minimum), summary(TelemetryMetric.CELL_MAX, maximum),
        summary(TelemetryMetric.CELL_DELTA, if (minimum != null && maximum != null) (maximum - minimum) * 1000 else null))
}
