package ru.sodovaya.volty.presentation.common

import androidx.compose.runtime.Composable
import ru.sodovaya.volty.domain.model.Pack
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.branch_label_n

/**
 * Matches the auto-synthesised pack label produced by
 * [ru.sodovaya.volty.domain.model.expandedTo] ("Pack 2", "Pack 3", …).
 * That label is deliberately NOT localised in the domain layer (it is
 * persisted), so localisation happens here at render time instead.
 */
private val AUTO_PACK_LABEL = Regex("""^Pack (\d+)$""")

/**
 * Display label for a pack. User-given labels (a vehicle name, "Основная")
 * pass through untouched; the domain's synthetic "Pack N" placeholder is
 * rendered as the localised branch term ("Branch N" / "Ветка N").
 */
@Composable
fun packDisplayLabel(pack: Pack): String {
    val auto = AUTO_PACK_LABEL.matchEntire(pack.label) ?: return pack.label
    val n = auto.groupValues[1].toIntOrNull() ?: return pack.label
    return stringResource(Res.string.branch_label_n, n)
}
