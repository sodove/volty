package ru.sodovaya.volty.presentation.nearby

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import ru.sodovaya.volty.domain.social.LiveGroupSnapshot
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.domain.social.RideGroup
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.TelemetryShareProfile
import ru.sodovaya.volty.domain.social.VoiceRoomState
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.nearby_create
import volty.composeapp.generated.resources.nearby_create_account
import volty.composeapp.generated.resources.nearby_email
import volty.composeapp.generated.resources.nearby_friends_count
import volty.composeapp.generated.resources.nearby_groups
import volty.composeapp.generated.resources.nearby_groups_empty
import volty.composeapp.generated.resources.nearby_greeting
import volty.composeapp.generated.resources.nearby_join_code
import volty.composeapp.generated.resources.nearby_login
import volty.composeapp.generated.resources.nearby_live
import volty.composeapp.generated.resources.nearby_mic_disable
import volty.composeapp.generated.resources.nearby_mic_enable
import volty.composeapp.generated.resources.nearby_mic_off
import volty.composeapp.generated.resources.nearby_mic_on
import volty.composeapp.generated.resources.nearby_name
import volty.composeapp.generated.resources.nearby_participants_empty
import volty.composeapp.generated.resources.nearby_password
import volty.composeapp.generated.resources.nearby_password_hint
import volty.composeapp.generated.resources.nearby_profile_full
import volty.composeapp.generated.resources.nearby_profile_location
import volty.composeapp.generated.resources.nearby_profile_ride
import volty.composeapp.generated.resources.nearby_register
import volty.composeapp.generated.resources.nearby_required
import volty.composeapp.generated.resources.nearby_retry
import volty.composeapp.generated.resources.nearby_share_description
import volty.composeapp.generated.resources.nearby_share_enabled
import volty.composeapp.generated.resources.nearby_share_what
import volty.composeapp.generated.resources.nearby_share_hour
import volty.composeapp.generated.resources.nearby_social_description
import volty.composeapp.generated.resources.nearby_social_optional
import volty.composeapp.generated.resources.nearby_stop
import volty.composeapp.generated.resources.nearby_title
import volty.composeapp.generated.resources.nearby_working
import volty.composeapp.generated.resources.nearby_voice
import volty.composeapp.generated.resources.nearby_voice_connected
import volty.composeapp.generated.resources.nearby_voice_connection_failed
import volty.composeapp.generated.resources.nearby_voice_join
import volty.composeapp.generated.resources.nearby_voice_leave
import volty.composeapp.generated.resources.nearby_voice_permission_denied
import volty.composeapp.generated.resources.nearby_voice_unavailable

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    component: NearbyComponent,
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
    ) { result ->
        component.onVoicePermissionResult(granted = result.values.all { it })
    }
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.nearby_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (state.session is SocialSession.Authenticated) {
                        IconButton(onClick = component::onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                        }
                        IconButton(onClick = component::onLogout) {
                            Icon(Icons.Default.Logout, contentDescription = "Выйти")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.session !is SocialSession.Authenticated) {
            AuthPanel(state, component, Modifier.padding(padding))
        } else {
            SocialPanel(state, component, Modifier.padding(padding), ::requestSharing)
        }
    }
}

@Composable
private fun AuthPanel(
    state: NearbyComponent.State,
    component: NearbyComponent,
    modifier: Modifier,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.nearby_social_optional), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(Res.string.nearby_social_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Button(
            onClick = component::onSubmitAuth,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(Res.string.nearby_working))
            }
            else Text(
                stringResource(
                    if (state.authMode == NearbyComponent.AuthMode.LOGIN) Res.string.nearby_login
                    else Res.string.nearby_create_account
                )
            )
        }
    }
}

@Composable
private fun SocialPanel(
    state: NearbyComponent.State,
    component: NearbyComponent,
    modifier: Modifier,
    onStartSharing: (Long) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val session = state.session as SocialSession.Authenticated
        Text(stringResource(Res.string.nearby_greeting, session.displayName), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.nearby_friends_count, state.friends.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.error?.let { ErrorCard(it) }
        Text(stringResource(Res.string.nearby_groups), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (state.groups.isEmpty()) {
            Text(stringResource(Res.string.nearby_groups_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.groups.forEach { group -> GroupRow(group, state.selectedGroup?.id == group.id) { component.onSelectGroup(group) } }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.joinCode,
                onValueChange = component::onJoinCodeChanged,
                label = { Text(stringResource(Res.string.nearby_join_code)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = component::onCreateGroup, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(Res.string.nearby_create), maxLines = 1)
                }
                OutlinedButton(onClick = component::onJoinGroup, modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.nearby_join_code), maxLines = 1)
                }
            }
        }
        state.selectedGroup?.let { group ->
            GroupControls(state, component, group, onStartSharing)
        }
    }
}

@Composable
private fun GroupRow(group: RideGroup, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(group.name, fontWeight = FontWeight.Medium)
            Text("Участников: ${group.members.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (group.inviteOnly) "По приглашению" else "Открытая", fontSize = 12.sp)
    }
}

@Composable
private fun GroupControls(
    state: NearbyComponent.State,
    component: NearbyComponent,
    group: RideGroup,
    onStartSharing: (Long) -> Unit,
) {
    Spacer(Modifier.height(4.dp))
    Text(stringResource(Res.string.nearby_live, group.name), style = MaterialTheme.typography.titleMedium)
    val snapshot = (state.liveEvent as? SocialLiveEvent.Snapshot)?.value
    ParticipantList(snapshot)
    Text(stringResource(Res.string.nearby_share_what), fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TelemetryShareProfile.entries.forEach { profile ->
            FilterChip(
                selected = state.shareProfile == profile,
                onClick = { component.onShareProfileChanged(profile) },
                label = { Text(profileLabel(profile)) },
            )
        }
    }
    Text(
        stringResource(Res.string.nearby_share_description),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onStartSharing(60 * 60 * 1000L) }, enabled = state.sharing == null) {
            Text(stringResource(if (state.sharing == null) Res.string.nearby_share_hour else Res.string.nearby_share_enabled))
        }
        if (state.sharing != null) TextButton(onClick = component::onStopSharing) { Text(stringResource(Res.string.nearby_stop)) }
    }
    VoiceControls(state.voice, component)
}

@Composable
private fun ParticipantList(snapshot: LiveGroupSnapshot?) {
    if (snapshot == null || snapshot.participants.isEmpty()) {
        Text(stringResource(Res.string.nearby_participants_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    snapshot.participants.forEach { participant ->
        val status = when (participant.presence) {
            PresenceStatus.ONLINE -> "онлайн"
            PresenceStatus.STALE -> "устарело"
            PresenceStatus.OFFLINE -> "офлайн"
        }
        Text("${participant.displayName} · $status", fontSize = 13.sp)
    }
}

@Composable
private fun VoiceControls(voice: VoiceRoomState, component: NearbyComponent) {
    Text(stringResource(Res.string.nearby_voice), fontWeight = FontWeight.SemiBold)
    when (voice) {
        VoiceRoomState.Unavailable -> Text(stringResource(Res.string.nearby_voice_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
        VoiceRoomState.Available -> Button(onClick = component::onJoinVoice) { Text(stringResource(Res.string.nearby_voice_join)) }
        VoiceRoomState.Joining -> CircularProgressIndicator(Modifier.size(24.dp))
        is VoiceRoomState.Joined -> Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.nearby_voice_connected))
                Text(
                    stringResource(if (voice.muted) Res.string.nearby_mic_off else Res.string.nearby_mic_on),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = { component.onSetMuted(!voice.muted) }) {
                Text(stringResource(if (voice.muted) Res.string.nearby_mic_enable else Res.string.nearby_mic_disable))
            }
            TextButton(onClick = component::onLeaveVoice) { Text(stringResource(Res.string.nearby_voice_leave)) }
        }
        is VoiceRoomState.Failed -> {
            Text(
                stringResource(
                    when (voice.reason) {
                        ru.sodovaya.volty.domain.social.VoiceRoomFailureReason.MICROPHONE_PERMISSION_DENIED ->
                            Res.string.nearby_voice_permission_denied
                        ru.sodovaya.volty.domain.social.VoiceRoomFailureReason.CONNECTION_FAILED ->
                            Res.string.nearby_voice_connection_failed
                    }
                ),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = component::onJoinVoice) { Text(stringResource(Res.string.nearby_retry)) }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
}

private fun profileLabel(profile: TelemetryShareProfile): String = when (profile) {
    TelemetryShareProfile.LOCATION -> "Только место"
    TelemetryShareProfile.RIDE -> "Поездка"
    TelemetryShareProfile.FULL -> "Все данные"
}
