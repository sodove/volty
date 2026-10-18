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
import androidx.lifecycle.lifecycleScope
import com.arkivanov.decompose.defaultComponentContext
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.presentation.common.VoltyTheme
import ru.sodovaya.volty.presentation.history.*
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
        override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?) =
            HistoryRoutePage(origin, HistoryPage(emptyList(), null, false), emptyList())
    }

    private companion object { val origin = HistoryTick(1000, 10, 1) }
}
