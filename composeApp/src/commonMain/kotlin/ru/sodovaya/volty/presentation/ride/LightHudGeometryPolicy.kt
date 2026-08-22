package ru.sodovaya.volty.presentation.ride

internal enum class LightArcSide { LEFT, RIGHT }

internal data class LightGaugeGeometry(
    val arcStartDegrees: Float,
    val arcSweepDegrees: Float,
    val arcWidthFraction: Float,
    val arcHeightFraction: Float,
    val progressesFromTop: Boolean,
    val valueSizeSp: Float,
    val valueWeight: Int,
    val labelSizeSp: Float,
    val labelWeight: Int,
)

internal fun lightGaugeBlockHeight(layoutMode: LightLayoutMode): Float = when (layoutMode) {
    LightLayoutMode.COMPACT -> 122f
    LightLayoutMode.MEDIUM -> 146f
    LightLayoutMode.WIDE -> 158f
}

internal fun lightGaugeBlockTopSpacing(layoutMode: LightLayoutMode): Float = 0f

internal fun lightDashboardMiddleSpacerWeight(): Float = 1f

internal fun lightGaugeGeometry(
    layoutMode: LightLayoutMode,
    arcSide: LightArcSide,
): LightGaugeGeometry = LightGaugeGeometry(
    arcStartDegrees = if (arcSide == LightArcSide.LEFT) 225f else 315f,
    arcSweepDegrees = if (arcSide == LightArcSide.LEFT) -90f else 90f,
    arcWidthFraction = 0.92f,
    arcHeightFraction = 0.92f,
    progressesFromTop = true,
    valueSizeSp = when (layoutMode) {
        LightLayoutMode.COMPACT -> 28f
        LightLayoutMode.MEDIUM -> 31f
        LightLayoutMode.WIDE -> 33f
    },
    valueWeight = 700,
    labelSizeSp = 8f,
    labelWeight = 700,
)
