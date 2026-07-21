package ru.sodovaya.volty.presentation.common

import androidx.compose.runtime.Composable
import ru.sodovaya.volty.domain.model.Pack
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.branch_label_n

/** Which label a pack card should show. Resolved to a string at render time. */
sealed interface PackLabel {
    /** The pack's stored label, shown verbatim (the user's own naming). */
    data class Own(val text: String) : PackLabel

    /** Localised positional label ("Branch N" / "Ветка N"); [number] is 1-based. */
    data class Positional(val number: Int) : PackLabel
}

/**
 * Decides a pack's display label. Pure and non-composable so the mapping is
 * unit-testable; [packDisplayLabel] localises the result.
 *
 * Stored labels are inconsistent across producers: `singlePackVehicle()`
 * names pack 0 after the vehicle, while `expandedTo()` names synthesised
 * packs "Pack 2", "Pack 3"…. Left as stored, every multi-pack wheel would
 * show one card titled with the vehicle name and the rest positionally —
 * defeating the side-by-side comparison the branch block exists for.
 *
 * The decision is made here, at display time, rather than by rewriting
 * stored rows: fixing the data would need a migration and would freeze
 * today's locale into persisted labels. Instead:
 *  - multi-pack vehicle: EVERY branch gets a positional label derived from
 *    [Pack.index], regardless of what is stored, so the cards read as a
 *    uniform comparable set;
 *  - single pack: there is nothing to compare it against, so the stored
 *    label (the user's own name) passes through untouched.
 */
fun packLabelFor(pack: Pack, packCount: Int): PackLabel =
    if (packCount > 1) PackLabel.Positional(pack.index + 1)
    else PackLabel.Own(pack.label)

/** Localised display label for a pack; see [packLabelFor] for the decision. */
@Composable
fun packDisplayLabel(pack: Pack, packCount: Int): String =
    when (val label = packLabelFor(pack, packCount)) {
        is PackLabel.Own -> label.text
        is PackLabel.Positional -> stringResource(Res.string.branch_label_n, label.number)
    }
