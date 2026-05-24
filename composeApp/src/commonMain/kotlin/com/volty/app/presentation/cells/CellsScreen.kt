package com.volty.app.presentation.cells

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.volty.app.presentation.common.CellGrid
import com.volty.app.presentation.common.MetricCard
import com.volty.app.presentation.common.MetricCardVariant
import kotlin.math.round
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.cells_cell_n
import volty.composeapp.generated.resources.cells_delta
import volty.composeapp.generated.resources.cells_healthy
import volty.composeapp.generated.resources.cells_imbalance
import volty.composeapp.generated.resources.cells_max
import volty.composeapp.generated.resources.cells_min
import volty.composeapp.generated.resources.cells_no_data
import volty.composeapp.generated.resources.cells_ok
import volty.composeapp.generated.resources.cells_title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellsScreen(component: CellsComponent) {
    val state by component.state.collectAsState()
    val cellsCount = state.cells.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.cells_title, cellsCount), fontWeight = FontWeight.SemiBold) },
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
                    label = stringResource(Res.string.cells_max),
                    value = formatVolts(state.cells.getOrNull(state.maxIdx)?.voltageV ?: 0f),
                    sub = state.cells.getOrNull(state.maxIdx)?.let { stringResource(Res.string.cells_cell_n, it.index) },
                    variant = MetricCardVariant.Primary,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                MetricCard(
                    label = stringResource(Res.string.cells_min),
                    value = formatVolts(state.cells.getOrNull(state.minIdx)?.voltageV ?: 0f),
                    sub = state.cells.getOrNull(state.minIdx)?.let { stringResource(Res.string.cells_cell_n, it.index) },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                MetricCard(
                    label = stringResource(Res.string.cells_delta),
                    value = "${state.deltaMv} mV",
                    sub = if (state.deltaMv < 50) stringResource(Res.string.cells_healthy)
                        else if (state.deltaMv < 200) stringResource(Res.string.cells_ok)
                        else stringResource(Res.string.cells_imbalance),
                    variant = MetricCardVariant.Tertiary,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }

            Box(modifier = Modifier.padding(top = 8.dp).fillMaxSize()) {
                if (state.cells.isEmpty()) {
                    Text(
                        stringResource(Res.string.cells_no_data),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Highlight max/min when spread is significant (>30 mV)
                    val highlightSpread = state.deltaMv >= 30
                    CellGrid(
                        cells = state.cells,
                        maxIdx = state.maxIdx,
                        minIdx = state.minIdx,
                        highlightSpread = highlightSpread,
                        compact = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
