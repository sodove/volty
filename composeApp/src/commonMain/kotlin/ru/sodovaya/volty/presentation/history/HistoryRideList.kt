package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import volty.composeapp.generated.resources.*

/** Paged recorded catalog. Preview ownership stays with the caller; drawing a
 * row never starts a route/series query or allocates a native map.
 */
@Composable
fun HistoryRideList(state: HistoryCatalogState, selectedRideId: String?, onOpen: (String) -> Unit,
    onRefresh: () -> Unit, onLoadOlder: () -> Unit, modifier: Modifier = Modifier,
    vehicleNames: Map<String, String> = emptyMap(), previews: HistoryPreviewsState = HistoryPreviewsState(),
    reduceMotion: Boolean = false, onVisibleChanged: (List<String>) -> Unit = {}, onRetryPreview: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState()) {
    val zone = TimeZone.currentSystemDefault()
    val latestVisible by rememberUpdatedState(onVisibleChanged)
    val rideKeys = remember(state.rides) { state.rides.associateBy { "ride:${it.id}" } }
    LaunchedEffect(listState, rideKeys) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }.distinctUntilChanged().collect { keys ->
            latestVisible(keys.mapNotNull { rideKeys[it]?.id }.take(20))
        }
    }
    DisposableEffect(Unit) { onDispose { latestVisible(emptyList()) } }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("refresh") { TextButton(onClick = onRefresh, enabled = !state.refreshing && !state.loadingOlder) {
            Text(stringResource(Res.string.history_refresh))
        } }
        if (state.refreshing) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.loaded && state.rides.isEmpty() && state.error == null) item("empty") {
            Text(stringResource(Res.string.history_empty), Modifier.padding(16.dp))
        }
        items(state.rides, key = { "ride:${it.id}" }) { ride ->
            val date = remember(ride.started.epochMillis, zone) { runCatching {
                val value = Instant.fromEpochMilliseconds(ride.started.epochMillis).toLocalDateTime(zone)
                "${value.date} · ${value.hour.toString().padStart(2, '0')}:${value.minute.toString().padStart(2, '0')}"
            }.getOrNull() }
            val duration = historyRideDurationMillis(ride)?.let {
                val seconds = it / 1000
                "${seconds / 3600}:${(seconds / 60 % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
            }
            val status = stringResource(when (ride.status) {
                HistoryRideStatus.RECORDING -> Res.string.history_recording
                HistoryRideStatus.LEGACY -> Res.string.history_legacy
                HistoryRideStatus.INTERRUPTED -> Res.string.history_interrupted
                HistoryRideStatus.COMPLETED -> Res.string.history_completed
            })
            HistoryRideCard(HistoryRideCardText(vehicleNames[ride.vehicleId] ?: stringResource(Res.string.history_ride_title),
                date ?: stringResource(Res.string.history_date_unknown), listOfNotNull(duration, status).joinToString(" · ")),
                previews.previews[ride.id]?.segments.orEmpty(), ride.id == selectedRideId, { onOpen(ride.id) },
                if (reduceMotion) Modifier else Modifier.animateItem(), previewLoading = ride.id in previews.loading,
                previewPartial = previews.previews[ride.id]?.partial == true, previewError = ride.id in previews.errors,
                onRetry = { onRetryPreview(ride.id) })
        }
        if (state.error != null) item("error") {
            Text(stringResource(Res.string.history_load_error), color = MaterialTheme.colorScheme.error)
        }
        if (state.hasMore) item("older") {
            TextButton(onClick = onLoadOlder, enabled = !state.refreshing && !state.loadingOlder) {
                Text(stringResource(Res.string.history_load_older))
            }
        }
        if (state.loadingOlder) item("loading-older") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.truncated && !state.hasMore) item("partial") {
            Text(stringResource(Res.string.history_catalog_partial), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
