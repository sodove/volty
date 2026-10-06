package ru.sodovaya.volty.presentation.nearby

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.nearby.community.*
import volty.composeapp.generated.resources.*

@Composable
internal fun ServerCreationForm(state: ServerWorkspaceUiState,presenter: ServerWorkspacePresenter) {
    val enabled=!state.commands.busy && state.actorId!=null
    Column(Modifier.widthIn(max=520.dp).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(state.draft.name,presenter::setName,enabled=enabled,singleLine=true,modifier=Modifier.fillMaxWidth(),
            label={Text(stringResource(Res.string.nearby_servers_name))})
        OutlinedTextField(state.draft.description,presenter::setDescription,enabled=enabled,modifier=Modifier.fillMaxWidth(),
            label={Text(stringResource(Res.string.nearby_servers_description))},minLines=2,maxLines=5)
        Text(stringResource(Res.string.nearby_servers_visibility),style=MaterialTheme.typography.titleMedium)
        for(visibility in CommunityVisibility.entries) Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected=state.draft.visibility==visibility,onClick={presenter.setVisibility(visibility)},enabled=enabled)
            Text(stringResource(if(visibility==CommunityVisibility.PUBLIC) Res.string.nearby_servers_public else Res.string.nearby_servers_private))
        }
        Text(stringResource(if(state.draft.visibility==CommunityVisibility.PUBLIC) Res.string.nearby_servers_public_help else Res.string.nearby_servers_private_help))
        Button(onClick=presenter::submitCreate,enabled=enabled && state.draft.name.isNotBlank(),modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.nearby_servers_create))
        }
    }
}

internal fun CommunityWorkspaceError.workspaceMessage(): StringResource = when(this) {
    CommunityWorkspaceError.InvalidInput -> Res.string.nearby_servers_invalid_input
    is CommunityWorkspaceError.Community -> when(reason) {
        CommunityFailure.UNAUTHORIZED -> Res.string.nearby_servers_auth_required
        CommunityFailure.ACCESS_DENIED -> Res.string.nearby_servers_access_denied
        else -> Res.string.nearby_servers_action_failed
    }
    is CommunityWorkspaceError.Removal -> when(reason) {
        CommunityRemovalFailure.UNAUTHORIZED -> Res.string.nearby_servers_auth_required
        CommunityRemovalFailure.ACCESS_DENIED -> Res.string.nearby_servers_access_denied
        CommunityRemovalFailure.SUCCESSOR_REQUIRED -> Res.string.nearby_servers_successor_required
        CommunityRemovalFailure.DELETE_REQUIRED -> Res.string.nearby_servers_delete_required
        else -> Res.string.nearby_servers_action_failed
    }
}

@Composable
internal fun ChannelCreationDialog(state: ServerWorkspaceUiState,presenter: ServerWorkspacePresenter) {
    val enabled=!state.commands.busy && !state.channelCreation.busy && state.actorId!=null
    AlertDialog(onDismissRequest=presenter::dismissChannelCreation,
        title={Text(stringResource(Res.string.nearby_channels_create))},
        text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(state.channelForm.name,presenter::setChannelName,enabled=enabled,singleLine=true,
                modifier=Modifier.fillMaxWidth(),label={Text(stringResource(Res.string.nearby_channels_name))})
            if(state.channelCreation.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(state.channelCreation.community==state.channelForm.community) {
                if(state.channelCreation.invalidInput) Text(stringResource(Res.string.nearby_channels_invalid_input),color=MaterialTheme.colorScheme.error)
                state.channelCreation.error?.let {Text(stringResource(it.channelMessage()),color=MaterialTheme.colorScheme.error)}
            }
        }},
        confirmButton={TextButton(onClick=presenter::submitChannelCreation,enabled=enabled && state.channelForm.name.isNotBlank()) {
            Text(stringResource(Res.string.nearby_create))
        }},
        dismissButton={TextButton(onClick=presenter::dismissChannelCreation) {Text(stringResource(Res.string.nearby_cancel))}})
}

private fun ChannelCommandFailure.channelMessage(): StringResource = when(this) {
    ChannelCommandFailure.UNAUTHORIZED -> Res.string.nearby_servers_auth_required
    ChannelCommandFailure.ACCESS_DENIED -> Res.string.nearby_servers_access_denied
    ChannelCommandFailure.CONFLICT -> Res.string.nearby_channels_conflict
    ChannelCommandFailure.LIMIT_REACHED -> Res.string.nearby_channels_limit
    ChannelCommandFailure.UNAVAILABLE -> Res.string.nearby_servers_action_failed
    ChannelCommandFailure.INVALID_RESPONSE -> Res.string.nearby_servers_read_failed
}
