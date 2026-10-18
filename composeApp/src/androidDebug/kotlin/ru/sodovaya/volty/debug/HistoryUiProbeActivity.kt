package ru.sodovaya.volty.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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

/** Native rendering/input fixture only. No recording, BLE, GPS or user database.
 * This source/manifest belongs exclusively to the Android debug variant.
 */
@OptIn(ExperimentalMaterial3Api::class)
class HistoryUiProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val owner = DefaultHistoryComponent(defaultComponentContext(), lifecycleScope, ProbeReader(), { origin }, ::finish)
        owner.openRide("fixture")
        setContent {
            VoltyTheme {
                val state by owner.state.collectAsState()
                var drawer by remember { mutableStateOf(true) }
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
        val identity = remember { HistoryMapIdentity("fixture", 1) }
        val history = remember(state.route.fixes, state.route.markers, state.detail.cursorEpochMillis) {
            historyMapScene(identity, origin, state.route.fixes, state.route.markers, state.detail.cursorEpochMillis, 1000)
        }
        val density = LocalDensity.current
        val insets = with(density) { HistoryMapInsets(24.dp.roundToPx(), 48.dp.roundToPx(), 24.dp.roundToPx(), 144.dp.roundToPx()) }
        val fit = remember(insets) { MapCameraRequest.FitHistory(1, identity, insets, reduceMotion = true) }
        Box(Modifier.fillMaxSize()) {
            PlatformRideMapLayer(historyMapNavigationScene(history, fit), darkTheme = true, onCameraGesture = {}, modifier = Modifier.fillMaxSize())
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(), tonalElevation = 3.dp) {
                Column(Modifier.padding(16.dp)) {
                    Text("GPS · ${history.segments.size} segments · ${state.detail.cursorEpochMillis ?: "—"}")
                    val range = state.detail.range
                    if (range != null) Slider(value = (state.detail.cursorEpochMillis ?: range.startEpochMillis).toFloat(),
                        onValueChange = { owner.selectTime(it.toLong()) },
                        valueRange = range.startEpochMillis.toFloat()..range.endEpochMillis.toFloat())
                }
            }
        }
    }

    private class ProbeReader : HistoryReadRepository {
        private val sources = listOf(HistorySource("fixture-a", HistorySourceKind.BATTERY, "Pack"),
            HistorySource("fixture-b", HistorySourceKind.BATTERY, "Pack"))
        override suspend fun ride(rideId: String) = HistoryRide(rideId, "fixture", origin, origin.copy(epochMillis = 5000, elapsedMillis = 4010), HistoryRideStatus.COMPLETED)
        override suspend fun rides(before: HistoryCursor?) = HistoryPage(listOf(ride("fixture")), null, false)
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
            fun fix(time: Long, lat: Double, lon: Double) = HistoryGpsFix(HistoryTick(time, time - 990, 1), lat, lon, 3.0, null)
            val fixes = listOf(fix(1000, 56.838, 60.600), fix(1500, 56.839, 60.603), fix(2000, 56.840, 60.603),
                fix(3000, 56.843, 60.610), fix(3500, 56.844, 60.612), fix(4000, 56.845, 60.611))
            return HistoryRoutePage(origin, HistoryPage(fixes, null, false),
                listOf(HistoryMarker(HistoryTick(2500, 1510, 1), HistoryMarkerKind.GPS_GAP)))
        }
    }

    private companion object { val origin = HistoryTick(1000, 10, 1) }
}
