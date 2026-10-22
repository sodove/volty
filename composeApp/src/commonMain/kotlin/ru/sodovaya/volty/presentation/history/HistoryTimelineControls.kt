package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.HistoryRange
import volty.composeapp.generated.resources.*

/** Accessible alternatives to gestures, sharing the same committed range/cursor. */
@Composable
fun HistoryTimelineControls(state: HistoryDetailState, onRange: (HistoryRange) -> Unit,
    onSelectTime: (Long?) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false,
    applyNavigationInsets: Boolean = true) {
    val range = state.range ?: return
    val bounds = state.availableRange ?: return
    val zone = TimeZone.currentSystemDefault()
    val times = runCatching {
        val start = Instant.fromEpochMilliseconds(range.startEpochMillis).toLocalDateTime(zone)
        val end = Instant.fromEpochMilliseconds(range.endEpochMillis).toLocalDateTime(zone)
        if (start.date == end.date) "${start.time} – ${end.time}" else "$start – $end"
    }.getOrDefault("—")
    Surface(modifier, tonalElevation = 3.dp) {
        BoxWithConstraints {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxWidth().then(if (applyNavigationInsets) Modifier.navigationBarsPadding() else Modifier)
            .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(times, style = MaterialTheme.typography.labelSmall)
            if (compact) TimelineCursor(range, state.cursorEpochMillis, onSelectTime)
            else if (wide) Row(verticalAlignment = Alignment.CenterVertically) {
                TimelineButtons(range, bounds, onRange)
                TimelineCursor(range, state.cursorEpochMillis, onSelectTime, Modifier.weight(1f))
            } else {
                TimelineButtons(range, bounds, onRange)
                TimelineCursor(range, state.cursorEpochMillis, onSelectTime)
            }
        }
        }
    }
}

@Composable
private fun TimelineButtons(range: HistoryRange, bounds: HistoryRange, onRange: (HistoryRange) -> Unit) {
    val earlier = historyTransformWindow(range, bounds, pan = 0.25)
    val later = historyTransformWindow(range, bounds, pan = -0.25)
    val closer = historyTransformWindow(range, bounds, zoom = 2.0)
    val wider = historyTransformWindow(range, bounds, zoom = 0.5)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { earlier?.let(onRange) }, enabled = earlier != null && earlier != range) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(Res.string.history_window_earlier))
                }
                IconButton(onClick = { closer?.let(onRange) }, enabled = closer != null && closer != range) {
                    Icon(Icons.Default.ZoomIn, stringResource(Res.string.history_window_zoom_in))
                }
                IconButton(onClick = { onRange(bounds) }, enabled = range != bounds) {
                    Icon(Icons.Default.FitScreen, stringResource(Res.string.history_window_reset))
                }
                IconButton(onClick = { wider?.let(onRange) }, enabled = wider != null && wider != range) {
                    Icon(Icons.Default.ZoomOut, stringResource(Res.string.history_window_zoom_out))
                }
                IconButton(onClick = { later?.let(onRange) }, enabled = later != null && later != range) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(Res.string.history_window_later))
                }
    }
}

@Composable
private fun TimelineCursor(range: HistoryRange, cursor: Long?, onSelectTime: (Long?) -> Unit, modifier: Modifier = Modifier) {
            val fraction = historyTimeFraction(range, cursor ?: range.startEpochMillis)
            val cursorLabel = stringResource(Res.string.history_window_cursor)
            Slider(value = fraction ?: 0f, enabled = fraction != null && range.startEpochMillis != range.endEpochMillis,
                modifier = modifier.semantics { contentDescription = cursorLabel },
                onValueChange = { value -> historyTimeAtFraction(range, value)?.let(onSelectTime) })
}
