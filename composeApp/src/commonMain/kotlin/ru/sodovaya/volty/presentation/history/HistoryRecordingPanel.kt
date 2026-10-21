package ru.sodovaya.volty.presentation.history

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import volty.composeapp.generated.resources.*

/** Current recorder only; archived detail never borrows this live state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryRecordingPanel(state: HistoryRecordingUiState, onStart: (HistoryRecordingStatus) -> Unit,
    onStop: (HistoryRecordingStatus) -> Unit, onLocation: () -> Unit,
    modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    if (!state.present) return
    val observed = state.status
    Surface(modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column((if (reduceMotion) Modifier else Modifier.animateContentSize(tween(180)))
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (observed.ride != null) Res.string.history_recording_active
                else Res.string.history_recording_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(when {
                observed.storageFailure -> Res.string.history_recording_storage_error
                observed.manuallyStopped -> Res.string.history_recording_stopped
                observed.ride == null -> Res.string.history_recording_connect
                !observed.linkConnected -> Res.string.history_recording_link_lost
                else -> Res.string.history_recording_connected
            }), style = MaterialTheme.typography.bodyMedium,
                color = if (observed.storageFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (observed.ride != null || observed.route == HistoryRecordingRouteStatus.STOPPING) {
                Text(stringResource(when (observed.route) {
                    HistoryRecordingRouteStatus.STARTING -> Res.string.history_recording_gps_starting
                    HistoryRecordingRouteStatus.RECORDING -> Res.string.history_recording_gps_connected
                    HistoryRecordingRouteStatus.STOPPING -> Res.string.history_recording_gps_stopping
                    else -> Res.string.history_recording_gps_unavailable
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let { error -> Text(stringResource(when (error) {
                HistoryRecordingActionFailure.UNAVAILABLE -> Res.string.history_recording_unavailable
                HistoryRecordingActionFailure.NOT_APPLIED -> Res.string.history_recording_changed
                HistoryRecordingActionFailure.STORAGE -> Res.string.history_recording_storage_error
            }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (observed.canStart) Button({ onStart(observed) }, enabled = !state.busy && !observed.storageFailure) {
                    Text(stringResource(Res.string.history_recording_start))
                }
                if (observed.canStop) OutlinedButton({ onStop(observed) }, enabled = !state.busy) {
                    Text(stringResource(Res.string.history_recording_stop))
                }
                if (observed.ride != null && observed.route == HistoryRecordingRouteStatus.UNAVAILABLE)
                    TextButton(onLocation, enabled = !state.busy) { Text(stringResource(Res.string.history_recording_gps_action)) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
expect fun rememberHistoryRecordingLocationRequest(owner: HistoryComponent): () -> Unit
