package ru.sodovaya.volty.presentation.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.diagnostics.DatabaseShareOutcome
import ru.sodovaya.volty.diagnostics.SnapshotRefusal
import ru.sodovaya.volty.presentation.common.VoltyTheme
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.db_gate_error_body
import volty.composeapp.generated.resources.db_gate_error_title
import volty.composeapp.generated.resources.db_gate_no_space_body
import volty.composeapp.generated.resources.db_gate_no_space_title
import volty.composeapp.generated.resources.db_gate_retry
import volty.composeapp.generated.resources.db_gate_send_logs
import volty.composeapp.generated.resources.db_gate_share_database
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
                }
                is DatabaseGateScreenModel.Error -> GateColumn {
                    Text(stringResource(Res.string.db_gate_error_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(stringResource(Res.string.db_gate_error_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.db_gate_retry)) }
                    OutlinedButton(onClick = onSendLogs, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.db_gate_send_logs)) }
                    OutlinedButton(onClick = onShareDatabase, enabled = !model.shareBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.db_gate_share_database))
                    }
                    Text(stringResource(Res.string.settings_share_database_warning), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    shareStatus(model)?.let { Text(it, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

@Composable
private fun shareStatus(model: DatabaseGateScreenModel.Error): String? = when {
    model.shareBusy -> stringResource(Res.string.settings_share_database_busy)
    else -> when (val outcome = model.shareOutcome) {
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
private fun GateColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
