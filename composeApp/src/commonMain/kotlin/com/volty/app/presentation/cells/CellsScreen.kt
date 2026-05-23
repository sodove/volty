package com.volty.app.presentation.cells

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volty.app.presentation.common.MetricCard
import com.volty.app.presentation.common.MetricCardVariant
import kotlin.math.round

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellsScreen(component: CellsComponent) {
    val state by component.state.collectAsState()
    val cellsCount = state.cells.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cells · ${cellsCount}s", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp)) {
            // Summary row
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            ) {
                MetricCard(
                    label = "Max",
                    value = formatVolts(state.cells.getOrNull(state.maxIdx)?.voltageV ?: 0f),
                    sub = state.cells.getOrNull(state.maxIdx)?.let { "Cell #${it.index}" },
                    variant = MetricCardVariant.Primary,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                MetricCard(
                    label = "Min",
                    value = formatVolts(state.cells.getOrNull(state.minIdx)?.voltageV ?: 0f),
                    sub = state.cells.getOrNull(state.minIdx)?.let { "Cell #${it.index}" },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                MetricCard(
                    label = "Δ Delta",
                    value = "${state.deltaMv} mV",
                    sub = if (state.deltaMv < 50) "Healthy" else if (state.deltaMv < 200) "OK" else "Imbalance",
                    variant = MetricCardVariant.Tertiary,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }

            Box(modifier = Modifier.padding(top = 8.dp).fillMaxSize()) {
                if (state.cells.isEmpty()) {
                    Text(
                        "No cell data yet",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // 3 columns, fill order
                    val rows = (state.cells.size + 2) / 3
                    val highlightSpread = state.deltaMv >= 10
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (r in 0 until rows) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                for (c in 0 until 3) {
                                    val idx = r * 3 + c
                                    if (idx < state.cells.size) {
                                        CellRow(
                                            cell = state.cells[idx],
                                            isMax = highlightSpread && idx == state.maxIdx,
                                            isMin = highlightSpread && idx == state.minIdx,
                                            showBar = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Box(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellRow(
    cell: CellsComponent.Cell,
    isMax: Boolean,
    isMin: Boolean,
    showBar: Boolean,
    modifier: Modifier = Modifier
) {
    val bg = when {
        isMax -> MaterialTheme.colorScheme.primaryContainer
        isMin -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "${cell.index}",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                formatVoltsThreeDecimals(cell.voltageV),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }
        if (showBar) {
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(cell.rangeFraction)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

private fun formatVolts(v: Float): String {
    val rounded = round(v * 1000f) / 1000f
    val whole = rounded.toInt()
    val frac = ((rounded - whole) * 1000).toInt().toString().padStart(3, '0').take(3)
    return "$whole.$frac V"
}

private fun formatVoltsThreeDecimals(v: Float): String = formatVolts(v)
