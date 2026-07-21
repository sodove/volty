package ru.sodovaya.volty.domain.model

enum class BmsType(val label: String) {
    JK_BMS("JK BMS"),
    JBD_BMS("JBD BMS"),
    ANT_BMS("Ant BMS"),
    DALY_BMS("Daly BMS"),

    /**
     * Begode / Gotway electric unicycle: the wheel streams motherboard and
     * smart-BMS telemetry for two battery branches over a single BLE link.
     */
    BEGODE("Begode")
}
