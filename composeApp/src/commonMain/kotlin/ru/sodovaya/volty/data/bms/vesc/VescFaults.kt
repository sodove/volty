package ru.sodovaya.volty.data.bms.vesc

/** `mc_fault_code` → human label (VESC datatypes.h). 0 is "no fault". */
object VescFaults {
    private val labels = mapOf(
        1 to "Over voltage", 2 to "Under voltage", 3 to "DRV fault", 4 to "ABS over current",
        5 to "Over temp FET", 6 to "Over temp motor", 7 to "Gate driver over voltage",
        8 to "Gate driver under voltage", 9 to "MCU under voltage", 10 to "Booting from watchdog reset",
        11 to "Encoder SPI fault", 12 to "Encoder sincos below min amplitude",
        13 to "Encoder sincos above max amplitude", 14 to "Flash corruption",
        15 to "High offset current sensor 1", 16 to "High offset current sensor 2",
        17 to "High offset current sensor 3", 18 to "Unbalanced currents"
    )
    fun label(code: Int): String? = if (code == 0) null else labels[code] ?: "Fault $code"
}
