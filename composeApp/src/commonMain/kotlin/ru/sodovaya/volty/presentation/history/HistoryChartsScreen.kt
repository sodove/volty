package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.HistorySeriesKey
import volty.composeapp.generated.resources.*

/** Fullscreen uses the same owner, selected range and cursor as the map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryChartsScreen(owner: HistoryComponent, state: HistoryUiState,
    cursorMaxAgeMillis: (HistorySeriesKey) -> Long, modifier: Modifier = Modifier, applySystemInsets: Boolean = true) {
    var drawer by remember(state.selectedRideId) { mutableStateOf(false) }
    Scaffold(modifier, bottomBar = { HistoryTimelineControls(state.detail.copy(range = state.windowPreview ?: state.detail.range),
        owner::selectRange, owner::selectTime, applyNavigationInsets = applySystemInsets) },
        topBar = { TopAppBar(title = { Text(stringResource(Res.string.history_charts_title)) },
        windowInsets = if (applySystemInsets) TopAppBarDefaults.windowInsets else WindowInsets(0),
        navigationIcon = { IconButton(onClick = owner::back) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.history_charts_back))
        } }, actions = { IconButton(onClick = { drawer = true }) {
            Icon(Icons.Default.Add, stringResource(Res.string.history_metrics_title))
        } }) }) { padding ->
        HistoryChartPanel(state.detail, owner::selectTime, cursorMaxAgeMillis, Modifier.fillMaxSize().padding(padding),
            state.windowPreview, owner)
    }
    if (drawer) HistoryMetricDrawer(state.detail, owner::loadSourceMetrics, owner::addMetric, owner::removeMetric,
        onDismiss = { drawer = false }, presets = state.presets, onLoadPresets = owner::loadPresets,
        onSavePreset = owner::savePreset, onApplyPreset = owner::applyPreset, onDeletePreset = owner::deletePreset)
}
