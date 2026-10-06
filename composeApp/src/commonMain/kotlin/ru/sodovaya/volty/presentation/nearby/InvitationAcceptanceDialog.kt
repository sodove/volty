package ru.sodovaya.volty.presentation.nearby

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.nearby.community.InvitationCommandFailure
import volty.composeapp.generated.resources.*

@Composable
internal fun InvitationAcceptanceDialog(state:ServerWorkspaceUiState,presenter:ServerWorkspacePresenter) {
    val enabled=state.actorId!=null && !state.commands.busy && !state.channelCreation.busy && !state.invitations.busy && !state.acceptance.busy && !state.invitationManagement.revoking
    AlertDialog(onDismissRequest=presenter::dismissInvitationAcceptance,
        title={Text(stringResource(Res.string.nearby_invitations_join))},
        text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.nearby_invitations_join_help))
            OutlinedTextField(state.acceptanceForm.code.reveal(),presenter::setInvitationCode,enabled=enabled,singleLine=true,
                visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),
                label={Text(stringResource(Res.string.nearby_invitations_code))})
            if(state.acceptance.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(state.acceptance.invalidInput) Text(stringResource(Res.string.nearby_invitations_code_invalid),color=MaterialTheme.colorScheme.error)
            state.acceptance.error?.let {Text(stringResource(it.acceptanceMessage()),color=MaterialTheme.colorScheme.error)}
        }},
        confirmButton={TextButton(onClick=presenter::submitInvitationAcceptance,enabled=enabled && state.acceptanceForm.code.reveal().isNotBlank()) {
            Text(stringResource(Res.string.nearby_servers_join))
        }},
        dismissButton={TextButton(onClick=presenter::dismissInvitationAcceptance) {Text(stringResource(Res.string.nearby_cancel))}})
}

private fun InvitationCommandFailure.acceptanceMessage():StringResource=when(this) {
    InvitationCommandFailure.UNAUTHORIZED -> Res.string.nearby_servers_auth_required
    InvitationCommandFailure.ACCESS_DENIED -> Res.string.nearby_invitations_unavailable
    InvitationCommandFailure.INVALID_RESPONSE -> Res.string.nearby_servers_read_failed
    else -> Res.string.nearby_servers_action_failed
}
