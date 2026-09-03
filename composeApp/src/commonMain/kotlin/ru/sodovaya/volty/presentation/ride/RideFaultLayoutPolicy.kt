package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.DashboardStyle

internal enum class RideFaultPlacement {
    INLINE,
    OVERLAY,
}

internal fun rideFaultPlacement(style: DashboardStyle): RideFaultPlacement =
    if (style == DashboardStyle.LIGHT) RideFaultPlacement.OVERLAY else RideFaultPlacement.INLINE
