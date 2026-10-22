package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import volty.composeapp.generated.resources.*

private data class BoundMapColor(val identity: HistoryMapIdentity, val origin: HistoryTick?, val route: HistoryRouteState,
    val series: HistorySeries, val stats: HistoryStats, val gpsAge: Long, val metricAge: Long, val coloring: HistoryMapColoring?)

/** Color derivation is independent of cursor frames. No data read is started here. */
@Composable
fun rememberHistoryMapColoring(identity: HistoryMapIdentity, detail: HistoryDetailState?, route: HistoryRouteState?,
    key: HistorySeriesKey?, gpsAgeMillis: Long, metricAgeMillis: Long): HistoryMapColoring? {
    val origin = detail?.ride?.takeUnless { it.status == HistoryRideStatus.LEGACY }?.started
    val evidence = historyMapMetricEvidence(detail, key).takeIf {
        detail?.ride?.id == identity.rideId && route?.rideId == identity.rideId && route?.range == detail?.range }
    val series = evidence?.series
    val stats = evidence?.stats
    val bound by produceState<BoundMapColor?>(null, identity, origin, route, series, stats, gpsAgeMillis, metricAgeMillis) {
        value = if (route == null || series == null || stats == null) null else withContext(Dispatchers.Default) {
            val geometry = historyMapScene(identity, origin, route.fixes, route.markers, null, gpsAgeMillis)
            BoundMapColor(identity, origin, route, series, stats, gpsAgeMillis, metricAgeMillis,
                historyMapColoring(geometry, origin, series, stats, metricAgeMillis))
        }
    }
    return bound?.takeIf { evidence != null && it.identity == identity && it.origin == origin && it.route === route &&
        it.series === series && it.stats == stats && it.gpsAge == gpsAgeMillis && it.metricAge == metricAgeMillis }?.coloring
}

@Composable
fun HistoryMapColorLegend(detail: HistoryDetailState, key: HistorySeriesKey, coloring: HistoryMapColoring?,
    onClear: () -> Unit, modifier: Modifier = Modifier) {
    val text = historyMetricText()
    val labels = historySourceLabels(detail.sources)
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.history_map_metric, text.title(key),
                labels[key.sourceId] ?: stringResource(Res.string.history_summary_source_unavailable)),
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = onClear) { Icon(Icons.Default.Close, stringResource(Res.string.history_map_neutral)) }
        }
        if (key in detail.loadingKeys) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (key in detail.metricErrors) Text(stringResource(Res.string.history_metric_chart_error),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        else if (coloring == null) Text(stringResource(Res.string.history_summary_unavailable), style = MaterialTheme.typography.labelSmall)
        else {
            Box(Modifier.fillMaxWidth().height(6.dp).background(Brush.horizontalGradient(
                listOf(Color(HISTORY_MAP_LOW_ARGB), Color(HISTORY_MAP_HIGH_ARGB)))))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val unit = historyUnitText(HistoryMetricRegistry.descriptor(key.metricId)?.unit)
                Text("${coloring.minimum} $unit", style = MaterialTheme.typography.labelSmall)
                Text("${coloring.maximum} $unit", style = MaterialTheme.typography.labelSmall)
            }
            if (coloring.partial) Text(stringResource(Res.string.history_map_partial), style = MaterialTheme.typography.labelSmall)
        }
        Text(stringResource(Res.string.history_map_unknown), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
