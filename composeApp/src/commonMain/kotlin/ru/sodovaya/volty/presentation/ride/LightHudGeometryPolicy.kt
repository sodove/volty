package ru.sodovaya.volty.presentation.ride

internal enum class LightArcSide { LEFT, RIGHT }

internal enum class LightMapControlsEdge { LEFT, RIGHT }

internal enum class LightMapControlsVertical { TOP, CENTER, BOTTOM }

internal data class LightMapControlsPlacement(
    val edge: LightMapControlsEdge,
    val vertical: LightMapControlsVertical,
)

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

internal fun lightMapControlsPlacement(): LightMapControlsPlacement = LightMapControlsPlacement(
    edge = LightMapControlsEdge.RIGHT,
    vertical = LightMapControlsVertical.CENTER,
)

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
        LightLayoutMode.COMPACT -> 30f
        LightLayoutMode.MEDIUM -> 34f
        LightLayoutMode.WIDE -> 37f
    },
    valueWeight = 700,
    labelSizeSp = 9f,
    labelWeight = 700,
)
