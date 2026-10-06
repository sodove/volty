package ru.sodovaya.volty.presentation.nearby

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.nearby.community.InvitationCommandFailure
import volty.composeapp.generated.resources.*

/** Metadata never supplies a recoverable invitation code. */
@Composable
internal fun InvitationManagementDialog(state:ServerWorkspaceUiState,presenter:ServerWorkspacePresenter) {
    val result=state.invitationManagement
    val rows=result.items.orEmpty()
    val enabled=state.actorId!=null && !state.commands.busy && !state.channelCreation.busy &&
        !state.invitations.busy && !state.acceptance.busy && !result.revoking
    AlertDialog(onDismissRequest=presenter::dismissInvitationManagement,
        title={Text(stringResource(Res.string.nearby_invitations_manage))},
        text={LazyColumn(Modifier.fillMaxWidth().heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(Res.string.nearby_invitations_metadata_help),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=presenter::refreshInvitations,enabled=!state.commands.busy && !result.loading) {
                    Text(stringResource(Res.string.nearby_refresh))
                }
                if(result.loading || result.revoking) LinearProgressIndicator(Modifier.fillMaxWidth())
                result.error?.let {Text(stringResource(it.managementMessage()),color=MaterialTheme.colorScheme.error)}
                result.commandError?.let {Text(stringResource(it.managementMessage()),color=MaterialTheme.colorScheme.error)}
                if(result.items?.isEmpty()==true && !result.loading) Text(stringResource(Res.string.nearby_invitations_empty))
            }
            itemsIndexed(rows,key={_,row -> row.invitationId}) {index,row ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(Res.string.nearby_invitations_number,index+1),style=MaterialTheme.typography.titleSmall)
                        Text(stringResource(Res.string.nearby_invitations_created_at,invitationDate(row.createdAtMillis)))
                        Text(stringResource(Res.string.nearby_invitations_expires_at,invitationDate(row.expiresAtMillis)))
                        Text(stringResource(Res.string.nearby_invitations_usage_count,row.uses,row.maxUses))
                        if(row.revokedAtMillis!=null || row.invitationId in result.confirmedRevocations)
                            Text(stringResource(Res.string.nearby_invitations_revoked))
                        else TextButton(onClick={presenter.requestInvitationRevocation(row.invitationId)},enabled=enabled && !result.loading) {
                            Text(stringResource(Res.string.nearby_invitations_revoke),color=MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if(result.hasMore) item {TextButton(onClick=presenter::loadMoreInvitations,enabled=!result.loading && !state.commands.busy) {
                Text(stringResource(Res.string.nearby_servers_more))
            }}
        }},
        confirmButton={TextButton(onClick=presenter::dismissInvitationManagement) {Text(stringResource(Res.string.nearby_close))}})
    val confirmation=result.confirmation
    val index=rows.indexOfFirst {it.invitationId==confirmation?.invitation}
    if(confirmation!=null && confirmation.community==state.invitationManagementCommunity && index>=0) AlertDialog(
        onDismissRequest=presenter::dismissInvitationRevocation,
        title={Text(stringResource(Res.string.nearby_invitations_revoke))},
        text={Text(stringResource(Res.string.nearby_invitations_revoke_confirm,index+1))},
        confirmButton={TextButton(onClick=presenter::confirmInvitationRevocation,enabled=enabled && !result.loading) {
            Text(stringResource(Res.string.nearby_invitations_revoke),color=MaterialTheme.colorScheme.error)
        }},
        dismissButton={TextButton(onClick=presenter::dismissInvitationRevocation) {Text(stringResource(Res.string.nearby_cancel))}})
}

private fun invitationDate(epochMillis:Long):String=runCatching {
    val date=Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(value:Int)=value.toString().padStart(2,'0')
    "${two(date.day)}.${two(date.monthNumber)}.${date.year}, ${two(date.hour)}:${two(date.minute)}"
}.getOrDefault("—")

private fun InvitationCommandFailure.managementMessage():StringResource=when(this) {
    InvitationCommandFailure.UNAUTHORIZED -> Res.string.nearby_servers_auth_required
    InvitationCommandFailure.ACCESS_DENIED -> Res.string.nearby_servers_access_denied
    InvitationCommandFailure.INVALID_RESPONSE -> Res.string.nearby_servers_read_failed
    else -> Res.string.nearby_servers_action_failed
}
