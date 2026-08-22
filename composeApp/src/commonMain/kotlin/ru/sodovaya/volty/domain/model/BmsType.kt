package ru.sodovaya.volty.domain.model

enum class BmsType(
    val label: String,
    /**
     * True when the device itself reports a coulomb-counted state of charge.
     *
     * Smart BMS units integrate current over time and report a real SoC that
     * must never be replaced by a guess — including a genuine 0 % on a flat
     * pack. Devices where this is false (a Begode wheel) report no SoC at
     * all; their samples always carry `soc = 0` and volty estimates it from
     * cell voltage instead (see VoltageSocEstimator).
     */
    val reportsStateOfCharge: Boolean
) {
    JK_BMS("JK BMS", reportsStateOfCharge = true),
    JBD_BMS("JBD BMS", reportsStateOfCharge = true),
    ANT_BMS("Ant BMS", reportsStateOfCharge = true),
    DALY_BMS("Daly BMS", reportsStateOfCharge = true),
    VESC_BMS("VESC BMS", reportsStateOfCharge = true),

    /**
     * Begode / Gotway electric unicycle: the wheel streams motherboard and
     * smart-BMS telemetry for two battery branches over a single BLE link.
     */
    BEGODE("Begode", reportsStateOfCharge = false),
    LEAPERKIM("Leaperkim", reportsStateOfCharge = true)
}
