package ru.sodovaya.volty.presentation.pack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.presentation.common.CellGrid
import ru.sodovaya.volty.presentation.common.CellUiModel
import ru.sodovaya.volty.presentation.common.MetricCard
import ru.sodovaya.volty.presentation.common.chemistryFraction
import ru.sodovaya.volty.presentation.common.packDisplayLabel
import ru.sodovaya.volty.util.formatFixed
import ru.sodovaya.volty.util.formatSigned
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.branch_current_a
import volty.composeapp.generated.resources.branch_delta_mv
import volty.composeapp.generated.resources.branch_max_cell
import volty.composeapp.generated.resources.branch_min_cell
import volty.composeapp.generated.resources.branch_offline_banner
import volty.composeapp.generated.resources.branch_voltage_v
import volty.composeapp.generated.resources.cells_no_data
import volty.composeapp.generated.resources.cells_section_title
import volty.composeapp.generated.resources.metric_cells
import volty.composeapp.generated.resources.metric_voltage
import volty.composeapp.generated.resources.section_voltage

/**
 * Full cell list of one branch, grouped by physical section when the protocol
 * supplied the breakdown. Opened by tapping a branch card on the dashboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackDetailScreen(component: PackDetailComponent) {
    val state by component.state.collectAsState()
    val pack = state.pack

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        pack?.let { packDisplayLabel(it.pack) } ?: "",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (pack == null) {
                Text(
                    stringResource(Res.string.cells_no_data),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (!pack.isOnline) OfflineBanner()

                val data = pack.data
                val cells = data.cellVoltages
                val minV = cells.minOrNull() ?: 0f
                val maxV = cells.maxOrNull() ?: 0f
                val deltaMv = ((maxV - minV) * 1000f).toInt()
                // Same greying rule as the dashboard card: last values stay
                // visible, but the branch must clearly look offline.
                val contentAlpha = if (pack.isOnline) 1f else 0.45f

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .alpha(contentAlpha)
                ) {
                    MetricCard(
                        label = stringResource(Res.string.metric_voltage),
                        value = stringResource(Res.string.branch_voltage_v, fmt2(data.voltage)),
                        sub = stringResource(Res.string.branch_current_a, formatSigned(data.current, 1)),
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    MetricCard(
                        label = stringResource(Res.string.metric_cells),
                        value = if (cells.isEmpty()) "—"
                        else stringResource(Res.string.branch_delta_mv, deltaMv),
                        sub = if (cells.isEmpty()) null
                        else stringResource(Res.string.branch_min_cell, fmt3(minV)) +
                            " · " + stringResource(Res.string.branch_max_cell, fmt3(maxV)),
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }

                if (state.groups.isEmpty()) {
                    Text(
                        stringResource(Res.string.cells_no_data),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Highlight the pack-global min/max cells, translated into
                    // each group's local index space by CellGroupCard.
                    val globalMaxIdx = cells.indexOf(maxV)
                    val globalMinIdx = cells.indexOf(minV)
                    val highlightSpread = deltaMv >= 30
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.alpha(contentAlpha)
                    ) {
                        state.groups.forEach { group ->
                            CellGroupCard(
                                group = group,
                                chemistry = state.chemistry,
                                globalMinIdx = globalMinIdx,
                                globalMaxIdx = globalMaxIdx,
                                highlightSpread = highlightSpread
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellGroupCard(
    group: PackDetailComponent.CellGroup,
    chemistry: Chemistry,
    globalMinIdx: Int,
    globalMaxIdx: Int,
    highlightSpread: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp)
    ) {
        val title = group.section
            ?.let { stringResource(Res.string.section_voltage, it.index + 1, fmt1(it.voltage)) }
            ?: stringResource(Res.string.cells_section_title)
        Text(
            title.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        )
        Spacer(Modifier.height(8.dp))
        CellGrid(
            // Index is positional: startIndex keeps the physical cell number
            // across section boundaries (section 2 of an ET Max starts at 21).
            cells = group.voltages.mapIndexed { i, v ->
                CellUiModel(
                    index = group.startIndex + i + 1,
                    voltageV = v,
                    rangeFraction = chemistryFraction(v, chemistry)
                )
            },
            maxIdx = (globalMaxIdx - group.startIndex).takeIf { it in group.voltages.indices } ?: -1,
            minIdx = (globalMinIdx - group.startIndex).takeIf { it in group.voltages.indices } ?: -1,
            highlightSpread = highlightSpread,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun OfflineBanner() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            "!",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        Text(
            stringResource(Res.string.branch_offline_banner),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

private fun fmt1(v: Float): String = formatFixed(v, 1)
private fun fmt2(v: Float): String = formatFixed(v, 2)
private fun fmt3(v: Float): String = formatFixed(v, 3)
