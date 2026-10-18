package ru.sodovaya.volty.debug

import android.os.Bundle
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
 * the explicit stored=true mode now reads existing closed rides through app DI.
 * Neither mode writes recordings or starts BLE/location. Synthetic is default.
 * This source/manifest belongs exclusively to the Android debug variant.
 */
@OptIn(ExperimentalMaterial3Api::class)
class HistoryUiProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val stored = intent.getBooleanExtra("stored", false)
        val catalog = stored || intent.getBooleanExtra("catalog", false)
        val repository = if (stored) getKoin().get<HistoryReadRepository>() else ProbeReader()
        val owner = DefaultHistoryComponent(defaultComponentContext(), lifecycleScope, repository,
            { if (stored) error("Stored debug probe supports closed rides only") else origin }, ::finish)
        if (catalog) owner.refresh() else owner.openRide("fixture")
        setContent {
            VoltyTheme {
                val state by owner.state.collectAsState()
                val catalogListState = rememberLazyListState()
                var drawer by remember { mutableStateOf(true) }
                if (catalog) {
                    BackHandler(enabled = state.destination != HistoryDestination.CATALOG) { owner.back() }
                    if (state.destination == HistoryDestination.CATALOG) {
                        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(Res.string.history_title)) },
                            navigationIcon = { IconButton(onClick = owner::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { padding ->
                            HistoryRideList(state.catalog, state.selectedRideId, owner::openRide, owner::refresh, owner::loadOlder,
                                Modifier.fillMaxSize().padding(padding),
                                vehicleNames = if (stored) emptyMap() else mapOf("fixture-vehicle-0" to "Scooter", "fixture-vehicle-1" to "Bicycle", "fixture-vehicle-2" to "Wheel"),
                                previews = state.previews, onVisibleChanged = owner::setVisibleRides, onRetryPreview = owner::retryPreview,
                                listState = catalogListState)
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
                    owner::removeMetric, onDismiss = { drawer = false })
            }
        }
    }

    @Composable
    private fun MapProbe(owner: HistoryComponent, state: HistoryUiState) {
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

    private class ProbeReader : HistoryReadRepository {
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
        override suspend fun sources(rideId: String) = sources
        override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys {
            val keys = listOf("voltage_v", "current_a", "power_w").map { HistorySeriesKey(sourceId, it) } +
                (0 until 1000).map { HistorySeriesKey(sourceId, "cell_v", it * 2) }
            return HistoryRecordedKeys(keys.take(limit), keys.size > limit)
        }
        override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int) =
            HistorySeries(key, emptyList(), emptyList(), false, origin)
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange) = HistoryStats(null, null, null, false)
        override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?) = HistoryPage<HistoryGpsFix>(emptyList(), null, false)
        override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryRoutePage {
            if (rideId.endsWith("-7") && failedReads.add(rideId)) error("Synthetic one-shot route read failure")
            fun fix(time: Long, lat: Double, lon: Double) = HistoryGpsFix(HistoryTick(time, time - 990, 1), lat, lon, 3.0, null)
            val fixes = listOf(fix(1000, 56.838, 60.600), fix(1500, 56.839, 60.603), fix(2000, 56.840, 60.603),
                fix(3000, 56.843, 60.610), fix(3500, 56.844, 60.612), fix(4000, 56.845, 60.611))
            val partial = rideId.endsWith("-5") && after == null
            val rows = if (after == null) fixes else listOf(fix(4500,56.846,60.613))
            return HistoryRoutePage(origin, HistoryPage(rows, if (partial) HistoryCursor(4000,"6") else null, partial),
                listOf(HistoryMarker(HistoryTick(2500, 1510, 1), HistoryMarkerKind.GPS_GAP)))
        }
    }

    private companion object {
        val origin = HistoryTick(1000, 10, 1)
    }
}
