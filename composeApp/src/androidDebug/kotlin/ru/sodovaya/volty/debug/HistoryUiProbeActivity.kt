package ru.sodovaya.volty.debug

import android.os.Bundle
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.arkivanov.decompose.defaultComponentContext
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.presentation.common.VoltyTheme
import ru.sodovaya.volty.presentation.history.*
import ru.sodovaya.volty.presentation.map.*
import volty.composeapp.generated.resources.*
import org.koin.android.ext.android.getKoin

/** Native rendering/input fixture. Earlier revisions used synthetic data only;
 * stored=true now reads closed rides and persisted windows of unclosed legacy
 * rides through app DI. It has no qualified clock for a live v2 recording.
 * Neither ordinary mode writes recordings or starts BLE/location. Synthetic is default.
 * Explicit favorites=true seeds one named debug recording for persistent favorite UI checks.
 * This source/manifest belongs exclusively to the Android debug variant.
 */
@OptIn(ExperimentalMaterial3Api::class)
class HistoryUiProbeActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("nativeGesture") == "pinch") dispatchHistoryProbePinch(window.decorView,
            intent.getFloatExtra("centerX", Float.NaN), intent.getFloatExtra("centerY", Float.NaN),
            intent.getFloatExtra("startSpan", Float.NaN), intent.getFloatExtra("endSpan", Float.NaN),
            intent.getBooleanExtra("cancelGesture", false))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val favoriteFixture = intent.getBooleanExtra("favorites", false)
        val stored = favoriteFixture || intent.getBooleanExtra("stored", false)
        val home = favoriteFixture || intent.getBooleanExtra("home", false)
        val catalog = stored || intent.getBooleanExtra("catalog", false)
        val repository = if (stored) getKoin().get<HistoryReadRepository>()
            else ProbeReader(intent.getBooleanExtra("omitFirstSource", false),intent.getBooleanExtra("remotePreviewArea", false),
                home || intent.getBooleanExtra("summary", false) || intent.getBooleanExtra("charts", false),
                home || intent.getBooleanExtra("charts", false), intent.getLongExtra("readDelayMillis", 0).coerceIn(0, 3000))
        val owner = DefaultHistoryComponent(defaultComponentContext(), lifecycleScope, repository,
            { if (stored) error("Stored debug probe has no active v2 recording clock") else origin }, ::finish,
            getKoin().get<HistoryMetricPresetStore>(),getKoin().get<HistoryPreviewImageLoader>(),
            favoriteStore = getKoin().get<HistoryFavoriteRepository>(), mediaRepository = getKoin().get<HistoryMediaRepository>(),
            deletionRepository = if (stored) getKoin().get<HistoryRideDeletionRepository>() else null,
            exportRepository = if (stored) getKoin().get<ru.sodovaya.volty.domain.history.HistoryExportRepository>() else null)
        if (intent.getBooleanExtra("charts", false)) lifecycleScope.launch {
            var previous: Pair<HistoryRange?,HistoryRange?>? = null
            owner.state.collect { state ->
                val windows = state.detail.range to state.windowPreview
                if (windows != previous) { previous = windows; Log.d("HistoryProbeWindow", "committed=${windows.first} preview=${windows.second}") }
            }
        }
        if (favoriteFixture) lifecycleScope.launch {
            if (intent.getBooleanExtra("seedPickedMedia", false)) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { seedPickedMedia() }
            val store = getKoin().get<HistoryStore>()
            val id = "history-favorite-ui-probe"
            if (store.ride(id) == null) {
                val start = HistoryTick(1792400000000, 1000, 99)
                store.begin(HistoryRide(id, "favorite-ui-bike", start, null, HistoryRideStatus.RECORDING),
                    listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Debug pack")))
                val fixes = (0L..6L).map { i -> HistoryGpsFix(start.copy(epochMillis = start.epochMillis + i * 10000,
                    elapsedMillis = start.elapsedMillis + i * 10000), 56.84 + i * 0.0001, 60.6 + i * 0.0001, 3.0, null) }
                store.append(HistoryBatch(id, fixes.map { HistorySample(it.tick, HistorySeriesKey("pack", "voltage_v"), 72.0) },
                    fixes, emptyList()))
                store.finish(id, fixes.last().tick, HistoryRideStatus.COMPLETED)
            }
            owner.openRide(id)
        } else if (catalog) owner.refresh() else owner.openRide(if (home) "fixture-0" else "fixture")
        setContent {
            VoltyTheme {
                val state by owner.state.collectAsState()
                if (home) {
                    if (!stored) LaunchedEffect(state.detail.ride?.id) {
                        if (state.detail.ride != null) listOf(HistorySeriesKey("fixture-a", "power_w"),
                            HistorySeriesKey("fixture-a", "voltage_v"), HistorySeriesKey("fixture-b", "current_a")).forEach(owner::addMetric)
                    }
                    HistoryScreen(owner, { 0 }, 1000, vehicleNames = if (stored) emptyMap() else
                        mapOf("fixture-vehicle-0" to "Scooter", "fixture-vehicle-1" to "Bicycle", "fixture-vehicle-2" to "Wheel"),
                        reduceMotion = intent.getBooleanExtra("reduceMotion", false))
                    return@VoltyTheme
                }
                val catalogListState = rememberLazyListState()
                var drawer by remember { mutableStateOf(true) }
                if (intent.getBooleanExtra("summary", false) || intent.getBooleanExtra("charts", false)) {
                    LaunchedEffect(state.detail.ride?.id) {
                        if (state.detail.ride != null) {
                            listOf(HistorySeriesKey("fixture-a", "power_w"), HistorySeriesKey("fixture-a", "voltage_v"),
                                HistorySeriesKey("fixture-b", "current_a")).forEach(owner::addMetric)
                            if (intent.getBooleanExtra("charts", false)) owner.showCharts()
                        }
                    }
                    BackHandler(enabled = state.destination == HistoryDestination.CHARTS) { owner.back() }
                    if (state.destination == HistoryDestination.CHARTS) HistoryChartsScreen(owner, state, { 0 })
                    else MapProbe(owner, state, showSummary = true)
                    return@VoltyTheme
                }
                if (catalog) {
                    BackHandler(enabled = state.destination != HistoryDestination.CATALOG) { owner.back() }
                    if (state.destination == HistoryDestination.CATALOG) {
                        Scaffold(topBar = { TopAppBar(title = { Column {
                            Text(stringResource(Res.string.history_title))
                            Text("Images ${state.previewImages.images.size} · pending ${state.previewImages.loading.size} · fallback ${state.previewImages.unavailable.size}",
                                style = MaterialTheme.typography.labelSmall)
                        } },
                            navigationIcon = { IconButton(onClick = owner::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { padding ->
                            HistoryRideList(state.catalog, state.selectedRideId, owner::openRide, owner::refresh, owner::loadOlder,
                                Modifier.fillMaxSize().padding(padding),
                                vehicleNames = if (stored) emptyMap() else mapOf("fixture-vehicle-0" to "Scooter", "fixture-vehicle-1" to "Bicycle", "fixture-vehicle-2" to "Wheel"),
                                previews = state.previews, onVisibleChanged = owner::setVisibleRides, onRetryPreview = owner::retryPreview,
                                listState = catalogListState, previewImages = state.previewImages,
                                previewRevision = state.previewRevision, onVisibleImagesChanged = owner::setVisiblePreviewImages)
                        }
                    } else MapProbe(owner, state)
                    return@VoltyTheme
                }
                if (intent.getBooleanExtra("map", false)) {
                    MapProbe(owner, state)
                    return@VoltyTheme
                }
                Scaffold(topBar = { TopAppBar(title = { Text(stringResource(Res.string.history_metrics_title)) }) },
                    floatingActionButton = { ExtendedFloatingActionButton(onClick = { drawer = true },
                        icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(Res.string.history_metrics_title)) }) }) { padding ->
                    HistorySelectedMetrics(state.detail, owner::removeMetric, owner::moveMetric,
                        Modifier.fillMaxSize().padding(padding))
                }
                if (drawer) HistoryMetricDrawer(state.detail, owner::loadSourceMetrics, owner::addMetric,
                    owner::removeMetric, onDismiss = { drawer = false }, presets = state.presets,
                    onLoadPresets = owner::loadPresets, onSavePreset = owner::savePreset,
                    onApplyPreset = owner::applyPreset, onDeletePreset = owner::deletePreset)
            }
        }
    }

    /** Explicit debug-only gallery fixture, never an ordinary startup scan. */
    private fun seedPickedMedia() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val name = "volty-history-probe.jpg"
        val collection = android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        contentResolver.query(collection, arrayOf(android.provider.MediaStore.Images.Media._ID),
            "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(name), null)?.use { if (it.moveToFirst()) return }
        val file = java.io.File(cacheDir, "history-picked-fixture.jpg")
        val bitmap = android.graphics.Bitmap.createBitmap(320, 240, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.rgb(36, 64, 96))
            canvas.drawRect(0f, 160f, 320f, 240f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(64, 140, 96) })
            canvas.drawText("VOLTY RIDE", 30f, 110f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 32f })
            file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)) }
            android.media.ExifInterface(file).apply {
                setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:19 13:53:40")
                setAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+05:00")
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VoltyHistoryProbe")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(contentResolver.insert(collection, values))
            try {
                checkNotNull(contentResolver.openOutputStream(uri)).use { target -> file.inputStream().use { it.copyTo(target) } }
                contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                Log.i("HistoryMediaProbe", "Gallery fixture ready")
            } catch (failure: Exception) { contentResolver.delete(uri, null, null); throw failure }
        } finally { bitmap.recycle(); file.delete() }
    }

    @Composable
    private fun MapProbe(owner: HistoryComponent, state: HistoryUiState, showSummary: Boolean = false) {
        var summaryExpanded by remember(state.selectedRideId) { mutableStateOf(false) }
        val identity = remember(state.selectedRideId) { HistoryMapIdentity(state.selectedRideId ?: "fixture", 1) }
        val rideOrigin = state.detail.ride?.started ?: origin
        val history = remember(identity, rideOrigin, state.route.fixes, state.route.markers, state.detail.cursorEpochMillis) {
            historyMapScene(identity, rideOrigin, state.route.fixes, state.route.markers, state.detail.cursorEpochMillis, 1000)
        }
        val density = LocalDensity.current
        val insets = with(density) { HistoryMapInsets(24.dp.roundToPx(), 48.dp.roundToPx(), 24.dp.roundToPx(), 144.dp.roundToPx()) }
        val fit = remember(identity, insets) { MapCameraRequest.FitHistory(1, identity, insets, reduceMotion = true) }
        Box(Modifier.fillMaxSize()) {
            PlatformRideMapLayer(historyMapNavigationScene(history, fit), darkTheme = true, onCameraGesture = {}, modifier = Modifier.fillMaxSize())
            if (showSummary) HistorySummaryPanel(state.detail, summaryExpanded, { summaryExpanded = it },
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp).fillMaxWidth())
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(), tonalElevation = 3.dp) {
                Column(Modifier.padding(16.dp)) {
                    Text("GPS · ${history.segments.size} segments · ${state.detail.cursorEpochMillis ?: "—"}")
                    val range = state.detail.range
                    if (range != null) Slider(value = historyTimeFraction(range, state.detail.cursorEpochMillis ?: range.startEpochMillis) ?: 0f,
                        onValueChange = { fraction -> historyTimeAtFraction(range, fraction)?.let(owner::selectTime) })
                }
            }
        }
    }

    private class ProbeReader(private val omitFirstSource: Boolean = false,private val remotePreviewArea: Boolean = false,
        private val summaryFixture: Boolean = false, private val chartFixture: Boolean = false,
        private val readDelayMillis: Long = 0) : HistoryReadRepository {
        private val failedReads = mutableSetOf<String>()
        private val sources = listOf(HistorySource("fixture-a", HistorySourceKind.BATTERY, "Pack"),
            HistorySource("fixture-b", HistorySourceKind.BATTERY, "Pack"))
        override suspend fun ride(rideId: String) = HistoryRide(rideId, "fixture-vehicle-${rideId.hashCode().mod(3)}", origin,
            origin.copy(epochMillis = 5000, elapsedMillis = 4010), if (rideId.endsWith("-3")) HistoryRideStatus.LEGACY else HistoryRideStatus.COMPLETED)
        override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> {
            val start = before?.stableId?.removePrefix("fixture-")?.toIntOrNull()?.plus(1) ?: 0
            val rows = (start until minOf(start + 20, 24)).map { ride("fixture-$it") }
            val next = if (start + rows.size < 24) HistoryCursor(1000, rows.last().id) else null
            return HistoryPage(rows, next, next != null)
        }
        override suspend fun sources(rideId: String) = if (omitFirstSource) sources.drop(1) else sources
        override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys {
            val keys = listOf("voltage_v", "current_a", "power_w").map { HistorySeriesKey(sourceId, it) } +
                (0 until 1000).map { HistorySeriesKey(sourceId, "cell_v", it * 2) }
            return HistoryRecordedKeys(keys.take(limit), keys.size > limit)
        }
        override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int): HistorySeries {
            if (readDelayMillis > 0) kotlinx.coroutines.delay(readDelayMillis)
            if (chartFixture) Log.d("HistoryProbeRead", "series $range")
            if (!chartFixture) return HistorySeries(key, emptyList(), emptyList(), false, origin)
            val markers = chartMarkers(key, range)
            return HistorySeries(key, historyOverview(chartSamples(key, range), markers), markers, false, origin)
        }
        private fun chartSamples(key: HistorySeriesKey, range: HistoryRange): List<HistorySample> =
            listOf(1000L, 1500L, 2000L, 2500L, 3000L, 4000L, 4500L, 5000L).mapIndexed { index, time ->
                val value = if (time == 2000L || key.metricId == "current_a") null else
                    if (key.metricId == "power_w") listOf(-500.0, -4200.0, 0.0, 200.0, -700.0, -1200.0, -900.0, -200.0)[index]
                    else 60.0 + index * 3.0
                HistorySample(HistoryTick(time, time - 990, 1), key, value, if (value == null) HistoryUnknownReason.GAP else null)
            }.filter { it.tick.epochMillis in range.startEpochMillis..range.endEpochMillis }
        private fun chartMarkers(key: HistorySeriesKey, range: HistoryRange) =
            listOf(HistoryMarker(HistoryTick(3500, 2510, 1), HistoryMarkerKind.BLE_GAP, key.sourceId))
                .filter { it.tick.epochMillis in range.startEpochMillis..range.endEpochMillis }
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats {
            if (readDelayMillis > 0) kotlinx.coroutines.delay(readDelayMillis)
            if (chartFixture) Log.d("HistoryProbeRead", "stats $range")
            return if (chartFixture) historyStats(chartSamples(key, range), chartMarkers(key, range),
                HistoryMetricRegistry.descriptor(key.metricId)?.consumptionMultiplier)
            else if (!summaryFixture) HistoryStats(null, null, null, false) else when (key.metricId) {
                "power_w" -> HistoryStats(-4200.0, 200.0, 12.0, false)
                "voltage_v" -> HistoryStats(60.0, 84.0, null, true)
                else -> HistoryStats(null, null, null, true)
            }
        }
        override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?) = HistoryPage<HistoryGpsFix>(emptyList(), null, false)
        override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryRoutePage {
            if (readDelayMillis > 0) kotlinx.coroutines.delay(readDelayMillis)
            if (chartFixture) Log.d("HistoryProbeRead", "route $range")
            if (rideId.endsWith("-7") && failedReads.add(rideId)) error("Synthetic one-shot route read failure")
            fun fix(time: Long, lat: Double, lon: Double) = HistoryGpsFix(HistoryTick(time, time - 990, 1), lat,
                lon + if(remotePreviewArea) 50.0 else 0.0, 3.0, null)
            val fixes = listOf(fix(1000, 56.838, 60.600), fix(1500, 56.839, 60.603), fix(2000, 56.840, 60.603),
                fix(3000, 56.843, 60.610), fix(3500, 56.844, 60.612), fix(4000, 56.845, 60.611))
            val rows = (if (after == null) fixes else listOf(fix(4500,56.846,60.613)))
                .filter { it.tick.epochMillis in range.startEpochMillis..range.endEpochMillis }
            val partial = rideId.endsWith("-5") && after == null && rows.lastOrNull()?.tick?.epochMillis == 4000L && range.endEpochMillis >= 4500L
            return HistoryRoutePage(origin, HistoryPage(rows, if (partial) HistoryCursor(4000,"6") else null, partial),
                listOf(HistoryMarker(HistoryTick(2500, 1510, 1), HistoryMarkerKind.GPS_GAP))
                    .filter { it.tick.epochMillis in range.startEpochMillis..range.endEpochMillis })
        }
    }

    private companion object {
        val origin = HistoryTick(1000, 10, 1)
    }
}
