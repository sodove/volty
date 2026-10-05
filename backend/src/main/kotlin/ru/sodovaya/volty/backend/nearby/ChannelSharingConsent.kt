package ru.sodovaya.volty.backend.nearby

enum class ChannelSharingMode { LOCATION, RIDE, FULL }
enum class ChannelSharingTtl(val millis: Long) { HALF_HOUR(1_800_000), TWO_HOURS(7_200_000), EIGHT_HOURS(28_800_000) }
enum class ChannelSharingPhase { ACTIVE, REVOKED, EXPIRED }
data class ChannelSharingConsent(val actorId: String, val sessionId: String, val generation: String,
    val phase: ChannelSharingPhase, val mode: ChannelSharingMode?, val deadlineEpochMillis: Long?)
