package ru.sodovaya.volty.domain.video

/** RGB is fully opaque; ARGB stores opacity separately from the three color channels. */
fun overlayColorAlpha(color: String): Int =
    if (color.length == 9) color.substring(1, 3).toIntOrNull(16) ?: 255 else 255

fun overlayColorWithAlpha(color: String, alpha: Int): String {
    val rgb = when (color.length) { 7 -> color.drop(1); 9 -> color.drop(3); else -> return color }
    return "#${alpha.coerceIn(0, 255).toString(16).padStart(2, '0')}$rgb".uppercase()
}

/** Numeric model echoes ("1" -> "1.0") must not replace the user's unfinished text. */
data class OverlayTextDraft(
    val text: String,
    val modelText: String = text,
    val focused: Boolean = false,
    val invalid: Boolean = false,
) {
    fun edited(candidate: String, accepted: Boolean): OverlayTextDraft = copy(text = candidate, invalid = !accepted)

    fun synced(value: String): OverlayTextDraft =
        if (!focused && value != modelText) copy(text = value, modelText = value, invalid = false)
        else copy(modelText = value)
}
