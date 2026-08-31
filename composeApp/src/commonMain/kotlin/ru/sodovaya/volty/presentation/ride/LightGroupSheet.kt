package ru.sodovaya.volty.presentation.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.domain.social.SharingDurationPolicy
import ru.sodovaya.volty.domain.social.SocialRuntimeState
import ru.sodovaya.volty.domain.social.VoiceRoomState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LightGroupSheet(
    runtime: SocialRuntimeState,
    onDismiss: () -> Unit,
    onStartSharing: (Long) -> Unit,
    onRenewSharing: (Long) -> Unit,
    onStopSharing: () -> Unit,
    onJoinVoice: () -> Unit,
    onToggleMute: () -> Unit,
    onLeaveVoice: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenGroupMap: () -> Unit,
) {
    val state = lightGroupSheetState(runtime) ?: return
    val colors = MaterialTheme.colorScheme
    var durationMenuExpanded by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerHigh,
        tonalElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = colors.primary)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.groupName, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Участники отображаются на карте Light", fontSize = 11.sp, color = colors.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Закрыть") }
            }

            state.members.forEach { member ->
                val statusColor = when (member.status) {
                    PresenceStatus.ONLINE -> colors.tertiary
                    PresenceStatus.STALE -> colors.secondary
                    PresenceStatus.OFFLINE, null -> colors.outline
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        member.name.take(1).uppercase(),
                        modifier = Modifier.size(34.dp).clip(CircleShape).background(statusColor.copy(alpha = .16f)).padding(top = 8.dp),
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(member.name + if (member.isOwner) " · ведущая" else "", fontWeight = FontWeight.SemiBold)
                        Text(
                            when (member.status) {
                                PresenceStatus.ONLINE -> "онлайн · точка обновляется"
                                PresenceStatus.STALE -> "последняя точка устарела"
                                PresenceStatus.OFFLINE -> "оффлайн · sharing завершён"
                                null -> "нет текущей точки · ждём GPS"
                            },
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Text(
                        lightGroupMemberStatusText(member.status),
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Text("Sharing", fontWeight = FontWeight.Bold, color = colors.onSurface)
            if (state.sharingActive) {
                Text(
                    "Геопозиция активна до ${state.sharingExpiresAtEpochMillis?.let(::formatSharingExpiry) ?: "—"}",
                    fontSize = 12.sp,
                    color = colors.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                        Button(
                            onClick = { durationMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Продлить") }
                        DropdownMenu(
                            expanded = durationMenuExpanded,
                            onDismissRequest = { durationMenuExpanded = false },
                        ) {
                            SharingDurationPolicy.options.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        durationMenuExpanded = false
                                        onRenewSharing(option.ttlMillis)
                                    },
                                )
                            }
                        }
                    }
                    TextButton(onClick = onStopSharing) { Text("Остановить") }
                }
            } else {
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(
                        onClick = { durationMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.ShareLocation, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Начать sharing")
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
            }

            Text("Voice", fontWeight = FontWeight.Bold, color = colors.onSurface)
            when (val voice = runtime.voice) {
                VoiceRoomState.Unavailable -> Text("Голосовой канал недоступен", fontSize = 12.sp, color = colors.onSurfaceVariant)
                VoiceRoomState.Available -> OutlinedButton(onClick = onJoinVoice, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Войти в voice")
                }
                VoiceRoomState.Joining -> Text("Подключение к voice…", color = colors.onSurfaceVariant)
                is VoiceRoomState.Joined -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Voice подключён", modifier = Modifier.weight(1f), color = colors.primary, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onToggleMute) { Icon(if (voice.muted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = "Микрофон") }
                    TextButton(onClick = onLeaveVoice) { Text("Выйти") }
                }
                is VoiceRoomState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Не удалось подключиться", modifier = Modifier.weight(1f), color = colors.error, fontSize = 12.sp)
                    TextButton(onClick = onJoinVoice) { Text("Повторить") }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenGroupMap, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Map, contentDescription = null)
                    Spacer(Modifier.size(5.dp))
                    Text("GroupMap")
                }
                Button(onClick = onOpenNearby, modifier = Modifier.weight(1f)) { Text("Подробнее в Nearby") }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}
