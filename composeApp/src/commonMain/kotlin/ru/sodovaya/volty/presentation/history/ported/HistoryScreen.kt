// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Vescape HistoryOverlay/HistoryRideDetail/HistoryTelemetryPanel.tsx,
// revision 2527c346bbdb25d4e018fdd68e916621e20fac27, author Kacper Kozak.
// Compose ownership, source-qualified data and adaptive layout are Volty changes.
// See README.md and LICENSE in this directory.
package ru.sodovaya.volty.presentation.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.presentation.common.LocalVoltyDarkTheme
import ru.sodovaya.volty.presentation.common.SyncLightDashboardSystemBars
import ru.sodovaya.volty.presentation.map.*
import ru.sodovaya.volty.presentation.ride.*
import volty.composeapp.generated.resources.*

/** One selected read owner and one native map across home/fullscreen charts.
 * The caller owns the component lifetime; this view never starts a recording or GPS.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(owner: HistoryComponent, cursorMaxAgeMillis: (HistorySeriesKey) -> Long,
    gpsMaxAgeMillis: Long, modifier: Modifier = Modifier, vehicleNames: Map<String, String> = emptyMap(),
    reduceMotion: Boolean = false, mapHost: HistoryMapHost? = null, mapViewportHeight: Dp? = null) {
    val state by owner.state.collectAsState()
    val requestRecordingLocation = rememberHistoryRecordingLocationRequest(owner)
    val content = historyScreenContent(state)
    DisposableEffect(mapHost, owner) { onDispose { mapHost?.clear(owner) } }
    val mediaPositions = rememberHistoryMediaPositions(state, gpsMaxAgeMillis)
    val catalogList = rememberLazyListState()
    val favoritesList = rememberLazyListState()
    var listOpen by remember { mutableStateOf(false) }
    var deletionSelection by remember(listOpen) { mutableStateOf<Set<HistoryFavoriteSource>?>(null) }
    var metricsOpen by remember(state.selectedRideId) { mutableStateOf(false) }
    var favoritesTab by remember { mutableStateOf(false) }
    var trimOpen by remember(state.selectedRideId) { mutableStateOf(false) }
    var mediaOpen by remember(state.media.favoriteId) { mutableStateOf(false) }
    LaunchedEffect(state.deletion.result) {
        val result = state.deletion.result ?: return@LaunchedEffect
        if (state.selectedRideId == null || (deletionSelection == null && result.sources.size == 1)) listOpen = false
        deletionSelection = null
        metricsOpen = false
        trimOpen = false
        mediaOpen = false
    }
    LaunchedEffect(owner, state.media.favoriteId) {
        if (state.media.favoriteId != null) owner.loadMedia()
    }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(state.favorites.busy, state.favorites.savedId, submitted) {
        if (submitted && !state.favorites.busy && state.favorites.savedId != null) {
            trimOpen = false; submitted = false
        }
    }
    LaunchedEffect(favoritesTab, listOpen) {
        if (favoritesTab && !state.favorites.loaded && !state.favorites.busy) owner.loadFavorites()
    }
    var summaryExpanded by remember(state.selectedRideId) { mutableStateOf(false) }
    // Closed by default: the map and the totals are the ride at a glance; charts are opened on purpose.
    var chartsExpanded by remember(state.selectedRideId) { mutableStateOf(false) }
    LaunchedEffect(state.destination, state.mapMetricKey) {
        if (state.destination == HistoryDestination.RIDE && state.mapMetricKey != null) chartsExpanded = false
    }
    BackHandler(enabled = !listOpen && !metricsOpen && !trimOpen && !mediaOpen && state.media.open == null &&
        state.deletion.targets.isEmpty() && state.deletion.result == null && state.export.source == null) {
        if (deletionSelection != null) deletionSelection = null else owner.back()
    }
    LaunchedEffect(owner, content.phase, listOpen) {
        if ((content.phase == HistoryScreenPhase.CATALOG || listOpen) && !state.catalog.loaded && !state.catalog.refreshing)
            owner.refresh()
    }
    val openRide: (String) -> Unit = { id -> listOpen = false; metricsOpen = false; owner.openRide(id) }
    val catalog: @Composable (Modifier, androidx.compose.ui.unit.Dp) -> Unit = { bodyModifier, recordingHeight ->
        Column(bodyModifier) {
        if (content.phase == HistoryScreenPhase.CATALOG) HistoryRecordingPanel(state.recording,
            owner::startRecording, owner::stopRecording, requestRecordingLocation,
            Modifier.fillMaxWidth().heightIn(max = recordingHeight), reduceMotion)
        if (state.favorites.available) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!favoritesTab, { favoritesTab = false }, label = { Text(stringResource(Res.string.history_title), maxLines = 1) })
            FilterChip(favoritesTab, { deletionSelection = null; favoritesTab = true },
                label = { Text(stringResource(Res.string.history_favorites), maxLines = 1) })
        }
        if (favoritesTab) HistoryFavoritesScreen(state.favorites, state.selectedFavorite?.id,
            { listOpen = false; owner.openFavorite(it) }, owner::loadFavorites, owner::loadOlderFavorites,
            owner::deleteFavorite, Modifier.fillMaxWidth().weight(1f), favoritesList)
        else {
            val selection = deletionSelection
            val selectedRides = if (selection == null) emptyList() else state.catalog.rides.filter {
                canRequestRideDeletion(it) && it.favoriteSource() in selection
            }
            if (state.deletion.available) {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selection == null) {
                        if (state.catalog.rides.any(::canRequestRideDeletion)) {
                            TextButton(onClick = { deletionSelection = emptySet() }) {
                                Text(stringResource(Res.string.history_rides_select))
                            }
                        }
                    } else {
                        TextButton(onClick = { deletionSelection = null }) {
                            Text(stringResource(Res.string.history_ride_delete_cancel))
                        }
                        TextButton(
                            onClick = { owner.requestRideDeletion(selectedRides) },
                            enabled = selectedRides.isNotEmpty() && !state.deletion.busy,
                        ) {
                            Text(stringResource(Res.string.history_rides_delete_selected))
                        }
                        Text(stringResource(Res.string.history_rides_selected_count, selectedRides.size),
                            Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            HistoryRideList(state.catalog, state.selectedRideId, openRide, owner::refresh, owner::loadOlder,
                Modifier.fillMaxWidth().weight(1f), vehicleNames, state.previews, reduceMotion,
                owner::setVisibleRides, owner::retryPreview, catalogList, state.previewImages, state.previewRevision,
                owner::setVisiblePreviewImages,
                onDelete = if (state.deletion.available) owner::requestRideDeletion else null,
                cardStats = state.cardStats, selection = selection, onToggleSelection = { ride ->
                    deletionSelection?.let { current ->
                        val source = ride.favoriteSource()
                        deletionSelection = if (source in current) current - source else current + source
                    }
                })
        }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().background(
        if (mapHost != null && content.phase != HistoryScreenPhase.CATALOG) androidx.compose.ui.graphics.Color.Transparent
        else MaterialTheme.colorScheme.background)) {
        if (content.phase == HistoryScreenPhase.CATALOG) {
            Scaffold(Modifier.safeDrawingPadding(), topBar = { TopAppBar(title = { Text(stringResource(Res.string.history_title)) },
                windowInsets = WindowInsets(0), navigationIcon = {
                    HistoryBack { if (deletionSelection != null) deletionSelection = null else owner.back() }
                }) }) { padding ->
                catalog(Modifier.fillMaxSize().padding(padding), (maxHeight / 3).coerceAtMost(240.dp))
            }
        } else {
            val density = LocalDensity.current
            val direction = LocalLayoutDirection.current
            val safeDrawing = WindowInsets.safeDrawing
            val externalTop = safeDrawing.getTop(density)
            val safeBottom = safeDrawing.getBottom(density)
            // Both maps extend beneath the system bars. The retained map also
            // extends behind any root call/radio dock below this screen.
            val dockHeight = mapViewportHeight?.let { with(density) { it.roundToPx() } - constraints.maxHeight } ?: 0
            val externalBottom = safeBottom + dockHeight.coerceAtLeast(0)
            val externalLeft = safeDrawing.getLeft(density, direction)
            val externalRight = safeDrawing.getRight(density, direction)
            val usableWidth = (maxWidth - with(density) { (externalLeft + externalRight).toDp() }).coerceAtLeast(0.dp)
            val usableHeight = (maxHeight - with(density) { (externalTop + safeBottom).toDp() }).coerceAtLeast(0.dp)
            val wide = usableWidth >= 600.dp && usableWidth > usableHeight
            val panelWidth = if (wide) maxWidth * 0.46f else maxWidth
            val summaryHeight = (usableHeight * 0.40f).coerceIn(64.dp, 300.dp)
            val chartHeight = if (wide) (usableHeight - 154.dp).coerceIn(80.dp, 160.dp)
                else (usableHeight * 0.28f).coerceIn(128.dp, 168.dp)
            val layoutMode = lightLayoutMode(usableWidth.value, usableHeight.value)
            val darkTheme = LocalVoltyDarkTheme.current
            if (mapHost == null && state.destination == HistoryDestination.RIDE) SyncLightDashboardSystemBars(darkTheme)
            var topPixels by remember { mutableIntStateOf(0) }
            var panelPixels by remember { mutableIntStateOf(0) }
            var fitSequence by remember { mutableLongStateOf(0) }
            val sidePadding = with(density) { 20.dp.roundToPx() }
            // Loading/ready is included so reopening the SAME ride issues a fresh fit,
            // while range/cursor changes never do. Native map itself stays mounted.
            LaunchedEffect(state.selectedRideId, content.phase == HistoryScreenPhase.LOADING) { fitSequence++ }
            val identity = remember(state.selectedRideId, mapHost?.viewportGeneration) {
                HistoryMapIdentity(checkNotNull(state.selectedRideId), mapHost?.viewportGeneration ?: 0)
            }
            val geometry = rememberHistoryScreenMap(identity, content, state.detail.cursorEpochMillis, gpsMaxAgeMillis)
            val coloring = rememberHistoryMapColoring(identity, content.detail, content.route, state.mapMetricKey,
                gpsMaxAgeMillis, state.mapMetricKey?.let(cursorMaxAgeMillis) ?: 0)
            val scene = geometry.copy(coloring = coloring, mediaFixes = mediaPositions)
            val chartOnRight = wide && chartsExpanded
            val rightPanelPixels = if (chartOnRight) with(density) { panelWidth.roundToPx() } else 0
            val insets = HistoryMapInsets(
                sidePadding + externalLeft, topPixels + sidePadding + externalTop,
                rightPanelPixels + sidePadding + externalRight,
                (if (chartOnRight) 0 else panelPixels) + sidePadding + externalBottom,
            )
            var fit by remember(identity, fitSequence) { mutableStateOf<MapCameraRequest.FitHistory?>(null) }
            val measured = topPixels > 0 && (wide || panelPixels > 0)
            // Freeze measured padding for this command. A panel animation must not
            // refit the camera every frame or undo a rider's manual camera gesture.
            LaunchedEffect(identity, fitSequence, measured, scene.segments.isNotEmpty()) {
                if (fit == null && measured && scene.segments.isNotEmpty())
                    fit = MapCameraRequest.FitHistory(fitSequence, identity, insets, reduceMotion)
            }
            val mapScene = historyMapNavigationScene(scene, fit)
            if (mapHost == null) PlatformRideMapLayer(mapScene,
                darkTheme, {}, Modifier.matchParentSize(),
                onHistoryMediaClick = { id -> state.media.items.singleOrNull { it.id == id }?.let(owner::openMedia) },
                showAttribution = false)
            else SideEffect {
                val source = content.detail?.ride?.favoriteSource()
                val range = content.detail?.range
                if (source != null && range != null) mapHost.publish(owner, HistoryHostedMap(source, range, mapScene))
                else mapHost.clear(owner)
            }
            if (state.destination != HistoryDestination.CHARTS) {
                LightMapVignette(darkTheme, Modifier.matchParentSize())
                CompositionLocalProvider(LocalLightHudPalette provides lightHudPalette()) {
                    val headerPadding = 6.dp
                    val panelPadding = 10.dp
                    val detail = content.detail
                    val title = state.selectedFavorite?.name ?: detail?.ride?.let { vehicleNames[it.vehicleId] }
                        ?: stringResource(Res.string.history_ride_title)
                    val subtitle = detail?.ride?.started?.epochMillis?.let { historyRideDate(it) }
                        ?: stringResource(Res.string.history_date_unknown)
                    val actions = buildList {
                        if (state.favorites.available && detail?.availableRange?.let { it.endEpochMillis > it.startEpochMillis } == true) {
                            add(HistoryRideAction(
                                stringResource(if (state.selectedFavorite == null) Res.string.history_favorite_add else Res.string.history_favorite_edit),
                                if (state.selectedFavorite == null) Icons.Default.StarBorder else Icons.Default.Star,
                            ) { submitted = false; trimOpen = true })
                        }
                        if (state.media.available && state.media.favoriteId != null) {
                            add(HistoryRideAction(stringResource(Res.string.history_media_title), Icons.Default.PhotoLibrary) { mediaOpen = true })
                        }
                        if (state.export.available && content.phase == HistoryScreenPhase.READY) {
                            add(HistoryRideAction(stringResource(Res.string.history_export_title), Icons.Default.Share, owner::openExport))
                        }
                    }
                    Column(
                        platformNavigationGlass(
                            Modifier.align(Alignment.TopStart).fillMaxWidth(if (wide) 0.56f else 1f),
                            fade = MapGlassFade.BOTTOM,
                        ).padding(bottom = MapGlassFadeDepth)
                            .windowInsetsPadding(safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .onSizeChanged { topPixels = it.height }
                            .padding(horizontal = headerPadding).padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        HistoryRideHeader(title, subtitle, layoutMode, { listOpen = true }, owner::back, actions)
                        if (detail != null) HistorySummaryPanel(
                            state.totals, summaryExpanded,
                            onExpandedChange = { expanded ->
                                summaryExpanded = expanded
                                if (expanded) chartsExpanded = false
                            },
                            modifier = Modifier.padding(horizontal = panelPadding - headerPadding)
                                .heightIn(max = summaryHeight),
                            reduceMotion = reduceMotion,
                        )
                        if (content.phase == HistoryScreenPhase.READY) HistoryRouteStatus(content, owner)
                        else HistoryHudSurface(Modifier.fillMaxWidth()) { HistoryDetailStatus(content.phase, owner::retrySelectedRide) }
                    }
                    if (scene.segments.isNotEmpty()) {
                        LightCircleButton(
                            Icons.Default.LocationSearching, stringResource(Res.string.history_fit_route),
                            Modifier.align(if (wide) Alignment.TopEnd else Alignment.CenterEnd)
                                .offset(y = if (wide) maxHeight * 0.22f else 0.dp)
                                .windowInsetsPadding(safeDrawing.only(WindowInsetsSides.Horizontal))
                                .padding(end = 10.dp),
                        ) { fitSequence++ }
                    }
                    Column(
                        platformNavigationGlass(
                            Modifier.align(Alignment.BottomEnd).width(panelWidth),
                            fade = MapGlassFade.TOP,
                        ).padding(top = MapGlassFadeDepth)
                            .windowInsetsPadding(safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                            .onSizeChanged { panelPixels = it.height }
                            .padding(horizontal = panelPadding),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        if (content.phase == HistoryScreenPhase.READY && detail != null) {
                            state.mapMetricKey?.let { key ->
                                HistoryHudSurface(Modifier.fillMaxWidth()) {
                                    HistoryMapColorLegend(detail, key, coloring, { owner.selectMapMetric(null) })
                                }
                            }
                            HistoryRideAnalysis(
                                detail, state, owner, chartsExpanded,
                                onToggle = {
                                    chartsExpanded = !chartsExpanded
                                    if (chartsExpanded) summaryExpanded = false
                                },
                                onOpenMetrics = { metricsOpen = true },
                                cursorMaxAgeMillis = cursorMaxAgeMillis,
                                reduceMotion = reduceMotion,
                                chartModifier = Modifier.fillMaxWidth().height(chartHeight),
                            )
                        }
                        MapAttributionCorner(darkTheme)
                    }
                }
            }
            // Keep the same map mounted beneath the opaque chart page; there is no
            // second map/read owner and returning does not recreate its camera.
            if (state.destination == HistoryDestination.CHARTS)
                HistoryChartsScreen(owner, state, cursorMaxAgeMillis,
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
                    applySystemInsets = false)
        }
    }
    if (listOpen && content.phase != HistoryScreenPhase.CATALOG) ModalBottomSheet(onDismissRequest = { listOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        val neighbors = historyRideNeighbors(state.catalog.rides.map { it.id }, state.selectedRideId)
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.history_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            HistoryRideStepButtons(
                neighbors.previousId?.let { id -> { openRide(id) } },
                neighbors.nextId?.let { id -> { openRide(id) } },
            )
        }
        catalog(Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding(), 240.dp)
    }
    if (metricsOpen && content.detail != null) HistoryMetricDrawer(content.detail, owner::loadSourceMetrics,
        owner::addMetric, owner::removeMetric, { metricsOpen = false }, reduceMotion,
        state.presets, owner::loadPresets, owner::savePreset, owner::applyPreset, owner::deletePreset)
    if (mediaOpen && state.media.favoriteId != null) HistoryMediaDrawer(owner, state.media, mediaPositions.keys) { mediaOpen = false }
    state.media.open?.let { HistoryMediaViewer(it, owner::consumeMediaOpen) }
    HistoryExportSheet(state.export, owner::export, owner::dismissExport)
    HistoryRideDeletionDialog(state.deletion, owner::confirmRideDeletion, owner::dismissRideDeletion,
        owner::requestRideDeletion, owner::dismissRideDeletionResult)
    val bounds = content.detail?.availableRange
    val selectedRange = content.detail?.range
    if (trimOpen && bounds != null && selectedRange != null && bounds.endEpochMillis > bounds.startEpochMillis) {
        HistoryTrimPanel(bounds, selectedRange, state.selectedFavorite?.name ?: stringResource(Res.string.history_ride_title),
            state.selectedFavorite != null, state.favorites,
            { name, range -> submitted = true; owner.saveFavorite(name, range, state.selectedFavorite) },
            { trimOpen = false; submitted = false })
    }
}

@Composable
private fun rememberHistoryMediaPositions(state: HistoryUiState, gpsMaxAgeMillis: Long): Map<String, HistoryGpsFix> {
    val favorite = state.selectedFavorite; val ride = state.detail.ride; val route = state.route; val media = state.media
    data class Bound(val favorite: HistoryFavorite?, val ride: HistoryRide?, val route: HistoryRouteState,
        val items: List<HistoryMediaRecord>, val favoriteId: String?, val age: Long, val fixes: Map<String, HistoryGpsFix>)
    val bound by produceState<Bound?>(null, favorite, ride, route, media.items, media.favoriteId, gpsMaxAgeMillis) {
        value = withContext(Dispatchers.Default) { Bound(favorite, ride, route, media.items, media.favoriteId, gpsMaxAgeMillis,
            historyMediaPositions(favorite, ride, route, media, gpsMaxAgeMillis)) }
    }
    // produceState keys use value equality. A refreshed immutable list may be
    // equal but have a new identity, which must not hide its earned positions.
    return bound?.takeIf { it.favorite == favorite && it.ride == ride && it.route == route &&
        it.items == media.items && it.favoriteId == media.favoriteId && it.age == gpsMaxAgeMillis }?.fixes.orEmpty()
}

private data class BoundHistoryMap(val identity: HistoryMapIdentity, val route: HistoryRouteState?,
    val age: Long, val origin: HistoryTick?, val track: HistoryMapTrack)

@Composable
private fun rememberHistoryScreenMap(identity: HistoryMapIdentity, content: HistoryScreenContent, cursor: Long?, age: Long): HistoryMapScene {
    val route = content.route
    val origin = content.detail?.ride?.takeUnless { it.status == HistoryRideStatus.LEGACY }?.started
    // The route's geometry is built off UI once per route; the cursor is looked up in the frame itself.
    // Keying the build on the cursor restarted it on every scrubbed frame, and the marker stayed
    // hidden until a build finished - which, under a moving finger, it never did.
    val bound by produceState<BoundHistoryMap?>(null, identity, route, age, origin) {
        value = withContext(Dispatchers.Default) { BoundHistoryMap(identity, route, age, origin,
            historyMapTrack(identity, origin, route?.fixes.orEmpty(), route?.markers.orEmpty(), age)) }
    }
    val matching = bound?.takeIf { it.identity == identity && it.route === route && it.age == age && it.origin == origin }
    return remember(matching, cursor) { matching?.track?.at(cursor) }
        ?: HistoryMapScene(identity, emptyList(), null, emptyList())
}

@Composable
private fun HistoryBack(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.history_back)) }
}

@Composable
private fun HistoryDetailStatus(phase: HistoryScreenPhase, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (phase == HistoryScreenPhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
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
    HistoryHudSurface {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
            if (route?.error != null) TextButton(onClick = owner::retryRoute) { Text(stringResource(Res.string.history_retry)) }
            else if (route?.nextCursor != null && !route.loading && !route.capped)
                TextButton(onClick = owner::loadMoreRoute) { Text(stringResource(Res.string.history_route_more)) }
        }
    }
}

@Composable
internal fun historyRideDate(epoch: Long, includeSeconds: Boolean = false): String {
    val zone = TimeZone.currentSystemDefault()
    return remember(epoch, zone, includeSeconds) { runCatching {
        val date = Instant.fromEpochMilliseconds(epoch).toLocalDateTime(zone)
        "${date.date} · ${date.hour.toString().padStart(2, '0')}:${date.minute.toString().padStart(2, '0')}" +
            if (includeSeconds) ":${date.second.toString().padStart(2, '0')}" else ""
    }.getOrNull() } ?: stringResource(Res.string.history_date_unknown)
}
