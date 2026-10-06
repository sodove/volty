package ru.sodovaya.volty.presentation.nearby

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.nearby.community.*
import volty.composeapp.generated.resources.*

/** Rendering only. The root supplies a presenter whose connect callback owns voice admission. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerWorkspaceScreen(presenter: ServerWorkspacePresenter, modifier: Modifier = Modifier) {
    val state by presenter.state.collectAsState()
    val enabled = !state.commands.busy && state.actorId != null
    val mutationEnabled = enabled && !state.channelCreation.busy && !state.invitations.busy && !state.acceptance.busy && !state.invitationManagement.revoking
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = {
            Text(when(state.section) {
                ServerWorkspaceSection.MY_SERVERS -> stringResource(Res.string.nearby_servers_mine)
                ServerWorkspaceSection.CATALOG -> stringResource(Res.string.nearby_servers_catalog)
                ServerWorkspaceSection.CREATE -> stringResource(Res.string.nearby_servers_create)
                ServerWorkspaceSection.SERVER -> state.browser.detail?.community?.name ?: stringResource(Res.string.nearby_server_title)
            },maxLines=1,overflow=TextOverflow.Ellipsis)
        },navigationIcon = {
            if(state.section != ServerWorkspaceSection.MY_SERVERS) TextButton(onClick=presenter::back,enabled=enabled) {
                Text(stringResource(Res.string.nearby_back))
            }
        })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick=presenter::openMine,enabled=enabled) {Text(stringResource(Res.string.nearby_servers_mine))}
                TextButton(onClick=presenter::openCatalog,enabled=enabled) {Text(stringResource(Res.string.nearby_servers_find))}
                TextButton(onClick=presenter::openInvitationAcceptance,enabled=mutationEnabled) {Text(stringResource(Res.string.nearby_invitations_join))}
                if(state.section != ServerWorkspaceSection.CREATE) TextButton(onClick=presenter::openCreate,enabled=mutationEnabled) {
                    Text(stringResource(Res.string.nearby_create))
                }
            }
            state.commands.error?.let { Text(stringResource(it.workspaceMessage()),color=MaterialTheme.colorScheme.error) }
            if(state.commands.cleanupPending) Text(stringResource(Res.string.nearby_servers_cleanup_pending))
            if(state.commands.busy || state.channelCreation.busy || state.invitations.busy || state.acceptance.busy || state.invitationManagement.revoking) LinearProgressIndicator(Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.section == ServerWorkspaceSection.CREATE -> ServerCreationForm(state,presenter)
                    state.section == ServerWorkspaceSection.SERVER && maxWidth>=720.dp -> Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                        ServerListPane(state,presenter,state.origin==ServerWorkspaceSection.CATALOG,Modifier.width(260.dp))
                        ServerDetailsPane(state,presenter,Modifier.weight(1f))
                    }
                    state.section == ServerWorkspaceSection.SERVER -> ServerDetailsPane(state,presenter,Modifier.fillMaxWidth())
                    else -> ServerListPane(state,presenter,state.section==ServerWorkspaceSection.CATALOG,Modifier.fillMaxWidth())
                }
            }
        }
    }
    val confirmation=state.commands.confirmation
    val target=state.browser.detail?.takeIf {it.community.id==confirmation?.community}
    if(confirmation!=null && target!=null) AlertDialog(
        onDismissRequest=presenter::dismissRemoval,
        title={Text(stringResource(if(confirmation.delete) Res.string.nearby_servers_delete else Res.string.nearby_servers_leave))},
        text={Text(stringResource(if(confirmation.delete) Res.string.nearby_servers_delete_confirm else Res.string.nearby_servers_leave_confirm,target.community.name))},
        confirmButton={TextButton(onClick=presenter::confirmRemoval,enabled=mutationEnabled) {
            Text(stringResource(if(confirmation.delete) Res.string.nearby_servers_delete else Res.string.nearby_servers_leave))
        }},
        dismissButton={TextButton(onClick=presenter::dismissRemoval,enabled=enabled) {Text(stringResource(Res.string.nearby_cancel))}},
    )
    if(state.channelForm.visible && state.channelForm.community==state.browser.selectedCommunity)
        ChannelCreationDialog(state,presenter)
    if(state.invitationForm.visible && state.invitationForm.community==state.browser.selectedCommunity)
        InvitationCreationDialog(state,presenter)
    if(state.acceptanceForm.visible && state.acceptanceForm.originCommunity==state.browser.selectedCommunity)
        InvitationAcceptanceDialog(state,presenter)
    if(state.invitationManagementCommunity!=null && state.invitationManagementCommunity==state.browser.selectedCommunity)
        InvitationManagementDialog(state,presenter)
}

@Composable
private fun ServerListPane(state: ServerWorkspaceUiState,presenter: ServerWorkspacePresenter,catalog: Boolean,modifier: Modifier) {
    val page=if(catalog) state.browser.catalog else state.browser.memberships
    val enabled=!state.commands.busy && state.actorId!=null
    LazyColumn(modifier.fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(catalog) item {
            OutlinedTextField(value=state.query,onValueChange=presenter::setQuery,enabled=enabled,singleLine=true,
                label={Text(stringResource(Res.string.nearby_servers_search))},modifier=Modifier.fillMaxWidth())
        }
        item {TextButton(onClick={presenter.refreshList(catalog)},enabled=enabled && !page.loading) {Text(stringResource(Res.string.nearby_refresh))}}
        if(page.error!=null) item {Text(stringResource(Res.string.nearby_servers_read_failed),color=MaterialTheme.colorScheme.error)}
        if(page.loading) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(page.items?.isEmpty()==true && !page.loading) item {
            Text(stringResource(if(catalog) Res.string.nearby_servers_catalog_empty else Res.string.nearby_servers_empty))
        }
        items(page.items.orEmpty(),key={it.id}) {server ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(server.name,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(stringResource(if(server.visibility==CommunityVisibility.PUBLIC) Res.string.nearby_servers_public else Res.string.nearby_servers_private),
                        style=MaterialTheme.typography.labelMedium)
                    if(server.description.isNotBlank()) Text(server.description,maxLines=3,overflow=TextOverflow.Ellipsis)
                    TextButton(onClick={if(catalog) presenter.joinPublic(server.id) else presenter.openServer(server.id)},enabled=enabled && (!catalog || (!state.channelCreation.busy && !state.invitations.busy && !state.acceptance.busy && !state.invitationManagement.revoking))) {
                        Text(stringResource(if(catalog) Res.string.nearby_servers_join else Res.string.nearby_servers_open))
                    }
                }
            }
        }
        if(page.hasMore) item {TextButton(onClick=presenter::loadMore,enabled=enabled && !page.loading) {Text(stringResource(Res.string.nearby_servers_more))}}
    }
}

@Composable
private fun ServerDetailsPane(state: ServerWorkspaceUiState,presenter: ServerWorkspacePresenter,modifier: Modifier) {
    val browser=state.browser
    val enabled=!state.commands.busy && !state.channelCreation.busy && !state.invitations.busy && !state.acceptance.busy && !state.invitationManagement.revoking && state.actorId!=null
    LazyColumn(modifier.fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(browser.detailLoading) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(browser.detailError!=null) item {
            Text(stringResource(Res.string.nearby_servers_read_failed),color=MaterialTheme.colorScheme.error)
            TextButton(onClick=presenter::refresh,enabled=enabled) {Text(stringResource(Res.string.nearby_refresh))}
        }
        val detail=browser.detail?.takeIf {it.community.id==browser.selectedCommunity}
        if(detail!=null) {
            item {
                Text(detail.community.name,style=MaterialTheme.typography.headlineSmall)
                if(detail.community.description.isNotBlank()) Text(detail.community.description)
                Text(stringResource(Res.string.nearby_servers_channels),style=MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.nearby_servers_voice_only),style=MaterialTheme.typography.bodySmall)
            }
            if(detail.channels.isEmpty()) item {Text(stringResource(Res.string.nearby_servers_no_channels))}
            items(detail.channels,key={it.id}) {channel ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Text(channel.name,Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
                        OutlinedButton(onClick={presenter.connect(channel.id)},enabled=enabled && !browser.detailLoading) {
                            Text(stringResource(Res.string.nearby_servers_connect))
                        }
                    }
                }
            }
            item {
                if(detail.ownerId==state.actorId) TextButton(onClick=presenter::openInvitationManagement,
                    enabled=!state.commands.busy && state.actorId!=null && !browser.detailLoading && browser.detailError==null) {
                    Text(stringResource(Res.string.nearby_invitations_manage))
                }
                if(detail.ownerId==state.actorId) TextButton(onClick=presenter::openInvitationCreation,enabled=enabled && !browser.detailLoading && browser.detailError==null) {
                    Text(stringResource(Res.string.nearby_invitations_create))
                }
                if(detail.ownerId==state.actorId) TextButton(onClick=presenter::openChannelCreation,enabled=enabled && !browser.detailLoading && browser.detailError==null) {
                    Text(stringResource(Res.string.nearby_channels_create))
                }
                TextButton(onClick=presenter::requestLeave,enabled=enabled) {Text(stringResource(Res.string.nearby_servers_leave))}
                if(detail.ownerId==state.actorId) TextButton(onClick=presenter::requestDelete,enabled=enabled) {
                    Text(stringResource(Res.string.nearby_servers_delete),color=MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
