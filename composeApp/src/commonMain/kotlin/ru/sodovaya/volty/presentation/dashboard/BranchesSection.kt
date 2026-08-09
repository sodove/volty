package ru.sodovaya.volty.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.presentation.common.chemistryFraction
import ru.sodovaya.volty.presentation.common.BmsMetricMapper
import ru.sodovaya.volty.presentation.common.groupPackCells
import ru.sodovaya.volty.presentation.common.packDisplayLabel
import ru.sodovaya.volty.util.formatFixed
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.branch_current_a
import volty.composeapp.generated.resources.branch_delta_mv
import volty.composeapp.generated.resources.branch_min_cell
import volty.composeapp.generated.resources.branch_offline
import volty.composeapp.generated.resources.branch_voltage_v
import volty.composeapp.generated.resources.branches_section_title

/**
 * Branch summary block: one tappable card per pack. Rendered only when the
 * battery has more than one pack (the caller gates on State.showBranches) —
 * single-pack dashboards must stay pixel-identical to the pre-multi-pack UI.
 */
@Composable
internal fun BranchesSection(
    packs: List<PackState>,
    chemistry: Chemistry,
    onPackClicked: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(Res.string.branches_section_title).uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.padding(start = 4.dp)
        )
        // Side-by-side cards so the rider can compare the branches at a glance —
        // divergence between branches is the symptom two BMS are read for.
        packs.chunked(2).forEach { rowPacks ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            ) {
                rowPacks.forEach { p ->
                    BranchCard(
                        state = p,
                        packCount = packs.size,
                        chemistry = chemistry,
                        onClick = { onPackClicked(p.pack.index) },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                // Keep an odd trailing card at half width so the grid stays aligned.
                if (rowPacks.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BranchCard(
    state: PackState,
    packCount: Int,
    chemistry: Chemistry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val data = state.data
    val cells = data.cellVoltages
    val deltaMv = if (cells.isEmpty()) 0 else ((cells.max() - cells.min()) * 1000f).toInt()
    // An offline branch keeps its last values but must clearly look offline:
    // the whole body is greyed and an explicit marker replaces guesswork.
    val contentAlpha = if (state.isOnline) 1f else 0.45f

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                packDisplayLabel(state.pack, packCount).uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f * contentAlpha),
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (!state.isOnline) OfflineChip()
        }
        Spacer(Modifier.height(4.dp))
        Column(Modifier.alpha(contentAlpha)) {
            Text(
                stringResource(Res.string.branch_voltage_v, fmt2(data.voltage)),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.height(2.dp))
            Text(
                (BmsMetricMapper.currentValue(data)?.let { stringResource(Res.string.branch_current_a, it) } ?: "—") +
                    " · " + stringResource(Res.string.branch_delta_mv, deltaMv),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (cells.isEmpty()) "—"
                else stringResource(Res.string.branch_min_cell, fmt3(cells.min())) + " · ${cells.size}s",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val sections = state.sections.sortedBy { it.index }
            if (sections.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                // Compact per-section indication: voltage + a mini bar whose fill
                // is the chemistry fraction of the section's average cell voltage.
                // Cells per section come from the shared grouping, which splits
                // only when the protocol declared authoritative cell ranges (see
                // groupPackCells); without them the bar honestly stays empty
                // instead of dividing by a guessed per-section cell count.
                val cellsPerSection: Map<Int, Int> = groupPackCells(state)
                    .mapNotNull { g -> g.section?.let { it.index to g.voltages.size } }
                    .toMap()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    sections.forEach { s ->
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(Res.string.branch_voltage_v, fmt1(s.voltage)),
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                            Spacer(Modifier.height(2.dp))
                            val sectionCells = cellsPerSection[s.index] ?: 0
                            val fraction = if (sectionCells > 0)
                                chemistryFraction(s.voltage / sectionCells, chemistry) else 0f
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            stringResource(Res.string.branch_offline),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

private fun fmt1(v: Float): String = formatFixed(v, 1)
private fun fmt2(v: Float): String = formatFixed(v, 2)
private fun fmt3(v: Float): String = formatFixed(v, 3)
