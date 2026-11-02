package ru.sodovaya.volty.domain.model

/** Stable manual color choices used to generate the app and map tonal palettes. */
enum class ThemeAccent(
    val id: String,
    val seedArgb: Int,
) {
    DEFAULT("default", 0xFF5B53D4.toInt()),
    ORANGE("orange", 0xFFC45C20.toInt()),
    GREEN("green", 0xFF367C44.toInt()),
    TEAL("teal", 0xFF007F82.toInt()),
    BLUE("blue", 0xFF2165C0.toInt()),
    PINK("pink", 0xFFAA3F77.toInt());

    companion object {
        fun fromId(id: String?): ThemeAccent = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
