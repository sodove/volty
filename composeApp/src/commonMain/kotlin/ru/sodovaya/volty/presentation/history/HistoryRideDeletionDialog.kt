package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.HistoryRide
import ru.sodovaya.volty.domain.history.HistoryRideDeletionFailure
import volty.composeapp.generated.resources.*

@Composable
fun HistoryRideDeletionDialog(
    state: HistoryRideDeletionState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: (List<HistoryRide>) -> Unit,
    onDismissResult: () -> Unit,
) {
    if (state.targets.isNotEmpty()) {
        val confirmation = state.confirmation
        val multiple = state.targets.size > 1
        AlertDialog(
            onDismissRequest = { if (!state.deleting) onDismiss() },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // The title scrolls with the impact text. A separate fixed
                    // title can consume the whole body at 200% font in landscape.
                    Text(
                        if (multiple) stringResource(Res.string.history_rides_delete_title, state.targets.size)
                        else stringResource(Res.string.history_ride_delete_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (state.preparing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(Res.string.history_ride_delete_preparing))
                        }
                    }

                    if (confirmation != null) {
                        Text(stringResource(if (multiple) Res.string.history_rides_delete_body else Res.string.history_ride_delete_body))
                        Text(
                            stringResource(
                                Res.string.history_ride_delete_counts,
                                confirmation.favoriteCount,
                                confirmation.mediaCount,
                            ),
                        )
                    }

                    if (state.deleting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(if (multiple) Res.string.history_rides_deleting else Res.string.history_ride_delete_deleting))
                    }

                    state.error?.let { failure ->
                        Text(
                            stringResource(failure.historyRideDeletionMessage()),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                if (state.error != null) {
                    TextButton(
                        onClick = { onRetry(state.targets) },
                        enabled = !state.busy,
                    ) {
                        Text(stringResource(Res.string.history_ride_delete_retry))
                    }
                } else {
                    TextButton(
                        onClick = onConfirm,
                        enabled = confirmation != null && !state.busy && state.error == null,
                    ) {
                        Text(stringResource(if (multiple) Res.string.history_rides_delete_confirm else Res.string.history_ride_delete_confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, enabled = !state.deleting) {
                    Text(stringResource(Res.string.history_ride_delete_cancel))
                }
            },
        )
        return
    }

    state.result?.let { result ->
        val multiple = result.sources.size > 1
        AlertDialog(
            onDismissRequest = onDismissResult,
            title = {
                Text(if (multiple) stringResource(Res.string.history_rides_deleted, result.sources.size)
                    else stringResource(Res.string.history_ride_delete_result_title))
            },
            text = {
                Text(
                    stringResource(
                        when {
                            result.cleanupPending && multiple -> Res.string.history_rides_cleanup_pending
                            result.cleanupPending -> Res.string.history_ride_delete_result_cleanup_pending
                            multiple -> Res.string.history_rides_delete_result_success
                            else -> Res.string.history_ride_delete_result_success
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissResult) {
                    Text(stringResource(Res.string.history_ride_delete_result_ok))
                }
            },
        )
    }
}

private fun HistoryRideDeletionFailure.historyRideDeletionMessage(): StringResource = when (this) {
    HistoryRideDeletionFailure.NOT_FOUND -> Res.string.history_ride_delete_failure_not_found
    HistoryRideDeletionFailure.ACTIVE_RECORDING -> Res.string.history_ride_delete_failure_active_recording
    HistoryRideDeletionFailure.SOURCE_CHANGED -> Res.string.history_ride_delete_failure_source_changed
    HistoryRideDeletionFailure.CONFLICT -> Res.string.history_ride_delete_failure_conflict
    HistoryRideDeletionFailure.STORAGE -> Res.string.history_ride_delete_failure_storage
}
