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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import volty.composeapp.generated.resources.*
import kotlin.math.roundToInt

/**
 * Whether the card shows a retry affordance. It is offered when EITHER read failed; before this (final review M2) only a preview failure showed it, and a failed
 * duty read silently left the PWM line absent until a pull-to-refresh bumped the revision. `retryPreview` retries both.
 */
internal fun historyCardRetryVisible(rideId: String, previews: HistoryPreviewsState, cardStats: HistoryCardStatsState): Boolean =
    rideId in previews.errors || rideId in cardStats.errors

/** The part of [historyCardRetryVisible] that the preview's own retry icon does not already cover. */
internal fun historyCardStatsRetryOnly(rideId: String, previews: HistoryPreviewsState, cardStats: HistoryCardStatsState): Boolean =
    historyCardRetryVisible(rideId, previews, cardStats) && rideId !in previews.errors

/** Paged recorded catalog. Preview ownership stays with the caller; drawing a
 * row never starts a route/series query or allocates a native map.
 */
@Composable
fun HistoryRideList(state: HistoryCatalogState, selectedRideId: String?, onOpen: (String) -> Unit,
    onRefresh: () -> Unit, onLoadOlder: () -> Unit, modifier: Modifier = Modifier,
    vehicleNames: Map<String, String> = emptyMap(), previews: HistoryPreviewsState = HistoryPreviewsState(),
    reduceMotion: Boolean = false, onVisibleChanged: (List<String>) -> Unit = {}, onRetryPreview: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState(), previewImages: HistoryPreviewImagesState = HistoryPreviewImagesState(),
    previewRevision: Long = 0, onVisibleImagesChanged: (List<HistoryPreviewImageKey>) -> Unit = {},
    onDelete: ((HistoryRide) -> Unit)? = null, cardStats: HistoryCardStatsState = HistoryCardStatsState()) {
    val zone = TimeZone.currentSystemDefault()
    val latestVisible by rememberUpdatedState(onVisibleChanged)
    val latestImages by rememberUpdatedState(onVisibleImagesChanged)
    val density = LocalDensity.current.density
    val imageHeight = (88*density).roundToInt().coerceIn(1,512)
    val imageWidth = (imageHeight*86.0/88).roundToInt().coerceIn(1,512)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val primary = MaterialTheme.colorScheme.primary.toArgb(); val tertiary = MaterialTheme.colorScheme.tertiary.toArgb()
    val renderKeys = remember(previews.previews,selectedRideId,previewRevision,imageWidth,imageHeight,dark,primary,tertiary) {
        previews.previews.mapNotNull { (id,data) ->
            if (data.segments.all { it.fixes.isEmpty() }) null else id to HistoryPreviewImageKey(id,previewRevision,imageWidth,imageHeight,
                dark,if (id == selectedRideId) primary else tertiary,data.segments)
        }.toMap()
    }
    val rideKeys = remember(state.rides) { state.rides.associateBy { "ride:${it.id}" } }
    LaunchedEffect(listState, rideKeys) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }.distinctUntilChanged().collect { keys ->
            latestVisible(keys.mapNotNull { rideKeys[it]?.id }.take(20))
        }
    }
    LaunchedEffect(listState,renderKeys) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }.distinctUntilChanged().collect { keys ->
            latestImages(keys.mapNotNull { rideKeys[it]?.id?.let(renderKeys::get) }.take(20))
        }
    }
    DisposableEffect(Unit) { onDispose { latestVisible(emptyList()); latestImages(emptyList()) } }
    Column(modifier) {
    LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                date ?: stringResource(Res.string.history_date_unknown), listOfNotNull(duration, status).joinToString(" · "),
                cardStats.stats[ride.id]?.maxDuty?.let { stringResource(Res.string.history_card_max_duty, historyCardMark(it) + it.text()) }),
                previews.previews[ride.id]?.segments.orEmpty(), ride.id == selectedRideId, { onOpen(ride.id) },
                if (reduceMotion) Modifier else Modifier.animateItem(), previewLoading = ride.id in previews.loading,
                previewPartial = previews.previews[ride.id]?.partial == true, previewError = ride.id in previews.errors, statsError = historyCardStatsRetryOnly(ride.id, previews, cardStats),
                onRetry = { onRetryPreview(ride.id) }, image = previewImages.images[ride.id]?.takeIf {
                    renderKeys[ride.id] != null && previewImages.renderKeys[ride.id] == renderKeys[ride.id]
                }, onDelete = onDelete?.takeIf { ride.status == HistoryRideStatus.LEGACY ||
                    (ride.status != HistoryRideStatus.RECORDING && ride.ended != null) }?.let { action -> { action(ride) } })
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
    if (previewImages.images.isNotEmpty()) HistoryPreviewAttribution()
    }
}
