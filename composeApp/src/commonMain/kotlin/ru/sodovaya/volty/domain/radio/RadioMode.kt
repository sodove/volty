package ru.sodovaya.volty.domain.radio

/**
 * Connection mode chosen by the radio hub. [wire] is the value carried on the
 * wire; [opusBitrate] null means raw PCM16.
 */
enum class RadioMode(
    val wire: Int,
    val jitterTargetFrames: Int,
    val opusBitrate: Int?,
    val fec: Boolean,
) {
    LOW_LATENCY(1, 2, null, false),
    BALANCED(2, 3, 24_000, false),
    RELIABLE(3, 6, 12_000, true);

    companion object {
        fun fromWire(wire: Int): RadioMode? = entries.firstOrNull { it.wire == wire }
    }
}