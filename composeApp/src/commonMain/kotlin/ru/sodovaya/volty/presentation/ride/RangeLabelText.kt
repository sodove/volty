package ru.sodovaya.volty.presentation.ride

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.energy.RangeEstimate
import ru.sodovaya.volty.util.UnitSystem
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.range_unit_km
import volty.composeapp.generated.resources.range_unit_mi

/** Resolves the localized unit words and hands the decision to [rangeLabel]; decides nothing itself. */
@Composable
internal fun localizedRangeLabel(range: RangeEstimate?, units: UnitSystem): String? =
    rangeLabel(
        range, units,
        kmUnit = stringResource(Res.string.range_unit_km),
        miUnit = stringResource(Res.string.range_unit_mi),
    )
