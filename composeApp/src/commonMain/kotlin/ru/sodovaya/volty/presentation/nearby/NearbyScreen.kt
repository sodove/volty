package ru.sodovaya.volty.presentation.nearby

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.social.FriendSummary
import ru.sodovaya.volty.domain.social.FriendshipState
import ru.sodovaya.volty.domain.social.LiveGroupSnapshot
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.domain.social.RideGroup
import ru.sodovaya.volty.domain.social.SharingDurationPolicy
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.TelemetryShareProfile
import ru.sodovaya.volty.domain.social.UserSearchResult
import ru.sodovaya.volty.domain.social.VoiceRoomFailureReason
import ru.sodovaya.volty.domain.social.VoiceRoomState
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.nearby_accept
import volty.composeapp.generated.resources.nearby_active_group
import volty.composeapp.generated.resources.nearby_back
import volty.composeapp.generated.resources.nearby_cancel
import volty.composeapp.generated.resources.nearby_copy
import volty.composeapp.generated.resources.nearby_create
import volty.composeapp.generated.resources.nearby_create_account
import volty.composeapp.generated.resources.nearby_delete_group
import volty.composeapp.generated.resources.nearby_delete_group_confirm
import volty.composeapp.generated.resources.nearby_delete_group_text
import volty.composeapp.generated.resources.nearby_delete_group_title
import volty.composeapp.generated.resources.nearby_email
import volty.composeapp.generated.resources.nearby_extend
import volty.composeapp.generated.resources.nearby_friend_accepted
import volty.composeapp.generated.resources.nearby_friend_action_failed
import volty.composeapp.generated.resources.nearby_friend_add
import volty.composeapp.generated.resources.nearby_friend_blocked
import volty.composeapp.generated.resources.nearby_friend_incoming
import volty.composeapp.generated.resources.nearby_friend_outgoing
import volty.composeapp.generated.resources.nearby_friend_request_received
import volty.composeapp.generated.resources.nearby_friend_request_sent
import volty.composeapp.generated.resources.nearby_friend_search
import volty.composeapp.generated.resources.nearby_friend_search_empty
import volty.composeapp.generated.resources.nearby_friend_search_error
import volty.composeapp.generated.resources.nearby_friend_search_hint
import volty.composeapp.generated.resources.nearby_friend_searching
import volty.composeapp.generated.resources.nearby_friends_count
import volty.composeapp.generated.resources.nearby_friends_empty
import volty.composeapp.generated.resources.nearby_friends_tab
import volty.composeapp.generated.resources.nearby_group_controls
import volty.composeapp.generated.resources.nearby_group_invite_only
import volty.composeapp.generated.resources.nearby_group_map
import volty.composeapp.generated.resources.nearby_group_member_count
import volty.composeapp.generated.resources.nearby_group_member_role
import volty.composeapp.generated.resources.nearby_group_name
import volty.composeapp.generated.resources.nearby_group_name_hint
import volty.composeapp.generated.resources.nearby_group_open
import volty.composeapp.generated.resources.nearby_group_owner_role
import volty.composeapp.generated.resources.nearby_groups
import volty.composeapp.generated.resources.nearby_groups_empty
import volty.composeapp.generated.resources.nearby_invite_code
import volty.composeapp.generated.resources.nearby_invite_unavailable
import volty.composeapp.generated.resources.nearby_join
import volty.composeapp.generated.resources.nearby_join_code
import volty.composeapp.generated.resources.nearby_leave_group
import volty.composeapp.generated.resources.nearby_leave_group_confirm
import volty.composeapp.generated.resources.nearby_leave_group_text
import volty.composeapp.generated.resources.nearby_leave_group_title
import volty.composeapp.generated.resources.nearby_live
import volty.composeapp.generated.resources.nearby_login
import volty.composeapp.generated.resources.nearby_logout
import volty.composeapp.generated.resources.nearby_mic_disable
import volty.composeapp.generated.resources.nearby_mic_enable
import volty.composeapp.generated.resources.nearby_name
import volty.composeapp.generated.resources.nearby_no_active_group
import volty.composeapp.generated.resources.nearby_offline
import volty.composeapp.generated.resources.nearby_online
import volty.composeapp.generated.resources.nearby_participant_line
import volty.composeapp.generated.resources.nearby_participant_list
import volty.composeapp.generated.resources.nearby_participants_empty
import volty.composeapp.generated.resources.nearby_password
import volty.composeapp.generated.resources.nearby_password_hint
import volty.composeapp.generated.resources.nearby_people
import volty.composeapp.generated.resources.nearby_people_title
import volty.composeapp.generated.resources.nearby_profile_edit
import volty.composeapp.generated.resources.nearby_profile_full
import volty.composeapp.generated.resources.nearby_profile_location
import volty.composeapp.generated.resources.nearby_profile_ride
import volty.composeapp.generated.resources.nearby_profile_save
import volty.composeapp.generated.resources.nearby_profile_title
import volty.composeapp.generated.resources.nearby_recovery
import volty.composeapp.generated.resources.nearby_refresh
import volty.composeapp.generated.resources.nearby_reject
import volty.composeapp.generated.resources.nearby_register
import volty.composeapp.generated.resources.nearby_retry
import volty.composeapp.generated.resources.nearby_share
import volty.composeapp.generated.resources.nearby_share_description
import volty.composeapp.generated.resources.nearby_share_what
import volty.composeapp.generated.resources.nearby_sharing_active
import volty.composeapp.generated.resources.nearby_sharing_expired
import volty.composeapp.generated.resources.nearby_sharing_off
import volty.composeapp.generated.resources.nearby_social_description
import volty.composeapp.generated.resources.nearby_social_optional
import volty.composeapp.generated.resources.nearby_stale
import volty.composeapp.generated.resources.nearby_stop
import volty.composeapp.generated.resources.nearby_title
import volty.composeapp.generated.resources.nearby_voice
import volty.composeapp.generated.resources.nearby_voice_connected
import volty.composeapp.generated.resources.nearby_voice_connection_failed
import volty.composeapp.generated.resources.nearby_voice_connecting
import volty.composeapp.generated.resources.nearby_voice_empty
import volty.composeapp.generated.resources.nearby_voice_join
import volty.composeapp.generated.resources.nearby_voice_leave
import volty.composeapp.generated.resources.nearby_voice_members
import volty.composeapp.generated.resources.nearby_voice_permission_denied
import volty.composeapp.generated.resources.nearby_voice_speaking
import volty.composeapp.generated.resources.nearby_voice_unavailable
import volty.composeapp.generated.resources.nearby_working

private enum class NearbySection {
    PEOPLE,
    FRIENDS,
    GROUPS,
}

internal enum class GroupManagementAction {
    DELETE,
    LEAVE,
    NONE,
}

internal fun groupManagementAction(
    session: SocialSession,
    group: RideGroup,
): GroupManagementAction = when {
    isGroupOwner(session, group) -> GroupManagementAction.DELETE
    canLeaveGroup(session, group) -> GroupManagementAction.LEAVE
    else -> GroupManagementAction.NONE
}

private data class PendingGroupAction(
    val action: GroupManagementAction,
    val group: RideGroup,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    component: NearbyComponent,
    onShareText: ((String) -> Unit)? = null,
) {
    val state by component.state.collectAsState()
    var pendingShareTtlMillis by remember { mutableStateOf<Long?>(null) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ttlMillis = pendingShareTtlMillis
        pendingShareTtlMillis = null
        if (ttlMillis != null && result.values.any { it }) component.onStartSharing(ttlMillis)
        else if (ttlMillis != null) component.onLocationPermissionDenied()
    }
    val voicePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> component.onVoicePermissionResult(granted = result.values.all { it }) }

    fun requestSharing(ttlMillis: Long) {
        if (component.locationPermissions.isEmpty()) component.onStartSharing(ttlMillis)
        else {
            pendingShareTtlMillis = ttlMillis
            locationPermissionLauncher.launch(component.locationPermissions.toTypedArray())
        }
    }

    LaunchedEffect(state.pendingVoicePermissionRequest) {
        if (state.pendingVoicePermissionRequest) {
            voicePermissionLauncher.launch(component.voicePermissions.toTypedArray())
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.nearby_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.nearby_back))
                    }
                },
                actions = {
                    if (state.session is SocialSession.Authenticated) {
                        IconButton(onClick = component::onRefresh, enabled = state.operation == null) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.nearby_refresh))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (state.session !is SocialSession.Authenticated) {
            AuthPanel(state, component, Modifier.padding(padding))
        } else {
            SocialPanel(
                state = state,
                component = component,
                modifier = Modifier.padding(padding),
                onStartSharing = ::requestSharing,
                onShareText = onShareText,
            )
        }
    }
}

@Composable
private fun AuthPanel(state: NearbyComponent.State, component: NearbyComponent, modifier: Modifier) {
    var passwordVisible by remember { mutableStateOf(false) }
    GlassPanel(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(Res.string.nearby_social_optional), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.nearby_social_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.authMode == NearbyComponent.AuthMode.LOGIN,
                onClick = { component.onAuthModeChanged(NearbyComponent.AuthMode.LOGIN) },
                label = { Text(stringResource(Res.string.nearby_login)) },
            )
            FilterChip(
                selected = state.authMode == NearbyComponent.AuthMode.REGISTER,
                onClick = { component.onAuthModeChanged(NearbyComponent.AuthMode.REGISTER) },
                label = { Text(stringResource(Res.string.nearby_register)) },
            )
        }
        if (state.authMode == NearbyComponent.AuthMode.REGISTER) {
            OutlinedTextField(
                value = state.displayName,
                onValueChange = component::onDisplayNameChanged,
                label = { Text(stringResource(Res.string.nearby_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = state.email,
            onValueChange = component::onEmailChanged,
            label = { Text(stringResource(Res.string.nearby_email)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = component::onPasswordChanged,
            label = { Text(stringResource(Res.string.nearby_password)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                    )
                }
            },
        )
        if (state.authMode == NearbyComponent.AuthMode.REGISTER) {
            Text(
                stringResource(Res.string.nearby_password_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let { ErrorCard(it) }
        state.notice?.let { NoticeText(it) }
        Button(onClick = component::onSubmitAuth, enabled = state.operation == null, modifier = Modifier.fillMaxWidth()) {
            if (state.operation == NearbyComponent.Operation.AUTH) WorkingContent()
            else Text(stringResource(if (state.authMode == NearbyComponent.AuthMode.LOGIN) Res.string.nearby_login else Res.string.nearby_create_account))
        }
        TextButton(onClick = component::onRequestRecovery, enabled = state.operation == null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.nearby_recovery))
        }
    }
}

@Composable
private fun SocialPanel(
    state: NearbyComponent.State,
    component: NearbyComponent,
    modifier: Modifier,
    onStartSharing: (Long) -> Unit,
    onShareText: ((String) -> Unit)?,
) {
    val session = state.session as SocialSession.Authenticated
    val clipboard = LocalClipboardManager.current
    var section by remember { mutableStateOf(NearbySection.PEOPLE) }
    var pendingGroupAction by remember { mutableStateOf<PendingGroupAction?>(null) }

    GlassPanel(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ProfileHeader(state, session, component)
        state.error?.let { ErrorCard(it) }
        state.notice?.let { NoticeText(it) }
        NearbyTabs(section) { section = it }
        when (section) {
            NearbySection.PEOPLE -> PeopleSection(
                state = state,
                component = component,
                session = session,
                onStartSharing = onStartSharing,
                clipboard = clipboard,
                onShareText = onShareText,
                onRequestGroupAction = { action, group -> pendingGroupAction = PendingGroupAction(action, group) },
            )
            NearbySection.FRIENDS -> FriendsSection(state, component)
            NearbySection.GROUPS -> GroupsSection(
                state = state,
                component = component,
                session = session,
                onStartSharing = onStartSharing,
                clipboard = clipboard,
                onShareText = onShareText,
                onRequestGroupAction = { action, group -> pendingGroupAction = PendingGroupAction(action, group) },
            )
        }
    }

    pendingGroupAction?.let { pending ->
        GroupActionDialog(
            pending = pending,
            enabled = state.operation == null,
            onDismiss = { pendingGroupAction = null },
            onConfirm = {
                pendingGroupAction = null
                when (pending.action) {
                    GroupManagementAction.DELETE -> component.onDeleteGroup(pending.group)
                    GroupManagementAction.LEAVE -> component.onLeaveGroup(pending.group)
                    GroupManagementAction.NONE -> Unit
                }
            },
        )
    }
}

@Composable
private fun ProfileHeader(
    state: NearbyComponent.State,
    session: SocialSession.Authenticated,
    component: NearbyComponent,
) {
    var editing by remember(session.userId) { mutableStateOf(false) }
    LaunchedEffect(session.displayName) { editing = false }

    NearbyCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InitialAvatar(session.displayName)
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.nearby_profile_title), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(session.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = { editing = !editing }, enabled = state.operation == null) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(Res.string.nearby_profile_edit))
            }
        }
        if (editing) {
            OutlinedTextField(
                value = state.profileName,
                onValueChange = component::onProfileNameChanged,
                label = { Text(stringResource(Res.string.nearby_name)) },
                singleLine = true,
                enabled = state.operation == null,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = component::onUpdateProfile, enabled = state.operation == null) {
                    if (state.operation == NearbyComponent.Operation.UPDATE_PROFILE) WorkingContent()
                    else Text(stringResource(Res.string.nearby_profile_save))
                }
                TextButton(onClick = { editing = false }, enabled = state.operation == null) {
                    Text(stringResource(Res.string.nearby_cancel))
                }
            }
        }
        TextButton(onClick = component::onLogout, enabled = state.operation == null) {
            Icon(Icons.Default.Logout, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text(stringResource(Res.string.nearby_logout))
        }
    }
}

@Composable
private fun NearbyTabs(selected: NearbySection, onSelected: (NearbySection) -> Unit) {
    TabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        NearbySection.entries.forEach { section ->
            Tab(
                selected = selected == section,
                onClick = { onSelected(section) },
                text = {
                    Text(
                        stringResource(
                            when (section) {
                                NearbySection.PEOPLE -> Res.string.nearby_people
                                NearbySection.FRIENDS -> Res.string.nearby_friends_tab
                                NearbySection.GROUPS -> Res.string.nearby_groups
                            }
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun PeopleSection(
    state: NearbyComponent.State,
    component: NearbyComponent,
    session: SocialSession.Authenticated,
    onStartSharing: (Long) -> Unit,
    clipboard: ClipboardManager,
    onShareText: ((String) -> Unit)?,
    onRequestGroupAction: (GroupManagementAction, RideGroup) -> Unit,
) {
    SectionTitle(stringResource(Res.string.nearby_people_title))
    val group = state.selectedGroup
    if (group == null) {
        NearbyCard {
            Text(stringResource(Res.string.nearby_no_active_group), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    ActiveGroupCard(state, group, component)
    NearbyCard {
        SectionTitle(stringResource(Res.string.nearby_participant_list))
        ParticipantList((state.liveEvent as? SocialLiveEvent.Snapshot)?.value)
    }
    GroupControls(
        state = state,
        component = component,
        session = session,
        group = group,
        onStartSharing = onStartSharing,
        clipboard = clipboard,
        onShareText = onShareText,
        onRequestGroupAction = onRequestGroupAction,
    )
}

@Composable
private fun ActiveGroupCard(
    state: NearbyComponent.State,
    group: RideGroup,
    component: NearbyComponent,
) {
    NearbyCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InitialAvatar(group.name)
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.nearby_active_group), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(Res.string.nearby_group_member_count, group.members.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(Res.string.nearby_live, group.name), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        }
        Button(onClick = component::onOpenGroupMap, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.nearby_group_map))
        }
        Text(
            if (state.sharing == null) stringResource(Res.string.nearby_sharing_off)
            else stringResource(Res.string.nearby_online),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FriendsSection(state: NearbyComponent.State, component: NearbyComponent) {
    SectionTitle(stringResource(Res.string.nearby_friends_count, state.friends.size))
    NearbyCard {
        OutlinedTextField(
            value = state.friendQuery,
            onValueChange = component::onFriendQueryChanged,
            label = { Text(stringResource(Res.string.nearby_friend_search)) },
            placeholder = { Text(stringResource(Res.string.nearby_friend_search_hint)) },
            singleLine = true,
            enabled = state.operation == null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.friendSearchLoading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.nearby_friend_searching), fontSize = 12.sp)
            }
        }
        if (state.friendQuery.isNotBlank() && !state.friendSearchLoading) {
            if (state.friendSearchResults.isEmpty()) {
                Text(
                    stringResource(Res.string.nearby_friend_search_empty),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    state.friendSearchResults.forEachIndexed { index, result ->
                        if (index > 0) FriendDivider()
                        FriendSearchRow(
                            result = result,
                            actionIds = state.friendActionIds,
                            enabled = state.operation == null,
                            onSendRequest = { component.onSendFriendRequest(result.userId.value) },
                            onRespond = component::onRespondToFriendRequest,
                        )
                    }
                }
            }
        }
    }
    if (state.friends.isEmpty()) {
        NearbyCard { Text(stringResource(Res.string.nearby_friends_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    NearbyCard {
        sortFriendsForDisplay(state.friends).forEachIndexed { index, friend ->
            if (index > 0) Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InitialAvatar(friend.displayName)
                Column(Modifier.weight(1f)) {
                    Text(friend.displayName, fontWeight = FontWeight.Medium)
                    FriendshipLabel(friend.state)
                }
                if (friend.state == FriendshipState.REQUEST_RECEIVED) {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(
                            onClick = { component.onRespondToFriendRequest(friend.friendshipId.value, true) },
                            enabled = state.operation == null && !isFriendRowBusy(state.friendActionIds, friend.friendshipId.value),
                        ) { Text(stringResource(Res.string.nearby_accept)) }
                        TextButton(
                            onClick = { component.onRespondToFriendRequest(friend.friendshipId.value, false) },
                            enabled = state.operation == null && !isFriendRowBusy(state.friendActionIds, friend.friendshipId.value),
                        ) { Text(stringResource(Res.string.nearby_reject)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendSearchRow(
    result: UserSearchResult,
    actionIds: Set<String>,
    enabled: Boolean,
    onSendRequest: () -> Unit,
    onRespond: (String, Boolean) -> Unit,
) {
    val actionId = friendActionKey(result)
    val busy = isFriendRowBusy(actionIds, actionId)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InitialAvatar(result.displayName)
        Column(Modifier.weight(1f)) {
            Text(result.displayName, fontWeight = FontWeight.Medium)
            result.state?.let { FriendshipLabel(it) }
        }
        when (friendSearchAction(result)) {
            FriendSearchAction.SEND_REQUEST -> TextButton(
                onClick = onSendRequest,
                enabled = enabled && !busy,
            ) {
                if (busy) WorkingContent() else Text(stringResource(Res.string.nearby_friend_add))
            }
            FriendSearchAction.STATUS -> if (result.state == FriendshipState.REQUEST_RECEIVED && result.friendshipId != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { onRespond(result.friendshipId.value, true) }, enabled = enabled && !busy) {
                        if (busy) WorkingContent() else Text(stringResource(Res.string.nearby_accept))
                    }
                    TextButton(onClick = { onRespond(result.friendshipId.value, false) }, enabled = enabled && !busy) {
                        Text(stringResource(Res.string.nearby_reject))
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendDivider() {
    Spacer(
        Modifier
            .height(1.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
    )
}

@Composable
private fun GroupsSection(
    state: NearbyComponent.State,
    component: NearbyComponent,
    session: SocialSession.Authenticated,
    onStartSharing: (Long) -> Unit,
    clipboard: ClipboardManager,
    onShareText: ((String) -> Unit)?,
    onRequestGroupAction: (GroupManagementAction, RideGroup) -> Unit,
) {
    SectionTitle(stringResource(Res.string.nearby_groups))
    if (state.groups.isEmpty()) {
        NearbyCard { Text(stringResource(Res.string.nearby_groups_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.groups.forEach { group ->
                GroupRow(group, state.selectedGroup?.id == group.id) { component.onSelectGroup(group) }
            }
        }
    }
    NearbyCard {
        OutlinedTextField(
            value = state.groupName,
            onValueChange = component::onGroupNameChanged,
            label = { Text(stringResource(Res.string.nearby_group_name)) },
            placeholder = { Text(stringResource(Res.string.nearby_group_name_hint)) },
            singleLine = true,
            enabled = state.operation == null,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.joinCode,
            onValueChange = component::onJoinCodeChanged,
            label = { Text(stringResource(Res.string.nearby_join_code)) },
            singleLine = true,
            enabled = state.operation == null,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = component::onCreateGroup, enabled = state.operation == null, modifier = Modifier.weight(1f)) {
                if (state.operation == NearbyComponent.Operation.CREATE_GROUP) WorkingContent()
                else {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(Res.string.nearby_create), maxLines = 1)
                }
            }
            OutlinedButton(onClick = component::onJoinGroup, enabled = state.operation == null, modifier = Modifier.weight(1f)) {
                if (state.operation == NearbyComponent.Operation.JOIN_GROUP) WorkingContent()
                else Text(stringResource(Res.string.nearby_join), maxLines = 1)
            }
        }
    }
    state.selectedGroup?.let { group ->
        GroupControls(
            state = state,
            component = component,
            session = session,
            group = group,
            onStartSharing = onStartSharing,
            clipboard = clipboard,
            onShareText = onShareText,
            onRequestGroupAction = onRequestGroupAction,
        )
    }
}

@Composable
private fun GroupRow(group: RideGroup, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InitialAvatar(group.name)
        Column(Modifier.weight(1f)) {
            Text(group.name, fontWeight = FontWeight.Medium)
            Text(stringResource(Res.string.nearby_group_member_count, group.members.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            stringResource(if (group.inviteOnly) Res.string.nearby_group_invite_only else Res.string.nearby_group_open),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GroupControls(
    state: NearbyComponent.State,
    component: NearbyComponent,
    session: SocialSession.Authenticated,
    group: RideGroup,
    onStartSharing: (Long) -> Unit,
    clipboard: ClipboardManager,
    onShareText: ((String) -> Unit)?,
    onRequestGroupAction: (GroupManagementAction, RideGroup) -> Unit,
) {
    NearbyCard {
        SectionTitle(stringResource(Res.string.nearby_group_controls))
        Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        GroupMembers(group)
        if (isGroupOwner(session, group)) InviteCodeSection(group, clipboard, onShareText)
        if (groupManagementAction(session, group) == GroupManagementAction.DELETE) {
            TextButton(
                onClick = { onRequestGroupAction(GroupManagementAction.DELETE, group) },
                enabled = state.operation == null,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(Res.string.nearby_delete_group))
            }
        }
        if (canLeaveGroup(session, group)) {
            TextButton(
                onClick = { onRequestGroupAction(GroupManagementAction.LEAVE, group) },
                enabled = state.operation == null,
            ) { Text(stringResource(Res.string.nearby_leave_group)) }
        }
        SharingControls(state, component, onStartSharing)
        VoiceControls(state.voice, state.operation, component)
    }
}

@Composable
private fun GroupMembers(group: RideGroup) {
    SectionTitle(stringResource(Res.string.nearby_participant_list))
    if (group.members.isEmpty()) {
        Text(stringResource(Res.string.nearby_participants_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        group.members.forEach { member ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(member.displayName, modifier = Modifier.weight(1f), fontSize = 13.sp)
                Text(
                    stringResource(if (member.role.name == "OWNER") Res.string.nearby_group_owner_role else Res.string.nearby_group_member_role),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InviteCodeSection(group: RideGroup, clipboard: ClipboardManager, onShareText: ((String) -> Unit)?) {
    val code = inviteCodeFor(group)
    SectionTitle(stringResource(Res.string.nearby_invite_code))
    if (code.isNullOrBlank()) {
        Text(stringResource(Res.string.nearby_invite_unavailable), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(code, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(Res.string.nearby_copy))
        }
        IconButton(onClick = { onShareText?.invoke(code) ?: clipboard.setText(AnnotatedString(code)) }) {
            Icon(Icons.Default.Share, contentDescription = stringResource(Res.string.nearby_share))
        }
    }
}

@Composable
private fun SharingControls(state: NearbyComponent.State, component: NearbyComponent, onStartSharing: (Long) -> Unit) {
    val sharing = state.sharing
    var durationMenuExpanded by remember { mutableStateOf(false) }
    var now by remember(sharing?.expiresAtEpochMillis) { mutableLongStateOf(epochMillisForUi()) }
    LaunchedEffect(sharing?.expiresAtEpochMillis) {
        while (sharing != null) {
            now = epochMillisForUi()
            delay(1_000L)
        }
    }
    SectionTitle(stringResource(Res.string.nearby_share_what))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TelemetryShareProfile.entries.forEach { profile ->
            FilterChip(
                selected = state.shareProfile == profile,
                onClick = { component.onShareProfileChanged(profile) },
                enabled = state.operation == null,
                label = {
                    Text(
                        stringResource(
                            when (profile) {
                                TelemetryShareProfile.LOCATION -> Res.string.nearby_profile_location
                                TelemetryShareProfile.RIDE -> Res.string.nearby_profile_ride
                                TelemetryShareProfile.FULL -> Res.string.nearby_profile_full
                            }
                        )
                    )
                },
            )
        }
    }
    Text(stringResource(Res.string.nearby_share_description), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (sharing == null) {
        Text(stringResource(Res.string.nearby_sharing_off), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Button(
                onClick = { durationMenuExpanded = true },
                enabled = state.operation == null,
            ) {
                if (state.operation == NearbyComponent.Operation.START_SHARING) WorkingContent()
                else Text("Начать sharing")
            }
            DropdownMenu(
                expanded = durationMenuExpanded,
                onDismissRequest = { durationMenuExpanded = false },
            ) {
                SharingDurationPolicy.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            durationMenuExpanded = false
                            onStartSharing(option.ttlMillis)
                        },
                    )
                }
            }
        }
    } else {
        val remaining = remainingSharingMillis(sharing.expiresAtEpochMillis, now)
        Text(
            if (remaining > 0L) stringResource(Res.string.nearby_sharing_active, formatDuration(remaining))
            else stringResource(Res.string.nearby_sharing_expired),
            color = if (remaining > 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Medium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Button(
                    onClick = { durationMenuExpanded = true },
                    enabled = state.operation == null,
                ) {
                    if (state.operation == NearbyComponent.Operation.RENEW_SHARING) WorkingContent()
                    else Text(stringResource(Res.string.nearby_extend))
                }
                DropdownMenu(
                    expanded = durationMenuExpanded,
                    onDismissRequest = { durationMenuExpanded = false },
                ) {
                    SharingDurationPolicy.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                durationMenuExpanded = false
                                component.onRenewSharing(option.ttlMillis)
                            },
                        )
                    }
                }
            }
            TextButton(onClick = component::onStopSharing, enabled = state.operation == null) {
                if (state.operation == NearbyComponent.Operation.STOP_SHARING) WorkingContent()
                else Text(stringResource(Res.string.nearby_stop))
            }
        }
    }
}

@Composable
private fun ParticipantList(snapshot: LiveGroupSnapshot?) {
    if (snapshot == null || snapshot.participants.isEmpty()) {
        Text(stringResource(Res.string.nearby_participants_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    snapshot.participants.forEach { participant ->
        val status = when (participant.presence) {
            PresenceStatus.ONLINE -> Res.string.nearby_online
            PresenceStatus.STALE -> Res.string.nearby_stale
            PresenceStatus.OFFLINE -> Res.string.nearby_offline
        }
        Text(
            stringResource(
                Res.string.nearby_participant_line,
                participant.displayName,
                if (nearbyParticipantHasCurrentPoint(participant)) stringResource(status)
                else "нет текущей точки",
            ),
            fontSize = 13.sp,
        )
    }
}

internal fun nearbyParticipantHasCurrentPoint(
    participant: ru.sodovaya.volty.domain.social.ParticipantSnapshot,
): Boolean = participant.location != null

@Composable
private fun VoiceControls(voice: VoiceRoomState, operation: NearbyComponent.Operation?, component: NearbyComponent) {
    SectionTitle(stringResource(Res.string.nearby_voice))
    when (voice) {
        VoiceRoomState.Unavailable -> Text(stringResource(Res.string.nearby_voice_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
        VoiceRoomState.Available -> Button(onClick = component::onJoinVoice, enabled = operation == null) { Text(stringResource(Res.string.nearby_voice_join)) }
        VoiceRoomState.Joining -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(Res.string.nearby_voice_connecting))
        }
        is VoiceRoomState.Joined -> {
            Text(stringResource(Res.string.nearby_voice_connected))
            if (voice.participants.isEmpty()) Text(stringResource(Res.string.nearby_voice_empty), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text(stringResource(Res.string.nearby_voice_members, voice.participants.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            voice.participants.forEach { participant ->
                Text(
                    if (participant.isSpeaking) stringResource(Res.string.nearby_voice_speaking, participant.displayName)
                    else participant.displayName,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { component.onSetMuted(!voice.muted) }, enabled = operation == null) {
                    if (operation == NearbyComponent.Operation.VOICE_MUTE) WorkingContent()
                    else Text(stringResource(if (voice.muted) Res.string.nearby_mic_enable else Res.string.nearby_mic_disable))
                }
                TextButton(onClick = component::onLeaveVoice, enabled = operation == null) {
                    if (operation == NearbyComponent.Operation.VOICE_LEAVE) WorkingContent()
                    else Text(stringResource(Res.string.nearby_voice_leave))
                }
            }
        }
        is VoiceRoomState.Failed -> {
            Text(
                when (voice.reason) {
                    VoiceRoomFailureReason.MICROPHONE_PERMISSION_DENIED -> stringResource(Res.string.nearby_voice_permission_denied)
                    VoiceRoomFailureReason.CONNECTION_FAILED -> stringResource(Res.string.nearby_voice_connection_failed)
                },
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = component::onJoinVoice, enabled = operation == null) { Text(stringResource(Res.string.nearby_retry)) }
        }
    }
}

@Composable
private fun FriendshipLabel(state: FriendshipState) {
    Text(
        stringResource(
            when (state) {
                FriendshipState.ACCEPTED -> Res.string.nearby_friend_accepted
                FriendshipState.REQUEST_SENT -> Res.string.nearby_friend_request_sent
                FriendshipState.REQUEST_RECEIVED -> Res.string.nearby_friend_request_received
                FriendshipState.BLOCKED -> Res.string.nearby_friend_blocked
            }
        ),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal enum class FriendSearchAction {
    SEND_REQUEST,
    STATUS,
}

internal fun friendSearchAction(result: UserSearchResult): FriendSearchAction =
    if (result.state == null) FriendSearchAction.SEND_REQUEST else FriendSearchAction.STATUS

internal fun friendActionKey(result: UserSearchResult): String =
    result.friendshipId?.value ?: result.userId.value

internal fun isFriendRowBusy(actionIds: Set<String>, rowId: String): Boolean = rowId in actionIds

internal fun sortFriendsForDisplay(friends: List<FriendSummary>): List<FriendSummary> =
    friends.sortedWith(
        compareBy<FriendSummary> {
            when (it.state) {
                FriendshipState.REQUEST_RECEIVED -> 0
                FriendshipState.ACCEPTED -> 1
                FriendshipState.REQUEST_SENT -> 2
                FriendshipState.BLOCKED -> 3
            }
        }.thenBy { it.displayName.lowercase() }
    )

@Composable
private fun GroupActionDialog(
    pending: PendingGroupAction,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val deleting = pending.action == GroupManagementAction.DELETE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (deleting) Res.string.nearby_delete_group_title else Res.string.nearby_leave_group_title))
        },
        text = {
            Text(
                stringResource(
                    if (deleting) Res.string.nearby_delete_group_text else Res.string.nearby_leave_group_text,
                    pending.group.name,
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = enabled,
                colors = if (deleting) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) {
                Text(stringResource(if (deleting) Res.string.nearby_delete_group_confirm else Res.string.nearby_leave_group_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.nearby_cancel)) } },
    )
}

@Composable
private fun InitialAvatar(name: String) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(name.trim().take(1).uppercase(), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NearbyCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun GlassPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun WorkingContent() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(6.dp))
        Text(stringResource(Res.string.nearby_working), maxLines = 1)
    }
}

@Composable
private fun ErrorCard(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun NoticeText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth())
}

private fun formatDuration(millis: Long): String {
    val totalMinutes = millis / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}ч ${minutes}м" else "${minutes}м"
}

internal fun inviteCodeFor(group: RideGroup): String? = group.inviteCode

@OptIn(kotlin.time.ExperimentalTime::class)
private fun epochMillisForUi(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
