package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.nearby.*
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

/** Negative intent only. No authentication credential, position or telemetry. */
internal data class ChannelSharingRevokeIntent(val actorId: String,val tag: ChannelSharingTag,val requestId: String) {
    init {invitationDraftId(actorId);invitationDraftId(requestId)}
}
internal interface ChannelSharingRevokeJournal {
    suspend fun record(intent: ChannelSharingRevokeIntent): ChannelSharingRevokeIntent
    suspend fun pending(actorId: String,limit: Int): List<ChannelSharingRevokeIntent>
    suspend fun acknowledge(intent: ChannelSharingRevokeIntent,authenticatedActorId: String,reply: ChannelSharingReply): Boolean
}
