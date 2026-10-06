package ru.sodovaya.volty.presentation.nearby

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.nearby.community.InvitationDraftFailure
import volty.composeapp.generated.resources.*

/** Disclosure is explicit and rechecked by the presenter at the moment of copying. */
@Composable
internal fun InvitationCreationDialog(state:ServerWorkspaceUiState,presenter:ServerWorkspacePresenter) {
    val clipboard=LocalClipboardManager.current
    val enabled=state.actorId!=null && !state.commands.busy && !state.channelCreation.busy && !state.invitations.busy && !state.acceptance.busy && !state.invitationManagement.revoking
    val result=state.invitations.takeIf {it.community==state.invitationForm.community}
    val created=result?.created!=null
    AlertDialog(onDismissRequest=presenter::dismissInvitationCreation,
        title={Text(stringResource(Res.string.nearby_invitations_create))},
        text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(created) Text(stringResource(Res.string.nearby_invitations_created))
            else {
                Text(stringResource(Res.string.nearby_invitations_lifetime),style=MaterialTheme.typography.titleSmall)
                for((ttl,label) in listOf(900_000L to Res.string.nearby_invitations_15_minutes,
                    86_400_000L to Res.string.nearby_invitations_day,604_800_000L to Res.string.nearby_invitations_week)) {
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(state.invitationForm.ttlMillis==ttl,{presenter.setInvitationTtl(ttl)},enabled=enabled)
                        Text(stringResource(label))
                    }
                }
                OutlinedTextField(state.invitationForm.maxUses,presenter::setInvitationUses,enabled=enabled,singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),
                    label={Text(stringResource(Res.string.nearby_invitations_uses))},
                    supportingText={Text(stringResource(Res.string.nearby_invitations_uses_help))})
            }
            if(state.invitations.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(result?.invalidInput==true) Text(stringResource(Res.string.nearby_invitations_invalid),color=MaterialTheme.colorScheme.error)
            result?.error?.let {Text(stringResource(it.invitationMessage()),color=MaterialTheme.colorScheme.error)}
        }},
        confirmButton={TextButton(onClick={
            if(created) presenter.copyInvitation {clipboard.setText(AnnotatedString(it))}
            else presenter.submitInvitationCreation()
        },enabled=enabled && (created || state.invitationForm.maxUses.isNotBlank())) {
            Text(stringResource(if(created) Res.string.nearby_invitations_copy else Res.string.nearby_create))
        }},
        dismissButton={TextButton(onClick=presenter::dismissInvitationCreation) {Text(stringResource(Res.string.nearby_cancel))}})
}

internal fun InvitationDraftFailure.invitationMessage():StringResource=when(this) {
    InvitationDraftFailure.UNAUTHORIZED -> Res.string.nearby_servers_auth_required
    InvitationDraftFailure.ACCESS_DENIED -> Res.string.nearby_servers_access_denied
    InvitationDraftFailure.CONFLICT,InvitationDraftFailure.PAYLOAD_CONFLICT -> Res.string.nearby_invitations_conflict
    InvitationDraftFailure.INVALID_RESPONSE -> Res.string.nearby_servers_read_failed
    else -> Res.string.nearby_servers_action_failed
}
