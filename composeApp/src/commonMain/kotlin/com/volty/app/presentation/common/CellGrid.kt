package com.volty.app.presentation.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volty.app.presentation.cells.CellsComponent
import kotlin.math.round

/**
 * 3-column grid of cells with thin progress bars. Shared between [CellsScreen] and the
 * dashboard's inline cells section. The bar fraction is precomputed by the component
 * (chemistry-based, not pack-spread).
 */
@Composable
fun CellGrid(
    cells: List<CellsComponent.Cell>,
    maxIdx: Int,
    minIdx: Int,
    highlightSpread: Boolean,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    compact: Boolean = false
) {
    if (cells.isEmpty()) return
    val rows = (cells.size + columns - 1) / columns
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)
    ) {
        for (r in 0 until rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                for (c in 0 until columns) {
                    val idx = r * columns + c
                    if (idx < cells.size) {
                        CellCell(
                            cell = cells[idx],
                            isMax = highlightSpread && idx == maxIdx,
                            isMin = highlightSpread && idx == minIdx,
                            compact = compact,
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

@Composable
private fun CellCell(
    cell: CellsComponent.Cell,
    isMax: Boolean,
    isMin: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val bg = when {
        isMax -> MaterialTheme.colorScheme.primaryContainer
        isMin -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val animatedFraction by animateFloatAsState(
        targetValue = cell.rangeFraction,
        animationSpec = tween(durationMillis = 400),
        label = "cellBar"
    )
    val vPad: Dp = if (compact) 3.dp else 4.dp
    val hPad: Dp = if (compact) 6.dp else 8.dp
    val barH: Dp = if (compact) 2.dp else 3.dp
    val voltSize = if (compact) 10.sp else 11.sp
    val idxSize = if (compact) 8.sp else 9.sp
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(if (compact) 10.dp else 12.dp))
            .background(bg)
            .padding(horizontal = hPad, vertical = vPad),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "${cell.index}",
                fontSize = idxSize,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                formatVoltsThreeDecimals(cell.voltageV),
                fontSize = voltSize,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barH)
                .clip(RoundedCornerShape(barH / 2))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .height(barH)
                    .clip(RoundedCornerShape(barH / 2))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

private fun formatVoltsThreeDecimals(v: Float): String {
    val rounded = round(v * 1000f) / 1000f
    val whole = rounded.toInt()
    val frac = ((rounded - whole) * 1000).toInt().toString().padStart(3, '0').take(3)
    return "$whole.$frac V"
}
