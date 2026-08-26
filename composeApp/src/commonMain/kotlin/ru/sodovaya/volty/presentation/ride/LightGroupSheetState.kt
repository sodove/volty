package ru.sodovaya.volty.presentation.ride

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialRuntimeState

internal data class LightGroupSheetMember(
    val name: String,
    val status: PresenceStatus?,
    val hasCurrentPoint: Boolean,
    val isOwner: Boolean,
)

internal data class LightGroupSheetState(
    val groupName: String,
    val members: List<LightGroupSheetMember>,
    val sharingActive: Boolean,
    val sharingExpiresAtEpochMillis: Long?,
)

internal fun lightGroupSheetState(runtime: SocialRuntimeState): LightGroupSheetState? {
    val group = runtime.selectedGroup ?: return null
    val markersByUser = runtime.markers.associateBy { it.userId }
    val snapshotPresenceByUser = (runtime.liveEvent as? SocialLiveEvent.Snapshot)
        ?.value
        ?.participants
        ?.associate { it.userId.value to it.presence }
    return LightGroupSheetState(
        groupName = group.name,
        members = group.members.map { member ->
            val participant = (runtime.liveEvent as? SocialLiveEvent.Snapshot)
                ?.value
                ?.participants
                ?.firstOrNull { it.userId == member.userId }
            LightGroupSheetMember(
                name = member.displayName,
                status = markersByUser[member.userId.value]?.presence
                    ?: snapshotPresenceByUser?.get(member.userId.value)
                        ?.takeIf { participant?.location != null },
                hasCurrentPoint = markersByUser.containsKey(member.userId.value) || participant?.location != null,
                isOwner = member.userId == group.ownerId,
            )
        },
        sharingActive = runtime.sharing?.groupId == group.id,
        sharingExpiresAtEpochMillis = runtime.sharing
            ?.takeIf { it.groupId == group.id }
            ?.expiresAtEpochMillis,
    )
}

internal fun lightGroupSheetVisibleAfterDismiss(initiallyVisible: Boolean): Boolean = false

internal fun lightGroupMemberStatusText(status: PresenceStatus?): String = when (status) {
    PresenceStatus.ONLINE -> "онлайн"
    PresenceStatus.STALE -> "старая"
    PresenceStatus.OFFLINE -> "оффлайн"
    null -> "нет текущей точки"
}

internal fun formatSharingExpiry(
    expiresAtEpochMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val dateTime = Instant.fromEpochMilliseconds(expiresAtEpochMillis).toLocalDateTime(timeZone)
    fun twoDigits(value: Int): String = value.toString().padStart(2, '0')
    return "${twoDigits(dateTime.day)}.${twoDigits(dateTime.monthNumber)}, ${twoDigits(dateTime.hour)}:${twoDigits(dateTime.minute)}"
}

/** Dismissing the presentation layer must not clear the app-scoped runtime. */
internal fun lightRuntimeShouldRemainSelectedAfterDismiss(): Boolean = true
