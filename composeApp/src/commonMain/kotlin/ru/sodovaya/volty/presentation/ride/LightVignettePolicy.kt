package ru.sodovaya.volty.presentation.ride

/** The vignette is a Light backdrop, never a full-screen map overlay. */
internal enum class LightVignettePlacement {
    BETWEEN_MAP_AND_HUD,
}

internal val lightVignettePlacement = LightVignettePlacement.BETWEEN_MAP_AND_HUD

internal enum class LightVignetteTone {
    DARK,
    BRIGHT,
}

internal fun lightVignetteTone(darkTheme: Boolean): LightVignetteTone =
    if (darkTheme) LightVignetteTone.DARK else LightVignetteTone.BRIGHT

internal data class LightMapOverlaySpec(
    val blurRadiusDp: Float,
    val clearUntilFraction: Float,
    val edgeStartFraction: Float,
    val fallbackAlpha: Float,
    val vignetteCenterAlpha: Float,
    val vignetteEdgeAlpha: Float,
    val fallbackTone: LightVignetteTone,
)

internal fun lightMapOverlaySpec(darkTheme: Boolean): LightMapOverlaySpec = LightMapOverlaySpec(
    blurRadiusDp = 24f,
    clearUntilFraction = 0.52f,
    edgeStartFraction = 0.86f,
    fallbackAlpha = if (darkTheme) 0.58f else 0.52f,
    vignetteCenterAlpha = if (darkTheme) 0.02f else 0.04f,
    vignetteEdgeAlpha = if (darkTheme) 0.72f else 0.78f,
    fallbackTone = lightVignetteTone(darkTheme),
)
