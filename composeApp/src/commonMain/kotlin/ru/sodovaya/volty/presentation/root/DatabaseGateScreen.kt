package ru.sodovaya.volty.presentation.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.data.db.DatabaseGateAction
import ru.sodovaya.volty.diagnostics.DatabaseShareOutcome
import ru.sodovaya.volty.diagnostics.SnapshotRefusal
import ru.sodovaya.volty.presentation.common.VoltyTheme
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.db_gate_cancel
import volty.composeapp.generated.resources.db_gate_clear_history
import volty.composeapp.generated.resources.db_gate_clear_history_body
import volty.composeapp.generated.resources.db_gate_clear_history_confirm
import volty.composeapp.generated.resources.db_gate_clear_history_title
import volty.composeapp.generated.resources.db_gate_error_body
import volty.composeapp.generated.resources.db_gate_error_title
import volty.composeapp.generated.resources.db_gate_no_space_body
import volty.composeapp.generated.resources.db_gate_no_space_title
import volty.composeapp.generated.resources.db_gate_recovery_failed
import volty.composeapp.generated.resources.db_gate_retry
import volty.composeapp.generated.resources.db_gate_send_logs
import volty.composeapp.generated.resources.db_gate_share_database
import volty.composeapp.generated.resources.db_gate_start_empty
import volty.composeapp.generated.resources.db_gate_start_empty_body
import volty.composeapp.generated.resources.db_gate_start_empty_confirm
import volty.composeapp.generated.resources.db_gate_start_empty_title
import volty.composeapp.generated.resources.db_gate_updating_body
import volty.composeapp.generated.resources.db_gate_updating_title
import volty.composeapp.generated.resources.settings_share_database_app_busy
import volty.composeapp.generated.resources.settings_share_database_busy
import volty.composeapp.generated.resources.settings_share_database_failed
import volty.composeapp.generated.resources.settings_share_database_inconsistent
import volty.composeapp.generated.resources.settings_share_database_no_space
import volty.composeapp.generated.resources.settings_share_database_recording
import volty.composeapp.generated.resources.settings_share_database_warning

/**
 * Full-screen stand-in for the app while the database is opening, converting or unable to. It is
 * not a dialog and has no dismiss action: the app behind it does not exist yet. Rendering is judged
 * on a device (Compose is not unit-tested here); every decision is in [databaseGateScreenModel].
 */
@Composable
fun DatabaseGateScreen(
    model: DatabaseGateScreenModel,
    onRetry: () -> Unit,
    onSendLogs: () -> Unit,
    onShareDatabase: () -> Unit,
    onGateAction: (DatabaseGateAction) -> Unit = {},
) {
    VoltyTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (model) {
                DatabaseGateScreenModel.Hidden, DatabaseGateScreenModel.Blank -> Unit
                DatabaseGateScreenModel.Updating -> GateColumn {
                    Text(stringResource(Res.string.db_gate_updating_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(Res.string.db_gate_updating_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                is DatabaseGateScreenModel.NoSpace -> GateColumn {
                    Text(stringResource(Res.string.db_gate_no_space_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(stringResource(Res.string.db_gate_no_space_body, model.freeUpMegabytes), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.db_gate_retry)) }
                    ShareButtons(model.shareBusy, onSendLogs, onShareDatabase)
                    shareStatus(model.shareBusy, model.shareOutcome)?.let { Text(it, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) }
                    GateActions(model.actions, model.recoveryFailed, onGateAction)
                }
                is DatabaseGateScreenModel.Error -> GateColumn {
                    Text(stringResource(Res.string.db_gate_error_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(stringResource(Res.string.db_gate_error_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.db_gate_retry)) }
                    ShareButtons(model.shareBusy, onSendLogs, onShareDatabase)
                    shareStatus(model.shareBusy, model.shareOutcome)?.let { Text(it, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) }
                    GateActions(model.actions, model.recoveryFailed, onGateAction)
                }
            }
        }
    }
}

@Composable
private fun shareStatus(shareBusy: Boolean, shareOutcome: DatabaseShareOutcome?): String? = when {
    shareBusy -> stringResource(Res.string.settings_share_database_busy)
    else -> when (val outcome = shareOutcome) {
        null, DatabaseShareOutcome.Shared -> null
        DatabaseShareOutcome.Failed -> stringResource(Res.string.settings_share_database_failed)
        is DatabaseShareOutcome.Refused -> when (outcome.reason) {
            SnapshotRefusal.RECORDING -> stringResource(Res.string.settings_share_database_recording)
            SnapshotRefusal.NO_SPACE -> stringResource(Res.string.settings_share_database_no_space)
            SnapshotRefusal.BUSY -> stringResource(Res.string.settings_share_database_app_busy)
            SnapshotRefusal.INCONSISTENT -> stringResource(Res.string.settings_share_database_inconsistent)
        }
    }
}

@Composable
private fun ShareButtons(shareBusy: Boolean, onSendLogs: () -> Unit, onShareDatabase: () -> Unit) {
    OutlinedButton(onClick = onSendLogs, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.db_gate_send_logs)) }
    OutlinedButton(onClick = onShareDatabase, enabled = !shareBusy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.db_gate_share_database))
    }
    Text(stringResource(Res.string.settings_share_database_warning), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
}

/**
 * The destructive way out, below the share buttons, in the error colour, and only through a dialog that says what is
 * lost and recommends sharing the database first. Which action is offered is [DatabaseGateScreenModel]'s decision.
 */
@Composable
private fun GateActions(actions: Set<DatabaseGateAction>, recoveryFailed: Boolean, onGateAction: (DatabaseGateAction) -> Unit) {
    var confirming by remember { mutableStateOf<DatabaseGateAction?>(null) }
    val danger = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
    for (action in DatabaseGateAction.entries.filter { it in actions }) {
        TextButton(onClick = { confirming = action }, colors = danger, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(when (action) {
                DatabaseGateAction.CLEAR_RIDE_HISTORY -> Res.string.db_gate_clear_history
                DatabaseGateAction.START_WITH_EMPTY_DATABASE -> Res.string.db_gate_start_empty
            }))
        }
    }
    if (recoveryFailed) {
        Text(stringResource(Res.string.db_gate_recovery_failed), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
    confirming?.let { action ->
        val clear = action == DatabaseGateAction.CLEAR_RIDE_HISTORY
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(if (clear) Res.string.db_gate_clear_history_title else Res.string.db_gate_start_empty_title)) },
            text = { Text(stringResource(if (clear) Res.string.db_gate_clear_history_body else Res.string.db_gate_start_empty_body)) },
            confirmButton = {
                TextButton(onClick = { confirming = null; onGateAction(action) }, colors = danger) {
                    Text(stringResource(if (clear) Res.string.db_gate_clear_history_confirm else Res.string.db_gate_start_empty_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(Res.string.db_gate_cancel)) } },
        )
    }
}

@Composable
private fun GateColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
