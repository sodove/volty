package ru.sodovaya.volty.presentation.map

/** Insets reserved for the Terra HUD while a route preview is on screen. */
object RoutePreviewCameraPolicy {
    data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun paddingFor(width: Int, height: Int): Padding {
        require(width >= 0 && height >= 0)
        return Padding(
            left = (width * 0.06f).toInt().coerceAtLeast(48),
            top = (height * 0.14f).toInt().coerceAtLeast(96),
            right = (width * 0.06f).toInt().coerceAtLeast(48),
            bottom = (height * 0.28f).toInt().coerceAtLeast(180),
        )
    }
}
