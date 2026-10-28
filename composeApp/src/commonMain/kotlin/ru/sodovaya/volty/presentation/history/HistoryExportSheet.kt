package ru.sodovaya.volty.presentation.history

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryExportSheet(state: HistoryExportState, onChoose: (HistoryExportFormat) -> Unit, onDismiss: () -> Unit) {
    if (state.source == null || state.range == null) return
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.history_export_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.history_export_window,
                historyRideDate(state.range.startEpochMillis, true), historyRideDate(state.range.endEpochMillis, true)))
            Text(stringResource(Res.string.history_export_privacy), style = MaterialTheme.typography.bodyMedium)
            for (format in HistoryExportFormat.entries) OutlinedButton(onClick = { onChoose(format) },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(when (format) {
                    HistoryExportFormat.LOGUI -> Res.string.history_export_logui
                    HistoryExportFormat.GPX -> Res.string.history_export_gpx
                }))
            }
            Text(stringResource(Res.string.history_export_logui_hold), style = MaterialTheme.typography.bodySmall)
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(Res.string.history_export_working))
            }
            state.error?.let { error -> Text(stringResource(when (error) {
                HistoryExportFailure.NO_ROUTE -> Res.string.history_export_no_route
                HistoryExportFailure.ACTIVE_RECORDING -> Res.string.history_export_active
                HistoryExportFailure.NOT_FOUND, HistoryExportFailure.SOURCE_CHANGED -> Res.string.history_export_changed
                HistoryExportFailure.INVALID_DATA, HistoryExportFailure.UNSUPPORTED_FORMAT -> Res.string.history_export_invalid
                HistoryExportFailure.LIMIT -> Res.string.history_export_limit
                HistoryExportFailure.STORAGE -> Res.string.history_export_storage
            }), color = MaterialTheme.colorScheme.error) }
            state.ready?.let { ready ->
                Text(stringResource(Res.string.history_export_ready, ((ready.bytes + 1023) / 1024).toString()))
                HistoryExportActions(ready)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.busy) Res.string.history_favorite_cancel else Res.string.history_export_close))
            }
        }
    }
}

@Composable
expect fun HistoryExportActions(artifact: HistoryExportResult.Success)
