package ru.sodovaya.volty.domain.video

/**
 * The dark HUD palette as one set of tokens, shared by the Light dashboard and everything burned into a ride video.
 * A video must read as Volty on any frame, so the overlay never follows the app's light theme.
 */
object OverlayPalette {
    const val TEXT = 0xFFEAF4F8
    const val MUTED = 0xFF8EA6B0
    const val UNIT = 0xFFC6D5DC
    const val SURFACE = 0x66132531
    const val SURFACE_STRONG = 0x80162A38
    const val CYAN = 0xFF27D5F5
    const val GREEN = 0xFF25E0B4
    const val BATTERY = 0xFF39E681
    const val ORANGE = 0xFFFFB45E
    const val PINK = 0xFFFF719C

    /** The capture screen's chrome; none of it reaches the file. */
    const val PANEL = 0xFF07131E
    const val REC = 0xFFFF3B4E
    const val ON_ORANGE = 0xFF16100A
    const val ON_PINK = 0xFF23060F
    const val ON_CYAN = 0xFF061A22

    // Protection on bright frames: the mock's contrast stack, in one place.
    const val CARD_FILL = 0xAD07131E
    const val CARD_STROKE = 0x24EAF4F8
    const val SCRIM = 0xFF040C12
    const val HALO = 0xB8030A0F
    const val GAUGE_UNDER = 0x9E030A0F
    const val GAUGE_TRACK = 0x6BEAF4F8
    const val BAR_TRACK = 0xB807131E
    const val BAR_STROKE = 0x4DEAF4F8
    const val BAR_TICK = 0x8C07131E

    /** `#AARRGGBB`, the format presets store. */
    fun hex(argb: Long): String = "#" + (argb and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')
}

/** The Int form of a validated `#RRGGBB` or `#AARRGGBB`; anything else reads as opaque white, never as a crash. */
fun overlayColorArgb(color: String): Int {
    val digits = color.removePrefix("#")
    val value = digits.toLongOrNull(16) ?: return -1
    return when (digits.length) {
        6 -> (value or 0xFF000000L).toInt()
        8 -> value.toInt()
        else -> -1
    }
}
