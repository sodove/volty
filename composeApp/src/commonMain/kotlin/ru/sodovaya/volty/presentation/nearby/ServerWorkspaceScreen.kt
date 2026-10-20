package ru.sodovaya.volty.presentation.nearby

import androidx.compose.foundation.layout.*
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
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
    val enabled = !state.commands.busy && !state.channelDeletion.busy && state.actorId != null
    val mutationEnabled = enabled && !state.channelCreation.busy && !state.invitations.busy && !state.acceptance.busy && !state.invitationManagement.revoking
        Column(modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(state.section==ServerWorkspaceSection.MY_SERVERS) {
                Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedButton(onClick=presenter::openCatalog,enabled=enabled,modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.Search,contentDescription=null,modifier=Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.nearby_servers_find))
                    }
                    FilledTonalIconButton(onClick=presenter::openCreate,enabled=mutationEnabled) {
                        Icon(Icons.Default.Add,contentDescription=stringResource(Res.string.nearby_create))
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={if(state.section==ServerWorkspaceSection.CATALOG) presenter.openMine() else presenter.back()},enabled=enabled) {
                        Text(stringResource(Res.string.nearby_back))
                    }
                    if(state.section!=ServerWorkspaceSection.SERVER) Text(stringResource(
                        if(state.section==ServerWorkspaceSection.CREATE) Res.string.nearby_servers_create else Res.string.nearby_servers_catalog),
                        style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            state.commands.error?.let { Text(stringResource(it.workspaceMessage()),color=MaterialTheme.colorScheme.error) }
            if(state.commands.cleanupPending) Text(stringResource(Res.string.nearby_servers_cleanup_pending))
            if(state.commands.busy || state.channelDeletion.busy || state.channelCreation.busy || state.invitations.busy || state.acceptance.busy || state.invitationManagement.revoking) LinearProgressIndicator(Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                Crossfade(state.section, label="serverSection") { section ->
                when {
                    section == ServerWorkspaceSection.CREATE -> ServerCreationForm(state,presenter)
                    section == ServerWorkspaceSection.SERVER && maxWidth>=720.dp -> Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                        ServerListPane(state,presenter,state.origin==ServerWorkspaceSection.CATALOG,Modifier.width(260.dp))
                        ServerDetailsPane(state,presenter,Modifier.weight(1f))
                    }
                    section == ServerWorkspaceSection.SERVER -> ServerDetailsPane(state,presenter,Modifier.fillMaxWidth())
                    else -> ServerListPane(state,presenter,section==ServerWorkspaceSection.CATALOG,Modifier.fillMaxWidth())
                }
                }
            }
    }
    val channelRemoval=state.channelRemoval
    if(channelRemoval!=null) AlertDialog(
        onDismissRequest=presenter::dismissChannelDeletion,
        title={Text(stringResource(Res.string.nearby_channels_delete_title,channelRemoval.name))},
        text={Text(stringResource(Res.string.nearby_channels_delete_warning))},
        confirmButton={TextButton(onClick=presenter::confirmChannelDeletion,enabled=mutationEnabled) {
            Text(stringResource(Res.string.nearby_channels_delete),color=MaterialTheme.colorScheme.error)
        }},
        dismissButton={TextButton(onClick=presenter::dismissChannelDeletion,enabled=!state.channelDeletion.busy) {
            Text(stringResource(Res.string.nearby_cancel))
        }},
    )
    val confirmation=state.commands.confirmation
    val target=state.browser.detail?.takeIf {it.community.id==confirmation?.community}
    if(confirmation!=null && target!=null && state.ownerDepartureCommunity==null) AlertDialog(
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
    if(state.ownerDepartureCommunity!=null && state.ownerDepartureCommunity==state.browser.selectedCommunity)
        OwnerDepartureDialog(state,presenter)
}

@Composable
private fun ServerListPane(state: ServerWorkspaceUiState,presenter: ServerWorkspacePresenter,catalog: Boolean,modifier: Modifier) {
    val page=if(catalog) state.browser.catalog else state.browser.memberships
    val enabled=!state.commands.busy && !state.channelDeletion.busy && state.actorId!=null
    LazyColumn(modifier.fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(4.dp),contentPadding=PaddingValues(bottom=12.dp)) {
        if(catalog) item {
            OutlinedTextField(value=state.query,onValueChange=presenter::setQuery,enabled=enabled,singleLine=true,
                label={Text(stringResource(Res.string.nearby_servers_search))},modifier=Modifier.fillMaxWidth())
        }
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(if(catalog) Res.string.nearby_servers_catalog else Res.string.nearby_servers_mine),
                Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(!catalog) TextButton(onClick=presenter::openInvitationAcceptance,
                enabled=enabled && !state.channelCreation.busy && !state.invitations.busy && !state.acceptance.busy && !state.invitationManagement.revoking) {
                Text(stringResource(Res.string.nearby_invitations_join),style=MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick={presenter.refreshList(catalog)},enabled=enabled && !page.loading) {
                Icon(Icons.Default.Refresh,contentDescription=stringResource(Res.string.nearby_refresh))
            }
        }}
        if(page.error!=null) item {Text(stringResource(Res.string.nearby_servers_read_failed),color=MaterialTheme.colorScheme.error)}
        if(page.loading) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(page.items?.isEmpty()==true && !page.loading) item {
            Text(stringResource(if(catalog) Res.string.nearby_servers_catalog_empty else Res.string.nearby_servers_empty))
        }
        items(page.items.orEmpty(),key={it.id}) {server ->
            Surface(onClick={if(catalog) presenter.joinPublic(server.id) else presenter.openServer(server.id)},
                enabled=enabled && (!catalog || (!state.channelCreation.busy && !state.invitations.busy && !state.acceptance.busy && !state.invitationManagement.revoking)),
                modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(vertical=12.dp,horizontal=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(12.dp),modifier=Modifier.size(44.dp)) {
                        Box(contentAlignment=Alignment.Center) {Text(server.name.trim().take(1).uppercase(),style=MaterialTheme.typography.titleMedium)}
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(server.name,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(stringResource(if(server.visibility==CommunityVisibility.PUBLIC) Res.string.nearby_servers_public else Res.string.nearby_servers_private),
                        style=MaterialTheme.typography.labelMedium)
                    if(server.description.isNotBlank()) Text(server.description,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(if(catalog) Res.string.nearby_servers_join else Res.string.nearby_servers_open),
                        style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                }
            }
        }
        if(page.hasMore) item {TextButton(onClick=presenter::loadMore,enabled=enabled && !page.loading) {Text(stringResource(Res.string.nearby_servers_more))}}
    }
}

@Composable
private fun ServerDetailsPane(state: ServerWorkspaceUiState,presenter: ServerWorkspacePresenter,modifier: Modifier) {
    val browser=state.browser
    val enabled=!state.commands.busy && !state.channelDeletion.busy && !state.channelCreation.busy && !state.invitations.busy && !state.acceptance.busy && !state.invitationManagement.revoking && state.actorId!=null
    var menuExpanded by remember(browser.selectedCommunity) {mutableStateOf(false)}
    LazyColumn(modifier.fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(state.channelDeletion.community==browser.selectedCommunity) {
            state.channelDeletion.error?.let { failure -> item {
                Text(stringResource(failure.channelDeletionMessage()),color=MaterialTheme.colorScheme.error)
            } }
            if(state.channelDeletion.deleted?.cleanupPending==true) item {
                // The deletion receipt proves deletion; cleanupPending was a snapshot, not live status.
                Text(stringResource(Res.string.nearby_channels_deleted),style=MaterialTheme.typography.bodySmall)
            }
        }
        if(browser.detailLoading) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(browser.detailError!=null) item {
            Text(stringResource(Res.string.nearby_servers_read_failed),color=MaterialTheme.colorScheme.error)
            TextButton(onClick=presenter::refresh,enabled=enabled) {Text(stringResource(Res.string.nearby_refresh))}
        }
        val detail=browser.detail?.takeIf {it.community.id==browser.selectedCommunity}
        if(detail!=null) {
            item {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(12.dp),modifier=Modifier.size(48.dp)) {
                        Box(contentAlignment=Alignment.Center) {Text(detail.community.name.trim().take(1).uppercase(),style=MaterialTheme.typography.titleLarge)}
                    }
                    Column(Modifier.weight(1f)) {
                        Text(detail.community.name,style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(stringResource(if(detail.community.visibility==CommunityVisibility.PUBLIC) Res.string.nearby_servers_public else Res.string.nearby_servers_private),
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick={menuExpanded=true},enabled=state.actorId!=null) {
                            Icon(Icons.Default.MoreVert,contentDescription=stringResource(Res.string.nearby_panel_more_controls))
                        }
                        DropdownMenu(expanded=menuExpanded,onDismissRequest={menuExpanded=false}) {
                            DropdownMenuItem(text={Text(stringResource(Res.string.nearby_refresh))},enabled=enabled,
                                onClick={menuExpanded=false;presenter.refresh()})
                            if(detail.ownerId==state.actorId) {
                                DropdownMenuItem(text={Text(stringResource(Res.string.nearby_invitations_manage))},
                                    enabled=!state.commands.busy && !state.channelDeletion.busy && state.actorId!=null && !browser.detailLoading && browser.detailError==null,
                                    onClick={menuExpanded=false;presenter.openInvitationManagement()})
                                DropdownMenuItem(text={Text(stringResource(Res.string.nearby_invitations_create))},
                                    enabled=enabled && !browser.detailLoading && browser.detailError==null,
                                    onClick={menuExpanded=false;presenter.openInvitationCreation()})
                                DropdownMenuItem(text={Text(stringResource(Res.string.nearby_channels_create))},
                                    enabled=enabled && !browser.detailLoading && browser.detailError==null,
                                    onClick={menuExpanded=false;presenter.openChannelCreation()})
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text={Text(stringResource(Res.string.nearby_servers_leave))},enabled=enabled,
                                onClick={menuExpanded=false;presenter.requestLeave()})
                            if(detail.ownerId==state.actorId) DropdownMenuItem(
                                text={Text(stringResource(Res.string.nearby_servers_delete),color=MaterialTheme.colorScheme.error)},enabled=enabled,
                                onClick={menuExpanded=false;presenter.requestDelete()})
                        }
                    }
                }
                if(detail.community.description.isNotBlank()) Text(detail.community.description,Modifier.padding(top=8.dp),style=MaterialTheme.typography.bodyMedium)
            }
            item {
                Text(stringResource(Res.string.nearby_servers_channels),style=MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.nearby_servers_voice_only),style=MaterialTheme.typography.bodySmall)
            }
            if(detail.channels.isEmpty()) item {Text(stringResource(Res.string.nearby_servers_no_channels))}
            items(detail.channels,key={it.id}) {channel ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        var channelMenu by remember(channel.id) { mutableStateOf(false) }
                        Text(channel.name,Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
                        OutlinedButton(onClick={presenter.connect(channel.id)},enabled=enabled && !browser.detailLoading) {
                            Text(stringResource(Res.string.nearby_servers_connect))
                        }
                        if(detail.ownerId==state.actorId) Box {
                            IconButton(onClick={channelMenu=true},enabled=enabled && !browser.detailLoading && browser.detailError==null) {
                                Icon(Icons.Default.MoreVert,contentDescription=stringResource(Res.string.nearby_panel_more_controls))
                            }
                            DropdownMenu(expanded=channelMenu,onDismissRequest={channelMenu=false}) {
                                DropdownMenuItem(text={Text(stringResource(Res.string.nearby_channels_delete),color=MaterialTheme.colorScheme.error)},
                                    onClick={channelMenu=false;presenter.requestChannelDeletion(channel.id)},enabled=enabled)
                            }
                        }
                    }
                }
            }
            // The radio belongs to the server, not to one voice channel: it works with no internet at all.
            item(key="radio") {ru.sodovaya.volty.presentation.root.nearby.NearbyRadioCard(detail.community.id,Modifier.fillMaxWidth())}
        }
    }
}

private fun ChannelCommandFailure.channelDeletionMessage() = when(this) {
    ChannelCommandFailure.ACCESS_DENIED, ChannelCommandFailure.UNAUTHORIZED -> Res.string.nearby_channels_delete_denied
    ChannelCommandFailure.CONFLICT -> Res.string.nearby_channels_delete_conflict
    else -> Res.string.nearby_channels_delete_failed
}
