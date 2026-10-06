package ru.sodovaya.volty.presentation.nearby

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.nearby.community.CommunityFailure
import volty.composeapp.generated.resources.*

/** Permanent departure, separate from voice/trip disconnection and explicit server deletion. */
@Composable
internal fun OwnerDepartureDialog(state:ServerWorkspaceUiState,presenter:ServerWorkspacePresenter) {
    val result=state.ownerDeparture
    val candidates=result.items.orEmpty().filter {it.userId!=state.actorId}
    val enabled=state.actorId!=null && !state.commands.busy && !state.channelCreation.busy &&
        !state.invitations.busy && !state.acceptance.busy && !state.invitationManagement.revoking
    AlertDialog(onDismissRequest=presenter::dismissOwnerDeparture,
        title={Text(stringResource(Res.string.nearby_owner_departure_title))},
        text={LazyColumn(Modifier.fillMaxWidth().heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(Res.string.nearby_owner_departure_help))
                TextButton(onClick=presenter::refreshOwnerMembers,enabled=enabled && !result.loading) {Text(stringResource(Res.string.nearby_refresh))}
                if(result.loading || state.commands.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                result.error?.let {Text(stringResource(it.memberReadMessage()),color=MaterialTheme.colorScheme.error)}
                state.commands.error?.let {Text(stringResource(it.workspaceMessage()),color=MaterialTheme.colorScheme.error)}
                if(result.items!=null && candidates.isEmpty() && !result.hasMore && !result.loading && result.error==null)
                    Text(stringResource(Res.string.nearby_owner_departure_no_candidates))
            }
            items(candidates,key={it.userId}) {member ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(member.displayName,Modifier.weight(1f))
                        TextButton(onClick={presenter.chooseOwnerSuccessor(member.userId)},enabled=enabled && !result.loading && result.error==null) {
                            Text(stringResource(Res.string.nearby_owner_departure_choose))
                        }
                    }
                }
            }
            if(result.hasMore) item {TextButton(onClick=presenter::loadMoreOwnerMembers,enabled=enabled && !result.loading) {
                Text(stringResource(Res.string.nearby_servers_more))
            }}
        }},
        confirmButton={TextButton(onClick=presenter::dismissOwnerDeparture) {Text(stringResource(Res.string.nearby_close))}})
    val confirmation=result.confirmation
    val detail=state.browser.detail?.takeIf {it.community.id==confirmation?.community && it.ownerId==state.actorId}
    if(confirmation!=null && confirmation.community==state.ownerDepartureCommunity && detail!=null) AlertDialog(
        onDismissRequest=presenter::dismissOwnerConfirmation,
        title={Text(stringResource(Res.string.nearby_owner_departure_title))},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.nearby_owner_departure_confirm,detail.community.name,confirmation.successor.displayName))
            if(state.commands.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.commands.error?.let {Text(stringResource(it.workspaceMessage()),color=MaterialTheme.colorScheme.error)}
        }},
        confirmButton={TextButton(onClick=presenter::confirmOwnerDeparture,enabled=enabled && !result.loading && result.error==null) {
            Text(stringResource(Res.string.nearby_owner_departure_submit))
        }},
        dismissButton={TextButton(onClick=presenter::dismissOwnerConfirmation) {Text(stringResource(Res.string.nearby_cancel))}})
}

private fun CommunityFailure.memberReadMessage():StringResource=when(this) {
    CommunityFailure.UNAUTHORIZED -> Res.string.nearby_servers_auth_required
    CommunityFailure.ACCESS_DENIED -> Res.string.nearby_servers_access_denied
    else -> Res.string.nearby_servers_read_failed
}
