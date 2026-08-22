package ru.sodovaya.volty.backend

import io.livekit.server.AccessToken
import io.livekit.server.CanPublish
import io.livekit.server.CanPublishSources
import io.livekit.server.CanSubscribe
import io.livekit.server.RoomJoin
import io.livekit.server.RoomName
import java.util.Date

class VoiceService(private val config: AppConfig) {
    fun providerResponse(): VoiceProviderResponse {
        val liveKit = config.liveKitConfigOrNull()
        return if (liveKit != null) {
            VoiceProviderResponse(
                available = true,
                provider = "livekit",
                serverUrl = liveKit.serverUrl,
            )
        } else {
            VoiceProviderResponse(
                available = false,
                provider = config.voiceProvider,
                message = "SFU is not configured",
            )
        }
    }

    fun issueJoin(user: UserRecord, groupId: String, nowEpochMillis: Long = nowMillis()): VoiceJoinResponse {
        val liveKit = config.liveKitConfigOrNull()
            ?: throw ApiException(
                io.ktor.http.HttpStatusCode.NotImplemented,
                "voice_provider_unconfigured",
                "Voice provider is not configured; no audio session was created",
            )

        val roomId = roomIdForGroup(groupId)
        val expiresAtEpochMillis = nowEpochMillis + liveKit.tokenTtlSeconds * 1_000
        val participantToken = AccessToken(liveKit.apiKey, liveKit.apiSecret).apply {
            identity = user.id
            name = user.displayName
            expiration = Date(expiresAtEpochMillis)
            addGrants(
                RoomJoin(true),
                RoomName(roomId),
                CanPublish(true),
                CanPublishSources(listOf("microphone")),
                CanSubscribe(true),
            )
        }.toJwt()

        return VoiceJoinResponse(
            provider = "livekit",
            serverUrl = liveKit.serverUrl,
            roomId = roomId,
            participantToken = participantToken,
            expiresAtEpochMillis = expiresAtEpochMillis,
        )
    }

    private fun roomIdForGroup(groupId: String): String = "volty-group-$groupId"
}
