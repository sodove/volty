// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Vescape HistoryOverlay/HistoryRideDetail/HistoryTelemetryPanel.tsx,
// revision 2527c346bbdb25d4e018fdd68e916621e20fac27, author Kacper Kozak.
// Compose ownership, source-qualified data and adaptive layout are Volty changes.
// See README.md and LICENSE in this directory.
package ru.sodovaya.volty.presentation.history

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.presentation.map.*
import volty.composeapp.generated.resources.*

/** One selected read owner and one native map across home/fullscreen charts.
 * The caller owns the component lifetime; this view never starts a recording or GPS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(owner: HistoryComponent, cursorMaxAgeMillis: (HistorySeriesKey) -> Long,
    gpsMaxAgeMillis: Long, modifier: Modifier = Modifier, vehicleNames: Map<String, String> = emptyMap(),
    reduceMotion: Boolean = false) {
    val state by owner.state.collectAsState()
    val content = historyScreenContent(state)
    val catalogList = rememberLazyListState()
    var listOpen by remember { mutableStateOf(false) }
    var metricsOpen by remember(state.selectedRideId) { mutableStateOf(false) }
    var summaryExpanded by remember(state.selectedRideId) { mutableStateOf(false) }
    var chartsExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(state.destination, state.mapMetricKey) {
        if (state.destination == HistoryDestination.RIDE && state.mapMetricKey != null) chartsExpanded = false
    }
    BackHandler(enabled = !listOpen && !metricsOpen) { owner.back() }
    LaunchedEffect(owner, content.phase, listOpen) {
        if ((content.phase == HistoryScreenPhase.CATALOG || listOpen) && !state.catalog.loaded && !state.catalog.refreshing)
            owner.refresh()
    }
    val openRide: (String) -> Unit = { id -> listOpen = false; metricsOpen = false; owner.openRide(id) }
    val catalog: @Composable (Modifier) -> Unit = { bodyModifier ->
        HistoryRideList(state.catalog, state.selectedRideId, openRide, owner::refresh, owner::loadOlder, bodyModifier,
            vehicleNames, state.previews, reduceMotion, owner::setVisibleRides, owner::retryPreview,
            catalogList, state.previewImages, state.previewRevision, owner::setVisiblePreviewImages)
    }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        if (content.phase == HistoryScreenPhase.CATALOG) {
            Scaffold(topBar = { TopAppBar(title = { Text(stringResource(Res.string.history_title)) },
                windowInsets = WindowInsets(0), navigationIcon = { HistoryBack(owner::back) }) }) { padding ->
                catalog(Modifier.fillMaxSize().padding(padding))
            }
        } else {
            val wide = maxWidth >= 600.dp && maxWidth > maxHeight
            val density = LocalDensity.current
            val panelWidth = if (wide) maxWidth * 0.5f else maxWidth
            val panelHeight = if (wide) maxHeight else maxHeight * 0.55f
            val summaryHeight = maxHeight * when {
                summaryExpanded -> 0.62f
                density.fontScale > 1.3f -> 0.45f
                else -> 0.28f
            }
            var topPixels by remember { mutableIntStateOf(0) }
            var panelPixels by remember { mutableIntStateOf(0) }
            var fitSequence by remember { mutableLongStateOf(0) }
            val sidePadding = with(density) { 20.dp.roundToPx() }
            // Loading/ready is included so reopening the SAME ride issues a fresh fit,
            // while range/cursor changes never do. Native map itself stays mounted.
            LaunchedEffect(state.selectedRideId, content.phase == HistoryScreenPhase.LOADING) { fitSequence++ }
            val identity = remember(state.selectedRideId) { HistoryMapIdentity(checkNotNull(state.selectedRideId), 0) }
            val geometry = rememberHistoryScreenMap(identity, content, state.detail.cursorEpochMillis, gpsMaxAgeMillis)
            val coloring = rememberHistoryMapColoring(identity, content.detail, content.route, state.mapMetricKey,
                gpsMaxAgeMillis, state.mapMetricKey?.let(cursorMaxAgeMillis) ?: 0)
            val scene = geometry.copy(coloring = coloring)
            val insets = HistoryMapInsets(sidePadding, topPixels + sidePadding,
                if (wide) with(density) { panelWidth.roundToPx() } + sidePadding else sidePadding,
                if (wide) sidePadding else panelPixels + sidePadding)
            var fit by remember(identity, fitSequence) { mutableStateOf<MapCameraRequest.FitHistory?>(null) }
            val measured = topPixels > 0 && (wide || panelPixels > 0)
            // Freeze measured padding for this command. A panel animation must not
            // refit the camera every frame or undo a rider's manual camera gesture.
            LaunchedEffect(identity, fitSequence, measured, scene.segments.isNotEmpty()) {
                if (fit == null && measured && scene.segments.isNotEmpty())
                    fit = MapCameraRequest.FitHistory(fitSequence, identity, insets, reduceMotion)
            }
            PlatformRideMapLayer(historyMapNavigationScene(scene, fit),
                MaterialTheme.colorScheme.surface.luminance() < 0.5f, {}, Modifier.fillMaxSize())
            if (state.destination != HistoryDestination.CHARTS) {
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth(if (wide) 0.5f else 1f)
                .onSizeChanged { topPixels = it.height }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        HistoryBack(owner::back)
                        Text(stringResource(Res.string.history_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { fitSequence++ }, enabled = scene.segments.isNotEmpty()) {
                            Icon(Icons.Default.MyLocation, stringResource(Res.string.history_fit_route))
                        }
                    }
                }
                if (content.detail != null) HistorySummaryPanel(content.detail, summaryExpanded, { summaryExpanded = it },
                    Modifier.fillMaxWidth().heightIn(max = summaryHeight), reduceMotion)
                HistoryRouteStatus(content, owner)
            }
            Surface(Modifier.align(if (wide) Alignment.CenterEnd else Alignment.BottomCenter).width(panelWidth)
                .heightIn(max = panelHeight).onSizeChanged { panelPixels = it.height },
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = if (wide) 0.dp else 16.dp), tonalElevation = 3.dp) {
                val scrollChrome = panelHeight < 320.dp || density.fontScale > 1.3f
                Column((if (reduceMotion) Modifier else Modifier.animateContentSize(tween(180)))
                    .then(if (scrollChrome) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                    val neighbors = historyRideNeighbors(state.catalog.rides.map { it.id }, state.selectedRideId)
                    val title = content.detail?.ride?.let { vehicleNames[it.vehicleId] } ?: stringResource(Res.string.history_ride_title)
                    val subtitle = content.detail?.ride?.started?.epochMillis?.let { historyRideDate(it) }
                        ?: stringResource(Res.string.history_date_unknown)
                    HistoryNavigationStrip(title, subtitle, neighbors.previousId != null, neighbors.nextId != null,
                        { neighbors.previousId?.let(openRide) }, { neighbors.nextId?.let(openRide) },
                        { listOpen = true }, owner::showCharts, { metricsOpen = true }, Modifier.padding(8.dp))
                    if (content.phase == HistoryScreenPhase.READY) {
                        Surface(onClick = { chartsExpanded = !chartsExpanded }, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(Res.string.history_charts_title), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                IconButton(onClick = { chartsExpanded = !chartsExpanded }) {
                                    Icon(if (chartsExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                        stringResource(if (chartsExpanded) Res.string.history_charts_collapse else Res.string.history_charts_expand))
                                }
                            }
                        }
                        if (chartsExpanded) HistoryChartPanel(checkNotNull(content.detail), owner::selectTime, cursorMaxAgeMillis,
                            Modifier.fillMaxWidth().then(if (scrollChrome) Modifier.height(240.dp) else Modifier.weight(1f)),
                            state.windowPreview, owner, compact = true, mapMetricKey = state.mapMetricKey,
                            onSelectMapMetric = owner::selectMapMetric)
                        state.mapMetricKey?.let { key -> HistoryMapColorLegend(checkNotNull(content.detail), key, coloring,
                            { owner.selectMapMetric(null) }) }
                        HistoryTimelineControls(checkNotNull(content.detail).copy(range = state.windowPreview ?: content.detail.range),
                            owner::selectRange, owner::selectTime, compact = true, applyNavigationInsets = false)
                    } else HistoryDetailStatus(content.phase) { state.selectedRideId?.let(owner::openRide) }
                    HistoryPreviewAttribution()
                }
            }
            }
            // Keep the same map mounted beneath the opaque chart page; there is no
            // second map/read owner and returning does not recreate its camera.
            if (state.destination == HistoryDestination.CHARTS)
                HistoryChartsScreen(owner, state, cursorMaxAgeMillis, Modifier.fillMaxSize(), applySystemInsets = false)
        }
    }
    if (listOpen) ModalBottomSheet(onDismissRequest = { listOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Text(stringResource(Res.string.history_title), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall)
        catalog(Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding())
    }
    if (metricsOpen && content.detail != null) HistoryMetricDrawer(content.detail, owner::loadSourceMetrics,
        owner::addMetric, owner::removeMetric, { metricsOpen = false }, reduceMotion,
        state.presets, owner::loadPresets, owner::savePreset, owner::applyPreset, owner::deletePreset)
}

private data class BoundHistoryMap(val identity: HistoryMapIdentity, val route: HistoryRouteState?,
    val cursor: Long?, val age: Long, val origin: HistoryTick?, val scene: HistoryMapScene)

@Composable
private fun rememberHistoryScreenMap(identity: HistoryMapIdentity, content: HistoryScreenContent, cursor: Long?, age: Long): HistoryMapScene {
    val route = content.route
    val origin = content.detail?.ride?.takeUnless { it.status == HistoryRideStatus.LEGACY }?.started
    val bound by produceState<BoundHistoryMap?>(null, identity, route, cursor, age, origin) {
        value = withContext(Dispatchers.Default) { BoundHistoryMap(identity, route, cursor, age, origin,
            historyMapScene(identity, origin, route?.fixes.orEmpty(), route?.markers.orEmpty(), cursor, age)) }
    }
    val matching = bound?.takeIf { it.identity == identity && it.route === route && it.age == age && it.origin == origin }
    // The route remains earned while a new cursor is calculated off UI. Hide the
    // old selected marker immediately; do not erase/redraw the whole route per frame.
    return matching?.let { if (it.cursor == cursor) it.scene else it.scene.copy(selectedFix = null) }
        ?: HistoryMapScene(identity, emptyList(), null, emptyList())
}

@Composable
private fun HistoryBack(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.history_back)) }
}

@Composable
private fun HistoryDetailStatus(phase: HistoryScreenPhase, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (phase == HistoryScreenPhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(when (phase) {
            HistoryScreenPhase.NOT_FOUND -> Res.string.history_ride_missing
            HistoryScreenPhase.ERROR -> Res.string.history_ride_load_error
            else -> Res.string.history_ride_loading
        }))
        if (phase == HistoryScreenPhase.ERROR) TextButton(onClick = onRetry) { Text(stringResource(Res.string.history_retry)) }
    }
}

@Composable
private fun HistoryRouteStatus(content: HistoryScreenContent, owner: HistoryComponent) {
    if (content.phase != HistoryScreenPhase.READY) return
    val route = content.route
    val label = when {
        route == null || route.loading -> Res.string.history_route_loading
        route.error != null -> Res.string.history_route_error
        route.noRoute -> Res.string.history_route_empty
        route.truncated || route.capped || route.nextCursor != null -> Res.string.history_preview_partial
        else -> null
    } ?: return
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
            if (route?.error != null) TextButton(onClick = owner::retryRoute) { Text(stringResource(Res.string.history_retry)) }
            else if (route?.nextCursor != null && !route.loading && !route.capped)
                TextButton(onClick = owner::loadMoreRoute) { Text(stringResource(Res.string.history_route_more)) }
        }
    }
}

@Composable
private fun historyRideDate(epoch: Long): String {
    val zone = TimeZone.currentSystemDefault()
    return remember(epoch, zone) { runCatching {
        val date = Instant.fromEpochMilliseconds(epoch).toLocalDateTime(zone)
        "${date.date} · ${date.hour.toString().padStart(2, '0')}:${date.minute.toString().padStart(2, '0')}"
    }.getOrNull() } ?: stringResource(Res.string.history_date_unknown)
}
